package dev.bilby.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import dev.bilby.ui.theme.Breakpoints
import kotlin.math.ceil

/** M3 的五档窗口断点,按可用窗口宽度而不是设备型号判断。 */
enum class BilbyWindowSize {
    Compact,
    Medium,
    Expanded,
    Large,
    ExtraLarge,
}

/**
 * 读的是**窗口**宽度(`LocalWindowInfo.containerSize`),不是 `Configuration.screenWidthDp`。
 * 后者的语义在 API 35 全面 edge-to-edge 之后变过(开始把系统栏占的那一条算进去),而这里要
 * 回答的是"内容能铺多宽";分屏和自由窗口下也是 containerSize 才跟着窗口走。
 */
@Composable
fun rememberBilbyWindowSize(): BilbyWindowSize {
    val widthPx = LocalWindowInfo.current.containerSize.width
    val widthDp = with(LocalDensity.current) { widthPx.toDp() }
    return bilbyWindowSize(widthDp)
}

private fun bilbyWindowSize(width: Dp): BilbyWindowSize = when {
    width < Breakpoints.Medium -> BilbyWindowSize.Compact
    width < Breakpoints.Expanded -> BilbyWindowSize.Medium
    width < Breakpoints.Large -> BilbyWindowSize.Expanded
    width < Breakpoints.ExtraLarge -> BilbyWindowSize.Large
    else -> BilbyWindowSize.ExtraLarge
}

fun BilbyWindowSize.isAtLeast(other: BilbyWindowSize): Boolean = ordinal >= other.ordinal

/**
 * 每列不超过 [maxCellWidth],列数取满足这一点的最小值,余宽均分。
 *
 * `GridCells.Adaptive` 反过来规定的是最小列宽,列数向下取整,于是一格可以宽到接近两倍 ——
 * 横排视频行宽到那个程度,右半格只剩一行拉长的标题。这里向上取整,照 PiliPlus 的
 * `SliverGridDelegateWithMaxCrossAxisExtent`。
 */
fun maxWidthGridCells(maxCellWidth: Dp): GridCells = MaxWidthGridCells(maxCellWidth)

private class MaxWidthGridCells(private val maxCellWidth: Dp) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val stride = maxCellWidth.roundToPx() + spacing
        // 每列连同它右边那道间距占一个 stride,末列没有间距,所以可用宽度补上一道再除。
        val count = ceil((availableSize + spacing).toFloat() / stride).toInt().coerceAtLeast(1)
        val usable = (availableSize - spacing * (count - 1)).coerceAtLeast(0)
        val base = usable / count
        val remainder = usable % count
        return List(count) { index -> base + if (index < remainder) 1 else 0 }
    }

    override fun equals(other: Any?): Boolean = other is MaxWidthGridCells && other.maxCellWidth == maxCellWidth

    override fun hashCode(): Int = maxCellWidth.hashCode()
}

/**
 * 在宽屏上限制内容行长,compact 下仍是全宽。具体页面自己决定一栏、两栏还是沉浸式播放器,
 * 这个容器只把多出来的宽度转成两边的留白。
 *
 * **内层不能写 `fillMaxWidth().widthIn(max)`**,那样上限完全不生效:`fillMaxWidth` 先把子约束
 * 定成 `min = max = 父宽`,而 `widthIn` 是 `constraints.constrain(...)`,它要把 max 夹进
 * `[父宽, 父宽]` 这个区间里,夹完还是父宽。顺序反过来才对:先夹上限,再让内容填满被夹过的
 * 那份宽度。
 */
/**
 * [AdaptiveContent] 的修饰符写法,给本身就是一个 Column、不想再包一层 Box 的页面用。
 * 先放开最小宽度并居中,再夹上限,最后填满被夹过的宽度 —— 顺序的理由同 [AdaptiveContent]。
 */
fun Modifier.readableWidth(maxWidth: Dp = Breakpoints.ReadableWidth): Modifier =
    fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = maxWidth)
        .fillMaxWidth()

@Composable
fun AdaptiveContent(
    modifier: Modifier = Modifier,
    maxWidth: Dp = Breakpoints.ReadableWidth,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier.widthIn(max = maxWidth).fillMaxSize(),
            content = content,
        )
    }
}
