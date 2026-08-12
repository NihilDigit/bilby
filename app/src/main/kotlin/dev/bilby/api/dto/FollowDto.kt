package dev.bilby.api.dto

import kotlinx.serialization.Serializable

/**
 * `GET x/polymer/web-dynamic/v1/portal` 的 data 节点 —— 动态页顶部那排"最常访问"的 UP。
 * 字段依据 PiliPlus 的 `lib/models/dynamics/up.dart`(`FollowUpModel`)。
 *
 * `live_users` 解析,用来在头像上标"正在直播"并给出房间号 —— 直播现在是这个 app 的一部分。
 * 每个 item 上的 `has_update`(更新红点)**不解析**:它是 DESIGN 1.3 点名不实现的东西,
 * 解析出来搁在模型里,下一个人就会顺手把它渲染上去。
 */
@Serializable
data class PortalDto(
    val up_list: PortalUpListDto? = null,
    val live_users: PortalLiveUsersDto? = null,
)

@Serializable
data class PortalUpListDto(val items: List<PortalUpDto> = emptyList())

@Serializable
data class PortalUpDto(
    val mid: Long = 0L,
    val uname: String = "",
    val face: String = "",
)

/**
 * [count] 是服务端给的正在直播人数。**它可能大于 [items] 的长度** —— items 是这一屏要显示的
 * 那几个,而 count 说的是一共有几个人在播。首页那一格的数字用 count,不用 items.size。
 */
@Serializable
data class PortalLiveUsersDto(
    val count: Int = 0,
    val items: List<PortalLiveUserDto> = emptyList(),
)

@Serializable
data class PortalLiveUserDto(
    val mid: Long = 0L,
    val uname: String = "",
    val face: String = "",
    val room_id: Long = 0L,
    /** 直播间标题。**这是这条目里唯一说"现在在播什么"的字段**,列表靠它才不是一串人名。 */
    val title: String = "",
)

/**
 * `GET x/relation/followings` 的 data 节点。字段依据 PiliPlus 的 `lib/http/dynamics.dart`
 * (`followings`)与 bilibili-API-collect 的 user/relation.md。
 */
@Serializable
data class FollowingsDto(val total: Int = 0, val list: List<FollowingDto> = emptyList())

@Serializable
data class FollowingDto(
    val mid: Long = 0L,
    val uname: String = "",
    val face: String = "",
    val sign: String = "",
    /** 1 = 在特别关注分组里。三条列表接口都带这个字段,分组接口也一样。 */
    val special: Int = 0,
)

/**
 * `GET x/relation/tags` 的一项:用户自己建的关注分组。
 *
 * **`tagid` 有两个固定值**:-10 是「特别关注」,0 是「默认分组」(没被分进任何分组的人)。
 * 2026-08-11 实测这两条恒定存在,自建分组排在它们后面。[count] 是这个分组里有几个人。
 */
@Serializable
data class FollowTagDto(
    val tagid: Long = 0L,
    val name: String = "",
    val count: Int = 0,
)

/**
 * `GET x/relation/blacks` 的 data 节点。
 *
 * **[total] 是翻页终止的依据**(notes/relation-groups.md 2.2):这条接口按"已取条数 ≥ total"
 * 判到底,不按"这一页是空的"判 —— 与挨着它的分组成员接口正好相反。
 */
@Serializable
data class BlackListDto(val total: Int = 0, val list: List<BlackUserDto> = emptyList())

@Serializable
data class BlackUserDto(
    val mid: Long = 0L,
    val uname: String = "",
    val face: String = "",
    /** 拉黑时间,秒。列表里靠它才排得出"什么时候拉黑的"。 */
    val mtime: Long = 0L,
)
