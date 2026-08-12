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
import dev.bilby.data.model.FeedItem
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class FeedPage(val items: List<FeedItem>, val nextOffset: String?, val hasMore: Boolean)

data class DynamicCardPage(val items: List<DynamicCard>, val nextOffset: String?, val hasMore: Boolean)

/**
 * 首页动态流(DESIGN 2.1:关注 UP 投稿,时间序,只显示视频类动态)。
 * 接口细节全部依据 notes/dynamic-feed.md 第 1 节:`feed/all` 不需要 WBI 签名、不需要 csrf。
 *
 * 图文、文字、转发这些非视频动态有地方看,但不在这里:它们只出现在 UP 主空间的动态 tab
 * (见 SpaceRepository.loadDynamics)。首页是投稿时间序,混进去就成了半个广场。
 */
class DynamicRepository(private val client: BiliClient) {

    /** 客户端过滤后一页可能全空,最多连翻这么多页去凑内容,防止在坏数据上无限递归(notes 第 1 节"分页机制")。 */
    private val maxAutoPages = 3

    suspend fun loadVideoFeed(offset: String? = null): BiliResult<FeedPage> =
        loadPage(offset, pagesLeft = maxAutoPages)

    /**
     * 首页折起来的那一半:图文、转发、直播、专栏、番剧更新……(DESIGN 2.1 的"图文/转发折叠为
     * 一个不显眼的入口")。
     *
     * **走 `type=all` 再在本地筛掉投稿视频**,不是另找一条接口:服务端只有 `video`/`article`/
     * `pgc` 这几档,拼不出"除了投稿之外的全部",而漏掉的那些类型正是这一页存在的理由。
     * 筛的判据是"它已经在首页出现过了",所以只排除 AV 与合集更新两种(notes/dynamic-cards.md)。
     *
     * 这一页仍然是时间序、仍然只含关注的人发的东西,与 DESIGN 1.1 的机制表逐条对过:没有排序、
     * 没有推荐池、翻到底就没了。它和首页的区别只有内容形态,不是另一条推送管道。
     */
    suspend fun loadOtherFeed(offset: String? = null): BiliResult<DynamicCardPage> =
        loadCardPage(offset, pagesLeft = maxAutoPages)

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

    private suspend fun loadPage(offset: String?, pagesLeft: Int): BiliResult<FeedPage> {
        val result = client.getData<DynamicFeedResponseDto>(FEED_URL, feedParams("video", offset))
        return when (result) {
            is BiliResult.Ok -> {
                val page = result.value
                val items = page.items.mapNotNull { it.toFeedItem() }
                val nextOffset = page.offset.ifEmpty { null }
                if (items.isEmpty() && page.hasMore && pagesLeft > 1) {
                    loadPage(nextOffset, pagesLeft - 1)
                } else {
                    BiliResult.Ok(FeedPage(items, nextOffset, page.hasMore))
                }
            }
            is BiliResult.ApiError -> result
            is BiliResult.Failure -> result
        }
    }

    private suspend fun loadCardPage(offset: String?, pagesLeft: Int): BiliResult<DynamicCardPage> {
        val result = client.getData<DynamicFeedResponseDto>(FEED_URL, feedParams("all", offset))
        return when (result) {
            is BiliResult.Ok -> {
                val page = result.value
                val items = page.items
                    .filterNot { it.type in VIDEO_TYPES }
                    .mapNotNull { it.toDynamicCard() }
                val nextOffset = page.offset.ifEmpty { null }
                // 这一页的筛选比首页狠得多(整页都是投稿视频是常态),所以自动翻页在这里更常
                // 触发,上限仍然是同一个:坏数据上不能无限递归。
                if (items.isEmpty() && page.hasMore && pagesLeft > 1) {
                    loadCardPage(nextOffset, pagesLeft - 1)
                } else {
                    BiliResult.Ok(DynamicCardPage(items, nextOffset, page.hasMore))
                }
            }

            is BiliResult.ApiError -> result
            is BiliResult.Failure -> result
        }
    }

    private fun feedParams(type: String, offset: String?): Map<String, String> = buildMap {
        put("type", type)
        offset?.let { put("offset", it) }
        put("features", BiliConstants.DYN_FEATURES)
    }

    /**
     * 五种视频类动态各自的 major 子对象在这里收敛成同一个 ArchiveDto(notes 第 5、6 节:
     * AV/UGC_SEASON/PGC/PGC_UNION/COURSES_SEASON 都复用同一套字段结构)。其余类型
     * (图文、文字、直播、转发……)一律丢弃,不是漏做,是 DESIGN 2.1「只显示视频类动态」的产品范围。
     *
     * DYNAMIC_TYPE_FORWARD 单独丢弃:转发的真实内容在 item.orig 里,要不要把"转发的视频"
     * 也算进投稿时间序需要产品侧决定(notes 第 6 节结论),而 DESIGN 2.1 已经定案「转发不
     * 混排」(转发会把时间序流变成半个广场),所以这里直接不解析 orig,不是技术限制。
     */
    private fun DynamicItemDto.toFeedItem(): FeedItem? {
        val archive = when (type) {
            "DYNAMIC_TYPE_AV" -> modules?.moduleDynamic?.major?.archive
            "DYNAMIC_TYPE_UGC_SEASON" -> modules?.moduleDynamic?.major?.ugcSeason
            "DYNAMIC_TYPE_PGC", "DYNAMIC_TYPE_PGC_UNION" -> modules?.moduleDynamic?.major?.pgc
            "DYNAMIC_TYPE_COURSES_SEASON" -> modules?.moduleDynamic?.major?.courses
            else -> null
        } ?: return null
        val author = modules?.moduleAuthor ?: return null
        // 笔记里 PiliPlus 在拿不到 bvid 时 fallback 到 epid(番剧类常见),但 epid 不是
        // bvid,播放页取流走的是 bvid 语义,塞进去会在下一层炸;按团队要求直接丢弃这条item。
        val bvid = archive.bvid?.takeIf { it.isNotBlank() } ?: return null
        return FeedItem(
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
         * 已经在首页出现过的两种。**番剧、课堂不在里面**:它们虽然也是"视频类",但首页那条
         * 路要求有 bvid,番剧只有 epid,于是一条都进不去 —— 不放到这一页来就等于整个看不见。
         */
        val VIDEO_TYPES = setOf("DYNAMIC_TYPE_AV", "DYNAMIC_TYPE_UGC_SEASON")
    }
}
