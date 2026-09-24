package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.offline.OfflineStatus
import dev.bilby.offline.OfflineStore
import dev.bilby.player.QueueItem

/**
 * 打开好的一份队列:当前视频附近的一段、来源名、目录入口,以及往两头续取用的 [feed]。
 * **不带"当前是第几条"**:调用方按 bvid 自己定位,中间隔着网络往返,下标随时可能已经挪了。
 */
class OpenedQueue(
    val items: List<QueueItem>,
    /**
     * 队列标题行:合集/系列、收藏夹**用它自己的名字**,其余是固定的几种说法。
     *
     * **不带条数,也不加"合集"「《》」这类前缀与包装。** 这一行还要并排放下找相关、缓存、
     * 顺序三个操作,每多一截就挤掉一截名字。条数另有出处(听视频页的 `N / M`)。
     */
    val label: String,
    /** 来源有目录页时非空,标题行可以点进去。 */
    val source: QueueSource?,
    val feed: QueueFeed,
)

/** 队列来源的身份,够用来打开它的目录页([dev.bilby.ui.CollectionContents])。 */
data class QueueSource(
    val mid: Long,
    val id: Long,
    val isSeason: Boolean,
    val name: String,
)

/**
 * 按入口上下文([QueueContext])打开队列。**点进去之前看到什么列表,队列就是什么列表**
 * (docs/queue-redesign.md 决定 1),只有 [QueueContext.Affiliation] 按这条视频自己的归属找。
 *
 * **返回的队列一定含有当前这条视频,否则返回 null。** 调用方是拿它去补全一份已经在播的
 * 队列,换成一份不含它的列表,队列面板高亮的就是别人,下一条也接错了地方。
 */
