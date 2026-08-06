package com.bilby.data

import com.bilby.api.BiliClient
import com.bilby.api.BiliConstants
import com.bilby.api.BiliResult
import com.bilby.api.dto.ToViewActionEnvelopeDto
import com.bilby.api.dto.ToViewItemDto
import com.bilby.api.dto.ToViewResponseDto
import com.bilby.api.getData
import com.bilby.api.map
import com.bilby.api.toHttpsUrl
import io.ktor.client.call.body

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
    suspend fun add(bvid: String): BiliResult<Unit> = postAction(
        "${BiliConstants.WEB_HOST}/x/v2/history/toview/add",
        mapOf("bvid" to bvid),
    )

    /** 没有单独的"删单个"接口,单条删除就是 resources 传一个 aid(notes 2.5 节)。 */
    suspend fun delete(aid: Long): BiliResult<Unit> = postAction(
        "${BiliConstants.WEB_HOST}/x/v2/history/toview/v2/dels",
        mapOf("resources" to aid.toString()),
    )

    /** clean_type=2 即"清空已看完",没有独立接口(notes 2.6 节)。 */
    suspend fun clearFinished(): BiliResult<Unit> = postAction(
        "${BiliConstants.WEB_HOST}/x/v2/history/toview/clear",
        mapOf("clean_type" to "2"),
    )

    /**
     * 这三个写接口成功时 `data` 通常是 null,BiliClient.postForm 的"data 非空才算 Ok"判定
     * 会把这种成功也误判成 ApiError,所以绕开它,只看外层信封的 code(参考 AuthRepository
     * 直接用 rawPostForm + 手动解析信封的写法)。
     */
    private suspend fun postAction(url: String, form: Map<String, String>): BiliResult<Unit> = runCatching {
        val envelope = client.rawPostForm(url, form).body<ToViewActionEnvelopeDto>()
        if (envelope.code == 0) BiliResult.Ok(Unit) else BiliResult.ApiError(envelope.code, envelope.message)
    }.getOrElse { BiliResult.Failure(it) }

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
