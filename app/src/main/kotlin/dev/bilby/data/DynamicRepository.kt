package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.DynamicDetailResponseDto
import dev.bilby.api.dto.DynamicFeedResponseDto
import dev.bilby.api.dto.DynamicItemDto
import dev.bilby.api.getData
import dev.bilby.api.postJsonAction
import dev.bilby.api.toHttpsUrl
import dev.bilby.data.model.DynamicAdditional
import dev.bilby.data.model.DynamicCard
import dev.bilby.data.model.FeedEntry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 一页动态,已经按内容形态分成两半。
 *
 * @param home 首页那一半:投稿视频、合集更新、专栏投稿。
 * @param other "其他动态"那一半:图文、纯文字、转发、直播、番剧更新……
 */
data class DynamicFeedPage(
    val home: List<FeedEntry>,
    val other: List<DynamicCard>,
    val nextOffset: String?,
    val hasMore: Boolean,
)

/**
 * 关注动态流(DESIGN 2.1:关注 UP 投稿,时间序)。接口细节全部依据 notes/dynamic-feed.md
 * 第 1 节:`feed/all` 不需要 WBI 签名、不需要 csrf。
 *
 * **只有一条流,`type=all`,分流在解析这一层做。** 服务端的 `type` 只有 all/video/article/pgc
 * 四档,拼不出"除了投稿之外的全部";而按内容形态分屏是展示决定,下沉成两次请求的代价是两条
 * 管线各有各的游标、缓存和过滤规则,凡是要求两边一致的事情都得手写同步 —— 首页排除掉的 UP
 * 在"其他动态"里照样出现,就是这么来的。分流规则见 [toFeedPage],流本身由 DynamicFeedStore 持有。
 */
class DynamicRepository(private val client: BiliClient) {

    /**
     * 取一页。**不做自动翻页**:一页里两半各能分到多少条差别很大,"够不够"只有要用它的那一侧
     * 知道,续翻由 DynamicFeedStore 决定。
     */
    suspend fun loadFeed(offset: String? = null): BiliResult<DynamicFeedPage> =
        when (val result = client.getData<DynamicFeedResponseDto>(FEED_URL, feedParams(offset))) {
            is BiliResult.Ok -> BiliResult.Ok(result.value.toFeedPage())
            is BiliResult.ApiError -> result
            is BiliResult.Failure -> result
        }

    /**
     * 一条动态的全部内容。动态详情页进来就走这条,**不接受列表页传过来的那份卡片**:评论区的
     * oid 与 type 只在 `basic` 里,而列表项的 `basic` 有时是缺的,推不出来只能回头再问一次
     * (notes/dynamic-cards.md 第 8 节)。分成"齐了直接用、缺了补一次"两条路的话,其中一条
     * 几乎不会被走到,它坏了也没人知道。
     *
     * 解析后为 null 表示这条动态没有可显示的内容(见 DynamicCardMapper),按业务失败报出去,
     * 不返回一张空卡片。
     */
    suspend fun loadDetail(id: String): BiliResult<DynamicCard> {
        val params = mapOf(
            "id" to id,
            "timezone_offset" to TIMEZONE_OFFSET,
            "features" to BiliConstants.DYN_FEATURES,
            "gaia_source" to "Athena",
            "web_location" to "333.1330",
        )
        return when (val result = client.getData<DynamicDetailResponseDto>(DETAIL_URL, params)) {
            is BiliResult.Ok -> result.value.item?.toDynamicCard()
                ?.let { BiliResult.Ok(it) }
                ?: BiliResult.ApiError(0, "这条动态没有可显示的内容")

            is BiliResult.ApiError -> result
            is BiliResult.Failure -> result
        }
    }

    /**
     * 动态点赞。**与视频点赞不是同一条接口**,也不是同一条路线:视频那条走 app 端 access_key,
     * 这条是网页 Cookie + csrf。风控按动作算,别处能过不代表这里能过(notes 第 7 节)。
     *
     * `up` 是 1/2 而不是 0/1 —— 2 才是取消,这一条反直觉,写错的表现是"取消点赞"变成再点一次赞。
     */
    /**
     * 动态点赞。**body 是 JSON,不是 form**,而且 `up` 要发成 JSON 数字 —— 发成字符串
     * 服务端回 `4100001 参数错误`(notes/dynamic-cards.md 第 7 节)。csrf 在 query,
     * Referer 指到动态站而不是站点首页。
     */
    suspend fun likeDynamic(id: String, like: Boolean): BiliResult<Unit> = client.postJsonAction(
        url = THUMB_URL,
        body = buildJsonObject {
            put("dyn_id_str", id)
            put("up", if (like) THUMB_UP else THUMB_CANCEL)
            put("spmid", "333.1365.0.0")
        },
        referer = BiliConstants.DYNAMIC_HOST,
    )

