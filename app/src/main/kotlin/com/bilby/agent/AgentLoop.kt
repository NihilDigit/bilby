package com.bilby.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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

    fun run(intent: AgentIntent): Flow<AgentEvent> = flow {
        val seenBvids = mutableSetOf<String>()
        val traceByBvid = mutableMapOf<String, TraceItem>()
        val messages = mutableListOf(
            ChatMessage(role = ChatMessage.ROLE_SYSTEM, content = SYSTEM_PROMPT),
            ChatMessage(role = ChatMessage.ROLE_USER, content = intent.toPrompt()),
        )

        var step = 0
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
                emit(finalAnswer(answerCall, seenBvids, traceByBvid))
                return@flow
            }
            if (lastStep) {
                // 到限还在检索就是不交卷。继续放行等于步数上限不存在。
                emit(AgentEvent.Failed("助理到达检索上限仍未给出结果"))
                return@flow
            }

            for (call in calls) {
                val tool = tools[call.function.name]
                if (tool == null) {
                    messages += ChatMessage(
                        role = ChatMessage.ROLE_TOOL,
                        toolCallId = call.id,
                        name = call.function.name,
                        content = "没有这个工具",
                    )
                    continue
                }

                val arguments = runCatching { json.parseToJsonElement(call.function.arguments).jsonObject }
                    .getOrElse { buildJsonObject { } }

                emit(AgentEvent.ToolStarted(tool.label(arguments)))
                val result = runCatching { tool.execute(arguments) }
                    .getOrElse { ToolResult(forModel = "工具执行失败: ${it.message}") }

                seenBvids += result.bvids
                result.forUi.forEach { traceByBvid[it.bvid] = it }
                emit(AgentEvent.ToolFinished(tool.label(arguments), result.forUi))

                messages += ChatMessage(
                    role = ChatMessage.ROLE_TOOL,
                    toolCallId = call.id,
                    name = tool.name,
                    content = result.forModel,
                )
            }
            step++
        }
    }

    private fun finalAnswer(
        call: ToolCall,
        seenBvids: Set<String>,
        traceByBvid: Map<String, TraceItem>,
    ): AgentEvent {
        val parsed = runCatching {
            json.parseToJsonElement(call.function.arguments).jsonObject["items"]!!.jsonArray.map { element ->
                val obj = element.jsonObject
                AnswerItem(
                    bvid = obj["bvid"]!!.jsonPrimitive.content,
                    reason = obj["reason"]?.jsonPrimitive?.content.orEmpty(),
                    trace = null,
                )
            }
        }.getOrElse { return AgentEvent.Failed("助理交回的结果无法解析") }

        // 规矩 2:不在本轮工具返回过的 bvid 一律丢弃。
        val verified = parsed
            .filter { it.bvid in seenBvids }
            .distinctBy { it.bvid }
            .map { it.copy(trace = traceByBvid[it.bvid]) }

        if (verified.isEmpty()) return AgentEvent.Failed("没有找到确实相关的结果")

        // 规矩 3:条数由代码定,模型只负责排序。
        return AgentEvent.Answer(verified.take(MAX_RESULTS))
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
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "bvid": { "type": "string" },
          "reason": { "type": "string", "description": "一句话说明为什么这条符合用户的意图" }
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
"""
    }
}
