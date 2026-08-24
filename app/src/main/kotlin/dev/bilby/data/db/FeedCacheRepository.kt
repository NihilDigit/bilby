package dev.bilby.data.db

import dev.bilby.data.model.ArticleRef
import dev.bilby.data.model.FeedEntry

/**
 * 首页动态流的本地缓存,包一层 [FeedCacheItemDao]。
 *
 * **存的是"服务端最新那一段现在长什么样",不是"这个列表历史上有过什么"。** 因此只有一个写入
 * 口 [saveHead],整段替换;翻页翻出来的更旧内容不落盘,进程结束就没了(见 FeedCacheItemEntity)。
 *
 * 从前这里是一份能独立往下长的持久列表,配一张续接游标表。代价是本地成了第二份真值而又没有
 * 删除路径:取关之后那个人的投稿一条都不会掉,只会等着被条数上限从尾部挤出去。
 */
class FeedCacheRepository(private val itemDao: FeedCacheItemDao) {

    suspend fun restore(): List<FeedEntry> = itemDao.loadAllOrdered().map { it.toFeedEntry() }

    /** 头部那一段。超过 [MAX_CACHE_SIZE] 的部分不存 —— 冷启动只要够铺满一屏就行。 */
    suspend fun saveHead(entries: List<FeedEntry>) {
        itemDao.replaceAll(entries.take(MAX_CACHE_SIZE).mapIndexed { index, entry -> entry.toEntity(index.toLong()) })
    }

    private companion object {
        const val MAX_CACHE_SIZE = 60
    }
}

private fun FeedCacheItemEntity.toFeedEntry(): FeedEntry = when (kind) {
    FeedCacheItemEntity.KIND_ARTICLE -> FeedEntry.Article(
        ref = ArticleRef(id = articleId, isRead = articleIsRead),
        title = title,
        summary = summary,
        coverUrl = coverUrl,
        upName = upName,
        upMid = upMid,
        publishedAtEpochSeconds = publishedAtEpochSeconds,
    )

    else -> FeedEntry.Video(
        bvid = id,
        title = title,
        coverUrl = coverUrl,
        durationText = durationText,
        upName = upName,
        upMid = upMid,
        publishedAtEpochSeconds = publishedAtEpochSeconds,
        playCount = playCount,
        danmakuCount = danmakuCount,
    )
}

private fun FeedEntry.toEntity(sortIndex: Long): FeedCacheItemEntity = when (this) {
    is FeedEntry.Video -> FeedCacheItemEntity(
        id = id,
        kind = FeedCacheItemEntity.KIND_VIDEO,
        sortIndex = sortIndex,
        title = title,
        coverUrl = coverUrl,
        upName = upName,
        upMid = upMid,
        publishedAtEpochSeconds = publishedAtEpochSeconds,
        durationText = durationText,
        playCount = playCount,
        danmakuCount = danmakuCount,
    )

    is FeedEntry.Article -> FeedCacheItemEntity(
        id = id,
        kind = FeedCacheItemEntity.KIND_ARTICLE,
        sortIndex = sortIndex,
        title = title,
        coverUrl = coverUrl,
        upName = upName,
        upMid = upMid,
        publishedAtEpochSeconds = publishedAtEpochSeconds,
        summary = summary,
        articleId = ref.id,
        articleIsRead = ref.isRead,
    )
}
