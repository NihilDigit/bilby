package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.postAction
import dev.bilby.api.toHttpsUrl
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 消息中心的一条。回复/@/赞三类结构不同,但界面上是同一种行,所以在这一层就归一
 * ——让 UI 去认三种 DTO 只会把"哪个字段是正文"这个问题散到三处。
 *
 * @param quoted 被回复的原文。只有"回复我的"有,是这条通知的语境:没有它,一句"说得对"
 *   读不出在说什么。
 * @param uri 点开去哪儿。服务端给的是站内链接,交给 [dev.bilby.ui.BilbyLink] 解析,
 *   与从别的 app 分享进来的链接同一条路。
 */
data class Notice(
    val id: Long,
    val avatarUrl: String,
    val name: String,
    /** 一起做了这件事的人数。赞是聚合的,1 表示只有一个人。 */
    val actorCount: Int,
    val body: String,
    val quoted: String?,
    val timeSeconds: Long,
    val uri: String,
)

/** 一页通知。[nextCursor] 为 null 即到底了。 */
data class NoticePage(val items: List<Notice>, val nextCursor: NoticeCursor?)

/** 消息中心的游标是 (id, time) 一对,不是页号。 */
data class NoticeCursor(val id: Long, val timeSeconds: Long)

/** 系统通知。它没有"谁"这个概念,只有标题和正文。 */
data class SysNotice(
    val id: Long,
    val cursor: Long,
    val title: String,
    val content: String,
    /** 服务端给的是拼好的字符串(“2026-08-12 10:00”),不是时间戳。 */
    val timeText: String,
)

/**
 * 一个私信会话。
 *
 * [lastMessage] 已经按 [MessageContent] 解出来了:会话列表要显示"最后一句话",而那句话
 * 可能是分享卡片而不是文本。
 */
data class WhisperSession(
    val talkerId: Long,
    val name: String,
    val faceUrl: String,
    val lastMessage: String,
    val unreadCount: Int,
    /** 服务端给的是**微秒**,这里已经折成秒。 */
    val timeSeconds: Long,
    val maxSeqno: Long,
    /**
     * 这个会话对面不是一个人。
     *
     * 判据是 `account/v1/user/cards` **查不到这个 id** —— 实测系统通知号
     * (`talker_id=844424930131965` 这种量级)对它返回空数组,而不是返回一个空名字的卡片。
     * 名字和头像因此都拿不到,界面上要给一个中性的说法,不能显示成一个没有名字的人。
     */
    val isSystem: Boolean,
)

/** 会话里的一条消息。 */
data class WhisperMessage(
    val seqno: Long,
    val senderUid: Long,
    val content: WhisperContent,
    val timeSeconds: Long,
)

/**
 * 一条私信的内容。**`content` 字段是一个 JSON 字符串,形状由 `msg_type` 决定**
 * (notes/private-message.md §2),所以这里按类型分派成几种真正不同的东西,而不是一律折成
 * 一行字 —— 视频推送折成一行就只剩标题,点不开,而私信里最常见的正是它。
 *
 * 实测到的类型(2026-08-12,一个账号的 20 个会话):1 文本、7 分享、10 系统通知、
 * 11 UP 主的视频推送、12 专栏、18 富文本提示。**11 和 12 是数量最多的两类**,
 * 只认 1/2/5/7 的话大半个收件箱都是"暂不支持"。
 */
sealed interface WhisperContent {

    data class Text(val text: String) : WhisperContent

    /** 分享(7)与 UP 主的视频推送(11)。两者字段名不同,但都是"一条视频",到这里已经归一。 */
    data class Video(val bvid: String, val title: String, val coverUrl: String) : WhisperContent

    /** 专栏(12)。`rid` 就是 cv 号。 */
    data class Article(val id: String, val title: String, val summary: String) : WhisperContent

    /** 系统通知(10)。它自带标题,不是某个人说的话。 */
    data class Notice(val title: String, val text: String) : WhisperContent

    /**
     * 客户端提示(18)。**不是谁说的话**,是 B 站塞进会话里的一条灰字规则说明,实测内容为
     * `对方主动回复或关注你前，最多发送1条消息`,自带日间/夜间两个颜色,而 `sender_uid`
     * 记的是**你自己** —— 照消息画就成了一条你发出去的、靠右的气泡。
     *
     * 会话里不显示(见 [MessageRepository.messages]);会话列表若最后一条正是它,拿它的文字
     * 当摘要,总好过空一行。
     */
    data class Hint(val text: String) : WhisperContent

