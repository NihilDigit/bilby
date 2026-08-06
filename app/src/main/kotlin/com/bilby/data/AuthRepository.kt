package com.bilby.data

import com.bilby.api.BiliClient
import com.bilby.api.BiliConstants
import com.bilby.api.BiliResponse
import com.bilby.api.BiliResult
import com.bilby.api.getData
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Cookie
import io.ktor.http.setCookie
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 只做扫码登录。密码登录走极验验证码,是 B 站风控最重、最易断的路径(DESIGN 2.6)。
 */
class AuthRepository(
    private val client: BiliClient,
    private val settings: SettingsStore,
) {

    val credentials: Flow<Credentials> = settings.credentials

    suspend fun generateQrCode(): BiliResult<QrCode> =
        client.getData<QrCodeData>("${BiliConstants.PASSPORT_HOST}$PATH_GENERATE")
            .let { result ->
                when (result) {
                    is BiliResult.Ok -> BiliResult.Ok(QrCode(result.value.url, result.value.qrcodeKey))
                    is BiliResult.ApiError -> result
                    is BiliResult.Failure -> result
                }
            }

    /**
     * 轮询。注意状态码在**内层** data.code 里,外层 code 恒为 0 —— 未扫码不是错误。
     * 登录成功时 Cookie 来自响应头 Set-Cookie,refresh_token 来自响应体,两处都要取。
     */
    suspend fun pollQrCode(qrcodeKey: String): BiliResult<QrPollStatus> = runCatching {
        val response: HttpResponse = client.rawGet(
            "${BiliConstants.PASSPORT_HOST}$PATH_POLL",
            mapOf("qrcode_key" to qrcodeKey),
        )
        val envelope = response.body<BiliResponse<QrPollData>>()
        val data = envelope.data
            ?: return@runCatching BiliResult.ApiError(envelope.code, envelope.message)

        when (data.code) {
            POLL_SUCCESS -> {
                saveLogin(response.setCookie(), data.refreshToken)
                BiliResult.Ok(QrPollStatus.Success)
            }

            POLL_SCANNED_UNCONFIRMED -> BiliResult.Ok(QrPollStatus.ScannedUnconfirmed)
            POLL_NOT_SCANNED -> BiliResult.Ok(QrPollStatus.NotScanned)
            POLL_EXPIRED -> BiliResult.Ok(QrPollStatus.Expired)
            else -> BiliResult.ApiError(data.code, data.message)
        }
    }.getOrElse { BiliResult.Failure(it) }

    suspend fun logout() = settings.clearCredentials()

    private suspend fun saveLogin(cookies: List<Cookie>, refreshToken: String) {
        val byName = cookies.associateBy { it.name }
        settings.saveCredentials(
            Credentials(
                sessdata = byName["SESSDATA"]?.value.orEmpty(),
                biliJct = byName["bili_jct"]?.value.orEmpty(),
                dedeUserId = byName["DedeUserID"]?.value.orEmpty(),
                dedeUserIdCkMd5 = byName["DedeUserID__ckMd5"]?.value.orEmpty(),
                refreshToken = refreshToken,
            )
        )
    }

    @Serializable
    private data class QrCodeData(
        val url: String = "",
        @SerialName("qrcode_key") val qrcodeKey: String = "",
    )

    @Serializable
    private data class QrPollData(
        val code: Int = -1,
        val message: String = "",
        @SerialName("refresh_token") val refreshToken: String = "",
    )

    companion object {
        private const val PATH_GENERATE = "/x/passport-login/web/qrcode/generate"
        private const val PATH_POLL = "/x/passport-login/web/qrcode/poll"

        private const val POLL_SUCCESS = 0
        private const val POLL_EXPIRED = 86038
        private const val POLL_SCANNED_UNCONFIRMED = 86090
        private const val POLL_NOT_SCANNED = 86101

        /** 二维码 180 秒失效,轮询 1 秒一次(与 PiliPlus 的间隔一致)。 */
        const val QR_TTL_MILLIS = 180_000L
        const val POLL_INTERVAL_MILLIS = 1_000L
    }
}

data class QrCode(val url: String, val key: String)

sealed interface QrPollStatus {
    data object NotScanned : QrPollStatus
    data object ScannedUnconfirmed : QrPollStatus
    data object Expired : QrPollStatus
    data object Success : QrPollStatus
}
