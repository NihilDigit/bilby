package dev.bilby.danmaku

import dev.nihildigit.danmaku.Danmaku
import dev.nihildigit.danmaku.DanmakuMode
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class DanmakuVoteTest {

    @Test
    fun `parseDmWebViewVotes 提取 #VOTE# 并跳过其它指令与损坏项`() {
        val voteLate = commandDmBytes(
            id = 1002L,
            idStr = "1002",
            command = "#VOTE#",
            content = "后出现的投票",
            progressMillis = 36_000,
            extra = """
                {
                  "vote_id": 22118703,
                  "vote_type": 1,
                  "question": "你更喜欢车？",
                  "cnt": 633,
                  "options": [
                    {"idx": 2, "desc": "硬一点", "cnt": 344, "has_self_def": false},
                    {"idx": 1, "desc": "软一点", "cnt": 289, "has_self_def": false}
                  ],
                  "my_vote": 2,
                  "duration": 7000
                }
            """.trimIndent(),
        )
        val voteEarly = commandDmBytes(
            id = 1001L,
            idStr = "1001",
            command = "#VOTE#",
            content = "早出现的投票",
            progressMillis = 12_000,
            extra = """
                {
                  "vote_id": 22110001,
                  "question": "",
                  "cnt": 10,
                  "options": [
                    {"idx": 1, "desc": "支持", "cnt": 7},
                    {"idx": 2, "desc": "反对", "cnt": 3}
                  ],
                  "my_vote": 0,
                  "duration": 0
                }
            """.trimIndent(),
        )
        val linkCommand = commandDmBytes(
            id = 1003L,
            idStr = "1003",
            command = "#LINK#",
            content = "关联视频",
            progressMillis = 5_000,
            extra = """{"aid":12345}""",
        )
        val brokenVote = commandDmBytes(
            id = 1004L,
            idStr = "1004",
            command = "#VOTE#",
            content = "损坏",
            progressMillis = 8_000,
            extra = "{not-valid-json",
        )

        val reply = ByteArrayOutputStream().apply {
            writeVarintField(1, 0L) // state
            writeBytesField(9, voteLate)
            writeBytesField(9, linkCommand)
            writeBytesField(9, brokenVote)
            writeBytesField(9, voteEarly)
            writeBytesField(9, voteLate) // 重复 vote_id 去重
        }.toByteArray()

        val votes = parseDmWebViewVotes(reply)

        assertEquals(2, votes.size)
        assertEquals(22110001L, votes[0].voteId)
        assertEquals("早出现的投票", votes[0].question)
        assertEquals(12_000L, votes[0].startMillis)
        assertEquals(DEFAULT_VOTE_DURATION_MILLIS, votes[0].durationMillis)
        assertEquals(0, votes[0].myVote)

        assertEquals(22118703L, votes[1].voteId)
        assertEquals("你更喜欢车？", votes[1].question)
        assertEquals(36_000L, votes[1].startMillis)
        assertEquals(7_000L, votes[1].durationMillis)
        assertEquals(2, votes[1].myVote)
        assertEquals(listOf("软一点", "硬一点"), votes[1].options.map { it.desc })
    }

    @Test
    fun `withoutVoteEchoes 只剔除投票时间窗内匹配选项文案的弹幕`() {
        val votes = listOf(
            DanmakuVote(
                dmid = "1002",
                voteId = 22118703L,
                question = "你更喜欢车？",
                startMillis = 36_000L,
                durationMillis = 7_000L,
                totalCount = 633,
                options = listOf(
                    DanmakuVoteOption(idx = 1, desc = "软一点", count = 289),
                    DanmakuVoteOption(idx = 2, desc = "硬一点", count = 344),
                ),
            ),
        )
        val pool = listOf(
            danmaku("1", 36_500L, "软一点"), // 窗口内选项回显 -> 剔除
            danmaku("2", 47_000L, " 硬一点 "), // 窗口末尾宽限期内选项回显 -> 剔除
            danmaku("3", 36_500L, "普通路过弹幕"), // 同时间但不是选项 -> 保留
            danmaku("4", 120_000L, "软一点"), // 远离投票窗口的巧合词 -> 保留
        )

        val filtered = pool.withoutVoteEchoes(votes)

        assertEquals(listOf("3", "4"), filtered.map { it.id })
    }

    private fun danmaku(id: String, timeMillis: Long, text: String) = Danmaku(
        id = id,
        playTimeMillis = timeMillis,
        mode = DanmakuMode.SCROLL,
        color = 0xFFFFFF,
        text = text,
        fontSize = null,
        isSelf = false,
    )

    private fun commandDmBytes(
        id: Long,
        idStr: String,
        command: String,
        content: String,
        progressMillis: Int,
        extra: String,
    ): ByteArray = ByteArrayOutputStream().apply {
        writeVarintField(1, id)
        writeStringField(4, command)
        writeStringField(5, content)
        writeVarintField(6, progressMillis.toLong())
        writeStringField(9, extra)
        writeStringField(10, idStr)
    }.toByteArray()

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                write(b)
                return
            }
            write(b or 0x80)
        }
    }

    private fun ByteArrayOutputStream.writeTag(field: Int, wireType: Int) {
        writeVarint(((field.toLong() shl 3) or wireType.toLong()))
    }

    private fun ByteArrayOutputStream.writeVarintField(field: Int, value: Long) {
        writeTag(field, DanmakuProtoReader.WIRE_VARINT)
        writeVarint(value)
    }

    private fun ByteArrayOutputStream.writeStringField(field: Int, value: String) {
        writeBytesField(field, value.toByteArray(Charsets.UTF_8))
    }

    private fun ByteArrayOutputStream.writeBytesField(field: Int, bytes: ByteArray) {
        writeTag(field, DanmakuProtoReader.WIRE_LEN)
        writeVarint(bytes.size.toLong())
        write(bytes)
    }
}
