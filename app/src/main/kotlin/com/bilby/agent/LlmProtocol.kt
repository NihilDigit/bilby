package com.bilby.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** OpenAI-compatible 的最小子集,只覆盖我们用到的字段。 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"
    }
}

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(val name: String, val arguments: String)

@Serializable
data class ToolSpec(
    val type: String = "function",
    val function: FunctionSpec,
)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonElement,
)

@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>? = null,
    val stream: Boolean = true,
    val temperature: Double = 0.2,
)

@Serializable
internal data class ChatChunk(val choices: List<ChunkChoice> = emptyList())

@Serializable
internal data class ChunkChoice(
    val delta: ChunkDelta = ChunkDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
internal data class ChunkDelta(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)

/**
 * 流式返回里 tool_call 是被切碎的:同一个调用的 name 和 arguments 分散在多个 chunk,
 * 靠 index 归拢。id/name 只在第一个片段出现,arguments 是逐段拼接的 JSON 文本。
 */
@Serializable
internal data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val function: FunctionCallDelta? = null,
)

@Serializable
internal data class FunctionCallDelta(val name: String? = null, val arguments: String? = null)

/** LlmClient 吐给上层的增量事件。 */
sealed interface LlmDelta {
    data class Text(val text: String) : LlmDelta
    data class ToolCalls(val calls: List<ToolCall>) : LlmDelta
    data class Done(val finishReason: String?) : LlmDelta
}

/** 工具返回值。给模型的是紧凑文本,给 UI 的是可点的领域对象(DESIGN 3.4 的过程直播)。 */
data class ToolResult(
    val forModel: String,
    val forUi: List<TraceItem> = emptyList(),
    /** 本次返回中出现过的所有 bvid,喂给溯源校验的白名单。 */
    val bvids: Set<String> = emptySet(),
)

/** 过程直播里可点的中间结果:助理翻到一半,用户看中了可以直接点走。 */
data class TraceItem(
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val upName: String,
)

@Serializable
data class ToolParameters(
    val type: String = "object",
    val properties: JsonObject,
    val required: List<String> = emptyList(),
)
