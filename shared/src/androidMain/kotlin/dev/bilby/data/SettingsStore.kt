package dev.bilby.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.bilby.AppBuild
import dev.bilby.player.DEFAULT_PREFERRED_CODECS
import dev.bilby.player.AUDIO_QUALITY_BEST
import dev.bilby.player.VideoCodecId
import dev.nihildigit.danmaku.DanmakuDensity
import dev.nihildigit.danmaku.DanmakuFrameRateCap
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
            autoNext = p[KEY_AUTO_NEXT] ?: true,
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
            baseUrl = p[KEY_LLM_BASE_URL] ?: AppBuild.llmBaseUrl.ifEmpty { DEFAULT_LLM_BASE_URL },
            apiKey = p[KEY_LLM_API_KEY] ?: AppBuild.llmApiKey,
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
     * 播放器偏好。默认画质、默认音质在设置页里设;播放页的菜单默认只改那一次播放,
     * 见 [PlayerPrefs.playerPickUpdatesDefault]。
     */
    val playerPrefs: Flow<PlayerPrefs> = store.data.map { p ->
        PlayerPrefs(
            codec = CodecPreference.fromKey(p[KEY_PREFERRED_CODEC]),
            // 老键继续当 WiFi 那一档读:它存的就是用户此前设的那个值,而绝大多数人是在
            // WiFi 上调的画质。换个新键会让所有人的偏好在升级那一刻悄悄回到 1080P。
            defaultQualityWifi = p[KEY_DEFAULT_QUALITY] ?: DEFAULT_QUALITY,
            defaultQualityMetered = p[KEY_DEFAULT_QUALITY_METERED] ?: DEFAULT_QUALITY_METERED,
            defaultAudioWifi = p[KEY_DEFAULT_AUDIO] ?: DEFAULT_AUDIO_QUALITY,
            defaultAudioMetered = p[KEY_DEFAULT_AUDIO_METERED] ?: DEFAULT_AUDIO_QUALITY_METERED,
            playerPickUpdatesDefault = p[KEY_PLAYER_PICK_UPDATES_DEFAULT] ?: false,
            fastForwardSpeed = p[KEY_FAST_FORWARD_SPEED] ?: DEFAULT_FAST_FORWARD_SPEED,
        )
    }

    /** 默认音质,按网络计不计费分两档,同 [saveDefaultQuality]。 */
    suspend fun saveDefaultAudio(quality: Int, metered: Boolean) {
        store.edit { p ->
            if (metered) p[KEY_DEFAULT_AUDIO_METERED] = quality else p[KEY_DEFAULT_AUDIO] = quality
        }
    }

    /** 见 [PlayerPrefs.playerPickUpdatesDefault]。 */
    suspend fun savePlayerPickUpdatesDefault(enabled: Boolean) {
        store.edit { p -> p[KEY_PLAYER_PICK_UPDATES_DEFAULT] = enabled }
    }

    suspend fun saveCodecPreference(value: CodecPreference) {
        store.edit { p -> p[KEY_PREFERRED_CODEC] = value.key }
    }

    suspend fun saveFastForwardSpeed(speed: Float) {
        store.edit { p -> p[KEY_FAST_FORWARD_SPEED] = speed }
    }

    /**
     * 存默认画质。**按网络计不计费分成两档**。播放页里切画质默认只管那一次播放
     * (见 `AudioPlaybackService.sessionQuality`),打开 [PlayerPrefs.playerPickUpdatesDefault]
     * 之后也写这里,写的是当下所在网络的那一格。
     */
    suspend fun saveDefaultQuality(quality: Int, metered: Boolean) {
        store.edit { p ->
            if (metered) p[KEY_DEFAULT_QUALITY_METERED] = quality else p[KEY_DEFAULT_QUALITY] = quality
        }
    }

    /**
     * 用户按「忽略此版本」压掉的那个版本号。
     *
     * **存版本号而不是一个布尔**:存布尔的话,下一个版本发出来时那个"已忽略"还在,而用户
     * 忽略的是上一个版本,不是"以后都别提"。存号之后,只要发的不是这一版,提示照常出现。
     */
    val ignoredUpdateVersion: Flow<String> = store.data.map { p -> p[KEY_IGNORED_UPDATE].orEmpty() }

    suspend fun saveIgnoredUpdateVersion(version: String) {
        store.edit { p -> p[KEY_IGNORED_UPDATE] = version }
    }

    /**
     * 外观:明暗、纯黑、配色来源。枚举按名字存,读不出(旧值、拼错)一律退回默认,
     * 不让一条坏数据把整个主题读挂。语言不在这里,见 [dev.bilby.ui.AppLanguage]。
     */
    val appearancePrefs: Flow<AppearancePrefs> = store.data.map { p ->
        AppearancePrefs(
            mode = p[KEY_THEME_MODE]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: ThemeMode.System,
            pureBlack = p[KEY_PURE_BLACK] ?: false,
            palette = p[KEY_THEME_PALETTE] ?: AppearancePrefs.DYNAMIC,
        )
    }

    suspend fun saveThemeMode(mode: ThemeMode) {
        store.edit { p -> p[KEY_THEME_MODE] = mode.name }
    }

    suspend fun savePureBlack(enabled: Boolean) {
        store.edit { p -> p[KEY_PURE_BLACK] = enabled }
    }

    /** [AppearancePrefs.palette] 的取值:[AppearancePrefs.DYNAMIC] 或内置配色的名字。 */
    suspend fun saveThemePalette(palette: String) {
        store.edit { p -> p[KEY_THEME_PALETTE] = palette }
    }

    /**
     * 同时下几条缓存。为什么默认 1、上限 3,见 [dev.bilby.offline.OfflineDownloader] 的
     * "并发度"一节 —— 这里只负责把用户选的那个数存下来。
     */
    val offlineConcurrency: Flow<Int> = store.data.map { p ->
        (p[KEY_OFFLINE_CONCURRENCY] ?: DEFAULT_OFFLINE_CONCURRENCY).coerceIn(1, MAX_OFFLINE_CONCURRENCY)
    }

    suspend fun saveOfflineConcurrency(value: Int) {
        store.edit { p -> p[KEY_OFFLINE_CONCURRENCY] = value.coerceIn(1, MAX_OFFLINE_CONCURRENCY) }
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

    /**
     * AI 字幕选的是哪条轨(语言代码),空字符串表示关。**默认关**——字幕默认关闭是产品要求,
     * 不是"还没设置过"才关;换视频后按语言代码去找同名轨,找不到就照样关掉,不自动挑一条。
     */
    val subtitlePrefs: Flow<SubtitlePrefs> = store.data.map { p ->
        SubtitlePrefs(lan = p[KEY_SUBTITLE_LAN].orEmpty())
    }

    suspend fun saveSubtitleLan(lan: String) {
        store.edit { p -> p[KEY_SUBTITLE_LAN] = lan }
    }

    /**
     * 弹幕开关。**默认关**——和字幕一样,是产品要求,不是"还没设置过"才关。
     *
     * 滚动弹幕显示区域、同屏密度、帧率三项存的是"用户选了什么",不是引擎内部的轨道数或帧
     * 间隔:后者会随字号、画布尺寸和面板刷新率变,存进去只会在换设备后变成一份错误的记忆。
     */
    val danmakuPrefs: Flow<DanmakuPrefs> = store.data.map { p ->
        DanmakuPrefs(
            enabled = p[KEY_DANMAKU_ENABLED] ?: false,
            opacity = (p[KEY_DANMAKU_OPACITY] ?: DEFAULT_DANMAKU_OPACITY).coerceIn(0.1f, 1f),
            scrollShowArea = (p[KEY_DANMAKU_SCROLL_SHOW_AREA] ?: DEFAULT_DANMAKU_SCROLL_SHOW_AREA).coerceIn(0.1f, 1f),
            density = danmakuDensityOf(p[KEY_DANMAKU_DENSITY]),
            frameRateCap = danmakuFrameRateOf(p[KEY_DANMAKU_FRAME_RATE]),
            inPip = p[KEY_DANMAKU_IN_PIP] ?: true,
        )
    }

    suspend fun saveDanmakuInPip(enabled: Boolean) {
        store.edit { p -> p[KEY_DANMAKU_IN_PIP] = enabled }
    }

    suspend fun saveDanmakuEnabled(enabled: Boolean) {
        store.edit { p -> p[KEY_DANMAKU_ENABLED] = enabled }
    }

    suspend fun saveDanmakuOpacity(opacity: Float) {
        store.edit { p -> p[KEY_DANMAKU_OPACITY] = opacity.coerceIn(0.1f, 1f) }
    }

    /**
     * 存比例而不是四个档位的序号:档位是界面的事,加一档不该让旧值全部错位。
     *
     * 名字里的 scroll 不是修饰词,是范围:它只管滚动和顶部弹幕能铺到哪儿,底部弹幕照旧贴
     * 画面底沿。叫"弹幕显示区域"会让下一个人以为它管全部三类。
     */
    suspend fun saveDanmakuScrollShowArea(fraction: Float) {
        store.edit { p -> p[KEY_DANMAKU_SCROLL_SHOW_AREA] = fraction.coerceIn(0.1f, 1f) }
    }

    suspend fun saveDanmakuDensity(density: DanmakuDensity) {
        store.edit { p -> p[KEY_DANMAKU_DENSITY] = density.name }
    }

    suspend fun saveDanmakuFrameRate(cap: DanmakuFrameRateCap) {
        store.edit { p -> p[KEY_DANMAKU_FRAME_RATE] = cap.name }
    }

    /**
     * 排除的 UP 主。只影响本机的关注动态流(首页与"其他动态"两半),不改变 B 站的关注关系,
     * 也不影响"正在直播""最常访问"那两排 —— 那是导航,不是时间线。
     *
     * 每一项存成 `mid|名字`。**名字只为设置页里那份可撤销的名单而存**:一条动态被隐藏之后
     * 界面上再也没有它的痕迹,只给一串数字的话,撤销时无从判断哪个是谁。按第一个 `|` 切开,
     * 名字里再有 `|` 也不受影响。
     */
    val excludedFeedMids: Flow<Set<Long>> = store.data.map { p ->
        p[KEY_EXCLUDED_FEED_MIDS].orEmpty().mapNotNullTo(mutableSetOf()) { it.toExcludedMid() }
    }

    val excludedFeedUps: Flow<List<ExcludedUp>> = store.data.map { p ->
        p[KEY_EXCLUDED_FEED_MIDS].orEmpty()
            .mapNotNull { entry ->
                val mid = entry.toExcludedMid() ?: return@mapNotNull null
                ExcludedUp(mid = mid, name = entry.substringAfter(EXCLUDED_SEPARATOR, "").ifBlank { mid.toString() })
            }
            .sortedBy { it.name }
    }

    suspend fun excludeFeedMid(mid: Long, name: String) {
        store.edit { p ->
            val kept = p[KEY_EXCLUDED_FEED_MIDS].orEmpty().filterNot { it.toExcludedMid() == mid }
            p[KEY_EXCLUDED_FEED_MIDS] = kept.toSet() + "$mid$EXCLUDED_SEPARATOR$name"
        }
    }

    /**
     * 直播间的「本场早前的醒目留言」要不要去 danmakus.com 补。
     *
     * **默认开(owner 定)。** 代价说清楚:一进直播间就会把**主播的 mid** 发给一个站外服务器
     * ——不带任何 B 站凭据,也不含用户自己的身份,但那台服务器因此知道有人在看这位主播。
     * 关掉的含义是**一个请求都不发**,不是"发了但不显示",与 SponsorBlock 那条一致
     * (见 VideoViewModel.loadSponsorSegments)。
     */
    val danmakusArchiveEnabled: Flow<Boolean> = store.data.map { p ->
        p[KEY_DANMAKUS_ARCHIVE] ?: true
    }

    suspend fun setDanmakusArchiveEnabled(enabled: Boolean) {
        store.edit { p -> p[KEY_DANMAKUS_ARCHIVE] = enabled }
    }

    /** 撤销其中一位。 */
    suspend fun restoreFeedMid(mid: Long) {
        store.edit { p ->
            p[KEY_EXCLUDED_FEED_MIDS] = p[KEY_EXCLUDED_FEED_MIDS].orEmpty()
                .filterNot { it.toExcludedMid() == mid }
                .toSet()
        }
    }

    suspend fun clearExcludedFeedMids() {
        store.edit { p -> p.remove(KEY_EXCLUDED_FEED_MIDS) }
    }

    /**
     * 普通搜索的历史,最近的在前。
     *
     * **不设条数上限。** 上限曾是 5,后来是 20:要找的那个词常常是一两周前搜过的,早被挤了
     * 出去。每一条都是用户自己敲过的字,删掉哪些该由他决定(长按删一条、清空全部),而不是
     * 替他按先来后到丢掉。一排 chip 可以换行,几十个词也只占几行。
     *
     * **只记普通搜索,不记助理。** 助理的上下文按 DESIGN 3.3 只含本次意图,把提问攒成一份
     * 可点的清单,等于给它做了一份会话历史 —— 那正是那条约束要避免的东西。
     *
     * 用换行拼成一个字符串存,不是 `stringSetPreferencesKey`:Set 不保序,而这份清单的
     * 全部意义就在顺序(最近的在最上面)。搜索词本身不可能含换行(输入框是单行)。
     */
    val searchHistory: Flow<List<String>> = store.data.map { p ->
        p[KEY_SEARCH_HISTORY].orEmpty().split('\n').filter { it.isNotEmpty() }
    }

    suspend fun addSearchHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        store.edit { p ->
            val previous = p[KEY_SEARCH_HISTORY].orEmpty().split('\n').filter { it.isNotEmpty() }
            // 搜过的词再搜一次是往上提,不是多一条。
            val merged = listOf(trimmed) + previous.filterNot { it == trimmed }
            p[KEY_SEARCH_HISTORY] = merged.joinToString("\n")
        }
    }

    suspend fun clearSearchHistory() {
        store.edit { p -> p.remove(KEY_SEARCH_HISTORY) }
    }

    suspend fun removeSearchHistory(query: String) {
        store.edit { p ->
            val remaining = p[KEY_SEARCH_HISTORY].orEmpty()
                .split('\n')
                .filter { it.isNotEmpty() && it != query }
            if (remaining.isEmpty()) p.remove(KEY_SEARCH_HISTORY) else p[KEY_SEARCH_HISTORY] = remaining.joinToString("\n")
        }
    }

    companion object {
        private val KEY_SEARCH_HISTORY = stringPreferencesKey("search_history")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_PURE_BLACK = booleanPreferencesKey("theme_pure_black")
        private val KEY_THEME_PALETTE = stringPreferencesKey("theme_palette")
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
        const val DEFAULT_LLM_MODEL = "deepseek-v4-flash"

        private val KEY_PREFERRED_CODEC = stringPreferencesKey("player_preferred_codec")
        private val KEY_DEFAULT_QUALITY = intPreferencesKey("player_default_quality")
        private val KEY_DEFAULT_AUDIO = intPreferencesKey("player_default_audio")
        private val KEY_DEFAULT_AUDIO_METERED = intPreferencesKey("player_default_audio_metered")
        private val KEY_PLAYER_PICK_UPDATES_DEFAULT = booleanPreferencesKey("player_pick_updates_default")
        private val KEY_DEFAULT_QUALITY_METERED = intPreferencesKey("player_default_quality_metered")

        /**
         * WiFi 的出厂值:1080P60。**全 app 只有这一处定义默认画质**(计费网络那一档见
         * [DEFAULT_QUALITY_METERED]),`VideoRepository` 那边不再另有一个。
         *
         * 片源没有这一档时 [dev.bilby.player.resolveQuality] 会退到不高于它的最高一档,
         * 所以设了也不会取流失败。
         */
        const val DEFAULT_QUALITY = 116

        /** 计费网络的出厂值:720P。比 WiFi 低一截,但仍然是能好好看的画质。 */
        const val DEFAULT_QUALITY_METERED = 64

        /** WiFi 的默认音质:最高(有无损放无损,其次杜比),和加这一项之前的行为一致。 */
        const val DEFAULT_AUDIO_QUALITY = AUDIO_QUALITY_BEST

        /** 计费网络的默认音质:132K。无损一分钟十几 MB,这一档存在的理由就是省流量。 */
        const val DEFAULT_AUDIO_QUALITY_METERED = 30232

        /**
         * 设置页能选的档。**是一张固定表,不是某条视频的 accept_quality** —— 这里设的是
         * "默认想要哪一档",而每条视频真有哪几档要取流才知道;片源没有时
         * [dev.bilby.player.resolveQuality] 会就近退档。
         *
         * 不列 8K、HDR、杜比:那几档只在极少数片源上存在,摆在默认值里等于让人设一个几乎
         * 永远退档的值。
         */
        val QUALITY_OPTIONS = listOf(120, 116, 112, 80, 64, 32, 16)

        private val KEY_FAST_FORWARD_SPEED = floatPreferencesKey("player_fast_forward_speed")

        /** 长按加速的倍率。3x 是 B 站客户端的档位,也是这里的默认。 */
        const val DEFAULT_FAST_FORWARD_SPEED = 3f

        /** 可选档位。再快画面就只剩一串跳帧,再慢和正常倍速区分不出来。 */
        val FAST_FORWARD_SPEEDS = listOf(2f, 2.5f, 3f)

        private val KEY_IGNORED_UPDATE = stringPreferencesKey("ignored_update_version")

        private val KEY_OFFLINE_CONCURRENCY = intPreferencesKey("offline_concurrency")

        /** 默认仍是一条一条下,和加这个设置之前的行为一样。 */
        const val DEFAULT_OFFLINE_CONCURRENCY = 1

        /** 上限。理由和档位清单一起写在 [OFFLINE_CONCURRENCY_OPTIONS] 上。 */
        const val MAX_OFFLINE_CONCURRENCY = 3

        /**
         * 可选并发度。**上限是 3 不是"随便填"**:再往上,瓶颈从带宽换成风控 —— 每条都要先打
         * 一次 playurl,同时打十次是那个接口最不该出现的形状。三条已经足够把家用带宽吃满。
         */
        val OFFLINE_CONCURRENCY_OPTIONS = listOf(1, 2, 3)

        private val KEY_SB_ENABLED = booleanPreferencesKey("sponsorblock_enabled")
        private val KEY_SB_CATEGORIES = stringSetPreferencesKey("sponsorblock_categories")
        private val KEY_SB_SERVER = stringPreferencesKey("sponsorblock_server")

        private val KEY_SUBTITLE_LAN = stringPreferencesKey("subtitle_lan")
        private val KEY_DANMAKU_ENABLED = booleanPreferencesKey("danmaku_enabled")
        private val KEY_DANMAKU_OPACITY = floatPreferencesKey("danmaku_opacity")
        private val KEY_DANMAKU_SCROLL_SHOW_AREA = floatPreferencesKey("danmaku_scroll_show_area")
        private val KEY_DANMAKU_DENSITY = stringPreferencesKey("danmaku_density")
        private val KEY_DANMAKU_FRAME_RATE = stringPreferencesKey("danmaku_frame_rate")
        private val KEY_DANMAKU_IN_PIP = booleanPreferencesKey("danmaku_in_pip")
        private val KEY_EXCLUDED_FEED_MIDS = stringSetPreferencesKey("excluded_feed_mids")

        private val KEY_DANMAKUS_ARCHIVE = booleanPreferencesKey("danmakus_archive_enabled")

        /** 见 [excludedFeedMids]。 */
        const val EXCLUDED_SEPARATOR = '|'

        const val DEFAULT_DANMAKU_OPACITY = 1f

        /**
         * 滚动弹幕从画面顶部起占 75%,底下 25% 不铺。铺得够满,同时给画面底部留一条不被滚动
         * 弹幕糊住的带。界面上给 25/50/75/100 四档,存的是比例本身,加减档位不会让旧值错位。
         */
        const val DEFAULT_DANMAKU_SCROLL_SHOW_AREA = 0.75f

        /** 认不出来的值(降级、手改、将来删档)一律回到默认档,不抛异常。 */
        private fun danmakuDensityOf(name: String?): DanmakuDensity =
            DanmakuDensity.entries.firstOrNull { it.name == name } ?: DanmakuDensity.STANDARD

        private fun danmakuFrameRateOf(name: String?): DanmakuFrameRateCap =
            DanmakuFrameRateCap.entries.firstOrNull { it.name == name } ?: DanmakuFrameRateCap.DISPLAY

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

/** 排除名单里的一位。名字是排除那一刻记下的,不回头核对 —— 改名了也还是同一个人。 */
data class ExcludedUp(val mid: Long, val name: String)

/** `mid|名字` 里的 mid。旧格式(只有数字)照样认得,升级不用迁移。 */
private fun String.toExcludedMid(): Long? =
    substringBefore(SettingsStore.EXCLUDED_SEPARATOR).toLongOrNull()

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

/**
 * [autoNext] 只管**自动**前进:一条播完了要不要接着放队列里的下一条。手动的下一条(界面按钮、
 * 通知栏、耳机线控双击)不受它影响 —— 那些是用户当场表达的意思,没有理由被一个设置挡住。
 *
 * 默认开。DESIGN 1.3 原先把"自动连播"整条列进永不实现清单,那一条已经作废(见该处),现在的
 * 边界只剩 2.4b 那句:**禁止从推荐池续接队列**。队列的内容来自合集、UP 投稿或稍后再看,都是
 * 有限且能穷尽的集合,播完即停 —— 这与"放完一条自动放下一条"是两件事,前者是产品约束,后者
 * 是听感偏好。
 */
data class PlaybackPrefs(val autoNext: Boolean = true, val shuffled: Boolean = false)

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
    /** 不计费网络(通常就是 WiFi)下的默认画质。 */
    val defaultQualityWifi: Int = SettingsStore.DEFAULT_QUALITY,
    /** 计费网络下的默认画质。默认比 WiFi 低一截 —— 这一档存在的理由就是省流量。 */
    val defaultQualityMetered: Int = SettingsStore.DEFAULT_QUALITY_METERED,
    /** 不计费网络下的默认音质,音质 id 或 [AUDIO_QUALITY_BEST]。 */
    val defaultAudioWifi: Int = SettingsStore.DEFAULT_AUDIO_QUALITY,
    val defaultAudioMetered: Int = SettingsStore.DEFAULT_AUDIO_QUALITY_METERED,
    /**
     * 播放页里切画质、音质时,顺带改掉设置里当前网络那一档的默认值。**默认关**:在一条恰好有
     * 1080P+ 的视频上切了一下,不该连带改掉以后所有视频的默认档。PiliPlus 默认是开的,另有
     * 一个「临时播放配置」开关关掉它;这里反过来,默认只管这一次。
     */
    val playerPickUpdatesDefault: Boolean = false,
    /** 长按画面时的临时倍速。松手恢复原速,不写回默认画质那种全局偏好。 */
    val fastForwardSpeed: Float = SettingsStore.DEFAULT_FAST_FORWARD_SPEED,
) {
    fun defaultAudioOn(metered: Boolean): Int = if (metered) defaultAudioMetered else defaultAudioWifi

    /**
     * 这一次该用哪一档。**判据是计不计费而不是"是不是 WiFi"** —— 要省的是流量:手机热点和
     * 按量计费的 WiFi 都该走省的那一档,而它们在 `TRANSPORT_WIFI` 眼里都是 WiFi。
     * 界面上仍叫「WiFi」和「计费网络」,那是这两种情况的常见名字。
     */
    fun defaultQualityOn(metered: Boolean): Int =
        if (metered) defaultQualityMetered else defaultQualityWifi
}

