package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.CODE_RISK_CHALLENGE
import dev.bilby.api.dto.SearchArticleItemDto
import dev.bilby.api.dto.SearchArticleResultDto
import dev.bilby.api.dto.SearchChallengeCarrier
import dev.bilby.api.dto.SearchSuggestDto
import dev.bilby.api.getOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    /** 标题里命中搜索词的几段,见 [parseKeywordHighlight]。 */
    val titleHighlights: List<IntRange> = emptyList(),
)

data class SearchUser(
    val mid: Long,
    val name: String,
    val avatarUrl: String,
    val fansCount: Long,
    val signature: String,
    val videoCount: Long = 0,
)

/** 专栏搜索的一条。打开走 cv 号那一套(`ArticleRef(isRead = true)`)。 */
data class SearchArticle(
    val id: Long,
    val title: String,
    val summary: String,
    val coverUrl: String,
    val viewCount: Long,
    val replyCount: Long,
    val publishedAtEpochSeconds: Long,
    val categoryName: String,
    val titleHighlights: List<IntRange> = emptyList(),
)

data class SearchVideoPage(val items: List<SearchVideo>, val hasMore: Boolean)

/** 一条补全词。[term] 是回车时搜的词,[text] 是列表里显示的那行,命中输入的几段在 [highlights]。 */
data class SearchSuggestion(val term: String, val text: String, val highlights: List<IntRange>)

/** 分类搜索的一页。[hasMore] 按服务端那一页的原始条数算,理由见 [SearchRepository.searchVideos]。 */
data class SearchPage<T>(val items: List<T>, val hasMore: Boolean)

/**
 * 搜索快路数据层(DESIGN 2.2:回车直搜,不经 LLM,结果页只有结果)。
 * 接口依据 notes/space-and-search.md 2.2/2.7/2.8 节:`search/type` 需要 WBI 签名。
 */
class SearchRepository(private val client: BiliClient) {

