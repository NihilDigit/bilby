package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /x/player/wbi/v2` 的响应体(data 节点)。
 *
 * **续播的 cid 和秒数这里都有,而且是整条视频那一对,与你问的 cid 无关。** 这一节此前写的是
 * "秒级续播位置不在这里""playurl 的 last_play_cid 真实响应并不填",两句都错;notes/playurl.md
 * §8.2 已一并更正。判据来自实测(2026-08-11,登录态,BV1Xx41117tr 这条 40 P 的公开课):
 *
 * - 先给 P1 报 100 秒、再给 P2 报 200 秒,之后 **playurl 问 P1 回 0/0、问 P2 回 200 秒** ——
 *   服务端全站每条视频只存一对 `(cid, 秒数)`,后写的把先写的抹掉,**分 P 各自的进度它不存**。
 * - 同一时刻 **v2 问 P1,回的是 P2 的 cid 和 200 秒**。
 *
 * 于是两个接口的语义正好互补,用哪个取决于要问什么:
 * - **playurl**:"我问的这一 P 有进度吗" —— 对不上就给 0,给出的秒数一定属于你问的那一 P。
 * - **v2**:"这条视频当前记在哪一 P 的第几秒" —— 拿它判断"该不该换 P"以及"服务端动没动过"。
 *   它不返回流地址,只为读这一对而调它比调 playurl 便宜得多。
 *
 * 看点(`view_points`)和互动信息暂时用不上,不建模。
 */
@Serializable
data class PlayerV2Dto(
    val subtitle: SubtitleInfoDto? = null,
    /** 上次播放到哪一 P 的 cid。0 表示这条视频没有记录。 */
    @SerialName("last_play_cid") val lastPlayCid: Long = 0,
    /**
     * 上次播到第几毫秒,属于 [lastPlayCid] 那一 P。0 表示没有记录。
     *
     * **不要不看 [lastPlayCid] 就用它** —— 它是整条视频那一对里的秒数,问 P1 也会回 P7 的值。
     */
    @SerialName("last_play_time") val lastPlayTimeMillis: Long = 0,
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
