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
 * @param cid 指名打开哪一 P,0 表示不指名。
 *
 *   **这个参数是上一轮离线方案的遗留,新方案不再需要它** —— 缓存内容改走独立的
 *   [OfflineVideo] 目的地(它一进来就不该有"取详情"这条路径)。等 `MainActivity` 那侧的
 *   `entry<Offline>` 改完之后,这个参数连同它的接线应该一起删掉:留着就是一个迟早会被
 *   重新接上的口子,而它会把离线内容又送回在线播放页。
 */
@Serializable
data class Video(
    val bvid: String,
    val listening: Boolean = false,
    val cid: Long = 0,
) : NavKey

/**
 * 设置页。它不是产品面的第六个界面,是必要的杂物间(DESIGN 2 节)——
 * 所以入口在动态页顶栏的图标,不占底部导航的一格:底部三格是"我要去哪",设置不是目的地。
 */
@Serializable
data object Settings : NavKey

@Serializable
data class Space(val mid: Long) : NavKey

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
/** 稍后再看的列表本身。第三屏改成入口页之后,它从 tab 里搬到这里。 */
@Serializable
data object ToViewList : NavKey

@Serializable
data class FavFolderContents(val mediaId: Long, val title: String) : NavKey

/**
 * 一个合集/系列的目录。按 1.1 的机制表对过:内容是用户自己点开的那一个合集,有限、不排序、
 * 不推荐、滚到底就没了,和 [FavFolderContents]、[LiveRoom] 落在同一栏。
 *
 * **它曾经是空间页里的一个状态**(`SpaceCollectionsTabState.detail`),不是独立目的地。那样
 * 有两处走不通:一是播放页的队列来源就是某个合集,而"进入这个合集"没有目的地可去;二是
 * 空间页在栈里出现第二次时(从播放页点 UP 头像),Nav3 按 NavKey 分组 ViewModel,同一个
 * `Space(mid)` 拿到的是同一个实例,于是那个还开着的目录被当成新页面画了出来。
 *
 * @param name 目录还没拉回来时顶栏就要有标题,和 [FavFolderContents] 带 title 同一个理由。
 * @param isSeason true=合集(season),false=系列(series)。两者的目录是两个接口
 *   (notes 1.4.2 节),取哪个由它决定,所以是身份的一部分。
 */
@Serializable
data class CollectionContents(
    val mid: Long,
    val id: Long,
    val isSeason: Boolean,
    val name: String,
) : NavKey

/** 历史记录。个人页的三个入口之一,DESIGN 2 节:历史只待在它自己这一页。 */
@Serializable
data object History : NavKey

/**
 * 已缓存到本地的视频。按 1.1 的机制表对过:内容全部来自用户自己按下的"缓存",是有限集合,
 * 不排序、不推荐、不会在滚动时长出新东西 —— 和 [ToViewList]、[FavFolderContents] 同一栏。
 */
@Serializable
data object Offline : NavKey

/**
 * 一个直播间。**入口只有 UP 的空间页** —— 进来是因为用户点了某个他自己选择去看的人,
 * 不是因为有人替他挑了一个。所以没有直播浏览页,也不会有"推荐直播"。
 *
 * 按 DESIGN 1.1 的机制表对过:内容来自用户已有的选择(他访问的这个 UP),是单个有限对象,
 * 不排序、不推荐,和 [Space]、[FavFolderContents] 落在同一栏。
 */
@Serializable
data class LiveRoom(val roomId: Long) : NavKey
