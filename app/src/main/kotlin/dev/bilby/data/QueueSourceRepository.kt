package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.player.QueueItem


/**
 * 建好的队列来源。**不带"当前是第几条"**:调用方按 bvid 自己定位
 * ([dev.bilby.player.PlaybackQueue.replaceKeeping])。下标在这里算好、到那边再用,中间隔着
 * 一次网络往返,列表随时可能已经整体挪了一位。
 */
data class QueueBuildResult(
    val items: List<QueueItem>,
    /**
     * 队列来源那一行:合集/系列**用它自己的名字**(「直播回放」「AI 早报」),其余两种来源是
     * 「UP 主投稿」「UP 主动态」。
     *
     * **不带条数,也不加"合集"「《》」这类前缀与包装。** 这一行还要并排放下找相关、缓存、
     * 顺序三个操作,每多一截就挤掉一截名字,而名字才是这行里唯一说明"在放什么"的东西。
     * 条数还额外不准:投稿取最新 25 条,系列和动态取当前视频前后各 12 条,写"共 N 条"时那个 N
     * 是窗口大小,一个发过三百条的 UP 会显示"共 25 条"。
     *
     * 队列的边界另有出处:听视频那边顶着 `N / M`(ListenScreen),队列列表本身也滚得到底。
     */
    val sourceLabel: String,
    /**
     * 这份队列取自哪个合集/系列。**为空表示来源没有对应的页面**(UP 投稿、UP 动态),
     * 播放页那行标题因此点不动 —— 那两种来源是这里为了凑出一份有边界的队列现算的窗口,
     * 不是用户能在别处打开的一份目录,给它一个入口等于凭空发明一个页面。
     */
    val source: QueueSource? = null,
)

/**
 * 以 [position] 为中心、前后各取 [half] 条的窗口。系列与动态两条来源共用它。
 *
 * **两端不补齐。** 当前视频排在第 2 条时窗口就是 15 条,不会为了凑够 25 而往后多要 10 条——
 * 那 10 条并不比别的更该在队列里,而"当前这条在队列中间"本来就只是常态,不是要求。
 *
 * 边界值是这里唯一容易写错的东西:[position] 落在两端时 `subList` 的上下界都得夹住,
 * 差一位就是 `IndexOutOfBounds`,而它只在"这条视频恰好是最新或最旧的一条"时才发作。
 */
internal fun <T> List<T>.windowAround(position: Int, half: Int): List<T> {
    val from = (position - half).coerceAtLeast(0)
    val to = (position + half).coerceAtMost(lastIndex)
    return subList(from, to + 1)
}

/** 队列来源的身份,够用来打开它的目录页([dev.bilby.ui.CollectionContents])。 */
data class QueueSource(
    val mid: Long,
    val id: Long,
    val isSeason: Boolean,
    val name: String,
)

/**
 * 播放队列的来源,按顺序退化:当前视频所属合集 → 所属系列 → UP 最新投稿 → UP 动态 →
 * 最新投稿加这一条。判据是"有限且用户显式选定的集合"——合集与系列本身有限;投稿和动态
 * 都是无底洞,所以只取一个 25 条的窗口,不取全部。
 *
 * 动态那条是为**以动态形式发布的视频**加的:它们不在投稿列表里,没有它的话,从动态流
 * 点进去的视频永远只有孤零零一条。
 *
 * **建出来的队列一定含有当前这条视频,否则返回 null。** 调用方拿到队列后会把页面带来的 cid
 * 写到当前那一格上,队列里没有这条视频时那个 cid 就落到了别人头上 —— bvid 与 cid 分属两条
 * 视频,playurl 回 -404「啥都木有」。所以这里宁可返回 null(调用方退成单条队列),也不返回
 * 一份"差不多的"队列。
 */
