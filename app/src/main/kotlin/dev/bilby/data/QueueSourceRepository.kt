package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.player.QueueItem


data class QueueBuildResult(val items: List<QueueItem>, val startIndex: Int, val sourceLabel: String)

/**
 * "听视频"播放队列的两个来源(DESIGN 2.4b):当前视频所属合集,或退化到 UP 空间投稿。
 * 判据是"有限且用户显式选定的集合"——合集本身有限;空间投稿是无底洞,所以只取当前视频
 * 前后各 25 条,不取全部。
 */
class QueueSourceRepository(
    private val spaceRepository: SpaceRepository,
    private val videoRepository: VideoRepository,
) {

    /** 当前视频所属合集的全部分集。不属于合集时返回 null。 */
    suspend fun fromSeason(bvid: String): QueueBuildResult? {
        val detail = when (val result = videoRepository.getVideoDetail(bvid)) {
            is BiliResult.Ok -> result.value
            else -> {
                BiliLog.w("听视频:取视频详情失败,无法判断合集,bvid=$bvid")
                return null
            }
        }
        val episodes = detail.seasonEpisodes
        if (episodes.isEmpty()) return null // 不属于合集,调用方应退化到 fromUpSpace

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
            startIndex = startIndex,
            sourceLabel = "合集《${detail.seasonTitle}》· 共 ${items.size} 集",
        )
    }

    /**
     * 该 UP 的空间投稿,取当前视频前后各 25 条。currentBvid 为 null 时从最新开始取 50 条。
     *
     * 为什么是前后各 25:投稿动辄成百上千条,全量当队列等于没有边界,"还剩多少"这件事
     * 会重新变得无意义(DESIGN 2.4b 明确要能看见边界)。25 条是一个数得过来、UI 上
     * "N / M" 仍然有意义的量。
     *
     * 空间投稿接口(loadArchives)按页返回,当前视频可能落在任意一页。做法是按 pubdate
     * 从第 1 页起顺序拉页、在页内线性找 bvid;找到后再往前/后补页凑够各 25 条。
     * 翻页设了上限(MAX_LOCATE_PAGES,单页 30 条,5 页覆盖 150 条投稿):真找不到时,
     * 继续翻下去只会把一次"听视频"点击变成好几次网络请求还不一定有结果,不值得为了
     * 定位一条冷门视频而无限翻页,直接退化成"从最新取 50 条"并在 sourceLabel 里说明。
     */
    suspend fun fromUpSpace(mid: Long, currentBvid: String?): QueueBuildResult? {
        if (currentBvid == null) {
            val page = loadArchivePage(mid, 1) ?: return null
            val items = page.items.take(50).map { it.toQueueItem() }
            if (items.isEmpty()) return null
            return QueueBuildResult(items, startIndex = 0, sourceLabel = spaceFallbackLabel(items.size))
        }

        val collected = mutableListOf<SpaceVideoItem>()
        var currentIndexInAll = -1
        var pageNum = 1
        while (pageNum <= MAX_LOCATE_PAGES) {
            val page = loadArchivePage(mid, pageNum) ?: break
            if (page.items.isEmpty()) break
            val posInPage = page.items.indexOfFirst { it.bvid == currentBvid }
            collected += page.items
            if (posInPage >= 0) {
                currentIndexInAll = collected.size - page.items.size + posInPage
                break
            }
            if (collected.size >= page.totalHint || page.items.size < PAGE_SIZE) break // 已到最后一页
            pageNum++
        }

        if (currentIndexInAll < 0) {
            // 翻了 MAX_LOCATE_PAGES 页仍未定位到,退化:从最新取 50 条。
            val fallbackItems = (collected.ifEmpty { loadArchivePage(mid, 1)?.items.orEmpty() }).take(50)
            if (fallbackItems.isEmpty()) return null
            return QueueBuildResult(
                items = fallbackItems.map { it.toQueueItem() },
                startIndex = 0,
                sourceLabel = spaceFallbackLabel(fallbackItems.size, degraded = true),
            )
        }

        val from = (currentIndexInAll - WINDOW_HALF).coerceAtLeast(0)
        val to = (currentIndexInAll + WINDOW_HALF).coerceAtMost(collected.size - 1)
        val windowed = collected.subList(from, to + 1)
        return QueueBuildResult(
            items = windowed.map { it.toQueueItem() },
            startIndex = currentIndexInAll - from,
            sourceLabel = "UP 主投稿 · 共 ${windowed.size} 条",
        )
    }

    private data class ArchivePageResult(val items: List<SpaceVideoItem>, val totalHint: Int)

    private suspend fun loadArchivePage(mid: Long, page: Int): ArchivePageResult? =
        when (val result = spaceRepository.loadArchives(mid, page, SpaceArchiveOrder.Pubdate)) {
            is BiliResult.Ok -> ArchivePageResult(result.value.items, result.value.total)
            else -> {
                BiliLog.w("听视频:拉取空间投稿失败,mid=$mid,page=$page")
                null
            }
        }

    private fun spaceFallbackLabel(count: Int, degraded: Boolean = false): String =
        if (degraded) "UP 主的投稿 · 未定位到当前视频,从最新 $count 条开始" else "UP 主的投稿 · $count 条"

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
        const val MAX_LOCATE_PAGES = 5 // 覆盖 150 条投稿,足够定位绝大多数场景;超过则退化
    }
}
