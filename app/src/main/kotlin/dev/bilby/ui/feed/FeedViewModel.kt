package dev.bilby.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.data.DynamicFeedHalf
import dev.bilby.data.DynamicFeedStore
import dev.bilby.data.FollowRepository
import dev.bilby.data.SettingsStore
import dev.bilby.data.ToViewRepository
import dev.bilby.data.db.FeedReadPositionRepository
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
    private val store: DynamicFeedStore,
    private val followRepository: FollowRepository,
    private val settings: SettingsStore,
    private val readPositionRepository: FeedReadPositionRepository,
    private val toViewRepository: ToViewRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedUiState(loading = true))
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    /**
     * 当前滚动到的顶部条目,喂给下面的去抖落盘。用 StateFlow 而不是直接在滚动回调里
     * `viewModelScope.launch { save() }`:那样每次滚动事件都会开一个协程、打一次库,
     * 是明显错的写法。
     */
    private val visibleTopId = MutableStateFlow<String?>(null)

    /** 去抖落盘还没触发时的最新值,onCleared 里补一次用。 */
    private var pendingReadId: String? = null

    /** 见 [ExcludeUndo.id]。 */
    private var undoSeq = 0L

    /** 见 [ToViewNotice.id]。 */
    private var noticeSeq = 0L

    init {
        // 列表与状态都来自 [DynamicFeedStore],这一层不持有第二份。排除名单在 store 里就已经
        // 过滤掉了,所以这里没有"过滤后的那一份"和"原样的那一份"之分。
        viewModelScope.launch {
            store.home.collect { items -> _state.update { it.copy(items = items) } }
        }
        viewModelScope.launch {
            store.status.collect { status ->
                _state.update {
                    it.copy(
                        loading = status.loading,
                        appending = status.appending,
                        refreshing = status.refreshing,
                        hasMore = status.hasMore,
                        error = status.error,
                    )
                }
            }
        }
        viewModelScope.launch { store.ensureStarted(DynamicFeedHalf.Home) }
        // 去抖落盘:滚动期间每一帧都可能换一次顶部条目,只在停下来之后写一次。1.2 秒是「已经
        // 停手」和「还在划」之间一个不难感知出来的分界,不是量出来的精确值。
        viewModelScope.launch {
            visibleTopId.filterNotNull().distinctUntilChanged().debounce(READ_POSITION_DEBOUNCE_MS).collect { id ->
                pendingReadId = null
                // runCatching 而不是让异常直接冒出去:这是唯一一处持续订阅的 collect,
                // 一次写库失败(磁盘满一类)不该让整条去抖链路跟着死掉,后面的滚动就再也存不下了。
                runCatching { readPositionRepository.save(id) }
                    .onFailure { BiliLog.w("记已读位置失败", it) }
            }
        }
        loadTopUps()
    }

    /**
     * 回到这一屏。**分隔线的位置在这里取一次快照,此后到离开为止不再动。**
     *
     * 两件事都要:不跟着 Flow 一直订阅,否则自己滚动触发的落盘会把线立刻顶到当前位置,分隔线
     * 变得毫无意义;也不能只在 VM 创建时取一次,这个 VM 挂在 Activity 的 store 上(见
     * MainActivity 的 FeedPane),那样这条线会钉在一个越来越旧的位置活满整个进程,越过去也
     * 不消失。一次进屏就是它该有的寿命。
     */
    fun onEnterScreen() {
        viewModelScope.launch {
            val marker = readPositionRepository.observe().first()
            _state.update { it.copy(readMarkerEntryId = marker) }
        }
    }

    /**
     * 开屏定位做过了。**这件事必须记在这一层**:动态页的 composition 会在进 UP 空间、切 tab
     * 时被销毁,而这个 VM 活到这次开屏结束。记在 composable 的 `remember` 里的那一版,返回
     * 动态页时滚动位置已经由 SaveableStateHolder 还原好了,flag 却忘了,于是又跳一次。
     */
    fun onLocated() = _state.update { it.copy(pendingLocate = false) }

    /** 由 [FeedScreen] 在滚动时上报当前顶部可见的条目。 */
    fun onVisibleTopChanged(entryId: String) {
        pendingReadId = entryId
        visibleTopId.value = entryId
    }

    /**
     * 去抖窗口还没到、页面就被关掉:上面那个 collect 协程跟着 viewModelScope 一起被取消,
     * 排在队里的这次落盘不会发生。这里补一次,NonCancellable 是因为此时 viewModelScope
     * 的 Job 已经在取消过程中,普通 launch 排的挂起点会在 upsert 写完前就被打断
     * ——同样的写法见 SettingsViewModel 的落盘。
     */
    override fun onCleared() {
        super.onCleared()
        val id = pendingReadId ?: return
        viewModelScope.launch(NonCancellable) { readPositionRepository.save(id) }
    }

    /**
     * 与动态流分开取,互不阻塞也互不牵连:这一排失败不该让整页显示错误,动态流失败也不该
     * 把它一起抹掉。失败就整排不显示 —— 它是快捷方式,没有它这一页照样能做正事。
     *
     * 但不显示不等于不留痕:界面上什么都不会有,不打这行日志就再也查不出它为什么没出来。
     *
     * **不按排除名单过滤**:排除的语义是"首页不看他的投稿"(owner 定),他还在关注列表里,
     * 在播就该能进得去。这一排是导航,不是时间线。
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

    /** 下拉刷新:整段重取,顶上那排也一并重取(关注关系可能在别处变过)。 */
    fun refresh() {
        viewModelScope.launch { store.refresh(DynamicFeedHalf.Home) }
        loadTopUps()
    }

    /** 首屏出错之后的重试。列表空的时候 store 会自己把它当成首屏加载,这里不另设一份状态。 */
    fun retry() {
        viewModelScope.launch { store.refresh(DynamicFeedHalf.Home) }
    }

    fun loadMore() {
        viewModelScope.launch { store.loadMore(DynamicFeedHalf.Home) }
    }

    /**
     * 不再显示这个 UP 的投稿。**只写设置,不动列表** —— 列表按名单过滤的那一步在
     * [DynamicFeedStore] 里,写完自然就少了一条。从前这里还留了一份本地副本做乐观更新,
     * 于是同一个名单有两个写入方,谁盖谁看运气。
     */
    fun excludeUp(mid: Long, name: String) {
        if (mid == 0L) return
        viewModelScope.launch { settings.excludeFeedMid(mid, name) }
        _state.update { it.copy(excludeUndo = ExcludeUndo(id = ++undoSeq, mid = mid, name = name)) }
    }

    fun undoExclude(mid: Long) {
        viewModelScope.launch { settings.restoreFeedMid(mid) }
        clearExcludeUndo()
    }

    /** 那句话已经说完(撤销没被按,或者被别的顶掉)。清掉之后重进这一屏不会再弹一次。 */
    fun clearExcludeUndo() = _state.update { it.copy(excludeUndo = null) }

    /**
     * 加入稍后再看。**只进不出**,和播放页那一处同一套规矩(见 `VideoViewModel.addToView`):
     * 移除在稍后再看那一页做,那里是个列表,划掉一条是自然动作。
     *
     * **没有乐观更新可回滚,所以成败都要报一句。** 这一行上没有任何位置能显示"已加入"——
     * 播放页那一格图标会点亮,这里的菜单点完就收起来了。不报的话,成功和失败在界面上完全同形。
     */
    fun addToView(bvid: String) {
        viewModelScope.launch {
            val succeeded = when (val result = toViewRepository.add(bvid)) {
                is BiliResult.Ok -> true
                is BiliResult.ApiError -> {
                    BiliLog.w("toview/add 失败(${result.code}): ${result.message}")
                    false
                }

                is BiliResult.Failure -> {
                    BiliLog.w("toview/add 异常", result.cause)
                    false
                }
            }
            _state.update { it.copy(toViewNotice = ToViewNotice(id = ++noticeSeq, succeeded = succeeded)) }
        }
    }

    /** 那句话说完了。 */
    fun clearToViewNotice() = _state.update { it.copy(toViewNotice = null) }

    private companion object {
        const val READ_POSITION_DEBOUNCE_MS = 1_200L

        /** 「特别关注」在关注分组里的固定 tagid,见 FollowRepository.groups。 */
        const val SPECIAL_GROUP_ID = -10L
    }
}
