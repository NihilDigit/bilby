package dev.bilby.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// 播放进度曾经在这里存过一张 playback_progress 表,已删除(DB v4)。续播只认服务端的
// last_play_time,理由见 VideoRepository 的 resumeAtMillisFor:那份本地进度按 bvid 落盘、
// 数字却读自全 app 唯一的那个播放器,翻页时会把上一条的位置写到新一条头上。

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
