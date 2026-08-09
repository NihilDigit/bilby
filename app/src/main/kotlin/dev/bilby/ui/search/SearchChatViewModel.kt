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
import dev.bilby.api.BiliResult
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
    fun searchFromHistory(query: String) {
        _state.update { it.copy(input = query, mode = SearchMode.Normal) }
        send()
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

    private var nextTurnId = 1L
    /** 普通搜索当前翻到第几页。它只有一份结果,不需要按轮次记。 */
    private var page = 1

    /**
     * 普通搜索已经收下的 bvid。分页边界上同一条稿件会重出,而 UI 拿 bvid 当 LazyColumn 的
     * key —— 重复即崩溃(「Key ... was already used」)。仓库那层只能管住单页内部,跨页
     * 只有这里知道。
     */
    private val seenSearchBvids = mutableSetOf<String>()

    /**
     * 普通搜索这条路自己的 generation(性能计划 7.2)。query、排序或翻页目标一变就加一,
     * 旧一代的响应落地前都要先比对这个数,对不上就是迟到的,整条丢弃 —— 包括对
     * [seenSearchBvids]、[page] 这些跨请求共享状态的写入,不能等到 `_state.update` 才拦。
     */
    private var normalGeneration = 0

    /** 当前这一代视频/用户请求所在的父 Job,下一代开始前先取消它,两路子请求跟着一起停。 */
    private var searchJob: Job? = null

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
            // 只会让人往上翻,而翻上去的东西和这次要找的无关。排序沿用上一次选的那档 ——
            // 新起一份 NormalSearchState() 会把它悄悄弹回综合。
            SearchMode.Normal -> {
                viewModelScope.launch { settings.addSearchHistory(query) }
                startNormalSearch(query, _state.value.normal.order)
            }

            SearchMode.Agent -> {
                val turnId = nextTurnId++
                _state.update { state ->
                    state.copy(
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

    /**
     * 续页只在**首页已经落地**之后才成立。列表在首页返回之前就已经排好版(此刻只有排序行
     * 和页脚两个 item),UI 那侧的预取条件因此立刻满足;放行的话 [runNormal] 会先取消掉
     * 正在飞的第一页,再按 [page] 发一个续页 —— 而那个 [page] 还停在上一次搜索翻到的位置,
     * 于是同一个词每次落到一段任意偏移的结果上。
     *
     * 首页出错时同样不续:往一个没建立起来的结果集后面追加没有意义,那一屏给的是重试。
     */
    fun loadMore() {
        val normal = _state.value.normal
        if (normal.videoLoading || normal.videoError != null) return
        if (normal.appending || !normal.hasMore || normal.query.isEmpty()) return
        _state.update { it.copy(normal = it.normal.copy(appending = true)) }
        runNormal(normal.query, page = page + 1, normal.order)
    }

    /**
     * 换关键词、换排序、重试,对结果集都是同一件事:从第一页重来。三个入口原先各自 copy
     * 一份状态,于是各漏各的 —— 换排序漏了 `appending`(切排序时正在续页的话,被取消的那一代
     * 不会清它,续页就此卡住),重试漏了 `hasMore`(翻到底之后下拉刷新,页面回到第一页而
     * `hasMore` 还是 false,再也翻不动)。分页状态只在这一处归位。
     *
     * @param keepVisibleResults 下拉刷新用。列表留在屏幕上直到新的第一页落地,否则一下拉就
     *   整屏空白再重画,而刷新指示器本身(`videoLoading && videos.isNotEmpty()`)也会立刻熄灭。
     */
    private fun startNormalSearch(query: String, order: SearchOrder, keepVisibleResults: Boolean = false) {
        seenSearchBvids.clear()
        _state.update {
            it.copy(
                normal = it.normal.copy(
                    query = query,
                    order = order,
                    videos = if (keepVisibleResults) it.normal.videos else emptyList(),
                    users = if (keepVisibleResults) it.normal.users else emptyList(),
                    hasMore = true,
                    appending = false,
                    videoLoading = true,
                    userLoading = true,
                    videoError = null,
                    userError = null,
                ),
            )
        }
        runNormal(query, page = 1, order)
    }

    /**
     * 切排序等于换了一份不同的结果集,不是往当前结果里插队:只换 order 参数继续 append
     * 会把两种排序的结果拼在一条列表里。
     *
     * 还没搜过东西时只记下这一档,不发请求 —— 没有关键词可搜。
     */
    fun onOrderChanged(order: SearchOrder) {
        val normal = _state.value.normal
        if (normal.order == order) return
        if (normal.query.isEmpty()) {
            _state.update { it.copy(normal = it.normal.copy(order = order)) }
            return
        }
        startNormalSearch(normal.query, order)
    }

    fun retry() {
        when (_state.value.mode) {
            SearchMode.Normal -> {
                val normal = _state.value.normal
                val query = normal.query.ifEmpty { return }
                startNormalSearch(query, normal.order, keepVisibleResults = true)
            }

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
     * 视频和用户两路请求独立发起(性能计划 7.1):视频先回先发布,用户回来了再并进去,
     * 慢的或失败的那路不拖累已经能看的结果。分页(page > 1)时不重新拉用户 —— 用户结果
     * 只在第一页有意义,原逻辑就是这样。
     *
     * 两路共用同一个父 Job:下一次 query/排序/翻页触发时,取消父 Job 就把两个子协程一起
     * 停掉,不需要分别记两个 Job 引用。
     */
    private fun runNormal(query: String, page: Int, order: SearchOrder) {
        val gen = ++normalGeneration
        // 分页游标在**请求发出时**归位,不等响应回来。它是所有入口(回车、换排序、重试)共同的
        // 收口处,写在这里比让三个调用方各自记得清一遍可靠。留到响应落地才写的那一版,意味着
        // 首页在途期间 page 还是上一次搜索的值,任何一次续页都会从一个与本次查询无关的偏移开始。
        if (page == 1) this.page = 1
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            launch { runVideos(gen, query, page, order) }
            if (page == 1) launch { runUsers(gen, query) }
        }
    }

    private suspend fun runVideos(gen: Int, query: String, page: Int, order: SearchOrder) {
        try {
            val result = searchRepository.searchVideos(keyword = query, page = page, order = order.apiValue)
            // 迟到的响应连 seenSearchBvids/page 这些跨请求共享的字段都不该碰,所以在提交
            // 之前先拦一次,不能只靠 _state.update 里那道检查。
            if (gen != normalGeneration) return
            when (result) {
                is BiliResult.Ok -> {
                    this@SearchChatViewModel.page = page
                    if (page == 1) seenSearchBvids.clear()
                    val fresh = result.value.items.filter { seenSearchBvids.add(it.bvid) }
                    _state.update {
                        it.copy(
                            normal = it.normal.copy(
                                videos = if (page == 1) fresh else it.normal.videos + fresh,
                                hasMore = result.value.hasMore,
                                videoError = null,
                            ),
                        )
                    }
                }

                is BiliResult.ApiError -> _state.update {
                    it.copy(normal = it.normal.copy(videoError = "${result.message}(${result.code})"))
                }

                is BiliResult.Failure -> _state.update {
                    it.copy(normal = it.normal.copy(videoError = result.cause.message ?: "网络错误"))
                }
            }
        } finally {
            // 按当前 generation 释放:被取消的旧一代不该把新一代刚置上的 loading 又扒下来。
            if (gen == normalGeneration) {
                _state.update { it.copy(normal = it.normal.copy(videoLoading = false, appending = false)) }
            }
        }
    }

    private suspend fun runUsers(gen: Int, query: String) {
        try {
            val result = searchRepository.searchUsers(query)
            if (gen != normalGeneration) return
            when (result) {
                is BiliResult.Ok -> _state.update {
                    it.copy(normal = it.normal.copy(users = result.value, userError = null))
                }

                is BiliResult.ApiError -> _state.update {
                    it.copy(normal = it.normal.copy(userError = "${result.message}(${result.code})"))
                }

                is BiliResult.Failure -> _state.update {
                    it.copy(normal = it.normal.copy(userError = result.cause.message ?: "网络错误"))
                }
            }
        } finally {
            if (gen == normalGeneration) _state.update { it.copy(normal = it.normal.copy(userLoading = false)) }
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
