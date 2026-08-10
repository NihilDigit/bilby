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
 * 本地已缓存的弹幕分段。离线缓存把服务端下发的原始 protobuf 原样存了盘,这里把它读回来。
 *
 * 做成一个接口而不是让仓库直接认识 `OfflineStore`:弹幕这块不需要知道缓存目录长什么样,
 * 它要的只是"这一段的字节在不在手上"。
 */
fun interface LocalDanmakuSource {
    suspend fun read(cid: Long, segmentIndex: Int): ByteArray?

    companion object {
        /** 没有离线缓存时的默认值。 */
        val None = LocalDanmakuSource { _, _ -> null }
    }
}

/**
 * B 站弹幕数据源。接口是 `x/v2/dm/web/seg.so`(notes/danmaku.md §1.1 实测过):**不签名、
 * 不要登录态、GET、没有 gRPC 帧头**——响应体就是裸 protobuf(`DmSegMobileReply`),不像
 * PiliPlus 走的 app 端 gRPC-over-HTTP 路径那样还要手工拼 5 字节帧头。走 [BiliClient.rawGet]
 * 而不是绕过它,这是 CLAUDE.md 的硬约定。
 *
 * 6 分钟一段,`segment_index` 从 1 开始(§1.3)。
 *
 * **本地缓存优先。** 这一段被离线缓存过就地解析,不出网。判断放在这里而不是调用方:
 * `VideoViewModel` 那侧的分段调度、重试、失败标志一行都不用为离线改,它本来也不该知道
 * 这条视频是不是缓存过的 —— 读本地和读网络在它眼里是同一件事的两种来源。
 */
class DanmakuRepository(
    private val client: BiliClient,
    private val local: LocalDanmakuSource = LocalDanmakuSource.None,
) {

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
                    if (result is BiliResult.ApiError) logRejected(cid, segmentIndex, result)
                    result
                },
                onFailure = {
                    BiliLog.w("拉弹幕分段失败 url=${SEG_URL.pathOnly()} cid=$cid segment=$segmentIndex", it)
                    BiliResult.Failure(it)
                },
            )

    /**
     * 一个分段的**原始 protobuf 字节**。离线缓存用它落盘 —— 存字节而不是存解析结果,播放时
     * 复用同一份解析器,不会长出"在线一套、离线一套"两条映射。
     *
     * 与 [getSegment] 的差别只有"解不解析",失败分支与日志共用同一条路径。
     */
    suspend fun getSegmentBytes(cid: Long, segmentIndex: Int): BiliResult<ByteArray> =
        runCatching { fetchSegmentBytes(cid, segmentIndex) }
            .fold(
                onSuccess = { result ->
                    if (result is BiliResult.ApiError) logRejected(cid, segmentIndex, result)
                    result
                },
                onFailure = {
                    BiliLog.w("拉弹幕分段失败 url=${SEG_URL.pathOnly()} cid=$cid segment=$segmentIndex", it)
                    BiliResult.Failure(it)
                },
            )

    private suspend fun loadSegment(cid: Long, segmentIndex: Int): BiliResult<DanmakuSegment> {
        // 本地有就不出网。缓存过的视频在飞行模式下照样有弹幕,靠的就是这一句。
        val bytes = local.read(cid, segmentIndex)
            ?: when (val fetched = fetchSegmentBytes(cid, segmentIndex)) {
                is BiliResult.Ok -> fetched.value
                is BiliResult.ApiError -> return fetched
                is BiliResult.Failure -> return fetched
            }
        // 解析和映射一起进后台:分开的话主线程还是要遍历一遍解析结果。
        return withContext(Dispatchers.Default) {
            BiliResult.Ok(parseDmSegMobileReply(bytes).toMappedSegment())
        }
    }

    private suspend fun fetchSegmentBytes(cid: Long, segmentIndex: Int): BiliResult<ByteArray> {
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
        return BiliResult.Ok(response.body<ByteArray>())
    }

    private fun logRejected(cid: Long, segmentIndex: Int, result: BiliResult.ApiError) {
        BiliLog.w(
            "拉弹幕分段被拒 url=${SEG_URL.pathOnly()} cid=$cid segment=$segmentIndex " +
                "code=${result.code} message=${result.message}",
        )
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
