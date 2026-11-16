package dev.bilby.ui.night

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilby.resources.Res
import dev.bilby.resources.night_mode_hold_holding
import dev.bilby.resources.night_mode_hold_idle
import dev.bilby.resources.night_mode_hold_progress
import dev.bilby.stringResource
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.components.PaneSheet
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.player.KeepScreenOn
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.rememberClockFont
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * 夜间模式下修改生效中的限制或今夜临时退出前的 30 秒长按确认面板。
 *
 * 视觉分上下两层而不重复放两条进度条：
 * - 上方是 30 格秒针刻度表盘([NightHoldDial])，每一格对应 1 秒、每 5 秒一格主刻度，内圈连续圆弧与
 *   `ClockAxes` 倒计时读数(`0:30` → `0:00`)完全露在手指上方，手机上按住下方触控垫时拇指不会挡住读数；
 * - 下方是 M3 Expressive 形变触控垫([NightHoldPad])，按下时由圆角矩形弹簧形变为胶囊，松手立即回卷归零。
 *   桌面端用鼠标直接按住表盘或触控垫、或聚焦后长按空格/回车均可触发。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NightExitHoldSheet(
    title: String,
    body: String? = null,
    onCompleted: () -> Unit,
    onDismiss: () -> Unit,
) {
    val fullHeightHost = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)
    PaneSheet(
        onDismissRequest = onDismiss,
        title = title,
    ) {
        // 必须在 PaneSheet 内部读取 LocalWindowInfo / LocalHapticFeedback / KeepScreenOn：
        // Android 上 ModalBottomSheet 与 ModalSideSheet 均为独立 Dialog 窗口，外层 Activity 窗口在弹窗打开期间
        // isWindowFocused 恒为 false；且触控按压由手势生命周期自行成对增减，不应受窗口焦点位阻塞。
        var pointerPresses by remember { mutableIntStateOf(0) }
        var heldKey by remember { mutableStateOf<Key?>(null) }
        var completed by remember { mutableStateOf(false) }
        val sheetWindowFocused = LocalWindowInfo.current.isWindowFocused
        val lifecycleOwner = LocalLifecycleOwner.current

        LaunchedEffect(sheetWindowFocused) {
            if (!sheetWindowFocused) {
                heldKey = null
            }
        }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    heldKey = null
                    pointerPresses = 0
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        val holding = !completed && (pointerPresses > 0 || (sheetWindowFocused && heldKey != null))
        KeepScreenOn(enabled = holding)

        val progress = remember { Animatable(0f) }
        val currentOnCompleted by rememberUpdatedState(onCompleted)
        val haptic = LocalHapticFeedback.current

        LaunchedEffect(holding, completed) {
            if (completed) return@LaunchedEffect
            if (holding) {
                // 松手重按必须重新连续按满 30 秒，与「松开即重置」一致。
                progress.snapTo(0f)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                var lastMilestone = 0
                val startNanos = withFrameNanos { it }
                while (isActive && holding && !completed) {
                    val nowNanos = withFrameNanos { it }
                    val elapsedMillis = (nowNanos - startNanos).coerceAtLeast(0L) / 1_000_000f
                    val next = (elapsedMillis / HoldDurationMillis).coerceIn(0f, 1f)
                    progress.snapTo(next)
                    val elapsedSeconds = (next * HoldDurationSeconds).toInt()
                    val milestone = (elapsedSeconds / MilestoneStepSeconds)
                    if (milestone in 1..2 && milestone != lastMilestone) {
                        lastMilestone = milestone
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    if (next >= 1f) {
                        completed = true
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnCompleted()
                        break
                    }
                }
            } else if (progress.value > 0f) {
                progress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = RewindDurationMillis, easing = FastOutSlowInEasing),
                )
            }
        }

        val showTitle = !inPane
        val fillsHeight = inPane || fullHeightHost
        val fraction = progress.value.coerceIn(0f, 1f)

        if (fillsHeight) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val enoughHeight = maxHeight >= FullHeightStageThreshold
                if (enoughHeight) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = Spacing.Loose)
                            .padding(top = Spacing.Tight, bottom = Spacing.Loose),
                    ) {
                        NightHoldHeader(showTitle = showTitle, title = title, body = body)
                        Spacer(modifier = Modifier.weight(TopStageWeight))
                        NightHoldStage(
                            fraction = fraction,
                            holding = holding,
                            completed = completed,
                            compact = false,
                            onPressDelta = { delta ->
                                pointerPresses = (pointerPresses + delta).coerceAtLeast(0)
                            },
                            heldKey = heldKey,
                            onHeldKeyChange = { heldKey = it },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        Spacer(modifier = Modifier.weight(BottomStageWeight))
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.Loose)
                            .padding(top = Spacing.Tight, bottom = Spacing.Loose),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
                    ) {
                        NightHoldHeader(showTitle = showTitle, title = title, body = body)
                        NightHoldStage(
                            fraction = fraction,
                            holding = holding,
                            completed = completed,
                            compact = true,
                            onPressDelta = { delta ->
                                pointerPresses = (pointerPresses + delta).coerceAtLeast(0)
                            },
                            heldKey = heldKey,
                            onHeldKeyChange = { heldKey = it },
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    }
                }
            }
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val compact = maxHeight < CompactSheetThreshold
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = Spacing.Loose)
                        .padding(top = Spacing.Hair, bottom = Spacing.Loose),
                    verticalArrangement = Arrangement.spacedBy(
                        if (compact) Spacing.Comfortable else Spacing.Loose,
                    ),
                ) {
                    NightHoldHeader(showTitle = showTitle, title = title, body = body)
                    NightHoldStage(
                        fraction = fraction,
                        holding = holding,
                        completed = completed,
                        compact = compact,
                        onPressDelta = { delta ->
                            pointerPresses = (pointerPresses + delta).coerceAtLeast(0)
                        },
                        heldKey = heldKey,
                        onHeldKeyChange = { heldKey = it },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            }
        }
    }
}

