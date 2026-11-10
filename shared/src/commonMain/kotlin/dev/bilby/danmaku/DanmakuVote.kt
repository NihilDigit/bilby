package dev.bilby.danmaku

import dev.bilby.BiliLog
import dev.nihildigit.danmaku.Danmaku
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 视频内的互动投票弹幕(`#VOTE#`,见 notes/danmaku.md §11)。
 *
 * 来自 `GET /x/v2/dm/web/view` 返回的 `DmWebViewReply.commandDms`(tag 9),
 * 每条对应视频某个时间点弹出的一个投票问题与 2~4 个选项。
 *
 * @param dmid 指令弹幕自身的 id 字符串(`CommandDm.idstr`),提交投票时作为 `cmd_id_str` 传回。
 * @param voteId 投票 id(`extra.vote_id`)。
 * @param voteType 投票类型(`extra.vote_type`),原样回传给投票接口。
 * @param question 投票问题标题(`extra.question`,为空时回退 `CommandDm.content`)。
 * @param startMillis 出现时间(毫秒,来自 `CommandDm.progress`)。
 * @param durationMillis 面板驻留时长(毫秒,来自 `extra.duration`,缺省按 7 秒计)。
 * @param totalCount 已参与总人数(`extra.cnt`)。
 * @param options 候选选项列表,按 `idx` 排序。
 * @param myVote 当前登录用户已投的选项 `idx`;`0` 表示尚未投票。
 */
data class DanmakuVote(
    val dmid: String,
    val voteId: Long,
    val voteType: Int = 0,
    val question: String,
    val startMillis: Long,
    val durationMillis: Long = DEFAULT_VOTE_DURATION_MILLIS,
    val totalCount: Int,
    val options: List<DanmakuVoteOption>,
    val myVote: Int = 0,
) {
    val endMillis: Long get() = startMillis + durationMillis
    val hasVoted: Boolean get() = myVote > 0
}

/**
 * 互动投票的单个选项(notes/danmaku.md §11.1)。
 *
 * @param idx 选项序号(1-based,提交投票时作为 `option_id` 传回)。
 * @param desc 选项显示文案。
 * @param count 已投该选项的票数。
 * @param hasSelfDef 是否带自定义标记(`extra.options[i].has_self_def`),原样回传给投票接口。
 */
data class DanmakuVoteOption(
    val idx: Int,
    val desc: String,
    val count: Int,
    val hasSelfDef: Boolean = false,
)

/**
 * 解析 `GET /x/v2/dm/web/view` 的裸 protobuf 响应(`DmWebViewReply`),提取全部 `#VOTE#` 互动投票
 * (notes/danmaku.md §11.1)。
 *
 * `DmWebViewReply` 的 tag 9(LEN)是 `repeated CommandDm`;其余字段(弹幕开关、分段规则、特殊弹幕配置等)
 * 当前不需要,按各自 wire type 跳过。单条 `extra` JSON 损坏时只跳过该条,不影响同一视频内的其它投票。
 */
internal fun parseDmWebViewVotes(bytes: ByteArray): List<DanmakuVote> {
    val reader = DanmakuProtoReader(bytes)
    val votes = mutableListOf<DanmakuVote>()
    val seenVoteIds = HashSet<Long>()
    while (reader.hasNext()) {
        val (field, wireType) = reader.readTag()
        if (field == DM_VIEW_FIELD_COMMAND_DMS && wireType == DanmakuProtoReader.WIRE_LEN) {
            val vote = parseCommandDmVoteOrNull(reader.readMessage())
            if (vote != null && seenVoteIds.add(vote.voteId)) {
                votes.add(vote)
            }
        } else {
            reader.skip(wireType)
        }
    }
    votes.sortBy { it.startMillis }
    return votes
}

/**
 * 从普通弹幕列表里剔除落在投票窗口期、且内容恰为投票选项文案的回显弹幕(notes/danmaku.md §11.2)。
 *
 * 协议层的 `dmFrom == 2` / `pool == 3` / `attr & 0x80000` 已经在 [toMappedSegment] 里拦掉了绝大多数
 * 指令衍生弹幕;但部分旧稿件或跨端发送的投票弹幕在 `seg.so` 里不带上述标记,这里按投票时间窗与选项
 * 文本再做一层精确过滤,避免投票期间满屏刷出重复的选项文字。
 */
