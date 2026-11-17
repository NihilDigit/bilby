package dev.bilby.agent

import dev.bilby.BiliLog
import dev.bilby.data.LlmConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * 规整用户填的 OpenAI 兼容根地址:去掉首尾空白、末尾斜杠与误粘进来的 `/chat/completions`,
 * 省略协议头时按本机/远端补齐 `http://` 或 `https://`。
 *
 * Google GFE 对 `.../openai//chat/completions` 与 `.../openai//models` 直接回 404,
 * 所以拼接子路径前必须先把尾斜杠清干净。
 */
fun normalizeLlmBaseUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val withScheme = if (trimmed.contains("://")) {
        trimmed
    } else {
        val local = trimmed.startsWith("localhost") ||
            trimmed.startsWith("127.0.0.1") ||
            trimmed.startsWith("10.0.2.2")
        if (local) "http://$trimmed" else "https://$trimmed"
    }
    return withScheme.trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
}

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

class LlmClient internal constructor(
    private val http: HttpClient,
    private val json: Json,
    private val retryDelaysMs: LongArray,
    private val configProvider: suspend () -> LlmConfig,
) : LlmStreamer {

    constructor(
        http: HttpClient,
        json: Json,
        configProvider: suspend () -> LlmConfig,
    ) : this(http, json, RETRY_DELAYS_MS, configProvider)

    /**
     * 冒烟测试:跑一次最小的一步工具往返(`echo_ping` -> 工具结果 -> 最终回复),返回总耗时。
     *
     * 只发裸文本会在两件事上给出假阳性:不支持工具调用的模型照样能回答 `1+1`,以及 Gemini 3+
     * 在第 0 步吐出 `thought_signature`、第 1 步才校验签名。走完一轮工具往返,退役模型(404)、
     * 无工具能力的模型和签名透传问题都会当场暴露。
     */
    suspend fun smokeTest(): Result<Long> = runCatching {
        val raw = configProvider()
        val baseUrl = normalizeLlmBaseUrl(raw.baseUrl)
        val apiKey = raw.apiKey.trim()
        val model = raw.model.trim()
        require(baseUrl.isNotEmpty() && apiKey.isNotEmpty() && model.isNotEmpty()) {
            "缺 base URL、key 或模型"
        }
        val started = System.nanoTime()
        val tools = listOf(SMOKE_TOOL_SPEC)
        val messages = mutableListOf(
            ChatMessage(role = ChatMessage.ROLE_SYSTEM, content = SMOKE_SYSTEM_PROMPT),
            ChatMessage(role = ChatMessage.ROLE_USER, content = SMOKE_PROMPT),
        )

        val step0 = stream(messages = messages, tools = tools).toList()
        val step0Text = step0.filterIsInstance<LlmDelta.Text>().joinToString("") { it.text }
        val step0Calls = step0.filterIsInstance<LlmDelta.ToolCalls>().flatMap { it.calls }
        val step0Extra = step0.filterIsInstance<LlmDelta.ExtraContent>().lastOrNull()?.content
        if (step0Calls.isEmpty()) {
            error("该模型未触发工具调用,助理需要支持工具调用的模型")
        }

        messages += ChatMessage(
            role = ChatMessage.ROLE_ASSISTANT,
            content = step0Text.ifBlank { null },
            toolCalls = step0Calls,
            extraContent = step0Extra,
        )
        step0Calls.forEach { call ->
            messages += ChatMessage(
                role = ChatMessage.ROLE_TOOL,
                toolCallId = call.id,
                name = call.function.name,
                content = SMOKE_TOOL_RESULT,
            )
        }

        val step1 = stream(messages = messages, tools = tools).toList()
        val step1Text = step1.filterIsInstance<LlmDelta.Text>().joinToString("") { it.text }
        if (step1Text.isBlank()) {
            error("模型在工具返回后未给出回复")
        }
        (System.nanoTime() - started) / 1_000_000
    }.onFailure { BiliLog.w("LLM 冒烟测试失败", it) }

    /**
     * 拉取服务端当前可用的对话模型清单(`GET {baseUrl}/models`),筛掉向量、语音、生图等非对话模型。
     */
    suspend fun listModels(baseUrl: String, apiKey: String): Result<List<LlmModelInfo>> = runCatching {
        val normalizedUrl = normalizeLlmBaseUrl(baseUrl)
        val trimmedKey = apiKey.trim()
        require(normalizedUrl.isNotEmpty() && trimmedKey.isNotEmpty()) { "缺 base URL 或 key" }

        val response = http.get("$normalizedUrl/models") {
            header(HttpHeaders.Authorization, "Bearer $trimmedKey")
            timeout {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = MODELS_TIMEOUT_MS
                requestTimeoutMillis = MODELS_TIMEOUT_MS
            }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val detail = extractLlmErrorMessage(body, json, trimmedKey)
            error(if (detail.isEmpty()) "LLM ${response.status.value}" else "LLM ${response.status.value}: $detail")
        }

        json.decodeFromString<ModelsResponse>(body)
            .data
            .map { it.copy(id = it.id.trim(), displayName = it.displayName?.trim()?.ifEmpty { null }) }
            .filter { isChatModelId(it.id) }
            .distinctBy { it.id }
    }.onFailure { BiliLog.w("LLM 模型列表拉取失败", it) }

    override fun stream(messages: List<ChatMessage>, tools: List<ToolSpec>): Flow<LlmDelta> = flow {
        val raw = configProvider()
        val baseUrl = normalizeLlmBaseUrl(raw.baseUrl)
        val apiKey = raw.apiKey.trim()
        val model = raw.model.trim()
        require(baseUrl.isNotEmpty() && apiKey.isNotEmpty() && model.isNotEmpty()) {
            "LLM 未配置:缺 base URL、key 或模型"
        }

        val config = LlmConfig(baseUrl = baseUrl, apiKey = apiKey, model = model)
        val request = ChatRequest(model = config.model, messages = messages, tools = tools.ifEmpty { null })
        val url = "${config.baseUrl}/chat/completions"

        // DeepSeek 限并发回 429,Gemini 瞬时过载回 503;这两类等几秒重试能自愈,其余错误码不重试。
        var attempt = 0
        while (true) {
            try {
                streamOnce(url, config, request)
                return@flow
            } catch (busy: ServerBusy) {
                attempt++
                if (attempt > retryDelaysMs.size) {
                    error("LLM 服务繁忙(${busy.status}),重试 ${retryDelaysMs.size} 次仍未成功")
                }
                val wait = retryDelaysMs[attempt - 1]
                BiliLog.w("LLM 返回 ${busy.status},${wait}ms 后第 $attempt 次重试")
                if (wait > 0) delay(wait)
            }
        }
        // SSE 是逐行读、逐行反序列化的,一次回答几百个 chunk 都在这条流里解。收集方在主线程,
        // 上游整体挪到 Default 之后主线程只剩下拿到 delta 之后的那一步。
    }.flowOn(Dispatchers.Default)

    private suspend fun FlowCollector<LlmDelta>.streamOnce(
        url: String,
        config: LlmConfig,
        request: ChatRequest,
    ) {
        http.preparePost(url) {
            header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            contentType(ContentType.Application.Json)
            timeout {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
            setBody(json.encodeToString(request))
        }.execute { response ->
            // 非 2xx 的响应体是普通 JSON,一行都不以 data: 开头,不检查的话整个错误会被
            // 当成"没有增量"静默吞掉,上层只能看到"助理没有给出结果"。
            if (response.status == HttpStatusCode.TooManyRequests ||
                response.status == HttpStatusCode.ServiceUnavailable
            ) {
                throw ServerBusy(response.status.value)
            }
            if (!response.status.isSuccess()) {
                val detail = extractLlmErrorMessage(response.bodyAsText(), json, config.apiKey)
                error(if (detail.isEmpty()) "LLM ${response.status.value}" else "LLM ${response.status.value}: $detail")
            }

            val channel = response.bodyAsChannel()
            val pending = mutableListOf<PartialToolCall>()
            var afterThought = false

            while (true) {
                val line = channel.readLine() ?: break
                if (!line.startsWith(DATA_PREFIX)) continue
                val payload = line.removePrefix(DATA_PREFIX).trim()
                if (payload == DONE_SENTINEL) break

                val chunk = runCatching { json.decodeFromString<ChatChunk>(payload) }
                    .onFailure { BiliLog.w("LLM 响应片段解析失败", it) }
                    .getOrNull() ?: continue
                val choice = chunk.choices.firstOrNull() ?: continue

                val deltaExtra = choice.delta.extraContent
                val isThought = deltaExtra?.isGoogleThought() == true
                deltaExtra?.let(::nonThoughtExtraContent)?.let { emit(LlmDelta.ExtraContent(it)) }

                if (isThought) {
                    afterThought = true
                } else {
                    val rawContent = choice.delta.content
                    if (!rawContent.isNullOrEmpty()) {
                        val cleaned = if (afterThought) {
                            afterThought = false
                            rawContent.removePrefix(THOUGHT_CLOSE_TAG)
                        } else {
                            rawContent
                        }
                        if (cleaned.isNotEmpty()) {
                            emit(LlmDelta.Text(cleaned))
                        }
                    }
                }

                choice.delta.toolCalls?.forEach { delta ->
                    val partial = resolvePartialToolCall(pending, delta)
                    delta.id?.takeIf { it.isNotEmpty() }?.let { partial.id = it }
                    delta.function?.name?.takeIf { it.isNotEmpty() }?.let { partial.name = it }
                    delta.function?.arguments?.let { partial.arguments.append(it) }
                    delta.extraContent?.let { partial.extraContent = it }
                }

                if (choice.finishReason != null) {
                    flushToolCalls(pending)
                    emit(LlmDelta.Done(choice.finishReason))
                }
            }

            // 兼容部分网关在流尾直接发 [DONE] 或断开、不带 finish_reason 的情况。
            flushToolCalls(pending)
        }
    }

    private suspend fun FlowCollector<LlmDelta>.flushToolCalls(pending: MutableList<PartialToolCall>) {
        if (pending.isEmpty()) return
        val ordered = if (pending.all { it.index != null } &&
            pending.map { it.index }.distinct().size == pending.size
        ) {
            pending.sortedBy { it.index }
        } else {
            pending.toList()
        }
        pending.clear()
        val calls = ordered.mapNotNull { it.toToolCall() }
        if (calls.isNotEmpty()) {
            emit(LlmDelta.ToolCalls(calls))
        }
    }

    private fun resolvePartialToolCall(
        pending: MutableList<PartialToolCall>,
        delta: ToolCallDelta,
    ): PartialToolCall {
        val deltaId = delta.id?.takeIf { it.isNotEmpty() }
        val deltaName = delta.function?.name?.takeIf { it.isNotEmpty() }
        if (delta.index != null) {
            val existing = pending.lastOrNull { it.index == delta.index }
            if (existing != null && (existing.id == null || deltaId == null || existing.id == deltaId)) {
                return existing
            }
            return PartialToolCall(index = delta.index).also { pending += it }
        }
        if (deltaId != null) {
            return pending.firstOrNull { it.id == deltaId }
                ?: PartialToolCall(index = null).also { pending += it }
        }
        val last = pending.lastOrNull()
        if (last != null && (deltaName == null || last.name == null)) {
            return last
        }
        return PartialToolCall(index = null).also { pending += it }
    }

    private class PartialToolCall(val index: Int? = null) {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
        var extraContent: JsonObject? = null

        fun toToolCall(): ToolCall? {
            val resolvedName = name?.takeIf { it.isNotEmpty() } ?: return null
            return ToolCall(
                id = id?.takeIf { it.isNotEmpty() } ?: resolvedName,
                function = FunctionCall(
                    name = resolvedName,
                    arguments = arguments.toString().ifEmpty { "{}" },
                ),
                extraContent = extraContent,
            )
        }
    }

    /** 429 并发受限、503 瞬时过载:等一会儿再来。 */
    private class ServerBusy(val status: Int) : Exception()

    private companion object {
        const val SMOKE_TOOL_NAME = "echo_ping"
        const val SMOKE_TOOL_RESULT = "pong"
        const val SMOKE_SYSTEM_PROMPT =
            "你正在执行工具调用连通性测试。你必须先调用一次 echo_ping 工具(参数 ping=\"ping\"),收到工具返回后再回复 ok。不要跳过工具调用直接回答。"
        const val SMOKE_PROMPT = "请调用 echo_ping 工具完成测试,收到结果后只回复 ok。"

        val SMOKE_TOOL_SPEC = ToolSpec(
            function = FunctionSpec(
                name = SMOKE_TOOL_NAME,
                description = "连通性测试工具,原样返回测试口令。",
                parameters = buildJsonObject {
                    put("type", JsonPrimitive("object"))
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "ping",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("固定传入 ping"))
                                },
                            )
                        },
                    )
                    put("required", buildJsonArray { add(JsonPrimitive("ping")) })
                },
            ),
        )

        const val DATA_PREFIX = "data:"
        const val DONE_SENTINEL = "[DONE]"
        const val THOUGHT_CLOSE_TAG = "</thought>"

        const val CONNECT_TIMEOUT_MS = 15_000L
        const val SOCKET_TIMEOUT_MS = 120_000L
        const val REQUEST_TIMEOUT_MS = 180_000L
        const val MODELS_TIMEOUT_MS = 30_000L

        /** 递增而不是固定间隔:并发拥堵通常要几秒才缓过来,连着敲三次只是白等。 */
        val RETRY_DELAYS_MS = longArrayOf(1_000, 3_000, 6_000)
    }
}

