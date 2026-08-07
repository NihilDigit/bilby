package dev.bilby.danmaku

import dev.bilby.BiliLog
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.pathOnly
import dev.danmaku.compose.Danmaku
import io.ktor.client.call.body

/**
 * B 站弹幕数据源。接口是 `x/v2/dm/web/seg.so`(notes/danmaku.md §1.1 实测过):**不签名、
 * 不要登录态、GET、没有 gRPC 帧头**——响应体就是裸 protobuf(`DmSegMobileReply`),不像
 * PiliPlus 走的 app 端 gRPC-over-HTTP 路径那样还要手工拼 5 字节帧头。走 [BiliClient.rawGet]
 * 而不是绕过它,这是 CLAUDE.md 的硬约定。
 *
 * 6 分钟一段,`segment_index` 从 1 开始(§1.3)。
 */
class DanmakuRepository(private val client: BiliClient) {

    /**
     * 拉一个分段。`segmentIndex` 是**1-based**(服务端约定,不是 Kotlin 习惯的 0-based,
     * 调用方自己按 [segmentIndexFor] 算出 0-based 段号后 +1)。
     *
     * 失败(网络异常、响应体不是预期的 protobuf 结构)时记一行日志、返回空列表,不向上抛——
     * 弹幕不是播放的必需路径,拉不到就当这一段没有弹幕,不能因为这个中断播放。
     */
    suspend fun getSegment(cid: Long, segmentIndex: Int): List<Danmaku> =
        runCatching {
            client.rawGet(
                SEG_URL,
                params = mapOf(
                    "type" to "1",
                    "oid" to cid.toString(),
                    "segment_index" to segmentIndex.toString(),
                ),
            ).body<ByteArray>()
        }.mapCatching(::parseDmSegMobileReply)
            .fold(
                onSuccess = { elems -> elems.mapNotNull { it.toDanmakuOrNull() } },
                onFailure = {
                    BiliLog.w("拉弹幕分段失败 url=${SEG_URL.pathOnly()} cid=$cid segment=$segmentIndex", it)
                    emptyList()
                },
            )

    /** 播放进度(毫秒)落在第几个 0-based 分段,§1.3:6 分钟一段。 */
    fun segmentIndexFor(progressMillis: Long): Int = (progressMillis / SEGMENT_LENGTH_MILLIS).toInt()

    private companion object {
        const val SEG_URL = "${BiliConstants.WEB_HOST}/x/v2/dm/web/seg.so"
        const val SEGMENT_LENGTH_MILLIS = 60L * 6 * 1000
    }
}
