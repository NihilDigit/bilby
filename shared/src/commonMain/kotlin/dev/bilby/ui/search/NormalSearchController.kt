package dev.bilby.ui.search

import dev.bilby.resources.*
import org.jetbrains.compose.resources.StringResource
import dev.bilby.api.BiliResult
import dev.bilby.api.CODE_NOT_LOGGED_IN
import dev.bilby.api.CODE_RATE_LIMITED
import dev.bilby.api.CODE_RISK_CHALLENGE
import java.time.LocalDate
import java.time.ZoneId
import dev.bilby.api.map
import dev.bilby.data.SearchArticle
import dev.bilby.data.SearchPage
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

/** 结果分三栏。番剧、影视、直播不在其中:这个应用只放用户投稿(README 的边界)。 */
enum class SearchTab(val labelRes: StringResource) {
    Video(Res.string.search_tab_video),
    User(Res.string.search_tab_user),
    Article(Res.string.search_tab_article),
}

/** 视频时长筛选。传参用 [apiValue](notes/space-and-search.md 2.5,即枚举下标)。 */
enum class SearchDuration(val apiValue: Int, val labelRes: StringResource) {
    All(0, Res.string.search_duration_all),
    UnderTen(1, Res.string.search_duration_under_10),
    TenToThirty(2, Res.string.search_duration_10_30),
    ThirtyToSixty(3, Res.string.search_duration_30_60),
    OverSixty(4, Res.string.search_duration_over_60),
}

/**
 * 视频的发布时间窗。窗口按本地日历日算,止于今天 23:59:59,照 PiliPlus 的
 * `search_panel/video/controller.dart`:「最近一天」是今天一整天,「最近一周」含今天共七天。
 */
enum class SearchPubTime(private val daysBack: Int?, val labelRes: StringResource) {
    All(null, Res.string.search_pubtime_all),
    Day(0, Res.string.search_pubtime_day),
    Week(6, Res.string.search_pubtime_week),
    HalfYear(179, Res.string.search_pubtime_half_year),
    ;

    /** 秒级时间戳的闭区间;[All] 不限,为 null。发请求时才算,跨过午夜的翻页也不会用旧窗口。 */
    fun window(zone: ZoneId = ZoneId.systemDefault()): LongRange? {
        val days = daysBack ?: return null
        val today = LocalDate.now(zone)
        val begin = today.minusDays(days.toLong()).atStartOfDay(zone).toEpochSecond()
        val end = today.plusDays(1).atStartOfDay(zone).toEpochSecond() - 1
        return begin..end
    }
}

/**
 * 视频分区(`tids`,notes 2.6)。**只列投稿分区**:番剧、国创、电影、电视、纪录片是版权方的
 * 内容,这个应用不放(README 的边界),筛进去只会得到一页打不开的条目。
 */
enum class SearchZone(val tids: Int?, val labelRes: StringResource) {
    All(null, Res.string.search_zone_all),
    Douga(1, Res.string.search_zone_douga),
    Music(3, Res.string.search_zone_music),
    Dance(129, Res.string.search_zone_dance),
    Game(4, Res.string.search_zone_game),
    Knowledge(36, Res.string.search_zone_knowledge),
    Tech(188, Res.string.search_zone_tech),
    Sports(234, Res.string.search_zone_sports),
    Car(223, Res.string.search_zone_car),
    Life(160, Res.string.search_zone_life),
    Food(221, Res.string.search_zone_food),
    Animal(217, Res.string.search_zone_animal),
    Kichiku(119, Res.string.search_zone_kichiku),
    Fashion(115, Res.string.search_zone_fashion),
    Info(202, Res.string.search_zone_info),
    Ent(5, Res.string.search_zone_ent),
    Cinephile(181, Res.string.search_zone_cinephile),
}

/** 用户栏的排序,notes 2.4 的 UserOrderType 原样。 */
enum class SearchUserOrder(val apiValue: String, val ascending: Boolean, val labelRes: StringResource) {
    Default("", false, Res.string.search_user_order_default),
    FansDesc("fans", false, Res.string.search_user_order_fans_desc),
    FansAsc("fans", true, Res.string.search_user_order_fans_asc),
    LevelDesc("level", false, Res.string.search_user_order_level_desc),
    LevelAsc("level", true, Res.string.search_user_order_level_asc),
}

/** 一栏结果的翻页状态。三栏各一份,互不牵连:一栏失败或还在飞不挡另一栏。 */
data class SearchListState<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val appending: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = true,
    /** 当前关键词下这一栏发起过没有。切到一栏时据此决定要不要拉第一页。 */
    val started: Boolean = false,
)

/**
 * 普通搜索的状态:**一次查询一份结果**,不留历史。它就是一个搜索页,上一次搜了什么
 * 和这一次无关。
 */
