package com.bilby.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    suspend fun clearCredentials() {
        store.edit { p -> ALL_CREDENTIAL_KEYS.forEach(p::remove) }
    }

    private companion object {
        val KEY_SESSDATA = stringPreferencesKey("sessdata")
        val KEY_BILI_JCT = stringPreferencesKey("bili_jct")
        val KEY_DEDE_USER_ID = stringPreferencesKey("dede_user_id")
        val KEY_DEDE_CK_MD5 = stringPreferencesKey("dede_user_id_ck_md5")

        /** 文档里叫 ac_time_value,存在 localStorage;这里就是 cookie 刷新的凭据。 */
        val KEY_REFRESH_TOKEN = stringPreferencesKey("refresh_token")

        val ALL_CREDENTIAL_KEYS = listOf(
            KEY_SESSDATA, KEY_BILI_JCT, KEY_DEDE_USER_ID, KEY_DEDE_CK_MD5, KEY_REFRESH_TOKEN,
        )
    }
}

data class Credentials(
    val sessdata: String = "",
    val biliJct: String = "",
    val dedeUserId: String = "",
    val dedeUserIdCkMd5: String = "",
    val refreshToken: String = "",
) {
    val isLoggedIn: Boolean get() = sessdata.isNotEmpty() && dedeUserId.isNotEmpty()
}
