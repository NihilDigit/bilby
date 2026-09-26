package dev.bilby.agent

import dev.bilby.BiliLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/**
 * 循环本体,以及 DESIGN 3.3 的三条硬规矩所在地。三条都在代码里,**不在 prompt 里** ——
 * prompt 是请求,代码是保证;模型不遵守 prompt 时没有任何征兆。
 *
 *   1. 步数上限:到限把工具撤走,再调工具就判失败 —— 撤走是让它不必违规,判失败是它违规了也走不下去。
 *   2. 溯源校验:答案里的 bvid 必须在本轮工具返回过的集合内,否则丢弃。模型编不出视频。
 *   3. 条数硬编码:模型只排序,不决定给多少。
 *
 * **模型每轮只有两种输出:调工具,或者回话。回话即终局。** 这里曾经要求它改调一个
 * submit_answer 交卷,理由是"把三条规矩收在同一个入口"。那个理由不成立:规矩 2、3 都在
 * [toBlocks] 里,而 [toBlocks] 吃的本来就是散文,工具参数只是把同一段散文换了个位置装。
 * 代价却是实打实的——模型说完就停时(finish_reason=stop)整轮判失败,而 tool_choice
 * 也堵不住:思考模型直接拒掉 "required"。
 */
class AgentLoop(
    private val llm: LlmStreamer,
    private val tools: ToolRegistry,
    private val json: Json,
) {

    /**
     * @param history 同一会话之前的对话(DESIGN 3.1 修订:会话内多轮)。**只含本会话的
     *   对话与工具返回,永不含观看历史** —— 那条约束(3.3 第 4 条)不因多轮而放松。
     *   不含 system 消息,那条每轮在这里现拼。
     * @param seenBvids 本会话此前工具返回过的 bvid。多轮时溯源校验的白名单要跨轮累积,
     *   否则用户追问"刚才第二个怎么样"时,模型重提上一轮的视频会被当成编造的丢掉。
     */
    fun run(
        intent: AgentIntent,
        history: List<ChatMessage> = emptyList(),
        priorBvids: Set<String> = emptySet(),
        // 上一轮工具返回过的视频展示信息(标题/封面/UP)。同样要跨轮累积:追问上一轮的
        // 视频时若查不到展示信息,卡片就只剩一个裸 bvid。
        priorTraces: Map<String, TraceItem> = emptyMap(),
        // 本轮新产生的消息与累积的状态,交给调用方落库。不做成事件是因为它属于持久化
        // 关注点,混进 UI 事件流里每个消费方都要处理一个自己用不上的分支。
        onTurnComplete: (
            newMessages: List<ChatMessage>,
            seenBvids: Set<String>,
            traces: Map<String, TraceItem>,
        ) -> Unit = { _, _, _ -> },
    ): Flow<AgentEvent> = flow {
        val seenBvids = priorBvids.toMutableSet()
        val traceByBvid = priorTraces.toMutableMap()
        // 过程里的名字(见 [NameBook])。视频标题取自 traceByBvid,UP 名与合集标题只在本轮攒:
        // 它们只服务于过程的文字,不值得跟着会话落库。
        val upNames = mutableMapOf<Long, String>()
        val collectionNames = mutableMapOf<Long, String>()
        fun nameBook() = NameBook(traceByBvid.mapValues { it.value.title }, upNames, collectionNames)
        var nextStepId = 0
        // **每一轮都自己把 system 拼上,不指望 history 里存着一份。** 回传给调用方的是
        // `drop(newFrom)`,起点在本轮的用户输入上,system 从来就没进过 history —— 只在
        // 首轮插一次的写法于是让第二轮起完全没有 system 消息,[[bvid]] 的引用格式和溯源
        // 约束都只写在里面。
        val messages = mutableListOf(ChatMessage(role = ChatMessage.ROLE_SYSTEM, content = SYSTEM_PROMPT))
        messages += history
        messages += ChatMessage(role = ChatMessage.ROLE_USER, content = intent.toPrompt())
        // 新消息从这里开始(本轮的用户输入也算),用于回传给持久化层
        val newFrom = messages.size - 1

        var step = 0
        try {
        while (true) {
            val lastStep = step >= MAX_TOOL_STEPS
            if (lastStep) {
                messages += ChatMessage(
                    role = ChatMessage.ROLE_USER,
                    content = "已达检索步数上限,现在直接回答,只用已经看过的候选。",
                )
            }
            // 规矩 1:到限就把工具撤走,模型没有继续检索的选项 —— 这是**结构上**没有,
            // 不是提示词劝住的。但撤走只是让它不必违规,并不保证它不违规(见下面那条守卫)。
            val available = if (lastStep) emptyList() else tools.specs

            val deltas = runCatching { llm.stream(messages, available).toList() }.getOrElse {
                BiliLog.w("LLM 请求失败", it)
                // **异常原文不上屏。** 这一句原先是 `it.message`,于是搜索结果下面会出现
                // 「Unable to resolve host "api.example.com"」这种话:读到它的人拿它做不了
                // 任何事,而原文和堆栈上面那行 BiliLog 已经收了。分档与 `ui/ErrorText.kt`
                // 的三句对齐(这条链路没有 B 站登录态可言,所以只有网络与被拒两档);
                // 这里写字面量而不是取资源 id,是因为 [AgentEvent.Failed] 带的是字符串,
                // 换成 StringResource 要一起动 AgentEvent 与 AgentTurn。
                emit(AgentEvent.Failed(if (it is IOException) "网络不可用" else "请求被拒绝"))
                return@flow
            }

            val text = deltas.filterIsInstance<LlmDelta.Text>().joinToString("") { it.text }
            val calls = deltas.filterIsInstance<LlmDelta.ToolCalls>().flatMap { it.calls }

            // 模型每一轮只有两种可能:调工具,或者回话。**回话就是终局**,不需要再有一个
            // submit_answer 把它包一层。
            //
            // 那层封装原先是失败的来源:答案自始至终是"带 [[bvid]] 引用的散文"(见 [toBlocks]),
            // 写在 content 里和写在工具参数里内容完全一样,却要求模型必须选后者。模型没有
            // 义务遵守——deepseek-v4-flash 这类思考模型说完就停(finish_reason=stop),而协议上
            // 也堵不住它:tool_choice:"required" 直接被拒,返回 400
            // "Thinking mode does not support this tool_choice"。
            if (calls.isEmpty()) {
                val answer = toAnswer(text, seenBvids, traceByBvid)
                // 纯文字的回答是合法的(问的是问题,不是找视频),所以只要正文不空就算交卷。
                if (answer.text.isNotBlank() || answer.sources.isNotEmpty()) {
                    emit(AgentEvent.Answer(answer))
                    return@flow
                }
                // 回话是空的才是真的没结果。finish_reason 是这里唯一能分辨"模型说完了"和
                // "被截断/流没收全"的东西,不留下就无从归因。
                val finish = deltas.filterIsInstance<LlmDelta.Done>().lastOrNull()?.finishReason
                BiliLog.w(
                    "助理没交卷也没可用引用: finish_reason=$finish 正文${text.length}字 " +
                        "第${step}步 历史${messages.size}条 增量${deltas.size}片",
                )
                emit(AgentEvent.Failed("助理没有给出结果"))
                return@flow
            }

            // 正文与工具调用同在时,正文是过程解说(DESIGN 3.4 的过程直播),不是答案。
            if (text.isNotBlank()) emit(AgentEvent.Thinking(text))

            messages += ChatMessage(role = ChatMessage.ROLE_ASSISTANT, content = text.ifBlank { null }, toolCalls = calls)

            // 到限了还在调工具。撤走 tools 已经让这件事不该发生,但"不该发生"不等于
            // "不会发生" —— 模型照样能凭上一轮的记忆再发一个调用,而这正是规矩 1 存在的理由:
            // 步数上限必须由代码保证,不能靠对面守规矩。少了这一段,lastStep 会一直为真,
            // 循环永远出不去。
            //
            // 协议要求:带 tool_calls 的 assistant 消息后面必须跟上对每个 id 的 tool 消息,
            // 缺一条,这段对话下一轮发回去就是 400。
            if (lastStep) {
                messages += calls.map { call ->
                    ChatMessage(
                        role = ChatMessage.ROLE_TOOL,
                        toolCallId = call.id,
                        name = call.function.name,
                        content = "已达检索上限,未执行",
                    )
                }
                BiliLog.w("助理到检索上限仍在调工具: ${calls.joinToString { it.function.name }}")
                emit(AgentEvent.Failed("助理到达检索上限仍未给出结果"))
                return@flow
            }

            // 模型一轮可以要求多个工具(读三个视频的热评就是三个调用)。串行执行等于把
            // 三次网络往返排队,是等待时间的主要来源;并发跑,结果按原顺序回填。
            val prepared = calls.map { call ->
                val tool = tools[call.function.name]
                val arguments = runCatching { json.parseToJsonElement(call.function.arguments).jsonObject }
                    .getOrElse { buildJsonObject { } }
                Triple(call, tool, arguments)
            }
            // 同一轮里对同一个工具的调用合成过程里的一步(见 [Tool.step]),按第一次出现的顺序排。
            val steps = prepared.indices
                .filter { prepared[it].second != null }
                .groupBy { prepared[it].second!!.name }
                .values
                .map { indices -> ProcessStep(nextStepId++, prepared[indices.first()].second!!, indices) }
            fun ProcessStep.text() = tool.step(indices.map { tool.subject(prepared[it].third, nameBook()) })
            steps.forEach { emit(AgentEvent.ToolStarted(it.id, it.tool.kind, it.text())) }

            val results = coroutineScope {
                prepared.map { (_, tool, arguments) ->
                    async {
                        if (tool == null) ToolResult(forModel = "没有这个工具")
                        else runCatching { tool.execute(arguments) }.getOrElse {
                            BiliLog.w("工具 ${tool.name} 执行失败", it)
                            ToolResult(forModel = "工具执行失败: ${it.message}")
                        }
                    }
                }.awaitAll()
            }

            prepared.forEachIndexed { index, (call, _, _) ->
                val result = results[index]
                seenBvids += result.bvids
                result.forUi.forEach { traceByBvid[it.bvid] = it }
                upNames += result.upNames
                collectionNames += result.collectionNames

                messages += ChatMessage(
                    role = ChatMessage.ROLE_TOOL,
                    toolCallId = call.id,
                    name = call.function.name,
                    content = result.forModel,
                )
            }
            // 结束时文字重算一次:这一轮刚认出的名字(合集标题、UP 名)能用上了。
            // 中间结果按 bvid 去重:同一个工具返回里重复出现同一条是常事(搜索结果里的重复投稿、
            // 合集列表里的同一集),几次调用合成一步时更是如此,而 UI 拿它当 LazyRow 的 key,
            // 重复会直接崩。去重放在产出侧一处,不放在每个消费方 —— 漏一处就是一次崩溃。
            steps.forEach { processStep ->
                val items = processStep.indices.flatMap { results[it].forUi }.distinctBy { it.bvid }
                emit(AgentEvent.ToolFinished(processStep.id, processStep.tool.kind, processStep.text(), items))
            }
            step++
        }
        } finally {
            // 无论正常交卷、失败还是被取消,本轮已经发生的对话都要落库:下一轮追问要接着它。
            // 落库前补齐缺失的 tool 响应 —— 中途被取消时工具还没跑完,留下的残缺对话
            // 会让下一轮请求被服务端拒掉(400 insufficient tool messages)。
            onTurnComplete(repairToolResponses(messages.drop(newFrom)), seenBvids, traceByBvid)
        }
    }

    /**
     * 保证每个带 tool_calls 的 assistant 消息后面都跟着对应的 tool 响应。缺的补一条占位,
     * 内容如实写明"未执行" —— 编一个假的工具结果会让模型下一轮基于不存在的数据推理。
     */
    private fun repairToolResponses(messages: List<ChatMessage>): List<ChatMessage> {
        val repaired = mutableListOf<ChatMessage>()
        for ((index, message) in messages.withIndex()) {
            repaired += message
            val calls = message.toolCalls ?: continue

            val answered = messages.drop(index + 1)
                .takeWhile { it.role == ChatMessage.ROLE_TOOL }
                .mapNotNull { it.toolCallId }
                .toSet()
            repaired += calls.filter { it.id !in answered }.map { call ->
                ChatMessage(
                    role = ChatMessage.ROLE_TOOL,
                    toolCallId = call.id,
                    name = call.function.name,
                    content = "未执行(本轮被中断)",
                )
            }
        }
        return repaired
    }


    /**
     * 把带 `[[bvid]]` 引用的回答整理成 [AgentAnswer]:引用换成角标,被引用的视频按第一次出现的
     * 顺序编号、列进出处。三条硬规矩全部落在这里,**丢引用不丢文字**:引用被丢掉时连标记一起
     * 抹掉,前后的句子照常显示。
     *
     *   - 规矩 2:不在工具返回过的集合里的 bvid 丢掉(模型编不出视频)。
     *   - 规矩 3:出处条数由代码定,超出上限的引用丢掉。
     *   - 同一个 bvid 只列一次:模型回指前文时(「刚才那条 [[BV1xx]]」)用同一个编号。
     */
    private fun toAnswer(
        answer: String,
        seenBvids: Set<String>,
        traceByBvid: Map<String, TraceItem>,
    ): AgentAnswer {
        val sources = mutableListOf<AnswerSource>()
        val numberOf = mutableMapOf<String, Int>()
        val text = VIDEO_REF.replace(answer) { match ->
            val bvid = match.groupValues[1]
            val number = numberOf[bvid] ?: when {
                bvid !in seenBvids -> {
                    BiliLog.w("助理引用了工具没返回过的视频,已丢弃:$bvid")
                    null
                }
                sources.size >= MAX_RESULTS -> {
                    BiliLog.w("助理引用的视频超过 $MAX_RESULTS 条,多余的已丢弃:$bvid")
                    null
                }
                else -> {
                    sources += AnswerSource(bvid, traceByBvid[bvid])
                    sources.size.also { numberOf[bvid] = it }
                }
            }
            if (number == null) "" else citation(number)
        }
        return AgentAnswer(text.trim(), sources)
    }

    /** 过程里的一步:同一轮里对 [tool] 的几次调用,[indices] 是它们在这一轮调用里的下标。 */
    private class ProcessStep(val id: Int, val tool: Tool, val indices: List<Int>)

    private fun AgentIntent.toPrompt(): String = when (this) {
        is AgentIntent.Query -> "用户想找:$text"
        is AgentIntent.Related -> buildString {
            append("用户正在看《$title》(UP:$upName,bvid:$bvid),想找相关的。")
            note?.takeIf { it.isNotBlank() }?.let { append("用户补充:$it") }
        }
    }

    private companion object {
        const val MAX_TOOL_STEPS = 12
        const val MAX_RESULTS = 5

        /**
         * `[[BV1xx4y1x7xx]]` 形式的引用。bvid 的字符集是 base58,这里不收窄,交给白名单校验。
         *
         * **紧贴引用的强调记号连同引用一起吃掉。** 模型很爱写 `**[[BV1xx]]**`,引用换成角标
         * 之后那两个 `**` 就包着一个角标;被丢掉的引用则只剩 `****`,配不上对就原样印在正文里
         * (真机上就是这样露出来的)。角标本身已经醒目,加粗对它没有意义。
         *
         * 这是兜底,不是主路:引用怎么写(紧贴句末、前面不留空格、最多几个)在 [SYSTEM_PROMPT]
         * 里写明,模型照着写就不需要这里再清洗。
         */
        val VIDEO_REF = Regex("""\*{0,3}\[\[(BV[0-9A-Za-z]+)]]\*{0,3}""")

        /**
         * 朴素表达任务即可。防沉迷由结构承担,不由这段文字承担(DESIGN 3.1);
         * 这里写再多"不要让用户上瘾"也不构成任何保证。
         */
        const val SYSTEM_PROMPT = """
你是一个 B 站内容助手。回答用户的问题;B 站视频是你回答的依据,也是用户接下来可以去看的地方。

做法:
- 用工具去查,不要凭记忆回答。热评往往能反映内容质量和观众的真实评价,值得翻。
- 只能引用你在工具返回里真实见过的视频。
- 查完就直接回答,回答本身就是终点。

先判断用户要什么:
- 输入是一个问题,就回答这个问题。
- 输入只是一个词或短语(一个名字、一个梗、一个话题),理解为「想看关于它的内容」:按它在 B 站
  最常见的含义去找,回答里说明这是什么、相关内容大致有哪几类、各自值得看的是哪些。不要把它当成
  「找一个叫这个名字的账号」,除非输入明确是在找人。
- 有歧义时按最常见的那种理解回答,最后用一句话提其他可能的含义。

回答怎么写:
- 先回答问题本身。用户问「是什么」「为什么」「哪个好」,就把答案写出来;用户要找视频,
  用一两句话说明找到的是什么、各自适合什么需求。
- 引用视频写成 `[[bvid]]`,放在它所支持的那句话的末尾、句末标点之前,**前面不留空格**
  (写「……讲得最清楚[[bvid]]。」)。它会显示成一个可点的上标角标,视频本身另外列在回答下面。
  不要把视频当句子成分写进话里(不要写「推荐 [[bvid]],它讲了……」),也不要重复标题、
  UP 主、播放量、时长。
- 全文最多引用 $MAX_RESULTS 个不同的视频,超出的不会显示。同一个视频再次提到时照写同一个
  `[[bvid]]`,不占名额。一句话只标一个最相关的出处,不要一连标好几个。
- 书面语,简洁。不寒暄,不写「看你想要哪种」「可以点进去看看」这类话,不用网络口语。
- 宁可少引,不凑数。问题本身用不着视频时,一个都不引也是对的。

例(用户问「线性代数怎么入门」):
「入门的关键是先建立几何直观,再学计算。系统性的课程里,有一套从向量的几何意义讲起、
不跳推导的系列最常被推荐 [[BV1xx4y1x7xx]];评论普遍认为第 3 集讲行列式的部分最有帮助。
已有基础、只想补计算的,可以直接看习题讲解 [[BV1yy4y1y7yy]]。」

排版只认这几种记号:`**加粗**`、`*斜体*`、`` `行内代码` ``、`- ` 无序列表、
`1. ` 有序列表、`#` 到 `###` 的小标题。表格、代码块、链接、图片一律不要用,
它们不会被渲染,记号会原样印在答案里。`[[bvid]]` 不要包在任何记号里。
"""
    }
}
