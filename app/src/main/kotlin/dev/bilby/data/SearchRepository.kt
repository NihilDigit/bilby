package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.SearchUserItemDto
import dev.bilby.api.dto.SearchUserResultDto
import dev.bilby.api.dto.SearchVideoItemDto
import dev.bilby.api.dto.SearchVideoResultDto
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.toHttpsUrl

data class SearchVideo(
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val durationText: String,
    val upName: String,
    val upMid: Long,
    val publishedAtEpochSeconds: Long,
    val playCount: Long,
    val danmakuCount: Long,
)

data class SearchUser(
    val mid: Long,
    val name: String,
    val avatarUrl: String,
    val fansCount: Long,
    val signature: String,
)

data class SearchVideoPage(val items: List<SearchVideo>, val hasMore: Boolean)

/**
 * 搜索快路数据层(DESIGN 2.2:回车直搜,不经 LLM,结果页只有结果)。
 * 接口依据 notes/space-and-search.md 2.2/2.7/2.8 节:`search/type` 需要 WBI 签名。
 */
class SearchRepository(private val client: BiliClient) {

    suspend fun searchVideos(
        keyword: String,
        page: Int,
        order: String,
        duration: Int? = null,
        tids: Int? = null,
    ): BiliResult<SearchVideoPage> {
        val params = buildMap {
            put("search_type", "video")
            put("keyword", keyword)
            put("page", page.toString())
            if (order.isNotEmpty()) put("order", order)
            duration?.let { put("duration", it.toString()) }
            tids?.let { put("tids", it.toString()) }
            put("page_size", PAGE_SIZE.toString())
            put("platform", "pc")
            put("web_location", "1430654")
        }
        return client.getData<SearchVideoResultDto>(SEARCH_URL, params, signed = true).map { dto ->
            val items = dto.result.map { it.toSearchVideo() }
            val loaded = (page - 1) * PAGE_SIZE + items.size
            SearchVideoPage(items, hasMore = loaded < dto.numResults)
        }
    }

    suspend fun searchUsers(keyword: String, page: Int = 1): BiliResult<List<SearchUser>> {
        val params = mapOf(
            "search_type" to "bili_user",
            "keyword" to keyword,
            "page" to page.toString(),
            "page_size" to PAGE_SIZE.toString(),
            "platform" to "pc",
            "web_location" to "1430654",
        )
        return client.getData<SearchUserResultDto>(SEARCH_URL, params, signed = true)
            .map { dto -> dto.result.map { it.toSearchUser() } }
    }

    private fun SearchVideoItemDto.toSearchVideo() = SearchVideo(
        bvid = bvid,
        title = title.stripKeywordHighlight(),
        coverUrl = pic.toHttpsUrl(),
        durationText = duration,
        upName = author,
        upMid = mid,
        publishedAtEpochSeconds = pubdate,
        playCount = play,
        danmakuCount = danmaku,
    )

    private fun SearchUserItemDto.toSearchUser() = SearchUser(
        mid = mid,
        name = uname.stripKeywordHighlight(),
        avatarUrl = upic.toHttpsUrl(),
        fansCount = fans,
        signature = usign,
    )

    private companion object {
        const val SEARCH_URL = "${BiliConstants.WEB_HOST}/x/web-interface/wbi/search/type"
        const val PAGE_SIZE = 20
    }
}

/**
 * 视频标题带 `<em class="keyword">关键词</em>` 高亮标签且做过 HTML 实体转义(notes 2.7)。
 * 用户名笔记里说没有高亮标签,但同样过一遍无害,统一处理更简单。`&amp;` 必须最后解码,
 * 否则 `&amp;lt;` 这种双重转义会被错误地还原成 `<`。
 */
private fun String.stripKeywordHighlight(): String = replace(Regex("</?em[^>]*>"), "")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
