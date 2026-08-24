package dev.bilby.live

import dev.bilby.api.BiliResult
import dev.bilby.data.LiveRepository
import dev.bilby.BiliLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.wss
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.zip.Inflater

/**
 * 一枚粉丝勋章。牌名加等级,底色与字色都由服务端给。
 *
 * **取 `v2_` 那一组,不取 `color_start`/`color_end` 那一组。** 两组编码的是同一件事:前者是
 * `#RRGGBBAA` 字符串、按 APP 端深色底调过,后者是 10 进制整数、没有 alpha。混用会得到两种不同
 * 的底色,所以选一套用到底。
 *
 * [anchorName] 是这枚牌子所属主播的名字。**它不在勋章对象里**,只在 `info[3]` 那个扁平数组的
 * 第 2 位,所以两处都要解析才凑得齐一枚牌子。
 */
data class LiveFanMedal(
    val name: String,
    val level: Int,
    /** 牌主的大航海等级,1 总督 / 2 提督 / 3 舰长,0 表示没有。 */
    val guardLevel: Int,
    val anchorName: String,
    /** ARGB。服务端给的是 `#RRGGBBAA`,已经转成 Compose 要的排列。 */
    val backgroundArgb: Int,
    val textArgb: Int,
)

/**
 * 一张表情弹幕的图。
 *
 * [official] 决定尺寸怎么算:官方表情信服务端给的宽高,房间表情和充电表情的宽高不可靠,一律按
 * 固定边长画(见 [LiveEmoteFallbackPx])。这条判断照 PiliPlus 的 `chat_panel.dart:242-246`。
 */
data class LiveEmote(
    val url: String,
    val widthPx: Int,
    val heightPx: Int,
    val official: Boolean,
)

/** 房间表情与充电表情的固定边长,服务端给的宽高在这两类上对不上。 */
const val LiveEmoteFallbackPx = 162

/**
 * 从直播间信息流里解出来、这个 app 会用到的消息。
 *
 * **丢弃的那些是产品决定,不是解析没做**:礼物(`SEND_GIFT`)、进场(`INTERACT_WORD`)、进场特效
 * (`ENTRY_EFFECT`)、红包天选(`POPULARITY_RED_POCKET_*`)、全站广播(`NOTICE_MSG`)、点赞
 * (`LIKE_INFO_V3_*`)一概不产出,理由见 `docs/live-room-redesign.md` §4。礼物连解析都不加 ——
 * 高峰期它是消息量的大头,解析了再扔仍然白付一遍反序列化。
 */
sealed interface LiveMessage {

    /**
     * 一条普通弹幕。**没有播放时间**:那是渲染层的事,由消费方用播放器当刻的位置打戳,
     * 见 `LiveRoomViewModel`。
     */
    data class Danmaku(
        val id: String,
        val text: String,
        val colorRgb: Int,
        /** 服务端原始模式号,1/4/5 之外的这里不产出。 */
        val mode: Int,
        val senderMid: Long,
        val senderName: String,
        /**
         * 发送者头像。**弹幕消息自带这个字段**(`info[0][15].user.base.face`),所以聊天行画
         * 头像不需要另外拉一次用户信息。取不到时是空串,由渲染层退成占位图。
         */
        val senderFace: String,
        val medal: LiveFanMedal?,
        /** 整条弹幕就是一张图时的那张图(`info[0][13]`)。 */
        val emote: LiveEmote?,
        /** 正文里夹图的表情表(`extra.emots`),键是要在正文里被替换掉的那段文字。 */
        val inlineEmotes: Map<String, LiveEmote>,
        /** 回复某人的弹幕,这里是被回复者的名字。本项目不做跳转,只显示。 */
        val replyName: String?,
        val isSelf: Boolean,
    ) : LiveMessage

