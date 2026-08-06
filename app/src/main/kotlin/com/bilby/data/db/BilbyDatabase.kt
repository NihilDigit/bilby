package com.bilby.data.db

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

@Database(
    entities = [PlaybackProgressEntity::class, FeedReadPositionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class BilbyDatabase : RoomDatabase() {
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun feedReadPositionDao(): FeedReadPositionDao

    companion object {
        fun create(context: Context): BilbyDatabase =
            Room.databaseBuilder(context, BilbyDatabase::class.java, "bilby.db").build()
    }
}