class QueueSourceRepository(
    private val spaceRepository: SpaceRepository,
    private val videoRepository: VideoRepository,
) {

    /**
     * 打开一条视频时的队列来源,按"这份集合有多像用户自己选的"排:所属合集 → 所属系列 →
     * UP 最新投稿 → UP 动态 → 最新投稿加这一条。
     *
     * **系列排在最新投稿之前。** 系列是 UP 归拢出来的一份目录,最新投稿只是按发布时间现编的
     * 邻居,两者都含有这条视频时该赢的是前者。代价是每条不属于合集的视频都要先问一次系列
     * 列表——但那一次就足以判空,没有系列的 UP 只多花一次请求,真正贵的翻页只发生在有系列的
     * UP 身上。
     *
     * 详情只取一次。合集要 `ugc_season`、其余几条要 UP 的 mid,都在这一份详情里,分头去取
     * 等于为同一条视频问两遍。最新投稿那一页同理只拉一次:它既是一条来源,也是最后的兜底。
     */
    suspend fun forVideo(bvid: String): QueueBuildResult? {
        val detail = when (val result = videoRepository.getVideoDetail(bvid)) {
            is BiliResult.Ok -> result.value
            else -> {
                BiliLog.w("听视频:取视频详情失败,无法建队列,bvid=$bvid")
                return null
            }
        }
        fromSeason(bvid, detail)?.let { return it }
        fromSeries(bvid, detail)?.let { return it }

        val recent = recentArchives(detail.up.mid)
        if (recent != null && recent.take(RECENT_COUNT).any { it.bvid == bvid }) {
            return QueueBuildResult(
                items = recent.take(RECENT_COUNT).map { it.toQueueItem() },
                sourceLabel = "UP 主投稿",
            )
        }

        // 动态排在兜底之前:以动态形式发的视频不进 `arc/search`,而兜底会无条件成立,
        // 放它前面就把这条路吃掉了。
        fromUpDynamics(bvid, detail)?.let { return it }

        if (recent == null) return null
        // 这条视频够老,不在最新 [RECENT_COUNT] 条里,也不属于任何系列或最近的动态。补最新的
        // 24 条再把它接在队尾,凑够 [RECENT_COUNT] —— 每种来源都是同一个量级,「N / M」里的 M
        // 才不会随来源跳变。接在队尾是因为队列的当前格必须就是它(见类注释),而按发布时间
        // 它本就排在这批的后面。
        return QueueBuildResult(
            items = recent.take(RECENT_COUNT - 1).map { it.toQueueItem() } + detail.toQueueItem(),
            sourceLabel = "UP 主投稿",
        )
    }

    /**
     * 这位 UP 最新的一页投稿(30 条),取不到(含 mid 缺席)返回 null。调用方按需截取:
     * 当前视频在其中时取 [RECENT_COUNT] 条,不在时取 [RECENT_COUNT] - 1 条再补上它自己。
     *
     * **只拉第一页。** 这里原先是按 pubdate 二分定位当前视频、再取前后各 25 条的窗口,前提是
     * "页号 → 内容"单调;而服务端会把过深的 `pn` 夹到一个可达上限,再往后翻永远返回同一页,
     * 前提就没了。索尼音乐中国(mid 486906719)身上实测:total 报 257561(8586 页),
     * pn=4294 与 pn=6440 返回一模一样的一页,2020 年那条投稿七次探测全部白发,最后仍是单条
     * 队列。二分的收益本来就只在"一条老投稿的邻居是谁"这件事上,而那个邻居关系是按发布时间
     * 现编的,不是用户选定的集合——真正成套的内容在合集和系列里,那两条路不受影响。
     */
    private suspend fun recentArchives(mid: Long): List<SpaceVideoItem>? {
        if (mid == 0L) return null
        return when (val result = spaceRepository.loadArchives(mid, 1, SpaceArchiveOrder.Pubdate)) {
            is BiliResult.Ok -> result.value.items.takeIf { it.isNotEmpty() }
            else -> {
                BiliLog.w("队列:拉取空间投稿失败,mid=$mid")
                null
            }
        }
    }

    /**
     * 这位 UP 的**系列**里找。合集(season)那条已经由 [fromSeason] 用详情里的 `ugc_season`
     * 处理了,系列没有对应的字段 —— 详情根本不告诉你这条视频属于哪个系列。
     *
     * **直播回放走的正是这条路。** 那类稿件挂在一个系列下(常见的名字就叫「直播回放」),而且
     * `arc/search` 不返回它们:表现是从 UP 的合集页点进去能放,却建不出队列,永远只有一条。
     * 真机上用 BV12iuG6zEt5 复现过,它属于 series_id 5157110。
     *
     * 排在最新投稿之前的理由见 [forVideo]:系列是一份目录,最新投稿只是时间上的邻居。
     *
     * **有请求预算,翻不完就放弃并记一行。** 一个 UP 可能有几十个系列,每个几百条;为一条
     * 视频把它们全翻一遍是拿风控换一个"说不定能建出来"。翻不到就老实停在单条队列 ——
     * 那是"这条视频没有可确定的所属集合"的诚实结果。
     */
    private suspend fun fromSeries(bvid: String, detail: VideoDetail): QueueBuildResult? {
        val mid = detail.up.mid
        if (mid == 0L) return null

        val collections = when (val result = spaceRepository.loadCollections(mid, 1)) {
            is BiliResult.Ok -> result.value.items.filterNot { it.isSeason }
            else -> {
                BiliLog.w("队列:拉取 UP 合集系列列表失败,mid=$mid")
                return null
            }
        }
        if (collections.isEmpty()) return null

        var budget = SERIES_REQUEST_BUDGET
        for (series in collections.take(SERIES_SCAN_LIMIT)) {
            val videos = mutableListOf<SpaceVideoItem>()
            var page = 1
            while (budget > 0) {
                budget--
                val loaded = when (
                    val result = spaceRepository.loadCollectionDetail(mid, series.id, series.isSeason, page)
                ) {
                    is BiliResult.Ok -> result.value
                    else -> {
                        BiliLog.w("队列:拉取系列 ${series.id} 第 $page 页失败")
                        break
                    }
                }
                videos += loaded.items
                if (videos.any { it.bvid == bvid } || videos.size >= loaded.total || loaded.items.isEmpty()) break
                page++
            }

            val position = videos.indexOfFirst { it.bvid == bvid }
            if (position < 0) continue
            val windowed = videos.windowAround(position, WINDOW_HALF)
            return QueueBuildResult(
                items = windowed.map { it.toQueueItem() },
                // 系列的真实条数用接口给的 total,不用 videos.size:后者是翻到当前视频为止
                // 拉了多少,预算耗尽时还会更少。
                sourceLabel = series.name,
                source = QueueSource(mid, series.id, series.isSeason, series.name),
            )
        }
        BiliLog.w("队列:UP 的前 $SERIES_SCAN_LIMIT 个系列(预算 $SERIES_REQUEST_BUDGET 次请求)里没有 bvid=$bvid")
        return null
    }

    /**
     * 以**动态**形式发的视频不在投稿列表里,所以 [fromUpSpace] 那条二分永远找不到它,
     * 队列就停在只有一条。这类视频从动态流点进来是常态,一进去就"没有下一条"。
     *
     * 退到这位 UP 自己的动态里找。它和投稿是同一件事的两个列表(都是"这个人发的东西"),
     * 判据没变:仍然是用户显式选定的那个人,仍然有边界。
     *
     * **只翻 [DYNAMIC_SCAN_PAGES] 页就放弃。** 动态接口是 offset 游标不是页号,没法二分,
     * 只能一页页往前走;而一条两年前的动态视频要翻上百页。翻不到就老实停在单条队列 ——
     * 那是"这条视频没有可确定的所属集合"的诚实结果,不是退到推荐池的理由。
     */
    private suspend fun fromUpDynamics(bvid: String, detail: VideoDetail): QueueBuildResult? {
        val mid = detail.up.mid
        if (mid == 0L) return null

        val videos = mutableListOf<SpaceVideoItem>()
        var offset: String? = null
        for (round in 1..DYNAMIC_SCAN_PAGES) {
            val page = when (val result = spaceRepository.loadDynamics(mid, offset)) {
                is BiliResult.Ok -> result.value
                else -> {
                    BiliLog.w("队列:拉取 UP 动态失败,mid=$mid")
                    return null
                }
            }
            videos += page.items.filterIsInstance<SpaceDynamicItem.Video>().map { it.item }
            offset = page.nextOffset
            // 找到了就停:动态按时间倒序,再往前只会更旧。游标没了也停 —— 拿着 null 再请求
            // 一次等于把第一页重新拉一遍,窗口里会出现两份同样的条目。
            if (videos.any { it.bvid == bvid } || !page.hasMore || offset == null) break
        }

        val position = videos.indexOfFirst { it.bvid == bvid }
        if (position < 0) {
            BiliLog.w("队列:UP 动态前 $DYNAMIC_SCAN_PAGES 页里没有 bvid=$bvid,保持单条队列")
            return null
        }
        val windowed = videos.windowAround(position, WINDOW_HALF)
        return QueueBuildResult(
            items = windowed.map { it.toQueueItem() },
            sourceLabel = "UP 主动态",
        )
    }

    /** 当前视频所属合集的全部分集。不属于合集时返回 null,由调用方退到空间投稿。 */
    private fun fromSeason(bvid: String, detail: VideoDetail): QueueBuildResult? {
        val episodes = detail.seasonEpisodes
        if (episodes.isEmpty()) return null

        val startIndex = episodes.indexOfFirst { it.bvid == bvid }
        if (startIndex < 0) {
            BiliLog.w("听视频:合集分集里找不到当前 bvid=$bvid,数据不一致")
            return null
        }
        val items = episodes.map { ep ->
            QueueItem(
                bvid = ep.bvid,
                cid = ep.cid,
                title = ep.title,
                upName = detail.up.name,
                coverUrl = ep.coverUrl,
                durationSeconds = ep.durationSeconds,
            )
        }
        // 合集归属 mid 缺席时退回作者的 mid:目录页要这两样才打得开,取不到就不给入口,
        // 而不是拿一个说不定是错的 mid 建一个点进去是空目录的链接。
        val seasonMid = detail.seasonMid.takeIf { it != 0L } ?: detail.up.mid
        return QueueBuildResult(
            items = items,
            // 合集这条不开窗:详情里的 ugc_season 给的就是全部分集,"共"名副其实。
            sourceLabel = detail.seasonTitle,
            source = if (detail.seasonId != 0L && seasonMid != 0L) {
                QueueSource(seasonMid, detail.seasonId, isSeason = true, name = detail.seasonTitle)
            } else {
                null
            },
        )
    }

    private fun VideoDetail.toQueueItem() = QueueItem(
        bvid = bvid,
        cid = cid,
        title = title,
        upName = up.name,
        coverUrl = coverUrl,
        durationSeconds = durationSeconds,
    )

    private fun SpaceVideoItem.toQueueItem() = QueueItem(
        bvid = bvid,
        cid = 0L, // 空间投稿接口不返回 cid,真正播放到这一条时由调用方补取,见 QueueItem 注释
        title = title,
        upName = "",
        coverUrl = coverUrl,
        durationSeconds = 0L, // durationText 是 "MM:SS" 格式字符串,这里不解析,播放端有需要再从详情补
    )

    private companion object {
        /** 系列与动态开窗时,当前视频前后各取几条。连同自己一共 25 条,与 [RECENT_COUNT] 齐平。 */
        const val WINDOW_HALF = 12

        /** 最新投稿取几条。一个数得过来、UI 上「N / M」仍然有意义的量。 */
        const val RECENT_COUNT = 25

        /** 最多看这位 UP 的前几个系列。列表按更新时间排,在播的那个系列排在前面。 */
        const val SERIES_SCAN_LIMIT = 4

        /** 扫系列总共最多发几次请求(每次 30 条)。够翻完一个几百条的直播回放系列的前段。 */
        const val SERIES_REQUEST_BUDGET = 8

        /**
         * 动态最多往前翻几页(一页约 12 条)。动态接口只有 offset 游标,没有页号也就没法二分,
         * 只能顺着往前走 —— 上限在这里,不是让它一路翻到 UP 注册那天。
         */
        const val DYNAMIC_SCAN_PAGES = 4
    }
}
