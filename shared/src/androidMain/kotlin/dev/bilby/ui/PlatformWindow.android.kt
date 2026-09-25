package dev.bilby.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@OptIn(ExperimentalLayoutApi::class)
actual val WindowInsets.Companion.imeTarget: WindowInsets
    @Composable get() = imeAnimationTarget

@OptIn(ExperimentalLayoutApi::class)
actual val WindowInsets.Companion.imeVisible: Boolean
    @Composable get() = isImeVisible

@Composable
actual fun screenOrientationKey(): Int = LocalConfiguration.current.orientation
