package dev.bilby.data.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 动态流读到哪儿了。存的是 [dev.bilby.data.model.FeedEntry.id],见 [FeedReadPositionEntity]。 */
class FeedReadPositionRepository(private val dao: FeedReadPositionDao) {

    fun observe(): Flow<String?> = dao.observe().map { it?.lastReadEntryId }

    suspend fun save(entryId: String) {
        dao.upsert(FeedReadPositionEntity(lastReadEntryId = entryId, updatedAt = System.currentTimeMillis()))
    }
}
