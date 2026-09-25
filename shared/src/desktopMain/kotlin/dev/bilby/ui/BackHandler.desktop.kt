package dev.bilby.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    androidx.compose.ui.backhandler.BackHandler(enabled, onBack)
