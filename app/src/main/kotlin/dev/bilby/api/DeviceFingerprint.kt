package dev.bilby.api

import dev.bilby.BiliLog
import dev.bilby.data.FingerprintStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 设备指纹:buvid3/buvid4 + bili_ticket + ExClimbWuzhi 激活。
 *
 * 不依赖 BiliClient —— BiliClient 拼 Cookie 要用到这里的 cookieEntries(),
 * 反过来依赖会成环,所以直接拿 HttpClient 自己发请求(同 WbiSigner 断循环依赖的思路)。
 * 所有失败路径都吞掉、返回已有的(可能为空的)值:指纹缺失只应让写接口更容易被
 * 风控拦下,不该让读接口跟着一起崩。
 */
class DeviceFingerprint(
    private val store: FingerprintStore,
    private val httpClient: HttpClient,
    private val json: Json,
) {

    private val mutex = Mutex()

    /** 已解析好的 Cookie 键值对 + 它对应的 ticket 到期时刻。null 表示这个进程还没解析过。 */
    private var cached: Pair<Map<String, String>, Long>? = null

    /** 取 ticket 失败后的冷却截止时刻(秒)。 */
    private var ticketRetryAfter = 0L

    /**
     * 返回要拼进 Cookie 的键值对,内部负责获取/刷新/缓存,任何字段缺失就不放进 Map。
     *
     * **每一个 B 站请求都会调这里**,所以必须串行化。不加锁时冷启动的那批并发请求会各自
     * 看到"buvid3 为空",各自生成一个不同的 buvid3、各自发一次 spi 与 GenWebTicket,
     * 最后随机一个胜出落盘 —— 结果是同一次启动里发出去的请求带着好几个不同的设备号,
     * 恰恰是 FingerprintStore 注释里说"绝不能发生"的那件事(风控眼里等于凭空多出几台新设备)。
     */
    suspend fun cookieEntries(): Map<String, String> {
        val nowSeconds = System.currentTimeMillis() / 1000
        cached?.let { (entries, expiresAt) -> if (nowSeconds < expiresAt) return entries }

        return mutex.withLock {
            // 排队等锁期间前一个持有者可能已经解析好了,进锁后重新判一次。
            cached?.let { (entries, expiresAt) -> if (nowSeconds < expiresAt) return@withLock entries }
            resolve(nowSeconds)
        }
    }

    /**
     * 记下响应里的 Set-Cookie。真实浏览器收到 Set-Cookie 就会存下来并在后续请求里带回去;
     * 我们原先只单向拼 header、把服务端下发的 `b_nut`、`_uuid`、`sid` 之类全部丢弃,
     * 于是每个请求看起来都像一个从没收到过任何 Cookie 的全新浏览器——这本身就是个特征。
     *
     * 登录态那四个键不收:它们由登录流程写进 SettingsStore,是唯一权威来源。让某个接口的
     * 响应顺手改写 SESSDATA,出问题时会极难追。
     */
    suspend fun rememberCookies(cookies: List<Cookie>) {
        val harvested = cookies
            .filter { it.name.isNotEmpty() && it.value.isNotEmpty() && it.name !in CREDENTIAL_COOKIE_NAMES }
            .associate { it.name to it.value }
        if (harvested.isEmpty()) return

        val known = cached?.first
        // 值没变就不写盘,也不作废缓存 —— 否则每个响应都会触发一次 DataStore 写入。
        if (known != null && harvested.all { (k, v) -> known[k] == v }) return

        store.mergeServerCookies(harvested)
        mutex.withLock { cached = null }
    }

    private suspend fun resolve(nowSeconds: Long): Map<String, String> {
        val current = store.data.first()

        // 服务端下发过 buvid3 就以它为准:浏览器不会留着旧值不放,而且服务端认的本来就是
        // 它自己发的那个。这也让下面的"buvid3 为空就去取"不会重复触发。
        val serverBuvid3 = current.serverCookies["buvid3"].orEmpty()
        var buvid3 = serverBuvid3.ifEmpty { current.buvid3 }
        var buvid4 = current.serverCookies["buvid4"].orEmpty().ifEmpty { current.buvid4 }
        if (buvid3.isEmpty()) {
            val fetched = fetchBuvidFromSpi()
            if (fetched != null) {
                buvid3 = fetched.first
                buvid4 = fetched.second
            } else {
                // buvid4 没有已知的本地生成算法(PiliPlus 也未实现,笔记里标了 UNSURE),
                // 接口拿不到就只补 buvid3,buvid4 留空。
                buvid3 = generateBuvid3Locally()
            }
            store.saveBuvid(buvid3, buvid4)
        }

        var ticket = current.biliTicket
        var expiresAt = current.ticketExpiresAt
        val stale = ticket.isEmpty() || nowSeconds >= expiresAt - TICKET_REFRESH_BUFFER_SECONDS
        if (stale && nowSeconds >= ticketRetryAfter) {
            val fresh = fetchBiliTicket()
            if (fresh != null) {
                ticket = fresh.first
                expiresAt = fresh.second
                store.saveTicket(ticket, expiresAt)
            } else {
                // 不冷却的话,接口只要在挂,之后每一个 B 站请求都会先赔上一次失败的 ticket 请求。
                ticketRetryAfter = nowSeconds + TICKET_RETRY_COOLDOWN_SECONDS
            }
        }

        val entries = buildMap {
            // 先铺服务端下发的那些(b_nut、_uuid、sid……),再让我们自己维护的三个覆盖上去:
            // buvid3/buvid4 上面已经把服务端值并进来了,bili_ticket 则是我们自己算 hexsign、
            // 自己管 TTL 的,以本地这份为准。
            putAll(current.serverCookies)
            if (buvid3.isNotEmpty()) put("buvid3", buvid3)
            if (buvid4.isNotEmpty()) put("buvid4", buvid4)
            if (ticket.isNotEmpty()) put("bili_ticket", ticket)
        }
        // ticket 拿到了就缓存到该刷新的时刻;没拿到(或还在冷却里用着一张过期的)就按冷却
        // 时长缓存。后一种情况不能拿 expiresAt 当缓存期限——那是个过去的时间,缓存立刻失效,
        // 每个请求都会重新抢锁读一遍 DataStore。
        val ticketUsable = ticket.isNotEmpty() && nowSeconds < expiresAt - TICKET_REFRESH_BUFFER_SECONDS
        val validUntil = if (ticketUsable) {
            expiresAt - TICKET_REFRESH_BUFFER_SECONDS
        } else {
            maxOf(ticketRetryAfter, nowSeconds + TICKET_RETRY_COOLDOWN_SECONDS)
        }
        cached = entries to validUntil
        return entries
    }

    /** buvid 激活,只需成功一次;失败吞掉,不重试、不影响主流程(PiliPlus 同样 catch 后忽略)。 */
    suspend fun activateIfNeeded() {
        if (store.data.first().buvidActivated) return
        runCatching { sendExClimbWuzhi() }
            .onSuccess { store.markBuvidActivated() }
            .onFailure { BiliLog.w("buvid 激活异常", it) }
    }

    private suspend fun fetchBuvidFromSpi(): Pair<String, String>? = runCatching {
        val resp: HttpResponse = httpClient.get("${BiliConstants.WEB_HOST}/x/frontend/finger/spi") {
            header(HttpHeaders.UserAgent, BiliConstants.USER_AGENT)
            header(HttpHeaders.Referrer, BiliConstants.REFERER)
        }
        val data = resp.body<BiliResponse<SpiData>>().data ?: return@runCatching null
        data.b3 to data.b4
    }.onFailure { BiliLog.w("buvid 获取异常", it) }.getOrNull()?.takeIf { it.first.isNotEmpty() }

    /** PiliPlus 的本地生成算法:UUIDv4 转大写 + [0,100000) 随机数(补零到 5 位) + "infoc"。 */
    private fun generateBuvid3Locally(): String {
        val random5 = Random.nextInt(100000).toString().padStart(5, '0')
        return "${UUID.randomUUID().toString().uppercase()}${random5}infoc"
    }

    /**
     * bili_ticket:HMAC-SHA256(key="XgwSnGZ1p", msg="ts"+timestamp) 取 hex 作为 hexsign。
     * 有效期文档内前后矛盾(简述段 259260 秒 vs 接口字段表 259200 秒),按字段表取更可能
     * 准确的 259200 秒;created_at 缺失时退化用请求发出时的时间戳兜底。
     */
    private suspend fun fetchBiliTicket(): Pair<String, Long>? = runCatching {
        val timestamp = System.currentTimeMillis() / 1000
        val hexsign = hmacSha256Hex(TICKET_HMAC_KEY, "ts$timestamp")
        // 参数必须放 query:放进 form body 时服务端报 -400 empty `ts` field。
        val resp = httpClient.post("${BiliConstants.WEB_HOST}/bapis/bilibili.api.ticket.v1.Ticket/GenWebTicket") {
            url {
                parameters.append("key_id", "ec02")
                parameters.append("hexsign", hexsign)
                parameters.append("context[ts]", timestamp.toString())
            }
            header(HttpHeaders.UserAgent, BiliConstants.USER_AGENT)
            header(HttpHeaders.Referrer, BiliConstants.REFERER)
        }
        val envelope = resp.body<BiliResponse<TicketData>>()
        val data = envelope.data
        if (data == null) {
            BiliLog.w("bili_ticket 获取失败 status=${resp.status.value} code=${envelope.code}: ${envelope.message}")
            return@runCatching null
        }
        val createdAt = data.createdAt.takeIf { it > 0 } ?: timestamp
        data.ticket to (createdAt + TICKET_TTL_SECONDS)
    }.onFailure { BiliLog.w("bili_ticket 请求异常", it) }.getOrNull()

    private fun hmacSha256Hex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /**
     * ExClimbWuzhi:笔记里完全没有说明这个 payload 的字段含义(3064/39c8/3c43/adca/bfe9
     * 都是混淆过的键名),这里只原样照抄结构,不臆测语义。伪造一段"PNG 尾部"随机字节,
     * 取其 base64 的最后 50 个字符作为 bfe9 字段。
     */
    private suspend fun sendExClimbWuzhi() {
        val randomBytes = ByteArray(32) { Random.nextInt(256).toByte() } +
            byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68) + // "IEND" chunk 标记
            ByteArray(4) { Random.nextInt(256).toByte() }
        val randPngEnd = java.util.Base64.getEncoder().encodeToString(randomBytes)
        val last50 = randPngEnd.takeLast(50)

        val payload = json.encodeToString(
            ExClimbWuzhiPayload(inner = ExClimbWuzhiInner(bfe9 = last50)),
        )

        val resp = httpClient.post("${BiliConstants.WEB_HOST}/x/internal/gaia-gateway/ExClimbWuzhi") {
            header(HttpHeaders.UserAgent, BiliConstants.USER_AGENT)
            header(HttpHeaders.Referrer, BiliConstants.REFERER)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ExClimbWuzhiRequest(payload)))
        }
        check(resp.status.isSuccess()) { "ExClimbWuzhi ${resp.status.value}" }
    }

    @Serializable
    private data class SpiData(
        @SerialName("b_3") val b3: String = "",
        @SerialName("b_4") val b4: String = "",
    )

    @Serializable
    private data class TicketData(
        val ticket: String = "",
        @SerialName("created_at") val createdAt: Long = 0L,
    )

    @Serializable
    private data class ExClimbWuzhiInner(
        val adca: String = "Linux",
        val bfe9: String,
    )

    @Serializable
    private data class ExClimbWuzhiPayload(
        @SerialName("3064") val f3064: Int = 1,
        @SerialName("39c8") val f39c8: String = "333.1387.fp.risk",
        @SerialName("3c43") val inner: ExClimbWuzhiInner,
    )

    @Serializable
    private data class ExClimbWuzhiRequest(val payload: String)

    private companion object {
        const val TICKET_HMAC_KEY = "XgwSnGZ1p"
        const val TICKET_TTL_SECONDS = 259_200L
        const val TICKET_REFRESH_BUFFER_SECONDS = 60L
        const val TICKET_RETRY_COOLDOWN_SECONDS = 60L

        /** 登录态的四个键只认登录流程写进 SettingsStore 的那份,不从响应里回收。 */
        val CREDENTIAL_COOKIE_NAMES =
            setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")
    }
}
