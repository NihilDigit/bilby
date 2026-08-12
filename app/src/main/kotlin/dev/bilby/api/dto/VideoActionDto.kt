package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET x/web-interface/archive/relation` 的响应体(data 节点)。这个接口不在
 * notes/playurl.md 或 notes/comment-toview-history.md 覆盖范围内,字段依据
 * bilibili-API-collect 的 video/action.md(公开文档,非 PiliPlus 源码读证)。
 * UNSURE: 未经真机抓包核实,响应里另有 dislike/attention/follow 等已废弃字段,
 * 这里只取用得到的三个,其余靠 ignoreUnknownKeys 吃掉。
 */
@Serializable
data class ArchiveRelationDto(
    val like: Boolean = false,
    /** 已投币数量,0/1/2。 */
    val coin: Int = 0,
    val favorite: Boolean = false,
)

/**
 * `GET x/v3/fav/folder/created/list-all` 的响应体(data 节点)。同样不在两份笔记范围内,
 * 字段依据 bilibili-API-collect 的 fav/list.md。
 */
@Serializable
data class FavFolderListDto(val count: Int = 0, val list: List<FavFolderDto> = emptyList())

/** `GET x/v3/fav/folder/info` 返回的也是这个形状,收藏夹管理页复用它,见 notes/fav.md。 */
@Serializable
data class FavFolderDto(
    val id: Long = 0L,
    val title: String = "",
    /** list-all 不保证带简介,folder/info 才一定有。取值的差别见 notes/fav.md。 */
    val intro: String = "",
    /** 位域:默认夹与公开性都在里面。判据在 `data/FavFolderDetail`,不要在调用点裸写位运算。 */
    val attr: Int = 0,
    /**
     * 只有请求带了 rid 时服务端才会填这个字段(1=已收藏在此夹,0=未收藏);
     * 不带 rid 时该字段缺省为 0,不能当作"未收藏"的证据——getDefaultFavFolderId
     * 就是不带 rid 调用的,不读这个字段。
     */
    @SerialName("fav_state") val favState: Int = 0,
    @SerialName("media_count") val mediaCount: Int = 0,
)
