package dev.bilby.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.v2.ScrollbarAdapter
import kotlin.math.ceil
import kotlin.math.floor
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.delay

@Composable
actual fun ListScrollbar(state: LazyListState, modifier: Modifier) {
    ThemedScrollbar(scrolling = state.isScrollInProgress, modifier = modifier) { barModifier, style, interactions ->
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = barModifier,
            style = style,
            interactionSource = interactions,
        )
    }
}

@Composable
actual fun ListScrollbar(state: LazyGridState, modifier: Modifier) {
    ThemedScrollbar(scrolling = state.isScrollInProgress, modifier = modifier) { barModifier, style, interactions ->
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = barModifier,
            style = style,
            interactionSource = interactions,
        )
    }
}

@Composable
actual fun ListScrollbar(state: LazyStaggeredGridState, modifier: Modifier) {
    val adapter = remember(state) { StaggeredGridScrollbarAdapter(state) }
    ThemedScrollbar(scrolling = state.isScrollInProgress, modifier = modifier) { barModifier, style, interactions ->
        VerticalScrollbar(
            adapter = adapter,
            modifier = barModifier,
            style = style,
            interactionSource = interactions,
        )
    }
}

/**
 * 瀑布流的滚动条。foundation 只给列表和网格配了适配器,瀑布流没有,这里自己估。
 *
 * 瀑布流各列高低不齐,拿不到精确的总高度,只能按"屏上可见条目的平均高度 × 行数"估;
 * 行数按条目数除以列数算。滚动条因此会随加载更多条目、随可见条目高矮而轻微伸缩,
 * 这是估算的代价,不是错位。
 */
private class StaggeredGridScrollbarAdapter(private val state: LazyStaggeredGridState) : ScrollbarAdapter {
    private val info get() = state.layoutInfo

    private val lanes: Int
        get() = (info.visibleItemsInfo.maxOfOrNull { it.lane } ?: 0) + 1

    private val averageRowHeight: Double
        get() {
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return 0.0
            return visible.sumOf { it.size.height }.toDouble() / visible.size + info.mainAxisItemSpacing
        }

    private val rowCount: Double
        get() = ceil(info.totalItemsCount.toDouble() / lanes)

    override val scrollOffset: Double
        get() = state.firstVisibleItemIndex / lanes * averageRowHeight + state.firstVisibleItemScrollOffset

    override val contentSize: Double
        get() = rowCount * averageRowHeight + info.beforeContentPadding + info.afterContentPadding

    override val viewportSize: Double
        get() = info.viewportSize.height.toDouble()

    override suspend fun scrollTo(scrollOffset: Double) {
        val rowHeight = averageRowHeight
        if (rowHeight <= 0.0) return
        val row = scrollOffset / rowHeight
        val index = (floor(row).toInt() * lanes).coerceIn(0, (info.totalItemsCount - 1).coerceAtLeast(0))
        state.scrollToItem(index, ((row - floor(row)) * rowHeight).toInt())
    }
}

/**
 * 细、圆头、不用时淡出。默认样式是一根常驻的直角灰条,在内容区中间立着,比列表本身还显眼。
 *
 * 淡出只改透明度,不移出组合:滚动条的命中区域还在原处,鼠标移过去照样唤出、照样能拖。
 * 颜色跟主题走,默认样式是固定的灰,深色主题下几乎看不见。
 */
@Composable
private fun ThemedScrollbar(
    scrolling: Boolean,
    modifier: Modifier,
    bar: @Composable (Modifier, ScrollbarStyle, MutableInteractionSource) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val dragged by interactions.collectIsDraggedAsState()
    // 滚轮一格只让 isScrollInProgress 亮一两百毫秒,直接跟着它走的话滚动条一闪就没了,
    // 读不出自己在哪儿。停下后再留一会儿。
    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling) {
        if (scrolling) {
            recentlyScrolled = true
        } else {
            delay(ScrollbarLingerMillis)
            recentlyScrolled = false
        }
    }
    val visible = scrolling || recentlyScrolled || hovered || dragged
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "scrollbarAlpha")
    val colors = MaterialTheme.colorScheme
    bar(
        modifier.fillMaxHeight().padding(vertical = Spacing.Hair, horizontal = ScrollbarEdgeGap).alpha(alpha),
        ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 6.dp,
            shape = CircleShape,
            hoverDurationMillis = 150,
            unhoverColor = colors.onSurfaceVariant.copy(alpha = 0.4f),
            hoverColor = colors.onSurfaceVariant.copy(alpha = 0.7f),
        ),
        interactions,
    )
}

private const val ScrollbarLingerMillis = 1200L

/** 离窗口边缘留一线,贴死在边上时圆头的外半边会被切平。 */
private val ScrollbarEdgeGap = 2.dp
