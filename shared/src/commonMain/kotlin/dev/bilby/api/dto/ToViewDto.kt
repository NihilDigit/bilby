package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 稍后再看 `x/v2/history/toview/web` 的响应体,依据 notes/comment-toview-history.md
 * 第 2.1、2.3 节。
 */
@Serializable
data class ToViewResponseDto(
    val count: Int = 0,
    val list: List<ToViewItemDto> = emptyList(),
)

@Serializable
data class ToViewItemDto(
    val aid: Long = 0L,
    val bvid: String = "",
    val pic: String = "",
    val title: String = "",
    /** 数值秒,不是 "mm:ss" 字符串。 */
    val duration: Long = 0L,
    val pubdate: Long = 0L,
    /** 观看进度,单位秒;-1 表示已看完(与历史记录同规则,notes 2.3 节标了 UNSURE,按同语义处理)。 */
    val progress: Long = -1L,
    val owner: ToViewOwnerDto = ToViewOwnerDto(),
    val stat: ToViewStatDto = ToViewStatDto(),
    /** 番剧、影视。本应用不解析这一类(UGC-only),见 notes 2.3 节。 */
    @SerialName("is_pgc") val isPgc: Boolean = false,
    /** 剧集的类别名(「番剧」「电影」),非剧集为空串。 */
    @SerialName("pgc_label") val pgcLabel: String = "",
    /** 付费课程。同上。 */
    @SerialName("is_pugv") val isPugv: Boolean = false,
    /** 非空且带 level 即充电专属(notes/comment-toview-history.md 2.3 节)。 */
    @SerialName("charging_pay") val chargingPay: ChargingPayDto? = null,
)

/**
 * 只看 level 在不在,不读它的值:PiliPlus 的判据就是 `level != null`,值的类型与含义
 * 未经核实,所以按 [JsonElement] 收,免得类型猜错让整页解析失败。
 */
@Serializable
data class ChargingPayDto(val level: JsonElement? = null)

@Serializable
data class ToViewOwnerDto(val mid: Long = 0L, val name: String = "")

@Serializable
data class ToViewStatDto(val view: Long = 0L, val danmaku: Long = 0L)
