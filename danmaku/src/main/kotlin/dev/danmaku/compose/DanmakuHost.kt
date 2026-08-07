package dev.danmaku.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * 弹幕渲染 host。持有一个 [DanmakuHostState],在 `LaunchedEffect` 里驱动它的帧循环,画布上
 * 把每次回调拿到的可见弹幕逐条画出来。不引 material3——没有主题、没有默认配色,外观全部
 * 来自 [style]。
 *
 * **契约**:编译时间轴用的 `DanmakuTimelineConfig.screenWidthPx` 必须等于这个 Canvas 的
 * 像素宽度,否则滚动弹幕算出来的位置会跟这里的 `size.width` 对不上。这里故意不重复接收一个
 * `screenWidthPx` 参数去覆盖编译期用的那份配置——`size` 是画布在绘制那一刻的真实宽度,是
 * 唯一权威来源,重复传参只会引入"两处配置各传一份、可能对不齐"的新故障模式。不一致不会被
 * 默默吞掉:尺寸变化时(转屏、分屏、窗口尺寸调整)会跟 [DanmakuHostState.compiledScreenWidthPx]
 * 比对,超出容差就回调 [onScreenWidthMismatch] ——库自己没法重编时间轴(它不持有弹幕池),
 * 只负责把"对不齐"这件事暴露出去,重新编译是调用方的事。
 *
 * 位置公式跟编译器层的约定完全一致:
 * - 滚动弹幕:`x = screenWidthPx - (t - t0Millis) * speedPxPerMillis`。
 * - 顶部固定弹幕:`y = track * trackHeightPx`,从上往下堆,轨道号 0 贴着顶边。
 * - 底部固定弹幕:`y = canvasHeight - (track + 1) * trackHeightPx`,从下往上堆,轨道号 0
 *   贴着底边。
 *
 * 排版没有自己另开一层缓存:[TextMeasurer] 自带 LRU([rememberTextMeasurer] 的 `cacheSize`
 * 参数),而且它的缓存 key 天生只认"影响排版的属性"——颜色不影响排版,被排除在 key 之外,
 * 所以这里测量时故意不把逐条弹幕的颜色烤进 [TextStyle],颜色只在画的那一刻通过 `drawText`
 * 的 `color` 参数传入,复读的弹幕(文本+字号相同、颜色不同)一样能命中同一份排版缓存。
 */
@Composable
fun DanmakuHost(
    state: DanmakuHostState,
    style: DanmakuRenderStyle = DanmakuRenderStyle(),
    modifier: Modifier = Modifier,
    onScreenWidthMismatch: (actualWidthPx: Float) -> Unit = {},
) {
    val measurer = rememberTextMeasurer(cacheSize = 512)
    // frame 是复用的普通 list,不是 SnapshotStateList——内容变化本身不会触发重组,
    // frameVersion 才是 Canvas 订阅的信号源,列表只是它背后的数据。
    val frame = remember { mutableListOf<CompiledDanmaku>() }
    var framePositionMillis by remember { mutableLongStateOf(0L) }
    var frameVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(state) {
        state.run { visible, positionMillis ->
            frame.clear()
            frame.addAll(visible)
            framePositionMillis = positionMillis
            frameVersion++
        }
    }

    Canvas(
        modifier = modifier.onSizeChanged { size ->
            val actualWidthPx = size.width.toFloat()
            if (actualWidthPx > 0f && abs(actualWidthPx - state.compiledScreenWidthPx) > SCREEN_WIDTH_TOLERANCE_PX) {
                onScreenWidthMismatch(actualWidthPx)
            }
        },
    ) {
        // 读一次 frameVersion,让这个绘制块订阅上面的 State 写入。
        @Suppress("UNUSED_EXPRESSION")
        frameVersion

        val screenWidthPx = size.width
        val canvasHeightPx = size.height

        for (entry in frame) {
            val layout = measureDanmaku(entry.danmaku, style, measurer)
            val y = when (entry.slot.mode) {
                DanmakuMode.SCROLL, DanmakuMode.TOP -> entry.slot.track * style.trackHeightPx
                DanmakuMode.BOTTOM -> canvasHeightPx - (entry.slot.track + 1) * style.trackHeightPx
            }
            val x = if (entry.slot.mode == DanmakuMode.SCROLL) {
                screenWidthPx - (framePositionMillis - entry.t0Millis) * entry.speedPxPerMillis
            } else {
                (screenWidthPx - layout.size.width) / 2f
            }
            // danmaku.color 是不带 alpha 的 24 位 RGB,直接塞进 Color(Int) 会被当成
            // 0x00RRGGBB(alpha=0,全透明),必须先把 alpha 字节填满。
            val color = Color(entry.danmaku.color or ALPHA_OPAQUE_MASK)
            drawDanmaku(layout, Offset(x, y), color, style)
        }
    }
}

// 故意不传颜色:颜色不影响排版,`TextMeasurer` 的缓存 key 也不认颜色,烤进 style 只会让
// 同一句话的不同颜色副本各占一条缓存、白白降低命中率。颜色只在 drawDanmaku 的 drawText
// 调用里通过 color 参数传入 —— 别把它挪回这里,这是反直觉的地方,之前手写过一版按颜色
// 分 key 的缓存,命中率反而更差,已经删掉了。
private fun measureDanmaku(danmaku: Danmaku, style: DanmakuRenderStyle, measurer: TextMeasurer): TextLayoutResult {
    val fontSizeSp = danmaku.fontSize?.toFloat() ?: style.globalFontSizeSp
    val resolvedStyle = if (fontSizeSp != null) style.baseTextStyle.copy(fontSize = fontSizeSp.sp) else style.baseTextStyle
    return measurer.measure(text = danmaku.text, style = resolvedStyle)
}

/**
 * 测一次、画两遍,共享同一份 [TextLayoutResult]:先描边(`drawStyle = Stroke`),再填充
 * (不传 `drawStyle`,默认即 Fill)。`strokeWidthPx <= 0` 时跳过描边那一遍,不多画。
 *
 * 透明度逐条通过 `alpha` 参数传给 `drawText`,不套 `Modifier.alpha`——理由见
 * [DanmakuRenderStyle.opacity] 的文档。
 */
private fun DrawScope.drawDanmaku(layout: TextLayoutResult, topLeft: Offset, color: Color, style: DanmakuRenderStyle) {
    if (style.strokeWidthPx > 0f) {
        drawText(
            textLayoutResult = layout,
            color = style.strokeColor,
            topLeft = topLeft,
            alpha = style.opacity,
            drawStyle = Stroke(width = style.strokeWidthPx, miter = 3f, join = StrokeJoin.Round),
        )
    }
    drawText(textLayoutResult = layout, color = color, topLeft = topLeft, alpha = style.opacity)
}

private const val ALPHA_OPAQUE_MASK = 0xFF000000.toInt()

/** 容差:亚像素级的取整误差不算"对不齐",超过一个像素才值得打扰调用方。 */
private const val SCREEN_WIDTH_TOLERANCE_PX = 1f
