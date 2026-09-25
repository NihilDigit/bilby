package dev.bilby.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import dev.bilby.ui.theme.Breakpoints

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
 * 在宽屏上限制内容行长,compact 下仍是全宽。具体页面自己决定一栏、两栏还是沉浸式播放器,
 * 这个容器只把多出来的宽度转成两边的留白。
 *
 * **内层不能写 `fillMaxWidth().widthIn(max)`**,那样上限完全不生效:`fillMaxWidth` 先把子约束
 * 定成 `min = max = 父宽`,而 `widthIn` 是 `constraints.constrain(...)`,它要把 max 夹进
 * `[父宽, 父宽]` 这个区间里,夹完还是父宽。顺序反过来才对:先夹上限,再让内容填满被夹过的
 * 那份宽度。
 */
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
