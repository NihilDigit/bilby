package dev.bilby.api

import dev.bilby.BiliLog
import dev.bilby.data.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.setCookie
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 所有 B 站接口的出口。只做四件事:带上请求约定的 header、带上 Cookie、按需 WBI 签名、
 * 按需附 csrf。业务语义一概不在这里。
 *
 * Cookie 走手工拼 header 而不是 Ktor 的 cookies 插件:凭据本来就由 SettingsStore 持有,
 * 刷新流程也要能整体替换,交给插件反而多一份状态。
 */
class BiliClient(
    val http: HttpClient,
    private val settings: SettingsStore,
    private val wbiSigner: WbiSigner,
    private val fingerprint: DeviceFingerprint,
) {

    /**
     * @param referer 覆盖站内 Referer/Origin。空间那几个接口要指到 `space.bilibili.com/<mid>`
     *   而不是站点首页(PiliPlus member.dart:305-310/380-385 逐个接口都手写了这一组),
     *   真实浏览器发这些请求时人就在空间页上,Referer 对不上是最好认的破绽之一。
     */
    suspend fun rawGet(
        url: String,
        params: Map<String, String> = emptyMap(),
        signed: Boolean = false,
        referer: String? = null,
        userAgent: String? = null,
    ): HttpResponse {
        val finalParams = if (signed) wbiSigner.sign(params) else params
        val credentials = settings.credentials.first()
        val cookie = cookieHeader(credentials)
        return http.get(url) {
            applyCommonHeaders(credentials, cookie, referer, userAgent)
            finalParams.forEach { (k, v) -> parameter(k, v) }
        }.also { fingerprint.rememberCookies(it.setCookie()) }
    }

    /**
     * 短链展开。**单独一条路线,因为它必须不带 Cookie**:b23.tv 是个跳转器,把登录凭据发给
     * 它既没有必要,也多一处泄漏面。同理不签名、不带站内 Referer。
     *
     * 只要最终落地的地址,不要正文 —— `prepareGet` 拿到响应头就退出,几百 KB 的页面不会读
     * 进内存。跟跳转由 HttpClient 自己完成,所以读的是 `call.request.url` 而不是 Location:
     * 短链可能连跳两次(b23.tv → m.bilibili.com → www.bilibili.com)。
     */
    suspend fun resolveRedirect(url: String): String =
        http.prepareGet(url) {
            header(HttpHeaders.UserAgent, BiliConstants.USER_AGENT)
        }.execute { response -> response.call.request.url.toString() }

    /**
     * CDN 直链取字节。**离线缓存下载视频/音频流走这一条**,不是普通的接口调用:
     *
     * - **不带 Cookie、不带账号 header、不签名。** 目标是 `*.bilivideo.com` 这类 CDN,
     *   不是 api.bilibili.com;把登录凭据发过去既没有必要,也多一处泄漏面。防盗链只认
     *   Referer 和 UA(见 [dev.bilby.player.PlayerFactory],播放器那侧发的是同一组)。
     * - **响应体是流,不是 JSON。** 几百 MB 的视频不能整个读进内存,所以用 `prepareGet` +
     *   `execute`,由调用方在 [block] 里边收边写盘。响应体只在 [block] 里有效,别把
     *   `HttpResponse` 带出去。
     * - **[rangeStart] 大于 0 时发 Range,用于断点续传。** 服务端接受时回 206,不接受时
     *   回 200 并从头给 —— 调用方要按状态码决定是追加还是重写,不能默认它一定支持。
     *
     * 放在这里而不是在下载器里直接 `httpClient.get`:CLAUDE.md 的约定是所有出网请求的
     * 形状都在这个文件里列全,而这一条的凭据、header 与流式响应体都和上面几条不同。
     */
    suspend fun <T> streamGet(
        url: String,
        rangeStart: Long = 0,
        block: suspend (HttpResponse) -> T,
    ): T = http.prepareGet(url) {
        header(HttpHeaders.UserAgent, BiliConstants.USER_AGENT)
        header(HttpHeaders.Referrer, BiliConstants.REFERER)
        if (rangeStart > 0) header(HttpHeaders.Range, "bytes=$rangeStart-")
    }.execute { block(it) }

    /**
     * app 端路线 + 参数走 query。TV 扫码登录的三个接口用它。
     *
     * 关键是**不能用网页端 header**:参数里带的是 TV/HD 的 appkey,UA 却是桌面 Chrome 的话,
     * B 站会把这次登录记成"Chrome 浏览器登录",而且这种参数与 UA 对不上的组合正是风控
     * 最容易盯上的特征。这里统一用 app UA,也不带 Cookie 和站内 Referer。
     */
    suspend fun appPostQuery(url: String, params: Map<String, String>): HttpResponse =
        http.post(url) {
            header(HttpHeaders.UserAgent, BiliConstants.APP_USER_AGENT)
            params.forEach { (k, v) -> parameter(k, v) }
        }

    /**
     * app 端路线(app.bilibili.com):参数里带 access_key 并做 appkey 签名,**不带 Cookie**。
     * 网页端与 app 端是两套授权,混着发只会两边都不认。
     */
    suspend fun appPostForm(url: String, form: Map<String, String>): HttpResponse {
        val credentials = settings.credentials.first()
        val accessKey = credentials.accessKey
        val signed = AppSign.sign(if (accessKey.isEmpty()) form else form + ("access_key" to accessKey))
        return http.submitForm(
            url = url,
            formParameters = Parameters.build { signed.forEach { (k, v) -> append(k, v) } },
        ) {
            header(HttpHeaders.UserAgent, BiliConstants.APP_USER_AGENT)
            // app 路线不带 Cookie,但账号 header 与 baseHeaders 照加:PiliPlus 的拦截器
            // 在分出 app/web 两条路之前就无条件 addAll 了 account.headers 和 referer
            // (account_mgr.dart:66-68),app 分支只是在这之后跳过 cookie。
            applyIdentityHeaders(credentials)
            header(HttpHeaders.Referrer, BiliConstants.REFERER)
        }
    }

    /**
     * 写操作接口一律要 csrf(bili_jct),且是 body 字段不是 header。
     *
     * @param params 少数接口的参数是**分开放**的:一部分在 query,一部分在 body。
     *   `x/relation/modify` 就是这样(statistics 与 x-bili-device-req-json 在 query,
     *   业务字段在 body),照 PiliPlus 的实际做法,放错位置会被拒。
     * @param referer 覆盖站内 Referer/Origin,同 [rawGet]。关注要指到 `space.bilibili.com/<mid>`。
     * @param csrfInQuery csrf 改放 query。**动态预约 `x/dynamic/feed/reserve/click` 要这样**
     *   (PiliPlus `http/dynamics.dart:531-535` 把 csrf 放 queryParameters、业务字段放 body)。
     *   风控是按动作算的,别的写接口把 csrf 放 body 能过,不代表这个也能 —— 同一类踩过一次的
     *   还有 `bili_ticket` 的参数必须放 query。
     */
    suspend fun rawPostForm(
        url: String,
        form: Map<String, String> = emptyMap(),
        withCsrf: Boolean = true,
        params: Map<String, String> = emptyMap(),
        referer: String? = null,
        userAgent: String? = null,
        csrfInQuery: Boolean = false,
    ): HttpResponse {
        val credentials = settings.credentials.first()
        val csrf = if (withCsrf) mapOf("csrf" to credentials.biliJct) else emptyMap()
        val fields = if (csrfInQuery) form else form + csrf
        val query = if (csrfInQuery) params + csrf else params
        val cookie = cookieHeader(credentials)
        return http.submitForm(
            url = url,
            formParameters = Parameters.build { fields.forEach { (k, v) -> append(k, v) } },
        ) {
            query.forEach { (k, v) -> parameter(k, v) }
            applyCommonHeaders(credentials, cookie, referer, userAgent)
        }.also { fingerprint.rememberCookies(it.setCookie()) }
    }

    /**
     * 收到 -101 时判断凭据是不是真的失效了,失效就清掉登录态 —— `MainActivity` 看的是
     * `credentials.isLoggedIn`,清掉之后扫码页会自己顶上来。
     *
     * **不直接凭一个 -101 就登出**:先拿 `nav` 复核一次。-101 的字面意思是"未登录",
     * 而个别接口在登录态正常时也会给它(未登录版评论列表就是特意不带 Cookie 调的),
     * 单凭一次响应就清凭据,代价是让用户莫名其妙地被踢去重新扫码。`nav` 的 `isLogin`
     * 是这件事的权威答案。
     *
     * 复核本身失败(断网之类)时什么都不做:那说明我们现在无法判断,而"无法判断"不该
     * 被当成"已失效"。
     */
    suspend fun invalidateIfLoggedOut() {
        if (!settings.credentials.first().isLoggedIn) return
        val stillValid = runCatching {
            rawGet("${BiliConstants.WEB_HOST}/x/web-interface/nav")
                .body<BiliResponse<NavData>>()
                .data
                ?.isLogin
        }.onFailure { BiliLog.w("复核登录态失败,保持现状", it) }.getOrNull()

        if (stillValid == false) {
            BiliLog.w("凭据已失效,清除登录态,需要重新扫码")
            settings.clearCredentials()
        }
    }

    suspend fun fetchWbiKeys(): WbiKeys {
        val nav = rawGet("${BiliConstants.WEB_HOST}/x/web-interface/nav")
            .body<BiliResponse<NavData>>()
        // nav 在未登录时返回 code=-101,但 wbi_img 照样给,所以这里不看 code。
        val img = nav.data?.wbiImg ?: error("nav 未返回 wbi_img")
        return WbiKeys(imgKey = img.imgUrl.keyFromUrl(), subKey = img.subUrl.keyFromUrl())
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyCommonHeaders(
        credentials: dev.bilby.data.Credentials,
        cookie: String,
        referer: String? = null,
        userAgent: String? = null,
    ) {
        header(HttpHeaders.UserAgent, userAgent ?: BiliConstants.USER_AGENT)
        header(HttpHeaders.Referrer, referer ?: BiliConstants.REFERER)
        // **只在指定了 referer 时才发 Origin**,默认不发。浏览器对同源的 GET 根本不带
        // Origin,无条件发反而是个破绽;PiliPlus 也没有全局 Origin,只在空间那几个接口
        // 和一键三连上手写(account_mgr.dart 全局只补 referer,member.dart:305-310 才有
        // origin)。发的时候值跟着 referer 走同一站点,不会出现 Referer 在空间页而
        // Origin 还指着首页这种自相矛盾的组合。
        referer?.let { header(HttpHeaders.Origin, it.originOf()) }
        applyIdentityHeaders(credentials)
        if (cookie.isNotEmpty()) header(HttpHeaders.Cookie, cookie)
    }

    /** 从 `https://space.bilibili.com/123/dynamic` 取出 `https://space.bilibili.com`。 */
    private fun String.originOf(): String {
        val schemeEnd = indexOf("//").takeIf { it >= 0 }?.plus(2) ?: return this
        val hostEnd = indexOf('/', schemeEnd).takeIf { it >= 0 } ?: length
        return substring(0, hostEnd)
    }

    /** baseHeaders + 账号标识。未登录时不写 mid/eid 两条(PiliPlus 的 NoAccount 同样不写)。 */
    private fun io.ktor.client.request.HttpRequestBuilder.applyIdentityHeaders(
        credentials: dev.bilby.data.Credentials,
    ) {
        BiliConstants.BASE_HEADERS.forEach { (k, v) -> header(k, v) }
        val mid = credentials.dedeUserId
        if (mid.isNotEmpty()) {
            header("x-bili-mid", mid)
            header("x-bili-aurora-eid", AuroraEid.of(mid))
        }
    }

    /**
     * 凭据 + 设备指纹。指纹缺失时读接口照常工作,写接口会被风控判成"账号异常"(-403),
     * 所以这里即使拿不到指纹也不能阻断请求。
     */
    private suspend fun cookieHeader(credentials: dev.bilby.data.Credentials): String {
        val device = fingerprint.cookieEntries().map { (k, v) -> "$k=$v" }
        return (listOf(credentials.toCookieHeader()).filter { it.isNotEmpty() } + device)
            .joinToString("; ")
    }

    @Serializable
    private data class NavData(
        @SerialName("wbi_img") val wbiImg: WbiImg? = null,
        @SerialName("isLogin") val isLogin: Boolean = false,
    )

    @Serializable
    private data class WbiImg(
        @SerialName("img_url") val imgUrl: String = "",
        @SerialName("sub_url") val subUrl: String = "",
    )
}

/** img_url 形如 .../wbi/<key>.png,key 就是去掉路径与扩展名的文件名。 */
private fun String.keyFromUrl(): String = substringAfterLast('/').substringBefore('.')

/**
 * 日志里只留路径,query 可能带签名(w_rid)或 mid 之类的账号相关信息。
 * 公开是因为上面那两个 inline 扩展函数要用它 —— inline 函数体会被搬到调用方,
 * 引用 private 成员编不过。
 */
fun String.pathOnly(): String = substringBefore('?')

fun dev.bilby.data.Credentials.toCookieHeader(): String = buildList {
    if (sessdata.isNotEmpty()) add("SESSDATA=$sessdata")
    if (biliJct.isNotEmpty()) add("bili_jct=$biliJct")
    if (dedeUserId.isNotEmpty()) add("DedeUserID=$dedeUserId")
    if (dedeUserIdCkMd5.isNotEmpty()) add("DedeUserID__ckMd5=$dedeUserIdCkMd5")
}.joinToString("; ")

/**
 * `code == 0` 但 `data` 为空是另一回事:接口没报错,只是这个响应形状与我们声明的 T 对不上
 * (常见于服务端把 data 省成 null,或 DTO 的必填字段写错导致反序列化出 null)。
 * 照原样打成"失败(0): "会得到一行没有错误码也没有 message 的日志,等于没打。
 */
fun BiliResponse<*>.describeFailure(): String =
    if (code == 0) "(code=0 但 data 为空,可能是响应结构与 DTO 不符)"
    else "(${code}): $message"

/** 把信封拆开:传输失败、业务失败、成功三分。 */
suspend inline fun <reified T> BiliClient.getData(
    url: String,
    params: Map<String, String> = emptyMap(),
    signed: Boolean = false,
    referer: String? = null,
    userAgent: String? = null,
): BiliResult<T> = runCatching { rawGet(url, params, signed, referer, userAgent).body<BiliResponse<T>>() }
    .fold(
        onSuccess = { envelope ->
            val data = envelope.data
            if (envelope.code == 0 && data != null) {
                BiliResult.Ok(data)
            } else {
                BiliLog.w("GET ${url.pathOnly()} 失败${envelope.describeFailure()}")
                if (envelope.code == CODE_NOT_LOGGED_IN) invalidateIfLoggedOut()
                BiliResult.ApiError(envelope.code, envelope.message)
            }
        },
        onFailure = {
            BiliLog.w("GET ${url.pathOnly()} 异常", it)
            BiliResult.Failure(it)
        },
    )

/**
 * 写接口的统一出口。与 [postForm] 的差别只有一条:**不要求 data 非空**。
 *
 * 这条差别此前让四个仓库(VideoAction 两份、ToView、Comment)各抄了一份手写的信封解析,
 * 连同各自的 `pathOnly` 与日志格式——那正是 DESIGN 8 节要求"一处打点覆盖所有接口"想避免的
 * 局面:漏抄一处日志,失败就变回静默。
 */
suspend fun BiliClient.postAction(
    url: String,
    form: Map<String, String> = emptyMap(),
    withCsrf: Boolean = true,
    params: Map<String, String> = emptyMap(),
    referer: String? = null,
    userAgent: String? = null,
    csrfInQuery: Boolean = false,
): BiliResult<Unit> = envelopeResult(url) {
    rawPostForm(url, form, withCsrf, params, referer, userAgent, csrfInQuery).body<BiliEnvelope>()
}

/** app 路线的写接口(点赞/投币):access_key + appkey 签名,不带 Cookie。 */
suspend fun BiliClient.appPostAction(
    url: String,
    form: Map<String, String> = emptyMap(),
): BiliResult<Unit> = envelopeResult(url) { appPostForm(url, form).body<BiliEnvelope>() }

private suspend inline fun BiliClient.envelopeResult(
    url: String,
    request: () -> BiliEnvelope,
): BiliResult<Unit> = runCatching(request).fold(
    onSuccess = { envelope ->
        if (envelope.code == 0) {
            BiliResult.Ok(Unit)
        } else {
            BiliLog.w("POST ${url.pathOnly()} 失败(${envelope.code}): ${envelope.message}")
            if (envelope.code == CODE_NOT_LOGGED_IN) invalidateIfLoggedOut()
            BiliResult.ApiError(envelope.code, envelope.message)
        }
    },
    onFailure = {
        BiliLog.w("POST ${url.pathOnly()} 异常", it)
        BiliResult.Failure(it)
    },
)

suspend inline fun <reified T> BiliClient.postForm(
    url: String,
    form: Map<String, String> = emptyMap(),
    withCsrf: Boolean = true,
): BiliResult<T> = runCatching { rawPostForm(url, form, withCsrf).body<BiliResponse<T>>() }
    .fold(
        onSuccess = { envelope ->
            val data = envelope.data
            if (envelope.code == 0 && data != null) {
                BiliResult.Ok(data)
            } else {
                BiliLog.w("POST ${url.pathOnly()} 失败${envelope.describeFailure()}")
                // 和 [envelopeResult] 一样复核登录态。少这一句的话,凭据过期后走这条路的写操作
                // (发评论)只会一条条报错,登录状态却还挂在界面上。
                if (envelope.code == CODE_NOT_LOGGED_IN) invalidateIfLoggedOut()
                BiliResult.ApiError(envelope.code, envelope.message)
            }
        },
        onFailure = {
            BiliLog.w("POST ${url.pathOnly()} 异常", it)
            BiliResult.Failure(it)
        },
    )
