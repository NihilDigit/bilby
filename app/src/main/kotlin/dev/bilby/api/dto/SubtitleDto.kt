package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /x/player/wbi/v2` 的响应体(data 节点)。notes/playurl.md §8.2 已经记着这个接口带
 * 字幕(不带秒级续播位置,那个从 playurl 自己的 `last_play_time` 拿,见 [PlayUrlDto])。
 * 这里只取字幕需要的这一块,其余字段(`last_play_cid`、看点)这次用不上,不建模。
 */
@Serializable
data class PlayerV2Dto(
    val subtitle: SubtitleInfoDto? = null,
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