data class NormalSearchState(
    val query: String = "",
    val tab: SearchTab = SearchTab.Video,
    val order: SearchOrder = SearchOrder.Comprehensive,
    val duration: SearchDuration = SearchDuration.All,
    val pubTime: SearchPubTime = SearchPubTime.All,
    val zone: SearchZone = SearchZone.All,
    /** 专栏的排序。和视频分开记:两栏的「按热度」不是同一个东西,切栏不该带过去。 */
    val articleOrder: SearchOrder = SearchOrder.Comprehensive,
    val userOrder: SearchUserOrder = SearchUserOrder.Default,
    val videos: SearchListState<SearchVideo> = SearchListState(),
    val users: SearchListState<SearchUser> = SearchListState(),
    val articles: SearchListState<SearchArticle> = SearchListState(),
)

/**
 * 普通搜索的一整套状态机。搜索 tab 与标签结果页(`SearchResultViewModel`)共用 —— 同一个
 * 接口、同一份排序、同一种分页,差别只在宿主。
 *
 * **三栏各一个 [Pager]**,翻页游标、跨页去重、generation 守卫都在那一处;三栏原先若各写一份,
 * 每一条坑(迟到响应写共享字段、分页游标归位时机、取消旧代不摘新代的 loading)都要抄三遍。
 *
 * **只拉当前那一栏。** 其余两栏等划过去、点过去才拉第一页:多数搜索只看视频,另外两路
 * 每次都陪着发是白打的请求。
 *
 * 生命周期跟着 [scope] 走,宿主用自己的 viewModelScope 传进来即可,不需要另行清理。
 */
class NormalSearchController(
    private val scope: CoroutineScope,
    private val searchRepository: SearchRepository,
) {
    private val _state = MutableStateFlow(NormalSearchState())
    val state: StateFlow<NormalSearchState> = _state.asStateFlow()

    private val videoPager = Pager(
        scope = scope,
        key = { it.bvid },
        fetch = { query, page ->
            val s = _state.value
            searchRepository.searchVideos(
                keyword = query,
                page = page,
                order = s.order.apiValue,
                duration = s.duration.apiValue.takeIf { it != 0 },
                tids = s.zone.tids,
                pubTime = s.pubTime.window(),
            ).map { SearchPage(it.items, it.hasMore) }
        },
        read = { it.videos },
        write = { s, list -> s.copy(videos = list) },
        state = _state,
    )

    private val userPager = Pager(
        scope = scope,
        key = { it.mid },
        fetch = { query, page ->
            val order = _state.value.userOrder
            searchRepository.searchUserPage(query, page, order.apiValue, order.ascending)
        },
        read = { it.users },
        write = { s, list -> s.copy(users = list) },
        state = _state,
    )

    private val articlePager = Pager(
        scope = scope,
        key = { it.id },
        fetch = { query, page -> searchRepository.searchArticles(query, page, _state.value.articleOrder.apiValue) },
        read = { it.articles },
        write = { s, list -> s.copy(articles = list) },
        state = _state,
    )

    private fun pagerFor(tab: SearchTab): Pager<*> = when (tab) {
        SearchTab.Video -> videoPager
        SearchTab.User -> userPager
        SearchTab.Article -> articlePager
    }

    /**
     * 换关键词:三栏全部归零,当前栏拉第一页。
     *
     * @param keepVisibleResults 下拉刷新用。列表留在屏幕上直到新的第一页落地,否则一下拉就
     *   整屏空白再重画,而刷新指示器本身也会立刻熄灭。
     */
    fun search(query: String, keepVisibleResults: Boolean = false) {
        _state.update { it.copy(query = query) }
        SearchTab.entries.forEach { pagerFor(it).reset(keepVisibleResults && it == _state.value.tab) }
        pagerFor(_state.value.tab).start(query)
    }

    /** 切栏。这一栏在当前关键词下还没发起过就拉第一页;发起过的保留原样,切回来不重拉。 */
    fun selectTab(tab: SearchTab) {
        _state.update { it.copy(tab = tab) }
        val query = _state.value.query
        if (query.isEmpty()) return
        val pager = pagerFor(tab)
        if (!pager.started()) pager.start(query)
    }

    fun loadMore() {
        val s = _state.value
        if (s.query.isEmpty()) return
        pagerFor(s.tab).loadMore(s.query)
    }

    /**
     * 切排序等于换了一份不同的结果集,不是往当前结果里插队:只换 order 参数继续 append
     * 会把两种排序的结果拼在一条列表里。还没搜过东西时只记下这一档。
     */
    fun onOrderChanged(order: SearchOrder) {
        if (_state.value.order == order) return
        _state.update { it.copy(order = order) }
        restart(videoPager)
    }

    fun onDurationChanged(duration: SearchDuration) {
        if (_state.value.duration == duration) return
        _state.update { it.copy(duration = duration) }
        restart(videoPager)
    }

    fun onPubTimeChanged(pubTime: SearchPubTime) {
        if (_state.value.pubTime == pubTime) return
        _state.update { it.copy(pubTime = pubTime) }
        restart(videoPager)
    }

    fun onZoneChanged(zone: SearchZone) {
        if (_state.value.zone == zone) return
        _state.update { it.copy(zone = zone) }
        restart(videoPager)
    }

    fun onArticleOrderChanged(order: SearchOrder) {
        if (_state.value.articleOrder == order) return
        _state.update { it.copy(articleOrder = order) }
        restart(articlePager)
    }

    fun onUserOrderChanged(order: SearchUserOrder) {
        if (_state.value.userOrder == order) return
        _state.update { it.copy(userOrder = order) }
        restart(userPager)
    }

    /** 重试和下拉刷新:当前栏从第一页重来,已有结果留在屏幕上等新页落地。 */
    fun retry() {
        val s = _state.value
        if (s.query.isEmpty()) return
        val pager = pagerFor(s.tab)
        pager.reset(keepVisible = true)
        pager.start(s.query)
    }

    private fun restart(pager: Pager<*>) {
        val query = _state.value.query
        pager.reset(keepVisible = false)
        if (query.isNotEmpty()) pager.start(query)
    }
}

