package dev.bilby.ui

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) =
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = enabled,
        onBackCompleted = onBack,
    )
