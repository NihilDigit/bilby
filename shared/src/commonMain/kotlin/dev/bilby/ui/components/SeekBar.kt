package dev.bilby.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import dev.bilby.formatDurationMillis
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.rememberClockFont
import kotlin.math.roundToInt
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import dev.bilby.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import dev.bilby.resources.*
import dev.bilby.ui.theme.Dimens

/** 进度条的槽高与滑块半径。由"别挡住画面"定,不走主题刻度。 */
private val TrackHeight = 3.dp

/**
 * 拖动时的槽高。**按下就加粗**:手指压在细线上时线被整个盖住,加粗之后露在指头两侧的那截
 * 才看得出已播到哪 —— 这是"抓住了"的反馈,和滑块放大是同一件事的两半。
 */
private val TrackHeightDragging = 6.dp
private val ThumbRadius = 5.dp
private val ThumbRadiusDragging = 8.dp

/** 时间气泡与滑块顶端之间的距离。 */
private val BubbleGap = 8.dp

/**
 * 触摸高度和视觉高度是分开的:槽只有 3dp,3dp 高的东西在手机上根本按不中。
 *
 * 取 [Dimens.MinTouchTarget] 而不是原来的 24dp:这一条是播放器上最主要的操作,而 24dp 只有
 * 无障碍下限的一半。代价是控制条整体高出 24dp、画面被多盖住一条,换掉的是拖不中。
 */
