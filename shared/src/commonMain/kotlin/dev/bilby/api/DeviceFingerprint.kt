package dev.bilby.api

import dev.bilby.BiliLog
import dev.bilby.data.FingerprintStore
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 设备指纹:buvid3 + 服务端下发过的 cookie + ExClimbWuzhi 激活。
 *
 * **这里只放服务端认得的东西。** 曾经还自己造过 `buvid4` 和 `bili_ticket`:两者 PiliPlus
 * 都没有(`notes/auth-model.md §5.1` 逐个 grep 确认零命中),而它们是**以 cookie 的形式交回
 * 服务端的** —— 等于声称"你给过我这两个值"。加上登录时被丢掉的 `sid`,我们交回去的是一套
 * 服务端从未这样签发过的组合。
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

    /** 已解析好的 Cookie 键值对 + 缓存有效到的时刻。null 表示这个进程还没解析过。 */
    private var cached: Pair<Map<String, String>, Long>? = null

    /**
     * 返回要拼进 Cookie 的键值对,内部负责获取/刷新/缓存,任何字段缺失就不放进 Map。
     *
     * **每一个 B 站请求都会调这里**,所以必须串行化。不加锁时冷启动的那批并发请求会各自
     * 看到"buvid3 为空"、各自生成一个不同的 buvid3,最后随机一个胜出落盘 ——
     * 结果是同一次启动里发出去的请求带着好几个不同的设备号,
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
    suspend fun rememberCookies(cookies: List<Cookie>) =
        rememberCookies(cookies.associate { it.name to it.value })

    /**
     * 同上,但接的是已经拆好的键值对。**TV 扫码登录要用这个**:那批 cookie 来自响应体的
     * `cookie_info.cookies`,不是响应头,`setCookie()` 收不到。
     *
     * 登录返回的 `sid` 就是这么丢掉的 —— 落盘时只按名字挑走了 SESSDATA/bili_jct/DedeUserID/
     * DedeUserID__ckMd5 四个,剩下的没有任何人接。PiliPlus 把整个 list 原样存进 cookie jar
     * (`account.dart:217-226`,`for (final i in cookies)`,不挑字段),之后全量带回。
     */
    suspend fun rememberCookies(cookies: Map<String, String>) {
        val harvested = cookies
            .filterKeys { it.isNotEmpty() && it !in CREDENTIAL_COOKIE_NAMES }
            .filterValues { it.isNotEmpty() }
        if (harvested.isEmpty()) return

        val known = cached?.first
        // 值没变就不写盘,也不作废缓存 —— 否则每个响应都会触发一次 DataStore 写入。
        if (known != null && harvested.all { (k, v) -> known[k] == v }) return

        store.mergeServerCookies(harvested)
        mutex.withLock { cached = null }
    }

    /** 登出:清掉这次会话的 cookie 与 buvid3,见 [FingerprintStore.clearSession]。 */
    suspend fun clearSession() {
        store.clearSession()
        mutex.withLock { cached = null }
    }

    private suspend fun resolve(nowSeconds: Long): Map<String, String> {
        val current = store.data.first()

        // 服务端下发过 buvid3 就以它为准:服务端认的本来就是它自己发的那个。
        val serverBuvid3 = current.serverCookies["buvid3"].orEmpty()
        var buvid3 = serverBuvid3.ifEmpty { current.buvid3 }
        if (buvid3.isEmpty()) {
            buvid3 = generateBuvid3Locally()
            store.saveBuvid(buvid3)
        }

        val entries = buildMap {
            // 服务端下发过的那些(b_nut、_uuid、sid……)原样带回,再让 buvid3 覆盖上去。
            putAll(current.serverCookies)
            if (buvid3.isNotEmpty()) put("buvid3", buvid3)
        }
        // 没有任何本地维护的到期时间了,缓存一直有效,由 [rememberCookies] 在服务端下发新值时
        // 作废。这里曾按 bili_ticket 的 TTL 算缓存期限,ticket 去掉之后那套算术没有对象了。
        cached = entries to Long.MAX_VALUE
        return entries
    }

    /** buvid 激活,只需成功一次;失败吞掉,不重试、不影响主流程(PiliPlus 同样 catch 后忽略)。 */
    suspend fun activateIfNeeded() {
        if (store.data.first().buvidActivated) return
        runCatching { sendExClimbWuzhi() }
            .onSuccess { store.markBuvidActivated() }
            .onFailure { BiliLog.w("buvid 激活异常", it) }
    }

    /**
     * PiliPlus 的本地生成算法:UUIDv4 转大写 + [0,100000) 随机数(补零到 5 位) + "infoc"
     * (`lib/utils/id_utils.dart:72-74`)。
     *
     * **不去 `x/frontend/finger/spi` 要。** 那条路曾经是首选、本地生成只当兜底,理由是
     * "服务端认它自己发的那个"。但 PiliPlus 从不请求它,登录态和匿名态都是本地生成
     * (`account.dart:115/137`),多发一次请求换不到任何已知好处。
     */
    private fun generateBuvid3Locally(): String {
        val random5 = Random.nextInt(100000).toString().padStart(5, '0')
        return "${UUID.randomUUID().toString().uppercase()}${random5}infoc"
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
        /** 登录态的四个键只认登录流程写进 SettingsStore 的那份,不从响应里回收。 */
        val CREDENTIAL_COOKIE_NAMES =
            setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5")
    }
}
