package dev.bilby.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedReadPositionDao {
    @Query("SELECT * FROM feed_read_position WHERE id = 0")
    fun observe(): Flow<FeedReadPositionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FeedReadPositionEntity)
}

@Dao
interface FeedCacheItemDao {
    @Query("SELECT * FROM feed_cache_item ORDER BY sortIndex ASC")
    suspend fun loadAllOrdered(): List<FeedCacheItemEntity>

    @Query("DELETE FROM feed_cache_item")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<FeedCacheItemEntity>)

    /**
     * 整段换掉。**清空再写,不是逐条 upsert** —— 缓存存的是"服务端最新那一页现在长什么样",
     * 而 upsert 表达不了"这一条不在了":取关之后那个人的投稿就是靠这一步消失的。
     */
    @Transaction
    suspend fun replaceAll(items: List<FeedCacheItemEntity>) {
        deleteAll()
        insertAll(items)
    }
}

@Database(
    entities = [
        FeedReadPositionEntity::class,
        FeedCacheItemEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class BilbyDatabase : RoomDatabase() {
    abstract fun feedReadPositionDao(): FeedReadPositionDao
    abstract fun feedCacheItemDao(): FeedCacheItemDao

    companion object {
        fun create(context: Context): BilbyDatabase =
            Room.databaseBuilder(context, BilbyDatabase::class.java, "bilby.db")
                // 两张表(读到哪了、动态流缓存条目)都是可再生的派生数据,个人应用不值得为
                // 它们维护迁移脚本 —— 版本升级直接丢重建。缓存表丢了的后果只是下次进动态流
                // 又要空等一次网络(退回 v5 之前的首屏转圈),不影响正确性。
                //
                // 续接游标表(v7 删)曾在这里。它是为"翻页翻到的深度要跨进程留住"服务的,
                // 而那份深列表同时也是一份没人能删掉东西的第二真值:取关的人不会消失、
                // 头部刷新不敢覆盖游标,都是它带来的。改成只留最新一段之后它没有了用处。
                //
                // 播放进度表(v4 删)与 agent 会话三张表(v5 删)都曾在这里。前者的位置读自
                // 全 app 唯一的播放器,按页面身份落盘会串味;后者是助理上下文,存下来既没有
                // 消费方,又让"要不要续接"变成一个反复要做的判断。两者都改成不存。
                .fallbackToDestructiveMigration()
                .build()
    }
}
