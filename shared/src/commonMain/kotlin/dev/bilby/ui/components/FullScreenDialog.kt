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

/**
 * 承载底部输入浮层的全屏对话框窗口配置:在独立窗口内响应软键盘尺寸动画(不触发底层页面重排),
 * 并关掉系统默认的硬切遮罩改由 Compose 自绘过渡。
 */
@Composable
internal expect fun ConfigureInputDialogWindow()
