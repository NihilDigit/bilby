package dev.bilby.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/** 进度条的槽高与滑块半径。由"别挡住画面"定,不走主题刻度。 */
private val TrackHeight = 3.dp
private val ThumbRadius = 5.dp
private val ThumbRadiusDragging = 8.dp

/** 触摸高度和视觉高度是分开的:槽只有 3dp,3dp 高的东西在手机上根本按不中。 */
private val TouchHeight = 24.dp

/**
 * 播放进度条。手写而不是用 material3 的 `Slider`:`Slider` 的滑块是 20dp 见方的实心块,
 * 压在画面上像个跑进视频里的表单控件,而这一条要的是"贴着画面底边的一道细线"
 * (B 站与 PiliPlus 的播放器都是这个形态)。听视频页用同一个组件,只是换掉两个颜色 ——
 * 两处的拖拽语义完全一样,分成两份写迟早会各自漂移。
 *
 * 拖拽中只回调 [onSeekTo] 不真 seek,由调用方在 [onSeekFinished] 时一次完成:
 * 每帧 seek 会让播放器不停丢缓冲重新起播,表现为拖不动。
 */
@Composable
fun SeekBar(
    position: Long,
    duration: Long,
    onSeekStart: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    /**
     * 会被自动跳过的片段(SponsorBlock)。**只染色,不可交互。**
     *
     * 在进度条上多加一种手势会和拖动、点击跳转打架;而"这一段我不想跳"的正确入口是设置里
     * 关掉那个类别,不是每次现场决定 —— 现场决定等于把一个一次性配置变成反复出现的打断。
     *
     * 染色解决的是另一件事:跳过本身有事后提示,但**跳之前看不到哪儿有段、有多长**,
     * 于是播放器忽然往前一跳这件事只能靠事后解释。染上之后它是可预期的。
     */
    segments: List<SeekBarSegment> = emptyList(),
) {
    var dragging by remember { mutableStateOf(false) }
    val thumbRadius by animateDpAsState(
        targetValue = if (dragging) ThumbRadiusDragging else ThumbRadius,
        label = "seek-thumb",
    )
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Box(
        modifier = modifier
            .heightIn(min = TouchHeight)
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        onSeekStart()
                        onSeekTo(offset.x.toFractionOf(size.width).toPosition(duration))
                    },
                    onDragEnd = {
                        dragging = false
                        onSeekFinished()
                    },
                    onDragCancel = {
                        dragging = false
                        onSeekFinished()
                    },
                    onHorizontalDrag = { change, _ ->
                        onSeekTo(change.position.x.toFractionOf(size.width).toPosition(duration))
                    },
                )
            }
            // 点一下直接跳过去。和拖拽分成两个 pointerInput:合在一起就得自己区分
            // "按下没动"和"按下拖了",而这正是两个手势检测器各自已经做过的事。
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                detectTapGestures { offset ->
                    onSeekStart()
                    onSeekTo(offset.x.toFractionOf(size.width).toPosition(duration))
                    onSeekFinished()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(ThumbRadiusDragging * 2)) {
            val trackY = size.height / 2
            val trackPx = TrackHeight.toPx()
            drawLine(
                color = inactiveColor,
                start = Offset(0f, trackY),
                end = Offset(size.width, trackY),
                strokeWidth = trackPx,
                cap = StrokeCap.Round,
            )
            // 片段画在底色之上、已播进度之下:进度条的首要信息是"播到哪了",
            // 片段是背景标注,盖住进度会本末倒置。
            if (duration > 0) {
                segments.forEach { segment ->
                    val from = (segment.startMillis.toFloat() / duration).coerceIn(0f, 1f)
                    val to = (segment.endMillis.toFloat() / duration).coerceIn(0f, 1f)
                    if (to <= from) return@forEach
                    drawLine(
                        color = segment.color,
                        start = Offset(size.width * from, trackY),
                        end = Offset(size.width * to, trackY),
                        strokeWidth = trackPx,
                        // 方头:圆头会让相邻的两段之间露出一道底色,看着像有缝隙。
                        cap = StrokeCap.Butt,
                    )
                }
            }
            if (fraction > 0f) {
                drawLine(
                    color = activeColor,
                    start = Offset(0f, trackY),
                    end = Offset(size.width * fraction, trackY),
                    strokeWidth = trackPx,
                    cap = StrokeCap.Round,
                )
            }
            drawCircle(
                color = activeColor,
                radius = thumbRadius.toPx(),
                center = Offset(size.width * fraction, trackY),
            )
        }
    }
}

/** 进度条上要染色的一段。颜色由调用方给 —— 类别到颜色的映射是 SponsorBlock 的领域知识。 */
data class SeekBarSegment(val startMillis: Long, val endMillis: Long, val color: Color)

private fun Float.toFractionOf(width: Int): Float =
    if (width <= 0) 0f else (this / width).coerceIn(0f, 1f)

private fun Float.toPosition(duration: Long): Long = (this * duration).toLong()
