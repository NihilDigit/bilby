package dev.bilby.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Nav3 的 backstack 就是一个 SnapshotStateList<NavKey>,没有独立的图定义。
 *
 * DESIGN 2 节写的是"五个界面加登录"。[Followings] 与 [FavFolderContents] 是之后加的两个,加之前
 * 按 1.1 的机制表逐条对过:两者的内容都来自用户自己的选择(关注了谁、收藏了什么),都是有限
 * 集合、都不排序不推荐,不落在四个机制的任何一条上。**新增第三个之前照样先回去对一遍。**
 */
@Serializable
data object Home : NavKey

@Serializable
data object Search : NavKey

@Serializable
data object ToView : NavKey

/**
 * @param listening 直接以听视频的状态打开。**听视频仍然只有播放页一个宿主** ——
 *   空间页的「听这位 UP 的投稿」靠这个参数进来,而不是自己再承载一份听视频界面。
 *   它是目的地身份的一部分(以什么状态打开这个视频),不是可变标志位。
 */
@Serializable
data class Video(val bvid: String, val listening: Boolean = false) : NavKey

/**
 * 设置页。它不是产品面的第六个界面,是必要的杂物间(DESIGN 2 节)——
 * 所以入口在动态页顶栏的图标,不占底部导航的一格:底部三格是"我要去哪",设置不是目的地。
 */
@Serializable
data object Settings : NavKey

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

/**
 * 关注列表。入口在动态页顶上那排"最常访问"的右侧 —— 那排只放得下几个人,
 * 而"我到底关注了谁"是个正当问题,不该只能去官方端看。
 */
@Serializable
data object Followings : NavKey

/**
 * 一个收藏夹的内容。收藏夹与稍后再看在产品上是同一类东西:用户自己挑好的有限存货
 * (DESIGN 1.2 否决点心盒时给的正是这个理由),所以两者并排在第三屏。
 */
@Serializable
data class FavFolderContents(val mediaId: Long, val title: String) : NavKey