    /** 认不出来的类型。带上类型号,下次要补哪一种一看便知。 */
    data class Unsupported(val msgType: Int) : WhisperContent
}

/**
 * 消息中心与私信。
 *
 * **两组接口分属两个主机**:消息中心在 `api.bilibili.com`(以及系统通知的
 * `message.bilibili.com`),私信在 `api.vc.bilibili.com`。事实与实测结论见
 * `notes/private-message.md`——尤其是"读不需要 WBI 签名"和"发送必须带 `msg[dev_id]`"这两条。
 *
 * **不走 gRPC。** PiliPlus 现在整条私信栈都在 gRPC 上,而这几条 HTTP 接口实测仍然可用
 * (2026-08-12),省掉一个分帧 + protobuf writer + 五个 metadata header 的子系统。
 */
class MessageRepository(private val client: BiliClient) {

    suspend fun replies(cursor: NoticeCursor? = null): BiliResult<NoticePage> =
        client.getData<MsgFeedDto>(REPLY_URL, feedParams(cursor, "reply_time"))
            .map { dto -> dto.toPage { it.toReplyNotice() } }

    suspend fun mentions(cursor: NoticeCursor? = null): BiliResult<NoticePage> =
        client.getData<MsgFeedDto>(AT_URL, feedParams(cursor, "at_time"))
            .map { dto -> dto.toPage { it.toMentionNotice() } }

    /**
     * 收到的赞。**响应里有 `latest` 和 `total` 两组**,只取 total:latest 是"最近一次查看
     * 之后的新增",它和 total 的前几条是同一批内容,两组都画就是同一条赞出现两遍。
     */
    suspend fun likes(cursor: NoticeCursor? = null): BiliResult<NoticePage> =
        client.getData<MsgLikeDto>(LIKE_URL, feedParams(cursor, "like_time"))
            .map { dto -> (dto.total ?: MsgFeedDto()).toPage { it.toLikeNotice() } }

    /**
     * 系统通知。**换了个主机**(`message.bilibili.com`),游标也换成单个 `cursor`,
     * 不是消息中心那对 (id, time)。
     */
    suspend fun sysNotices(cursor: Long? = null): BiliResult<List<SysNotice>> =
        client.getData<List<SysNoticeDto>>(
            SYS_URL,
            buildMap {
                put("page_size", PAGE_SIZE.toString())
                put("mobi_app", "web")
                put("build", "0")
                cursor?.let { put("cursor", it.toString()) }
            },
        ).map { list ->
            list.map {
                SysNotice(
                    id = it.id,
                    cursor = it.cursor,
                    title = it.title,
                    content = it.content,
                    timeText = it.timeAt,
                )
            }
        }

    /**
     * 会话列表。**服务端只给 `talker_id`,名字和头像要另查一次**(`account/v1/user/cards`),
     * 所以这里连着发两个请求再拼起来——让 UI 拿着一串裸 mid 去逐个查会变成一屏几十个请求。
     */
    suspend fun sessions(): BiliResult<List<WhisperSession>> {
        val listed: BiliResult<SessionListDto> = client.getData(
            SESSIONS_URL,
            mapOf(
                "session_type" to "1",
                "group_fold" to "1",
                "unfollow_fold" to "0",
                "sort_rule" to "2",
                "build" to "0",
                "mobi_app" to "web",
            ),
            referer = MESSAGE_REFERER,
        )
        val sessions = when (listed) {
            is BiliResult.Ok -> listed.value.sessionList
            is BiliResult.ApiError -> return listed
            is BiliResult.Failure -> return listed
        }
        val cards = userCards(sessions.map { it.talkerId })
        return BiliResult.Ok(
            sessions.map { session ->
                val card = cards[session.talkerId]
                WhisperSession(
                    talkerId = session.talkerId,
                    name = card?.name.orEmpty(),
                    faceUrl = card?.face.orEmpty().toHttpsUrl(),
                    lastMessage = MessageContent.parse(
                        session.lastMsg?.msgType ?: 0,
                        session.lastMsg?.content.orEmpty(),
                    ).summarize(),
                    unreadCount = session.unreadCount,
                    timeSeconds = session.sessionTs / MICROS_PER_SECOND,
                    maxSeqno = session.maxSeqno,
                    isSystem = card == null,
                )
            },
        )
    }

