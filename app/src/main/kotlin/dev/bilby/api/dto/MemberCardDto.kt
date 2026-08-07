package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `x/web-interface/card` 的响应体。播放页拿它给 UP 主信息补等级和粉丝数——`x/web-interface/view`
 * 的 `owner` 只有 `mid`/`name`/`face`。
 *
 * `level_info` 真的下发,不是照公开文档假设:实测抓过一个真实 mid(`curl` 未登录直调,
 * 接口本身不需要 cookie),`data.card.level_info.current_level` 确实在,值是 `6`。PiliPlus
 * 的 `Card` 模型没解析这个字段(`models_new/member_card_info/card.dart` 只有
 * `mid/name/face/official/vip`),那是它自己选择不用,不代表接口没给。
 */
@Serializable
data class MemberCardResponseDto(
    val card: MemberCardDto = MemberCardDto(),
    /** 顶层字段,和 PiliPlus `MemberCardInfoData.follower` 是同一个;`card.fans` 也有同样的值,
     * 顶层这个是 PiliPlus 实际读的那个。 */
    val follower: Long = 0L,
    /** 同一份响应里顺带就有,`LevelBadge` 的 senior 参数要用,不必再猜一个默认值。 */
    @SerialName("is_senior_member") val isSeniorMember: Int = 0,
)

@Serializable
data class MemberCardDto(
    @SerialName("level_info") val levelInfo: MemberCardLevelInfoDto = MemberCardLevelInfoDto(),
)

@Serializable
data class MemberCardLevelInfoDto(@SerialName("current_level") val currentLevel: Int = 0)
