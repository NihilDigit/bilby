package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.postAction
import dev.bilby.api.toHttpsUrl
import dev.bilby.data.model.RichLinkIcon
import dev.bilby.data.model.RichSpan
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 消息中心的一条。回复/@/赞三类结构不同,但界面上是同一种行,所以在这一层就归一
 * ——让 UI 去认三种 DTO 只会把"哪个字段是正文"这个问题散到三处。
 *
 * @param body 对方写的那段话。赞没有正文,为空串。
 * @param quoted 这条通知指向的、属于用户自己的那样东西:被回复的评论原文,或被评论、被赞、
 *   在其中被 @ 的那条视频或动态的标题。它是语境:没有它,一句"说得对"读不出在说什么。
 * @param uri 点开去哪儿。服务端给的是站内链接,交给 [dev.bilby.ui.BilbyLink] 解析,
 *   与从别的 app 分享进来的链接同一条路。
 */
data class Notice(
    val id: Long,
    /** 做这件事的人。赞是聚合的,这里是排第一的那位;0 表示接口没给。 */
    val actorMid: Long,
    val avatarUrl: String,
    val name: String,
    /** 一起做了这件事的人数。赞是聚合的,1 表示只有一个人。 */
    val actorCount: Int,
    val kind: NoticeKind,
    /**
     * 被回应的东西是哪一类,服务端给的中文名(`视频`、`评论`、`动态`),原样拼进界面上的动作
     * 那一行。不在本地从 `business_id` 映射:那张表服务端随时加项,名字它自己就给了。
     */
    val business: String,
    val body: String,
    val quoted: String?,
    /** 被回应那样东西的缩略图(视频封面、动态首图)。回复通知里接口恒给空串,见 notes。 */
    val imageUrl: String,
    val timeSeconds: Long,
    val uri: String,
    /**
     * 客户端 scheme 形式的去处。回复、@、被赞的评论在这里带着评论定位,点开落到评论详情页
     * 而不是整个视频;认不出评论定位时退回 [uri]。
     */
    val nativeUri: String,
    /** 评论区的 oid 与类型。native_uri 只给了动态 id 时,用它们定位评论区。0 表示没给。 */
    val subjectId: Long,
    val businessId: Int,
)

/**
 * 通知说的是哪一件事。**回复与评论分开**:同一个 `msgfeed/reply` 接口里,有人回了用户的
 * 评论(带 `target_reply_content`)和有人在用户的视频下面发了一条评论是两件事,前者的语境是
 * 那条评论,后者是那个视频。
 */
enum class NoticeKind { Reply, Comment, Mention, Like }

/**
 * 一页。[next] 为 null 即到底了。游标的形状各接口不同:消息中心是 (id, time) 一对,系统通知
 * 是最后一条自带的 `cursor`,私信会话列表是最后一个会话的微秒时间戳。
 */
data class MessagePage<T, C>(val items: List<T>, val next: C?)

/** 消息中心的游标是 (id, time) 一对,不是页号。 */
data class NoticeCursor(val id: Long, val timeSeconds: Long)

/** 系统通知。它没有"谁"这个概念,只有标题和正文。 */
data class SysNotice(
    val id: Long,
    val cursor: Long,
    val title: String,
    /** 正文里嵌着 `#{文字}{"链接"}` 这种标记,已经解成可点的链接,见 [sysNoticeSpans]。 */
    val content: List<RichSpan>,
    /** 服务端给的是拼好的字符串(“2026-08-12 10:00”),不是时间戳。 */
    val timeText: String,
)

/**
 * 一个私信会话。
 *
 * [lastMessage] 已经按 [MessageContent] 解出来了,折成一行字的事留给界面:那句话可能是一张
 * 图片或一条撤回,它们的说法是界面文案。
 *
 * **不带未读数。** 接口给 `unread_count`,这里不收:界面上不画红点也不画数字(DESIGN 1.3),
 * 一个没人读的字段只会等着哪天被人顺手画出来。
 */
