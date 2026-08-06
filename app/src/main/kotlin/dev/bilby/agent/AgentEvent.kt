package dev.bilby.agent

/** UI 实时渲染的过程直播:搜了 X → 读了 Y 的热评 → 进了 Z 的空间。 */
sealed interface AgentEvent {
    data class Thinking(val text: String) : AgentEvent

    data class ToolStarted(val label: String) : AgentEvent

    /** 中间结果可点:助理翻到一半用户看中了可以直接点走(DESIGN 3.4)。 */
    data class ToolFinished(val label: String, val items: List<TraceItem>) : AgentEvent

    /**
     * 答案是**一段可以夹着视频卡片的正文**,不是"一段总结 + 一串卡片"。
     *
     * 模型交回的是散文,视频写成 `[[BV1xx]]` 的行内引用;这里把它切成块,引用处就地换成
     * 卡片。这样"为什么值得看"由引用前后的句子承担,不再需要每张卡片自带一句独立说明——
     * 那种说明没有上下文可依附,才逼出了"不许写检索理由""不超过 60 字"这类限制。
     *
     * 纯文本回答是合法的:用户问"这几个评价如何"时,答案本来就不该是一串视频。
     */
    data class Answer(val blocks: List<AnswerBlock>) : AgentEvent {
        /** 过渡用:搜索页还按"总结 + 卡片"渲染。搜索页改成按块渲染后删掉这两个。 */
        val summary: String? get() = blocks.filterIsInstance<AnswerBlock.Text>()
            .joinToString("\n") { it.text }
            .takeIf { it.isNotBlank() }

        val items: List<AnswerItem> get() = blocks.filterIsInstance<AnswerBlock.Video>()
            .map { AnswerItem(it.bvid, reason = "", trace = it.trace) }
    }

    data class Failed(val message: String) : AgentEvent
}

sealed interface AnswerBlock {
    data class Text(val text: String) : AnswerBlock

    /** 引用位置就地插的卡片。文案不在这里,在它前后的 [Text] 里。 */
    data class Video(val bvid: String, val trace: TraceItem?) : AnswerBlock
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