    /**
     * 人气值。**它在心跳回包(op=3)里,不是一条业务消息** —— 服务端每次心跳都回一个 4 字节
     * 大端整数,这是它唯一的来源:房间详情接口给的那个数只在进房那一刻准。
     */
    data class Popularity(val value: Long) : LiveMessage

    /**
     * 看过的人数。**和 [Popularity] 是两个数,不能互相顶替** —— 人气值是服务端按互动算出来的
     * 一个分数(送礼、弹幕都会推高它),看过人数才是真的有多少人来过。界面上写着"人"的那一处
     * 用这个。
     *
     * [text] 是服务端已经格式化好的整句(如"1.2万人看过")。不解析成数字再自己拼:那个阈值
     * (万/亿)和小数位是 B 站定的,自己拼一份出来只会和官方端对不上。
     */
    data class Watched(val text: String) : LiveMessage

    /**
     * 高能榜人数。**和 [Watched] 是两个数**:那个是"来过多少人",这个是此刻榜上有多少人
     * (送礼、弹幕会把人推上榜)。
     *
     * 这里是**数字**,不是拼好的句子 —— 服务端在这条上只给 `data.count`,所以量级折算和量词
     * 由本地来(见界面上取的那条 string)。
     */
    data class OnlineRankCount(val count: Int) : LiveMessage

    /**
     * 醒目留言。[endTimeSeconds] 到点就该从列表里撤下来,是服务端定的,不是本地计时。
     *
     * **服务端给的 `background_color` 与 `background_bottom_color` 这里不收。** 那两个值是照
     * 白底设计的,深色主题下直接糊,而它们编码的档位本地有一张自己的表(`ui/theme/Color.kt` 的
     * `superChatTier`)。字段本身仍然存在,记在 `notes/live.md` §8.1。
     */
    data class SuperChat(
        val id: Long,
        val message: String,
        val priceYuan: Int,
        val senderMid: Long,
        val senderName: String,
        val senderFace: String,
        val startTimeSeconds: Long,
        val endTimeSeconds: Long,
    ) : LiveMessage

    /**
     * 醒目留言被撤回。一条命令带的是**一组** id(`data.ids`),不是一条。
     *
     * 撤回之后流里那条留着并加删除线,汇总屏那份直接移除 —— 流是这个房间发生过什么的记录,
     * 抹掉一条会让上下文断开;汇总屏是"此刻还有效的留言",撤回的不该再占位置。
     */
    data class SuperChatRemoved(val ids: List<Long>) : LiveMessage

    /**
     * 上舰。
     *
     * **`guard_level` 越小等级越高**:1 总督、2 提督、3 舰长。这和直觉相反,而
     * `ui/live/LiveRoomScreen.kt` 的大航海名单用的是同一套取值,两处要保持一致。
     *
     * **没有头像字段。** `GUARD_BUY` 的 `data` 里不含 `face`,三个来源的字段表都不含,所以这一
     * 行只能用类别图标起头。
     *
     * [months] 取自 `USER_TOAST_MSG` 的 `num` 配 `unit`;只收到 `GUARD_BUY` 时它是 null ——
     * 那条消息里的 `num` 是礼物个数,不是月数,拿来当月数会写出一个错的数字。
     */
    data class GuardBuy(
        val senderMid: Long,
        val senderName: String,
        /** 1 总督 / 2 提督 / 3 舰长。 */
        val guardLevel: Int,
        val months: Int?,
        val startTimeSeconds: Long,
    ) : LiveMessage

    /** 主播中途改了标题。 */
    data class RoomTitleChanged(val title: String) : LiveMessage

    /**
     * 开播与下播。**这条命令改的是页面状态**,不只是流里多一行:下播之后画面停在最后一帧,
     * 而页面此前没有任何地方说明发生了什么。
     */
    data class LiveStateChanged(val live: Boolean) : LiveMessage

