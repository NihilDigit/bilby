package dev.bilby.danmaku

import dev.bilby.BiliLog
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.pathOnly
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * **返回 [BiliResult] 而不是 `List<Danmaku>`**:空列表既可能是"这一段本来就没人发弹幕",
     * 也可能是"这一次没拉到",两种情况在 `List` 上完全同形。调用方要靠这个区别决定段号还能不能
     * 重拉——曾经因为分不出来,一次失败会让那一段在整条视频的剩余时间里永远缺弹幕。
     *
     * 三个分支的含义:[BiliResult.Ok] 拿到了这一段的全部弹幕(可能为空);[BiliResult.ApiError]
     * 服务端明确拒绝(HTTP 非 2xx,共享 HttpClient 设了 `expectSuccess = false`,错误响应会照常
     * 走到这里,body 是一段不成形的 protobuf);[BiliResult.Failure] 网络异常或响应体解析不出来。
     *
     * 仍然不向上抛:弹幕不是播放的必需路径,拉不到不能中断播放,失败照旧记一行日志。
     *
     * protobuf 解析与模式映射都在 `Dispatchers.Default` 上完成,返回的已经是可以直接发布的
     * 不可变结果——热门视频一段几千条,这些遍历放在主线程上就是一次可见的掉帧。
     *
     * 返回的 [DanmakuSegment] 把普通弹幕和高级弹幕(mode 7)分成两份:它们走两条互不相干的
     * 渲染路径,见 [DanmakuSegment]。
     */
    suspend fun getSegment(cid: Long, segmentIndex: Int): BiliResult<DanmakuSegment> =
        runCatching { loadSegment(cid, segmentIndex) }
            .fold(
                onSuccess = { result ->
                    if (result is BiliResult.ApiError) {
                        BiliLog.w(
                            "拉弹幕分段被拒 url=${SEG_URL.pathOnly()} cid=$cid segment=$segmentIndex " +
                                "code=${result.code} message=${result.message}",
                        )
                    }
                    result
                },
                onFailure = {
                    BiliLog.w("拉弹幕分段失败 url=${SEG_URL.pathOnly()} cid=$cid segment=$segmentIndex", it)
                    BiliResult.Failure(it)
                },
            )

    private suspend fun loadSegment(cid: Long, segmentIndex: Int): BiliResult<DanmakuSegment> {
        val response: HttpResponse = client.rawGet(
            SEG_URL,
            params = mapOf(
                "type" to "1",
                "oid" to cid.toString(),
                "segment_index" to segmentIndex.toString(),
            ),
        )
        if (!response.status.isSuccess()) {
            return BiliResult.ApiError(response.status.value, response.status.description)
        }
        val bytes = response.body<ByteArray>()
        // 解析和映射一起进后台:分开的话主线程还是要遍历一遍解析结果。
        return withContext(Dispatchers.Default) {
            BiliResult.Ok(parseDmSegMobileReply(bytes).toMappedSegment())
        }
    }

    /** 播放进度(毫秒)落在第几个 0-based 分段,§1.3:6 分钟一段。 */
    fun segmentIndexFor(progressMillis: Long): Int = (progressMillis / SEGMENT_LENGTH_MILLIS).toInt()

    /**
     * 当前进度距离下一个分段边界还有多少毫秒。预取用:分段是懒加载的,走到 6 分钟整点那一刻
     * 才发请求的话,请求往返这段时间里屏幕上一条弹幕都没有,而这正好是每 6 分钟必然出现一次的
     * 固定破绽。提前一个进度回调的量把下一段拉回来就没有这个空窗。
     */
    fun millisUntilNextSegment(progressMillis: Long): Long =
        SEGMENT_LENGTH_MILLIS - progressMillis.mod(SEGMENT_LENGTH_MILLIS)

    private companion object {
        const val SEG_URL = "${BiliConstants.WEB_HOST}/x/v2/dm/web/seg.so"
        const val SEGMENT_LENGTH_MILLIS = 60L * 6 * 1000
    }
}
