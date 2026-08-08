package dev.bilby.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.fingerprintDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "bilby_fingerprint")

/**
 * 设备指纹的持久化。单独开一个 DataStore 文件而不是塞进 SettingsStore,是因为两类数据的
 * 生命周期不同:登录凭据随账号切换/登出而变,设备指纹是"这台设备"的身份。
 *
 * [FingerprintData.buvid3] 必须固定下来:它是风控识别设备的依据,每次启动重新生成等于每次
 * 都换一台新设备,新设备在风控眼里天然可疑。
 *
 * [FingerprintData.serverCookies] 相反,它属于**这一次登录**(`sid` 之类),登出时必须清掉,
 * 见 [clearSession] —— PiliPlus 登出是 `cookieJar.deleteAll()` 之后重新生成 buvid3
 * (`account.dart:157-160`),两者的存续期在那边也是分开的。
 */
class FingerprintStore(context: Context) {

    private val store = context.fingerprintDataStore

    val data: Flow<FingerprintData> = store.data.map { p ->
        FingerprintData(
            buvid3 = p[KEY_BUVID3].orEmpty(),
            buvidActivated = p[KEY_BUVID_ACTIVATED] ?: false,
            serverCookies = p[KEY_SERVER_COOKIES].orEmpty().decodeCookies(),
        )
    }

    /**
     * 合并服务端下发的 Cookie。整表编码成一个字符串存,不为每个键开一个 Preferences key ——
     * B 站会下发哪些键不由我们决定(`b_nut`、`_uuid`、`sid`、`b_lsid`……),给未知键预留
     * 固定字段是不可能的。
     */
    suspend fun mergeServerCookies(incoming: Map<String, String>) {
        if (incoming.isEmpty()) return
        store.edit { p ->
            val merged = p[KEY_SERVER_COOKIES].orEmpty().decodeCookies() + incoming
            p[KEY_SERVER_COOKIES] = merged.encodeCookies()
        }
    }

    suspend fun saveBuvid(buvid3: String) {
        store.edit { p -> p[KEY_BUVID3] = buvid3 }
    }

    /**
     * 登出:丢掉这次会话的 cookie 并换一个 buvid3。
     *
     * `sid` 是服务端签发给**那一次登录**的,留着它去发下一次登录的请求,交出去的就是一套
     * 拼接出来的会话。buvid3 一并换掉是照 PiliPlus 的 `delete()`;激活标记跟着 buvid3 走,
     * 新的 buvid3 没有激活过。
     */
    suspend fun clearSession() {
        store.edit { p ->
            p.remove(KEY_SERVER_COOKIES)
            p.remove(KEY_BUVID3)
            p.remove(KEY_BUVID_ACTIVATED)
        }
    }

    /** ExClimbWuzhi 只需要成功一次,标记后 DeviceFingerprint 不会再重复发起。 */
    suspend fun markBuvidActivated() {
        store.edit { p -> p[KEY_BUVID_ACTIVATED] = true }
    }

    private companion object {
        val KEY_BUVID3 = stringPreferencesKey("buvid3")
        val KEY_BUVID_ACTIVATED = booleanPreferencesKey("buvid_activated")
        val KEY_SERVER_COOKIES = stringPreferencesKey("server_cookies")
    }
}

data class FingerprintData(
    val buvid3: String = "",
    val buvidActivated: Boolean = false,
    /** 服务端通过 Set-Cookie 下发、我们原样回带的那些键(b_nut、_uuid、sid……)。 */
    val serverCookies: Map<String, String> = emptyMap(),
)

/**
 * `k=v` 用 `\n` 分隔。Cookie 的值不会包含换行(RFC 6265 的 cookie-value 排除了 CTL),
 * 所以换行是安全的分隔符,不需要引一个 JSON 依赖进来。
 */
private fun Map<String, String>.encodeCookies(): String =
    entries.joinToString("\n") { "${it.key}=${it.value}" }

private fun String.decodeCookies(): Map<String, String> =
    if (isEmpty()) emptyMap()
    else split('\n').mapNotNull { line ->
        val i = line.indexOf('=')
        if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
    }.toMap()
