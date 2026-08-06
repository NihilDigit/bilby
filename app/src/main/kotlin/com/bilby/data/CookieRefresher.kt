package com.bilby.data

import com.bilby.api.BiliClient
import com.bilby.api.BiliConstants
import com.bilby.api.BiliResponse
import com.bilby.api.BiliResult
import com.bilby.api.CorrespondPath
import com.bilby.api.getData
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Cookie
import io.ktor.http.setCookie
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cookie 刷新。五步一步都不能省,顺序也不能换:
 *
 *   cookie/info(要不要刷) → correspondPath(RSA) → 抓 HTML 取 refresh_csrf
 *   → cookie/refresh(旧 refresh_token + refresh_csrf,拿到新 cookie 与新 refresh_token)
 *   → confirm/refresh(**新 cookie 的 csrf** + **旧 refresh_token**)
 *
 * 最后一步的参数组合最反直觉:用新的 csrf 去作废旧的 token。写反了服务端回 86095 或 -111,
 * 而且此时新 cookie 已经生效,不会有任何本地征兆。
 *
 * PiliPlus 没有实现这套流程(全仓库零命中),所以这里没有参考实现可对照,只有文档。
 */
class CookieRefresher(
    private val client: BiliClient,
    private val settings: SettingsStore,
) {

    private val mutex = Mutex()

    /** 返回是否真的刷新过。并发调用会被串行化,后来者看到的是已刷新后的状态。 */
    suspend fun refreshIfNeeded(): BiliResult<Boolean> = mutex.withLock {
        val credentials = settings.credentials.first()
        if (!credentials.isLoggedIn || credentials.refreshToken.isEmpty()) {
            return BiliResult.Ok(false)
        }

        when (val info = client.getData<CookieInfo>("${BiliConstants.PASSPORT_HOST}$PATH_INFO")) {
            is BiliResult.ApiError -> return info
            is BiliResult.Failure -> return info
            is BiliResult.Ok -> if (!info.value.refresh) return BiliResult.Ok(false)
        }

        return runCatching { doRefresh(credentials) }.getOrElse { BiliResult.Failure(it) }
    }

    private suspend fun doRefresh(old: Credentials): BiliResult<Boolean> {
        // 用服务端返回的 timestamp 还是本地时间都行,文档两者都允许;取本地时间少一次依赖。
        val path = CorrespondPath.of(System.currentTimeMillis())
        val html = client.rawGet("${BiliConstants.MAIN_HOST}/correspond/1/$path").bodyAsText()
        val refreshCsrf = REFRESH_CSRF_REGEX.find(html)?.groupValues?.get(1)
            ?: return BiliResult.ApiError(ERR_NO_REFRESH_CSRF, "correspond 页面没有 refresh_csrf(路径过期或未登录)")

        val refreshResponse = client.rawPostForm(
            url = "${BiliConstants.PASSPORT_HOST}$PATH_REFRESH",
            form = mapOf(
                "csrf" to old.biliJct,
                "refresh_csrf" to refreshCsrf,
                "source" to "main_web",
                "refresh_token" to old.refreshToken,
            ),
            withCsrf = false, // csrf 在上面显式给了,不要让客户端再塞一次
        )
        val refreshEnvelope = refreshResponse.body<BiliResponse<RefreshData>>()
        val newToken = refreshEnvelope.data?.refreshToken
        if (refreshEnvelope.code != 0 || newToken.isNullOrEmpty()) {
            return BiliResult.ApiError(refreshEnvelope.code, refreshEnvelope.message)
        }

        val fresh = merge(old, refreshResponse.setCookie(), newToken)
        settings.saveCredentials(fresh)

        // 走到这里新 cookie 已经生效;confirm 失败只是旧 token 没被作废,不该把用户踢下线。
        val confirm = client.rawPostForm(
            url = "${BiliConstants.PASSPORT_HOST}$PATH_CONFIRM",
            form = mapOf(
                "csrf" to fresh.biliJct,
                "refresh_token" to old.refreshToken,
            ),
            withCsrf = false,
        ).body<BiliResponse<Unit>>()

        return if (confirm.code == 0) BiliResult.Ok(true)
        else BiliResult.ApiError(confirm.code, "cookie 已刷新但旧 token 未作废: ${confirm.message}")
    }

    private fun merge(old: Credentials, cookies: List<Cookie>, newToken: String): Credentials {
        val byName = cookies.associateBy { it.name }
        return Credentials(
            sessdata = byName["SESSDATA"]?.value ?: old.sessdata,
            biliJct = byName["bili_jct"]?.value ?: old.biliJct,
            dedeUserId = byName["DedeUserID"]?.value ?: old.dedeUserId,
            dedeUserIdCkMd5 = byName["DedeUserID__ckMd5"]?.value ?: old.dedeUserIdCkMd5,
            refreshToken = newToken,
        )
    }

    @Serializable
    private data class CookieInfo(val refresh: Boolean = false, val timestamp: Long = 0)

    @Serializable
    private data class RefreshData(@SerialName("refresh_token") val refreshToken: String = "")

    private companion object {
        const val PATH_INFO = "/x/passport-login/web/cookie/info"
        const val PATH_REFRESH = "/x/passport-login/web/cookie/refresh"
        const val PATH_CONFIRM = "/x/passport-login/web/confirm/refresh"

        /** 文档给的 xpath 是 //div[id='1-name']/text();页面极简,正则足够,不值得引 HTML 解析器。 */
        val REFRESH_CSRF_REGEX = Regex("""<div id="1-name">([^<]+)</div>""")

        const val ERR_NO_REFRESH_CSRF = -1001
    }
}
