package dev.bilby.ui.dynamic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.data.DynamicFeedHalf
import dev.bilby.data.DynamicFeedStore
import dev.bilby.data.DynamicRepository
import dev.bilby.data.model.DynamicCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OtherDynamicsUiState(
    val items: List<DynamicCard> = emptyList(),
    val loading: Boolean = false,
    val appending: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

/**
 * 首页折起来的那一半(DESIGN 2.1)。
 *
 * **和首页是同一条流的两个视图**,列表、游标、排除名单都在 [DynamicFeedStore] 里,这一层
 * 只负责把它摆出来和发点赞请求。从前这里自带一份游标和一次 `type=all` 请求,于是首页排除掉
 * 的人在这一页照样出现、两边连"刷新"的含义都不一样。
 *
 * **不做本地缓存,也不记已读位置** —— 首页那两样是为"每天回来接着看"服务的,而这一页是偶尔
 * 点进来翻一翻的地方,给它同一套装置只会多两张表和一条去抖落盘链路。
 */
class OtherDynamicsViewModel(
    private val store: DynamicFeedStore,
    private val repository: DynamicRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(OtherDynamicsUiState(loading = true))
    val state: StateFlow<OtherDynamicsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            store.other.collect { items -> _state.update { it.copy(items = items) } }
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
        // 正常路径是从首页点进来,流早就起来了;直达这一页时(恢复导航栈)由它负责起流。
        viewModelScope.launch { store.ensureStarted(DynamicFeedHalf.Other) }
    }

    fun refresh() {
        viewModelScope.launch { store.refresh(DynamicFeedHalf.Other) }
    }

    fun retry() {
        viewModelScope.launch { store.refresh(DynamicFeedHalf.Other) }
    }

    fun loadMore() {
        viewModelScope.launch { store.loadMore(DynamicFeedHalf.Other) }
    }

    /**
     * 点赞。**乐观更新,失败回滚,不重新拉取** —— 重拉会让同一个数字先跳到新值、再被响应改回去,
     * 在点赞多的动态上看起来就是闪两下(CLAUDE.md 的硬约定,与视频点赞同一条)。
     */
    fun like(id: String, like: Boolean) {
        store.setLiked(id, like)
        viewModelScope.launch {
            val result = repository.likeDynamic(id, like)
            if (result is BiliResult.ApiError || result is BiliResult.Failure) {
                // 回滚。日志由 BiliClient 那层打过一行带 code 的了,这里只补上是哪条动态。
                BiliLog.w("动态 $id 点赞失败,已回滚")
                store.setLiked(id, !like)
            }
        }
    }
}
