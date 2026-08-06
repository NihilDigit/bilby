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
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
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

    suspend fun rawGet(
        url: String,
        params: Map<String, String> = emptyMap(),
        signed: Boolean = false,
    ): HttpResponse {
        val finalParams = if (signed) wbiSigner.sign(params) else params
        val cookie = cookieHeader()
        return http.get(url) {
            applyCommonHeaders(cookie)
            finalParams.forEach { (k, v) -> parameter(k, v) }
        }
    }

    /**
     * POST 但参数走 query。passport 下的几个接口(TV 二维码、ticket)只认这种形式:
     * 参数放进 form body 会得到 `-400 empty ts field` 或 `-101 账号未登录`,
     * 错误信息完全指不到真因。
     */
    suspend fun rawPostQuery(
        url: String,
        params: Map<String, String> = emptyMap(),
        withCsrf: Boolean = true,
    ): HttpResponse {
        val credentials = settings.credentials.first()
        val finalParams = if (withCsrf) params + ("csrf" to credentials.biliJct) else params
        val cookie = cookieHeader()
        return http.post(url) {
            applyCommonHeaders(cookie)
            finalParams.forEach { (k, v) -> parameter(k, v) }
        }
    }

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
        val accessKey = settings.credentials.first().accessKey
        val signed = AppSign.sign(if (accessKey.isEmpty()) form else form + ("access_key" to accessKey))
        return http.submitForm(
            url = url,
            formParameters = Parameters.build { signed.forEach { (k, v) -> append(k, v) } },
        ) {
            header(HttpHeaders.UserAgent, BiliConstants.APP_USER_AGENT)
        }
    }

    /** 写操作接口一律要 csrf(bili_jct),且是 body 字段不是 header。 */
    suspend fun rawPostForm(
        url: String,
        form: Map<String, String> = emptyMap(),
        withCsrf: Boolean = true,
    ): HttpResponse {
        val credentials = settings.credentials.first()
        val fields = if (withCsrf) form + ("csrf" to credentials.biliJct) else form
        val cookie = cookieHeader()
        return http.submitForm(
            url = url,
            formParameters = Parameters.build { fields.forEach { (k, v) -> append(k, v) } },
        ) {
            applyCommonHeaders(cookie)
        }
    }

    suspend fun fetchWbiKeys(): WbiKeys {
        val nav = rawGet("${BiliConstants.WEB_HOST}/x/web-interface/nav")
            .body<BiliResponse<NavData>>()
        // nav 在未登录时返回 code=-101,但 wbi_img 照样给,所以这里不看 code。
        val img = nav.data?.wbiImg ?: error("nav 未返回 wbi_img")
        return WbiKeys(imgKey = img.imgUrl.keyFromUrl(), subKey = img.subUrl.keyFromUrl())
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyCommonHeaders(cookie: String) {
        header(HttpHeaders.UserAgent, BiliConstants.USER_AGENT)
        header(HttpHeaders.Referrer, BiliConstants.REFERER)
        header(HttpHeaders.Origin, BiliConstants.ORIGIN)
        if (cookie.isNotEmpty()) header(HttpHeaders.Cookie, cookie)
    }

    /**
     * 凭据 + 设备指纹。指纹缺失时读接口照常工作,写接口会被风控判成"账号异常"(-403),
     * 所以这里即使拿不到指纹也不能阻断请求。
     */
    private suspend fun cookieHeader(): String {
        val credentials = settings.credentials.first().toCookieHeader()
        val device = fingerprint.cookieEntries().map { (k, v) -> "$k=$v" }
        return (listOf(credentials).filter { it.isNotEmpty() } + device).joinToString("; ")
    }

    @Serializable
    private data class NavData(@SerialName("wbi_img") val wbiImg: WbiImg? = null)

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

/** 把信封拆开:传输失败、业务失败、成功三分。 */
suspend inline fun <reified T> BiliClient.getData(
    url: String,
    params: Map<String, String> = emptyMap(),
    signed: Boolean = false,
): BiliResult<T> = runCatching { rawGet(url, params, signed).body<BiliResponse<T>>() }
    .fold(
        onSuccess = { envelope ->
            val data = envelope.data
            if (envelope.code == 0 && data != null) {
                BiliResult.Ok(data)
            } else {
                BiliLog.w("GET ${url.pathOnly()} 失败(${envelope.code}): ${envelope.message}")
                BiliResult.ApiError(envelope.code, envelope.message)
            }
        },
        onFailure = {
            BiliLog.w("GET ${url.pathOnly()} 异常", it)
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
                BiliLog.w("POST ${url.pathOnly()} 失败(${envelope.code}): ${envelope.message}")
                BiliResult.ApiError(envelope.code, envelope.message)
            }
        },
        onFailure = {
            BiliLog.w("POST ${url.pathOnly()} 异常", it)
            BiliResult.Failure(it)
        },
    )
