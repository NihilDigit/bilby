package dev.bilby.ui

import androidx.compose.runtime.Composable

/**
 * 拦截返回。Android 上是 activity-compose 的同名函数,行为与迁移前一致;
 * 桌面上接 Compose Multiplatform 的返回事件(Esc 键)。
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
