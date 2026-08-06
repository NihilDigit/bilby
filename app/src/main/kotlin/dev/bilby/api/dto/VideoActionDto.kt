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

@Serializable
data class FavFolderDto(
    val id: Long = 0L,
    val title: String = "",
    /**
     * 只有请求带了 rid 时服务端才会填这个字段(1=已收藏在此夹,0=未收藏);
     * 不带 rid 时该字段缺省为 0,不能当作"未收藏"的证据——getDefaultFavFolderId
     * 就是不带 rid 调用的,不读这个字段。
     */
    @SerialName("fav_state") val favState: Int = 0,
    @SerialName("media_count") val mediaCount: Int = 0,
)

/**
 * 点赞/投币/收藏这几个写接口成功时 `data` 通常是 null 或者只有零散提示字段(比如收藏
 * 接口的 `prompt`),不满足 BiliClient.postForm "data 非空才算 Ok" 的判定,所以跟
 * ToViewRepository 一样绕开它,只看外层信封的 code/message。
 */
@Serializable
data class ActionEnvelopeDto(
    val code: Int = 0,
    val message: String = "",
)