    /**
     * 超管警告与切断。两条命令的结构完全相同,只有 cmd 名不同,所以合成一个类型。
     *
     * **`msg` 和 `roomid` 在顶层,没有 `data` 这一层。** 这是直播协议里少数几条不走 `data` 的
     * 命令之一,照别处的写法去取会恒定拿到 null。
     */
    data class Warning(val message: String, val cutOff: Boolean) : LiveMessage

    /**
     * 有人被禁言。**只在被禁言的是自己时才该显示** —— 别人被禁言与这个用户无关,而自己被禁言
     * 之后发弹幕会一直失败,不说一声就只剩一个没有原因的错误。是不是自己由消费方判断。
     *
     * `uid` 在顶层是字符串、在 `data` 里是数字,同一条消息里两种类型并存。这里取 `data` 那份。
     */
    data class Blocked(val uid: Long, val name: String) : LiveMessage
}

/**
 * 直播间信息流。
 *
 * 协议照 PiliPlus `lib/tcp/live.dart` 实现:16 字节大端头 + JSON 体,认证 op=7、心跳 op=2、
 * 认证回包 op=8、心跳回包 op=3(人气值在它里面,见 [LiveMessage.Popularity])。业务包 op=5
 * 在压缩载荷里,而
 * **一个压缩载荷解开之后是若干个首尾相接的完整包**,每个各带自己的 16 字节头,必须循环拆,
 * 只读第一个会丢掉同一批里的其余弹幕。
 *
 * 认证体里请求 `protover = 2`(zlib)而不是 3(brotli):zlib 用 JDK 自带的 [Inflater] 就能解,
 * brotli 在 Android 上要额外引一个解码库,而这条链路上两者的压缩率差别对弹幕这种小文本可以
 * 忽略。
 */
