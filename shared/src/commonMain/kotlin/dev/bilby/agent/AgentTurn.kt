package dev.bilby.agent

/**
 * 一次工具调用在界面上的样子:标签、中间结果、跑完没有。
 *
 * [items] 是 DESIGN 3.4 的"中间结果可点" —— 助理翻到一半用户看中了可以直接点走,
 * 所以它属于这一步本身,不是等答案出来之后才有的东西。
 */
data class AgentStep(
    val id: Int,
    val kind: StepKind,
    /** 这一步的对象,不带动词;动作由 [kind] 的图标表达。 */
    val text: String,
    val items: List<TraceItem> = emptyList(),
    val finished: Boolean = false,
)

/**
 * 助理一轮的完整状态。搜索页的一轮对话、播放页「找相关」的那一次检索,是同一件事的两个容器,
 * 状态没有理由长成两份。
 *
 * 这里原先是三份:`TurnResult.Agent`、`RelatedState`、`AgentUiState`,各带一个自己的
 * [reduce]。三份不是同时写歪的,是各自跟着容器长的 —— 播放页那份把 [AgentEvent.ToolFinished]
 * 整个丢掉,于是「找相关」跑的过程里没有中间结果卡片,3.4 那条在播放页根本不成立,而看代码
 * 的人不会知道它少了一段,因为那份 reducer 自己是自洽的。
 */
data class AgentTurnState(
    val steps: List<AgentStep> = emptyList(),
    val answer: AgentAnswer? = null,
    val running: Boolean = false,
    val error: String? = null,
)

/**
 * 事件流到状态的唯一一处折叠。
 *
 * 一步的开始与结束是两个事件,结束按 stepId 回填那一步,否则每步会显示两遍。
 */
fun AgentTurnState.reduce(event: AgentEvent): AgentTurnState = when (event) {
    // 模型的自然语言不进 UI:过程直播要显示"做了什么",不是"想了什么"。
    is AgentEvent.Thinking -> this

    is AgentEvent.ToolStarted -> copy(steps = steps + AgentStep(event.stepId, event.kind, event.text))

    is AgentEvent.ToolFinished -> copy(
        steps = steps.toMutableList().also { list ->
            val finished = AgentStep(event.stepId, event.kind, event.text, event.items, finished = true)
            val index = list.indexOfFirst { it.id == event.stepId }
            if (index >= 0) list[index] = finished else list += finished
        },
    )

    is AgentEvent.Answer -> copy(answer = event.answer, running = false)
    is AgentEvent.Failed -> copy(error = event.message, running = false)
}
