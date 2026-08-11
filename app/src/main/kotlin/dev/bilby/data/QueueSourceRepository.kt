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
     * 条数还额外不准:投稿、系列、动态取的是当前视频前后各 [QueueSourceRepository.WINDOW_HALF]
     * 条,写"共 N 条"时那个 N 是窗口大小,一个发过三百条的 UP 会显示"共 51 条"。
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

/** 队列来源的身份,够用来打开它的目录页([dev.bilby.ui.CollectionContents])。 */
data class QueueSource(
    val mid: Long,
    val id: Long,
    val isSeason: Boolean,
    val name: String,
)

/**
 * 播放队列的三个来源(DESIGN 2.4b),按顺序退化:当前视频所属合集 → UP 空间投稿 →
 * UP 动态。判据是"有限且用户显式选定的集合"——合集本身有限;投稿和动态都是无底洞,
 * 所以只取当前视频前后各 25 条,不取全部。
 *
 * 第三条是为**以动态形式发布的视频**加的:它们不在投稿列表里,只走前两条的话,从动态流
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
     * `mid:bvid → 页号`,给空间投稿的定位当起点。
     *
     * 二分定位一条老投稿要 log2(页数) 次请求(三千条投稿约 7 次),而同一条视频被反复打开是
     * 常态:退出再进、切集回来、通知栏切回来。
     *
     * **存页号而不是绝对下标**,因为页号能当场验证:把那一页拉回来看里面有没有它,没有就照常
     * 二分。下标没有这种自证方式,UP 发一条新稿它就整体错位,而错位的下标会安静地建出一份
     * 当前格是邻居的队列。
     */
    private val pageHints = ExpiringLruCache<String, Int>(PAGE_HINT_CACHE_SIZE, PAGE_HINT_TTL_NANOS)

    /**
     * 打开一条视频时的队列来源:先按合集,不属于合集退到 UP 空间投稿,投稿列表里也没有
     * (动态视频)再退到 UP 动态。
     *
     * 详情只取一次。合集要 `ugc_season`、空间定位要 `pubdate` 和 UP 的 mid,三样都在这一份
     * 详情里,分头去取等于为同一条视频问两遍。
     */
    suspend fun forVideo(bvid: String): QueueBuildResult? {
        val detail = when (val result = videoRepository.getVideoDetail(bvid)) {
            is BiliResult.Ok -> result.value
            else -> {
                BiliLog.w("听视频:取视频详情失败,无法建队列,bvid=$bvid")
                return null
            }
        }
        return fromSeason(bvid, detail)
            ?: fromUpSpace(bvid, detail)
            ?: fromSeries(bvid, detail)
            ?: fromUpDynamics(bvid, detail)
    }

    /**
     * 这位 UP 的**系列**里找。合集(season)那条已经由 [fromSeason] 用详情里的 `ugc_season`
     * 处理了,系列没有对应的字段 —— 详情根本不告诉你这条视频属于哪个系列。
     *
     * **直播回放走的正是这条路。** 那类稿件挂在一个系列下(常见的名字就叫「直播回放」),而且
     * `arc/search` 不返回它们:表现是从 UP 的合集页点进去能放,却建不出队列,永远只有一条。
     * 真机上用 BV12iuG6zEt5 复现过,它属于 series_id 5157110。
     *
     * 排在 [fromUpSpace] 之后:绝大多数视频在投稿列表里,那条路一次二分就定位到了,而这条
     * 要按系列逐个翻。排在 [fromUpDynamics] 之前:动态那条一页页往前走且翻不到就放弃,更贵
     * 也更容易空手而归。
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
            val from = (position - WINDOW_HALF).coerceAtLeast(0)
            val to = (position + WINDOW_HALF).coerceAtMost(videos.lastIndex)
            val windowed = videos.subList(from, to + 1)
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
        val from = (position - WINDOW_HALF).coerceAtLeast(0)
        val to = (position + WINDOW_HALF).coerceAtMost(videos.lastIndex)
        val windowed = videos.subList(from, to + 1)
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

    /**
     * 该 UP 的空间投稿,取当前视频前后各 25 条。
     *
     * 为什么是前后各 25:投稿动辄成百上千条,全量当队列等于没有边界,"还剩多少"这件事
     * 会重新变得无意义(DESIGN 2.4b 明确要能看见边界)。25 条是一个数得过来、UI 上
     * "N / M" 仍然有意义的量。
     */
    private suspend fun fromUpSpace(bvid: String, detail: VideoDetail): QueueBuildResult? {
        val mid = detail.up.mid
        if (mid == 0L) return null

        val pages = ArchivePageCache(mid)
        val index = pages.locate(bvid, detail.publishedAtEpochSeconds) ?: return null
        val total = pages.total

        // 目标下标两侧各 25 条,换算成页号;51 条最多横跨 3 页,其中一页定位时已经取过。
        val from = (index - WINDOW_HALF).coerceAtLeast(0)
        val to = (index + WINDOW_HALF).coerceAtMost(total - 1)
        val firstPage = from / PAGE_SIZE + 1
        val lastPage = to / PAGE_SIZE + 1
        val span = (firstPage..lastPage).map { pages.load(it) ?: return null }.flatten()

        // 位置在拼好的这几页里重新找一遍,不照下标算。定位与取窗口之间 UP 发了新稿的话整个
        // 列表会往后挪一位,按下标切会切出一条邻居 —— 而这份队列的当前格必须就是这条视频。
        val position = span.indexOfFirst { it.bvid == bvid }
        if (position < 0) {
            BiliLog.w("听视频:取窗口时投稿列表已变动,找不到 bvid=$bvid")
            return null
        }
        val windowFrom = (position - WINDOW_HALF).coerceAtLeast(0)
        val windowTo = (position + WINDOW_HALF).coerceAtMost(span.size - 1)
        val windowed = span.subList(windowFrom, windowTo + 1)
        return QueueBuildResult(
            items = windowed.map { it.toQueueItem() },
            sourceLabel = "UP 主投稿",
        )
    }

    /**
     * 空间投稿的分页取用,带一份本次调用内的页缓存 —— 定位命中的那一页多半也在窗口里,
     * 缓存省掉的就是这次重复请求。
     */
    private inner class ArchivePageCache(private val mid: Long) {
        private val pages = mutableMapOf<Int, List<SpaceVideoItem>>()

        /** 投稿总数,[load] 成功过之后才有意义。 */
        var total: Int = 0
            private set

        suspend fun load(page: Int): List<SpaceVideoItem>? {
            pages[page]?.let { return it }
            return when (val result = spaceRepository.loadArchives(mid, page, SpaceArchiveOrder.Pubdate)) {
                is BiliResult.Ok -> {
                    total = result.value.total
                    result.value.items.also { pages[page] = it }
                }
                else -> {
                    BiliLog.w("听视频:拉取空间投稿失败,mid=$mid,page=$page")
                    null
                }
            }
        }

        /**
         * 目标视频在全部投稿里的绝对下标。
         *
         * 列表按 pubdate 降序,所以页号可以直接二分:拿目标的 pubdate 跟某页首尾两条比一下,
         * 就知道该往新的一侧还是旧的一侧走。这里原先是从第 1 页顺序翻、最多翻 5 页(150 条),
         * 翻不到就降级成"从最新 50 条开始" —— 对机核这种日更 UP,一条 2021 年的投稿必定翻不到,
         * 于是每次都白发 5 次请求再拿到一份不含目标视频的队列。二分之后请求数是 log2(页数),
         * 三千条投稿约 7 次,且与视频有多老无关。
         */
        suspend fun locate(bvid: String, pubdate: Long): Int? {
            val hintKey = "$mid:$bvid"
            pageHints.get(hintKey)?.let { hinted ->
                val position = load(hinted)?.indexOfFirst { it.bvid == bvid } ?: -1
                if (position >= 0) return (hinted - 1) * PAGE_SIZE + position
                // 页号还在,但那一页已经没有它了(UP 又发了几条,把它挤到下一页)。当没命中处理,
                // 下面照常二分并把新页号写回去。load 的页缓存让这一页不会被再拉一次。
            }

            var low = 1
            var high = 1 // 先取第一页,才知道总共有多少页
            var probes = 0
            while (low <= high && probes < MAX_PROBES) {
                val page = (low + high) / 2
                val items = load(page) ?: return null
                probes++
                if (probes == 1) high = ((total + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
                if (items.isEmpty()) return null

                val position = items.indexOfFirst { it.bvid == bvid }
                if (position >= 0) {
                    pageHints.put(hintKey, page)
                    return (page - 1) * PAGE_SIZE + position
                }

                val newest = items.first().publishedAtEpochSeconds
                val oldest = items.last().publishedAtEpochSeconds
                when {
                    pubdate > newest -> high = page - 1
                    pubdate < oldest -> low = page + 1
                    // pubdate 落在本页区间内却不在本页:稿件已不在空间列表里(删除、仅自己可见、
                    // 或联合投稿只挂在另一个 UP 名下)。继续二分不会有结果。
                    else -> return null
                }
            }
            BiliLog.w("听视频:空间投稿里定位不到 bvid=$bvid(探测 $probes 次)")
            return null
        }
    }

    private fun SpaceVideoItem.toQueueItem() = QueueItem(
        bvid = bvid,
        cid = 0L, // 空间投稿接口不返回 cid,真正播放到这一条时由调用方补取,见 QueueItem 注释
        title = title,
        upName = "",
        coverUrl = coverUrl,
        durationSeconds = 0L, // durationText 是 "MM:SS" 格式字符串,这里不解析,播放端有需要再从详情补
    )

    private companion object {
        const val WINDOW_HALF = 25

        /** 最多看这位 UP 的前几个系列。列表按更新时间排,在播的那个系列排在前面。 */
        const val SERIES_SCAN_LIMIT = 4

        /** 扫系列总共最多发几次请求(每次 30 条)。够翻完一个几百条的直播回放系列的前段。 */
        const val SERIES_REQUEST_BUDGET = 8
        const val PAGE_SIZE = 30 // loadArchives 固定 ps=30(SpaceRepository.kt)

        /** 二分的探测上限。2^12 页 = 12 万条投稿,够到不了;它挡的是列表非单调时的死循环。 */
        const val MAX_PROBES = 12

        /**
         * 动态最多往前翻几页(一页约 12 条)。动态接口只有 offset 游标,没有页号也就没法二分,
         * 只能顺着往前走 —— 上限在这里,不是让它一路翻到 UP 注册那天。
         */
        const val DYNAMIC_SCAN_PAGES = 4

        /** 页号提示的容量。一次会话里来回打开的视频数量级就在这附近。 */
        const val PAGE_HINT_CACHE_SIZE = 128

        /**
         * 30 分钟。命中之后还要拉那一页验证一次,所以过期时间不必短;它挡的是"这个 UP 这半天
         * 发了太多稿,提示页号已经差出好几页"这种整体漂移。
         */
        val PAGE_HINT_TTL_NANOS = 30L * 60 * 1_000_000_000L
    }
}