class LiveDanmakuClient(
    private val http: HttpClient,
    private val repository: LiveRepository,
    private val json: Json,
) {

    /**
     * 连上房间并持续产出消息,直到协程被取消。断线会重连 —— 连接期间 token 可能过期,所以
     * 每次重连都重新取一遍,不复用上一次的。
     *
     * 整条上游走 [Dispatchers.Default]:zlib 解压和 JSON 解析都在 [dispatch] 里,而弹幕高峰
     * 期一个压缩包解开就是几十条。收集方在主线程,不加这一句这些活全落在主线程上。点播侧
     * 同样处理,见 `danmaku/DanmakuRepository`。
     */
    fun messages(roomId: Long, selfMid: Long): Flow<LiveMessage> = channelFlow {
        var backoffMillis = INITIAL_BACKOFF_MILLIS
        while (currentCoroutineContext().isActive) {
            val connected = runCatching { connectOnce(roomId, selfMid, channel) }
                .onFailure { BiliLog.w("直播弹幕连接中断 room=$roomId", it) }
                .getOrDefault(false)
            if (!currentCoroutineContext().isActive) return@channelFlow
            // 连上过就把退避清零:一次长连接正常跑了很久之后掉线,跟"根本连不上"是两回事。
            backoffMillis = if (connected) INITIAL_BACKOFF_MILLIS else (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
            delay(backoffMillis)
        }
    }.flowOn(Dispatchers.Default)

    /** @return 是否真的建立过连接(用于决定重连退避)。 */
    private suspend fun connectOnce(roomId: Long, selfMid: Long, out: SendChannel<LiveMessage>): Boolean {
        val info = when (val result = repository.loadDanmakuInfo(roomId)) {
            is BiliResult.Ok -> result.value
            else -> return false
        }
        val host = info.hostList.firstOrNull() ?: return false
        val url = "wss://${host.host}:${host.wssPort}/sub"

        var established = false
        http.wss(url) {
            established = true
            send(Frame.Binary(true, authPacket(roomId, selfMid, info.token)))

            val heartbeat = launch {
                // 认证回包之前就开始发也无妨,服务端不会因此断开;等 op=8 只会让第一个心跳
                // 晚一个来回,而漏发心跳是会被断开的。
                while (isActive) {
                    delay(HEARTBEAT_INTERVAL_MILLIS)
                    runCatching { send(Frame.Binary(true, packet(OP_HEARTBEAT, ByteArray(0)))) }
                        .onFailure { return@launch }
                }
            }

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Binary) continue
                    dispatch(frame.readBytes(), selfMid, out)
                }
            } finally {
                heartbeat.cancel()
            }
        }
        return established
    }

    private suspend fun dispatch(bytes: ByteArray, selfMid: Long, out: SendChannel<LiveMessage>) {
        if (bytes.size < HEADER_SIZE) return
        val totalSize = bytes.readInt(0)
        val headerSize = bytes.readShort(4)
        val protocolVer = bytes.readShort(6)
        val operation = bytes.readInt(8)

        when (operation) {
            OP_HEARTBEAT_REPLY -> {
                // 载荷是 4 字节大端人气值,没有 JSON。
                if (bytes.size >= headerSize + 4) {
                    val popularity = bytes.readInt(headerSize).toLong() and 0xFFFFFFFFL
                    out.send(LiveMessage.Popularity(popularity))
                }
                return
            }
            OP_AUTH_REPLY -> {
                // 认证结果是这条链路上唯一会"安静失败"的一步:被拒之后服务端直接关连接,
                // 表现成一个没有上下文的 EOF。把回包打出来,免得只能靠猜。
                val body = if (totalSize in (headerSize + 1)..bytes.size) {
                    bytes.decodeToString(headerSize, totalSize)
                } else {
                    "(空)"
                }
                BiliLog.d("直播信息流认证回包 $body")
                return
            }
        }

        when (protocolVer) {
            PROTO_PLAIN, PROTO_HEARTBEAT -> {
                if (totalSize in (headerSize + 1)..bytes.size) {
                    emitCommand(bytes.decodeToString(headerSize, totalSize), selfMid, out)
                }
            }
            PROTO_ZLIB -> {
                val inflated = inflate(bytes, HEADER_SIZE) ?: return
                // 解开之后是若干个完整包首尾相接,逐个走。
                var offset = 0
                while (offset + HEADER_SIZE <= inflated.size) {
                    val size = inflated.readInt(offset)
                    val innerHeader = inflated.readShort(offset + 4)
                    if (size <= innerHeader || offset + size > inflated.size) return
                    emitCommand(
                        inflated.decodeToString(offset + innerHeader, offset + size),
                        selfMid,
                        out,
                    )
                    offset += size
                }
            }
            else -> BiliLog.w("直播信息流:未知的 protover $protocolVer")
        }
    }

    /**
     * 一条业务命令。
     *
     * **每条各自 `runCatching`,一条解析失败不影响同一批里的其余消息。** 直播协议里字段的类型
     * 和层级会变(`PREPARING` 的 `roomid` 从数字改成过字符串,`info` 数组的长度不同版本不一样),
     * 而一个压缩包里是几十条首尾相接的消息 —— 让一条的异常冒出去会把整批一起丢掉。失败时把 cmd
     * 记下来,否则这类问题在日志里没有任何痕迹。
     */
    private suspend fun emitCommand(body: String, selfMid: Long, out: SendChannel<LiveMessage>) {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return
        // cmd 有 `DANMU_MSG:4:0:2:2:2:0` 这种带后缀的变体,按前缀判。
        val cmd = root["cmd"]?.jsonPrimitive?.contentOrNull ?: return
        val message = runCatching {
            when {
                cmd.startsWith("DANMU_MSG") -> root.parseDanmaku(selfMid)
                cmd == "SUPER_CHAT_MESSAGE" -> root.parseSuperChat()
                cmd == "SUPER_CHAT_MESSAGE_DELETE" -> root.parseSuperChatRemoved()
                cmd == "WATCHED_CHANGE" -> root.parseWatched()
                cmd == "ONLINE_RANK_COUNT" -> root.parseOnlineRank()
                cmd == "ROOM_CHANGE" -> root.parseRoomTitle()
                cmd == "GUARD_BUY" -> root.parseGuardBuy()
                cmd.startsWith("USER_TOAST_MSG") -> root.parseUserToast()
                cmd == "LIVE" -> LiveMessage.LiveStateChanged(live = true)
                cmd == "PREPARING" -> LiveMessage.LiveStateChanged(live = false)
                cmd == "WARNING" -> root.parseWarning(cutOff = false)
                cmd.startsWith("CUT_OFF") -> root.parseWarning(cutOff = true)
                cmd == "ROOM_BLOCK_MSG" -> root.parseBlocked()
                else -> null
            }
        }.onFailure { BiliLog.w("直播信息流解析失败 cmd=$cmd", it) }.getOrNull()
        if (message != null) out.send(message)
    }

    private companion object {
        const val HEADER_SIZE = 16

        const val OP_HEARTBEAT = 2
        const val OP_HEARTBEAT_REPLY = 3
        const val OP_AUTH = 7
        const val OP_AUTH_REPLY = 8

        const val PROTO_PLAIN = 0
        const val PROTO_HEARTBEAT = 1
        const val PROTO_ZLIB = 2

        const val HEARTBEAT_INTERVAL_MILLIS = 30_000L
        const val INITIAL_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
    }

    private fun authPacket(roomId: Long, selfMid: Long, token: String): ByteArray {
        val body = buildString {
            append("{\"roomid\":").append(roomId)
            append(",\"uid\":").append(selfMid)
            append(",\"protover\":").append(PROTO_ZLIB)
            append(",\"platform\":\"web\",\"type\":2,\"key\":\"").append(token).append("\"}")
        }
        return packet(OP_AUTH, body.encodeToByteArray())
    }

    private fun packet(operation: Int, body: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + body.size)
        out.writeInt(0, HEADER_SIZE + body.size)
        out.writeShort(4, HEADER_SIZE)
        out.writeShort(6, PROTO_HEARTBEAT)
        out.writeInt(8, operation)
        out.writeInt(12, 1)
        body.copyInto(out, HEADER_SIZE)
        return out
    }
}

