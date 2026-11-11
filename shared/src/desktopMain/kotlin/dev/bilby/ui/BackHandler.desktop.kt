package dev.bilby.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )

@Composable
actual fun rememberBackTrigger(): () -> Unit {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val input = remember { DirectNavigationEventInput() }
    DisposableEffect(dispatcher, input) {
        dispatcher?.addInput(input)
        onDispose { dispatcher?.removeInput(input) }
    }
    val latestDispatcher by rememberUpdatedState(dispatcher)
    return {
        if (latestDispatcher != null) {
            input.backCompleted()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.mouseBackNavigation(onBack: () -> Unit): Modifier =
    pointerInput(onBack) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.button == PointerButton.Back) {
                    event.changes.forEach { it.consume() }
                    onBack()
                }
            }
        }
    }
