package dev.bilby.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * 铺满整个窗口的对话框。默认的 Dialog 会留出边距并按内容收缩,看图要的是整块屏幕;
 * Android 上还要铺到系统栏底下去,见 actual。
 */
internal expect fun fullScreenDialogProperties(): DialogProperties

/** 在对话框内容里调用:让这个对话框窗口铺进刘海所在的短边。只有 Android 有这回事。 */
@Composable
internal expect fun DialogIntoDisplayCutout()
