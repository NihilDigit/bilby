package dev.bilby.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.BackHandler
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.PlayerTheme
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlin.math.roundToInt

private object SpeedDialSize {
    val Thickness = 80.dp
    val LongBlock = 108.dp
    val ShortBlock = Dimens.MinTouchTarget
    val UnselectedCorner = 12.dp
    val EdgeFade = Spacing.Spacious
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpeedSelectionHint(controller: PlaybackSpeedController, modifier: Modifier = Modifier) {
    val selection = controller.selection
    val window = LocalWindowInfo.current.containerSize
    val vertical = window.width > window.height
    val locked = controller.lockedHint
    val releaseToLock = selection?.locked == true
    val dockEnd = vertical && selection?.dockEnd == true
    val text = when {
        locked != null -> stringResource(Res.string.player_speed_locked, formatSpeed(locked / 100f))
        releaseToLock -> stringResource(Res.string.player_speed_release_to_lock)
        !vertical -> stringResource(Res.string.player_speed_coarse_horizontal)
        dockEnd -> stringResource(Res.string.player_speed_coarse_vertical_right)
        else -> stringResource(Res.string.player_speed_coarse_vertical)
    }
    var shownText by remember { mutableStateOf(text) }
    var shownLockedIcon by remember { mutableStateOf(false) }
    if (selection != null || locked != null) {
        shownText = text
        shownLockedIcon = locked != null || releaseToLock
    }
    val revision = controller.hintRevision
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    LaunchedEffect(revision) {
        if (controller.lockedHint == null) return@LaunchedEffect
        delay(900)
        if (controller.hintRevision == revision) controller.clearHint()
    }
    AnimatedVisibility(
        visible = selection != null || locked != null,
        enter = fadeIn(effects),
        exit = fadeOut(effects),
        modifier = modifier,
    ) {
        SpeedBoostCapsule(
            text = shownText,
            icon = if (shownLockedIcon) Icons.Filled.Lock else Icons.Filled.FastForward,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SpeedSelectionOverlay(
    controller: PlaybackSpeedController,
    playerBounds: Rect? = null,
    dimDetails: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var shownSelection by remember { mutableStateOf<SpeedSelection?>(null) }
    controller.selection?.let { shownSelection = it }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(controller) {
        snapshotFlow { controller.coarseStepRevision }.drop(1).collect {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    val window = LocalWindowInfo.current.containerSize
    val vertical = window.width > window.height
    val dockEnd = vertical && (controller.selection ?: shownSelection)?.dockEnd == true
    var origin by remember { mutableStateOf(Offset.Zero) }
    val focus = remember { FocusRequester() }
    BackHandler(enabled = controller.active) { controller.cancel() }
    LaunchedEffect(controller.active) { if (controller.active) focus.requestFocus() }
    PlayerTheme {
        val motion = MaterialTheme.motionScheme
        val spatialOffset = motion.fastSpatialSpec<IntOffset>()
        val effects = motion.fastEffectsSpec<Float>()
        Box(
            modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !controller.active) return@onKeyEvent false
                    when (event.key) {
                        Key.Escape -> controller.cancel()
                        Key.Enter -> { controller.lock(); controller.finish() }
                        Key.DirectionUp -> if (vertical) controller.step(-1) else return@onKeyEvent false
                        Key.DirectionDown -> if (vertical) controller.step(1) else { controller.lock(); controller.finish() }
                        Key.DirectionRight -> when {
                            !vertical -> controller.step(1)
                            dockEnd -> { controller.lock(); controller.finish() }
                            else -> return@onKeyEvent false
                        }
                        Key.DirectionLeft -> when {
                            !vertical -> controller.step(-1)
                            !dockEnd -> { controller.lock(); controller.finish() }
                            else -> return@onKeyEvent false
                        }
                        else -> return@onKeyEvent false
                    }
                    true
                }.focusRequester(focus).focusable(),
        ) {
            if (controller.active) {
                Box(Modifier.fillMaxSize().pointerInput(controller) { detectTapGestures { controller.finish() } })
            }
            AnimatedVisibility(
                visible = controller.active && dimDetails,
                enter = fadeIn(effects),
                exit = fadeOut(effects),
                modifier = Modifier.fillMaxSize(),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val bounds = playerBounds?.translate(-origin)
                    if (bounds == null) drawRect(FixedColors.ScrimOnMedia)
                    else clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom, ClipOp.Difference) {
                        drawRect(FixedColors.ScrimOnMedia)
                    }
                }
            }
            AnimatedVisibility(
                visible = controller.active,
                enter = (when {
                    !vertical -> slideInVertically(spatialOffset) { it / 2 }
                    dockEnd -> slideInHorizontally(spatialOffset) { it / 2 }
                    else -> slideInHorizontally(spatialOffset) { -it / 2 }
                }) + fadeIn(effects),
                exit = (when {
                    !vertical -> slideOutVertically(spatialOffset) { it / 2 }
                    dockEnd -> slideOutHorizontally(spatialOffset) { it / 2 }
                    else -> slideOutHorizontally(spatialOffset) { -it / 2 }
                }) + fadeOut(effects),
                modifier = Modifier.align(
                    when {
                        !vertical -> Alignment.BottomCenter
                        dockEnd -> Alignment.CenterEnd
                        else -> Alignment.CenterStart
                    },
                ),
            ) {
                val selection = controller.selection ?: shownSelection
                if (selection != null) {
                    val scrim = when {
                        !vertical -> Brush.verticalGradient(
                            listOf(Color.Transparent, FixedColors.PlayerControlScrim, ControlScrimBottom),
                        )
                        selection.dockEnd -> Brush.horizontalGradient(
                            listOf(Color.Transparent, FixedColors.PlayerControlScrim, ControlScrimBottom),
                        )
                        else -> Brush.horizontalGradient(
                            listOf(ControlScrimBottom, FixedColors.PlayerControlScrim, Color.Transparent),
                        )
                    }
                    SpeedDial(
                        controller = controller,
                        selection = selection,
                        vertical = vertical,
                        modifier = Modifier
                            .background(scrim)
                            .windowInsetsPadding(
                                WindowInsets.barsAndCutout.only(
                                    when {
                                        !vertical -> WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                                        selection.dockEnd -> WindowInsetsSides.End + WindowInsetsSides.Vertical
                                        else -> WindowInsetsSides.Start + WindowInsetsSides.Vertical
                                    },
                                ),
                            )
                            .then(
                                if (vertical) Modifier.width(SpeedDialSize.Thickness).fillMaxHeight()
                                else Modifier.height(SpeedDialSize.Thickness).fillMaxWidth(),
                            ),
                    )
                }
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
private fun SpeedDial(
    controller: PlaybackSpeedController,
    selection: SpeedSelection,
    vertical: Boolean,
    modifier: Modifier,
) {
    val description = stringResource(Res.string.player_speed)
    val lockAction = stringResource(Res.string.player_speed_lock_action)
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val valueDescription = formatSpeed(selection.speed)
    val fadePx = with(density) { SpeedDialSize.EdgeFade.toPx() }
    val input = modifier
        .clipToBounds()
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val span = if (vertical) size.height else size.width
            if (span > fadePx * 2f) {
                val startFraction = (fadePx / span).coerceIn(0f, 0.25f)
                val stops = arrayOf(
                    0f to Color.Transparent,
                    startFraction to Color.Black,
                    (1f - startFraction) to Color.Black,
                    1f to Color.Transparent,
                )
                val mask = if (vertical) Brush.verticalGradient(*stops) else Brush.horizontalGradient(*stops)
                drawRect(brush = mask, blendMode = BlendMode.DstIn)
            }
        }
        .semantics {
            contentDescription = description
            stateDescription = valueDescription
            progressBarRangeInfo = ProgressBarRangeInfo(
                current = selection.speed,
                range = .25f..3f,
                steps = (selection.choices.size - 2).coerceAtLeast(0),
            )
            setProgress {
                controller.adjustTo(it)
                controller.lock()
                controller.finish()
                true
            }
            customActions = listOf(
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
                        val before = controller.selection
                        val alongPos = if (vertical) change.position.y else change.position.x
                        val span = (if (vertical) size.height else size.width).toFloat()
                        val edgePx = SpeedEdgeZoneDp * density.density
                        val acrossPx = when {
                            !vertical -> displacement.y
                            before?.dockEnd == true -> displacement.x
                            else -> -displacement.x
                        }
                        controller.move(
                            alongDp = (if (vertical) displacement.y else displacement.x) / density.density,
                            acrossDp = acrossPx / density.density,
                            atStartEdge = span > edgePx * 2f && alongPos <= edgePx,
                            atEndEdge = span > edgePx * 2f && alongPos >= span - edgePx,
                        )
                        val after = controller.selection
                        if (after?.locked != before?.locked) {
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                    }
                }
            } finally {
                if (owned) controller.cancel()
            }
        }
    val animatedPosition by animateFloatAsState(
        targetValue = selection.position,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "speed-blocks",
    )
    // 拖拽中直接跟手,松手或点击时走弹簧吸附。
    val position = if (controller.dragging) selection.position else animatedPosition
    BoxWithConstraints(input) {
        val center = with(density) { if (vertical) maxHeight.toPx() / 2 else maxWidth.toPx() / 2 }
        val width = if (vertical) SpeedDialSize.ShortBlock else SpeedDialSize.LongBlock
        val height = if (vertical) SpeedDialSize.LongBlock else SpeedDialSize.ShortBlock
        val widthPx = with(density) { width.toPx() }
        val heightPx = with(density) { height.toPx() }
        val stepPx = with(density) { SpeedBlockStepDp.dp.toPx() }
        val colors = MaterialTheme.colorScheme
        val unselectedContainer = mediaControlContainer()
        selection.choices.forEachIndexed { index, value ->
            val selected = value == selection.value
            val corner by animateDpAsState(
                targetValue = if (selected) SpeedDialSize.ShortBlock / 2 else SpeedDialSize.UnselectedCorner,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "speed-corner",
            )
            val containerColor by animateColorAsState(
                targetValue = when {
                    selected && selection.locked -> colors.primaryContainer
                    selected -> colors.primary
                    else -> unselectedContainer
                },
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "speed-container",
            )
            val contentColor by animateColorAsState(
                targetValue = when {
                    selected && selection.locked -> colors.onPrimaryContainer
                    selected -> colors.onPrimary
                    else -> colors.onSurfaceVariant
                },
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "speed-content",
            )
            Box(
                Modifier.offset {
                    val along = center + (index - position) * stepPx
                    IntOffset(
                        (if (vertical) (constraints.maxWidth - widthPx) / 2 else along - widthPx / 2).roundToInt(),
                        (if (vertical) along - heightPx / 2 else (constraints.maxHeight - heightPx) / 2).roundToInt(),
                    )
                }.size(width, height)
                    .clip(RoundedCornerShape(corner))
                    .background(containerColor)
                    .clickable(role = Role.Button) { controller.choose(value) },
                contentAlignment = Alignment.Center,
            ) {
                val label = formatSpeed(value / 100f)
                val textStyle = (if (selected) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.titleMedium)
                    .copy(fontFeatureSettings = "tnum")
                val icon = if (selection.locked) Icons.Filled.Lock else Icons.Filled.FastForward
                if (selected && vertical) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(Dimens.IconInline),
                        )
                        Text(label, style = textStyle, color = contentColor)
                    }
                } else if (selected) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = contentColor,
                            modifier = Modifier.size(Dimens.IconInline),
                        )
                        Text(label, style = textStyle, color = contentColor)
                    }
                } else {
                    Text(label, style = textStyle, color = contentColor)
                }
            }
        }
    }
}
