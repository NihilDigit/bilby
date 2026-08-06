package dev.bilby.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.agent.AgentEvent
import dev.bilby.agent.AgentIntent
import dev.bilby.agent.AgentLoop
import dev.bilby.agent.ChatMessage
import dev.bilby.agent.TraceItem
import dev.bilby.data.AgentSessionRepository
import dev.bilby.api.BiliResult
import dev.bilby.data.SearchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 搜索排序。快路默认综合;这几个取值来自 B 站 search/type 的 order 参数。 */
enum class SearchOrder(val apiValue: String, val label: String) {
    Comprehensive("totalrank", "综合"),
    Play("click", "播放"),
    NewPublished("pubdate", "新发布"),
}

/**
 * 两条路共用一个界面(DESIGN 2.2 的"一个框,两条路"):快路直接打 B 站搜索接口瞬时返回,
 * 慢路起 agent 循环。区别只在这里的分派,UI 侧是同一串轮次。
 *
 * 轮次只留在内存里,不落库 —— DESIGN 2.2 明确不做搜索历史。
 */
class SearchChatViewModel(
    private val searchRepository: SearchRepository,
    private val agentLoop: AgentLoop,
    private val sessions: AgentSessionRepository,
) : ViewModel() {

    /**
     * 会话内多轮共享的上下文(DESIGN 3.1 修订)。**只含本会话的对话与工具返回**,
     * 观看历史一个字都不会进来。会话由用户点"新会话"显式开启,不自动续接。
     */
    private var sessionId: Long? = null
    private var history: List<ChatMessage> = emptyList()
    private var seenBvids: Set<String> = emptySet()
    private var traces: Map<String, TraceItem> = emptyMap()

    /** 开新会话:清空上下文。旧会话留在库里,这一版不做会话列表。 */
    fun newSession() {
        sessionId = null
        history = emptyList()
        seenBvids = emptySet()
        traces = emptyMap()
        _state.value = SearchChatUiState(mode = _state.value.mode)
    }

    private val _state = MutableStateFlow(SearchChatUiState())
    val state: StateFlow<SearchChatUiState> = _state.asStateFlow()

    private var nextTurnId = 1L
    private val pages = mutableMapOf<Long, Int>()

    fun onInputChange(value: String) = _state.update { it.copy(input = value) }

    fun onModeChange(mode: SearchMode) = _state.update { it.copy(mode = mode) }

    fun send() {
        val query = _state.value.input.trim()
        if (query.isEmpty()) return
        val mode = _state.value.mode
        val turnId = nextTurnId++

        val initial = when (mode) {
            SearchMode.Normal -> TurnResult.Normal(emptyList(), emptyList(), loading = true)
            SearchMode.Agent -> TurnResult.Agent(emptyList(), emptyList(), running = true)
        }
        _state.update {
            it.copy(turns = it.turns + SearchTurn(turnId, query, mode, initial), input = "")
        }

        when (mode) {
            SearchMode.Normal -> runNormal(turnId, query, page = 1)
            SearchMode.Agent -> runAgent(turnId, query)
        }
    }

    fun loadMore(turnId: Long) {
        val turn = _state.value.turns.firstOrNull { it.id == turnId } ?: return
        val result = turn.result as? TurnResult.Normal ?: return
        if (result.appending || !result.hasMore) return
        updateNormal(turnId) { it.copy(appending = true) }
        runNormal(turnId, turn.query, page = (pages[turnId] ?: 1) + 1)
    }

    fun retry(turnId: Long) {
        val turn = _state.value.turns.firstOrNull { it.id == turnId } ?: return
        when (turn.mode) {
            SearchMode.Normal -> {
                updateNormal(turnId) { it.copy(loading = true, error = null) }
                runNormal(turnId, turn.query, page = 1)
            }

            SearchMode.Agent -> {
                updateTurn(turnId) { TurnResult.Agent(emptyList(), emptyList(), running = true) }
                runAgent(turnId, turn.query)
            }
        }
    }

    private fun runNormal(turnId: Long, query: String, page: Int) = viewModelScope.launch {
        when (val result = searchRepository.searchVideos(keyword = query, page = page, order = SearchOrder.Comprehensive.apiValue)) {
            is BiliResult.Ok -> {
                pages[turnId] = page
                val users = if (page == 1) searchRepository.searchUsers(query).usersOrEmpty() else emptyList()
                updateNormal(turnId) { current ->
                    current.copy(
                        videos = if (page == 1) result.value.items else current.videos + result.value.items,
                        users = if (page == 1) users else current.users,
                        loading = false,
                        appending = false,
                        hasMore = result.value.hasMore,
                        error = null,
                    )
                }
            }

            is BiliResult.ApiError -> failNormal(turnId, "${result.message}(${result.code})")
            is BiliResult.Failure -> failNormal(turnId, result.cause.message ?: "网络错误")
        }
    }

    private fun runAgent(turnId: Long, query: String) = viewModelScope.launch {
        val id = sessionId ?: sessions.newSession(query.take(SESSION_TITLE_LENGTH)).also { sessionId = it }

        agentLoop.run(
            intent = AgentIntent.Query(query),
            history = history,
            priorBvids = seenBvids,
            priorTraces = traces,
            onTurnComplete = { newMessages, seen, newTraces ->
                history = history + newMessages
                seenBvids = seen
                traces = newTraces
                // 落库不能阻塞 UI 线程上的收流,单独起一个协程。
                viewModelScope.launch { sessions.appendMessages(id, newMessages) }
            },
        ).collect { event ->
            updateAgent(turnId) { it.reduce(event) }
            if (event is AgentEvent.Answer) {
                viewModelScope.launch { sessions.saveAnswer(id, event.items) }
            }
        }
        updateAgent(turnId) { it.copy(running = false) }
    }

    private fun TurnResult.Agent.reduce(event: AgentEvent): TurnResult.Agent = when (event) {
        // 模型的自然语言不进 UI:直播要显示"做了什么",不是"想了什么"。
        is AgentEvent.Thinking -> this

        is AgentEvent.ToolStarted -> copy(steps = steps + AgentStep(event.label, emptyList(), finished = false))

        // 同一次调用的开始与结束是两个事件,按 label 回填最后一个未完成项,否则每步显示两遍。
        is AgentEvent.ToolFinished -> copy(
            steps = steps.toMutableList().also { list ->
                val index = list.indexOfLast { it.label == event.label && !it.finished }
                if (index >= 0) list[index] = list[index].copy(items = event.items, finished = true)
                else list += AgentStep(event.label, event.items, finished = true)
            },
        )

        is AgentEvent.Answer -> copy(answer = event.items, summary = event.summary, running = false)
        is AgentEvent.Failed -> copy(error = event.message, running = false)
    }

    private fun failNormal(turnId: Long, message: String) =
        updateNormal(turnId) { it.copy(loading = false, appending = false, error = message) }

    private inline fun updateNormal(turnId: Long, crossinline block: (TurnResult.Normal) -> TurnResult.Normal) =
        updateTurn(turnId) { if (it is TurnResult.Normal) block(it) else it }

    private inline fun updateAgent(turnId: Long, crossinline block: (TurnResult.Agent) -> TurnResult.Agent) =
        updateTurn(turnId) { if (it is TurnResult.Agent) block(it) else it }

    private inline fun updateTurn(turnId: Long, crossinline block: (TurnResult) -> TurnResult) {
        _state.update { state ->
            state.copy(
                turns = state.turns.map { turn ->
                    if (turn.id == turnId) turn.copy(result = block(turn.result)) else turn
                },
            )
        }
    }

    private fun BiliResult<List<dev.bilby.data.SearchUser>>.usersOrEmpty() =
        (this as? BiliResult.Ok)?.value.orEmpty()

    private companion object {
        /** 会话标题取第一轮输入的前若干字,给以后的会话列表用。 */
        const val SESSION_TITLE_LENGTH = 20
    }
}
