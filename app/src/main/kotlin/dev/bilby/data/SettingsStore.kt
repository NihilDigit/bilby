package dev.bilby.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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
            refreshToken = p[KEY_REFRESH_TOKEN].orEmpty(),
            accessKey = p[KEY_ACCESS_KEY].orEmpty(),
        )
    }

    suspend fun saveCredentials(value: Credentials) {
        store.edit { p ->
            p[KEY_SESSDATA] = value.sessdata
            p[KEY_BILI_JCT] = value.biliJct
            p[KEY_DEDE_USER_ID] = value.dedeUserId
            p[KEY_DEDE_CK_MD5] = value.dedeUserIdCkMd5
            p[KEY_REFRESH_TOKEN] = value.refreshToken
        }
    }

    /** app 端接口的凭据,与 Cookie 并存:写操作走 app 路线需要它。 */
    suspend fun saveAccessKey(value: String) {
        store.edit { it[KEY_ACCESS_KEY] = value }
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

        /** 文档里叫 ac_time_value,存在 localStorage;这里就是 cookie 刷新的凭据。 */
        val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")

        val KEY_ACCESS_KEY = stringPreferencesKey("access_key")

        val KEY_LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        val KEY_LLM_API_KEY = stringPreferencesKey("llm_api_key")
        val KEY_LLM_MODEL = stringPreferencesKey("llm_model")

        /** 任务简单,用最便宜档即可(DESIGN 3.1)。 */
        const val DEFAULT_LLM_MODEL = "deepseek-chat"

        val ALL_CREDENTIAL_KEYS = listOf(
            KEY_SESSDATA, KEY_BILI_JCT, KEY_DEDE_USER_ID, KEY_DEDE_CK_MD5, KEY_REFRESH_TOKEN,
            KEY_ACCESS_KEY,
        )
    }
}

data class Credentials(
    val sessdata: String = "",
    val biliJct: String = "",
    val dedeUserId: String = "",
    val dedeUserIdCkMd5: String = "",
    val refreshToken: String = "",
    val accessKey: String = "",
) {
    val isLoggedIn: Boolean get() = sessdata.isNotEmpty() && dedeUserId.isNotEmpty()
}

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && apiKey.isNotEmpty()
}