/** 明暗。三档互斥;纯黑是深色之下的另一个开关,不是第四档,见 [AppearancePrefs.pureBlack]。 */
enum class ThemeMode { System, Light, Dark }

data class AppearancePrefs(
    val mode: ThemeMode = ThemeMode.System,
    /**
     * 深色时页面底色压到纯黑。只作用于深色(包括跟随系统时的夜间),浅色下没有意义。
     * 做成开关而不是第四档:它和"什么时候深色"是两件事,做成一档的话"跟随系统 + 纯黑"
     * 就选不出来。
     */
    val pureBlack: Boolean = false,
    /**
     * 配色来源:[DYNAMIC] 是系统按壁纸取色(Android 12+),其余是内置配色的名字
     * (`dev.bilby.ui.theme.ThemePalette`)。存名字而不是序号:以后增删内置色不会让已存的选择
     * 指到别的颜色上。
     */
    val palette: String = DYNAMIC,
) {
    companion object {
        const val DYNAMIC = "dynamic"
    }
}

data class SponsorBlockPrefs(
    val enabled: Boolean = true,
    val categories: Set<String> = SettingsStore.DEFAULT_SB_CATEGORIES,
    val serverUrl: String = SettingsStore.DEFAULT_SB_SERVER,
)

/** 空字符串是关(默认值)。非空时是某条字幕轨的语言代码,如 `ai-zh`。 */
data class SubtitlePrefs(val lan: String = "")

/**
 * 弹幕设置。[scrollShowArea] 是**滚动与顶部**弹幕能占画面高度的比例(界面上的 25/50/75/100%
 * 四档),底部弹幕不受它约束;[density] 决定用哪个调度器排布;[frameRateCap] 是弹幕层自己的
 * 绘制上限,和视频解码帧率无关。
 */
data class DanmakuPrefs(
    val enabled: Boolean = false,
    val opacity: Float = 1f,
    val scrollShowArea: Float = SettingsStore.DEFAULT_DANMAKU_SCROLL_SHOW_AREA,
    val density: DanmakuDensity = DanmakuDensity.STANDARD,
    val frameRateCap: DanmakuFrameRateCap = DanmakuFrameRateCap.DISPLAY,
    /**
     * 画中画小窗里画不画弹幕。**默认画**:小窗是边做别的事边看,弹幕正是那时候还想瞟一眼的
     * 东西;嫌挡画面的人在这里关。只在 [enabled] 为真时才有意义,总开关关着小窗里也不画。
     */
    val inPip: Boolean = true,
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
