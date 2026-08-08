package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.AppSign
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.DeviceFingerprint
import dev.bilby.api.BiliResponse
import dev.bilby.api.BiliResult
import io.ktor.client.call.body
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * TV 扫码登录,现在是**唯一**登录方式。网页扫码那条路(连同它带来的 cookie 刷新流程)
 * 已于 2026-08-07 整体删除,理由见 notes/auth-model.md §7 ——
 * **凭据过期就重新扫码,不要再实现 cookie 刷新。**
 *
 * 改用它的原因(notes/auth-model.md §2.5):PiliPlus 的"扫码"登录 tab 本来就是走
 * TV/HD 端接口,不是网页端——一次扫码同时拿到 cookie 和 access_key,不需要"先网页扫码
 * 登录、再额外补一次 TV 授权"这种两步流程。之前认为 TV 扫码只是"补充授权"、不能覆盖
 * 现有 cookie 的判断已经作废:登录成功时 cookie 和 access_key 都要落盘,因为现在没有
 * 别的 cookie 来源了。
 *
 * 参数为什么走 query 而不是表单:passport 下的这批接口(TV 二维码、ticket)只认
 * query 形式,body 表单会得到文不对题的错误(见 [BiliClient.appPostQuery] 的注释),
 * 所以这里全程用 `appPostQuery` —— 它同时保证用 app UA 发,不然 B 站会把这次登录
 * 记成"Chrome 浏览器登录",参数(TV appkey)与 UA(浏览器)对不上也更招风控。也正因为是 passport 接口而非网页登录态接口,
 * 鉴权靠 appkey 签名而不是 cookie,所以这条路径根本不带 cookie 与 csrf。
 *
 * app 端 token 刷新:**PiliPlus 未实现**。全仓库 grep `oauth2/refresh_token`、
 * `refreshToken`、`oauth2` 只命中两个接口——`oauth2/login`(密码登录)和
 * `oauth2/access_token`(风控验证手机号后用 code 换 token,`lib/http/login.dart:452-479`),
 * 都不是"用旧 token 换新 token"的刷新语义。`token_info.refresh_token` 只在登录成功时
 * 存进 `LoginAccount` 构造参数(`lib/pages/login/controller.dart:622-626`),此后全仓库没有
 * 任何读取点。因此这里不提供 `refreshToken()`,access_key 过期(`expires_in` 约 180 天)后
 * 只能重新扫码,不要凭空发明刷新接口。
 */