data class WhisperSession(
    val talkerId: Long,
    val name: String,
    val faceUrl: String,
    val lastMessage: WhisperContent,
    /** 服务端给的是**微秒**,这里已经折成秒。 */
    val timeSeconds: Long,
    /**
     * 这个会话对面不是一个人。
     *
     * 判据有两条,任一成立即是:会话的 `system_msg_type` 不为 0(文档镜像),或者
     * `account/v1/user/cards` **查不到这个 id** —— 实测系统通知号(`talker_id=844424930131965`
     * 这种量级)对它返回空数组。这类会话的名字和头像改取会话自带的 `account_info`。
     */
    val isSystem: Boolean,
    /** 系统会话是哪一种,普通会话为 null。见 [SystemSessionKind]。 */
    val systemKind: SystemSessionKind?,
    /**
     * 最后一条是对方发来的投稿推送([WhisperContent.VideoPush])。会话列表把这类会话收进
     * 「UP 主推送」一栏,不和人说的话混排。判据只看最后一条:用户回一句之后最后一条变成文字,
     * 这个会话自然回到主列表,不需要另记状态。
     */
    val lastIsUpPush: Boolean,
)

/**
 * 系统会话的种类,取自会话对象的 `system_msg_type`(文档镜像 private_msg.md 会话对象一节:
 * 1 主播小助手、7 UP 主小助手、8 客服消息、9 支付小助手)。**只收文档写实的几种**:文档把 5
 * 记作"系统通知(?)",带着问号,不据此单列;它和其余没见过的值一起落到 [Other]。
 * 查不到用户卡片、却又没有 system_msg_type 的会话也算 [Other]。
 */
enum class SystemSessionKind { LiveAssistant, UpAssistant, CustomerService, PayAssistant, Other }

/** 会话里的一条消息。撤回了的那条,[content] 已经换成 [WhisperContent.Withdrawn]。 */
data class WhisperMessage(
    val seqno: Long,
    val senderUid: Long,
    val content: WhisperContent,
    val timeSeconds: Long,
)

/**
 * 一段会话记录。
 *
 * [oldestSeqno] 与 [newestSeqno] 取自**过滤之前**的原始条目:撤回指令那种被丢掉的条目也占着
 * 序列号,按过滤后的首尾去续页,会把同一段再取一遍。
 */