    /**
     * @param pubTime 发布时间窗,秒级时间戳的闭区间;null 不限。
     */
    suspend fun searchVideos(
        keyword: String,
        page: Int,
        order: String,
        duration: Int? = null,
        tids: Int? = null,
        pubTime: LongRange? = null,
    ): BiliResult<SearchVideoPage> {
        val params = typeParams("video", keyword, page) + buildMap {
            if (order.isNotEmpty()) put("order", order)
            duration?.let { put("duration", it.toString()) }
            tids?.let { put("tids", it.toString()) }
            pubTime?.let {
                put("pubtime_begin_s", it.first.toString())
                put("pubtime_end_s", it.last.toString())
            }
        }
        return searchType<SearchVideoResultDto>("video", keyword, params).map { dto ->
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

    suspend fun searchUsers(keyword: String, page: Int = 1): BiliResult<List<SearchUser>> =
        searchType<SearchUserResultDto>("bili_user", keyword, typeParams("bili_user", keyword, page))
            .map { dto -> dto.result.map { it.toSearchUser() } }

    /**
     * 用户搜索,带翻页。搜索页的「用户」一栏用它;助理工具只要第一页,走 [searchUsers]。
     *
     * @param order 排序字段(`fans` / `level`),空串是默认排序;[ascending] 只在给了字段时有意义
     *   (notes 2.4 的 UserOrderType:`order_sort` 0 降序、1 升序)。
     */
    suspend fun searchUserPage(
        keyword: String,
        page: Int,
        order: String = "",
        ascending: Boolean = false,
    ): BiliResult<SearchPage<SearchUser>> {
        val params = typeParams("bili_user", keyword, page) + buildMap {
            if (order.isNotEmpty()) {
                put("order", order)
                put("order_sort", if (ascending) "1" else "0")
            }
        }
        return searchType<SearchUserResultDto>("bili_user", keyword, params).map { dto ->
            val raw = dto.result.map { it.toSearchUser() }
            // mid 为 0 的条目点不进空间,还会在 LazyColumn 的 key 上撞车,理由同视频的 bvid。
            SearchPage(raw.filter { it.mid != 0L }, hasMore = (page - 1) * PAGE_SIZE + raw.size < dto.numResults)
        }
    }

    /**
     * 专栏搜索。`order` 与视频共用一组取值里的一部分(notes 2.4:totalrank/pubdate/click/scores
     * 同值,attention 只有专栏有),所以搜索页两栏共用一个排序枚举。
     */
    suspend fun searchArticles(keyword: String, page: Int, order: String): BiliResult<SearchPage<SearchArticle>> {
        val params = typeParams("article", keyword, page) + buildMap {
            if (order.isNotEmpty()) put("order", order)
        }
        return searchType<SearchArticleResultDto>("article", keyword, params).map { dto ->
            val raw = dto.result.map { it.toSearchArticle() }
            SearchPage(raw.filter { it.id != 0L }, hasMore = (page - 1) * PAGE_SIZE + raw.size < dto.numResults)
        }
    }

    private fun typeParams(type: String, keyword: String, page: Int): Map<String, String> = mapOf(
        "search_type" to type,
        "keyword" to keyword,
        "page" to page.toString(),
        "page_size" to PAGE_SIZE.toString(),
        "platform" to "pc",
        "web_location" to "1430654",
    )

    /**
     * 分类搜索的一次请求。
     *
     * **Referer 与 Origin 指到搜索页本身**(`search.bilibili.com/<类型>?keyword=…`),照 PiliPlus
     * `http/search.dart` 的 searchByType:真实浏览器发这条请求时人就在那一页上,默认的主站
     * Referer 对不上,是风控最好认的破绽之一。
     *
     * 触发风控时 data 里只有 `v_voucher`,折成 [CODE_RISK_CHALLENGE],不当作空结果。
     */
    private suspend inline fun <reified T : SearchChallengeCarrier> searchType(
        type: String,
        keyword: String,
        params: Map<String, String>,
    ): BiliResult<T> {
        val referer = "https://search.bilibili.com/$type?keyword=${encodeQuery(keyword)}"
        val result = client.getData<T>(SEARCH_URL, params, signed = true, referer = referer)
        if (result is BiliResult.Ok && result.value.vVoucher != null) {
            BiliLog.w("搜索触发风控验证(search_type=$type)")
            return BiliResult.ApiError(CODE_RISK_CHALLENGE, "v_voucher")
        }
        return result
    }

    /**
     * 输入框的补全词(notes/space-and-search.md 2.9)。失败或没有时给空表:补全只是锦上添花,
     * 出错不该打扰正在打字的人,原因已由 [getData] 记进日志。
     */
    suspend fun suggest(term: String): List<SearchSuggestion> {
        val params = mapOf(
            "term" to term,
            "highlight" to "0",
            "spmid" to "333.1365",
            "web_location" to "333.1365",
        )
        val dto = client.getData<SearchSuggestDto>(SUGGEST_URL, params, signed = true).getOrNull() ?: return emptyList()
        val tags = (dto.result as? JsonObject)?.get("tag") as? JsonArray ?: return emptyList()
        return tags.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val term = (item["term"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val (text, highlights) = ((item["name"] as? JsonPrimitive)?.contentOrNull ?: term).parseKeywordHighlight()
            SearchSuggestion(term = term, text = text, highlights = highlights)
        }
    }

    private fun encodeQuery(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun SearchArticleItemDto.toSearchArticle(): SearchArticle {
        val (plainTitle, highlights) = title.parseKeywordHighlight()
        return SearchArticle(
            id = id,
            title = plainTitle,
            titleHighlights = highlights,
            summary = desc.stripKeywordHighlight(),
            coverUrl = imageUrls.firstOrNull().orEmpty().toHttpsUrl(),
            viewCount = view,
            replyCount = reply,
            publishedAtEpochSeconds = pubTime,
            categoryName = categoryName,
        )
    }

    private fun SearchVideoItemDto.toSearchVideo(): SearchVideo {
        val (plainTitle, highlights) = title.parseKeywordHighlight()
        return SearchVideo(
            bvid = bvid,
            title = plainTitle,
            titleHighlights = highlights,
            coverUrl = pic.toHttpsUrl(),
            durationText = duration,
            upName = author,
            upMid = mid,
            publishedAtEpochSeconds = pubdate,
            playCount = play,
            danmakuCount = danmaku,
        )
    }

    private fun SearchUserItemDto.toSearchUser() = SearchUser(
        mid = mid,
        name = uname.stripKeywordHighlight(),
        avatarUrl = upic.toHttpsUrl(),
        fansCount = fans,
        signature = usign,
        videoCount = videos,
    )

    private companion object {
        const val SEARCH_URL = "${BiliConstants.WEB_HOST}/x/web-interface/wbi/search/type"
        const val SUGGEST_URL = "${BiliConstants.WEB_HOST}/x/web-interface/suggest"
        const val PAGE_SIZE = 20
    }
}

private val EmTag = Regex("</?em[^>]*>")

/**
 * 视频标题带 `<em class="keyword">关键词</em>` 高亮标签且做过 HTML 实体转义(notes 2.7)。
 * 用户名笔记里说没有高亮标签,但同样过一遍无害,统一处理更简单。实体解码本身见
 * [decodeHtmlEntities] —— 评论区读的是同一份实现。
 */
private fun String.stripKeywordHighlight(): String = replace(EmTag, "").decodeHtmlEntities()

/**
 * 同一份标签,但记下命中的位置,供标题标出搜索词。命中是服务端分词后的结果("宝可梦 朱紫"
 * 两个词分别标),在客户端拿查询串去匹配标题做不到这一点。实体按段解码,位置才算在解码后的
 * 文字上。
 */
private fun String.parseKeywordHighlight(): Pair<String, List<IntRange>> {
    val text = StringBuilder()
    val ranges = mutableListOf<IntRange>()
    var openedAt = -1
    var consumed = 0
    for (tag in EmTag.findAll(this)) {
        text.append(substring(consumed, tag.range.first).decodeHtmlEntities())
        if (tag.value.startsWith("</")) {
            if (openedAt in 0 until text.length) ranges += openedAt until text.length
            openedAt = -1
        } else {
            openedAt = text.length
        }
        consumed = tag.range.last + 1
    }
    text.append(substring(consumed).decodeHtmlEntities())
    return text.toString() to ranges
}