private fun JsonObject.parseDanmaku(selfMid: Long): LiveMessage.Danmaku? {
    val info = this["info"] as? JsonArray ?: return null
    val text = info.getOrNull(1)?.jsonPrimitive?.contentOrNull ?: return null
    val head = info.getOrNull(0) as? JsonArray ?: return null

    // 新版把结构化字段塞在 info[0][15] 里,`extra` 还是一层 JSON 字符串。老的位置式字段
    // (info[0][1] 模式、info[0][3] 颜色)仍在,当 extra 解不出来时兜底用。
    val extension = head.getOrNull(15) as? JsonObject
    val extra = extension?.get("extra")?.jsonPrimitive?.contentOrNull
        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

    val id = extra?.get("id_str")?.jsonPrimitive?.contentOrNull
        ?: head.getOrNull(16)?.jsonPrimitive?.contentOrNull
        ?: return null
    val mode = extra?.get("mode")?.jsonPrimitive?.intOrNull
        ?: head.getOrNull(1)?.jsonPrimitive?.intOrNull
        ?: 1
    val color = extra?.get("color")?.jsonPrimitive?.intOrNull
        ?: head.getOrNull(3)?.jsonPrimitive?.intOrNull
        ?: 0xFFFFFF

    val user = extension?.get("user") as? JsonObject
    val base = user?.get("base") as? JsonObject
    val mid = user?.get("uid")?.jsonPrimitive?.longOrNull
        ?: (info.getOrNull(2) as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.longOrNull
        ?: 0L
    val name = base?.get("name")?.jsonPrimitive?.contentOrNull
        ?: (info.getOrNull(2) as? JsonArray)?.getOrNull(1)?.jsonPrimitive?.contentOrNull
        ?: ""

    // 回复某人的弹幕。两个字段要一起看:名字非空、且 mid 非零才算数。
    val replyMid = extra?.get("reply_mid")?.jsonPrimitive?.longOrNull ?: 0L
    val replyName = extra?.get("reply_uname")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotEmpty() && replyMid != 0L }

    return LiveMessage.Danmaku(
        id = id,
        text = text,
        colorRgb = color,
        mode = mode,
        senderMid = mid,
        senderName = name,
        senderFace = base?.get("face")?.jsonPrimitive?.contentOrNull.orEmpty(),
        medal = parseMedal(user?.get("medal") as? JsonObject, info.getOrNull(3) as? JsonArray),
        emote = (head.getOrNull(13) as? JsonObject)?.parseEmote(),
        inlineEmotes = (extra?.get("emots") as? JsonObject).parseEmoteMap(),
        replyName = replyName,
        // `extra.send_from_me` 在实测里不可靠(PiliPlus 注释标了 invalid),按 mid 比。
        isSelf = selfMid != 0L && mid == selfMid,
    )
}

