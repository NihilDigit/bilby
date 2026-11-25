package dev.bilby.data

import dev.bilby.data.db.BilbyDatabase
import dev.bilby.data.db.CollectionProgressEntity
import dev.bilby.data.db.CollectionWatchedEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 合集/系列追更进度仓库：记录每个合集最后观看的视频、进度与已看条目集合。
 */
class CollectionProgressRepository(private val db: BilbyDatabase) {
    private val dao get() = db.collectionProgressDao()

    fun observeProgress(mid: Long, collectionId: Long, isSeason: Boolean): Flow<CollectionProgressEntity?> =
        dao.observeProgress(mid, collectionId, isSeason)

    fun observeWatchedBvids(mid: Long, collectionId: Long, isSeason: Boolean): Flow<Set<String>> =
        dao.observeWatchedBvids(mid, collectionId, isSeason).map { it.toSet() }

    suspend fun recordPlay(
        mid: Long,
        collectionId: Long,
        isSeason: Boolean,
        bvid: String,
        title: String,
        coverUrl: String = "",
        positionMillis: Long = 0L,
        durationMillis: Long = 0L,
    ) {
        if (mid <= 0L || collectionId <= 0L || bvid.isBlank()) return
        val now = System.currentTimeMillis()
        dao.upsertProgress(
            CollectionProgressEntity(
                mid = mid,
                collectionId = collectionId,
                isSeason = isSeason,
                lastBvid = bvid,
                lastTitle = title,
                lastCoverUrl = coverUrl,
                lastPositionMillis = positionMillis.coerceAtLeast(0L),
                durationMillis = durationMillis.coerceAtLeast(0L),
                updatedAt = now,
            ),
        )
        dao.upsertWatched(
            CollectionWatchedEntity(
                mid = mid,
                collectionId = collectionId,
                isSeason = isSeason,
                bvid = bvid,
                watchedAt = now,
            ),
        )
    }

    suspend fun updatePosition(
        mid: Long,
        collectionId: Long,
        isSeason: Boolean,
        bvid: String,
        positionMillis: Long,
        durationMillis: Long,
    ) {
        if (mid <= 0L || collectionId <= 0L || bvid.isBlank()) return
        dao.updatePositionIfMatches(
            mid = mid,
            collectionId = collectionId,
            isSeason = isSeason,
            bvid = bvid,
            positionMillis = positionMillis.coerceAtLeast(0L),
            durationMillis = durationMillis.coerceAtLeast(0L),
            updatedAt = System.currentTimeMillis(),
        )
    }
}
