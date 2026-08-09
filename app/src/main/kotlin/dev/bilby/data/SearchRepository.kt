package dev.bilby.data

import dev.bilby.BiliLog
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
            // 搜索结果里会混进没有 bvid 的条目(非稿件类的卡片)。它们点了也打不开——跳转全按
            // bvid 走——而留着的直接后果是崩溃:UI 拿 bvid 当 LazyColumn 的 key,两条空串就是
            // 「Key "" was already used」。丢在产出侧一处,不指望每个消费方各自记得防。
            val raw = dto.result.map { it.toSearchVideo() }
            val items = raw.filter { it.bvid.isNotEmpty() }
            if (items.size != raw.size) {
                BiliLog.w("搜索结果丢弃 ${raw.size - items.size} 条无 bvid 的条目(keyword=$keyword page=$page)")
            }
            // 翻页进度按**服务端给的那一页**算,不按过滤后的条数:过滤掉几条不代表服务端那边
            // 少发了几条,拿过滤后的数去比 numResults 会让 hasMore 一直为真,翻不到头。
            val loaded = (page - 1) * PAGE_SIZE + raw.size
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

private val EmTag = Regex("</?em[^>]*>")

/** `&#39;` 与 `&#x27;` 两种写法。B 站的标题里撇号就是以后者出现的。 */
private val NumericEntity = Regex("&#([xX][0-9a-fA-F]+|[0-9]+);")

/**
 * 视频标题带 `<em class="keyword">关键词</em>` 高亮标签且做过 HTML 实体转义(notes 2.7)。
 * 用户名笔记里说没有高亮标签,但同样过一遍无害,统一处理更简单。`&amp;` 必须最后解码,
 * 否则 `&amp;lt;` 这种双重转义会被错误地还原成 `<`;数字字符引用同理排在它前面 ——
 * 双重转义过的 `&amp;#x27;` 里没有 `&#` 相邻,不会被这一步误伤。
 */
private fun String.stripKeywordHighlight(): String = replace(EmTag, "")
    .replace(NumericEntity) { match ->
        val body = match.groupValues[1]
        val code = if (body[0] == 'x' || body[0] == 'X') {
            body.drop(1).toIntOrNull(16)
        } else {
            body.toIntOrNull()
        }
        // 码点非法(超出 Unicode 范围之类)时原样留着,总好过抛异常把整条结果搞没。
        code?.takeIf { it in 0..0x10FFFF }?.let { String(Character.toChars(it)) } ?: match.value
    }
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
