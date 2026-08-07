package dev.bilby.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.agent.AgentEvent
import dev.bilby.agent.AgentIntent
import dev.bilby.agent.AgentLoop
import dev.bilby.agent.ChatMessage
import dev.bilby.agent.TraceItem
import dev.bilby.api.BiliResult
import dev.bilby.data.SearchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 搜索排序。快路默认综合;这几个取值来自 B 站 search/type 的 order 参数。
 * 界面上还没有排序控件,所以只留 apiValue —— 原来的 label 没有任何地方显示。
 */
enum class SearchOrder(val apiValue: String) {
    Comprehensive("totalrank"),
    Play("click"),
    NewPublished("pubdate"),
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
) : ViewModel() {

    /**
     * 会话内多轮共享的上下文(DESIGN 3.1 修订)。**只含本会话的对话与工具返回**,
     * 观看历史一个字都不会进来。会话由用户点"新会话"显式开启,不自动续接。
     */
    private var history: List<ChatMessage> = emptyList()
    private var seenBvids: Set<String> = emptySet()
    private var traces: Map<String, TraceItem> = emptyMap()

    /**
     * 正在跑的那一轮。**开新会话和再次发问都必须先把它取消掉。**
     *
     * 循环跑在 [viewModelScope] 上,这是它能扛住切 tab 和翻到播放页的原因(那两处只是
     * composable 离开组合,ViewModel 还在)。反过来说,不显式取消就没有任何东西会停下它:
     * 旧会话会继续执行工具、继续把消息写进一个用户已经放弃的上下文,而它的
     * `updateAgent(turnId)` 还会往刚清空的状态里回写。
     */
    private var agentJob: Job? = null

    /** 开新会话:销毁旧的那一轮并丢弃它的上下文。 */
    fun newSession() {
        agentJob?.cancel()
        agentJob = null
        history = emptyList()
        seenBvids = emptySet()
        traces = emptyMap()
        _state.update { it.copy(agent = AgentSearchState()) }
    }

    private val _state = MutableStateFlow(SearchChatUiState())
    val state: StateFlow<SearchChatUiState> = _state.asStateFlow()

    private var nextTurnId = 1L
    /** 普通搜索当前翻到第几页。它只有一份结果,不需要按轮次记。 */
    private var page = 1

    /**
     * 普通搜索已经收下的 bvid。分页边界上同一条稿件会重出,而 UI 拿 bvid 当 LazyColumn 的
     * key —— 重复即崩溃(「Key ... was already used」)。仓库那层只能管住单页内部,跨页
     * 只有这里知道。
     */
    private val seenSearchBvids = mutableSetOf<String>()

    fun onInputChange(value: String) = _state.update { it.copy(input = value) }

    /**
     * 两种模式各有各的状态,切换只是换显示哪一份,**都不清空**。会话要重开有右上角的
     * 显式入口;切一下模式就丢掉一整段对话,没人会预期。
     */
    fun onModeChange(mode: SearchMode) = _state.update { it.copy(mode = mode) }

    fun send() {
        val query = _state.value.input.trim()
        if (query.isEmpty()) return
        _state.update { it.copy(input = "") }
        when (_state.value.mode) {
            // 普通搜索是一次查询一份结果,不累积轮次:它就是一个搜索页。留着上一次的结果
            // 只会让人往上翻,而翻上去的东西和这次要找的无关。
            SearchMode.Normal -> {
                seenSearchBvids.clear()
                _state.update { it.copy(normal = NormalSearchState(query = query, loading = true)) }
                runNormal(query, page = 1)
            }

            SearchMode.Agent -> {
                val turnId = nextTurnId++
                _state.update { state ->
                    state.copy(
                        agent = state.agent.copy(
                            turns = state.agent.turns +
                                SearchTurn(turnId, query, TurnResult.Agent(emptyList(), running = true)),
                        ),
                    )
                }
                runAgent(turnId, query)
            }
        }
    }

    fun loadMore() {
        val normal = _state.value.normal
        if (normal.appending || !normal.hasMore || normal.query.isEmpty()) return
        _state.update { it.copy(normal = it.normal.copy(appending = true)) }
        runNormal(normal.query, page = page + 1)
    }

    fun retry() {
        when (_state.value.mode) {
            SearchMode.Normal -> {
                val query = _state.value.normal.query.ifEmpty { return }
                seenSearchBvids.clear()
                _state.update { it.copy(normal = it.normal.copy(loading = true, error = null)) }
                runNormal(query, page = 1)
            }

            SearchMode.Agent -> {
                val turn = _state.value.agent.turns.lastOrNull() ?: return
                updateAgent(turn.id) { TurnResult.Agent(emptyList(), running = true) }
                runAgent(turn.id, turn.query)
            }
        }
    }

    private fun runNormal(query: String, page: Int) = viewModelScope.launch {
        val order = SearchOrder.Comprehensive.apiValue
        when (val result = searchRepository.searchVideos(keyword = query, page = page, order = order)) {
            is BiliResult.Ok -> {
                this@SearchChatViewModel.page = page
                if (page == 1) seenSearchBvids.clear()
                val fresh = result.value.items.filter { seenSearchBvids.add(it.bvid) }
                val users = if (page == 1) searchRepository.searchUsers(query).usersOrEmpty() else emptyList()
                _state.update { state ->
                    state.copy(
                        normal = state.normal.copy(
                            videos = if (page == 1) fresh else state.normal.videos + fresh,
                            users = if (page == 1) users else state.normal.users,
                            loading = false,
                            appending = false,
                            hasMore = result.value.hasMore,
                            error = null,
                        ),
                    )
                }
            }

            is BiliResult.ApiError -> failNormal("${result.message}(${result.code})")
            is BiliResult.Failure -> failNormal(result.cause.message ?: "网络错误")
        }
    }

    /**
     * 会话只活在内存里,随 ViewModel 生灭,不落库。
     *
     * 助理上下文是一次性的:它只含本次意图(DESIGN 3.3 第 4 条),用户开新会话就是明确表示
     * 不要它了。存下来既没有消费方(这一版没有会话列表),又要为"该不该续接"反复做判断,
     * 而任何续接都在把上下文变成一份沉淀的画像。
     */
    private fun runAgent(turnId: Long, query: String) {
        // 上一轮还在跑就先停掉:同一个会话里两个循环并行会交替往 history 上追加,
        // 拼出一段谁也没说过的对话。
        agentJob?.cancel()
        agentJob = viewModelScope.launch {
            agentLoop.run(
                intent = AgentIntent.Query(query),
                history = history,
                priorBvids = seenBvids,
                priorTraces = traces,
                onTurnComplete = { newMessages, seen, newTraces ->
                    history = history + newMessages
                    seenBvids = seen
                    traces = newTraces
                },
            ).collect { event ->
                updateAgent(turnId) { it.reduce(event) }
            }
            updateAgent(turnId) { it.copy(running = false) }
        }
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

        is AgentEvent.Answer -> copy(blocks = event.blocks, running = false)
        is AgentEvent.Failed -> copy(error = event.message, running = false)
    }

    private fun failNormal(message: String) = _state.update {
        it.copy(normal = it.normal.copy(loading = false, appending = false, error = message))
    }

    private inline fun updateAgent(turnId: Long, crossinline block: (TurnResult.Agent) -> TurnResult.Agent) {
        _state.update { state ->
            state.copy(
                agent = state.agent.copy(
                    turns = state.agent.turns.map { turn ->
                        if (turn.id == turnId) turn.copy(result = block(turn.result)) else turn
                    },
                ),
            )
        }
    }

    private fun BiliResult<List<dev.bilby.data.SearchUser>>.usersOrEmpty() =
        (this as? BiliResult.Ok)?.value.orEmpty()

    private companion object {
        /** 会话标题取第一轮输入的前若干字,给以后的会话列表用。 */
    }
}
