package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.player.QueueItem


/**
 * 建好的队列来源。**不带"当前是第几条"**:调用方按 bvid 自己定位
 * ([dev.bilby.player.PlaybackQueue.replaceKeeping])。下标在这里算好、到那边再用,中间隔着
 * 一次网络往返,列表随时可能已经整体挪了一位。
 */
data class QueueBuildResult(val items: List<QueueItem>, val sourceLabel: String)

/**
 * "听视频"播放队列的两个来源(DESIGN 2.4b):当前视频所属合集,或退化到 UP 空间投稿。
 * 判据是"有限且用户显式选定的集合"——合集本身有限;空间投稿是无底洞,所以只取当前视频
 * 前后各 25 条,不取全部。
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
     * 打开一条视频时的队列来源:先按合集,不属于合集才退到 UP 空间投稿。
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
        return fromSeason(bvid, detail) ?: fromUpSpace(bvid, detail)
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
        return QueueBuildResult(
            items = items,
            sourceLabel = "合集《${detail.seasonTitle}》· 共 ${items.size} 集",
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
            sourceLabel = "UP 主投稿 · 共 ${windowed.size} 条",
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
        const val PAGE_SIZE = 30 // loadArchives 固定 ps=30(SpaceRepository.kt)

        /** 二分的探测上限。2^12 页 = 12 万条投稿,够到不了;它挡的是列表非单调时的死循环。 */
        const val MAX_PROBES = 12

        /** 页号提示的容量。一次会话里来回打开的视频数量级就在这附近。 */
        const val PAGE_HINT_CACHE_SIZE = 128

        /**
         * 30 分钟。命中之后还要拉那一页验证一次,所以过期时间不必短;它挡的是"这个 UP 这半天
         * 发了太多稿,提示页号已经差出好几页"这种整体漂移。
         */
        val PAGE_HINT_TTL_NANOS = 30L * 60 * 1_000_000_000L
    }
}
