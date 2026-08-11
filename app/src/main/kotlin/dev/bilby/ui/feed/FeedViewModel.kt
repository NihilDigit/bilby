package dev.bilby.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.data.DynamicRepository
import dev.bilby.data.FollowRepository
import dev.bilby.data.SettingsStore
import dev.bilby.data.db.FeedCacheRepository
import dev.bilby.data.db.FeedReadPositionRepository
import dev.bilby.data.model.FeedItem
import dev.bilby.ui.appendDistinctBy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FeedViewModel(
    private val repository: DynamicRepository,
    private val followRepository: FollowRepository,
    private val settings: SettingsStore,
    private val readPositionRepository: FeedReadPositionRepository,
    private val cacheRepository: FeedCacheRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedUiState(loading = true))
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    /** 抓下来的原样内容,不按排除名单过滤——过滤只在 [publishItems] 里对渲染出去的那份做,
     * 写入(以及本地缓存)不做,否则取消排除之后那些条目永远回不来。 */
    private var allItems: List<FeedItem> = emptyList()
    private var excludedMids = emptySet<Long>()

    private var nextOffset: String? = null

    /**
     * `nextOffset` 是否已经代表"缓存/网络翻到的真实深度",而不是刚刚一次头部刷新拿到的
     * 浅游标。只有翻页(`fetch(append = true)`)和"缓存本来是空的、这是第一页"这两种情况
     * 才会把它推进——见 [fetch] 里的用法,和 `FeedCacheRepository.mergeHead` 的同一条注释。
     */
    private var hasEstablishedCursor = false

    private var loadingPage = false

    /**
     * 当前滚动到的顶部条目,喂给下面的去抖落盘。用 StateFlow 而不是直接在滚动回调里
     * `viewModelScope.launch { save() }`:那样每次滚动事件都会开一个协程、打一次库,
     * 是明显错的写法。
     */
    private val visibleTopBvid = MutableStateFlow<String?>(null)

    /** 去抖落盘还没触发时的最新值,onCleared 里补一次用。 */
    private var pendingReadBvid: String? = null

    init {
        viewModelScope.launch {
            // 排除名单变了(设置页勾一个 / 清空),只需要用新名单重新过滤 allItems 再渲染一遍——
            // allItems 本来就没按排除名单过滤过,被移出名单的那些条目一直都在,不用像以前
            // 那样重新拉一遍网络才能找回来。
            settings.excludedFeedMids.collect { mids ->
                excludedMids = mids
                publishItems()
            }
        }
        // 只取一次。已读位置只用来在「刚进这一屏」时定位一次分隔线,如果跟着 Flow 一直订阅,
        // 后面自己滚动触发的落盘会把这条线立刻顶到当前位置,分隔线变得毫无意义。
        viewModelScope.launch {
            val marker = readPositionRepository.observe().first()
            _state.update { it.copy(readMarkerBvid = marker) }
        }
        // 去抖落盘:滚动期间每一帧都可能换一次顶部条目,只在停下来之后写一次。1.2 秒是「已经
        // 停手」和「还在划」之间一个不难感知出来的分界,不是量出来的精确值。
        viewModelScope.launch {
            visibleTopBvid.filterNotNull().distinctUntilChanged().debounce(READ_POSITION_DEBOUNCE_MS).collect { bvid ->
                pendingReadBvid = null
                // runCatching 而不是让异常直接冒出去:这是唯一一处持续订阅的 collect,
                // 一次写库失败(磁盘满一类)不该让整条去抖链路跟着死掉,后面的滚动就再也存不下了。
                runCatching { readPositionRepository.save(bvid) }
                    .onFailure { BiliLog.w("记已读位置失败", it) }
            }
        }
        // 先渲染缓存,缓存非空就不显示首屏 loading;随后总是发一次头部请求去后台核对更新
        // (下拉刷新走的是同一条路径,见 refresh())。缓存为空(第一次用、或缓存表刚被
        // fallbackToDestructiveMigration 冲掉)时退回老路径:首屏转圈,等网络回来。
        viewModelScope.launch {
            val cached = runCatching { cacheRepository.restore() }
                .onFailure { BiliLog.w("读动态流缓存失败", it) }
                .getOrNull()
            if (cached != null && cached.items.isNotEmpty()) {
                allItems = cached.items
                nextOffset = cached.nextOffset
                hasEstablishedCursor = true
                _state.update { it.copy(loading = false, hasMore = cached.hasMore) }
                publishItems()
                fetch(append = false)
            } else {
                loadFirstPage()
            }
        }
        loadTopUps()
    }

    /**
     * 开屏定位做过了。**这件事必须记在这一层**:动态页的 composition 会在进 UP 空间、切 tab
     * 时被销毁,而这个 VM 挂在 Activity 的 store 上(见 MainActivity 的 FeedPane),活到这次
     * 开屏结束。记在 composable 的 `remember` 里的那一版,返回动态页时滚动位置已经由
     * SaveableStateHolder 还原好了,flag 却忘了,于是又跳一次。
     */
    fun onLocated() = _state.update { it.copy(pendingLocate = false) }

    /** 由 [FeedScreen] 在滚动时上报当前顶部可见的条目。 */
    fun onVisibleTopChanged(bvid: String) {
        pendingReadBvid = bvid
        visibleTopBvid.value = bvid
    }

    /**
     * 去抖窗口还没到、页面就被关掉:上面那个 collect 协程跟着 viewModelScope 一起被取消,
     * 排在队里的这次落盘不会发生。这里补一次,NonCancellable 是因为此时 viewModelScope
     * 的 Job 已经在取消过程中,普通 launch 排的挂起点会在 upsert 写完前就被打断
     * ——同样的写法见 SettingsViewModel 的落盘。
     */
    override fun onCleared() {
        super.onCleared()
        val bvid = pendingReadBvid ?: return
        viewModelScope.launch(NonCancellable) { readPositionRepository.save(bvid) }
    }

    /**
     * 与动态流分开取,互不阻塞也互不牵连:这一排失败不该让整页显示错误,动态流失败也不该
     * 把它一起抹掉。失败就整排不显示 —— 它是快捷方式,没有它这一页照样能做正事。
     *
     * 但不显示不等于不留痕:界面上什么都不会有,不打这行日志就再也查不出它为什么没出来。
     */
    private fun loadTopUps() = viewModelScope.launch {
        // portal 这条照发:它同时带着"谁在播",而那份数据没有别的来源。
        val portal = followRepository.frequentUps()
        when (portal) {
            is BiliResult.Ok -> _state.update {
                it.copy(liveUps = portal.value.liveUsers, liveCount = portal.value.liveCount)
            }
            is BiliResult.ApiError -> BiliLog.w("取正在直播失败(${portal.code}): ${portal.message}")
            is BiliResult.Failure -> BiliLog.w("取正在直播异常", portal.cause)
        }

        // 那一排人优先用「特别关注」——那是用户自己划的一组人。**没划过就退回最常访问**:
        // 一排空位比一份不是自己排的名单更没用,而"没有特别关注"是个很常见的状态。
        val special = when (val result = followRepository.groupMembers(SPECIAL_GROUP_ID, page = 1)) {
            is BiliResult.Ok -> result.value
            is BiliResult.ApiError -> emptyList<dev.bilby.data.UpBrief>()
                .also { BiliLog.w("取特别关注失败(${result.code}): ${result.message}") }
            is BiliResult.Failure -> emptyList<dev.bilby.data.UpBrief>()
                .also { BiliLog.w("取特别关注异常", result.cause) }
        }
        _state.update {
            if (special.isNotEmpty()) {
                it.copy(topUps = special, topUpsAreSpecial = true)
            } else {
                it.copy(topUps = (portal as? BiliResult.Ok)?.value?.ups.orEmpty(), topUpsAreSpecial = false)
            }
        }
    }

    /**
     * 下拉刷新:重新拿一次头部,顶上那排也一并重取(关注关系可能在别处变过)。**不重置
     * `nextOffset`**——那是已经翻到的深度,刷新只影响列表最前面那一小段,清成 null 会让用户
     * 翻到已加载列表/缓存底部时,中间已经翻过的那些页被重新请求一遍。
     */
    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        fetch(append = false)
        loadTopUps()
    }

    /**
     * 真正意义上的"从头来过":缓存是空的(第一次用)、或者首屏出错之后用户点了重试。
     * 这两种情况下 allItems 本来就是空的,直接把 loading 打开等网络。
     */
    fun loadFirstPage() {
        nextOffset = null
        hasEstablishedCursor = false
        allItems = emptyList()
        publishItems()
        _state.update { it.copy(loading = true, error = null) }
        fetch(append = false)
    }

    fun loadMore() {
        if (loadingPage || !_state.value.hasMore) return
        _state.update { it.copy(appending = true) }
        fetch(append = true)
    }

    /**
     * @param append false = 头部请求(offset 恒为 null,拿最新一页);true = 翻页续接
     * (用当前 `nextOffset`,翻到更旧的内容)。两条路径对 `allItems`/游标/本地缓存的处理方式
     * 不同,核心区别是:头部请求可能只是"核对更新",不代表真的翻到了新的深度;翻页请求则一定
     * 代表深度前进了一格,见分支里的注释。
     */
    private fun fetch(append: Boolean) {
        loadingPage = true
        viewModelScope.launch {
            val requestOffset = if (append) nextOffset else null
            when (val page = repository.loadVideoFeed(requestOffset)) {
                is BiliResult.Ok -> {
                    val fresh = page.value.items
                    // 这次请求是否真的把翻页深度往前推了一格——只有这种情况才该更新
                    // nextOffset/hasMore,单纯核对头部的请求不该覆盖已经翻到的更深位置。
                    var cursorAdvanced = false
                    if (append) {
                        allItems = allItems.appendDistinctBy(fresh) { it.bvid }
                        cacheRepository.appendTail(fresh, page.value.nextOffset, page.value.hasMore)
                        nextOffset = page.value.nextOffset
                        hasEstablishedCursor = true
                        cursorAdvanced = true
                    } else {
                        allItems = mergeFeedAtHead(allItems, fresh)
                        cacheRepository.mergeHead(fresh)
                        if (!hasEstablishedCursor) {
                            // 缓存本来是空的,这次头部请求其实就是唯一一次"第一页"。
                            cacheRepository.advanceCursor(page.value.nextOffset, page.value.hasMore)
                            nextOffset = page.value.nextOffset
                            hasEstablishedCursor = true
                            cursorAdvanced = true
                        }
                    }
                    publishItems()
                    _state.update { current ->
                        current.copy(
                            loading = false,
                            appending = false,
                            refreshing = false,
                            hasMore = if (cursorAdvanced) page.value.hasMore && page.value.nextOffset != null else current.hasMore,
                            error = null,
                        )
                    }
                }

                is BiliResult.ApiError -> setError("${page.message}(${page.code})")
                is BiliResult.Failure -> setError(page.cause.message ?: "网络错误")
            }
            loadingPage = false
        }
    }

    /** allItems 按当前排除名单过滤后才是真正渲染的那份,见类头注释。 */
    private fun publishItems() {
        _state.update { it.copy(items = allItems.filterNot { item -> item.upMid in excludedMids }) }
    }

    private fun setError(message: String) {
        _state.update { it.copy(loading = false, appending = false, refreshing = false, error = message) }
    }

    fun excludeUp(mid: Long) {
        if (mid == 0L) return
        excludedMids = excludedMids + mid
        publishItems()
        viewModelScope.launch { settings.excludeFeedMid(mid) }
    }

    private companion object {
        const val READ_POSITION_DEBOUNCE_MS = 1_200L

        /** 「特别关注」在关注分组里的固定 tagid,见 FollowRepository.groups。 */
        const val SPECIAL_GROUP_ID = -10L
    }
}

/**
 * 把新抓到的一页(动态流的头部请求永远从最新开始)并到已有列表的头部:已经缓存过的条目原地
 * 更新字段(播放量之类会变)、不改变它的位置;真正没见过的条目按 [fresh] 里的相对顺序整体
 * 插到最前面。
 *
 * 前提和 DynamicRepository 的分页语义一致:同一次响应里的条目本来就按时间新→旧排列,新出现
 * 的动态必然比所有已缓存的条目更新——所以"没见过的整体放最前面"而不是逐条比较发布时间去精确
 * 插入,已经是对的结果,不需要更复杂的合并算法。
 */
internal fun mergeFeedAtHead(current: List<FeedItem>, fresh: List<FeedItem>): List<FeedItem> {
    if (fresh.isEmpty()) return current
    val currentBvids = current.mapTo(HashSet()) { it.bvid }
    val freshByBvid = fresh.associateBy { it.bvid }
    val newAtHead = fresh.filterNot { it.bvid in currentBvids }
    val refreshedRest = current.map { freshByBvid[it.bvid] ?: it }
    return newAtHead + refreshedRest
}
