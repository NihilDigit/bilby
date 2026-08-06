package com.bilby.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Nav3 的 backstack 就是一个 SnapshotStateList<NavKey>,没有独立的图定义。
 * 界面共五个(DESIGN 2 节),这里随里程碑逐个加。
 */
@Serializable
data object Home : NavKey

@Serializable
data object Login : NavKey
