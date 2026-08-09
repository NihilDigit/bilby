package dev.bilby.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 动态流读到哪儿了(DESIGN 2.1),包一层 dao。ViewModel 不该直接认识 Room 的 Entity/Dao ——
 * 这里只暴露 [FeedViewModel] 真正要用的两个动作,类型也收窄成裸 bvid。
 */
class FeedReadPositionRepository(private val dao: FeedReadPositionDao) {

    fun observe(): Flow<String?> = dao.observe().map { it?.lastReadBvid }

    suspend fun save(bvid: String) {
        dao.upsert(FeedReadPositionEntity(lastReadBvid = bvid, updatedAt = System.currentTimeMillis()))
    }
}
