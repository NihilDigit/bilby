package com.bilby.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
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

/**
 * 搜索助理的一次会话(DESIGN 3.1)。会话由用户在 UI 显式开启,不自动续接;
 * title 取自第一轮用户输入的前若干字,给以后的会话列表用。
 */
@Entity(tableName = "agent_session")
data class AgentSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val lastActiveAt: Long,
    val title: String,
)

/**
 * 会话内的一条消息,对应 LlmProtocol.ChatMessage。
 *
 * toolCallsJson 直接存 ChatMessage.toolCalls 序列化后的字符串,不拆表:tool_calls 的结构
 * 由 OpenAI 协议定死,我们不会查询它内部字段,只需要原样回放给模型,拆表除了增加还原成本
 * 没有别的好处。
 */
@Entity(
    tableName = "agent_message",
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class AgentMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val seq: Int,
    val role: String,
    val content: String?,
    val toolCallsJson: String?,
    val toolCallId: String?,
    val name: String?,
    val createdAt: Long,
)

/**
 * 某一轮的答案条目(AgentEvent.AnswerItem)。展示信息一并存下来,重新打开会话时不用
 * 再查一次接口。
 */
@Entity(
    tableName = "agent_answer",
    foreignKeys = [
        ForeignKey(
            entity = AgentSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("messageId")],
)
data class AgentAnswerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val messageId: Long,
    val bvid: String,
    val reason: String,
    val title: String?,
    val coverUrl: String?,
    val upName: String?,
)
