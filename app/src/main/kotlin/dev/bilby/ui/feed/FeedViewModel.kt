package dev.bilby.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.data.DynamicRepository
import dev.bilby.data.FollowRepository
import dev.bilby.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FeedViewModel(
    private val repository: DynamicRepository,
    private val followRepository: FollowRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(FeedUiState(loading = true))
    val state: StateFlow<FeedUiState> = _state.asStateFlow()

    private var nextOffset: String? = null
    private var loadingPage = false

    /**
     * 同一个视频可能同时以投稿动态和合集更新动态出现,分页边界上也会重出。
     * 不去重的直接后果是 LazyColumn 的 key 冲突崩溃;但去重本身是产品要求 ——
     * 时间序流里同一个视频只该占一行。
     */
    private val seenBvids = mutableSetOf<String>()
    private var excludedMids = emptySet<Long>()

    init {
        viewModelScope.launch {
            settings.excludedFeedMids.collect { mids ->
                val restored = excludedMids.any { it !in mids }
                excludedMids = mids
                if (restored) {
                    // 有人被移出名单(设置页那个清空)。光放开过滤没用 —— 他的动态在之前几页
                    // 就被丢掉了,不重新拉一遍不会自己回来。
                    loadFirstPage()
                } else {
                    _state.update { current ->
                        current.copy(items = current.items.filterNot { it.upMid in mids })
                    }
                }
            }
        }
        loadFirstPage()
        loadFrequentUps()
    }

    /**
     * 与动态流分开取,互不阻塞也互不牵连:这一排失败不该让整页显示错误,动态流失败也不该
     * 把它一起抹掉。失败就整排不显示 —— 它是快捷方式,没有它这一页照样能做正事。
     *
     * 但不显示不等于不留痕:界面上什么都不会有,不打这行日志就再也查不出它为什么没出来。
     */
    private fun loadFrequentUps() = viewModelScope.launch {
        when (val result = followRepository.frequentUps()) {
            is BiliResult.Ok -> _state.update { it.copy(frequentUps = result.value) }
            is BiliResult.ApiError -> BiliLog.w("取最常访问失败(${result.code}): ${result.message}")
            is BiliResult.Failure -> BiliLog.w("取最常访问异常", result.cause)
        }
    }

    /** 下拉刷新:重拉第一页,顶上那排也一并重取(关注关系可能在别处变过)。 */
    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        nextOffset = null
        seenBvids.clear()
        fetch(append = false)
        loadFrequentUps()
    }

    fun loadFirstPage() {
        nextOffset = null
        seenBvids.clear()
        // 保留已经拿到的那排 UP:重载的是动态流,把顶上那排一起清掉会让它闪一下。
        _state.update { FeedUiState(loading = true, frequentUps = it.frequentUps) }
        fetch(append = false)
    }

    fun loadMore() {
        if (loadingPage || !_state.value.hasMore) return
        _state.update { it.copy(appending = true) }
        fetch(append = true)
    }

    private fun fetch(append: Boolean) {
        loadingPage = true
        viewModelScope.launch {
            when (val page = repository.loadVideoFeed(nextOffset)) {
                is BiliResult.Ok -> {
                    nextOffset = page.value.nextOffset
                    val fresh = page.value.items.filter {
                        it.upMid !in excludedMids && seenBvids.add(it.bvid)
                    }
                    _state.update { current ->
                        current.copy(
                            items = if (append) current.items + fresh else fresh,
                            loading = false,
                            appending = false,
                            refreshing = false,
                            hasMore = page.value.hasMore && page.value.nextOffset != null,
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

    private fun setError(message: String) {
        _state.update { it.copy(loading = false, appending = false, refreshing = false, error = message) }
    }

    fun excludeUp(mid: Long) {
        if (mid == 0L) return
        excludedMids = excludedMids + mid
        _state.update { current ->
            current.copy(items = current.items.filterNot { it.upMid == mid })
        }
        viewModelScope.launch { settings.excludeFeedMid(mid) }
    }
}