    /**
     * 一个会话里的消息。服务端给的是**新的在前**,这里翻过来 —— 聊天要从上往下读。
     */
    suspend fun messages(talkerId: Long, size: Int = PAGE_SIZE): BiliResult<List<WhisperMessage>> =
        client.getData<SessionMessagesDto>(
            MESSAGES_URL,
            mapOf(
                "talker_id" to talkerId.toString(),
                "session_type" to "1",
                "size" to size.toString(),
                "sender_device_id" to "1",
                "build" to "0",
                "mobi_app" to "web",
            ),
            referer = MESSAGE_REFERER,
        ).map { dto ->
            // **提示条不进会话。** 它不是消息,而且记在自己名下,画出来就是一条自己发的气泡
            // (见 [WhisperContent.Hint])。丢在这一层而不是界面上:"这一条不是消息"是关于
            // 数据的事实,不是显示偏好。
            dto.messages.asReversed().filter { it.msgType != HINT_MSG_TYPE }.map {
                WhisperMessage(
                    seqno = it.msgSeqno,
                    senderUid = it.senderUid,
                    content = MessageContent.parse(it.msgType, it.content),
                    timeSeconds = it.timestamp,
                )
            }
        }

    /**
     * 发一条私信(notes/private-message.md §3,实测过)。
     *
     * **`msg[dev_id]` 每次现生成一个 UUID**,不是设备级持久化的标识。少了它稳定回 -400,
     * 这是那一组参数里唯一一个去掉就挂的;而 PiliPlus 两条路(注释掉的 HTTP 与在用的 gRPC)
     * 都是每次新生成,实测服务端接受。
     *
     * 正文要包一层 JSON(`{"content":"..."}`),不是裸文本 —— 私信的 content 字段本身就是
     * 一个按 `msg_type` 变形状的 JSON 字符串。
     */
    suspend fun send(senderUid: Long, receiverId: Long, text: String): BiliResult<Unit> =
        client.postAction(
            url = SEND_URL,
            form = mapOf(
                "msg[sender_uid]" to senderUid.toString(),
                "msg[receiver_id]" to receiverId.toString(),
                "msg[receiver_type]" to "1",
                "msg[msg_type]" to MessageContent.TEXT.toString(),
                "msg[msg_status]" to "0",
                "msg[dev_id]" to UUID.randomUUID().toString(),
                "msg[timestamp]" to (System.currentTimeMillis() / 1000).toString(),
                "msg[new_face_version]" to "1",
                "msg[content]" to MessageContent.wrapText(text),
                "from_firework" to "0",
                "build" to "0",
                "mobi_app" to "web",
            ),
            csrfTokenAlias = true,
            referer = MESSAGE_REFERER,
        )

    /** 标记读到哪儿了。失败不影响任何界面,调用方按"尽力而为"处理。 */
    suspend fun ack(talkerId: Long, ackSeqno: Long): BiliResult<Unit> = client.postAction(
        url = ACK_URL,
        form = mapOf(
            "talker_id" to talkerId.toString(),
            "session_type" to "1",
            "ack_seqno" to ackSeqno.toString(),
            "build" to "0",
            "mobi_app" to "web",
        ),
        csrfTokenAlias = true,
        referer = MESSAGE_REFERER,
    )

    private suspend fun userCards(mids: List<Long>): Map<Long, UserCardDto> {
        if (mids.isEmpty()) return emptyMap()
        val result: BiliResult<List<UserCardDto>> = client.getData(
            CARDS_URL,
            mapOf("uids" to mids.joinToString(",")),
            referer = MESSAGE_REFERER,
        )
        // 查不到名字不该让整个会话列表失败:那时列表照画,只是少了名字和头像。
        return (result as? BiliResult.Ok)?.value?.associateBy { it.mid }.orEmpty()
    }

    private fun feedParams(cursor: NoticeCursor?, timeKey: String): Map<String, String> = buildMap {
        put("platform", "web")
        put("mobi_app", "web")
        put("build", "0")
        cursor?.let {
            put("id", it.id.toString())
            put(timeKey, it.timeSeconds.toString())
        }
    }

