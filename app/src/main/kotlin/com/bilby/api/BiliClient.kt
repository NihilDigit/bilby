package com.bilby.api

import com.bilby.data.SettingsStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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

    /** 写操作接口一律要 csrf(bili_jct),且是 body 字段不是 header。 */
    suspend fun rawPostForm(
        url: String,
        form: Map<String, String> = emptyMap(),
        withCsrf: Boolean = true,
    ): HttpResponse {
        val credentials = settings.credentials.first()
        val fields = if (withCsrf) form + ("csrf" to credentials.biliJct) else form
        val cookie = credentials.toCookieHeader()
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

    private suspend fun cookieHeader(): String = settings.credentials.first().toCookieHeader()

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

fun com.bilby.data.Credentials.toCookieHeader(): String = buildList {
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
            if (envelope.code == 0 && data != null) BiliResult.Ok(data)
            else BiliResult.ApiError(envelope.code, envelope.message)
        },
        onFailure = { BiliResult.Failure(it) },
    )

suspend inline fun <reified T> BiliClient.postForm(
    url: String,
    form: Map<String, String> = emptyMap(),
    withCsrf: Boolean = true,
): BiliResult<T> = runCatching { rawPostForm(url, form, withCsrf).body<BiliResponse<T>>() }
    .fold(
        onSuccess = { envelope ->
            val data = envelope.data
            if (envelope.code == 0 && data != null) BiliResult.Ok(data)
            else BiliResult.ApiError(envelope.code, envelope.message)
        },
        onFailure = { BiliResult.Failure(it) },
    )
