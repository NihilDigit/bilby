package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.HistoryItemDto
import dev.bilby.api.dto.HistoryResponseDto
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.toHttpsUrl
import dev.bilby.formatDurationSeconds

/** 一条历史记录。只有 UGC 稿件会走到这里,见 [computeHistoryPage] 的过滤说明。 */
data class HistoryItem(
    val oid: Long,
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val durationText: String,
    val durationSeconds: Long,
    val upName: String,
    val viewAtEpochSeconds: Long,
    val progressSeconds: Long,
) {
    val isFinished: Boolean get() = progressSeconds == -1L
}

data class HistoryPage(
    val items: List<HistoryItem>,
    val nextMax: Long,
    val nextViewAt: Long,
    val isEnd: Boolean,
)

/**
 * 历史记录(DESIGN 2 节:历史只待在它自己这一页,不做"继续观看"一类推送式入口,
 * 不喂给别的界面)。
 *
 * 游标分页依据 notes/comment-toview-history.md §3.3:下一页的 `max`/`view_at` 取自
 * **上一页服务端返回的最后一条**的 `history.oid`/`view_at`,不是服务端单独给的 cursor 对象;
 * 空列表即到底。接口本身不带 WBI。
 */
class HistoryRepository(private val client: BiliClient) {

    suspend fun loadPage(max: Long, viewAt: Long): BiliResult<HistoryPage> {
        val params = mapOf(
            "type" to "all",
            "ps" to PAGE_SIZE.toString(),
            "max" to max.toString(),
            "view_at" to viewAt.toString(),
        )
        val result = client.getData<HistoryResponseDto>(
            "${BiliConstants.WEB_HOST}/x/web-interface/history/cursor",
            params,
        )
        return result.map { dto -> computeHistoryPage(dto.list, max, viewAt) }
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}

/**
 * 纯函数,把一页原始响应折成 [HistoryPage]。拆出来单独测,不需要真的发请求。
 *
 * 非稿件条目(直播/专栏/番剧/课程)在这里一处丢弃 —— 拿不到能打开的视频页,留着的直接后果
 * 是消费方各自都要记得防一次(同样的道理见 SearchRepository.kt:61-69 的注释)。
 * 游标按**过滤前**的服务端列表算:过滤掉几条不代表服务端那页少发了几条,拿过滤后的数
 * 去接下一页会把游标对错位置。
 */
internal fun computeHistoryPage(raw: List<HistoryItemDto>, fallbackMax: Long, fallbackViewAt: Long): HistoryPage {
    val items = raw
        .filter { it.history.business == "archive" && it.history.bvid.isNotEmpty() }
        .map { it.toDomain() }
    if (items.size != raw.size) {
        BiliLog.w("历史记录丢弃 ${raw.size - items.size} 条非稿件条目(max=$fallbackMax view_at=$fallbackViewAt)")
    }
    val last = raw.lastOrNull()
    return HistoryPage(
        items = items,
        nextMax = last?.history?.oid ?: fallbackMax,
        nextViewAt = last?.viewAt ?: fallbackViewAt,
        isEnd = raw.isEmpty(),
    )
}

private fun HistoryItemDto.toDomain() = HistoryItem(
    oid = history.oid,
    bvid = history.bvid,
    title = title,
    coverUrl = cover.toHttpsUrl(),
    durationText = formatDurationSeconds(duration),
    durationSeconds = duration,
    upName = authorName,
    viewAtEpochSeconds = viewAt,
    progressSeconds = progress,
)
