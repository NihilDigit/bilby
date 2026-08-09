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
interface FeedReadPositionDao {
    @Query("SELECT * FROM feed_read_position WHERE id = 0")
    fun observe(): Flow<FeedReadPositionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FeedReadPositionEntity)
}

/** [FeedCacheItemDao.sortIndicesFor] 的投影:合并新一页时只需要知道哪些 bvid 已经存在、
 * 存在的话原来的顺序位置是多少,不需要整行。 */
data class BvidSortIndex(val bvid: String, val sortIndex: Long)

@Dao
interface FeedCacheItemDao {
    @Query("SELECT * FROM feed_cache_item ORDER BY sortIndex ASC")
    suspend fun loadAllOrdered(): List<FeedCacheItemEntity>

    @Query("SELECT bvid, sortIndex FROM feed_cache_item WHERE bvid IN (:bvids)")
    suspend fun sortIndicesFor(bvids: List<String>): List<BvidSortIndex>

    @Query("SELECT MIN(sortIndex) FROM feed_cache_item")
    suspend fun minSortIndex(): Long?

    @Query("SELECT MAX(sortIndex) FROM feed_cache_item")
    suspend fun maxSortIndex(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<FeedCacheItemEntity>)

    // 只留 sortIndex 最小(最新)的 limit 条,其余一律丢 —— 无界缓存迟早变成一个没人知道
    // 多大的本地库。
    @Query(
        """
        DELETE FROM feed_cache_item WHERE bvid NOT IN (
            SELECT bvid FROM feed_cache_item ORDER BY sortIndex ASC LIMIT :limit
        )
        """
    )
    suspend fun trimToNewest(limit: Int)
}

@Dao
interface FeedCacheCursorDao {
    @Query("SELECT * FROM feed_cache_cursor WHERE id = 0")
    suspend fun get(): FeedCacheCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FeedCacheCursorEntity)
}

@Database(
    entities = [
        FeedReadPositionEntity::class,
        FeedCacheItemEntity::class,
        FeedCacheCursorEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class BilbyDatabase : RoomDatabase() {
    abstract fun feedReadPositionDao(): FeedReadPositionDao
    abstract fun feedCacheItemDao(): FeedCacheItemDao
    abstract fun feedCacheCursorDao(): FeedCacheCursorDao

    companion object {
        fun create(context: Context): BilbyDatabase =
            Room.databaseBuilder(context, BilbyDatabase::class.java, "bilby.db")
                // 三张表(读到哪了、动态流缓存条目、续接游标)全部是可再生的派生数据,个人应用
                // 不值得为它们维护迁移脚本 —— 版本升级直接丢重建。缓存表丢了的后果只是下次进
                // 动态流又要空等一次网络(退回 v5 之前的首屏转圈),不影响正确性。
                //
                // 播放进度表(v4 删)与 agent 会话三张表(v5 删)都曾在这里。前者的位置读自
                // 全 app 唯一的播放器,按页面身份落盘会串味;后者是助理上下文,存下来既没有
                // 消费方,又让"要不要续接"变成一个反复要做的判断。两者都改成不存。
                .fallbackToDestructiveMigration()
                .build()
    }
}
