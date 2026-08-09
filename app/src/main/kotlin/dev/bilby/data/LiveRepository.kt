package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.LiveCodecDto
import dev.bilby.api.dto.LiveDanmakuInfoDto
import dev.bilby.api.dto.LiveGuardPageDto
import dev.bilby.api.dto.LiveRoomH5InfoDto
import dev.bilby.api.dto.LiveRoomPlayInfoDto
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.BiliLog

/** 一条选定的直播流。[url] 已经把 `host + base_url + extra` 三段拼好。 */
data class LiveStreamPick(
    val url: String,
    val protocolName: String,
    val formatName: String,
    val codecName: String,
    /** 服务端**实际**给的档位,不一定等于请求的那个:要不到就自己降。 */
    val qn: Int,
    /** 这个房间能选的档位。空的话界面不出清晰度入口。 */
    val acceptQn: List<Int>,
)

/** 房间当前状态。未开播时 [stream] 为 null,调用方据此显示"未开播"而不是报错。 */
data class LiveRoomPlayback(
    val roomId: Long,
    val uid: Long,
    val liveStatus: Int,
    val isPortrait: Boolean,
    val stream: LiveStreamPick?,
) {
    val isLive: Boolean get() = liveStatus == LIVE_STATUS_LIVE

    companion object {
        /** 1 才是真直播;2 是轮播录播,不当成直播。 */
        const val LIVE_STATUS_LIVE = 1
    }
}

data class LiveGuardPage(
    val items: List<dev.bilby.api.dto.LiveGuardItemDto>,
    val hasMore: Boolean,
)

class LiveRepository(private val client: BiliClient) {

    /**
     * 一次把 protocol/format/codec 三个维度全要回来再挑,不是每换一档发一次请求 —— 参数照
     * PiliPlus `live.dart:83` 逐个对齐,少一个字段签名内容就不同。
     */
    suspend fun loadPlayback(roomId: Long, qn: Int = DEFAULT_QN): BiliResult<LiveRoomPlayback> {
        val result: BiliResult<LiveRoomPlayInfoDto> = client.getData(
            "${BiliConstants.LIVE_HOST}/xlive/web-room/v2/index/getRoomPlayInfo",
            mapOf(
                "room_id" to roomId.toString(),
                "protocol" to "0,1",
                "format" to "0,1,2",
                "codec" to "0,1,2",
                "qn" to qn.toString(),
                "platform" to "web",
                "ptype" to "8",
                "dolby" to "5",
                "panorama" to "1",
                "web_location" to "444.8",
            ),
            signed = true,
            referer = BiliConstants.LIVE_REFERER,
        )
        return result.map { dto ->
            LiveRoomPlayback(
                roomId = if (dto.roomId != 0L) dto.roomId else roomId,
                uid = dto.uid,
                liveStatus = dto.liveStatus,
                isPortrait = dto.isPortrait,
                stream = dto.pickStream(),
            )
        }
    }

    suspend fun loadDanmakuInfo(roomId: Long): BiliResult<LiveDanmakuInfoDto> =
        client.getData(
            "${BiliConstants.LIVE_HOST}/xlive/web-room/v1/index/getDanmuInfo",
            mapOf("id" to roomId.toString(), "web_location" to "444.8"),
            signed = true,
            referer = BiliConstants.LIVE_REFERER,
        )

    suspend fun loadRoomInfo(roomId: Long): BiliResult<LiveRoomH5InfoDto> =
        client.getData(
            "${BiliConstants.LIVE_HOST}/xlive/web-room/v1/index/getH5InfoByRoom",
            mapOf("room_id" to roomId.toString()),
            referer = BiliConstants.LIVE_REFERER,
        )

    /**
     * 大航海。参数里的 `ruid` 是**主播 mid**,不是房间号 —— 传错不会报错,只会恒定返回空列表。
     * 这个接口不签名。
     */
    suspend fun loadGuardPage(ruid: Long, page: Int): BiliResult<LiveGuardPage> {
        val result: BiliResult<LiveGuardPageDto> = client.getData(
            "${BiliConstants.LIVE_HOST}/xlive/app-ucenter/v1/guard/MainGuardCardAll",
            mapOf(
                "ruid" to ruid.toString(),
                "page" to page.toString(),
                "page_size" to GUARD_PAGE_SIZE.toString(),
            ),
            referer = BiliConstants.LIVE_REFERER,
        )
        return result.map { LiveGuardPage(items = it.guardTopList, hasMore = it.hasMore == 1) }
    }

    companion object {
        /** 原画。取不到时服务端会自己降档,`current_qn` 会告诉我们实际给了哪一档。 */
        const val DEFAULT_QN = 10000

        const val GUARD_PAGE_SIZE = 20
    }
}

/**
 * 挑一条能播的流。优先级 **HLS-fmp4-avc → HLS-ts-avc → FLV-avc → 任意第一条**。
 *
 * 排在最前的是 Media3 原生支持的组合。FLV 排在后面而不是被排除:它是这三条里唯一在所有房间
 * 都存在的,而 fmp4 偶尔缺席(小主播、某些 CDN),那时宁可用 progressive 里的 FlvExtractor 播,
 * 也好过什么都不放。avc 优先于 hevc/av1 是同样的理由 —— 硬解覆盖面最广。
 */
private fun LiveRoomPlayInfoDto.pickStream(): LiveStreamPick? {
    val streams = playurlInfo?.playurl?.stream.orEmpty()
    if (streams.isEmpty()) return null

    val candidates = streams.flatMap { stream ->
        stream.format.flatMap { format ->
            format.codec.map { codec -> Triple(stream.protocolName, format.formatName, codec) }
        }
    }

    val ranked = candidates.minByOrNull { (protocol, format, codec) ->
        preferenceRank(protocol, format, codec.codecName)
    } ?: return null

    val (protocol, format, codec) = ranked
    val url = codec.buildUrl() ?: run {
        BiliLog.w("直播流 $protocol/$format/${codec.codecName} 没有可用的 url_info")
        return null
    }
    return LiveStreamPick(
        url = url,
        protocolName = protocol,
        formatName = format,
        codecName = codec.codecName,
        qn = codec.currentQn,
        acceptQn = codec.acceptQn,
    )
}

private fun preferenceRank(protocol: String, format: String, codec: String): Int {
    val protocolScore = if (protocol == "http_hls") 0 else 100
    val formatScore = when (format) {
        "fmp4" -> 0
        "ts" -> 10
        else -> 20
    }
    val codecScore = when (codec) {
        "avc" -> 0
        "hevc" -> 1
        else -> 2
    }
    return protocolScore + formatScore + codecScore
}

/** 播放地址是三段拼出来的,任何一段缺了都拼不成可用的 url。 */
private fun LiveCodecDto.buildUrl(): String? {
    val info = urlInfo.firstOrNull() ?: return null
    if (info.host.isEmpty() || baseUrl.isEmpty()) return null
    return info.host + baseUrl + info.extra
}
