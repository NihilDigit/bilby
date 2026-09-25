package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.HistoryItemDto
import dev.bilby.api.dto.HistoryResponseDto
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.postAction
import dev.bilby.api.toHttpsUrl
import dev.bilby.formatDurationSeconds

/**
 * 服务端用来区分内容类型的字段值,同时是删除主键的前缀。Bilby 只留稿件
 * (见 [computeHistoryPage] 的过滤),所以两处用的是同一个常量:能被列出来的条目,
 * 拼出来的删除主键就一定对得上。
 */
private const val ArchiveBusiness = "archive"

/** 一条历史记录。只有 UGC 稿件会走到这里,见 [computeHistoryPage] 的过滤说明。 */
data class HistoryItem(
    val oid: Long,
    /** 删除主键。**不是 [oid]**,见 notes/comment-toview-history.md §3.5。 */
    val kid: Long,
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

    /**
     * 删除,单条和批量是同一个接口:`kid` 参数本身就是逗号分隔的一串 `business_kid`
     * (notes §3.5)。web cookie,不签 WBI。
     */
    suspend fun delete(kids: List<Long>): BiliResult<Unit> = client.postAction(
        "${BiliConstants.WEB_HOST}/x/v2/history/delete",
        mapOf(
            "kid" to kids.joinToString(",") { "${ArchiveBusiness}_$it" },
            "jsonp" to "jsonp",
        ),
    )

    /** 清空全部(notes §3.7)。"清空已看完"没有对应的服务端模式,由调用方筛出来走 [delete]。 */
    suspend fun clear(): BiliResult<Unit> = client.postAction(
        "${BiliConstants.WEB_HOST}/x/v2/history/clear",
        mapOf("jsonp" to "jsonp"),
    )

    /**
     * 是否正在暂停记录观看历史,`true` 即暂停中(notes §3.6)。
     *
     * **响应体的 `data` 直接是一个布尔值,不是对象**,所以这里的类型参数是 `Boolean` 而不是
     * 某个 DTO。
     */
    suspend fun isPaused(): BiliResult<Boolean> = client.getData<Boolean>(
        "${BiliConstants.WEB_HOST}/x/v2/history/shadow",
        mapOf("jsonp" to "jsonp"),
    )

    /**
     * 暂停或恢复记录观看历史,`paused = true` 是暂停(notes §3.6)。
     *
     * 两件容易误会的事:
     *
     * 这是**账号级的服务端设置**,不是本机开关 —— 在这里关掉,官方客户端和网页端同样不再
     * 记录,回到官方端也要在那边改回来。
     *
     * 它**只挡新记录**,已有的历史一条不动(要清得走 [clear] 或 [delete]),也不影响播放
     * 进度:进度走 `x/click-interface/web/heartbeat`(见 [HeartbeatReporter] 与 notes §8.1),
     * 是另一条链路,暂停期间续播照常。
     */
    suspend fun setPaused(paused: Boolean): BiliResult<Unit> = client.postAction(
        "${BiliConstants.WEB_HOST}/x/v2/history/shadow/set",
        mapOf("switch" to paused.toString(), "jsonp" to "jsonp"),
    )

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
        .filter { it.history.business == ArchiveBusiness && it.history.bvid.isNotEmpty() }
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
    kid = kid,
    bvid = history.bvid,
    title = title,
    coverUrl = cover.toHttpsUrl(),
    durationText = formatDurationSeconds(duration),
    durationSeconds = duration,
    upName = authorName,
    viewAtEpochSeconds = viewAt,
    progressSeconds = progress,
)
