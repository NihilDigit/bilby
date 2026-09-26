package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.ToViewItemDto
import dev.bilby.api.dto.ToViewResponseDto
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.postAction
import dev.bilby.api.toHttpsUrl

/** 一条稍后再看。progress==-1 表示已看完(notes/comment-toview-history.md 2.3 节)。 */
data class ToViewItem(
    val aid: Long,
    val bvid: String,
    val coverUrl: String,
    val title: String,
    /** 存秒,不存拼好的文案:进度条要拿它算比例,而展示统一交给 `formatDurationSeconds`。 */
    val durationSeconds: Long,
    val upName: String,
    val progressSeconds: Long,
    val pubdateEpochSeconds: Long,
    val playCount: Long,
    val danmakuCount: Long,
    val kind: ToViewKind,
    /** 剧集的类别名(「番剧」「电影」),只有 [ToViewKind.Pgc] 可能带。 */
    val pgcLabel: String = "",
    val chargingOnly: Boolean = false,
) {
    val isFinished: Boolean get() = progressSeconds == -1L

    /**
     * 剧集与课程不是投稿,本应用不打开它们(UGC-only)。照常列出而不藏:藏起来的话,
     * 容量条上的数字和眼前的条数对不上。
     */
    val playable: Boolean get() = kind == ToViewKind.Video && bvid.isNotEmpty()
}

enum class ToViewKind { Video, Pgc, Course }

data class ToViewList(val count: Int, val items: List<ToViewItem>)

/**
 * 稍后再看(DESIGN 2.5):直接用 B 站原生列表双向同步,不建本地队列。原生 100 条上限
 * 视为特性,所以这里一次性拉满(ps=100)就是全部,不需要翻页——这也是"挖存货/找相关都往
 * 这里丢"这套设计能成立的前提:上限本身很小,一屏装得下。
 */
class ToViewRepository(private val client: BiliClient) {

    /** @param asc true 为最早添加的在前。队列续取时要带同一个方向,见 [QueueContext.ToView]。 */
    suspend fun loadList(asc: Boolean = false): BiliResult<ToViewList> {
        val params = mapOf(
            "pn" to "1",
            "ps" to CAPACITY.toString(),
            "viewed" to "0", // 全部(notes 2.2 节)
            "key" to "",
            "asc" to asc.toString(),
            "need_split" to "true",
            "web_location" to "333.881",
        )
        val result = client.getData<ToViewResponseDto>(
            "${BiliConstants.WEB_HOST}/x/v2/history/toview/web",
            params,
            signed = true,
        )
        return result.map { dto -> ToViewList(dto.count, dto.list.map { it.toDomain() }) }
    }

    /** 添加,aid/bvid 二选一(notes 2.4 节);Bilby 目前只有 bvid 语义,固定传 bvid。 */
    suspend fun add(bvid: String): BiliResult<Unit> = client.postAction(
        "${BiliConstants.WEB_HOST}/x/v2/history/toview/add",
        mapOf("bvid" to bvid),
    )

    /** 没有单独的"删单个"接口,单条删除就是 resources 传一个 aid(notes 2.5 节)。 */
    suspend fun delete(aid: Long): BiliResult<Unit> = client.postAction(
        "${BiliConstants.WEB_HOST}/x/v2/history/toview/v2/dels",
        mapOf("resources" to aid.toString()),
    )

    /** clean_type=2 即"清空已看完",没有独立接口(notes 2.6 节)。 */
    suspend fun clearFinished(): BiliResult<Unit> = clear(CLEAN_FINISHED)

    /** clean_type=1,清空已失效(notes 2.6 节)。 */
    suspend fun clearInvalid(): BiliResult<Unit> = clear(CLEAN_INVALID)

    private suspend fun clear(cleanType: Int): BiliResult<Unit> = client.postAction(
        "${BiliConstants.WEB_HOST}/x/v2/history/toview/clear",
        mapOf("clean_type" to cleanType.toString()),
    )

    private fun ToViewItemDto.toDomain() = ToViewItem(
        aid = aid,
        bvid = bvid,
        coverUrl = pic.toHttpsUrl(),
        title = title,
        durationSeconds = duration,
        upName = owner.name,
        progressSeconds = progress,
        pubdateEpochSeconds = pubdate,
        playCount = stat.view,
        danmakuCount = stat.danmaku,
        kind = when {
            isPgc -> ToViewKind.Pgc
            isPugv -> ToViewKind.Course
            else -> ToViewKind.Video
        },
        pgcLabel = pgcLabel,
        chargingOnly = chargingPay?.level != null,
    )

    companion object {
        /** DESIGN 2.5:原生上限视为特性,UI 要把这个数字亮出来,不是随便挑的常量。 */
        const val CAPACITY = 100

        private const val CLEAN_INVALID = 1
        private const val CLEAN_FINISHED = 2
    }
}
