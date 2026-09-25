package dev.bilby.ui

import androidx.compose.runtime.Composable

/**
 * 一个时刻按系统习惯写成字:当天只写时刻,跨了天带上日期。Android 用 DateUtils,
 * 跟着系统的 12/24 小时制;桌面用 java.time 的本地化短格式。
 */
@Composable
expect fun formatTimeOfDay(epochMillis: Long): String
