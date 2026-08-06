package com.bilby.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Nav3 的 backstack 就是一个 SnapshotStateList<NavKey>,没有独立的图定义。
 * 共五个界面加登录(DESIGN 2 节),没有第六个 —— 新增之前先回 1.1 的机制表检验。
 */
@Serializable
data object Home : NavKey

@Serializable
data object Login : NavKey

@Serializable
data object Search : NavKey

@Serializable
data object ToView : NavKey

@Serializable
data class Video(val bvid: String) : NavKey

@Serializable
data class Space(val mid: Long) : NavKey

/**
 * 助理的两个入口都必须携带完整意图,不能只传一个 id 让下游去补 ——
 * 上下文只含本次意图(DESIGN 3.3 第 4 条),这里就是那条约束的边界。
 */
@Serializable
data class AgentSearch(val query: String) : NavKey

@Serializable
data class AgentRelated(val bvid: String, val title: String, val upName: String) : NavKey