@Composable
private fun NightHoldHeader(
    showTitle: Boolean,
    title: String,
    body: String?,
) {
    if (!showTitle && body.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        if (showTitle) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (!body.isNullOrBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NightHoldStage(
    fraction: Float,
    holding: Boolean,
    completed: Boolean,
    compact: Boolean,
    onPressDelta: (Int) -> Unit,
    heldKey: Key?,
    onHeldKeyChange: (Key?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val remainingSeconds = if (!holding && fraction <= 0f) {
        HoldDurationSeconds
    } else {
        ceil((1f - fraction) * HoldDurationSeconds).toInt().coerceIn(0, HoldDurationSeconds)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) Spacing.Comfortable else Spacing.Loose),
        modifier = modifier.fillMaxWidth(),
    ) {
        NightHoldDial(
            fraction = fraction,
            remainingSeconds = remainingSeconds,
            holding = holding,
            completed = completed,
            compact = compact,
            onPressDelta = onPressDelta,
        )
        NightHoldPad(
            fraction = fraction,
            remainingSeconds = remainingSeconds,
            holding = holding,
            completed = completed,
            onPressDelta = onPressDelta,
            heldKey = heldKey,
            onHeldKeyChange = onHeldKeyChange,
        )
    }
}

/**
 * 30 秒精密秒针刻度盘：外圈 30 格对应每秒进度（每 5 秒一格主刻度，按住时当前秒刻度带轻微外推脉冲），
 * 中圈为连续扫秒圆弧与领跑圆点，内圈色阶随按压抬高并居中展示 `ClockAxes` 倒计时。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NightHoldDial(
    fraction: Float,
    remainingSeconds: Int,
    holding: Boolean,
    completed: Boolean,
    compact: Boolean,
    onPressDelta: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val active = holding || completed || fraction > 0f

    val dialSize = if (compact) DialSizeCompact else DialSizeRegular
    val readoutSize = if (compact) DialReadoutCompact else DialReadoutRegular

    val dialScale by animateFloatAsState(
        targetValue = if (holding) DialHoldingScale else 1f,
        animationSpec = motion.fastSpatialSpec(),
        label = "nightDialScale",
    )
    val wellColor by animateColorAsState(
        targetValue = when {
            completed -> colors.primaryContainer
            holding -> colors.surfaceContainerHigh
            else -> colors.surfaceContainer
        },
        animationSpec = motion.defaultEffectsSpec(),
        label = "nightDialWell",
    )
    val readoutColor by animateColorAsState(
        targetValue = when {
            completed -> colors.onPrimaryContainer
            active -> colors.primary
            else -> colors.onSurface
        },
        animationSpec = motion.fastEffectsSpec(),
        label = "nightDialReadout",
    )
    val iconColor by animateColorAsState(
        targetValue = when {
            completed -> colors.onPrimaryContainer
            active -> colors.primary
            else -> colors.onSurfaceVariant
        },
        animationSpec = motion.fastEffectsSpec(),
        label = "nightDialIcon",
    )

    val primaryColor = colors.primary
    val onPrimaryColor = colors.onPrimary
    val trackColor = colors.surfaceContainerHighest
    val inactiveTickColor = colors.outlineVariant

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(dialSize)
            .graphicsLayer {
                scaleX = dialScale
                scaleY = dialScale
            }
            .clip(CircleShape)
            .holdPointer(completed = completed, onPressDelta = onPressDelta),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = size.minDimension / 2f - OuterMargin.toPx()

            val majorLen = (if (compact) MajorTickCompact else MajorTickRegular).toPx()
            val minorLen = (if (compact) MinorTickCompact else MinorTickRegular).toPx()
            val majorStroke = MajorTickWidth.toPx()
            val minorStroke = MinorTickWidth.toPx()
            val pulseMax = TickPulseOffset.toPx()
            val elapsed = fraction * HoldDurationSeconds

            for (index in 0 until HoldDurationSeconds) {
                val major = index % MajorTickInterval == 0
                val tickLen = if (major) majorLen else minorLen
                val angleRad = ((-90.0 + index * DegreesPerSecond) * PI / 180.0)
                val cosA = cos(angleRad).toFloat()
                val sinA = sin(angleRad).toFloat()

                val lit = ((elapsed - index) * TickFadeMultiplier).coerceIn(0f, 1f)
                val tickColor = lerp(inactiveTickColor, primaryColor, lit)

                val waveWindow = (elapsed - index + TickWaveLead) / TickWaveSpan
                val pulse = if (holding && waveWindow in 0f..1f) {
                    sin(waveWindow * PI).toFloat() * pulseMax
                } else {
                    0f
                }

                val rOuter = outerRadius + pulse
                val rInner = outerRadius - tickLen + pulse * 0.4f
                drawLine(
                    color = tickColor,
                    start = Offset(center.x + cosA * rInner, center.y + sinA * rInner),
                    end = Offset(center.x + cosA * rOuter, center.y + sinA * rOuter),
                    strokeWidth = if (major) majorStroke else minorStroke,
                    cap = StrokeCap.Round,
                )
            }

            val ringInset = (if (compact) RingInsetCompact else RingInsetRegular).toPx()
            val arcStroke = ArcStrokeWidth.toPx()
            val arcRadius = (outerRadius - ringInset).coerceAtLeast(arcStroke)
            val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
            val arcSize = Size(arcRadius * 2f, arcRadius * 2f)

            drawCircle(
                color = trackColor,
                radius = arcRadius,
                center = center,
                style = Stroke(width = arcStroke),
            )

            if (fraction > 0f) {
                val sweep = 360f * fraction
                drawArc(
                    color = primaryColor,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = arcStroke, cap = StrokeCap.Round),
                )
                val tipRad = ((-90.0 + sweep) * PI / 180.0)
                val tip = Offset(
                    x = center.x + cos(tipRad).toFloat() * arcRadius,
                    y = center.y + sin(tipRad).toFloat() * arcRadius,
                )
                drawCircle(
                    color = primaryColor,
                    radius = ArcBeadRadius.toPx(),
                    center = tip,
                )
                drawCircle(
                    color = onPrimaryColor,
                    radius = ArcBeadPinRadius.toPx(),
                    center = tip,
                )
            }

            val wellGap = (if (compact) WellGapCompact else WellGapRegular).toPx()
            val wellRadius = (arcRadius - wellGap).coerceAtLeast(0f)
            drawCircle(
                color = wellColor,
                radius = wellRadius,
                center = center,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            Icon(
                imageVector = when {
                    completed -> Icons.Filled.LockOpen
                    active -> Icons.Filled.Bedtime
                    else -> Icons.Outlined.Lock
                },
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(Dimens.IconInline),
            )
            Text(
                text = "0:%02d".format(remainingSeconds),
                style = TextStyle(
                    fontFamily = rememberClockFont(),
                    fontSize = readoutSize,
                    lineHeight = readoutSize,
                    fontFeatureSettings = "tnum",
                    textAlign = TextAlign.Center,
                ),
                color = readoutColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * 下方 M3 Expressive 触控垫：常态为 `secondaryContainer` 圆角块，按下形变为 `primary` 胶囊并轻微下沉。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NightHoldPad(
    fraction: Float,
    remainingSeconds: Int,
    holding: Boolean,
    completed: Boolean,
    onPressDelta: (Int) -> Unit,
    heldKey: Key?,
    onHeldKeyChange: (Key?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val engaged = holding || completed
    val latestHeldKey by rememberUpdatedState(heldKey)
    val latestOnHeldKeyChange by rememberUpdatedState(onHeldKeyChange)

    val corner by animateDpAsState(
        targetValue = if (engaged) HoldPadHeight / 2 else HoldPadIdleCorner,
        animationSpec = motion.fastSpatialSpec(),
        label = "nightPadCorner",
    )
    val scale by animateFloatAsState(
        targetValue = if (holding) HoldPadPressedScale else 1f,
        animationSpec = motion.fastSpatialSpec(),
        label = "nightPadScale",
    )
    val containerColor by animateColorAsState(
        targetValue = if (engaged) colors.primary else colors.secondaryContainer,
        animationSpec = motion.fastEffectsSpec(),
        label = "nightPadContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (engaged) colors.onPrimary else colors.onSecondaryContainer,
        animationSpec = motion.fastEffectsSpec(),
        label = "nightPadContent",
    )

    val idleLabel = stringResource(Res.string.night_mode_hold_idle)
    val holdingLabel = stringResource(Res.string.night_mode_hold_holding)
    val a11yProgress = stringResource(Res.string.night_mode_hold_progress, remainingSeconds)

    DisposableEffect(Unit) {
        onDispose { latestOnHeldKeyChange(null) }
    }

    val shape = RoundedCornerShape(corner)
    Surface(
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = Modifier
            .widthIn(max = HoldPadMaxWidth)
            .fillMaxWidth()
            .height(HoldPadHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = if (holding || fraction > 0f) a11yProgress else idleLabel
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = fraction,
                    range = 0f..1f,
                )
            }
            .onFocusChanged { focusState ->
                if (!focusState.isFocused && latestHeldKey != null) {
                    latestOnHeldKeyChange(null)
                }
            }
            .onKeyEvent { event ->
                if (event.key != Key.Spacebar && event.key != Key.Enter) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (!completed && latestHeldKey == null) latestOnHeldKeyChange(event.key)
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (latestHeldKey == event.key) latestOnHeldKeyChange(null)
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .holdPointer(completed = completed, onPressDelta = onPressDelta),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight, Alignment.CenterHorizontally),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.Loose),
        ) {
            Icon(
                imageVector = when {
                    completed -> Icons.Filled.LockOpen
                    holding -> Icons.Filled.Lock
                    else -> Icons.Outlined.TouchApp
                },
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconInline),
            )
            Text(
                text = if (engaged) holdingLabel else idleLabel,
                style = MaterialTheme.typography.labelLargeEmphasized,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Modifier.holdPointer(
    completed: Boolean,
    onPressDelta: (Int) -> Unit,
): Modifier {
    val latestOnPressDelta by rememberUpdatedState(onPressDelta)
    return this.pointerInput(completed) {
        if (completed) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            latestOnPressDelta(1)
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    if (change.isOutOfBounds(size, extendedTouchPadding)) {
                        break
                    }
                    change.consume()
                }
            } finally {
                latestOnPressDelta(-1)
            }
        }
    }
}

/** 临时退出或放松夜间限制前需要持续按住的时长。 */
private const val HoldDurationMillis = 30_000L
private const val HoldDurationSeconds = 30
private const val MilestoneStepSeconds = 10
private const val RewindDurationMillis = 320

private const val DegreesPerSecond = 360.0 / HoldDurationSeconds
private const val MajorTickInterval = 5
private const val TickFadeMultiplier = 2.5f
private const val TickWaveLead = 0.25f
private const val TickWaveSpan = 1.25f

private const val TopStageWeight = 0.85f
private const val BottomStageWeight = 1.15f
private const val DialHoldingScale = 1.025f
private const val HoldPadPressedScale = 0.97f

private val FullHeightStageThreshold = 440.dp
private val CompactSheetThreshold = 460.dp

private val DialSizeRegular = 212.dp
private val DialSizeCompact = 156.dp
private val DialReadoutRegular = 56.sp
private val DialReadoutCompact = 40.sp

private val OuterMargin = 4.dp
private val MajorTickRegular = 10.dp
private val MajorTickCompact = 7.dp
private val MinorTickRegular = 6.dp
private val MinorTickCompact = 4.5.dp
private val MajorTickWidth = 2.5.dp
private val MinorTickWidth = 1.75.dp
private val TickPulseOffset = 2.5.dp

private val RingInsetRegular = 16.dp
private val RingInsetCompact = 12.dp
private val ArcStrokeWidth = 4.dp
private val ArcBeadRadius = 5.5.dp
private val ArcBeadPinRadius = 2.dp
private val WellGapRegular = 12.dp
private val WellGapCompact = 9.dp

private val HoldPadMaxWidth = 320.dp
private val HoldPadHeight = 56.dp
private val HoldPadIdleCorner = 16.dp