/**
 * 一枚粉丝勋章要两个来源才凑得齐:[medal] 是结构化的那份,牌子所属主播的名字只在 [flat]
 * (`info[3]`)的第 2 位。**无勋章时两边的空表示不一样** —— 结构化那份是 null,扁平那份是空数组。
 */
private fun parseMedal(medal: JsonObject?, flat: JsonArray?): LiveFanMedal? {
    if (medal == null) return null
    val name = medal["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
    val background = medal["v2_medal_color_start"]?.jsonPrimitive?.contentOrNull?.let(::parseHexArgb)
    val text = medal["v2_medal_color_text"]?.jsonPrimitive?.contentOrNull?.let(::parseHexArgb)
    return LiveFanMedal(
        name = name,
        level = medal["level"]?.jsonPrimitive?.intOrNull ?: 0,
        guardLevel = medal["guard_level"]?.jsonPrimitive?.intOrNull ?: 0,
        anchorName = flat?.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty(),
        backgroundArgb = background ?: MedalFallbackBackground,
        textArgb = text ?: MedalFallbackText,
    )
}

/**
 * `#RRGGBBAA` 或 `#RRGGBB` 转 ARGB。**服务端给的是 alpha 在末尾**,Compose 要的是 alpha 在
 * 最前,所以八位那种要把末两位挪到最前面,直接当整数解会得到一个完全不同的颜色。
 */
private fun parseHexArgb(raw: String): Int? {
    val hex = raw.removePrefix("#")
    val value = hex.toLongOrNull(radix = 16) ?: return null
    return when (hex.length) {
        6 -> (0xFF000000L or value).toInt()
        8 -> ((value ushr 8) or ((value and 0xFF) shl 24)).toInt()
        else -> null
    }
}

/** 勋章色缺失时的兜底。取中性灰配白字,不去猜一个牌子色。 */
private val MedalFallbackBackground = 0xFF6D6D75.toInt()
private val MedalFallbackText = 0xFFFFFFFF.toInt()

private fun JsonObject.parseEmote(): LiveEmote? {
    val url = this["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
    val unique = this["emoticon_unique"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val width = this["width"]?.jsonPrimitive?.intOrNull ?: 0
    // 高度缺省时取宽度,表情基本是方的。
    val height = this["height"]?.jsonPrimitive?.intOrNull ?: width
    val official = unique.startsWith("official_")
    return LiveEmote(
        url = url,
        widthPx = if (official && width > 0) width else LiveEmoteFallbackPx,
        heightPx = if (official && height > 0) height else LiveEmoteFallbackPx,
        official = official,
    )
}

private fun JsonObject?.parseEmoteMap(): Map<String, LiveEmote> {
    if (this == null || isEmpty()) return emptyMap()
    return entries.mapNotNull { (key, value) ->
        (value as? JsonObject)?.parseEmote()?.let { key to it }
    }.toMap()
}

/** `data.ids` 是一组 id,一条命令可能撤回好几条。 */
private fun JsonObject.parseSuperChatRemoved(): LiveMessage.SuperChatRemoved? {
    val ids = ((this["data"] as? JsonObject)?.get("ids") as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
        .orEmpty()
    return if (ids.isEmpty()) null else LiveMessage.SuperChatRemoved(ids)
}

private fun JsonObject.parseRoomTitle(): LiveMessage.RoomTitleChanged? =
    (this["data"] as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotEmpty() }
        ?.let { LiveMessage.RoomTitleChanged(it) }

/**
 * 上舰。**`data.num` 是礼物个数,不是月数**,所以这里不填 [LiveMessage.GuardBuy.months] ——
 * 月数在 `USER_TOAST_MSG` 的 `num` 配 `unit` 那一对里,见 [parseUserToast]。
 */
private fun JsonObject.parseGuardBuy(): LiveMessage.GuardBuy? {
    val data = this["data"] as? JsonObject ?: return null
    val mid = data["uid"]?.jsonPrimitive?.longOrNull ?: return null
    return LiveMessage.GuardBuy(
        senderMid = mid,
        senderName = data["username"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        guardLevel = data["guard_level"]?.jsonPrimitive?.intOrNull ?: 0,
        months = null,
        startTimeSeconds = data["start_time"]?.jsonPrimitive?.longOrNull ?: 0L,
    )
}

/**
 * 上舰的另一条通知。**它和 `GUARD_BUY` 会为同一次上舰同时下发**,所以两条都产出成
 * [LiveMessage.GuardBuy],由消费方按 `senderMid` 加 `guardLevel` 去重(见 `LiveRoomViewModel`)。
 * 在这里选一条丢掉不行:哪一条先到没有保证,而带月数的是这一条。
 *
 * 现网同时存在 v1 与 v2 两个形态,字段路径完全不同,所以两条路都试。v2 的
 * `sender_uinfo.base` 与弹幕里的 `user.base` 同构。
 *
 * `toast_msg` 那句整话这里不用:它把用户名包在 `<%...%>` 里给客户端做高亮,直接渲染会露出这对
 * 符号,而剥掉之后剩下的信息本地已经能拼出来。
 */
private fun JsonObject.parseUserToast(): LiveMessage.GuardBuy? {
    val data = this["data"] as? JsonObject ?: return null
    val sender = data["sender_uinfo"] as? JsonObject
    val guardInfo = data["guard_info"] as? JsonObject
    val payInfo = data["pay_info"] as? JsonObject

    val mid = sender?.get("uid")?.jsonPrimitive?.longOrNull
        ?: data["uid"]?.jsonPrimitive?.longOrNull
        ?: return null
    val name = (sender?.get("base") as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        ?: data["username"]?.jsonPrimitive?.contentOrNull
        ?: ""
    val guardLevel = guardInfo?.get("guard_level")?.jsonPrimitive?.intOrNull
        ?: data["guard_level"]?.jsonPrimitive?.intOrNull
        ?: 0

    // 单位不是「月」时(有「\*3天」这类)就不给数字 —— 拿个数当月数会写出一个错的时长。
    val unit = payInfo?.get("unit")?.jsonPrimitive?.contentOrNull
        ?: data["unit"]?.jsonPrimitive?.contentOrNull
    val num = payInfo?.get("num")?.jsonPrimitive?.intOrNull
        ?: data["num"]?.jsonPrimitive?.intOrNull

    return LiveMessage.GuardBuy(
        senderMid = mid,
        senderName = name,
        guardLevel = guardLevel,
        months = num?.takeIf { unit == "月" && it > 0 },
        startTimeSeconds = guardInfo?.get("start_time")?.jsonPrimitive?.longOrNull
            ?: data["start_time"]?.jsonPrimitive?.longOrNull
            ?: 0L,
    )
}

/** `msg` 与 `roomid` 都在顶层,这两条命令没有 `data` 那一层。 */
private fun JsonObject.parseWarning(cutOff: Boolean): LiveMessage.Warning? =
    this["msg"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        ?.let { LiveMessage.Warning(message = it, cutOff = cutOff) }

/** 顶层 `uid` 是字符串、`data.uid` 是数字,取 `data` 那份。 */
private fun JsonObject.parseBlocked(): LiveMessage.Blocked? {
    val data = this["data"] as? JsonObject ?: return null
    val uid = data["uid"]?.jsonPrimitive?.longOrNull ?: return null
    return LiveMessage.Blocked(
        uid = uid,
        name = data["uname"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )
}

/** `WATCHED_CHANGE` 的 `data.text_large`,服务端已经格式化好(见 [LiveMessage.Watched])。 */
private fun JsonObject.parseWatched(): LiveMessage.Watched? =
    (this["data"] as? JsonObject)
        ?.get("text_large")
        ?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotEmpty() }
        ?.let { LiveMessage.Watched(it) }

/**
 * `ONLINE_RANK_COUNT` 的 `data.count`,高能榜人数(PiliPlus `controller.dart:623-625`,
 * 见 notes/live.md 第 6 节)。**服务端给的是数字,不是拼好的句子**,与 `WATCHED_CHANGE` 不同。
 */
private fun JsonObject.parseOnlineRank(): LiveMessage.OnlineRankCount? =
    (this["data"] as? JsonObject)
        ?.get("count")
        ?.jsonPrimitive?.intOrNull
        ?.takeIf { it >= 0 }
        ?.let { LiveMessage.OnlineRankCount(it) }

private fun JsonObject.parseSuperChat(): LiveMessage.SuperChat? {
    val data = this["data"] as? JsonObject ?: return null
    val user = data["user_info"] as? JsonObject
    return LiveMessage.SuperChat(
        id = data["id"]?.jsonPrimitive?.longOrNull ?: return null,
        message = data["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        priceYuan = data["price"]?.jsonPrimitive?.intOrNull ?: 0,
        senderMid = data["uid"]?.jsonPrimitive?.longOrNull ?: 0L,
        senderName = user?.get("uname")?.jsonPrimitive?.contentOrNull.orEmpty(),
        senderFace = user?.get("face")?.jsonPrimitive?.contentOrNull.orEmpty(),
        startTimeSeconds = data["start_time"]?.jsonPrimitive?.longOrNull ?: 0L,
        endTimeSeconds = data["end_time"]?.jsonPrimitive?.longOrNull ?: 0L,
    )
}

/** zlib 流(带头,不是 raw deflate),所以 [Inflater] 不传 `nowrap`。 */
private fun inflate(source: ByteArray, offset: Int): ByteArray? = runCatching {
    val inflater = Inflater()
    try {
        inflater.setInput(source, offset, source.size - offset)
        val out = java.io.ByteArrayOutputStream(source.size * 4)
        val buffer = ByteArray(8 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    } finally {
        inflater.end()
    }
}.onFailure { BiliLog.w("直播信息流 zlib 解压失败", it) }.getOrNull()

private fun ByteArray.readInt(offset: Int): Int =
    (this[offset].toInt() and 0xFF shl 24) or
        (this[offset + 1].toInt() and 0xFF shl 16) or
        (this[offset + 2].toInt() and 0xFF shl 8) or
        (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.readShort(offset: Int): Int =
    (this[offset].toInt() and 0xFF shl 8) or (this[offset + 1].toInt() and 0xFF)

private fun ByteArray.writeInt(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

private fun ByteArray.writeShort(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}