    private companion object {
        const val REPLY_URL = "${BiliConstants.WEB_HOST}/x/msgfeed/reply"
        const val AT_URL = "${BiliConstants.WEB_HOST}/x/msgfeed/at"
        const val LIKE_URL = "${BiliConstants.WEB_HOST}/x/msgfeed/like"
        const val SYS_URL = "https://message.bilibili.com/x/sys-msg/query_notify_list"

        const val VC_HOST = "https://api.vc.bilibili.com"
        const val SESSIONS_URL = "$VC_HOST/session_svr/v1/session_svr/get_sessions"
        const val MESSAGES_URL = "$VC_HOST/svr_sync/v1/svr_sync/fetch_session_msgs"
        const val SEND_URL = "$VC_HOST/web_im/v1/web_im/send_msg"
        const val ACK_URL = "$VC_HOST/session_svr/v1/session_svr/update_ack"
        const val CARDS_URL = "$VC_HOST/account/v1/user/cards"

        /** 私信这几条要指到消息中心,不是站点首页。 */
        const val MESSAGE_REFERER = "https://message.bilibili.com/"

        const val PAGE_SIZE = 20

        /** 见 [WhisperContent.Hint]。 */
        const val HINT_MSG_TYPE = 18
        const val MICROS_PER_SECOND = 1_000_000L
    }
}

/**
 * 私信正文。**`content` 是一个 JSON 字符串,形状由 `msg_type` 决定**,不是纯文本 ——
 * 当成文本直接显示会在分享卡片上露出一整坨 JSON(notes/private-message.md §2)。
 */
private object MessageContent {

    const val TEXT = 1
    private const val SHARE = 7
    private const val NOTICE = 10
    private const val VIDEO_PUSH = 11
    private const val ARTICLE = 12
    private const val HINT = 18

    private val json = Json { ignoreUnknownKeys = true }

    fun wrapText(text: String): String = json.encodeToString(TextContent(text))

    /**
     * 按类型解出内容。解析失败(服务端换了形状、字段缺失)一律退到
     * [WhisperContent.Unsupported],不抛 —— 一条读不懂的消息不该让整个会话打不开。
     */
    fun parse(msgType: Int, raw: String): WhisperContent {
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return WhisperContent.Unsupported(msgType)
        fun field(key: String) = obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (msgType) {
            TEXT -> WhisperContent.Text(field("content"))

            // 7 的封面字段叫 thumb,11 叫 cover;标题都叫 title。归一到一种东西之后,
            // 界面上它们本来也该长得一样 —— 都是"一条可以点开的视频"。
            SHARE, VIDEO_PUSH -> {
                val bvid = field("bvid")
                if (bvid.isEmpty()) {
                    WhisperContent.Unsupported(msgType)
                } else {
                    WhisperContent.Video(
                        bvid = bvid,
                        title = field("title"),
                        coverUrl = field("thumb").ifEmpty { field("cover") }.toHttpsUrl(),
                    )
                }
            }

            ARTICLE -> WhisperContent.Article(
                id = field("rid"),
                title = field("title"),
                summary = field("summary"),
            )

            NOTICE -> WhisperContent.Notice(title = field("title"), text = field("text"))

            // 里层还套一层 JSON:`content` 是一个 `[{text,color_day,color_nig}]` 的**字符串**。
            // 只取文字,颜色不要 —— 那两个色是照 B 站自己的主题定的,搬过来在深色主题下糊。
            HINT -> WhisperContent.Hint(
                runCatching {
                    json.decodeFromString<List<HintSegment>>(field("content")).joinToString("") { it.text }
                }.getOrDefault(""),
            )

            else -> WhisperContent.Unsupported(msgType)
        }
    }
}

/**
 * 会话列表那一行的"最后一句话"。视频和专栏折成标题,系统通知折成它的标题 —— 这一行只有
 * 一行高,而完整的样子在会话里。
 */
private fun WhisperContent.summarize(): String = when (this) {
    is WhisperContent.Text -> text
    is WhisperContent.Hint -> text
    is WhisperContent.Video -> title
    is WhisperContent.Article -> title
    is WhisperContent.Notice -> title
    is WhisperContent.Unsupported -> ""
}

@Serializable
private data class TextContent(val content: String)

/** 提示条里的一段。颜色字段不要,理由见解析处。 */
@Serializable
private data class HintSegment(val text: String = "")

