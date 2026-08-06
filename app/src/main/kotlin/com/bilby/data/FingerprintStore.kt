package com.bilby.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.fingerprintDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "bilby_fingerprint")

/**
 * 设备指纹(buvid3/buvid4/bili_ticket)的持久化。单独开一个 DataStore 文件而不是塞进
 * SettingsStore,是因为这两类数据的生命周期不同:登录凭据随账号切换/登出而变,
 * 设备指纹是"这台设备"的身份,理应跨账号、跨登出保留。
 *
 * buvid3/buvid4 必须固定下来:它们是风控识别设备的依据,每次启动重新生成等于每次
 * 都换一台新设备,新设备在风控眼里天然可疑,只会让写接口的 -403 更频繁而不是更少。
 */
class FingerprintStore(context: Context) {

    private val store = context.fingerprintDataStore

    val data: Flow<FingerprintData> = store.data.map { p ->
        FingerprintData(
            buvid3 = p[KEY_BUVID3].orEmpty(),
            buvid4 = p[KEY_BUVID4].orEmpty(),
            biliTicket = p[KEY_BILI_TICKET].orEmpty(),
            ticketExpiresAt = p[KEY_TICKET_EXPIRES_AT] ?: 0L,
            buvidActivated = p[KEY_BUVID_ACTIVATED] ?: false,
        )
    }

    suspend fun saveBuvid(buvid3: String, buvid4: String) {
        store.edit { p ->
            p[KEY_BUVID3] = buvid3
            p[KEY_BUVID4] = buvid4
        }
    }

    suspend fun saveTicket(ticket: String, expiresAt: Long) {
        store.edit { p ->
            p[KEY_BILI_TICKET] = ticket
            p[KEY_TICKET_EXPIRES_AT] = expiresAt
        }
    }

    /** ExClimbWuzhi 只需要成功一次,标记后 DeviceFingerprint 不会再重复发起。 */
    suspend fun markBuvidActivated() {
        store.edit { p -> p[KEY_BUVID_ACTIVATED] = true }
    }

    private companion object {
        val KEY_BUVID3 = stringPreferencesKey("buvid3")
        val KEY_BUVID4 = stringPreferencesKey("buvid4")
        val KEY_BILI_TICKET = stringPreferencesKey("bili_ticket")
        val KEY_TICKET_EXPIRES_AT = longPreferencesKey("bili_ticket_expires_at")
        val KEY_BUVID_ACTIVATED = booleanPreferencesKey("buvid_activated")
    }
}

data class FingerprintData(
    val buvid3: String = "",
    val buvid4: String = "",
    val biliTicket: String = "",
    val ticketExpiresAt: Long = 0L,
    val buvidActivated: Boolean = false,
)
