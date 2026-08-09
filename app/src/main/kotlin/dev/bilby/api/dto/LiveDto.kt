package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 直播相关接口的响应体。字段以 PiliPlus 为准:`lib/http/live.dart`、
 * `lib/models_new/live/`、`lib/http/member.dart:835`(大航海)。
 */

/**
 * `xlive/web-room/v2/index/getRoomPlayInfo`,需要 WBI。
 *
 * 一次请求把 protocol/format/codec 三个维度全要回来(`0,1` / `0,1,2` / `0,1,2`),挑哪一组是
 * 客户端的事 —— 见 [LiveStreamPick]。分开请求等于每换一档就多一次风控面。
 */
@Serializable
data class LiveRoomPlayInfoDto(
    @SerialName("room_id") val roomId: Long = 0L,
    val uid: Long = 0L,
    /** 0 未开播、1 直播中、2 轮播。只有 1 能拿到可播的流。 */
    @SerialName("live_status") val liveStatus: Int = 0,
    /** 开播时刻,秒。未开播时是 -62170012800 之类的哨兵值,别拿它算时长。 */
    @SerialName("live_time") val liveTime: Long = 0L,
    @SerialName("is_portrait") val isPortrait: Boolean = false,
    @SerialName("playurl_info") val playurlInfo: LivePlayurlInfoDto? = null,
)

@Serializable
data class LivePlayurlInfoDto(
    val playurl: LivePlayurlDto? = null,
)

@Serializable
data class LivePlayurlDto(
    val stream: List<LiveStreamDto> = emptyList(),
)

@Serializable
data class LiveStreamDto(
    /** `http_stream`(FLV)或 `http_hls`。 */
    @SerialName("protocol_name") val protocolName: String = "",
    val format: List<LiveFormatDto> = emptyList(),
)

@Serializable
data class LiveFormatDto(
    /** `flv` / `ts` / `fmp4`。 */
    @SerialName("format_name") val formatName: String = "",
    val codec: List<LiveCodecDto> = emptyList(),
)

@Serializable
data class LiveCodecDto(
    /** `avc` / `hevc` / `av1`。 */
    @SerialName("codec_name") val codecName: String = "",
    @SerialName("current_qn") val currentQn: Int = 0,
    @SerialName("accept_qn") val acceptQn: List<Int> = emptyList(),
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("url_info") val urlInfo: List<LiveUrlInfoDto> = emptyList(),
)

/** 播放地址是三段拼出来的:`host + base_url + extra`,不是某一个字段。 */
@Serializable
data class LiveUrlInfoDto(
    val host: String = "",
    val extra: String = "",
)

/** `xlive/web-room/v1/index/getDanmuInfo`,需要 WBI。token 有时效,断线重连要重新取。 */
@Serializable
data class LiveDanmakuInfoDto(
    val token: String = "",
    @SerialName("host_list") val hostList: List<LiveDanmakuHostDto> = emptyList(),
)

@Serializable
data class LiveDanmakuHostDto(
    val host: String = "",
    @SerialName("wss_port") val wssPort: Int = 443,
)

/** `xlive/web-room/v1/index/getH5InfoByRoom`,不签名。只取展示要用的几项。 */
@Serializable
data class LiveRoomH5InfoDto(
    @SerialName("room_info") val roomInfo: LiveRoomInfoDto? = null,
    @SerialName("anchor_info") val anchorInfo: LiveAnchorInfoDto? = null,
)

@Serializable
data class LiveRoomInfoDto(
    @SerialName("room_id") val roomId: Long = 0L,
    val uid: Long = 0L,
    val title: String = "",
    val cover: String = "",
    @SerialName("live_status") val liveStatus: Int = 0,
    @SerialName("online") val online: Long = 0L,
)

@Serializable
data class LiveAnchorInfoDto(
    @SerialName("base_info") val baseInfo: LiveAnchorBaseInfoDto? = null,
)

@Serializable
data class LiveAnchorBaseInfoDto(
    val uname: String = "",
    val face: String = "",
)

/**
 * `xlive/app-ucenter/v1/guard/MainGuardCardAll`,**不签名、不需要 WBI**,参数是
 * `ruid`(主播 mid,不是房间号)+ `page` + `page_size`。
 */
@Serializable
data class LiveGuardPageDto(
    @SerialName("guard_top_list") val guardTopList: List<LiveGuardItemDto> = emptyList(),
    /** 1 表示还有下一页。服务端给的是 int 不是 bool。 */
    @SerialName("has_more") val hasMore: Int = 0,
)

@Serializable
data class LiveGuardItemDto(
    val uid: Long = 0L,
    val username: String = "",
    val face: String = "",
    /** 1 总督、2 提督、3 舰长。 */
    @SerialName("guard_level") val guardLevel: Int = 3,
)
