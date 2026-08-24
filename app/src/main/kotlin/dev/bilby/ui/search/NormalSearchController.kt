package dev.bilby.ui.search

import dev.bilby.api.BiliResult
import dev.bilby.api.CODE_NOT_LOGGED_IN
import dev.bilby.api.CODE_RATE_LIMITED
import dev.bilby.data.SearchRepository
import dev.bilby.data.SearchUser
import dev.bilby.data.SearchVideo
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 普通搜索的状态:**一次查询一份结果**,不留历史。它就是一个搜索页,上一次搜了什么
 * 和这一次无关。
 */
data class NormalSearchState(
    val query: String = "",
    val order: SearchOrder = SearchOrder.Comprehensive,
    val videos: List<SearchVideo> = emptyList(),
    val users: List<SearchUser> = emptyList(),
    // 视频和用户两路请求并行、互相独立(性能计划 7.1):慢的那路失败或还没回来
    // 不能挡住已经到手的另一路,所以 loading/error 各记各的,不共用一份粗粒度状态。
    val videoLoading: Boolean = false,
    val videoError: String? = null,
    val userLoading: Boolean = false,
    val userError: String? = null,
    val appending: Boolean = false,
    val hasMore: Boolean = true,
)

/**
 * 普通搜索的一整套状态机:分页游标、跨页去重、generation 守卫、视频/用户两路并行。
 *
 * 从 [SearchChatViewModel] 抽出来,因为标签结果页(`SearchResultViewModel`)要的是同一套
 * 行为 —— 同一个接口、同一份排序、同一种分页,差别只在宿主:一个长在搜索 tab 里,另一个
 * 是压栈的目的地。各写一份的话,这里每一条注释记下的坑(迟到响应写共享字段、分页游标
 * 归位时机、取消旧代不摘新代的 loading)都要靠人记得抄全。
 *
 * 生命周期跟着 [scope] 走,宿主用自己的 viewModelScope 传进来即可,不需要另行清理。
 */
