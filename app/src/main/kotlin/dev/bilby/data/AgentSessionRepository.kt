package dev.bilby.data

import dev.bilby.agent.AnswerItem
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

    suspend fun saveAnswer(sessionId: Long, items: List<AnswerItem>) {
        if (items.isEmpty()) return
        val entities = items.map { item ->
            AgentAnswerEntity(
                sessionId = sessionId,
                bvid = item.bvid,
                reason = item.reason,
                title = item.trace?.title,
                coverUrl = item.trace?.coverUrl,
                upName = item.trace?.upName,
            )
        }
        dao.insertAnswers(entities)
    }

    suspend fun loadAnswers(sessionId: Long): List<AnswerItem> =
        dao.getAnswers(sessionId).map { entity ->
            val trace = if (entity.title != null && entity.coverUrl != null && entity.upName != null) {
                TraceItem(bvid = entity.bvid, title = entity.title, coverUrl = entity.coverUrl, upName = entity.upName)
            } else {
                null
            }
            AnswerItem(bvid = entity.bvid, reason = entity.reason, trace = trace)
        }

    suspend fun deleteSession(sessionId: Long) {
        dao.deleteSession(sessionId)
    }
}
