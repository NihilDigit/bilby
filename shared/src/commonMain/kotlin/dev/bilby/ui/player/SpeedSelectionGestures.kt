package dev.bilby.ui.player

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo

@Composable
internal fun Modifier.speedSelectionLongPress(
    controller: PlaybackSpeedController?,
    enabled: Boolean,
    onStart: () -> Unit,
): Modifier {
    val currentOnStart by rememberUpdatedState(onStart)
    val haptics = LocalHapticFeedback.current
    val window = LocalWindowInfo.current.containerSize
    val vertical = window.width > window.height
    return pointerInput(controller, enabled, vertical) {
        if (controller == null || !enabled) return@pointerInput
        var owned = false
        var dockEnd = false
        var displacement = Offset.Zero
        try {
            detectDragGesturesAfterLongPress(
                onDragStart = { startOffset ->
                    dockEnd = vertical && startOffset.x < size.width / 2f
                    controller.open(
                        temporary = true,
                        dockEnd = dockEnd,
                        initialSpeed = controller.defaultHoldSpeed,
                    )
                    owned = controller.beginDrag()
                    displacement = Offset.Zero
                    currentOnStart()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                if (!owned) return@detectDragGesturesAfterLongPress
                displacement += amount
                val before = controller.selection
                val acrossPx = when {
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
        } finally {
            if (owned) controller.cancel()
        }
    }
}