/**
 * 一栏结果的翻页器。
 *
 * - **generation**:query、排序或翻页目标一变就加一,旧一代的响应落地前先比对,对不上整条丢弃
 *   —— 包括对 [seen]、[page] 这些跨请求共享状态的写入,不能等到 `_state.update` 才拦。
 * - **分页游标在请求发出时归位**,不等响应回来:留到响应落地才写的话,首页在途期间 page 还是
 *   上一次搜索的值,任何一次续页都会从一个与本次查询无关的偏移开始。
 * - **跨页去重**:分页边界上同一条会重出,而 UI 拿它当 LazyColumn 的 key,重复即崩溃。
 */
private class Pager<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> Any,
    private val fetch: suspend (query: String, page: Int) -> BiliResult<SearchPage<T>>,
    private val read: (NormalSearchState) -> SearchListState<T>,
    private val write: (NormalSearchState, SearchListState<T>) -> NormalSearchState,
    private val state: MutableStateFlow<NormalSearchState>,
) {
    private var page = 1
    private val seen = mutableSetOf<Any>()
    private var generation = 0
    private var job: Job? = null

    private fun update(block: (SearchListState<T>) -> SearchListState<T>) =
        state.update { write(it, block(read(it))) }

    fun started(): Boolean = read(state.value).started

    /** 回到"这个关键词下还没发起过"。取消在飞的请求,让它们的响应作废。 */
    fun reset(keepVisible: Boolean) {
        generation++
        job?.cancel()
        page = 1
        seen.clear()
        update { if (keepVisible) it.copy(started = false, error = null) else SearchListState() }
    }

    fun start(query: String) {
        update { it.copy(started = true, loading = true, appending = false, error = null, hasMore = true) }
        run(query, page = 1)
    }

    /**
     * 续页只在**首页已经落地**之后才成立。列表在首页返回之前就已经排好版,UI 那侧的预取条件
     * 因此立刻满足;放行的话会先取消正在飞的第一页,再从一个任意偏移续页。首页出错时同样不续。
     */
    fun loadMore(query: String) {
        val current = read(state.value)
        if (!current.started || current.loading || current.error != null) return
        if (current.appending || !current.hasMore || current.items.isEmpty()) return
        update { it.copy(appending = true) }
        run(query, page = page + 1)
    }

    private fun run(query: String, page: Int) {
        val gen = ++generation
        if (page == 1) this.page = 1
        job?.cancel()
        job = scope.launch {
            try {
                val result = fetch(query, page)
                if (gen != generation) return@launch
                when (result) {
                    is BiliResult.Ok -> {
                        this@Pager.page = page
                        if (page == 1) seen.clear()
                        val fresh = result.value.items.filter { seen.add(key(it)) }
                        update {
                            it.copy(
                                items = if (page == 1) fresh else it.items + fresh,
                                hasMore = result.value.hasMore,
                                error = null,
                            )
                        }
                    }

                    is BiliResult.ApiError -> update { it.copy(error = apiErrorText(result)) }
                    is BiliResult.Failure -> update { it.copy(error = failureText(result.cause)) }
                }
            } finally {
                // 按当前 generation 释放:被取消的旧一代不该把新一代刚置上的 loading 又扒下来。
                if (gen == generation) update { it.copy(loading = false, appending = false) }
            }
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
    // 验证码这一步还没有做(要一个 WebView 跑极验),只能说清是什么拦住了。
    CODE_RISK_CHALLENGE -> "B 站要求验证身份,稍后再试"
    else -> "服务暂时不可用,稍后重试"
}

/**
 * 请求根本没走完时说的那句话。分两档:[IOException] 一族(DNS、连不上、超时、证书)是
 * 用户自己能处理的,剩下的(解析不了返回体一类)他做什么都没用,只能说清没成功。
 */
private fun failureText(cause: Throwable): String =
    if (cause is IOException) "网络不通,检查网络后重试" else "搜索没能完成,稍后重试"