internal fun List<Danmaku>.withoutVoteEchoes(votes: List<DanmakuVote>): List<Danmaku> {
    if (isEmpty() || votes.isEmpty()) return this
    return filterNot { it.isVoteEchoOf(votes) }
}

internal fun Danmaku.isVoteEchoOf(votes: List<DanmakuVote>): Boolean {
    if (votes.isEmpty()) return false
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return false
    return votes.any { vote ->
        playTimeMillis in (vote.startMillis - VOTE_ECHO_LEAD_MILLIS)..(vote.endMillis + VOTE_ECHO_TAIL_MILLIS) &&
            vote.options.any { option -> option.desc.trim() == trimmed }
    }
}

private fun parseCommandDmVoteOrNull(reader: DanmakuProtoReader): DanmakuVote? {
    var id = 0L
    var command = ""
    var content = ""
    var progressMillis = 0
    var extra = ""
    var idStr = ""

    while (reader.hasNext()) {
        val (field, wireType) = reader.readTag()
        when (field) {
            1 -> id = reader.readVarint()
            4 -> command = reader.readString()
            5 -> content = reader.readString()
            6 -> progressMillis = reader.readVarint().toInt()
            9 -> extra = reader.readString()
            10 -> idStr = reader.readString()
            else -> reader.skip(wireType)
        }
    }

    if (command != VOTE_COMMAND || extra.isBlank()) return null
    val dto = runCatching { voteJson.decodeFromString<VoteExtraDto>(extra) }
        .onFailure { BiliLog.w("解析互动投票弹幕失败(dmid=${idStr.ifEmpty { id.toString() }})", it) }
        .getOrNull() ?: return null
    if (dto.voteId <= 0L || dto.options.isEmpty()) return null

    val options = dto.options
        .mapNotNull { opt ->
            val desc = opt.desc.trim()
            if (opt.idx <= 0 || desc.isEmpty()) null
            else DanmakuVoteOption(
                idx = opt.idx,
                desc = desc,
                count = opt.cnt.coerceAtLeast(0),
                hasSelfDef = opt.hasSelfDef,
            )
        }
        .sortedBy { it.idx }
    if (options.isEmpty()) return null

    val question = dto.question.ifBlank { content }.trim()
    val dmid = idStr.ifEmpty { id.takeIf { it != 0L }?.toString().orEmpty() }
    val duration = dto.duration.takeIf { it > 0L } ?: DEFAULT_VOTE_DURATION_MILLIS
    val optionSum = options.sumOf { it.count }

    return DanmakuVote(
        dmid = dmid,
        voteId = dto.voteId,
        voteType = dto.voteType,
        question = question,
        startMillis = progressMillis.coerceAtLeast(0).toLong(),
        durationMillis = duration,
        totalCount = maxOf(dto.cnt, optionSum),
        options = options,
        myVote = dto.myVote.takeIf { voted -> options.any { it.idx == voted } } ?: 0,
    )
}

@Serializable
private data class VoteExtraDto(
    @SerialName("vote_id") val voteId: Long = 0L,
    @SerialName("vote_type") val voteType: Int = 0,
    val question: String = "",
    val cnt: Int = 0,
    val duration: Long = DEFAULT_VOTE_DURATION_MILLIS,
    @SerialName("my_vote") val myVote: Int = 0,
    val options: List<VoteOptionDto> = emptyList(),
)

@Serializable
private data class VoteOptionDto(
    val idx: Int = 0,
    val desc: String = "",
    val cnt: Int = 0,
    @SerialName("has_self_def") val hasSelfDef: Boolean = false,
)

private val voteJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

private const val DM_VIEW_FIELD_COMMAND_DMS = 9
private const val VOTE_COMMAND = "#VOTE#"
internal const val DEFAULT_VOTE_DURATION_MILLIS = 7_000L
private const val VOTE_ECHO_LEAD_MILLIS = 1_000L
private const val VOTE_ECHO_TAIL_MILLIS = 5_000L
