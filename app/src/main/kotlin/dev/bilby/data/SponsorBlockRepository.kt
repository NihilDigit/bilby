package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.dto.SponsorBlockSegmentDto
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** 一段广告/赞助/片头片尾之类的可跳过区间,时间统一转成毫秒(服务端给的是秒)。 */
data class SponsorSegment(
    val startMillis: Long,
    val endMillis: Long,
    val category: String,
    val uuid: String,
)

/**
 * BSponsorBlock 片段查询。接口形状照抄 PiliPlus 的现成实现——
 * `GET {server}/api/skipSegments?videoID=<bvid>&cid=<cid>`,server 默认
 * https://www.bsbsb.top(PiliPlus/lib/http/sponsor_block.dart:56-75、
 * PiliPlus/lib/http/constants.dart:14)。
 *
 * 不走 BiliClient:这是发给第三方社区服务端的请求,BiliClient 会自动带上 B 站的
 * Cookie/Referer/WBI 签名,那一套东西第三方服务端既不认也不需要——带过去纯粹是把
 * 登录态泄露给一个不受 B 站控制的第三方,没有任何好处。所以直接用调用方传入的
 * 裸 HttpClient,不签名、不带 Cookie。
 *
 * 手动 `bodyAsText` + `json.decodeFromString` 而不是让共享 HttpClient 上装的
 * ContentNegotiation 自动解码:第三方服务端的 content-type 是否严格标 application/json
 * 不受我们控制,ContentNegotiation 认不出 content-type 时会直接抛异常;手动解析
 * 把"能不能解析"完全收在这个类自己的 try 里,和 ContentNegotiation 的行为解耦。
 *
 * 网络失败、HTTP 非 2xx(包括视频没有任何人提交过片段时的 404,这是正常情况不是错误)、
 * JSON 解析失败——一律返回空列表,绝不抛异常:这只是锦上添花的自动跳过功能,
 * 它整个挂掉也不能连累播放本身。
 */
class SponsorBlockRepository(
    private val httpClient: HttpClient,
    private val json: Json,
) {
    /**
     * @param serverUrl 服务器根地址。可配是因为这是社区跑的第三方服务:换域名或长期挂掉时
     *   我们发不出版本,用户得能自己改(设置页里那一项)。PiliPlus 同样把它做成可配项。
     */
    suspend fun segments(
        bvid: String,
        cid: Long,
        serverUrl: String = SettingsStore.DEFAULT_SB_SERVER,
    ): List<SponsorSegment> = runCatching {
        val response = httpClient.get("${serverUrl.trimEnd('/')}$SKIP_SEGMENTS_PATH") {
            parameter("videoID", bvid)
            parameter("cid", cid)
        }
        if (!response.status.isSuccess()) return@runCatching emptyList()

        computeSponsorSegments(json.decodeFromString(response.bodyAsText()))
    }.getOrElse {
        // 网络失败或 JSON 解析失败才会走到这里(404 已经在上面提前 return 掉,不算异常)。
        BiliLog.w("SponsorBlock 片段查询异常", it)
        emptyList()
    }

    private companion object {
        const val SKIP_SEGMENTS_PATH = "/api/skipSegments"
    }
}

/**
 * 把服务端那一串原始片段折成可以直接喂给 [dev.bilby.ui.video.nextSkipTarget] 的形态:
 * **按起点升序、互不重叠**。
 *
 * 拆成文件级纯函数是为了能单独测(同 `computeHistoryPage`)。它建立的正是 `nextSkipTarget`
 * 明写着要求的那个前提 —— 而前提破了不会报错:那个函数遇到乱序会提前返回 null,表现是
 * "片段就是不跳",没有任何地方能看出是这里出的问题。
 */
internal fun computeSponsorSegments(dtos: List<SponsorBlockSegmentDto>): List<SponsorSegment> {
    val segments = dtos
        // poi_highlight(空降点)、exclusive_access(整段视频打标)不是"跳过一段时间"
        // 的语义,只有 actionType 缺省或显式为 skip 的条目才当成可跳过区间处理。
        .filter { (it.actionType ?: "skip") == "skip" }
        .mapNotNull { it.toSegmentOrNull() }
    return mergeOverlaps(segments)
}

private fun SponsorBlockSegmentDto.toSegmentOrNull(): SponsorSegment? {
    if (segment.size != 2) return null
    val startMillis = (segment[0] * 1000).toLong()
    val endMillis = (segment[1] * 1000).toLong()
    // **零长与倒置一律丢掉。** 留着的话 nextSkipTarget 会返回一个等于当前位置的目标,
    // 播放器 seek 到原地、位置监听再次命中同一段,就是一个跳不出去的循环。
    if (endMillis <= startMillis) return null
    return SponsorSegment(startMillis, endMillis, category, uuid)
}

/**
 * 按起点排序后合并重叠或首尾相接的片段,类别/uuid 取排在前面的那个——
 * 重叠片段本就是罕见的边界情况,合并后提示文案偶尔对不上具体类别,
 * 但不影响跳过这个核心功能。
 */
private fun mergeOverlaps(segments: List<SponsorSegment>): List<SponsorSegment> {
    val merged = mutableListOf<SponsorSegment>()
    for (segment in segments.sortedBy { it.startMillis }) {
        val last = merged.lastOrNull()
        if (last != null && segment.startMillis <= last.endMillis) {
            merged[merged.lastIndex] = last.copy(endMillis = maxOf(last.endMillis, segment.endMillis))
        } else {
            merged.add(segment)
        }
    }
    return merged
}
