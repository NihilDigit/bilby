package dev.bilby.ui

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    androidx.activity.compose.BackHandler(enabled, onBack)

@Composable
actual fun rememberBackTrigger(): () -> Unit {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val latest = rememberUpdatedState(dispatcher)
    return { latest.value?.onBackPressed() }
}

actual fun Modifier.mouseBackNavigation(onBack: () -> Unit): Modifier = this
