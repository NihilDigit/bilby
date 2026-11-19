package dev.bilby.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo

private sealed interface SurfaceGestureRace {
    data object Ended : SurfaceGestureRace
    data class DragStarted(val change: PointerInputChange) : SurfaceGestureRace
}

/**
 * 播放器画面的统一互斥手势状态机(拖拽进退/亮度音量 vs 长按倍速选档)。
 *
 * 单个 `awaitEachGesture` 内在 touchSlop 与 longPressTimeoutMillis 之间二选一:
 * - 先越过 touchSlop → 独占拖拽模式,本次抬手前不再触发长按倍速;
 * - 先达到 longPressTimeoutMillis → 独占倍速选档模式,后续位移全归倍速选档,不再触发 seek/亮度/音量;
 * - 按下时倍速选档已处于激活态(含竖屏内嵌 showSpeedOverlay = false) → 忽略画面拖拽与长按。
 */
@Composable
internal fun Modifier.playerSurfaceGestures(
    key1: Any?,
    locked: Boolean,
    pipWindow: PipWindow?,
    speedController: PlaybackSpeedController?,
    speedEnabled: Boolean,
    dialEnabled: Boolean = true,
    forceHorizontalBelow: Boolean = false,
    onSpeedStart: () -> Unit,
    onDragStart: (start: Offset, width: Float, height: Float) -> Unit,
    onDrag: (position: Offset, delta: Offset, width: Float, height: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
): Modifier {
    val currentSpeedEnabled by rememberUpdatedState(speedEnabled)
    val currentDialEnabled by rememberUpdatedState(dialEnabled)
    val currentOnSpeedStart by rememberUpdatedState(onSpeedStart)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)
    val haptics = LocalHapticFeedback.current
    val window = LocalWindowInfo.current.containerSize
    val vertical = !forceHorizontalBelow && window.width > window.height

    return pointerInput(key1, locked, pipWindow, speedController, vertical, forceHorizontalBelow) {
        if (locked) return@pointerInput
        // 小窗没有标题栏,拖画面就是拖窗口;小窗里也用不着横划 seek,底下有进度条。
        if (pipWindow != null) {
            detectDragGestures(onDragStart = { pipWindow.startMove() }) { change, _ ->
                change.consume()
                pipWindow.move()
            }
            return@pointerInput
        }

        var speedOwned = false
        var dragActive = false
        try {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (speedController?.active == true) return@awaitEachGesture

                val pointerId = down.id
                val startPosition = down.position
                var lastPosition = startPosition
                val touchSlop = viewConfiguration.touchSlop
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                val canSpeedDial = currentSpeedEnabled && speedController != null

                var dragStartChange: PointerInputChange? = null
                var enterSpeedDial = false

                if (canSpeedDial) {
                    val outcome: SurfaceGestureRace? = withTimeoutOrNull(longPressTimeout) {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: return@withTimeoutOrNull SurfaceGestureRace.Ended
                            if (change.isConsumed || !change.pressed) {
                                return@withTimeoutOrNull SurfaceGestureRace.Ended
                            }
                            lastPosition = change.position
                            if ((change.position - startPosition).getDistance() > touchSlop) {
                                return@withTimeoutOrNull SurfaceGestureRace.DragStarted(change)
                            }
                        }
                    }
                    when (outcome) {
                        null -> enterSpeedDial = true
                        SurfaceGestureRace.Ended -> return@awaitEachGesture
                        is SurfaceGestureRace.DragStarted -> dragStartChange = outcome.change
                    }
                } else {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == pointerId }
                            ?: return@awaitEachGesture
                        if (change.isConsumed || !change.pressed) {
                            return@awaitEachGesture
                        }
                        lastPosition = change.position
                        if ((change.position - startPosition).getDistance() > touchSlop) {
                            dragStartChange = change
                            break
                        }
                    }
                }

                if (enterSpeedDial) {
                    val controller = speedController ?: return@awaitEachGesture
                    if (!currentSpeedEnabled || controller.active) return@awaitEachGesture
                    val dial = currentDialEnabled
                    val dockEnd = dial && vertical && lastPosition.x < size.width / 2f
                    // 竖屏内嵌时选档块画在画面下方(forceHorizontalBelow),一律下滑锁定;
                    // 竖屏全屏时偏下长按把选档条放画面上沿(上滑锁定),偏上长按放画面下沿(下滑锁定)。
                    val dockTop = dial && !vertical && !forceHorizontalBelow && lastPosition.y >= size.height / 2f
                    controller.open(temporary = true, dockEnd = dockEnd, dockTop = dockTop)
                    speedOwned = controller.beginDrag(vertical = vertical)
                    var displacement = Offset.Zero
                    currentOnSpeedStart()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null) {
                            if (speedOwned) controller.cancel()
                            speedOwned = false
                            break
                        }
                        if (!change.pressed) {
                            change.consume()
                            if (speedOwned) {
                                if (change.changedToUpIgnoreConsumed()) {
                                    controller.finish()
                                } else {
                                    controller.cancel()
                                }
                            }
                            speedOwned = false
                            break
                        }
                        val amount = change.positionChange()
                        change.consume()
                        if (!speedOwned || !currentDialEnabled || amount == Offset.Zero) continue
                        displacement += amount
                        val before = controller.selection
                        val acrossPx = when {
                            !vertical && dockTop -> -displacement.y
                            !vertical -> displacement.y
                            dockEnd -> displacement.x
                            else -> -displacement.x
                        }
                        controller.move(
                            alongDp = (if (vertical) displacement.y else displacement.x) / density,
                            acrossDp = acrossPx / density,
                        )
                        val after = controller.selection
                        if (after?.locked != before?.locked) {
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                    }
                    return@awaitEachGesture
                }

                val firstChange = dragStartChange ?: return@awaitEachGesture
                val width = size.width.toFloat().coerceAtLeast(1f)
                val height = size.height.toFloat().coerceAtLeast(1f)
                dragActive = true
                currentOnDragStart(startPosition, width, height)
                firstChange.consume()
                val initialDelta = firstChange.position - startPosition
                if (initialDelta != Offset.Zero) {
                    currentOnDrag(firstChange.position, initialDelta, width, height)
                }

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == pointerId }
                    if (change == null || !change.pressed) {
                        if (change != null && change.changedToUpIgnoreConsumed()) {
                            change.consume()
                            dragActive = false
                            currentOnDragEnd()
                        } else {
                            dragActive = false
                            currentOnDragCancel()
                        }
                        break
                    }
                    if (change.isConsumed) {
                        dragActive = false
                        currentOnDragCancel()
                        break
                    }
                    val delta = change.positionChange()
                    change.consume()
                    if (delta != Offset.Zero) {
                        currentOnDrag(
                            change.position,
                            delta,
                            size.width.toFloat().coerceAtLeast(1f),
                            size.height.toFloat().coerceAtLeast(1f),
                        )
                    }
                }
            }
        } finally {
            if (speedOwned) {
                speedController?.cancel()
                speedOwned = false
            }
            if (dragActive) {
                dragActive = false
                currentOnDragCancel()
            }
        }
    }
}
