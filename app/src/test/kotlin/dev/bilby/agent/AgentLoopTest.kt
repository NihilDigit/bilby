package dev.bilby.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只测 DESIGN 3.3 的三条硬规矩,每条一个用例。它们的共同特点是:被违反时不会抛异常、
 * 不会有日志,只会安静地多给几条或给出不存在的视频 —— 所以必须有回归。
 * 不测循环的复述逻辑。
 */
class AgentLoopTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `到达步数上限仍不交卷则失败,而不是继续检索`() = runTest {
        val alwaysSearches = FakeStreamer { _ -> toolCall("search_videos", """{"kw":"x"}""") }
        val tool = FakeTool(name = "search_videos", bvids = setOf("BV1"))
        val events = AgentLoop(alwaysSearches, ToolRegistry(listOf(tool)), json)
            .run(AgentIntent.Query("随便"))
            .toList()

        assertTrue(events.last() is AgentEvent.Failed)
        // 12 步上限:第 13 轮只给交卷工具,模型仍不交卷即失败,工具最多被执行 12 次。
        assertEquals(12, tool.executions)
    }

    @Test
    fun `工具没返回过的 bvid 会被丢弃`() = runTest {
        val fabricates = FakeStreamer { messages ->
            if (messages.none { it.role == ChatMessage.ROLE_TOOL }) toolCall("search_videos", """{"kw":"x"}""")
            else toolCall("submit_answer", """{"items":[{"bvid":"BV_REAL","reason":"真的"},{"bvid":"BV_FAKE","reason":"编的"}]}""")
        }
        val events = AgentLoop(fabricates, ToolRegistry(listOf(FakeTool("search_videos", setOf("BV_REAL")))), json)
            .run(AgentIntent.Query("随便"))
            .toList()

        val answer = events.filterIsInstance<AgentEvent.Answer>().single()
        assertEquals(listOf("BV_REAL"), answer.items.map { it.bvid })
    }

    @Test
    fun `模型给再多条也只取硬编码的上限`() = runTest {
        val all = (1..8).map { "BV$it" }
        val greedy = FakeStreamer { messages ->
            if (messages.none { it.role == ChatMessage.ROLE_TOOL }) toolCall("search_videos", """{"kw":"x"}""")
            else toolCall(
                "submit_answer",
                """{"items":[${all.joinToString(",") { """{"bvid":"$it","reason":"r"}""" }}]}""",
            )
        }
        val events = AgentLoop(greedy, ToolRegistry(listOf(FakeTool("search_videos", all.toSet()))), json)
            .run(AgentIntent.Query("随便"))
            .toList()

        assertEquals(5, events.filterIsInstance<AgentEvent.Answer>().single().items.size)
    }

    @Test
    fun `交卷后的对话记录里每个 tool_call 都有对应的 tool 响应`() = runTest {
        // 协议要求带 tool_calls 的 assistant 消息后面必须跟上对每个 tool_call_id 的响应。
        // 缺了在单轮时看不出来,下一轮把这段历史发回去就是 400。
        val answers = FakeStreamer { messages ->
            if (messages.none { it.role == ChatMessage.ROLE_TOOL }) toolCall("search_videos", """{"kw":"x"}""")
            else toolCall("submit_answer", """{"items":[{"bvid":"BV1","reason":"r"}]}""")
        }
        var transcript: List<ChatMessage> = emptyList()
        AgentLoop(answers, ToolRegistry(listOf(FakeTool("search_videos", setOf("BV1")))), json)
            .run(AgentIntent.Query("随便"), onTurnComplete = { messages, _, _ -> transcript = messages })
            .toList()

        val pendingCallIds = transcript
            .flatMap { it.toolCalls.orEmpty() }
            .map { it.id }
            .toSet() - transcript.mapNotNull { it.toolCallId }.toSet()
        assertEquals(emptySet<String>(), pendingCallIds)
    }

    private fun toolCall(name: String, arguments: String): List<LlmDelta> = listOf(
        LlmDelta.ToolCalls(listOf(ToolCall(id = name, function = FunctionCall(name, arguments)))),
        LlmDelta.Done("tool_calls"),
    )

    private class FakeStreamer(private val respond: (List<ChatMessage>) -> List<LlmDelta>) : LlmStreamer {
        override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<LlmDelta> =
            flowOf(*respond(messages).toTypedArray())
    }

    private class FakeTool(override val name: String, private val bvids: Set<String>) : Tool {
        var executions = 0
            private set

        override val description = "fake"
        override val parameters: JsonObject = JsonObject(emptyMap())

        override suspend fun execute(arguments: JsonObject): ToolResult {
            executions++
            return ToolResult(forModel = bvids.joinToString(), bvids = bvids)
        }
    }
}
