package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * danmakus.com(ukamnads)的响应。**这一族不属于 B 站接口**,和 `BiliClient` 那套无关:
 * 无鉴权、无签名,事实全部记在 `notes/danmakus-com.md`。
 *
 * 只解本项目要用的那几个字段,其余一概不写 —— 那个站的响应很大(一场几万条弹幕的统计),
 * 声明得越多越要跟着它改。
 */
@Serializable
data class DanmakusEnvelopeDto<T>(
    val code: Int = 0,
    val message: String = "",
    val data: T? = null,
)

@Serializable
data class DanmakusChannelDataDto(
    val lives: List<DanmakusLiveDto> = emptyList(),
)

/**
 * 一场直播。**本场的判据是 `isFinish == false`**,不是 `stopDate == 0` —— 后者是这一场
 * 有没有收到过下播事件,录制端掉线时它同样是 0。
 *
 * **一场有多个录制版本,每个版本一个自己的 [liveId]。** 顶层这一个是本站官方那份,而它常常
 * 是空的(2026-08-24 实测:一场进行中的直播,官方版本 0 条,同场的贡献者版本 12476 条且在实时
 * 增长)。要拿数据必须从 [versions] 里挑,见 notes/danmakus-com.md。
 */
@Serializable
data class DanmakusLiveDto(
    @SerialName("liveId") val liveId: String = "",
    @SerialName("isFinish") val isFinish: Boolean = true,
    @SerialName("startDate") val startDate: Long = 0L,
    @SerialName("danmakusCount") val danmakusCount: Int = 0,
    val versions: List<DanmakusVersionDto> = emptyList(),
)

/**
 * 一份录制。[userId] 为 0、[isOfficial] 为真的那份是本站自己录的。
 *
 * @param rangeDanmakusCount 这一份收了多少条。**挑版本就按它**,不按 [isOfficial] ——
 *   官方那份的权威性不体现在数据多少上,进行中的场次里它往往一条都没有。
 */
@Serializable
data class DanmakusVersionDto(
    val live: DanmakusVersionLiveDto? = null,
    @SerialName("rangeDanmakusCount") val rangeDanmakusCount: Int = 0,
)

@Serializable
data class DanmakusVersionLiveDto(
    @SerialName("liveId") val liveId: String = "",
)

/** `live` 那条的外层是分页壳,真正的内容在 `data.data` 上(notes 第 1 节)。 */
@Serializable
data class DanmakusLivePageDto(
    val data: DanmakusLiveDetailDto? = null,
)

@Serializable
data class DanmakusLiveDetailDto(
    val danmakus: List<DanmakusItemDto> = emptyList(),
)

/**
 * 一条消息。**没有 id** —— 这个站的存储模型里没有消息 id 这个概念(2026-08-24 实测,
 * 见 notes/danmakus-com.md),所以与 B 站那侧合并时只能按「谁、什么时候、说了什么」去重。
 *
 * swagger 里还声明了 `ct`,真实响应里不下发,所以这里也不要。
 */
@Serializable
data class DanmakusItemDto(
    @SerialName("uId") val uid: Long = 0L,
    @SerialName("uName") val uname: String = "",
    /** 3 是醒目留言,取值表见 notes 第 2 节。 */
    val type: Int = 0,
    /** 毫秒。 */
    @SerialName("sendDate") val sendDate: Long = 0L,
    val message: String = "",
    /** 元。v3 那套是毫元,这里不是。 */
    val price: Double = 0.0,
)
