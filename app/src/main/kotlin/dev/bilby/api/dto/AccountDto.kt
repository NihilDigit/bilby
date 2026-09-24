package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `x/web-interface/nav` 的响应体,只取个人页头部要的那几个字段。字段名照 PiliPlus 的
 * `UserInfoData`(`lib/models/user/info.dart:86-113`):`uname`/`face`/`level_info.current_level`/
 * `is_senior_member`。这个接口本身不需要 WBI —— [BiliClient.fetchWbiKeys] 已经在裸调它取
 * `wbi_img`,同一个接口顺手把身份信息也取了,不必再起一次请求。
 */
@Serializable
data class NavInfoDto(
    val mid: Long = 0L,
    val uname: String = "",
    val face: String = "",
    @SerialName("level_info") val levelInfo: NavLevelInfoDto = NavLevelInfoDto(),
    /** 硬核会员,`LevelBadge` 的 senior 参数要用。1 表示是。 */
    @SerialName("is_senior_member") val isSeniorMember: Int = 0,
    /** 硬币余额。**是小数**(投币后可能剩 42.5),PiliPlus 同样按 double 读。 */
    val money: Double = 0.0,
)

@Serializable
data class NavLevelInfoDto(@SerialName("current_level") val currentLevel: Int = 0)

/**
 * `x/web-interface/nav/stat`:当前账号自己的关注数、粉丝数、动态数(PiliPlus `userStatOwner`,
 * `models/user/stat.dart`)。个人页只用关注数,粉丝数不取 —— 那是一个随别人的行为变化、
 * 会引人回来看的数(风格指南 §4.2)。
 */
@Serializable
data class NavStatDto(val following: Int = 0)
