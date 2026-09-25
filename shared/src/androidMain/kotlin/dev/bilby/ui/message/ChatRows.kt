package dev.bilby.ui.message

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.bilby.R
import dev.bilby.data.WhisperContent
import dev.bilby.data.WhisperMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 会话页里的一行。消息本身之外还有两种行:时间分隔,以及不是谁说的话的那几种(撤回、系统提示)。
 * 三者在同一个列表里按时间排,所以在进列表之前就拼好,而不是在每条气泡里各自判断要不要在
 * 头上顶一行时间。
 */
internal sealed interface ChatRow {
    val key: Any

    /** 与上一条隔得够久(见 [TimeGapSeconds])时插的一行时间。 */
    data class Time(val epochSeconds: Long, override val key: Any) : ChatRow

    /** 居中的一行小字。撤回与系统提示不是谁说的话,画成气泡就会被读成某一方发的。 */
    data class Caption(val message: WhisperMessage, val mine: Boolean) : ChatRow {
        override val key: Any get() = message.seqno
    }

    /**
     * 一张投稿卡片。UP 主的投稿推送是服务端代发的通知,不是对方说的话,理由同 [Caption]。
     * 时间写在卡片里,不另起一行时间分隔,见 [buildChatRows]。
     */
    data class Pushed(val push: WhisperContent.VideoPush, val seqno: Long, val timeSeconds: Long) : ChatRow {
        override val key: Any get() = seqno
    }

    /**
     * 一个气泡。同一个人接连说的几句归成一组,组内相邻两个气泡靠发送方那一侧的角收小
     * ([joinsPrevious] 管上角,[joinsNext] 管下角),读起来是一段话而不是几条互不相干的消息。
     */
    data class Bubble(
        val message: WhisperMessage,
        val mine: Boolean,
        val joinsPrevious: Boolean,
        val joinsNext: Boolean,
        /** 推送附言拆出来的气泡与推送卡片出自同一条消息,序列号相同,键要另取。 */
        override val key: Any = message.seqno,
    ) : ChatRow
}

/**
 * 把按时间先后排的消息拼成行。
 *
 * **分组不跨时间分隔。** 隔了一天的两句话即使是同一个人说的,中间那行日期已经把它们切开了,
 * 再把气泡的角接起来,读者会以为它们是连着说的。
 */
internal fun buildChatRows(messages: List<WhisperMessage>, selfMid: Long): List<ChatRow> {
    val startsSection = BooleanArray(messages.size) { i ->
        i == 0 || messages[i].timeSeconds - messages[i - 1].timeSeconds > TimeGapSeconds
    }

    fun joins(a: Int, b: Int): Boolean =
        !startsSection[b] &&
            messages[a].content.isBubble() &&
            messages[b].content.isBubble() &&
            messages[a].senderUid == messages[b].senderUid

    val rows = ArrayList<ChatRow>(messages.size + messages.size / 4)
    messages.forEachIndexed { i, message ->
        val mine = message.senderUid == selfMid
        val content = message.content
        // 推送卡片自己带时间:推送之间通常隔得远,每张卡上面都会顶一行时间,一半的行是分隔。
        // 卡片右边标题底下本来就空着一截,时间放在那里。
        if (startsSection[i] && content !is WhisperContent.VideoPush) {
            rows += ChatRow.Time(message.timeSeconds, "time-${message.seqno}")
        }
        rows += when {
            content is WhisperContent.VideoPush -> {
                rows += ChatRow.Pushed(content, message.seqno, message.timeSeconds)
                // 附言是 UP 主说的话,画成对方的一个气泡,接在卡片下面;塞在卡片里时它和标题
                // 挤在封面右边,写满两行就把卡片撑得比封面高一截。
                if (content.note.isEmpty()) return@forEachIndexed
                ChatRow.Bubble(
                    message = message.copy(content = WhisperContent.Text(content.note)),
                    mine = mine,
                    joinsPrevious = false,
                    joinsNext = false,
                    key = "note-${message.seqno}",
                )
            }
            content.isBubble() -> ChatRow.Bubble(
                message = message,
                mine = mine,
                joinsPrevious = i > 0 && joins(i - 1, i),
                joinsNext = i < messages.lastIndex && joins(i, i + 1),
            )

            else -> ChatRow.Caption(message, mine)
        }
    }
    return rows
}

private fun WhisperContent.isBubble(): Boolean =
    this !is WhisperContent.Hint && this !is WhisperContent.Withdrawn && this !is WhisperContent.VideoPush

/**
 * 两条之间隔多久才插一行时间。五分钟是常见聊天软件的做法:一来一回的一段对话落在同一个
 * 时间下面,过一阵再说话时才另起一段。
 */
private const val TimeGapSeconds = 5 * 60

/**
 * 时间分隔那一行的字。**写具体时刻,不写"3 小时前"**:聊天记录是往回翻着读的,相对时间在
 * 翻的时候不会变,而同一屏里"3 小时前"与"昨天"两种说法混着出现,读者得自己换算回先后。
 * 今天只写时刻,昨天加前缀,今年省略年份。
 */
@Composable
internal fun formatChatTime(epochSeconds: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochSecond(epochSeconds).atZone(zone)
    val date = time.toLocalDate()
    val today = LocalDate.now(zone)
    val clock = time.format(ClockFormatter)
    return when {
        date == today -> clock
        date == today.minusDays(1) -> stringResource(R.string.whisper_time_yesterday, clock)
        date.year == today.year ->
            time.format(DateTimeFormatter.ofPattern(stringResource(R.string.whisper_time_pattern_year)))

        else -> time.format(DateTimeFormatter.ofPattern(stringResource(R.string.whisper_time_pattern_full)))
    }
}

private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
