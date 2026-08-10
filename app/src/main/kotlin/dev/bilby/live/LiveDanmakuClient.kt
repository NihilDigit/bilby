package dev.bilby.live

import dev.bilby.api.BiliResult
import dev.bilby.data.LiveRepository
import dev.bilby.BiliLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.wss
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.zip.Inflater

/** 从直播间信息流里解出来、这个 app 会用到的消息。其余 cmd(礼物、进场、人气)一概丢弃。 */
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

    /** 醒目留言。[endTimeSeconds] 到点就该从列表里撤下来,是服务端定的,不是本地计时。 */
    data class SuperChat(
        val id: Long,
        val message: String,
        val priceYuan: Int,
        val senderMid: Long,
        val senderName: String,
        val senderFace: String,
        val backgroundColor: String,
        val backgroundBottomColor: String,
        val startTimeSeconds: Long,
        val endTimeSeconds: Long,
    ) : LiveMessage
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
    }

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

    private suspend fun emitCommand(body: String, selfMid: Long, out: SendChannel<LiveMessage>) {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return
        // cmd 有 `DANMU_MSG:4:0:2:2:2:0` 这种带后缀的变体,按前缀判。
        val cmd = root["cmd"]?.jsonPrimitive?.contentOrNull ?: return
        val message = when {
            cmd.startsWith("DANMU_MSG") -> root.parseDanmaku(selfMid)
            cmd == "SUPER_CHAT_MESSAGE" -> root.parseSuperChat()
            cmd == "WATCHED_CHANGE" -> root.parseWatched()
            else -> null
        }
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
    val mid = user?.get("uid")?.jsonPrimitive?.longOrNull
        ?: (info.getOrNull(2) as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.longOrNull
        ?: 0L
    val name = (user?.get("base") as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
        ?: (info.getOrNull(2) as? JsonArray)?.getOrNull(1)?.jsonPrimitive?.contentOrNull
        ?: ""

    return LiveMessage.Danmaku(
        id = id,
        text = text,
        colorRgb = color,
        mode = mode,
        senderMid = mid,
        senderName = name,
        // `extra.send_from_me` 在实测里不可靠(PiliPlus 注释标了 invalid),按 mid 比。
        isSelf = selfMid != 0L && mid == selfMid,
    )
}

/** `WATCHED_CHANGE` 的 `data.text_large`,服务端已经格式化好(见 [LiveMessage.Watched])。 */
private fun JsonObject.parseWatched(): LiveMessage.Watched? =
    (this["data"] as? JsonObject)
        ?.get("text_large")
        ?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotEmpty() }
        ?.let { LiveMessage.Watched(it) }

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
        backgroundColor = data["background_color"]?.jsonPrimitive?.contentOrNull ?: "#EDF5FF",
        backgroundBottomColor = data["background_bottom_color"]?.jsonPrimitive?.contentOrNull ?: "#2A60B2",
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
