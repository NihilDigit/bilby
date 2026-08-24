package dev.bilby.ui.search

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.data.SearchRepository
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.theme.Breakpoints
import kotlinx.coroutines.flow.StateFlow

/**
 * 标签结果页的 ViewModel:一个关键词、一份 [NormalSearchController]。关键词是路由身份
 * (Destinations.kt 的 SearchResult),不会中途换,所以没有 switchTo —— 换关键词就是
 * 另一个目的地、另一个实例。
 *
 * **不写搜索历史。** 历史是"自己敲过的字"(SearchChatViewModel 那边的判据),点标签
 * 没有敲字这个动作,记进去反而会把五条历史挤掉一条。
 */
class SearchResultViewModel(
    keyword: String,
    searchRepository: SearchRepository,
) : ViewModel() {
    private val controller = NormalSearchController(viewModelScope, searchRepository)
    val state: StateFlow<NormalSearchState> = controller.state

    init {
        controller.search(keyword, SearchOrder.Comprehensive)
    }

    fun loadMore() = controller.loadMore()
    fun onOrderChanged(order: SearchOrder) = controller.onOrderChanged(order)
    fun retry() = controller.retry()
}

/**
 * 一个关键词的普通搜索结果,从播放页的标签点进来。列表本体与搜索 tab 的普通模式共用
 * [NormalResultList];这一页没有输入框 —— 要换词去搜索 tab,这里的词是点进来那枚标签,
 * 顶栏(路由那层的 BilbyTopBar)已经把它当标题写着。
 */
@Composable
fun SearchResultScreen(
    state: NormalSearchState,
    onOrderChange: (SearchOrder) -> Unit,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 宽屏不拉满,和搜索 tab 同一条理由:条目是"封面 + 两行文字",行长一超可读宽度就得转头扫。
    AdaptiveContent(modifier = modifier.fillMaxSize(), maxWidth = Breakpoints.ReadableWidth) {
        RefreshBox(
            refreshing = state.videoLoading && state.videos.isNotEmpty(),
            onRefresh = onRetry,
            modifier = Modifier.fillMaxSize(),
        ) {
            NormalResultList(
                state = state,
                onOrderChange = onOrderChange,
                onVideoClick = onVideoClick,
                onUserClick = onUserClick,
                onLoadMore = onLoadMore,
                onRetry = onRetry,
            )
        }
    }
}
