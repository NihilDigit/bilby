package dev.bilby.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import dev.bilby.ui.LocalWindowCaption

internal actual fun fullScreenDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false)

@Composable
internal actual fun DialogIntoDisplayCutout() {
    LocalWindowCaption.current?.Suppress()
}

@Composable
internal actual fun ConfigureInputDialogWindow() {
    LocalWindowCaption.current?.Suppress()
}
