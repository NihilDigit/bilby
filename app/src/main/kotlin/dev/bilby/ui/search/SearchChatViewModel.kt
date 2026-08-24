package dev.bilby.ui.search

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.R
import dev.bilby.agent.AgentIntent
import dev.bilby.agent.AgentLoop
import dev.bilby.agent.AgentTurnState
import dev.bilby.agent.ChatMessage
import dev.bilby.agent.TraceItem
import dev.bilby.agent.reduce
import dev.bilby.data.SearchRepository
import dev.bilby.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 搜索排序。快路默认综合;这几个取值来自 B 站 search/type 的 order 参数。
 */
enum class SearchOrder(val apiValue: String, @StringRes val labelRes: Int) {
    Comprehensive("totalrank", R.string.search_order_comprehensive),
    Play("click", R.string.search_order_click),
    NewPublished("pubdate", R.string.search_order_pubdate),
}

/**
 * 两条路共用一个界面(DESIGN 2.2 的"一个框,两条路"):快路直接打 B 站搜索接口瞬时返回,
 * 慢路起 agent 循环。区别只在这里的分派,UI 侧是同一串轮次。
 *
 * **轮次只留在内存里,落库的只有普通搜索的关键词。** 这里原先写着"DESIGN 2.2 明确不做搜索
 * 历史",那是读错了:2.2 禁的是「相关推荐」和「热搜词」,而 DESIGN 的技术选型表里本来就列着
 * 要存搜索历史。两者不是一回事 —— 热搜词是把别人的热门查询推给你,搜索历史是你自己敲过的字,
 * 前者是推送式入口,后者只是省一次重复输入。
 *
 * 助理那一路的提问**不记**:它的上下文按 DESIGN 3.3 只含本次意图,把提问攒成一份可点的清单
 * 等于给它做了一份会话历史。
 */
class SearchChatViewModel(
    private val searchRepository: SearchRepository,
    private val agentLoop: AgentLoop,
    private val settings: SettingsStore,
) : ViewModel() {

    /** 最近搜过的词,最多 [SettingsStore.SEARCH_HISTORY_LIMIT] 条,最近的在前。 */
    val searchHistory: StateFlow<List<String>> = settings.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeSearchHistory(query: String) = viewModelScope.launch { settings.removeSearchHistory(query) }

    /** 点历史里的一条 = 把它填回输入框并直接搜。 */
    /**
     * 点历史词条**只把它填回输入框,不直接搜**。历史里的词多半是"上次搜的那个,再改一点",
     * 直接发出去的话想改就只能重打一遍。要搜就按发送,和手打没有区别。
     */
    fun fillFromHistory(query: String) {
        _state.update { it.copy(input = query, mode = SearchMode.Normal) }
    }

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

    /**
     * 普通搜索整套状态机在 [NormalSearchController] 里(标签结果页共用同一套)。它是
     * `normal` 那一格的唯一写入方,这边只镜像进 [SearchChatUiState] —— UI 仍然只看一份状态。
     */
    private val normal = NormalSearchController(viewModelScope, searchRepository)

    init {
        viewModelScope.launch {
            normal.state.collect { n -> _state.update { it.copy(normal = n) } }
        }
    }

    private var nextTurnId = 1L

    fun onInputChange(value: String) = _state.update { it.copy(input = value) }

    /**
     * 两种模式各有各的状态,切换只是换显示哪一份,**都不清空**。会话要重开有右上角的
     * 显式入口;切一下模式就丢掉一整段对话,没人会预期。
     */
    fun onModeChange(mode: SearchMode) = _state.update { it.copy(mode = mode) }

    fun send() {
        val query = _state.value.input.trim()
        if (query.isEmpty()) return
        when (_state.value.mode) {
            // 普通搜索是一次查询一份结果,不累积轮次:它就是一个搜索页。留着上一次的结果
            // 只会让人往上翻,而翻上去的东西和这次要找的无关。排序沿用上一次选的那档 ——
            // 新起一份 NormalSearchState() 会把它悄悄弹回综合。
            // **查询词留在输入框里**,只把两端空白去掉。普通搜索的结果页没有别处显示当前查到的
            // 是什么,清空之后这一屏就没有任何东西说明这些结果从何而来,改一个字也得重打。
            // 留下之后 [SearchField] 尾部那个清除按钮才会出现 —— 它一直都在,只是从来没有过
            // 非空的输入可显示。
            SearchMode.Normal -> {
                _state.update { it.copy(input = query) }
                viewModelScope.launch { settings.addSearchHistory(query) }
                normal.search(query, _state.value.normal.order)
            }

            // 助理这边**照旧清空**:它是一段对话,发出去的话已经作为一轮留在上面了,输入框里
            // 再留一份就是同一句话印两遍,而下一句要问什么和上一句无关。
            SearchMode.Agent -> {
                val turnId = nextTurnId++
                _state.update { state ->
                    state.copy(
                        input = "",
                        agent = state.agent.copy(
                            turns = state.agent.turns +
                                SearchTurn(turnId, query, AgentTurnState(running = true)),
                        ),
                    )
                }
                runAgent(turnId, query)
            }
        }
    }

    fun loadMore() = normal.loadMore()

    fun onOrderChanged(order: SearchOrder) = normal.onOrderChanged(order)

    fun retry() {
        when (_state.value.mode) {
            SearchMode.Normal -> normal.retry()

            SearchMode.Agent -> {
                val turn = _state.value.agent.turns.lastOrNull() ?: return
                updateAgent(turn.id) { AgentTurnState(running = true) }
                runAgent(turn.id, turn.query)
            }
        }
    }

    /** 重新执行当前搜索/当前助理轮次，供下拉刷新和再次进入搜索页使用。 */
    fun refresh() = retry()

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
            try {
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
            } finally {
                // **收在 finally 里**:上面那句 cancel 打断的正是这个协程,写在 collect 之后
                // 的话被取消的那一轮永远停在 running=true,界面上是一个再也停不下来的转圈。
                // `_state.update` 不挂起,取消中照样执行;轮次已被"新会话"清掉时
                // [updateAgent] 找不到这个 id,自然什么都不做。
                updateAgent(turnId) { it.copy(running = false) }
            }
        }
    }

    private inline fun updateAgent(turnId: Long, crossinline block: (AgentTurnState) -> AgentTurnState) {
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

}