class TvLoginRepository(
    private val client: BiliClient,
    private val settings: SettingsStore,
    private val fingerprint: DeviceFingerprint,
) {

    val credentials: Flow<Credentials> = settings.credentials

    suspend fun requestQrCode(): BiliResult<TvQrCode> = runCatching {
        val params = AppSign.sign(AUTH_CODE_PARAMS)
        val envelope = client.appPostQuery(
            "${BiliConstants.PASSPORT_HOST}$PATH_AUTH_CODE",
            params,
        ).body<BiliResponse<AuthCodeData>>()

        val data = envelope.data
        if (envelope.code == 0 && data != null) {
            BiliResult.Ok(TvQrCode(url = data.url, authCode = data.authCode))
        } else {
            BiliLog.w("TV 二维码申请失败(${envelope.code}): ${envelope.message}")
            BiliResult.ApiError(envelope.code, envelope.message)
        }
    }.onFailure { BiliLog.w("TV 二维码申请异常", it) }.getOrElse { BiliResult.Failure(it) }

    /**
     * 轮询。与网页扫码不同,TV 端的状态码在**外层** `code`(网页端是内层 `data.code`),
     * 见 notes/auth-from-docs.md §3.2。
     *
     * 成功时**同时**落两样凭据:`cookie_info.cookies` 里的 SESSDATA/bili_jct/DedeUserID/
     * DedeUserID__ckMd5 存进 `Credentials`,`token_info.access_token` 存 access_key、
     * `token_info.refresh_token` 存进 `Credentials.refreshToken`。这是现在唯一的登录入口,
     * 不存 cookie 就没有登录态。
     */
    suspend fun poll(authCode: String): BiliResult<TvPollStatus> = runCatching {
        val params = AppSign.sign(POLL_PARAMS + ("auth_code" to authCode))
        val envelope = client.appPostQuery(
            "${BiliConstants.PASSPORT_HOST}$PATH_POLL",
            params,
        ).body<BiliResponse<PollData>>()

        when (envelope.code) {
            POLL_SUCCESS -> {
                val data = envelope.data
                if (data == null) {
                    BiliLog.w("TV 登录轮询成功但缺 data(${envelope.code}): ${envelope.message}")
                    BiliResult.ApiError(envelope.code, envelope.message)
                } else {
                    saveLogin(data)
                    BiliResult.Ok(TvPollStatus.Success)
                }
            }

            POLL_EXPIRED -> BiliResult.Ok(TvPollStatus.Expired)
            // UNSURE: 笔记明确记录的只有 0(成功)和 86038(过期)。86090/86039 笔记里分别标
            // "已扫码未确认"/"尚未确认",按字面语义映射到 ScannedUnconfirmed/NotScanned,
            // 但笔记没有给出这两个码的官方定义来源,未实测验证过。
            POLL_SCANNED_UNCONFIRMED -> BiliResult.Ok(TvPollStatus.ScannedUnconfirmed)
            POLL_NOT_SCANNED -> BiliResult.Ok(TvPollStatus.NotScanned)
            else -> {
                BiliLog.w("TV 登录轮询失败(${envelope.code}): ${envelope.message}")
                BiliResult.ApiError(envelope.code, envelope.message)
            }
        }
    }.onFailure { BiliLog.w("TV 登录轮询异常", it) }.getOrElse { BiliResult.Failure(it) }

    /**
     * 登出要连会话指纹一起清:`sid` 属于刚刚结束的那次登录,留到下次登录就成了拼接出来的
     * 会话身份。PiliPlus 的 `delete()` 同样是清空整个 cookie jar 再重新生成 buvid3。
     */
    suspend fun logout() {
        settings.clearCredentials()
        fingerprint.clearSession()
    }

    /**
     * cookie 与 access_key 一次写完,不分两笔:分开写会让 credentials 流先发出一个
     * "已登录但 access_key 还是空"的中间态,那一瞬间点赞/投币会走没有 access_key 的
     * app 请求,失败得毫无道理。
     *
     * `token_info.refresh_token` 落的是 [Credentials.appRefreshToken] 而不是
     * webRefreshToken:它是 app 端 OAuth 的刷新口令,不是网页 cookie 刷新要的
     * ac_time_value(notes/auth-from-docs.md §2.5),混着用会让 cookie/refresh 回 86095。
     */
    /**
     * **`cookie_info.cookies` 里的每一个都要留下。** 下面按名字取的四个是有专门用途的
     * (SESSDATA 是登录态、bili_jct 当 csrf、DedeUserID 还要拼 `x-bili-mid`),其余的
     * ——目前是 `sid`——交给 [DeviceFingerprint] 原样带回。
     *
     * 这里曾经只取那四个,剩下的连同 `sid` 一起丢掉。于是之后每个请求交回去的 cookie 组合,
     * 服务端从来没有这样签发过:少了它自己给的 sid。PiliPlus 不挑字段,整份存进 jar
     * (`account.dart:217-226`)。
     */
    private suspend fun saveLogin(data: PollData) {
        val cookiesByName = data.cookieInfo.cookies.associateBy { it.name }
        fingerprint.rememberCookies(data.cookieInfo.cookies.associate { it.name to it.value })
        settings.saveLogin(
            Credentials(
                sessdata = cookiesByName["SESSDATA"]?.value.orEmpty(),
                biliJct = cookiesByName["bili_jct"]?.value.orEmpty(),
                dedeUserId = cookiesByName["DedeUserID"]?.value.orEmpty(),
                dedeUserIdCkMd5 = cookiesByName["DedeUserID__ckMd5"]?.value.orEmpty(),
                appRefreshToken = data.tokenInfo.refreshToken,
                accessKey = data.tokenInfo.accessToken,
            )
        )
    }

    @Serializable
    private data class AuthCodeData(
        val url: String = "",
        @SerialName("auth_code") val authCode: String = "",
    )

    @Serializable
    private data class PollData(
        @SerialName("token_info") val tokenInfo: TokenInfo = TokenInfo(),
        @SerialName("cookie_info") val cookieInfo: CookieInfo = CookieInfo(),
    )

    @Serializable
    private data class TokenInfo(
        @SerialName("access_token") val accessToken: String = "",
        @SerialName("refresh_token") val refreshToken: String = "",
    )

    @Serializable
    private data class CookieInfo(val cookies: List<TvCookie> = emptyList())

    @Serializable
    private data class TvCookie(val name: String = "", val value: String = "")

    companion object {
        private const val PATH_AUTH_CODE = "/x/passport-tv-login/qrcode/auth_code"
        private const val PATH_POLL = "/x/passport-tv-login/qrcode/poll"

        // 抄自 notes/auth-model.md §2.3(历史实现)与 §6 方案 C,HD 端固定值。
        private val AUTH_CODE_PARAMS = mapOf(
            "local_id" to "0",
            "platform" to "android",
            "mobi_app" to "android_hd",
        )
        private val POLL_PARAMS = mapOf("local_id" to "0")

        private const val POLL_SUCCESS = 0
        private const val POLL_EXPIRED = 86038
        private const val POLL_SCANNED_UNCONFIRMED = 86090
        private const val POLL_NOT_SCANNED = 86039

        /** 二维码 180 秒失效(与网页扫码一致),轮询间隔同样是 1 秒。 */
        const val QR_TTL_MILLIS = 180_000L
        const val POLL_INTERVAL_MILLIS = 1_000L
    }
}

data class TvQrCode(val url: String, val authCode: String)

sealed interface TvPollStatus {
    data object NotScanned : TvPollStatus
    data object ScannedUnconfirmed : TvPollStatus
    data object Expired : TvPollStatus
    data object Success : TvPollStatus
}
