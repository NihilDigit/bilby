package dev.bilby.agent

import dev.bilby.data.LlmConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmClientTest {

    /** 与 [dev.bilby.AppContainer.json] 保持完全一致的序列化开关。 */
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val testConfig = LlmConfig(
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/",
        apiKey = "  test-placeholder-token  ",
        model = "  gemini-3.8-flash  ",
    )

    @Test
    fun `normalizeLlmBaseUrl 去掉尾斜杠与 chat completions 后缀并补齐协议头`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            normalizeLlmBaseUrl("  https://generativelanguage.googleapis.com/v1beta/openai/  "),
        )
        assertEquals(
            "https://api.deepseek.com/v1",
            normalizeLlmBaseUrl("https://api.deepseek.com/v1/chat/completions/"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/openai",
            normalizeLlmBaseUrl("generativelanguage.googleapis.com/v1beta/openai/"),
        )
        assertEquals(
            "http://localhost:11434/v1",
            normalizeLlmBaseUrl("localhost:11434/v1/"),
        )
        assertEquals("", normalizeLlmBaseUrl("   "))
    }

    @Test
    fun `Gemini 省略 index 的并行工具调用各自独立成条且保留 thought_signature`() = runTest {
        val sse = """
            data: {"choices":[{"delta":{"role":"assistant","tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_video","arguments":"{\"bvid\":\"BV1\"}"},"extra_content":{"google":{"thought_signature":"SIG_FIRST=="}}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"id":"call_2","type":"function","function":{"name":"get_video","arguments":"{\"bvid\":\"BV2\"}"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"id":"call_3","type":"function","function":{"name":"get_hot_comments","arguments":"{\"bvid\":\"BV1\"}"}}]},"finish_reason":"tool_calls"}]}

            data: [DONE]
        """.trimIndent()

        var requestedUrl = ""
        var authHeader = ""
        var requestedModel = ""
        val client = mockLlmClient { request ->
            requestedUrl = request.url.toString()
            authHeader = request.headers[HttpHeaders.Authorization].orEmpty()
            val root = json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            requestedModel = root["model"]!!.jsonPrimitive.content
            respondSse(sse)
        }

        val deltas = client.stream(
            messages = listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "查一下")),
            tools = emptyList(),
        ).toList()

        // 末尾斜杠被清掉,不会拼成 .../openai//chat/completions 触发 Google 404
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", requestedUrl)
        assertEquals("Bearer test-placeholder-token", authHeader)
        assertEquals("gemini-3.8-flash", requestedModel)

        val calls = deltas.filterIsInstance<LlmDelta.ToolCalls>().flatMap { it.calls }
        assertEquals(3, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("get_video", calls[0].function.name)
        assertEquals("""{"bvid":"BV1"}""", calls[0].function.arguments)
        assertEquals(
            "SIG_FIRST==",
            calls[0].extraContent?.get("google")?.jsonObject?.get("thought_signature")?.jsonPrimitive?.content,
        )

        assertEquals("call_2", calls[1].id)
        assertEquals("get_video", calls[1].function.name)
        assertEquals("""{"bvid":"BV2"}""", calls[1].function.arguments)
        assertNull(calls[1].extraContent)

        assertEquals("call_3", calls[2].id)
        assertEquals("get_hot_comments", calls[2].function.name)
        assertEquals("""{"bvid":"BV1"}""", calls[2].function.arguments)
        assertNull(calls[2].extraContent)
    }

    @Test
    fun `带 index 的分片工具参数与空串 id 续片不会被覆盖,流尾缺 finish_reason 也能刷出`() = runTest {
        val sse = """
            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a","type":"function","function":{"name":"search_videos","arguments":"{\"kw\":"}},{"index":1,"id":"call_b","type":"function","function":{"name":"get_video","arguments":"{\"bvid\":"}}]}}]}

            data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"","function":{"name":"","arguments":"\"lin\"}"}},{"index":1,"id":"","function":{"name":"","arguments":"\"BV9\"}"}}]}}]}

            data: [DONE]
        """.trimIndent()

        val client = mockLlmClient { respondSse(sse) }
        val calls = client.stream(
            messages = listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "x")),
            tools = emptyList(),
        ).toList().filterIsInstance<LlmDelta.ToolCalls>().flatMap { it.calls }

        assertEquals(2, calls.size)
        assertEquals("call_a", calls[0].id)
        assertEquals("search_videos", calls[0].function.name)
        assertEquals("""{"kw":"lin"}""", calls[0].function.arguments)
        assertEquals("call_b", calls[1].id)
        assertEquals("get_video", calls[1].function.name)
        assertEquals("""{"bvid":"BV9"}""", calls[1].function.arguments)
    }

    @Test
    fun `extra_content 为空时不序列化,非空时原样透传回请求体`() = runTest {
        val sig = buildJsonObject {
            put("google", buildJsonObject { put("thought_signature", JsonPrimitive("ROUNDTRIP_SIG==")) })
        }
        var capturedBody = ""
        val client = mockLlmClient { request ->
            capturedBody = request.body.toByteArray().decodeToString()
            respondSse("""data: {"choices":[{"delta":{"content":"好"},"finish_reason":"stop"}]}""")
        }

        client.stream(
            messages = listOf(
                ChatMessage(role = ChatMessage.ROLE_USER, content = "你好"),
                ChatMessage(
                    role = ChatMessage.ROLE_ASSISTANT,
                    toolCalls = listOf(
                        ToolCall(
                            id = "call_1",
                            function = FunctionCall("get_video", """{"bvid":"BV1"}"""),
                            extraContent = sig,
                        ),
                    ),
                ),
                ChatMessage(role = ChatMessage.ROLE_TOOL, toolCallId = "call_1", name = "get_video", content = "ok"),
            ),
            tools = emptyList(),
        ).toList()

        val root = json.parseToJsonElement(capturedBody).jsonObject
        val msgs = root["messages"]!!.jsonArray
        // 普通 user/tool 消息没有 extra_content 字段
        assertFalse("extra_content" in msgs[0].jsonObject)
        assertFalse("extra_content" in msgs[2].jsonObject)
        // assistant.tool_calls[0].extra_content.google.thought_signature 原样保留
        val sentCall = msgs[1].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals(
            "ROUNDTRIP_SIG==",
            sentCall["extra_content"]!!.jsonObject["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `过滤 Google thought 思考块并剥掉前导闭合标签,只把非 thought 元数据作为 ExtraContent 吐出`() = runTest {
        val sse = """
            data: {"choices":[{"delta":{"content":"<thought>先算一下 1+1","extra_content":{"google":{"thought":true}}}}]}

            data: {"choices":[{"delta":{"content":" 等于 2","extra_content":{"google":{"thought":true}}}}]}

            data: {"choices":[{"delta":{"content":"</thought>答案是 2"}}]}

            data: {"choices":[{"delta":{"content":"。","extra_content":{"google":{"thought_signature":"FINAL_SIG=="}}},"finish_reason":"stop"}]}

            data: [DONE]
        """.trimIndent()

        val client = mockLlmClient { respondSse(sse) }
        val deltas = client.stream(
            messages = listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "1+1")),
            tools = emptyList(),
        ).toList()

        val text = deltas.filterIsInstance<LlmDelta.Text>().joinToString("") { it.text }
        assertEquals("答案是 2。", text)

        val extras = deltas.filterIsInstance<LlmDelta.ExtraContent>()
        assertEquals(1, extras.size)
        assertEquals(
            "FINAL_SIG==",
            extras.single().content["google"]?.jsonObject?.get("thought_signature")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `503 与 429 一样触发退避重试`() = runTest {
        var calls = 0
        val client = mockLlmClient {
            calls++
            when (calls) {
                1 -> respond("overloaded", HttpStatusCode.ServiceUnavailable)
                2 -> respond("rate limited", HttpStatusCode.TooManyRequests)
                else -> respondSse("""data: {"choices":[{"delta":{"content":"恢复"},"finish_reason":"stop"}]}""")
            }
        }

        val deltas = client.stream(
            messages = listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "hi")),
            tools = emptyList(),
        ).toList()

        assertEquals(3, calls)
        assertEquals("恢复", deltas.filterIsInstance<LlmDelta.Text>().joinToString("") { it.text })
    }

    @Test
    fun `listModels 请求 models 端点并过滤非对话模态`() = runTest {
        var seenMethod: HttpMethod? = null
        var seenUrl = ""
        var seenAuth = ""
        val modelsJson = """
            {
              "data": [
                {"id": "models/gemini-3.8-flash", "display_name": "Gemini 3.8 Flash"},
                {"id": "models/gemini-3.1-pro-preview", "display_name": "Gemini 3.1 Pro"},
                {"id": "models/gemini-embedding-001", "display_name": "Embedding"},
                {"id": "models/gemini-2.5-flash-preview-tts", "display_name": "TTS"},
                {"id": "models/gemini-2.0-flash-preview-image-generation", "display_name": "Image"},
                {"id": "models/imagen-3.0-generate-002", "display_name": "Imagen"},
                {"id": "models/veo-2.0-generate-001", "display_name": "Veo"},
                {"id": "gpt-4o-mini-transcribe"},
                {"id": "models/lyria-realtime-exp"},
                {"id": "models/gemini-2.0-flash-live-001"},
                {"id": "models/aqa"},
                {"id": "deepseek-chat", "display_name": "DeepSeek Chat"}
              ]
            }
        """.trimIndent()

        val client = mockLlmClient { request ->
            seenMethod = request.method
            seenUrl = request.url.toString()
            seenAuth = request.headers[HttpHeaders.Authorization].orEmpty()
            respond(
                content = modelsJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = client.listModels(
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions/",
            apiKey = "  unit-test-token  ",
        )

        assertTrue(result.isSuccess)
        assertEquals(HttpMethod.Get, seenMethod)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/models", seenUrl)
        assertEquals("Bearer unit-test-token", seenAuth)
        assertEquals(
            listOf(
                LlmModelInfo("models/gemini-3.8-flash", "Gemini 3.8 Flash"),
                LlmModelInfo("models/gemini-3.1-pro-preview", "Gemini 3.1 Pro"),
                LlmModelInfo("deepseek-chat", "DeepSeek Chat"),
            ),
            result.getOrThrow(),
        )
    }

    @Test
    fun `smokeTest 走完整一步工具往返并校验签名回传`() = runTest {
        var step = 0
        val client = mockLlmClient { request ->
            val body = json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            val tools = body["tools"]?.jsonArray
            assertEquals("echo_ping", tools?.single()?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content)

            if (step == 0) {
                step++
                respondSse(
                    """
                    data: {"choices":[{"delta":{"tool_calls":[{"id":"ping_1","type":"function","function":{"name":"echo_ping","arguments":"{\"ping\":\"ping\"}"},"extra_content":{"google":{"thought_signature":"SMOKE_SIG=="}}}]},"finish_reason":"tool_calls"}]}

                    data: [DONE]
                    """.trimIndent(),
                )
            } else {
                step++
                val messages = body["messages"]!!.jsonArray
                val assistantMsg = messages.first { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }.jsonObject
                val toolSig = assistantMsg["tool_calls"]!!.jsonArray[0].jsonObject["extra_content"]!!
                    .jsonObject["google"]!!.jsonObject["thought_signature"]!!.jsonPrimitive.content
                assertEquals("SMOKE_SIG==", toolSig)
                val toolMsg = messages.last().jsonObject
                assertEquals("tool", toolMsg["role"]?.jsonPrimitive?.content)
                assertEquals("ping_1", toolMsg["tool_call_id"]?.jsonPrimitive?.content)
                assertEquals("pong", toolMsg["content"]?.jsonPrimitive?.content)

                respondSse(
                    """
                    data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

                    data: [DONE]
                    """.trimIndent(),
                )
            }
        }

        val result = client.smokeTest()
        assertTrue(result.isSuccess)
        assertEquals(2, step)
    }

    @Test
    fun `smokeTest 对不调工具的模型与退役 404 模型报错且绝不泄露凭据`() = runTest {
        val noToolClient = mockLlmClient {
            respondSse("""data: {"choices":[{"delta":{"content":"2"},"finish_reason":"stop"}]}""")
        }
        assertTrue(noToolClient.smokeTest().isFailure)

        val google404Json = """
            [{"error":{"code":404,"message":"This model models/gemini-2.5-flash is no longer available to new users. Key=test-placeholder-token","status":"NOT_FOUND"}}]
        """.trimIndent()
        val retiredClient = mockLlmClient {
            respond(
                content = google404Json,
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val failure = retiredClient.smokeTest().exceptionOrNull()!!
        assertTrue(failure.message!!.startsWith("LLM 404: This model models/gemini-2.5-flash is no longer available"))
        assertFalse(failure.message!!.contains("test-placeholder-token"))
    }

    private fun mockLlmClient(
        config: LlmConfig = testConfig,
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): LlmClient {
        val http = HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(HttpTimeout)
        }
        return LlmClient(http, json, longArrayOf(0, 0, 0)) { config }
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondSse(sse: String) = respond(
        content = ByteReadChannel(sse),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
    )
}