class NormalSearchController(
    private val scope: CoroutineScope,
    private val searchRepository: SearchRepository,
) {
    private val _state = MutableStateFlow(NormalSearchState())
    val state: StateFlow<NormalSearchState> = _state.asStateFlow()

    /** 普通搜索当前翻到第几页。它只有一份结果,不需要按轮次记。 */
    private var page = 1

    /**
     * 已经收下的 bvid。分页边界上同一条稿件会重出,而 UI 拿 bvid 当 LazyColumn 的
     * key —— 重复即崩溃(「Key ... was already used」)。仓库那层只能管住单页内部,跨页
     * 只有这里知道。
     */
    private val seenBvids = mutableSetOf<String>()

    /**
     * 这条路自己的 generation(性能计划 7.2)。query、排序或翻页目标一变就加一,
     * 旧一代的响应落地前都要先比对这个数,对不上就是迟到的,整条丢弃 —— 包括对
     * [seenBvids]、[page] 这些跨请求共享状态的写入,不能等到 `_state.update` 才拦。
     */
    private var generation = 0

    /** 当前这一代视频/用户请求所在的父 Job,下一代开始前先取消它,两路子请求跟着一起停。 */
    private var searchJob: Job? = null

    /**
     * 换关键词、换排序、重试,对结果集都是同一件事:从第一页重来。三个入口原先各自 copy
     * 一份状态,于是各漏各的 —— 换排序漏了 `appending`(切排序时正在续页的话,被取消的那一代
     * 不会清它,续页就此卡住),重试漏了 `hasMore`(翻到底之后下拉刷新,页面回到第一页而
     * `hasMore` 还是 false,再也翻不动)。分页状态只在这一处归位。
     *
     * @param keepVisibleResults 下拉刷新用。列表留在屏幕上直到新的第一页落地,否则一下拉就
     *   整屏空白再重画,而刷新指示器本身(`videoLoading && videos.isNotEmpty()`)也会立刻熄灭。
     */
    fun search(query: String, order: SearchOrder, keepVisibleResults: Boolean = false) {
        seenBvids.clear()
        _state.update {
            it.copy(
                query = query,
                order = order,
                videos = if (keepVisibleResults) it.videos else emptyList(),
                users = if (keepVisibleResults) it.users else emptyList(),
                hasMore = true,
                appending = false,
                videoLoading = true,
                userLoading = true,
                videoError = null,
                userError = null,
            )
        }
        run(query, page = 1, order)
    }

    /**
     * 续页只在**首页已经落地**之后才成立。列表在首页返回之前就已经排好版(此刻只有排序行
     * 和页脚两个 item),UI 那侧的预取条件因此立刻满足;放行的话 [run] 会先取消掉
     * 正在飞的第一页,再按 [page] 发一个续页 —— 而那个 [page] 还停在上一次搜索翻到的位置,
     * 于是同一个词每次落到一段任意偏移的结果上。
     *
     * 首页出错时同样不续:往一个没建立起来的结果集后面追加没有意义,那一屏给的是重试。
     */
    fun loadMore() {
        val current = _state.value
        if (current.videoLoading || current.videoError != null) return
        if (current.appending || !current.hasMore || current.query.isEmpty()) return
        _state.update { it.copy(appending = true) }
        run(current.query, page = page + 1, current.order)
    }

    /**
     * 切排序等于换了一份不同的结果集,不是往当前结果里插队:只换 order 参数继续 append
     * 会把两种排序的结果拼在一条列表里。
     *
     * 还没搜过东西时只记下这一档,不发请求 —— 没有关键词可搜。
     */
    fun onOrderChanged(order: SearchOrder) {
        val current = _state.value
        if (current.order == order) return
        if (current.query.isEmpty()) {
            _state.update { it.copy(order = order) }
            return
        }
        search(current.query, order)
    }

    /** 重试和下拉刷新:同一个词从第一页重来,已有结果留在屏幕上等新页落地。 */
    fun retry() {
        val current = _state.value
        val query = current.query.ifEmpty { return }
        search(query, current.order, keepVisibleResults = true)
    }

    /**
     * 视频和用户两路请求独立发起(性能计划 7.1):视频先回先发布,用户回来了再并进去,
     * 慢的或失败的那路不拖累已经能看的结果。分页(page > 1)时不重新拉用户 —— 用户结果
     * 只在第一页有意义,原逻辑就是这样。
     *
     * 两路共用同一个父 Job:下一次 query/排序/翻页触发时,取消父 Job 就把两个子协程一起
     * 停掉,不需要分别记两个 Job 引用。
     */
    private fun run(query: String, page: Int, order: SearchOrder) {
        val gen = ++generation
        // 分页游标在**请求发出时**归位,不等响应回来。它是所有入口(回车、换排序、重试)共同的
        // 收口处,写在这里比让三个调用方各自记得清一遍可靠。留到响应落地才写的那一版,意味着
        // 首页在途期间 page 还是上一次搜索的值,任何一次续页都会从一个与本次查询无关的偏移开始。
        if (page == 1) this.page = 1
        searchJob?.cancel()
        searchJob = scope.launch {
            launch { runVideos(gen, query, page, order) }
            if (page == 1) launch { runUsers(gen, query) }
        }
    }

    private suspend fun runVideos(gen: Int, query: String, page: Int, order: SearchOrder) {
        try {
            val result = searchRepository.searchVideos(keyword = query, page = page, order = order.apiValue)
            // 迟到的响应连 seenBvids/page 这些跨请求共享的字段都不该碰,所以在提交
            // 之前先拦一次,不能只靠 _state.update 里那道检查。
            if (gen != generation) return
            when (result) {
                is BiliResult.Ok -> {
                    this.page = page
                    if (page == 1) seenBvids.clear()
                    val fresh = result.value.items.filter { seenBvids.add(it.bvid) }
                    _state.update {
                        it.copy(
                            videos = if (page == 1) fresh else it.videos + fresh,
                            hasMore = result.value.hasMore,
                            videoError = null,
                        )
                    }
                }

                is BiliResult.ApiError -> _state.update { it.copy(videoError = apiErrorText(result)) }

                is BiliResult.Failure -> _state.update { it.copy(videoError = failureText(result.cause)) }
            }
        } finally {
            // 按当前 generation 释放:被取消的旧一代不该把新一代刚置上的 loading 又扒下来。
            if (gen == generation) {
                _state.update { it.copy(videoLoading = false, appending = false) }
            }
        }
    }

    private suspend fun runUsers(gen: Int, query: String) {
        try {
            val result = searchRepository.searchUsers(query)
            if (gen != generation) return
            when (result) {
                is BiliResult.Ok -> _state.update { it.copy(users = result.value, userError = null) }

                // 带上"UP 主"三个字:这一行挨着视频结果显示,不说清是哪一路失败的话,
                // 读起来像整页都出了问题。
                is BiliResult.ApiError -> _state.update {
                    it.copy(userError = "UP 主搜索失败:${apiErrorText(result)}")
                }

                is BiliResult.Failure -> _state.update {
                    it.copy(userError = "UP 主搜索失败:${failureText(result.cause)}")
                }
            }
        } finally {
            if (gen == generation) _state.update { it.copy(userLoading = false) }
        }
    }
}

/**
 * 接口返回错误码时屏幕上说的那句话。
 *
 * **接口自己的 message 和错误码都不上屏。** 那句 message 是写给调用方看的("请求错误"、
 * "啊哦,出错了"),错误码更是;摆到界面上,用户拿着它做不了任何事,而这一屏要回答的是
 * "现在该做什么"。原文和错误码 [dev.bilby.api.BiliClient] 每条请求都已经写进
 * [dev.bilby.BiliLog],这里不再打第二遍。
 */
private fun apiErrorText(error: BiliResult.ApiError): String = when (error.code) {
    CODE_NOT_LOGGED_IN -> "登录已过期,重新登录后再搜"
    CODE_RATE_LIMITED -> "请求太频繁,过一会儿再试"
    else -> "服务暂时不可用,稍后重试"
}

/**
 * 请求根本没走完时说的那句话。分两档:[IOException] 一族(DNS、连不上、超时、证书)是
 * 用户自己能处理的,剩下的(解析不了返回体一类)他做什么都没用,只能说清没成功。
 */
private fun failureText(cause: Throwable): String =
    if (cause is IOException) "网络不通,检查网络后重试" else "搜索没能完成,稍后重试"
