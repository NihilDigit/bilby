package dev.bilby.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun formatTimeOfDay(epochMillis: Long): String {
    val flags = DateUtils.FORMAT_SHOW_TIME or
        if (DateUtils.isToday(epochMillis)) 0 else DateUtils.FORMAT_SHOW_DATE
    return DateUtils.formatDateTime(LocalContext.current, epochMillis, flags)
}
