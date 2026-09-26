package dev.bilby.agent

/** UI 实时渲染的过程直播:搜索 X → Y 的热评 → Z 的投稿。 */
sealed interface AgentEvent {
    data class Thinking(val text: String) : AgentEvent

    /**
     * 一步开始。一步是同一轮里对同一个工具的全部调用,见 [Tool.step]。
     *
     * @param stepId 本轮内唯一,结束事件按它回填。原先按文字回填,文字在结束时会变(名字查到了),
     *   而并发的两步文字又可能相同。
     */
    data class ToolStarted(val stepId: Int, val kind: StepKind, val text: String) : AgentEvent

    /**
     * 一步结束。[text] 可能与开始时不同:执行时认出了合集标题这类名字。
     * [items] 是可点的中间结果:助理翻到一半用户看中了可以直接点走(DESIGN 3.4)。
     */
    data class ToolFinished(
        val stepId: Int,
        val kind: StepKind,
        val text: String,
        val items: List<TraceItem>,
    ) : AgentEvent

    data class Answer(val answer: AgentAnswer) : AgentEvent {
        val bvids: List<String> get() = answer.sources.map { it.bvid }
    }

    data class Failed(val message: String) : AgentEvent
}

/**
 * 一轮的答案:**一段回答,加上它提到的视频**,两者分开摆。
 *
 * 用户主要拿助理问问题,视频是依据和下一步去看的地方,不是答案本身。原先卡片就地插在句子里
 * (模型写 `[[bvid]]`,卡片落在那个位置),一句话被一张卡劈成两半,而模型为了凑卡片会把
 * 回答写成一串推荐 —— 开放问题尤其答不好。
 *
 * [text] 是 markdown,引用处是一个角标记号([citation]),编号从 1 起,对应 [sources] 的
 * 位置。纯文字的回答是合法的,那时 [sources] 为空。
 */
data class AgentAnswer(val text: String, val sources: List<AnswerSource>)

data class AnswerSource(val bvid: String, val trace: TraceItem?)

/**
 * 正文里一个角标的写法:私用区的两个字符夹着编号。**不用 `[1]` 这种可读的写法**,模型自己
 * 写的正文里也会出现方括号和数字,渲染时分不清哪个是引用。
 */
fun citation(number: Int): String = "$CitationOpen$number$CitationClose"

const val CitationOpen = ''
const val CitationClose = ''

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