data class WhisperPage(
    val messages: List<WhisperMessage>,
    /** 比 [oldestSeqno] 更早的还有没有。 */
    val hasOlder: Boolean,
    val oldestSeqno: Long?,
    val newestSeqno: Long?,
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

    /** 文字(1)。表情已经按 `e_infos` 换成了图,见 [MessageContent.textSpans]。 */
    data class Text(val spans: List<RichSpan>) : WhisperContent

    /**
     * 图片(2)与自定义表情(6)。两者字段相同,[sticker] 区分它们:会话列表的摘要要说清是哪一种。
     * [width]、[height] 是发送方填的像素尺寸,可能缺(为 0),界面据此定比例。
     */
    data class Image(val url: String, val width: Int, val height: Int, val sticker: Boolean) : WhisperContent

    /** 分享(7)里的一条视频:某个人把它发给了用户,是那个人说的一句话。 */
    data class Video(val bvid: String, val title: String, val coverUrl: String) : WhisperContent

    /**
     * UP 主的投稿推送(11,PiliPlus 叫 EN_MSG_TYPE_VIDEO_CARD)。**与分享分开**:它不是对方
     * 打给用户的一句话,是关注了这位 UP 之后服务端代发的新投稿通知,PiliPlus 把它和系统通知
     * 归为一类,画成居中的卡片而不是左侧气泡(`whisper_detail/widget/chat_item.dart` 的
     * isSystem 列表)。
     *
     * @param durationSeconds 接口的 `times`,0 表示不知道,那时不画时长角标。
     * @param note UP 主附的一句话(`attach_msg.content`),没写时为空。表情按这条消息的
     *   `e_infos` 解开,多是充电专属表情;接口在前面自带的「UP主赠言：」去掉,会话页把它画成
     *   UP 主的一个气泡,谁说的已经看得出来。
     */
    data class VideoPush(
        val bvid: String,
        val title: String,
        val coverUrl: String,
        val durationSeconds: Long,
        val note: List<RichSpan>,
    ) : WhisperContent

    /**
     * 专栏(12),以及分享类型为专栏的 7。`rid` 与分享里的 `id` 都是 **cv 号**,不是 opus id,
     * 打开时要走旧版专栏那套接口(见 `ui/Destinations.kt` 的 ArticlePage)。
     */
    data class Article(val id: String, val title: String, val summary: String, val coverUrl: String) : WhisperContent

    /**
     * 其余能点开的卡片:图片卡片(13)、分享其他内容(14,常见的是直播间),以及分享类型不是
     * 视频也不是专栏的 7。点开交给链接解析,认得的在应用内落地,认不得的去浏览器。
     */
    data class Link(val title: String, val subtitle: String, val coverUrl: String, val url: String) : WhisperContent

    /**
     * 系统通知(10)。它自带标题,不是某个人说的话。[jumpUrl] 是通知底部那个按钮的去处,
     * 为空即没有按钮;[jumpText] 是按钮上的字,可能为空(官方前端那时写「查看详情」)。
     */
    data class Notice(val title: String, val text: String, val jumpText: String, val jumpUrl: String) : WhisperContent

    /**
     * 客户端提示(18)。**不是谁说的话**,是 B 站塞进会话里的一条灰字规则说明,实测内容为
     * `对方主动回复或关注你前，最多发送1条消息`,自带日间/夜间两个颜色,而 `sender_uid`
     * 记的是**你自己** —— 照消息画就成了一条你发出去的、靠右的气泡。
     *
     * 会话里画成居中的一行小字,不画成气泡;它说的正是"为什么这条发不出去",藏掉它,人只能
     * 对着一次次失败的发送猜原因。
     */
    data class Hint(val text: String) : WhisperContent

    /**
     * 撤回了的消息(`msg_status=1`),以及会话列表摘要里那条撤回指令本身(5)。
     *
     * **原文不留。** 接口仍然返回被撤回的内容(文档镜像记为设计缺陷),PiliPlus 照原样画出来再
     * 标一句"已撤回"。这里在数据层就丢掉:撤回是发送者收回的意思,留着原文等于替他没收回。
     */
    data object Withdrawn : WhisperContent

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

    suspend fun replies(cursor: NoticeCursor? = null): BiliResult<MessagePage<Notice, NoticeCursor>> =
        client.getData<MsgFeedDto>(REPLY_URL, feedParams(cursor, "reply_time"))
            .map { dto -> dto.toPage { it.toReplyNotice() } }

    suspend fun mentions(cursor: NoticeCursor? = null): BiliResult<MessagePage<Notice, NoticeCursor>> =
        client.getData<MsgFeedDto>(AT_URL, feedParams(cursor, "at_time"))
            .map { dto -> dto.toPage { it.toMentionNotice() } }

    /**
     * 收到的赞。**响应里有 `latest` 和 `total` 两组**,只取 total:latest 是"最近一次查看
     * 之后的新增",它和 total 的前几条是同一批内容,两组都画就是同一条赞出现两遍。
     */
    suspend fun likes(cursor: NoticeCursor? = null): BiliResult<MessagePage<Notice, NoticeCursor>> =
        client.getData<MsgLikeDto>(LIKE_URL, feedParams(cursor, "like_time"))
            .map { dto -> (dto.total ?: MsgFeedDto()).toPage { it.toLikeNotice() } }

    /**
     * 系统通知。**换了个主机**(`message.bilibili.com`),游标也换成单个 `cursor`,
     * 不是消息中心那对 (id, time):续页用上一页最后一条自带的那个值,这个接口没有另给游标。
     * 到底的判据是回来一页空的。
     */
    suspend fun sysNotices(cursor: Long? = null): BiliResult<MessagePage<SysNotice, Long>> =
        client.getData<List<SysNoticeDto>>(
            SYS_URL,
            buildMap {
                put("page_size", PAGE_SIZE.toString())
                put("mobi_app", "web")
                put("build", "0")
                cursor?.let { put("cursor", it.toString()) }
            },
        ).map { list ->
            MessagePage(
                items = list.map {
                    SysNotice(
                        id = it.id,
                        cursor = it.cursor,
                        title = it.title,
                        content = sysNoticeSpans(it.content),
                        timeText = it.timeAt,
                    )
                },
                next = list.lastOrNull()?.cursor,
            )
        }

    /**
     * 会话列表,一页 20 个。**服务端只给 `talker_id`,名字和头像要另查一次**
     * (`account/v1/user/cards`),所以这里连着发两个请求再拼起来——让 UI 拿着一串裸 mid 去
     * 逐个查会变成一屏几十个请求。系统会话不查:它们查不到,名字和头像在会话自带的
     * `account_info` 里。
     *
     * 续页传上一页最后一个会话的 `session_ts`(微秒)作 `end_ts`,判据是响应的 `has_more`
     * (notes/private-message.md §5)。
     */
    suspend fun sessions(endTsMicros: Long? = null): BiliResult<MessagePage<WhisperSession, Long>> {
        val listed: BiliResult<SessionListDto> = client.getData(
            SESSIONS_URL,
            buildMap {
                put("session_type", "1")
                put("group_fold", "1")
                put("unfollow_fold", "0")
                put("sort_rule", "2")
                put("build", "0")
                put("mobi_app", "web")
                endTsMicros?.let { put("end_ts", it.toString()) }
            },
            referer = MESSAGE_REFERER,
        )
        val page = when (listed) {
            is BiliResult.Ok -> listed.value
            is BiliResult.ApiError -> return listed
            is BiliResult.Failure -> return listed
        }
        val sessions = page.sessionList
        val cards = userCards(sessions.filter { it.systemMsgType == 0 }.map { it.talkerId })
        return BiliResult.Ok(
            MessagePage(
                items = sessions.map { session ->
                    val card = cards[session.talkerId]
                    val account = session.accountInfo
                    val lastMessage = session.lastMsg?.let { MessageContent.parse(it) }
                        ?: WhisperContent.Unsupported(0)
                    val isSystem = session.systemMsgType != 0 || card == null
                    WhisperSession(
                        talkerId = session.talkerId,
                        name = card?.name ?: account?.name.orEmpty(),
                        faceUrl = (card?.face ?: account?.picUrl).orEmpty().toHttpsUrl(),
                        lastMessage = lastMessage,
                        timeSeconds = session.sessionTs / MICROS_PER_SECOND,
                        isSystem = isSystem,
                        systemKind = if (isSystem) systemKindOf(session.systemMsgType) else null,
                        // 推送的发送方是 UP 主本人,即会话对面;用 talker_id 比,不必知道自己的 mid。
                        lastIsUpPush = lastMessage is WhisperContent.VideoPush &&
                            session.lastMsg?.senderUid == session.talkerId,
                    )
                },
                next = if (page.hasMore == 1) sessions.lastOrNull()?.sessionTs else null,
            ),
        )
    }

    /**
     * 一个会话里的一段消息。服务端给的是**新的在前**,这里翻过来,按时间先后排。
     *
     * @param beforeSeqno 取比它更早的一段(`end_seqno`,不含它本身)。往上翻旧消息用。
     * @param afterSeqno 取比它更晚的一段(`begin_seqno`,不含它本身)。发送成功后补上新的那几条,
     *   不整段重拉 —— 整段重拉会把已经往上翻出来的旧消息丢掉。
     */
    suspend fun messages(
        talkerId: Long,
        beforeSeqno: Long? = null,
        afterSeqno: Long? = null,
        size: Int = PAGE_SIZE,
    ): BiliResult<WhisperPage> =
        client.getData<SessionMessagesDto>(
            MESSAGES_URL,
            buildMap {
                put("talker_id", talkerId.toString())
                put("session_type", "1")
                put("size", size.toString())
                put("sender_device_id", "1")
                put("build", "0")
                put("mobi_app", "web")
                beforeSeqno?.let { put("end_seqno", it.toString()) }
                afterSeqno?.let { put("begin_seqno", it.toString()) }
            },
            referer = MESSAGE_REFERER,
        ).map { dto ->
            val emotes = dto.emotes.associateBy { it.text }
            WhisperPage(
                // **撤回指令不进会话。** 它是一条"把某条消息撤回"的指令,内容只是目标的
                // msg_key;被撤回的那条自己带着 msg_status=1,由它来显示。丢在这一层而不是界面上:
                // "这一条不是消息"是关于数据的事实,不是显示偏好。PiliPlus 同样在拉到之后
                // 就移除(`whisper_detail/controller.dart`)。
                messages = dto.messages.asReversed().filter { it.msgType != WITHDRAW_MSG_TYPE }.map {
                    WhisperMessage(
                        seqno = it.msgSeqno,
                        senderUid = it.senderUid,
                        content = MessageContent.parse(it, emotes),
                        timeSeconds = it.timestamp,
                    )
                },
                hasOlder = dto.hasMore == 1,
                // 响应里另有 min_seqno / max_seqno,不用:会话为空时 min_seqno 是
                // 18446744073709551615,超出 Long,按 Long 收会让整个请求解析失败。
                oldestSeqno = dto.messages.minOfOrNull { it.msgSeqno },
                newestSeqno = dto.messages.maxOfOrNull { it.msgSeqno },
            )
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

    private fun systemKindOf(systemMsgType: Int): SystemSessionKind = when (systemMsgType) {
        1 -> SystemSessionKind.LiveAssistant
        7 -> SystemSessionKind.UpAssistant
        8 -> SystemSessionKind.CustomerService
        9 -> SystemSessionKind.PayAssistant
        else -> SystemSessionKind.Other
    }

    /**
     * 一批用户的头像,mid 到 URL。查不到的不在结果里。
     *
     * 走会话列表查名字的同一条 `account/v1/user/cards`,原样照发(含 Referer):直播间「本场早前」
     * 的醒目留言来自 danmakus.com,那边只给 mid 不给头像,要在这里补。每次最多
     * [CARDS_BATCH] 个,与会话列表一页的量相同,更大的批量没有实测过。
     */
    suspend fun userFaces(mids: Collection<Long>): Map<Long, String> =
        mids.distinct().chunked(CARDS_BATCH).flatMap { batch ->
            userCards(batch).map { (mid, card) -> mid to card.face.toHttpsUrl() }
        }.filter { it.second.isNotEmpty() }.toMap()

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

        const val CARDS_BATCH = PAGE_SIZE

        /** 撤回指令,见 [messages] 里的过滤。 */
        const val WITHDRAW_MSG_TYPE = 5
        const val MICROS_PER_SECOND = 1_000_000L
    }
}

