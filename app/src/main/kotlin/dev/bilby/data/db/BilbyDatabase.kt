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

@Database(
    entities = [
        FeedReadPositionEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class BilbyDatabase : RoomDatabase() {
    abstract fun feedReadPositionDao(): FeedReadPositionDao

    companion object {
        fun create(context: Context): BilbyDatabase =
            Room.databaseBuilder(context, BilbyDatabase::class.java, "bilby.db")
                // 库里只剩一张"动态读到哪了"的表,是可再生的缓存数据,个人应用不值得为它
                // 维护迁移脚本 —— 版本升级直接丢重建。
                //
                // 播放进度表(v4 删)与 agent 会话三张表(v5 删)都曾在这里。前者的位置读自
                // 全 app 唯一的播放器,按页面身份落盘会串味;后者是助理上下文,存下来既没有
                // 消费方,又让"要不要续接"变成一个反复要做的判断。两者都改成不存。
                .fallbackToDestructiveMigration()
                .build()
    }
}
