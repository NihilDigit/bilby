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
    val durationText: String,
    val upName: String,
    val progressSeconds: Long,
) {
    val isFinished: Boolean get() = progressSeconds == -1L
}

data class ToViewList(val count: Int, val items: List<ToViewItem>)

/**
 * 稍后再看(DESIGN 2.5):直接用 B 站原生列表双向同步,不建本地队列。原生 100 条上限
 * 视为特性,所以这里一次性拉满(ps=100)就是全部,不需要翻页——这也是"挖存货/找相关都往
 * 这里丢"这套设计能成立的前提:上限本身很小,一屏装得下。
 */
class ToViewRepository(private val client: BiliClient) {

    suspend fun loadList(): BiliResult<ToViewList> {
        val params = mapOf(
            "pn" to "1",
            "ps" to CAPACITY.toString(),
            "viewed" to "0", // 全部(notes 2.2 节)
            "key" to "",
            "asc" to "false",
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
    suspend fun clearFinished(): BiliResult<Unit> = client.postAction(
        "${BiliConstants.WEB_HOST}/x/v2/history/toview/clear",
        mapOf("clean_type" to "2"),
    )

    private fun ToViewItemDto.toDomain() = ToViewItem(
        aid = aid,
        bvid = bvid,
        coverUrl = pic.toHttpsUrl(),
        title = title,
        durationText = "%d:%02d".format(duration / 60, duration % 60),
        upName = owner.name,
        progressSeconds = progress,
    )

    companion object {
        /** DESIGN 2.5:原生上限视为特性,UI 要把这个数字亮出来,不是随便挑的常量。 */
        const val CAPACITY = 100
    }
}
