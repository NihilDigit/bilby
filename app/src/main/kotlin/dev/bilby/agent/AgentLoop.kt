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
                emit(AgentEvent.Failed(it.message ?: "LLM 请求失败"))
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
                val blocks = toBlocks(text, seenBvids, traceByBvid)
                if (blocks.isNotEmpty()) {
                    emit(AgentEvent.Answer(blocks))
                    return@flow
                }
                // 正文里一条能用的引用都没有,才是真的没结果。finish_reason 是这里唯一能分辨
                // "模型说完了"和"被截断/流没收全"的东西,不留下就无从归因。
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
            prepared.forEach { (_, tool, arguments) ->
                if (tool != null) emit(AgentEvent.ToolStarted(tool.label(arguments)))
            }

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

            prepared.forEachIndexed { index, (call, tool, arguments) ->
                val result = results[index]
                seenBvids += result.bvids
                result.forUi.forEach { traceByBvid[it.bvid] = it }
                // 按 bvid 去重:同一个工具返回里重复出现同一条是常事(搜索结果里的重复投稿、
                // 合集列表里的同一集),而 UI 拿它当 LazyRow 的 key,重复会直接崩。
                // 去重放在产出侧一处,不放在每个消费方 —— 漏一处就是一次崩溃。
                if (tool != null) {
                    emit(AgentEvent.ToolFinished(tool.label(arguments), result.forUi.distinctBy { it.bvid }))
                }

                messages += ChatMessage(
                    role = ChatMessage.ROLE_TOOL,
                    toolCallId = call.id,
                    name = call.function.name,
                    content = result.forModel,
                )
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
     * 把带 `[[bvid]]` 引用的散文切成块。三条硬规矩全部落在这里,**丢引用不丢文字** ——
     * 引用被丢掉时只是少一张卡片,前后的句子照常显示,读起来不会断。
     *
     *   - 规矩 2:不在工具返回过的集合里的 bvid 丢掉(模型编不出视频)。
     *   - 规矩 3:卡片数量由代码定,超出上限的引用丢掉。
     *   - 同一个 bvid 只出卡片一次:模型回指前文时(「刚才那条 [[BV1xx]]」)不该再来一张。
     */
    private fun toBlocks(
        answer: String,
        seenBvids: Set<String>,
        traceByBvid: Map<String, TraceItem>,
    ): List<AnswerBlock> {
        val blocks = mutableListOf<AnswerBlock>()
        val used = mutableSetOf<String>()

        // 文字先攒在缓冲里,只有真的要插卡片时才切块。被丢掉的引用**连同标记一起**从正文里
        // 抹掉,而它两侧的句子仍属于同一段 —— 否则一个被丢的引用会把一句话劈成两块。
        val text = StringBuilder()
        var cursor = 0

        fun flushText() {
            text.toString().trim().takeIf { it.isNotEmpty() }?.let { blocks += AnswerBlock.Text(it) }
            text.clear()
        }

        for (match in VIDEO_REF.findAll(answer)) {
            val bvid = match.groupValues[1]
            text.append(answer, cursor, match.range.first)
            cursor = match.range.last + 1

            val keep = when {
                bvid !in seenBvids -> {
                    BiliLog.w("助理引用了工具没返回过的视频,已丢弃:$bvid")
                    false
                }
                bvid in used -> false
                used.size >= MAX_RESULTS -> {
                    BiliLog.w("助理引用的视频超过 $MAX_RESULTS 条,多余的已丢弃:$bvid")
                    false
                }
                else -> true
            }
            if (!keep) continue

            flushText()
            blocks += AnswerBlock.Video(bvid, traceByBvid[bvid])
            used += bvid
        }
        text.append(answer, cursor, answer.length)
        flushText()

        return blocks
    }

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
         * `[[BV1xx4y1x7xx]]` 形式的行内引用。bvid 的字符集是 base58,这里不收窄,交给白名单校验。
         *
         * **紧贴引用的强调记号连同引用一起吃掉。** 模型很爱写 `**[[BV1xx]]**`,而切块发生在
         * markdown 解析之前:开头那个 `**` 会落在前一个文字块的末尾、结尾那个落在后一个块的
         * 开头,两边各剩半个记号,配不上对就原样印在正文里(真机上就是这样露出来的)。
         *
         * 不要求两侧对称:紧贴引用的记号,配对对象要么是这个引用本身(卡片已经够突出,加粗
         * 对它没有意义),要么已经跨到别的块去了(跨块的强调本来也渲染不出来)。两种情况丢掉
         * 都比留一个裸 `**` 好。
         */
        val VIDEO_REF = Regex("""\*{0,3}\[\[(BV[0-9A-Za-z]+)]]\*{0,3}""")

        /**
         * 朴素表达任务即可。防沉迷由结构承担,不由这段文字承担(DESIGN 3.1);
         * 这里写再多"不要让用户上瘾"也不构成任何保证。
         */
        const val SYSTEM_PROMPT = """
你是一个 B 站内容检索助手。给定用户的意图和你用工具查到的候选,挑出真正相关的几条。

要求:
- 用工具去查,不要凭记忆回答。热评往往能反映内容质量,值得翻。
- 去重,不凑数。宁可少给几条,也不要塞进不相关的。
- 只能提到你在工具返回里真实见过的视频。
- 查完就直接回答。不要再调工具,回答本身就是终点。

**回答写一段自然的话,把视频用 `[[bvid]]` 写在句子里。** 引用会被渲染成一张可点的
视频卡片,所以不要重复卡片上已有的信息(标题、UP主、播放量、时长),把力气花在
卡片上没有的东西:它讲了什么、适合什么情况看、热评里提到的具体评价。

例:
「想从零开始的话,[[BV1xx4y1x7xx]] 把推导过程完整走了一遍,没有跳步;
评论里不少人提到第 12 分钟那段例子是关键。已经有基础可以直接跳到
[[BV1yy4y1y7yy]],它默认你知道前置概念,节奏快很多。」

用户问的是问题(比如「这几个的评价怎么样」)时,不引用任何视频、直接把答案写成
一段话也是对的 —— 强行凑几个视频出来只会让回答变差。

`[[bvid]]` 不要加粗、不要包在任何记号里 —— 它会变成一张卡片,记号对它没有意义。

排版只认这几种记号:`**加粗**`、`*斜体*`、`` `行内代码` ``、`- ` 无序列表、
`1. ` 有序列表、`#` 到 `###` 的小标题。表格、代码块、链接、图片一律不要用,
它们不会被渲染,记号会原样印在答案里。
"""
    }
}
