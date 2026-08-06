package dev.bilby.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackProgressDao {
    @Query("SELECT * FROM playback_progress WHERE bvid = :bvid")
    suspend fun get(bvid: String): PlaybackProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PlaybackProgressEntity)
}

@Dao
interface FeedReadPositionDao {
    @Query("SELECT * FROM feed_read_position WHERE id = 0")
    fun observe(): Flow<FeedReadPositionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FeedReadPositionEntity)
}

@Dao
interface AgentSessionDao {
    @Insert
    suspend fun insertSession(entity: AgentSessionEntity): Long

    @Query("UPDATE agent_session SET lastActiveAt = :lastActiveAt WHERE id = :sessionId")
    suspend fun touchSession(sessionId: Long, lastActiveAt: Long)

    @Query("SELECT * FROM agent_session ORDER BY lastActiveAt DESC LIMIT 1")
    suspend fun getMostRecentSession(): AgentSessionEntity?

    @Insert
    suspend fun insertMessages(entities: List<AgentMessageEntity>)

    @Query("SELECT * FROM agent_message WHERE sessionId = :sessionId ORDER BY seq ASC")
    suspend fun getMessages(sessionId: Long): List<AgentMessageEntity>

    @Query("SELECT COALESCE(MAX(seq), -1) FROM agent_message WHERE sessionId = :sessionId")
    suspend fun getMaxSeq(sessionId: Long): Int

    @Query("SELECT id FROM agent_message WHERE sessionId = :sessionId ORDER BY seq DESC LIMIT 1")
    suspend fun getLastMessageId(sessionId: Long): Long?

    @Insert
    suspend fun insertAnswers(entities: List<AgentAnswerEntity>)

    @Query("SELECT * FROM agent_answer WHERE sessionId = :sessionId")
    suspend fun getAnswers(sessionId: Long): List<AgentAnswerEntity>

    // 会话表设了 onDelete = CASCADE,删会话自动带走消息与答案。
    @Query("DELETE FROM agent_session WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)
}

@Database(
    entities = [
        PlaybackProgressEntity::class,
        FeedReadPositionEntity::class,
        AgentSessionEntity::class,
        AgentMessageEntity::class,
        AgentAnswerEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class BilbyDatabase : RoomDatabase() {
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun feedReadPositionDao(): FeedReadPositionDao
    abstract fun agentSessionDao(): AgentSessionDao

    companion object {
        fun create(context: Context): BilbyDatabase =
            Room.databaseBuilder(context, BilbyDatabase::class.java, "bilby.db")
                // agent 会话表是本次新加的,个人应用不值得为这种可再生的缓存数据维护迁移
                // 脚本 —— 版本升级时直接丢重建,会话丢了就丢了。
                .fallbackToDestructiveMigration()
                .build()
    }
}
