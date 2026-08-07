package dev.bilby.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.bilby.BuildConfig
import dev.bilby.player.DEFAULT_PREFERRED_CODECS
import dev.bilby.player.VideoCodecId
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
            baseUrl = p[KEY_LLM_BASE_URL] ?: BuildConfig.LLM_BASE_URL.ifEmpty { DEFAULT_LLM_BASE_URL },
            apiKey = p[KEY_LLM_API_KEY] ?: BuildConfig.LLM_API_KEY,
            model = p[KEY_LLM_MODEL] ?: DEFAULT_LLM_MODEL,
        )
    }

    suspend fun saveLlmConfig(value: LlmConfig) {
        store.edit { p ->
            p[KEY_LLM_BASE_URL] = value.baseUrl.withScheme()
            p[KEY_LLM_API_KEY] = value.apiKey
            p[KEY_LLM_MODEL] = value.model
        }
    }

    /**
     * 播放器偏好。默认清晰度不在设置页里选,它由播放页那个画质菜单写进来 ——
     * 在播放时改画质就是在改全局默认(DESIGN 2 节),设置页只放"设一次就不再想"的东西。
     */
    val playerPrefs: Flow<PlayerPrefs> = store.data.map { p ->
        PlayerPrefs(
            codec = CodecPreference.fromKey(p[KEY_PREFERRED_CODEC]),
            defaultQuality = p[KEY_DEFAULT_QUALITY] ?: DEFAULT_QUALITY,
        )
    }

    suspend fun saveCodecPreference(value: CodecPreference) {
        store.edit { p -> p[KEY_PREFERRED_CODEC] = value.key }
    }

    suspend fun saveDefaultQuality(quality: Int) {
        store.edit { p -> p[KEY_DEFAULT_QUALITY] = quality }
    }

    /**
     * SponsorBlock。服务器地址可配是有原因的:它是社区跑的第三方服务,挂掉或换域名时
     * 我们这边发不出版本,用户得能自己改(PiliPlus 同样把它做成可配项)。
     */
    val sponsorBlockPrefs: Flow<SponsorBlockPrefs> = store.data.map { p ->
        SponsorBlockPrefs(
            enabled = p[KEY_SB_ENABLED] ?: true,
            categories = p[KEY_SB_CATEGORIES] ?: DEFAULT_SB_CATEGORIES,
            serverUrl = p[KEY_SB_SERVER]?.takeIf { it.isNotBlank() } ?: DEFAULT_SB_SERVER,
        )
    }

    suspend fun saveSponsorBlockPrefs(value: SponsorBlockPrefs) {
        store.edit { p ->
            p[KEY_SB_ENABLED] = value.enabled
            p[KEY_SB_CATEGORIES] = value.categories
            p[KEY_SB_SERVER] = value.serverUrl
        }
    }

    companion object {
        private val KEY_SESSDATA = stringPreferencesKey("sessdata")
        private val KEY_BILI_JCT = stringPreferencesKey("bili_jct")
        private val KEY_DEDE_USER_ID = stringPreferencesKey("dede_user_id")
        private val KEY_DEDE_CK_MD5 = stringPreferencesKey("dede_user_id_ck_md5")

        /**
         * TV/HD 扫码返回的 `token_info.refresh_token`,app 端 OAuth 那一套的刷新口令。
         *
         * **存而不用**,和 PiliPlus 一样(`LoginAccount.refresh` 全仓库没有读取点)——
         * app 端没有已知可用的"用旧 token 换新 token"接口,过期就重新扫码
         * (notes/auth-model.md §7)。留着是因为将来真出现了刷新接口时它是必需的输入,
         * 丢了就只能让用户重登。它**不是** ac_time_value,不要拿它去调网页端
         * `cookie/refresh` —— 那正是被删掉那条路犯的错。
         */
        private val KEY_APP_REFRESH_TOKEN = stringPreferencesKey("refresh_token")

        private val KEY_ACCESS_KEY = stringPreferencesKey("access_key")

        private val KEY_AUTO_NEXT = booleanPreferencesKey("playback_auto_next")
        private val KEY_SHUFFLED = booleanPreferencesKey("playback_shuffled")

        private val KEY_LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        private val KEY_LLM_API_KEY = stringPreferencesKey("llm_api_key")
        private val KEY_LLM_MODEL = stringPreferencesKey("llm_model")

        /** 任务简单,用最便宜档即可(DESIGN 3.1)。 */
        /**
         * 默认指向 DeepSeek:填个 key 就能用,不必先去查地址长什么样。改成别的服务只要
         * 换掉这两项,协议是 OpenAI 兼容的那一套。
         */
        const val DEFAULT_LLM_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_LLM_MODEL = "dsv4f"

        private val KEY_PREFERRED_CODEC = stringPreferencesKey("player_preferred_codec")
        private val KEY_DEFAULT_QUALITY = intPreferencesKey("player_default_quality")

        /** 与 `VideoRepository.DEFAULT_QUALITY` 同值(1080P)。 */
        const val DEFAULT_QUALITY = 80

        private val KEY_SB_ENABLED = booleanPreferencesKey("sponsorblock_enabled")
        private val KEY_SB_CATEGORIES = stringSetPreferencesKey("sponsorblock_categories")
        private val KEY_SB_SERVER = stringPreferencesKey("sponsorblock_server")

        const val DEFAULT_SB_SERVER = "https://www.bsbsb.top"

        /**
         * 默认跳过哪些类别。只含"跳过整段不会丢内容"的四类,和 BSponsorBlock 浏览器扩展的
         * 默认一致。离题闲聊(filler)故意不默认开:它按提交者的口味划,激进,漏掉正片的
         * 代价比多看半分钟大。
         */
        val DEFAULT_SB_CATEGORIES = setOf("sponsor", "selfpromo", "interaction", "intro", "outro")

        private val ALL_CREDENTIAL_KEYS = listOf(
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

/**
 * 编解码偏好。选的是"取流时优先要哪一条",不是"用什么解码器" ——
 * Media3 只要某个编码有硬解就会用硬解,真正的杠杆在选流(见 `player/DeviceCodecs`)。
 *
 * [Auto] 是默认值,照抄 PiliPlus 的 `[AVC, AV1]`(它不含 HEVC)。选定某一种时把它排在
 * 最前,后面仍然跟着兜底顺序 —— 不然遇到只发了另一种编码的视频会直接没流可播。
 */
enum class CodecPreference(val key: String, val label: String, val codecIds: List<Int>) {
    Auto("auto", "自动", DEFAULT_PREFERRED_CODECS),
    Avc("avc", "AVC / H.264", listOf(VideoCodecId.AVC, VideoCodecId.AV1, VideoCodecId.HEVC)),
    Hevc("hevc", "HEVC / H.265", listOf(VideoCodecId.HEVC, VideoCodecId.AVC, VideoCodecId.AV1)),
    Av1("av1", "AV1", listOf(VideoCodecId.AV1, VideoCodecId.AVC, VideoCodecId.HEVC)),
    ;

    companion object {
        fun fromKey(key: String?): CodecPreference = entries.firstOrNull { it.key == key } ?: Auto
    }
}

data class PlayerPrefs(
    val codec: CodecPreference = CodecPreference.Auto,
    val defaultQuality: Int = SettingsStore.DEFAULT_QUALITY,
)

data class SponsorBlockPrefs(
    val enabled: Boolean = true,
    val categories: Set<String> = SettingsStore.DEFAULT_SB_CATEGORIES,
    val serverUrl: String = SettingsStore.DEFAULT_SB_SERVER,
)

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val isConfigured: Boolean get() = baseUrl.isNotEmpty() && apiKey.isNotEmpty()
}

/**
 * 补上省略的 scheme。不补的话 Ktor 会当成 http,而应用禁止明文,报出来的是
 * "CLEARTEXT communication not permitted" —— 这句话完全不提"你少写了 https://",
 * 用户只会以为是网络策略挡了他。
 *
 * 本机地址补 http:自建模型(ollama、LM Studio)监听的就是 http,补成 https 反而连不上。
 */
private fun String.withScheme(): String {
    val value = trim()
    if (value.isEmpty() || value.contains("://")) return value
    val local = value.startsWith("localhost") || value.startsWith("127.0.0.1") || value.startsWith("10.0.2.2")
    return if (local) "http://$value" else "https://$value"
}