/**
 * 私信正文。**`content` 是一个 JSON 字符串,形状由 `msg_type` 决定**,不是纯文本 ——
 * 当成文本直接显示会在分享卡片上露出一整坨 JSON(notes/private-message.md §2)。
 */
private object MessageContent {

    const val TEXT = 1
    private const val IMAGE = 2
    private const val WITHDRAW = 5
    private const val STICKER = 6
    private const val SHARE = 7
    private const val NOTICE = 10
    private const val VIDEO_PUSH = 11
    private const val ARTICLE = 12
    private const val PICTURE_CARD = 13
    private const val COMMON_SHARE = 14
    private const val HINT = 18

    /** 分享(7)的 `source`:5 是视频,6 是专栏,11 是动态。其余见 notes/private-message.md §6。 */
    private const val SHARE_SOURCE_ARTICLE = 6
    private const val SHARE_SOURCE_DYNAMIC = 11

    /** `msg_status` 为 1:这条被发送者撤回了。 */
    private const val STATUS_WITHDRAWN = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun wrapText(text: String): String = json.encodeToString(TextContent(text))

    fun parse(message: SessionMessageDto, emotes: Map<String, EmoteInfoDto> = emptyMap()): WhisperContent =
        if (message.msgStatus == STATUS_WITHDRAWN) {
            WhisperContent.Withdrawn
        } else {
            parse(message.msgType, message.content, emotes)
        }

