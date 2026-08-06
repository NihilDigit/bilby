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
import kotlinx.serialization.json.jsonPrimitive

/**
 * 循环本体,以及 DESIGN 3.3 的三条硬规矩所在地。三条都在代码里,**不在 prompt 里** ——
 * prompt 是请求,代码是保证;模型不遵守 prompt 时没有任何征兆。
 *
 *   1. 步数上限:到限强制交卷,不再给数据工具。
 *   2. 溯源校验:答案里的 bvid 必须在本轮工具返回过的集合内,否则丢弃。模型编不出视频。
 *   3. 条数硬编码:模型只排序,不决定给多少。
 *
 * 答案不解析自由文本,而是要求模型调 submit_answer —— 这样三条规矩全落在同一个入口上,
 * 模型没有第二条输出结论的路径。
 */
class AgentLoop(
    private val llm: LlmStreamer,
    private val tools: ToolRegistry,
    private val json: Json,
) {

    /**
     * @param history 同一会话之前的对话(DESIGN 3.1 修订:会话内多轮)。**只含本会话的
     *   对话与工具返回,永不含观看历史** —— 那条约束(3.3 第 4 条)不因多轮而放松。
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
        val turnStart = if (history.isEmpty()) {
            listOf(ChatMessage(role = ChatMessage.ROLE_SYSTEM, content = SYSTEM_PROMPT))
        } else {
            emptyList()
        }
        val messages = (turnStart + history).toMutableList().apply {
            add(ChatMessage(role = ChatMessage.ROLE_USER, content = intent.toPrompt()))
        }
        // 新消息从这里开始(本轮的用户输入也算),用于回传给持久化层
        val newFrom = messages.size - 1

        var step = 0
        try {
        while (true) {
            val lastStep = step >= MAX_TOOL_STEPS
            if (lastStep) {
                // 规矩 1:到限只留交卷工具,模型没有继续检索的选项。
                messages += ChatMessage(
                    role = ChatMessage.ROLE_USER,
                    content = "已达检索步数上限,现在必须用 submit_answer 交卷,只用已经看过的候选。",
                )
            }
            val available = if (lastStep) listOf(submitAnswerSpec()) else tools.specs + submitAnswerSpec()

            val deltas = runCatching { llm.stream(messages, available).toList() }.getOrElse {
                BiliLog.w("LLM 请求失败", it)
                emit(AgentEvent.Failed(it.message ?: "LLM 请求失败"))
                return@flow
            }

            val text = deltas.filterIsInstance<LlmDelta.Text>().joinToString("") { it.text }
            if (text.isNotBlank()) emit(AgentEvent.Thinking(text))

            val calls = deltas.filterIsInstance<LlmDelta.ToolCalls>().flatMap { it.calls }
            if (calls.isEmpty()) {
                emit(AgentEvent.Failed("助理没有给出结果"))
                return@flow
            }

            messages += ChatMessage(role = ChatMessage.ROLE_ASSISTANT, content = text.ifBlank { null }, toolCalls = calls)

            val answerCall = calls.firstOrNull { it.function.name == SUBMIT_ANSWER }
            if (answerCall != null) {
                // 协议要求:带 tool_calls 的 assistant 消息后面必须跟上对**每个** tool_call_id
                // 的 tool 消息。交卷时直接 return 会在历史里留下一段残缺对话,单轮看不出来,
                // 下一轮把它发回去就是 400。同批次里没执行的其他调用也要一并回。
                messages += calls.map { call ->
                    ChatMessage(
                        role = ChatMessage.ROLE_TOOL,
                        toolCallId = call.id,
                        name = call.function.name,
                        content = if (call.id == answerCall.id) "已记录" else "本轮已交卷,该调用未执行",
                    )
                }
                emit(finalAnswer(answerCall, seenBvids, traceByBvid))
                return@flow
            }
            if (lastStep) {
                // 到限还在检索就是不交卷。继续放行等于步数上限不存在。
                messages += calls.map { call ->
                    ChatMessage(
                        role = ChatMessage.ROLE_TOOL,
                        toolCallId = call.id,
                        name = call.function.name,
                        content = "已达检索上限,未执行",
                    )
                }
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
                if (tool != null) emit(AgentEvent.ToolFinished(tool.label(arguments), result.forUi))

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

    private fun finalAnswer(
        call: ToolCall,
        seenBvids: Set<String>,
        traceByBvid: Map<String, TraceItem>,
    ): AgentEvent {
        val arguments = runCatching { json.parseToJsonElement(call.function.arguments).jsonObject }
            .getOrElse {
                BiliLog.w("submit_answer 参数解析失败", it)
                return AgentEvent.Failed("助理交回的结果无法解析")
            }

        val summary = arguments["summary"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

        val parsed = runCatching {
            arguments["items"]?.jsonArray.orEmpty().map { element ->
                val obj = element.jsonObject
                AnswerItem(
                    bvid = obj["bvid"]!!.jsonPrimitive.content,
                    reason = obj["reason"]?.jsonPrimitive?.content.orEmpty(),
                    trace = null,
                )
            }
        }.getOrElse {
            BiliLog.w("submit_answer 的 items 解析失败", it)
            emptyList()
        }

        // 规矩 2:不在本轮工具返回过的 bvid 一律丢弃。
        val verified = parsed
            .filter { it.bvid in seenBvids }
            .distinctBy { it.bvid }
            .map { it.copy(trace = traceByBvid[it.bvid]) }

        // 纯文本回答是合法的:用户问"这几个评价如何"时,答案本来就不是一串视频。
        if (verified.isEmpty() && summary == null) return AgentEvent.Failed("没有找到确实相关的结果")

        // 规矩 3:条数由代码定,模型只负责排序。
        return AgentEvent.Answer(summary, verified.take(MAX_RESULTS))
    }

    private fun AgentIntent.toPrompt(): String = when (this) {
        is AgentIntent.Query -> "用户想找:$text"
        is AgentIntent.Related -> buildString {
            append("用户正在看《$title》(UP:$upName,bvid:$bvid),想找相关的。")
            note?.takeIf { it.isNotBlank() }?.let { append("用户补充:$it") }
        }
    }

    private fun submitAnswerSpec(): ToolSpec = ToolSpec(
        function = FunctionSpec(
            name = SUBMIT_ANSWER,
            description = "交卷。只能提交你在工具返回里真实见过的视频,给出每条为什么相关。",
            parameters = json.parseToJsonElement(SUBMIT_ANSWER_SCHEMA),
        ),
    )

    private companion object {
        const val MAX_TOOL_STEPS = 12
        const val MAX_RESULTS = 5
        const val SUBMIT_ANSWER = "submit_answer"

        const val SUBMIT_ANSWER_SCHEMA = """
{
  "type": "object",
  "properties": {
    "summary": {
      "type": "string",
      "description": "面向用户的回答正文。用户问的是问题(如「这几个评价怎么样」)时,答案写在这里,items 可以为空;用户要的是找视频时,这里可以留空或写一句总览。"
    },
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "bvid": { "type": "string" },
          "reason": {
            "type": "string",
            "description": "面向用户的一两句介绍(**不超过 60 字**):这个视频讲什么、有什么值得看的地方。写内容本身,不要写你是怎么找到它的,也不要出现「同一UP主」「同属该系列」这类检索理由。更长的说明写进 summary。"
          }
        },
        "required": ["bvid"]
      }
    }
  },
  "required": ["items"]
}
"""

        /**
         * 朴素表达任务即可。防沉迷由结构承担,不由这段文字承担(DESIGN 3.1);
         * 这里写再多"不要让用户上瘾"也不构成任何保证。
         */
        const val SYSTEM_PROMPT = """
你是一个 B 站内容检索助手。给定用户的意图和你用工具查到的候选,挑出真正相关的几条。

要求:
- 用工具去查,不要凭记忆回答。热评往往能反映内容质量,值得翻。
- 去重,不凑数。宁可少给几条,也不要塞进不相关的。
- 只能提交你在工具返回里真实见过的视频。
- 查完后调用 submit_answer 交卷。

交卷时每条要写一两句**给用户看的介绍**:这个视频讲了什么、有什么值得看的地方,
必要时可以引用热评里的具体评价。**不要写你的检索理由** —— 用户不关心它是不是
「同一个 UP 主」或「同属一个系列」,那是你判断相关性的依据,不是他决定点不点开的依据。
"""
    }
}
