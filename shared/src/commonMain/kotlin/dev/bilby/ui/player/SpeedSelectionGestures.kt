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
        var displacement = Offset.Zero
        try {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    controller.open(temporary = true)
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
                controller.move(
                    alongDp = (if (vertical) displacement.y else displacement.x) / density,
                    acrossDp = (if (vertical) -displacement.x else displacement.y) / density,
                )
                val after = controller.selection
                if (after?.fine != before?.fine || after?.locked != before?.locked) {
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                }
            }
        } finally {
            if (owned) controller.cancel()
        }
    }
}