    /**
     * 一页里的每一条去首页还是去"其他动态"。
     *
     * 三条规则,顺序即优先级:视频类动态归首页(拿不到 bvid 的那几种归谁都进不去,见
     * [toFeedVideo]);正文被服务端截断的 opus 是专栏投稿,归首页(见 [toFeedArticle]);
     * 其余一律是"其他动态"。
     */
    private fun DynamicFeedResponseDto.toFeedPage(): DynamicFeedPage {
        val home = mutableListOf<FeedEntry>()
        val other = mutableListOf<DynamicCard>()
        for (item in items) {
            when {
                item.type in HOME_VIDEO_TYPES -> item.toFeedVideo()?.let(home::add)
                else -> {
                    val article = item.toFeedArticle()
                    if (article != null) home += article else item.toDynamicCard()?.let(other::add)
                }
            }
        }
        return DynamicFeedPage(home, other, offset.ifEmpty { null }, hasMore)
    }

    private fun feedParams(offset: String?): Map<String, String> = buildMap {
        put("type", "all")
        offset?.let { put("offset", it) }
        put("features", BiliConstants.DYN_FEATURES)
    }

    /**
     * 投稿视频与合集更新,两者的 major 子对象复用同一套 ArchiveDto 字段(notes 第 5、6 节)。
     *
     * 转发不进首页:转发的真实内容在 item.orig 里,而 DESIGN 2.1 已经定案「转发不混排」
     * (转发会把时间序流变成半个广场),所以这里不解析 orig,它归"其他动态"那一半。
     */
    private fun DynamicItemDto.toFeedVideo(): FeedEntry.Video? {
        val archive = when (type) {
            "DYNAMIC_TYPE_AV" -> modules?.moduleDynamic?.major?.archive
            else -> modules?.moduleDynamic?.major?.ugcSeason
        } ?: return null
        val author = modules?.moduleAuthor ?: return null
        // 笔记里 PiliPlus 在拿不到 bvid 时 fallback 到 epid(番剧类常见),但 epid 不是
        // bvid,播放页取流走的是 bvid 语义,塞进去会在下一层炸;按团队要求直接丢弃这条item。
        val bvid = archive.bvid?.takeIf { it.isNotBlank() } ?: return null
        return FeedEntry.Video(
            bvid = bvid,
            title = archive.title,
            coverUrl = archive.cover.toHttpsUrl(),
            durationText = archive.durationText,
            upName = author.name,
            upMid = author.mid,
            publishedAtEpochSeconds = author.pubTs,
            playCount = archive.stat?.play ?: "",
            danmakuCount = archive.stat?.danmaku ?: "",
        )
    }

    private companion object {
        const val FEED_URL = "${BiliConstants.WEB_HOST}/x/polymer/web-dynamic/v1/feed/all"
        const val DETAIL_URL = "${BiliConstants.WEB_HOST}/x/polymer/web-dynamic/v1/detail"
        const val THUMB_URL = "${BiliConstants.WEB_HOST}/x/dynamic/feed/dyn/thumb"

        /** 东八区,分钟数取负。服务端按它算"几天前"这类展示串。 */
        const val TIMEZONE_OFFSET = "-480"

        /** `up`:1 点赞,2 取消。**不是 0/1**,而且要发成 JSON 数字,发字符串回 4100001。 */
        const val THUMB_UP = 1
        const val THUMB_CANCEL = 2

        /**
         * 归首页的两种视频动态。**番剧、影视、课堂不在里面**:它们虽然也是"视频类",但首页
         * 那条路要求有 bvid,番剧只有 epid,于是一条都进不去 —— 归到"其他动态"那一半去,
         * 才不至于整个看不见。
         */
        val HOME_VIDEO_TYPES = setOf("DYNAMIC_TYPE_AV", "DYNAMIC_TYPE_UGC_SEASON")
    }
}
