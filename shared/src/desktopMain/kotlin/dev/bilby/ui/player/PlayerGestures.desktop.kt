package dev.bilby.ui.player

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberWindowBrightness(): LevelControl? = null

/** Windows 的系统音量要走 Core Audio,尚未接入。 */
@Composable
internal actual fun rememberMediaVolume(): LevelControl? = null
