package dev.bilby.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.BackHandler
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.PlayerTheme
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

private object SpeedDialSize {
    val Thickness = 88.dp
    val LongBlock = 128.dp
    val ShortBlock = 54.dp
    val LabelMask = 108.dp
    val VerticalLabelWidth = 48.dp
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpeedSelectionHint(controller: PlaybackSpeedController, modifier: Modifier = Modifier) {
    val selection = controller.selection
    val window = LocalWindowInfo.current.containerSize
    val vertical = window.width > window.height
    val locked = controller.lockedHint
    val releaseToLock = selection?.let { it.fine || it.locked } == true
    val text = when {
        locked != null -> stringResource(Res.string.player_speed_locked, formatSpeed(locked / 100f))
        releaseToLock -> stringResource(Res.string.player_speed_release_to_lock)
        else -> stringResource(if (vertical) Res.string.player_speed_coarse_vertical else Res.string.player_speed_coarse_horizontal)
    }
    val revision = controller.hintRevision
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(revision) {
        if (controller.lockedHint == null) return@LaunchedEffect
        delay(900)
        if (controller.hintRevision == revision) controller.clearHint()
    }
    AnimatedVisibility(visible = selection != null || locked != null, enter = fadeIn(effects), exit = fadeOut(effects), modifier = modifier) {
        if (vertical) {
            SpeedBoostCapsule(text, icon = if (locked != null || releaseToLock) Icons.Filled.Lock else Icons.Filled.FastForward)
        } else {
            Text(
                text,
                color = FixedColors.OnMedia,
                style = MaterialTheme.typography.labelLarge.copy(shadow = Shadow(Color.Black, blurRadius = 4f)),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Spacing.Comfortable),
            )
        }
    }
}

