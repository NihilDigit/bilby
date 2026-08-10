package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /x/player/wbi/v2` 的响应体(data 节点)。
 *
 * 两个字段分属两件事,notes/playurl.md §8.2 把这条分工写清楚了:
 * - **秒级续播位置不在这里**,它跟 playurl 一起返回(`PlayUrlDto.lastPlayTime`);
 * - **"上次播到哪一 P"只在这里**。`PlayUrlDto` 里那个同名字段是照 PiliPlus 的模型建的,
 *   但真实响应并不填它 —— 从那儿读永远是 0,多 P 续播因此一直没生效过。PiliPlus 自己
 *   也是从这个接口读的(`pages/video/controller.dart` 里 `playInfo` 的结果)。
 *
 * 看点(`view_points`)和互动信息暂时用不上,不建模。
 */
@Serializable
data class PlayerV2Dto(
    val subtitle: SubtitleInfoDto? = null,
    /** 上次播放到哪一 P 的 cid。0 表示没有记录。 */
    @SerialName("last_play_cid") val lastPlayCid: Long = 0,
)

@Serializable
data class SubtitleInfoDto(
    val subtitles: List<SubtitleTrackDto> = emptyList(),
)

@Serializable
data class SubtitleTrackDto(
    /** 语言代码,如 `ai-zh`、`zh-CN`。 */
    val lan: String = "",
    @SerialName("lan_doc") val lanDoc: String = "",
    /** 1 = AI 生成。 */
    val type: Int = 0,
    /** 协议相对地址(`//aisubtitle.hdslb.com/...`),取正文前要自己补 `https:`。 */
    @SerialName("subtitle_url") val subtitleUrl: String = "",
)

/**
 * 字幕正文的响应。**不是 B 站标准信封**——没有 `code`/`data` 外层,直接就是这个形状,
 * 所以取正文时不能走 `BiliClient.getData`(它会去剥一层不存在的 `data`)。
 */
@Serializable
data class SubtitleBodyDto(
    val body: List<SubtitleLineDto> = emptyList(),
)

@Serializable
data class SubtitleLineDto(
    /** 浮点秒。 */
    val from: Double = 0.0,
    val to: Double = 0.0,
    val content: String = "",
)