    /**
     * 按类型解出内容。解析失败(服务端换了形状、字段缺失)一律退到
     * [WhisperContent.Unsupported],不抛 —— 一条读不懂的消息不该让整个会话打不开。
     */
    private fun parse(msgType: Int, raw: String, emotes: Map<String, EmoteInfoDto>): WhisperContent {
        // 撤回指令的 content 是一个裸的数字(目标的 msg_key),不是 JSON 对象。它只会出现在
        // 会话列表的摘要里,会话本身在取数时就把它滤掉了。
        if (msgType == WITHDRAW) return WhisperContent.Withdrawn
        val obj = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return WhisperContent.Unsupported(msgType)
        fun field(key: String) = obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun nested(key: String, inner: String) =
            (obj[key] as? JsonObject)?.get(inner)?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (msgType) {
            TEXT -> WhisperContent.Text(textSpans(field("content"), emotes))

            IMAGE, STICKER -> field("url").takeIf { it.isNotEmpty() }?.let { url ->
                WhisperContent.Image(
                    url = url.toHttpsUrl(),
                    width = field("width").toDoubleOrNull()?.toInt() ?: 0,
                    height = field("height").toDoubleOrNull()?.toInt() ?: 0,
                    sticker = msgType == STICKER,
                )
            } ?: WhisperContent.Unsupported(msgType)

            VIDEO_PUSH -> field("bvid").takeIf { it.isNotEmpty() }?.let { bvid ->
                WhisperContent.VideoPush(
                    bvid = bvid,
                    title = field("title"),
                    coverUrl = field("cover").toHttpsUrl(),
                    durationSeconds = field("times").toDoubleOrNull()?.toLong() ?: 0L,
                    note = nested("attach_msg", "content")
                        .replaceFirst(NotePrefix, "")
                        .takeIf { it.isNotBlank() }
                        ?.let { textSpans(it, emotes) }
                        .orEmpty(),
                )
            } ?: WhisperContent.Unsupported(msgType)

            SHARE -> {
                val id = field("id")
                val title = field("title").ifEmpty { field("headline") }
                val source = field("source").toIntOrNull()
                videoOrNull(field("bvid"), title, field("thumb"))
                    ?: if (source == SHARE_SOURCE_ARTICLE && id.isNotEmpty()) {
                        WhisperContent.Article(
                            id = id,
                            title = title,
                            summary = field("author"),
                            coverUrl = field("thumb").toHttpsUrl(),
                        )
                    } else {
                        // 动态的分享常常不带 url,按网页版的地址补一个;别的类型没有 url 就点不开,
                        // 只能认作不支持,不画一张点了没反应的卡片。
                        val url = field("url").ifEmpty {
                            if (source == SHARE_SOURCE_DYNAMIC && id.isNotEmpty()) "https://t.bilibili.com/$id" else ""
                        }
                        if (url.isEmpty()) {
                            WhisperContent.Unsupported(msgType)
                        } else {
                            WhisperContent.Link(title, field("author"), field("thumb").toHttpsUrl(), url)
                        }
                    }
            }

            ARTICLE -> WhisperContent.Article(
                id = field("rid"),
                title = field("title"),
                summary = field("summary"),
                coverUrl = (obj["image_urls"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
                    .orEmpty()
                    .toHttpsUrl(),
            )

            PICTURE_CARD -> WhisperContent.Link(
                title = field("title"),
                subtitle = "",
                coverUrl = field("pic_url").toHttpsUrl(),
                url = field("jump_url"),
            )

            COMMON_SHARE -> WhisperContent.Link(
                title = field("title"),
                subtitle = field("author"),
                coverUrl = field("cover").toHttpsUrl(),
                url = field("url"),
            )

            // 按钮的去处有三处可放,官方前端按 config 里的平台地址、config 的 all_uri、根上的
            // jump_uri 依次取(文档镜像 private_msg_content.md,通知消息一节)。
            NOTICE -> WhisperContent.Notice(
                title = field("title"),
                text = field("text"),
                jumpText = nested("jump_uri_config", "text").ifEmpty { field("jump_text") },
                jumpUrl = nested("jump_uri_config", "web_uri")
                    .ifEmpty { nested("jump_uri_config", "all_uri") }
                    .ifEmpty { field("jump_uri") },
            )

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

    private fun videoOrNull(bvid: String, title: String, cover: String): WhisperContent.Video? =
        bvid.takeIf { it.isNotEmpty() }?.let { WhisperContent.Video(it, title, cover.toHttpsUrl()) }

    /**
     * 文字里的 `[doge]` 按同一次响应里的 `e_infos` 换成表情图。**只换 e_infos 里有的**:
     * 正文里本来就可能有一对方括号,认不出的原样留着。会话列表的摘要没有 e_infos,
     * 那里的表情就是方括号里的名字,与官方客户端的摘要一样。
     */
    fun textSpans(text: String, emotes: Map<String, EmoteInfoDto>): List<RichSpan> {
        if (emotes.isEmpty()) return listOf(RichSpan.Text(text))
        val spans = mutableListOf<RichSpan>()
        var last = 0
        EmotePattern.findAll(text).forEach { match ->
            val emote = emotes[match.value] ?: return@forEach
            if (match.range.first > last) spans += RichSpan.Text(text.substring(last, match.range.first))
            spans += RichSpan.Emoji(url = emote.uri.toHttpsUrl(), alt = emote.text, scale = emote.size.toFloat())
            last = match.range.last + 1
        }
        if (last < text.length) spans += RichSpan.Text(text.substring(last))
        return spans
    }

    private val EmotePattern = Regex("""\[[^\[\]]+]""")

    /** 投稿推送附言开头接口自带的标签,见 [WhisperContent.VideoPush] 的 note。 */
    private val NotePrefix = Regex("""^UP主赠言[：:]\s*""")
}

/**
 * 系统通知的正文。两件事要先处理:
 *
 * - 正文有时是一个 JSON 对象,要显示的在它的 `web` 字段里(PiliPlus `msg_sys/data.dart`
 *   同样处理);不拆的话屏幕上是一整坨 JSON。
 * - 正文里嵌着 `#{文字}{"链接"}` 这种标记,官方前端把它画成一段可点的文字。照原样显示的话,
 *   读者看到的是一串花括号和引号。裸露的网址也一并认成链接。
 */
private fun sysNoticeSpans(raw: String): List<RichSpan> {
    val text = runCatching {
        (Json.parseToJsonElement(raw) as? JsonObject)?.get("web")?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: raw
    val spans = mutableListOf<RichSpan>()
    var last = 0
    SysLinkPattern.findAll(text).forEach { match ->
        if (match.range.first > last) spans += RichSpan.Text(text.substring(last, match.range.first))
        val label = match.groups[1]?.value
        spans += if (label != null) {
            RichSpan.Link(text = label, url = match.groupValues[2].trim('"'))
        } else {
            RichSpan.Link(text = match.value, url = match.value, icon = RichLinkIcon.Web)
        }
        last = match.range.last + 1
    }
    if (last < text.length) spans += RichSpan.Text(text.substring(last))
    return spans
}

private val SysLinkPattern = Regex("""#\{([^}]*)\}\{([^}]*)\}|https?://[^\s，。）)]+""")

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
    val business: String = "",
    val image: String = "",
    /** 客户端 scheme,带着评论定位(`comment_root_id` 等),见 notes/private-message.md §8。 */
    @SerialName("native_uri") val nativeUri: String = "",
    /** 评论区所在内容的 oid 与评论区类型。`business_id` 就是评论区的 type。 */
    @SerialName("subject_id") val subjectId: Long = 0,
    @SerialName("business_id") val businessId: Int = 0,
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
    @SerialName("has_more") val hasMore: Int = 0,
)

@Serializable
private data class SessionDto(
    @SerialName("talker_id") val talkerId: Long = 0,
    @SerialName("session_ts") val sessionTs: Long = 0,
    @SerialName("last_msg") val lastMsg: SessionMessageDto? = null,
    /** 0 是普通会话,其余是各类系统会话(主播小助手、UP 主小助手、客服……)。 */
    @SerialName("system_msg_type") val systemMsgType: Int = 0,
    /** 只在系统会话里有:那个会话的名字和头像。 */
    @SerialName("account_info") val accountInfo: SessionAccountDto? = null,
)

@Serializable
private data class SessionAccountDto(
    val name: String = "",
    @SerialName("pic_url") val picUrl: String = "",
)

@Serializable
private data class SessionMessagesDto(
    val messages: List<SessionMessageDto> = emptyList(),
    @SerialName("has_more") val hasMore: Int = 0,
    @SerialName("e_infos") val emotes: List<EmoteInfoDto> = emptyList(),
)

/** 这一段消息里出现过的表情。[text] 带方括号,与正文里的写法逐字相同。 */
@Serializable
private data class EmoteInfoDto(
    val text: String = "",
    val uri: String = "",
    /** 1 是跟着文字走的小表情,2 是大表情。 */
    val size: Int = 1,
)

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
    @SerialName("msg_status") val msgStatus: Int = 0,
    val timestamp: Long = 0,
)

@Serializable
private data class UserCardDto(
    val mid: Long = 0,
    val name: String = "",
    val face: String = "",
)

private fun MsgFeedDto.toPage(map: (NoticeItemDto) -> Notice): MessagePage<Notice, NoticeCursor> = MessagePage(
    items = items.map(map),
    next = cursor?.takeIf { !it.isEnd && it.id != 0L }?.let { NoticeCursor(it.id, it.time) },
)

/**
 * 回复分两种,见 [NoticeKind]:带着被回复的那条评论原文的是回复,没有的是在用户的视频或动态
 * 底下直接发的评论,那时语境换成那个视频或动态的标题。
 */
private fun NoticeItemDto.toReplyNotice(): Notice {
    val target = item?.targetReplyContent?.takeIf { it.isNotBlank() }
    return Notice(
        id = id,
        actorMid = user?.mid ?: 0,
        avatarUrl = user?.avatar.orEmpty().toHttpsUrl(),
        name = user?.nickname.orEmpty(),
        actorCount = 1,
        kind = if (target != null) NoticeKind.Reply else NoticeKind.Comment,
        business = item?.business.orEmpty(),
        body = item?.sourceContent.orEmpty(),
        quoted = target ?: item?.title?.takeIf { it.isNotBlank() },
        imageUrl = item?.image.orEmpty().toHttpsUrl(),
        timeSeconds = replyTime,
        uri = item?.uri.orEmpty(),
        nativeUri = item?.nativeUri.orEmpty(),
        subjectId = item?.subjectId ?: 0,
        businessId = item?.businessId ?: 0,
    )
}

private fun NoticeItemDto.toMentionNotice(): Notice {
    val body = item?.sourceContent.orEmpty()
    return Notice(
        id = id,
        actorMid = user?.mid ?: 0,
        avatarUrl = user?.avatar.orEmpty().toHttpsUrl(),
        name = user?.nickname.orEmpty(),
        actorCount = 1,
        kind = NoticeKind.Mention,
        business = item?.business.orEmpty(),
        body = body,
        // 在动态正文里 @ 时,标题就是那段正文本身,再引一遍是同一句话出现两次。
        quoted = item?.title?.takeIf { it.isNotBlank() && it != body },
        imageUrl = item?.image.orEmpty().toHttpsUrl(),
        timeSeconds = atTime,
        uri = item?.uri.orEmpty(),
        nativeUri = item?.nativeUri.orEmpty(),
        subjectId = item?.subjectId ?: 0,
        businessId = item?.businessId ?: 0,
    )
}

/** 赞没有"正文",被赞的是用户自己的一样东西,放在引用的位置,和回复里被回复的那条评论同一档。 */
private fun NoticeItemDto.toLikeNotice() = Notice(
    id = id,
    actorMid = users.firstOrNull()?.mid ?: 0,
    avatarUrl = users.firstOrNull()?.avatar.orEmpty().toHttpsUrl(),
    name = users.firstOrNull()?.nickname.orEmpty(),
    actorCount = counts.coerceAtLeast(users.size),
    kind = NoticeKind.Like,
    business = item?.business.orEmpty(),
    body = "",
    quoted = item?.title?.takeIf { it.isNotBlank() },
    imageUrl = item?.image.orEmpty().toHttpsUrl(),
    timeSeconds = likeTime,
    uri = item?.uri.orEmpty(),
    nativeUri = item?.nativeUri.orEmpty(),
    subjectId = item?.subjectId ?: 0,
    businessId = item?.businessId ?: 0,
)