@Composable
internal fun SpeedSelectionOverlay(
    controller: PlaybackSpeedController,
    playerBounds: Rect? = null,
    dimDetails: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (!controller.active && controller.lockedHint == null) return
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(controller) {
        snapshotFlow { controller.coarseStepRevision }.drop(1).collect {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    val window = LocalWindowInfo.current.containerSize
    val vertical = window.width > window.height
    var origin by remember { mutableStateOf(Offset.Zero) }
    val focus = remember { FocusRequester() }
    BackHandler(enabled = controller.active) { controller.cancel() }
    LaunchedEffect(controller.active) { if (controller.active) focus.requestFocus() }
    PlayerTheme {
        Box(
            modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !controller.active) return@onKeyEvent false
                    when (event.key) {
                        Key.Escape -> controller.cancel()
                        Key.Enter -> { controller.lock(); controller.finish() }
                        Key.DirectionUp -> if (vertical) controller.step(-1) else controller.finer()
                        Key.DirectionDown -> if (vertical) controller.step(1) else controller.coarserOrLock()
                        Key.DirectionRight -> if (vertical) controller.finer() else controller.step(1)
                        Key.DirectionLeft -> if (vertical) controller.coarserOrLock() else controller.step(-1)
                        else -> return@onKeyEvent false
                    }
                    true
                }.focusRequester(focus).focusable(),
        ) {
            if (controller.active) {
                Box(Modifier.fillMaxSize().pointerInput(controller) { detectTapGestures { controller.finish() } })
                if (dimDetails) Canvas(Modifier.fillMaxSize()) {
                    val bounds = playerBounds?.translate(-origin)
                    if (bounds == null) drawRect(FixedColors.ScrimOnMedia)
                    else clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom, ClipOp.Difference) {
                        drawRect(FixedColors.ScrimOnMedia)
                    }
                }
                SpeedDial(
                    controller,
                    vertical,
                    Modifier.align(if (vertical) Alignment.CenterStart else Alignment.BottomCenter)
                        .windowInsetsPadding(
                            WindowInsets.barsAndCutout.only(
                                if (vertical) WindowInsetsSides.Start + WindowInsetsSides.Vertical
                                else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                            ),
                        )
                        .then(if (vertical) Modifier.width(SpeedDialSize.Thickness).fillMaxHeight() else Modifier.height(SpeedDialSize.Thickness).fillMaxWidth()),
                )
            }
            SpeedSelectionHint(
                controller,
                if (vertical) Modifier.align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.barsAndCutout).padding(top = Spacing.Comfortable)
                else Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.barsAndCutout.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(bottom = SpeedDialSize.Thickness + Spacing.Tight),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpeedDial(controller: PlaybackSpeedController, vertical: Boolean, modifier: Modifier) {
    val selection = controller.selection ?: return
    val description = stringResource(Res.string.player_speed)
    val fineAction = stringResource(Res.string.player_speed_fine_action)
    val coarseAction = stringResource(Res.string.player_speed_coarse_action)
    val lockAction = stringResource(Res.string.player_speed_lock_action)
    val density = LocalDensity.current
    val background = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .96f)
    val valueDescription = formatSpeed(selection.speed)
    val input = modifier.clipToBounds()
        .semantics {
            contentDescription = description
            stateDescription = valueDescription
            progressBarRangeInfo = ProgressBarRangeInfo(selection.speed, .5f..3f)
            setProgress {
                controller.adjustTo(it)
                controller.lock()
                controller.finish()
                true
            }
            customActions = listOf(
                CustomAccessibilityAction(if (selection.fine) coarseAction else fineAction) {
                    if (selection.fine) controller.coarserOrLock() else controller.finer()
                    true
                },
                CustomAccessibilityAction(lockAction) {
                    controller.lock()
                    controller.finish()
                    true
                },
            )
        }
        .pointerInput(controller) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.type != PointerEventType.Scroll || controller.dragging) continue
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.scrollDelta.y != 0f) {
                        controller.step(if (change.scrollDelta.y > 0) 1 else -1)
                        change.consume()
                    }
                }
            }
        }
        .pointerInput(controller, vertical) {
            var owned = false
            var displacement = Offset.Zero
            try {
                detectDragGestures(
                    onDragStart = {
                        owned = controller.beginDrag()
                        displacement = Offset.Zero
                    },
                    onDragEnd = {
                        if (owned) controller.finish()
                        owned = false
                    },
                    onDragCancel = {
                        if (owned) controller.cancel()
                        owned = false
                    },
                ) { change, amount ->
                    change.consume()
                    if (owned) {
                        displacement += amount
                        controller.move(
                            alongDp = (if (vertical) displacement.y else displacement.x) / density.density,
                            acrossDp = (if (vertical) -displacement.x else displacement.y) / density.density,
                        )
                    }
                }
            } finally {
                if (owned) controller.cancel()
            }
        }
    // 指针监听留在同一个宿主上;切换内容时移除监听会取消正在进行的精调手势。
    Box(input) {
        if (selection.fine) {
            SpeedRuler(selection.value, vertical, background, Modifier.fillMaxSize().background(background))
        } else {
            val position by animateFloatAsState(selection.position, MaterialTheme.motionScheme.fastSpatialSpec(), label = "speed-blocks")
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val center = with(density) { if (vertical) maxHeight.toPx() / 2 else maxWidth.toPx() / 2 }
                val width = if (vertical) SpeedDialSize.ShortBlock else SpeedDialSize.LongBlock
                val height = if (vertical) SpeedDialSize.LongBlock else SpeedDialSize.ShortBlock
                val widthPx = with(density) { width.toPx() }
                val heightPx = with(density) { height.toPx() }
                val stepPx = with(density) { SpeedBlockStepDp.dp.toPx() }
                selection.choices.forEachIndexed { index, value ->
                    val selected = value == selection.value
                    Box(
                        Modifier.offset {
                            val along = center + (index - position) * stepPx
                            IntOffset(
                                (if (vertical) (constraints.maxWidth - widthPx) / 2 else along - widthPx / 2).roundToInt(),
                                (if (vertical) along - heightPx / 2 else (constraints.maxHeight - heightPx) / 2).roundToInt(),
                            )
                        }.size(width, height).clip(MaterialTheme.shapes.small)
                            .background(if (selected) MaterialTheme.colorScheme.primary else background)
                            .clickable { controller.choose(value) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            formatSpeed(value / 100f),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else FixedColors.OnMedia,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedRuler(value: Int, vertical: Boolean, background: Color, modifier: Modifier) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val labelStyle = MaterialTheme.typography.labelLarge.copy(
        color = FixedColors.OnMedia.copy(alpha = .65f),
        fontFeatureSettings = "tnum",
    )
    val currentStyle = MaterialTheme.typography.titleLarge.copy(
        color = MaterialTheme.colorScheme.primary,
        fontFeatureSettings = "tnum",
    )
    val current = measurer.measure(preciseSpeedNumber(value), currentStyle)
    val pointer = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val center = if (vertical) size.height / 2 else size.width / 2
        val spacing = SpeedFineStepDp.dp.toPx()
        val span = ((if (vertical) size.height else size.width) / spacing / 2).roundToInt() + 2
        val tickColor = FixedColors.OnMedia.copy(alpha = .5f)
        val labelRow = (Spacing.Spacious + Spacing.Comfortable).toPx()
        val maskLength = SpeedDialSize.LabelMask.toPx()
        for (tick in (value - span).coerceAtLeast(50)..(value + span).coerceAtMost(300)) {
            val position = center + (tick - value) * spacing
            val length = (if (tick % 10 == 0) Spacing.Loose else if (tick % 5 == 0) Spacing.Comfortable else Spacing.Tight).toPx()
            if (vertical) {
                drawLine(tickColor, Offset(Spacing.Tight.toPx(), position), Offset(Spacing.Tight.toPx() + length, position))
            } else {
                drawLine(tickColor, Offset(position, Spacing.Cozy.toPx()), Offset(position, Spacing.Cozy.toPx() + length))
            }
            if (tick % 10 == 0) {
                val label = measurer.measure("${tick / 100}.${tick % 100 / 10}", labelStyle)
                val topLeft = if (vertical) Offset(Spacing.Spacious.toPx(), position - label.size.height / 2)
                    else Offset(position - label.size.width / 2, labelRow)
                drawText(label, topLeft = topLeft)
            }
        }
        val opaque = background.copy(alpha = 1f)
        val colors = arrayOf(0f to Color.Transparent, .3f to opaque, .7f to opaque, 1f to Color.Transparent)
        if (vertical) {
            drawLine(pointer, Offset(Spacing.Hair.toPx(), center), Offset(Spacing.Spacious.toPx(), center), strokeWidth = 2.dp.toPx())
            val top = center - maskLength / 2
            drawRect(
                Brush.verticalGradient(*colors, startY = top, endY = top + maskLength),
                Offset(Spacing.Spacious.toPx(), top),
                Size(SpeedDialSize.VerticalLabelWidth.toPx(), maskLength),
            )
            drawText(current, topLeft = Offset(Spacing.Spacious.toPx(), center - current.size.height / 2))
        } else {
            drawLine(
                pointer,
                Offset(center, Spacing.Hair.toPx()),
                Offset(center, (Spacing.Spacious + Spacing.Tight).toPx()),
                strokeWidth = 2.dp.toPx(),
            )
            val left = center - maskLength / 2
            drawRect(
                Brush.horizontalGradient(*colors, startX = left, endX = left + maskLength),
                Offset(left, labelRow),
                Size(maskLength, current.size.height.toFloat()),
            )
            drawText(current, topLeft = Offset(center - current.size.width / 2, labelRow))
        }
    }
}
