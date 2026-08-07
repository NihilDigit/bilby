package dev.bilby.data

import dev.bilby.agent.AnswerBlock
import dev.bilby.agent.ChatMessage
import dev.bilby.agent.ToolCall
import dev.bilby.agent.TraceItem
import dev.bilby.data.db.AgentAnswerEntity
import dev.bilby.data.db.AgentMessageEntity
import dev.bilby.data.db.AgentSessionEntity
import dev.bilby.data.db.BilbyDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 搜索助理会话的持久化层(DESIGN 3.1:会话内多轮,由用户显式开启)。只负责存取,
 * 不做会话管理(列表/重命名/删除的 UI 由别处接)。
 */
class AgentSessionRepository(
    private val db: BilbyDatabase,
    private val json: Json,
) {
    private val dao get() = db.agentSessionDao()

    suspend fun newSession(title: String): Long {
        val now = System.currentTimeMillis()
        return dao.insertSession(
            AgentSessionEntity(createdAt = now, lastActiveAt = now, title = title),
        )
    }

    suspend fun appendMessages(sessionId: Long, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val now = System.currentTimeMillis()
        var seq = dao.getMaxSeq(sessionId)
        val entities = messages.map { message ->
            seq += 1
            AgentMessageEntity(
                sessionId = sessionId,
                seq = seq,
                role = message.role,
                content = message.content,
                toolCallsJson = message.toolCalls?.let { json.encodeToString(it) },
                toolCallId = message.toolCallId,
                name = message.name,
                createdAt = now,
            )
        }
        dao.insertMessages(entities)
        dao.touchSession(sessionId, now)
    }

    /** 还原成 AgentLoop 能直接塞回 LLM 请求的形状。 */
    suspend fun loadMessages(sessionId: Long): List<ChatMessage> =
        dao.getMessages(sessionId).map { entity ->
            ChatMessage(
                role = entity.role,
                content = entity.content,
                toolCalls = entity.toolCallsJson?.let { json.decodeFromString<List<ToolCall>>(it) },
                toolCallId = entity.toolCallId,
                name = entity.name,
            )
        }

    /**
     * 记下这一轮提到过哪些视频。**只存 bvid 与展示信息,不存正文** —— 正文在会话消息里
     * 已经有一份,再存一份必然对不上;这张表的用途是下一轮的溯源白名单和卡片展示。
     */
    suspend fun saveAnswer(sessionId: Long, blocks: List<AnswerBlock>) {
        val videos = blocks.filterIsInstance<AnswerBlock.Video>()
        if (videos.isEmpty()) return
        val entities = videos.map { video ->
            AgentAnswerEntity(
                sessionId = sessionId,
                bvid = video.bvid,
                reason = "",
                title = video.trace?.title,
                coverUrl = video.trace?.coverUrl,
                upName = video.trace?.upName,
            )
        }
        dao.insertAnswers(entities)
    }

    /** 恢复上一轮提到过的视频的展示信息,供跨轮追问时的卡片使用。 */
    suspend fun loadAnswers(sessionId: Long): Map<String, TraceItem> =
        dao.getAnswers(sessionId).mapNotNull { entity ->
            if (entity.title == null || entity.coverUrl == null || entity.upName == null) null
            else entity.bvid to TraceItem(
                bvid = entity.bvid,
                title = entity.title,
                coverUrl = entity.coverUrl,
                upName = entity.upName,
            )
        }.toMap()

    suspend fun deleteSession(sessionId: Long) {
        dao.deleteSession(sessionId)
    }
}
