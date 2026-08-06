package com.bilby.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 播放进度。DESIGN 7 节未定案是否把进度回传 B 站心跳接口,在定案前本地这份是唯一来源;
 * 即便日后回传,本地仍然要留一份 —— 心跳是有延迟的,冷启动续播不能等网络。
 */
@Entity(tableName = "playback_progress")
data class PlaybackProgressEntity(
    @PrimaryKey val bvid: String,
    val cid: Long,
    val positionMillis: Long,
    val durationMillis: Long,
    val updatedAt: Long,
)

/**
 * 动态流读到哪儿了(DESIGN 2.1)。存的是动态 id 而不是列表下标:下标会因为新投稿插入顶部
 * 而整体位移,id 不会。
 */
@Entity(tableName = "feed_read_position")
data class FeedReadPositionEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val lastReadBvid: String,
    val updatedAt: Long,
) {
    companion object {
        const val SINGLE_ROW = 0
    }
}
