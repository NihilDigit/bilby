package dev.bilby.data.db

import dev.bilby.data.model.FeedItem

/** [FeedCacheRepository.restore] 的结果:恢复出来的列表本身,以及继续往下翻页要用的游标。 */
data class CachedFeed(
    val items: List<FeedItem>,
    val nextOffset: String?,
    val hasMore: Boolean,
)

private const val MAX_CACHE_SIZE = 500

/**
 * 动态流本地缓存,包一层 [FeedCacheItemDao] / [FeedCacheCursorDao]。**只负责存取,不负责
 * 决定什么时候该更新游标**——那是 [FeedViewModel][dev.bilby.ui.feed.FeedViewModel] 的事:
 * 它知道当前这次网络请求是"缓存本来就是空的、这就是第一页"还是"缓存已经翻到更深的地方、
 * 这只是头部刷新",这里没有足够信息替它做这个判断。
 */
class FeedCacheRepository(
    private val itemDao: FeedCacheItemDao,
    private val cursorDao: FeedCacheCursorDao,
) {

    suspend fun restore(): CachedFeed {
        val items = itemDao.loadAllOrdered().map { it.toFeedItem() }
        val cursor = cursorDao.get()
        return CachedFeed(items, cursor?.nextOffset, cursor?.hasMore ?: true)
    }

    /**
     * 头部合并(首屏拉到的第一页 / 下拉刷新):已经缓存过的条目原地更新字段(播放量之类会变),
     * 不改变它的位置;真正没见过的条目按抓取里的相对顺序整体插到最前面。
     *
     * **不动游标。** 头部请求用的 offset 永远是 null(拿的是最新第一页),它返回的 nextOffset
     * 只对应"这一页之后的下一页",而缓存里可能已经翻到比这深得多的地方——覆盖了游标,翻到
     * 缓存底部时就会把中间已经翻过的页重新请求一遍。游标只由 [appendTail] / [advanceCursor] 改。
     */
    suspend fun mergeHead(items: List<FeedItem>) {
        if (items.isEmpty()) return
        val existing = itemDao.sortIndicesFor(items.map { it.bvid }).associate { it.bvid to it.sortIndex }
        var nextHeadIndex = itemDao.minSortIndex() ?: 0L
        val entities = items.map { item ->
            val sortIndex = existing[item.bvid] ?: run { nextHeadIndex -= 1; nextHeadIndex }
            item.toEntity(sortIndex)
        }
        itemDao.upsertAll(entities)
        itemDao.trimToNewest(MAX_CACHE_SIZE)
    }

    /** 尾部续接(翻页):这一页理应全是没见过的旧条目,按抓取顺序接在已有最旧一条后面;万一
     * 服务端在翻页边界上给出了重复的条目,保留它原来的位置而不是又插一份。翻页本身就是在
     * 推进游标,请求成功就直接落盘。 */
    suspend fun appendTail(items: List<FeedItem>, nextOffset: String?, hasMore: Boolean) {
        if (items.isNotEmpty()) {
            val existing = itemDao.sortIndicesFor(items.map { it.bvid }).associate { it.bvid to it.sortIndex }
            var nextTailIndex = itemDao.maxSortIndex() ?: -1L
            val entities = items.map { item ->
                val sortIndex = existing[item.bvid] ?: run { nextTailIndex += 1; nextTailIndex }
                item.toEntity(sortIndex)
            }
            itemDao.upsertAll(entities)
            itemDao.trimToNewest(MAX_CACHE_SIZE)
        }
        cursorDao.upsert(FeedCacheCursorEntity(nextOffset = nextOffset, hasMore = hasMore))
    }

    /** 缓存本来是空的、这次头部请求其实就是第一页时,调用方用这个把它拿到的游标补上。 */
    suspend fun advanceCursor(nextOffset: String?, hasMore: Boolean) {
        cursorDao.upsert(FeedCacheCursorEntity(nextOffset = nextOffset, hasMore = hasMore))
    }
}

private fun FeedCacheItemEntity.toFeedItem() = FeedItem(
    bvid = bvid,
    title = title,
    coverUrl = coverUrl,
    durationText = durationText,
    upName = upName,
    upMid = upMid,
    publishedAtEpochSeconds = publishedAtEpochSeconds,
    playCount = playCount,
    danmakuCount = danmakuCount,
)

private fun FeedItem.toEntity(sortIndex: Long) = FeedCacheItemEntity(
    bvid = bvid,
    title = title,
    coverUrl = coverUrl,
    durationText = durationText,
    upName = upName,
    upMid = upMid,
    publishedAtEpochSeconds = publishedAtEpochSeconds,
    playCount = playCount,
    danmakuCount = danmakuCount,
    sortIndex = sortIndex,
)
