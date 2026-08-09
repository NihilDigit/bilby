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

/**
 * 动态流本地缓存的一条(DESIGN 2.1)。进屏时先渲染这张表里的内容,后台再拿网络结果去合并,
 * 首屏才不用每次都空等一轮网络。
 *
 * `sortIndex` 是本地维护的相对顺序,不是发布时间:同一批抓回来的条目发布时间可能打平,记
 * 抓取顺序才能保证恢复出来的列表和当时看到的一致(见 FeedCacheRepository)。**这里不存
 * 排除的 UP 主这条信息**——排除是可撤销的设置,过滤要放在读出来之后做,烤进缓存的话取消
 * 排除之后那些条目永远回不来。
 */
@Entity(tableName = "feed_cache_item")
data class FeedCacheItemEntity(
    @PrimaryKey val bvid: String,
    val title: String,
    val coverUrl: String,
    val durationText: String,
    val upName: String,
    val upMid: Long,
    val publishedAtEpochSeconds: Long,
    val playCount: String,
    val danmakuCount: String,
    val sortIndex: Long,
)

/**
 * 动态流缓存续接到网络分页要用的游标:缓存里最旧那条之后,再往下翻一页该传给服务端的
 * offset。单行,和 [FeedReadPositionEntity] 同样的形状。只在真正往更旧翻页时前进
 * (`FeedCacheRepository.appendTail` / `advanceCursor`),头部刷新拿到的浅游标不会覆盖它
 * ——覆盖了会在翻到缓存底部时把中间已经翻过的页重新请求一遍。
 */
@Entity(tableName = "feed_cache_cursor")
data class FeedCacheCursorEntity(
    @PrimaryKey val id: Int = SINGLE_ROW,
    val nextOffset: String?,
    val hasMore: Boolean,
) {
    companion object {
        const val SINGLE_ROW = 0
    }
}
