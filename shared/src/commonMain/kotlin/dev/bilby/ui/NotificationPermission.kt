package dev.bilby.ui

import androidx.compose.runtime.Composable

/**
 * 需要时向系统要通知权限,返回发起请求的那个动作。给不给都不影响调用方要做的事(下载照常),
 * 只影响进度通知出不出得来。桌面没有这道授权,动作为空。
 */
@Composable
expect fun rememberNotificationPermissionRequest(): () -> Unit
