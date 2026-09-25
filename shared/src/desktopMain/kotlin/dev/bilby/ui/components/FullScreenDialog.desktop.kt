package dev.bilby.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

internal actual fun fullScreenDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false)

@Composable
internal actual fun DialogIntoDisplayCutout() = Unit
