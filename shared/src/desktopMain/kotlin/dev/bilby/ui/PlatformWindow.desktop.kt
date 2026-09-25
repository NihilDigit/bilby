package dev.bilby.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

actual val WindowInsets.Companion.imeTarget: WindowInsets
    @Composable get() = WindowInsets(0, 0, 0, 0)

actual val WindowInsets.Companion.imeVisible: Boolean
    @Composable get() = false

@Composable
actual fun screenOrientationKey(): Int = 0
