package dev.bilby.agent

/** UI 实时渲染的过程直播:搜了 X → 读了 Y 的热评 → 进了 Z 的空间。 */
sealed interface AgentEvent {
    data class Thinking(val text: String) : AgentEvent

    data class ToolStarted(val label: String) : AgentEvent

    /** 中间结果可点:助理翻到一半用户看中了可以直接点走(DESIGN 3.4)。 */
    data class ToolFinished(val label: String, val items: List<TraceItem>) : AgentEvent

    data class Answer(val items: List<AnswerItem>) : AgentEvent

    data class Failed(val message: String) : AgentEvent
}

data class AnswerItem(val bvid: String, val reason: String, val trace: TraceItem?)

/**
 * 本次意图。**只含本次意图**,永不含观看画像(DESIGN 1.1 的隐式反馈回路那一栏)——
 * 这个类的字段列表本身就是那条约束的执行点,加字段前先回去看机制表。
 */
sealed interface AgentIntent {
    data class Query(val text: String) : AgentIntent

    data class Related(
        val bvid: String,
        val title: String,
        val upName: String,
        val note: String? = null,
    ) : AgentIntent
}