private val TouchHeight = Dimens.MinTouchTarget

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
    /**
     * 未播段。**半透明的 onSurface,不是一个实色容器角色**:这条线压在画面上,实色的浅灰
     * 在亮画面上几乎看不见、在暗画面上又是一道硬线;半透明让它随画面明暗走,始终比画面
     * 亮(或暗)一档。播放器里配色是深色主题(见 PlayerTheme),onSurface 是浅色。
     */
    inactiveColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = InactiveAlpha),
    /**
     * 已缓冲到哪儿。0 表示不画 —— 直播和还没起播的时候这个数没有意义。
     *
     * 它回答的是"往前拖到哪里不用等"。没有这一段时,网络慢和播放器卡死在画面上是同一个样子:
     * 进度不动、转圈在转,而缓冲条还在往前爬就说明流在进来。
     */
    bufferedPosition: Long = 0L,
    /**
     * 缓冲段的颜色。**落在底色与已播之间**:缓冲是"比底色确定、比已播次要"的第三档,而三条
     * 线挤在 3dp 高的同一道槽里,只能靠明度分开。和未播段同一种半透明,只是更实一档。
     */
    bufferedColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = BufferedAlpha),
    /**
     * 拖动时在手指上方显示目标时间。**默认开**:手指压在进度条上,能读到时间的地方只剩手指
     * 上面。页面另有大号读数的(听视频页的时间就在进度条正下方)可以关掉。
     */
    timeBubble: Boolean = true,
    /**
     * 已播段画成一条流动的波浪(M3 Expressive 的 wavy progress 那种)。**只在放着的时候起伏**:
     * 暂停、拖动时振幅收到 0,拉平成直线 —— 波浪说的是"声音正在往前走",停着时还在波动就是
     * 在撒谎,拖的时候晃着也对不准。
     *
     * 听视频页用:那一页没有画面,进度条是整屏唯一在动的东西。视频播放器不用,画面本身就在动。
     */
    wavy: Boolean = false,
    /** [wavy] 时决定起不起伏。 */
    playing: Boolean = false,
    /** 播放倍速。[wavy] 时波浪流动的速度跟着它:2x 下声音走得快一倍,波也该快一倍。 */
    speed: Float = 1f,
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
    // 两个量走同一个 spatial 弹簧:它们是"按住了"这一个反馈的两面,节奏对不上会看出来。
    val thumbRadius by animateDpAsState(
        targetValue = if (dragging) ThumbRadiusDragging else ThumbRadius,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "seek-thumb",
    )
    val trackHeight by animateDpAsState(
        targetValue = if (dragging) TrackHeightDragging else TrackHeight,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "seek-track",
    )
    val waveAmplitude by animateDpAsState(
        targetValue = if (wavy && playing && !dragging) WaveAmplitude else 0.dp,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "seek-wave",
    )
    // 相位只在要画波浪时才走:无限动画每帧都要重绘,直线模式下不该为它付钱。**只在画的时候读**
    // (下面 Canvas 里),读在这里的话整条进度条每一帧都要重组一遍。
    val wavePhase: State<Float> = if (wavy) {
        rememberInfiniteTransition(label = "seek-wave-phase").animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                tween((WavePeriodMillis / speed.coerceAtLeast(MinWaveSpeed)).toInt(), easing = LinearEasing),
            ),
            label = "seek-wave-phase",
        )
    } else {
        NoWavePhase
    }
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // **两端各内缩一个(放大后的)滑块半径。** 不缩的话拖到 0 或末尾时滑块有一半画在条外,
    // 贴着屏幕边缘的那半截被切掉,读起来像没拖到头。触点换算用同一个内缩,手指和滑块才对得上。
    val insetPx = with(density) { ThumbRadiusDragging.toPx() }
    fun Float.toFraction(): Float {
        val usable = widthPx - insetPx * 2
        return if (usable <= 0f) 0f else ((this - insetPx) / usable).coerceIn(0f, 1f)
    }
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufferedFraction =
        if (duration > 0) (bufferedPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val label = stringResource(Res.string.player_progress)

    Box(
        modifier = modifier
            .heightIn(min = TouchHeight)
            .onSizeChanged { widthPx = it.width }
            // 手写的进度轨没有任何自带语义:读屏在这里既读不出播到哪了,也没有可用的动作。
            // progressBarRangeInfo 给出位置,setProgress 让读屏的"调整"手势能真的跳转 ——
            // 拖拽手势对读屏用户是不存在的。
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(current = fraction, range = 0f..1f)
                setProgress { target ->
                    if (duration <= 0) {
                        false
                    } else {
                        onSeekStart()
                        onSeekTo(target.coerceIn(0f, 1f).toPosition(duration))
                        onSeekFinished()
                        true
                    }
                }
            }
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        onSeekStart()
                        onSeekTo(offset.x.toFraction().toPosition(duration))
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
                        onSeekTo(change.position.x.toFraction().toPosition(duration))
                    },
                )
            }
            // 点一下直接跳过去。和拖拽分成两个 pointerInput:合在一起就得自己区分
            // "按下没动"和"按下拖了",而这正是两个手势检测器各自已经做过的事。
            .pointerInput(duration) {
                if (duration <= 0) return@pointerInput
                detectTapGestures { offset ->
                    onSeekStart()
                    onSeekTo(offset.x.toFraction().toPosition(duration))
                    onSeekFinished()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(ThumbRadiusDragging * 2)) {
            val trackY = size.height / 2
            val trackPx = trackHeight.toPx()
            val start = insetPx
            val span = (size.width - insetPx * 2).coerceAtLeast(0f)
            fun x(f: Float) = start + span * f
            // **波浪模式下未播段从已播段末端隔一道缝才开始**,不从头铺到尾:铺满的话波浪是叠在
            // 一条直线上画的,像一根线上缠着另一根。M3E 的 wavy indicator 就是这样断开的。
            val restStart = if (wavy && fraction > 0f) {
                (x(fraction) + WaveGap.toPx() + trackPx).coerceAtMost(x(1f))
            } else {
                x(0f)
            }
            if (restStart < x(1f)) {
                drawLine(
                    color = inactiveColor,
                    start = Offset(restStart, trackY),
                    end = Offset(x(1f), trackY),
                    strokeWidth = trackPx,
                    cap = StrokeCap.Round,
                )
            }
            // 缓冲段压在底色上、片段与已播之下。**在片段底下**是因为缓冲通常已经跑到画面之外
            // 那么远,盖在上面的话整条 SponsorBlock 染色都会被它遮掉,而那个染色要回答的
            // "前面哪儿有段"比"那一段缓冲了没有"有用。
            if (bufferedFraction > fraction && x(bufferedFraction) > restStart) {
                drawLine(
                    color = bufferedColor,
                    start = Offset(restStart, trackY),
                    end = Offset(x(bufferedFraction), trackY),
                    strokeWidth = trackPx,
                    cap = StrokeCap.Round,
                )
            }
            // 片段画在底色之上、已播进度之下:进度条的首要信息是"播到哪了",
            // 片段是背景标注,盖住进度会本末倒置。
            if (duration > 0) {
                segments.forEach { segment ->
                    val from = (segment.startMillis.toFloat() / duration).coerceIn(0f, 1f)
                    val to = (segment.endMillis.toFloat() / duration).coerceIn(0f, 1f)
                    if (to <= from) return@forEach
                    drawLine(
                        color = segment.color,
                        start = Offset(x(from), trackY),
                        end = Offset(x(to), trackY),
                        strokeWidth = trackPx,
                        // 方头:圆头会让相邻的两段之间露出一道底色,看着像有缝隙。
                        cap = StrokeCap.Butt,
                    )
                }
            }
            if (fraction > 0f) {
                val amp = waveAmplitude.toPx()
                if (amp > 0.1f) {
                    // 正弦波,波长固定、随相位平移。起点振幅从 0 渐起:左端贴着槽的起点,不从
                    // 半空里冒出来。
                    val wavelength = WaveLength.toPx()
                    val endX = x(fraction)
                    val path = Path().apply {
                        moveTo(x(0f), trackY)
                        var px = x(0f)
                        while (px < endX) {
                            px = (px + WaveStepPx).coerceAtMost(endX)
                            val ramp = ((px - x(0f)) / wavelength).coerceIn(0f, 1f)
                            val y = trackY + amp * ramp *
                                sin((px / wavelength) * 2f * PI.toFloat() - wavePhase.value)
                            lineTo(px, y)
                        }
                    }
                    drawPath(path, activeColor, style = Stroke(width = trackPx, cap = StrokeCap.Round))
                } else {
                    drawLine(
                        color = activeColor,
                        start = Offset(x(0f), trackY),
                        end = Offset(x(fraction), trackY),
                        strokeWidth = trackPx,
                        cap = StrokeCap.Round,
                    )
                }
            }
            drawCircle(
                color = activeColor,
                radius = thumbRadius.toPx(),
                center = Offset(x(fraction), trackY),
            )
        }

        // 时间气泡。**不占布局高度**:它上报 0×0,画在进度条上方的界外 —— 占了高度的话,一按
        // 下去整条控制条就往上长一截,手指下的东西跟着挪。水平方向跟着滑块,贴边时收在条内。
        if (timeBubble && dragging && duration > 0) {
            val gapPx = with(density) { (BubbleGap + ThumbRadiusDragging).roundToPx() }
            Surface(
                color = activeColor,
                contentColor = contentColorFor(activeColor),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .layout { measurable, constraints ->
                        val bubble = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        layout(0, 0) {
                            val center = insetPx + (widthPx - insetPx * 2) * fraction
                            val x = (center - bubble.width / 2f).roundToInt()
                                .coerceIn(0, (widthPx - bubble.width).coerceAtLeast(0))
                            bubble.place(x, -bubble.height - gapPx)
                        }
                    },
            ) {
                // 字形同横划读数与定时倒计时(ClockAxes);等宽,拖动时不左右抖。
                Text(
                    text = formatDurationMillis(position),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = rememberClockFont(),
                        fontFeatureSettings = "tnum",
                    ),
                    modifier = Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
                )
            }
        }
    }
}

/** 波浪的振幅与波长,取 M3E wavy progress indicator 的量级;周期是相位走完一圈的时长。 */
private val WaveAmplitude = 3.dp
private val WaveLength = 28.dp
/** 1x 下波浪平移一个波长的时长。更短的话静静听着时它像在抖。 */
private const val WavePeriodMillis = 3_200

/** 已播波浪与未播直线之间的缝。 */
private val WaveGap = 4.dp

/** 倍速的下限,只为算波浪周期时不除以 0。 */
private const val MinWaveSpeed = 0.1f

private val NoWavePhase: State<Float> = mutableFloatStateOf(0f)

/** 画波浪时每一小段的步长。2px 在任何密度下都看不出折线。 */
private const val WaveStepPx = 2f

/** 未播段与缓冲段在 onSurface 上的透明度,见 [SeekBar] 的 inactiveColor。 */
private const val InactiveAlpha = 0.28f
private const val BufferedAlpha = 0.55f

/** 进度条上要染色的一段。颜色由调用方给 —— 类别到颜色的映射是 SponsorBlock 的领域知识。 */
data class SeekBarSegment(val startMillis: Long, val endMillis: Long, val color: Color)

private fun Float.toPosition(duration: Long): Long = (this * duration).toLong()
