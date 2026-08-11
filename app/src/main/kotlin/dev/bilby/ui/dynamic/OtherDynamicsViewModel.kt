package dev.bilby.ui.dynamic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.data.DynamicRepository
import dev.bilby.data.model.DynamicAdditional
import dev.bilby.data.model.DynamicCard
import dev.bilby.ui.appendDistinctBy
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
 * **不做本地缓存,也不记已读位置** —— 首页那两样是为"每天回来接着看"服务的,而这一页是偶尔
 * 点进来翻一翻的地方,给它同一套装置只会多两张表和一条去抖落盘链路。
 */
class OtherDynamicsViewModel(private val repository: DynamicRepository) : ViewModel() {

    private val _state = MutableStateFlow(OtherDynamicsUiState(loading = true))
    val state: StateFlow<OtherDynamicsUiState> = _state.asStateFlow()

    private var nextOffset: String? = null
    private var loadingPage = false

    init {
        fetch(append = false)
    }

    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        nextOffset = null
        fetch(append = false)
    }

    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        nextOffset = null
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
            when (val page = repository.loadOtherFeed(if (append) nextOffset else null)) {
                is BiliResult.Ok -> {
                    val fresh = page.value.items
                    _state.update { current ->
                        current.copy(
                            // 刷新时整份换掉,不做首页那套头部合并:这一页没有"上次读到哪儿"
                            // 要保护,合并只会让刷新看起来什么都没发生。
                            items = if (append) current.items.appendDistinctBy(fresh) { it.id } else fresh,
                            loading = false,
                            appending = false,
                            refreshing = false,
                            hasMore = page.value.hasMore && page.value.nextOffset != null,
                            error = null,
                        )
                    }
                    nextOffset = page.value.nextOffset
                }

                is BiliResult.ApiError -> setError("${page.message}(${page.code})")
                is BiliResult.Failure -> setError(page.cause.message ?: "网络错误")
            }
            loadingPage = false
        }
    }

    private fun setError(message: String) = _state.update {
        it.copy(loading = false, appending = false, refreshing = false, error = message)
    }
}