class QueueSourceRepository(
    private val spaceRepository: SpaceRepository,
    private val videoRepository: VideoRepository,
    private val favRepository: FavRepository,
    private val toViewRepository: ToViewRepository,
    private val offlineStore: OfflineStore,
) {

    suspend fun open(context: QueueContext, bvid: String): OpenedQueue? = when (context) {
        QueueContext.Affiliation -> affiliation(bvid)
        is QueueContext.Collection -> openFeed(
            feed = collectionFeed(context.mid, context.id, context.isSeason, context.page),
            bvid = bvid,
            label = context.name,
            source = QueueSource(context.mid, context.id, context.isSeason, context.name),
        )
        is QueueContext.UpArchive -> openFeed(
            feed = PagedFeed(context.page, SpaceRepository.ARCHIVE_PAGE_SIZE) { page ->
                when (val r = spaceRepository.loadArchives(context.mid, page, context.order, context.keyword)) {
                    is BiliResult.Ok -> FeedPage(
                        items = r.value.items.map { it.toQueueItem() },
                        hasMore = page * SpaceRepository.ARCHIVE_PAGE_SIZE < r.value.total,
                        total = r.value.total,
                    )
                    else -> null.also { BiliLog.w("队列:拉取投稿第 $page 页失败 mid=${context.mid}") }
                }
            },
            bvid = bvid,
            label = upArchiveLabel(context),
            source = null,
        )
        is QueueContext.FavFolder -> openFeed(
            feed = PagedFeed(context.page, FavRepository.Paging.PAGE_SIZE) { page ->
                when (val r = favRepository.folderContents(context.mediaId, page)) {
                    // 失效稿件在收藏夹页照常列出(点不动),队列里放不了,不收。
                    is BiliResult.Ok -> FeedPage(
                        items = r.value.items.filterNot { it.invalid }.map {
                            QueueItem(it.bvid, it.title, it.upName, it.coverUrl, it.durationSeconds)
                        },
                        hasMore = r.value.hasMore,
                    )
                    else -> null.also { BiliLog.w("队列:拉取收藏夹第 $page 页失败 id=${context.mediaId}") }
                }
            },
            bvid = bvid,
            label = context.title,
            source = null,
        )
        QueueContext.ToView -> {
            when (val r = toViewRepository.loadList()) {
                is BiliResult.Ok -> openFeed(
                    feed = StaticFeed(
                        r.value.items.map {
                            QueueItem(it.bvid, it.title, it.upName, it.coverUrl, parseDurationText(it.durationText))
                        },
                    ),
                    bvid = bvid,
                    label = "稍后再看",
                    source = null,
                )
                else -> null.also { BiliLog.w("队列:拉取稍后再看失败") }
            }
        }
        QueueContext.Offline -> offline(bvid)
    }

    /**
     * 缓存库。**不联网**,所以它也是本地有副本的视频在离线时的退路(调用方决定)。
     *
     * 缓存库按 (bvid, cid) 一 P 一条,队列行是视频不是分 P:不收拢的话同一视频缓了两个 P 就是
     * 两行同 bvid,队列面板拿 bvid 当 LazyColumn key,真机上直接崩。
     */
    suspend fun offline(bvid: String): OpenedQueue? {
        val cached = offlineStore.list()
            .filter { it.status == OfflineStatus.Completed }
            .sortedByDescending { it.createdAtMillis }
            .distinctBy { it.bvid }
        if (cached.none { it.bvid == bvid }) return null
        return openFeed(
            feed = StaticFeed(
                cached.map { QueueItem(it.bvid, it.title, it.upName, it.coverUrl, it.durationSeconds) },
            ),
            bvid = bvid,
            label = "已缓存",
            source = null,
        )
    }

    private suspend fun openFeed(feed: QueueFeed, bvid: String, label: String, source: QueueSource?): OpenedQueue? {
        val items = feed.open(bvid) ?: return null
        return OpenedQueue(items, label, source, feed)
    }

    private fun upArchiveLabel(context: QueueContext.UpArchive): String = when {
        context.keyword.isNotBlank() -> "投稿搜索「${context.keyword}」"
        context.order == SpaceArchiveOrder.Click -> "UP 主投稿（最多播放）"
        else -> "UP 主投稿"
    }

    private fun collectionFeed(mid: Long, id: Long, isSeason: Boolean, anchorPage: Int) =
        PagedFeed(anchorPage, SpaceRepository.COLLECTION_PAGE_SIZE) { page ->
            when (val r = spaceRepository.loadCollectionDetail(mid, id, isSeason, page)) {
                is BiliResult.Ok -> FeedPage(
                    items = r.value.items.map { it.toQueueItem() },
                    hasMore = page * SpaceRepository.COLLECTION_PAGE_SIZE < r.value.total && r.value.items.isNotEmpty(),
                    total = r.value.total,
                )
                else -> null.also { BiliLog.w("队列:拉取合集/系列 $id 第 $page 页失败") }
            }
        }

    /**
     * 这条视频自己的归属:所属合集 → 所属系列 → UP 投稿里它前后的邻居。
     *
     * **系列排在投稿邻居之前。** 系列是 UP 归拢出来的一份目录,投稿邻居只是按发布时间排在它
     * 旁边的那些,两者都含有这条视频时该赢的是前者。
     *
     * 三条都不成立(动态视频不进投稿列表,直播回放又没被归进前几个系列)就返回 null,调用方
     * 留在单条队列。这是"这条视频没有可确定的所属集合"的诚实结果。
     */
    private suspend fun affiliation(bvid: String): OpenedQueue? {
        val detail = when (val result = videoRepository.getVideoDetail(bvid)) {
            is BiliResult.Ok -> result.value
            else -> {
                BiliLog.w("队列:取视频详情失败,无法找归属 bvid=$bvid")
                return null
            }
        }
        fromSeason(bvid, detail)?.let { return it }
        fromSeries(bvid, detail)?.let { return it }
        val mid = detail.up.mid
        if (mid == 0L) return null
        return openFeed(
            feed = ArchiveCursorFeed { aid, newer, includeCursor ->
                when (val r = spaceRepository.loadArchiveCursor(mid, aid, newer, includeCursor)) {
                    is BiliResult.Ok -> r.value
                    else -> null
                }
            },
            bvid = bvid,
            label = "UP 主投稿",
            source = null,
        ) ?: null.also { BiliLog.w("队列:bvid=$bvid 不在 UP 投稿里,也不属于合集或前几个系列") }
    }

    /** 详情里的 `ugc_season` 给的就是全部分集,不开窗。 */
    private suspend fun fromSeason(bvid: String, detail: VideoDetail): OpenedQueue? {
        val episodes = detail.seasonEpisodes
        if (episodes.isEmpty()) return null
        val items = episodes.map { ep ->
            QueueItem(
                bvid = ep.bvid,
                title = ep.title,
                upName = detail.up.name,
                coverUrl = ep.coverUrl,
                durationSeconds = ep.durationSeconds,
            )
        }
        // 合集归属 mid 缺席时退回作者的 mid:目录页要这两样才打得开,取不到就不给入口,
        // 而不是拿一个说不定是错的 mid 建一个点进去是空目录的链接。
        val seasonMid = detail.seasonMid.takeIf { it != 0L } ?: detail.up.mid
        val source = if (detail.seasonId != 0L && seasonMid != 0L) {
            QueueSource(seasonMid, detail.seasonId, isSeason = true, name = detail.seasonTitle)
        } else {
            null
        }
        return openFeed(StaticFeed(items), bvid, detail.seasonTitle, source)
            ?: null.also { BiliLog.w("队列:合集分集里找不到当前 bvid=$bvid,数据不一致") }
    }

    /**
     * 这位 UP 的**系列**里找。详情不告诉你这条视频属于哪个系列,只能翻。
     *
     * **直播回放走的正是这条路。** 那类稿件挂在一个系列下,而投稿列表不返回它们(notes 1.4.3
     * 的三类视频表)。真机上用 BV12iuG6zEt5 复现过,它属于 series_id 5157110。
     *
     * **有请求预算,翻不完就放弃并记一行。** 一个 UP 可能有几十个系列,每个几百条;为一条视频
     * 把它们全翻一遍是拿风控换一个"说不定能建出来"。
     */
    private suspend fun fromSeries(bvid: String, detail: VideoDetail): OpenedQueue? {
        val mid = detail.up.mid
        if (mid == 0L) return null
        val collections = when (val result = spaceRepository.loadCollections(mid, 1)) {
            is BiliResult.Ok -> result.value.items.filterNot { it.isSeason }
            else -> {
                BiliLog.w("队列:拉取 UP 合集系列列表失败,mid=$mid")
                return null
            }
        }
        var budget = SERIES_REQUEST_BUDGET
        for (series in collections.take(SERIES_SCAN_LIMIT)) {
            var page = 1
            while (budget > 0) {
                budget--
                val loaded = when (val r = spaceRepository.loadCollectionDetail(mid, series.id, false, page)) {
                    is BiliResult.Ok -> r.value
                    else -> {
                        BiliLog.w("队列:拉取系列 ${series.id} 第 $page 页失败")
                        break
                    }
                }
                if (loaded.items.any { it.bvid == bvid }) {
                    // 找到的那一页就是锚点。feed 会把这一页再取一次:多一次请求,换来翻页逻辑
                    // 只有 PagedFeed 一份。
                    return openFeed(
                        feed = collectionFeed(mid, series.id, isSeason = false, anchorPage = page),
                        bvid = bvid,
                        label = series.name,
                        source = QueueSource(mid, series.id, isSeason = false, name = series.name),
                    )
                }
                if (page * SpaceRepository.COLLECTION_PAGE_SIZE >= loaded.total || loaded.items.isEmpty()) break
                page++
            }
        }
        if (collections.isNotEmpty()) {
            BiliLog.w("队列:UP 的前 $SERIES_SCAN_LIMIT 个系列(预算 $SERIES_REQUEST_BUDGET 次请求)里没有 bvid=$bvid")
        }
        return null
    }

    private fun SpaceVideoItem.toQueueItem() = QueueItem(
        bvid = bvid,
        title = title,
        upName = "",
        coverUrl = coverUrl,
        durationSeconds = parseDurationText(durationText),
    )

    private companion object {
        /** 最多看这位 UP 的前几个系列。列表按更新时间排,在播的那个系列排在前面。 */
        const val SERIES_SCAN_LIMIT = 4

        /** 扫系列总共最多发几次请求(每次 30 条)。够翻完一个几百条的直播回放系列的前段。 */
        const val SERIES_REQUEST_BUDGET = 8
    }
}

/**
 * `"12:34"` / `"1:02:03"` 换成秒,认不出时给 0。投稿列表与稍后再看给的时长是这种字符串
 * (合集详情给的是数值秒,不走这里)。队列面板要显示时长,0 表示不显示。
 */
internal fun parseDurationText(text: String): Long {
    val parts = text.trim().split(':').map { it.toLongOrNull() ?: return 0L }
    if (parts.size !in 2..3) return 0L
    return parts.fold(0L) { total, part -> total * 60 + part }
}
