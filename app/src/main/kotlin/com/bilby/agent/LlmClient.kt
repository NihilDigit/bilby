package com.bilby.agent

import com.bilby.BiliLog
import com.bilby.data.LlmConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

/**
 * OpenAI-compatible chat completions 的薄封装,provider 无关(base URL 与 key 可配)。
 *
 * 一定要流式,但目的不是打字机效果 —— 是把 tool_calls 一出现就吐给 UI 做过程直播
 * (DESIGN 3.4)。等整个响应回来再渲染,等待期就只剩一个转圈。
 */
/** 抽成接口是为了让 AgentLoop 能对假实现跑三条硬规矩的回归。 */
interface LlmStreamer {
    fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<LlmDelta>
}

class LlmClient(
    private val http: HttpClient,
    private val json: Json,
    private val configProvider: suspend () -> LlmConfig,
) : LlmStreamer {

    override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<LlmDelta> = flow {
        val config = configProvider()
        require(config.isConfigured) { "LLM 未配置:缺 base URL 或 key" }

        val request = ChatRequest(model = config.model, messages = messages, tools = tools.ifEmpty { null })
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"

        http.preparePost(url) {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }.execute { response ->
            // 非 2xx 的响应体是普通 JSON,一行都不以 data: 开头,不检查的话整个错误会被
            // 当成"没有增量"静默吞掉,上层只能看到"助理没有给出结果"。
            if (!response.status.isSuccess()) {
                error("LLM ${response.status.value}: ${response.bodyAsText().take(500)}")
            }

            val channel = response.bodyAsChannel()
            val pending = mutableMapOf<Int, PartialToolCall>()

            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith(DATA_PREFIX)) continue
                val payload = line.removePrefix(DATA_PREFIX).trim()
                if (payload == DONE_SENTINEL) break

                val chunk = runCatching { json.decodeFromString<ChatChunk>(payload) }
                    .onFailure { BiliLog.w("LLM 响应片段解析失败", it) }
                    .getOrNull() ?: continue
                val choice = chunk.choices.firstOrNull() ?: continue

                choice.delta.content?.takeIf { it.isNotEmpty() }?.let { emit(LlmDelta.Text(it)) }

                choice.delta.toolCalls?.forEach { delta ->
                    val partial = pending.getOrPut(delta.index) { PartialToolCall() }
                    delta.id?.let { partial.id = it }
                    delta.function?.name?.let { partial.name = it }
                    delta.function?.arguments?.let { partial.arguments.append(it) }
                }

                if (choice.finishReason != null) {
                    if (pending.isNotEmpty()) {
                        emit(LlmDelta.ToolCalls(pending.toSortedMap().values.mapNotNull { it.toToolCall() }))
                        pending.clear()
                    }
                    emit(LlmDelta.Done(choice.finishReason))
                }
            }
        }
    }

    private class PartialToolCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()

        fun toToolCall(): ToolCall? {
            val name = name ?: return null
            return ToolCall(
                id = id ?: name,
                function = FunctionCall(name = name, arguments = arguments.toString().ifEmpty { "{}" }),
            )
        }
    }

    private companion object {
        const val DATA_PREFIX = "data:"
        const val DONE_SENTINEL = "[DONE]"
    }
}
