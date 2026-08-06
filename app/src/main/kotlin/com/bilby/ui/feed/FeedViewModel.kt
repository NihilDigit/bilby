package com.bilby.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilby.api.BiliResult
import com.bilby.data.DynamicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FeedViewModel(private val repository: DynamicRepository) : ViewModel() {

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

    init {
        loadFirstPage()
    }

    fun loadFirstPage() {
        nextOffset = null
        seenBvids.clear()
        _state.value = FeedUiState(loading = true)
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
                    val fresh = page.value.items.filter { seenBvids.add(it.bvid) }
                    _state.update { current ->
                        current.copy(
                            items = if (append) current.items + fresh else fresh,
                            loading = false,
                            appending = false,
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
        _state.update { it.copy(loading = false, appending = false, error = message) }
    }
}
