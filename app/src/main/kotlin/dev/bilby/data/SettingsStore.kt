package dev.bilby.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.bilby.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bilby")

/**
 * 凭据与 LLM 配置一律明文存 DataStore。单用户个人设备的威胁模型下已明确接受
 * (DESIGN 2.6);Keystore 加密是 TODO,不阻塞任何里程碑。
 */
class SettingsStore(context: Context) {

    private val store = context.dataStore

    val credentials: Flow<Credentials> = store.data.map { p ->
        Credentials(
            sessdata = p[KEY_SESSDATA].orEmpty(),
            biliJct = p[KEY_BILI_JCT].orEmpty(),
            dedeUserId = p[KEY_DEDE_USER_ID].orEmpty(),
            dedeUserIdCkMd5 = p[KEY_DEDE_CK_MD5].orEmpty(),
            appRefreshToken = p[KEY_APP_REFRESH_TOKEN].orEmpty(),
            accessKey = p[KEY_ACCESS_KEY].orEmpty(),
        )
    }

    /**
     * 一次写入全部登录产物。access_key 也在这里 —— 拆成两次 edit 会让 credentials 流
     * 先发一个"已登录但没有 access_key"的中间态,那一瞬间发出去的点赞/投币会裸奔。
     */
    suspend fun saveLogin(value: Credentials) {
        store.edit { p ->
            p[KEY_SESSDATA] = value.sessdata
            p[KEY_BILI_JCT] = value.biliJct
            p[KEY_DEDE_USER_ID] = value.dedeUserId
            p[KEY_DEDE_CK_MD5] = value.dedeUserIdCkMd5
            p[KEY_APP_REFRESH_TOKEN] = value.appRefreshToken
            p[KEY_ACCESS_KEY] = value.accessKey
        }
    }

    /** 播放偏好:连播与随机。是用户偏好,不按队列类型猜(DESIGN 2.4b)。 */
    val playbackPrefs: Flow<PlaybackPrefs> = store.data.map { p ->
        PlaybackPrefs(
            autoNext = p[KEY_AUTO_NEXT] ?: false,
            shuffled = p[KEY_SHUFFLED] ?: false,
        )
    }

    suspend fun savePlaybackPrefs(value: PlaybackPrefs) {
        store.edit { p ->
            p[KEY_AUTO_NEXT] = value.autoNext
            p[KEY_SHUFFLED] = value.shuffled
        }
    }

    suspend fun clearCredentials() {
        store.edit { p -> ALL_CREDENTIAL_KEYS.forEach(p::remove) }
    }

    /** 未配置时回落到 BuildConfig(debug 版从 local.properties 注入),省去每次装机重输。 */
    val llmConfig: Flow<LlmConfig> = store.data.map { p ->
        LlmConfig(
            baseUrl = p[KEY_LLM_BASE_URL] ?: BuildConfig.LLM_BASE_URL,
            apiKey = p[KEY_LLM_API_KEY] ?: BuildConfig.LLM_API_KEY,
            model = p[KEY_LLM_MODEL] ?: DEFAULT_LLM_MODEL,
        )
    }

    suspend fun saveLlmConfig(value: LlmConfig) {
        store.edit { p ->
            p[KEY_LLM_BASE_URL] = value.baseUrl
            p[KEY_LLM_API_KEY] = value.apiKey
            p[KEY_LLM_MODEL] = value.model
        }
    }

    private companion object {
        val KEY_SESSDATA = stringPreferencesKey("sessdata")
        val KEY_BILI_JCT = stringPreferencesKey("bili_jct")
        val KEY_DEDE_USER_ID = stringPreferencesKey("dede_user_id")
        val KEY_DEDE_CK_MD5 = stringPreferencesKey("dede_user_id_ck_md5")

        /**
         * TV/HD 扫码返回的 `token_info.refresh_token`,app 端 OAuth 那一套的刷新口令。
         *
         * **存而不用**,和 PiliPlus 一样(`LoginAccount.refresh` 全仓库没有读取点)——
         * app 端没有已知可用的"用旧 token 换新 token"接口,过期就重新扫码
         * (notes/auth-model.md §7)。留着是因为将来真出现了刷新接口时它是必需的输入,
         * 丢了就只能让用户重登。它**不是** ac_time_value,不要拿它去调网页端
         * `cookie/refresh` —— 那正是被删掉那条路犯的错。
         */
        val KEY_APP_REFRESH_TOKEN = stringPreferencesKey("refresh_token")

        val KEY_ACCESS_KEY = stringPreferencesKey("access_key")

        val KEY_AUTO_NEXT = booleanPreferencesKey("playback_auto_next")
        val KEY_SHUFFLED = booleanPreferencesKey("playback_shuffled")

        val KEY_LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        val KEY_LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val KEY_LLM_MODEL = stringPreferencesKey("llm_model")

        /** 任务简单,用最便宜档即可(DESIGN 3.1)。 */
        const val DEFAULT_LLM_MODEL = "deepseek-chat"

        val ALL_CREDENTIAL_KEYS = listOf(
            KEY_SESSDATA, KEY_BILI_JCT, KEY_DEDE_USER_ID, KEY_DEDE_CK_MD5,
            KEY_APP_REFRESH_TOKEN, KEY_ACCESS_KEY,
        )
    }
}

data class Credentials(
    val sessdata: String = "",
    val biliJct: String = "",
    val dedeUserId: String = "",
    val dedeUserIdCkMd5: String = "",
    /** TV/HD 扫码返回的 app 端 OAuth refresh_token。存而不用,见 SettingsStore 里的说明。 */
    val appRefreshToken: String = "",
    val accessKey: String = "",
) {
    val isLoggedIn: Boolean get() = sessdata.isNotEmpty() && dedeUserId.isNotEmpty()
}

data class PlaybackPrefs(val autoNext: Boolean = false, val shuffled: Boolean = false)

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && apiKey.isNotEmpty()
}