@Serializable
private data class MsgFeedDto(
    val cursor: NoticeCursorDto? = null,
    val items: List<NoticeItemDto> = emptyList(),
)

@Serializable
private data class MsgLikeDto(val total: MsgFeedDto? = null)

@Serializable
private data class NoticeCursorDto(
    @SerialName("is_end") val isEnd: Boolean = false,
    val id: Long = 0,
    val time: Long = 0,
)

@Serializable
private data class NoticeItemDto(
    val id: Long = 0,
    val user: NoticeUserDto? = null,
    /** 赞是聚合的:同一个对象被多个人赞,`users` 里是所有人,`counts` 是总数。 */
    val users: List<NoticeUserDto> = emptyList(),
    val item: NoticeContentDto? = null,
    val counts: Int = 0,
    @SerialName("reply_time") val replyTime: Long = 0,
    @SerialName("at_time") val atTime: Long = 0,
    @SerialName("like_time") val likeTime: Long = 0,
)

@Serializable
private data class NoticeUserDto(
    val mid: Long = 0,
    val nickname: String = "",
    val avatar: String = "",
)

@Serializable
private data class NoticeContentDto(
    val title: String = "",
    val uri: String = "",
    @SerialName("source_content") val sourceContent: String = "",
    @SerialName("target_reply_content") val targetReplyContent: String = "",
)

@Serializable
private data class SysNoticeDto(
    val id: Long = 0,
    val cursor: Long = 0,
    val title: String = "",
    val content: String = "",
    @SerialName("time_at") val timeAt: String = "",
)

@Serializable
private data class SessionListDto(
    @SerialName("session_list") val sessionList: List<SessionDto> = emptyList(),
)

@Serializable
private data class SessionDto(
    @SerialName("talker_id") val talkerId: Long = 0,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("session_ts") val sessionTs: Long = 0,
    @SerialName("max_seqno") val maxSeqno: Long = 0,
    @SerialName("last_msg") val lastMsg: SessionMessageDto? = null,
)

@Serializable
private data class SessionMessagesDto(val messages: List<SessionMessageDto> = emptyList())

/**
 * `msg_seqno` 与 `msg_key` 都是 int64(实测量级 7.6e18),**必须按 Long 收**:
 * 当成 Double 会在末几位丢精度,而 seqno 是标记已读的凭据。
 */
@Serializable
private data class SessionMessageDto(
    @SerialName("sender_uid") val senderUid: Long = 0,
    @SerialName("msg_type") val msgType: Int = 0,
    val content: String = "",
    @SerialName("msg_seqno") val msgSeqno: Long = 0,
    val timestamp: Long = 0,
)

@Serializable
private data class UserCardDto(
    val mid: Long = 0,
    val name: String = "",
    val face: String = "",
)

private fun MsgFeedDto.toPage(map: (NoticeItemDto) -> Notice): NoticePage = NoticePage(
    items = items.map(map),
    nextCursor = cursor?.takeIf { !it.isEnd && it.id != 0L }?.let { NoticeCursor(it.id, it.time) },
)

private fun NoticeItemDto.toReplyNotice() = Notice(
    id = id,
    avatarUrl = user?.avatar.orEmpty().toHttpsUrl(),
    name = user?.nickname.orEmpty(),
    actorCount = 1,
    body = item?.sourceContent.orEmpty(),
    quoted = item?.targetReplyContent?.takeIf { it.isNotBlank() },
    timeSeconds = replyTime,
    uri = item?.uri.orEmpty(),
)

private fun NoticeItemDto.toMentionNotice() = Notice(
    id = id,
    avatarUrl = user?.avatar.orEmpty().toHttpsUrl(),
    name = user?.nickname.orEmpty(),
    actorCount = 1,
    body = item?.sourceContent.orEmpty(),
    quoted = null,
    timeSeconds = atTime,
    uri = item?.uri.orEmpty(),
)

/** 赞没有"正文",被赞的是一个对象,所以正文位置放它的标题。 */
private fun NoticeItemDto.toLikeNotice() = Notice(
    id = id,
    avatarUrl = users.firstOrNull()?.avatar.orEmpty().toHttpsUrl(),
    name = users.firstOrNull()?.nickname.orEmpty(),
    actorCount = counts.coerceAtLeast(users.size),
    body = item?.title.orEmpty(),
    quoted = null,
    timeSeconds = likeTime,
    uri = item?.uri.orEmpty(),
)