private val NON_CHAT_MODEL_TOKENS = listOf(
    "embedding",
    "tts",
    "image",
    "imagen",
    "veo",
    "transcribe",
    "lyria",
    "live",
    "aqa",
)

internal fun isChatModelId(id: String): Boolean {
    val lower = id.trim().lowercase()
    return lower.isNotEmpty() && NON_CHAT_MODEL_TOKENS.none { token -> lower.contains(token) }
}

private fun JsonObject.isGoogleThought(): Boolean =
    (this["google"] as? JsonObject)
        ?.get("thought")
        ?.let { it as? JsonPrimitive }
        ?.booleanOrNull == true

private fun nonThoughtExtraContent(extra: JsonObject): JsonObject? {
    val google = extra["google"] as? JsonObject ?: return extra.takeIf { it.isNotEmpty() }
    if ("thought" !in google) return extra.takeIf { it.isNotEmpty() }
    val cleanedGoogle = JsonObject(google.filterKeys { it != "thought" })
    val cleanedExtra = JsonObject(
        buildMap {
            putAll(extra)
            if (cleanedGoogle.isEmpty()) remove("google") else put("google", cleanedGoogle)
        },
    )
    return cleanedExtra.takeIf { it.isNotEmpty() }
}

/**
 * 从错误响应体里提取可读原因(兼容 `{"error":{"message":...}}` 与 Google 的 `[{"error":{"message":...}}]`),
 * 并抹掉可能被上游原样回显的 API key,保证凭据永不进异常消息与日志。
 */
internal fun extractLlmErrorMessage(rawBody: String, json: Json, apiKey: String = ""): String {
    fun redact(text: String): String {
        val secret = apiKey.trim()
        return if (secret.isNotEmpty()) text.replace(secret, "***") else text
    }

    val trimmed = rawBody.trim()
    if (trimmed.isEmpty()) return ""

    val parsed = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        runCatching {
            val root = json.parseToJsonElement(trimmed)
            val obj = when (root) {
                is JsonObject -> root
                is JsonArray -> root.firstOrNull() as? JsonObject
                else -> null
            }
            val errorElement = obj?.get("error")
            when (errorElement) {
                is JsonObject -> (errorElement["message"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> errorElement.contentOrNull
                else -> (obj?.get("message") as? JsonPrimitive)?.contentOrNull
            }
        }.onFailure { BiliLog.w("LLM 错误响应 JSON 解析失败") }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    } else {
        null
    }

    val fallback = parsed ?: trimmed.lineSequence().joinToString(" ") { it.trim() }.trim()
    return redact(fallback).take(300)
}
