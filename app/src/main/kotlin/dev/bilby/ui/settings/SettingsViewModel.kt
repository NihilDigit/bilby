package dev.bilby.ui.settings

import dev.bilby.BuildConfig
import dev.bilby.api.BiliResult
import dev.bilby.data.HistoryRepository
import dev.bilby.data.UpdateCheck
import dev.bilby.data.UpdateInfo
import dev.bilby.data.UpdateRepository
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.data.CodecPreference
import dev.bilby.agent.LlmClient
import dev.bilby.data.LlmConfig
import dev.bilby.data.DanmakuPrefs
import dev.nihildigit.danmaku.DanmakuDensity
import dev.nihildigit.danmaku.DanmakuFrameRateCap
import dev.bilby.data.SettingsStore
import dev.bilby.data.SponsorBlockPrefs
import dev.bilby.player.DeviceCodecs
import dev.bilby.player.PlaybackTechInfo
import dev.bilby.player.PlayerFactory
import dev.bilby.player.VideoCodecId
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 手动更新的状态机。**下载进度和结果都不落盘** —— 它描述的是这一次点击,
 * 下次进设置页应当是干净的 Idle。
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

data class SettingsUiState(
    val llm: LlmConfig? = null,
    val llmTest: LlmTest = LlmTest.Idle,
    /**
     * DataStore 那一批值到齐了没有。**没到齐之前不画任何一行。**
     *
     * 每个字段都有一个编译期默认值,而它和用户真正存的值多半不同 —— 先按默认值画一遍再改
     * 过来,开关会当着人的面自己滑一下(自动连播那一项最明显:默认开、实际关,进页面就左滑)。
     * 一个还不知道状态的开关不该先摆出一个姿势。
     *
     * 全部 prefs 都是同一个 `store.data` 的 map,所以任何一条发出第一次即代表全部可用。
     */
    val loaded: Boolean = false,
    val codec: CodecPreference = CodecPreference.Auto,
    /** 不计费网络(界面上叫 WiFi)下的默认画质。 */
    val defaultQualityWifi: Int = SettingsStore.DEFAULT_QUALITY,
    val defaultQualityMetered: Int = SettingsStore.DEFAULT_QUALITY_METERED,
    val sponsorBlock: SponsorBlockPrefs = SponsorBlockPrefs(),
    /** 本机真有硬解器的编码,决定编解码那一节列出哪几项。 */
    val hardwareCodecIds: Set<Int> = emptySet(),
    val fastForwardSpeed: Float = SettingsStore.DEFAULT_FAST_FORWARD_SPEED,
    /** 播完一条要不要接着放队列里的下一条。见 [dev.bilby.data.PlaybackPrefs.autoNext]。 */
    val autoNext: Boolean = true,
    /** 弹幕设置整体存一份,不为每个档位开一个平行字段——理由同 VideoViewModel 的 danmakuPrefs。 */
    val danmaku: DanmakuPrefs = DanmakuPrefs(),
    /** 首页排除了多少个 UP。为 0 时那一行不显示 —— 没排除过的人不需要看见这个概念。 */
    val excludedFeedCount: Int = 0,
    val offlineConcurrency: Int = SettingsStore.DEFAULT_OFFLINE_CONCURRENCY,
    val update: UpdateState = UpdateState.Idle,
    /** 服务端的「暂停记录观看历史」。不是本机偏好,所以有读不到这一档,见 [HistoryPause]。 */
    val historyPause: HistoryPause = HistoryPause.Loading,
)

/**
 * 「暂停记录观看历史」的三态。
 *
 * **读不到不能兜底成"未暂停"。** 这一项是服务端的账号设置,不像别的开关那样本机就有答案;
 * 拉失败时画一个关着的开关,等于告诉用户"正在记录",而实际上谁也不知道。三态摆开之后,
 * [Unavailable] 这一档在界面上是一行说明加一次重试,不是一个可以拨的开关。
 */
sealed interface HistoryPause {
    data object Loading : HistoryPause

    data class Known(val paused: Boolean) : HistoryPause

    data object Unavailable : HistoryPause
}

/**
 * 设置页的状态。
 *
 * **账号信息与登出已经搬到「我的」页**(`ui/profile/ProfileViewModel`)——这一页只剩
 * "怎么做"这一类设置(DESIGN 2 节),不再是登出的入口。
 *
 * **凭据不进日志**(DESIGN 8):这个类仍会经手 LLM 的 API key,
 * 全文没有一处 `BiliLog`,新增分支时也不要加 —— 想排查就看 UI 上的值。
 */
class SettingsViewModel(
    private val settings: SettingsStore,
    private val llmClient: LlmClient,
    private val updateRepository: UpdateRepository,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    /**
     * 拉「暂停记录」的当前状态。进页面时来一次,失败后可以由用户再点一次。
     *
     * 只在还没读到的时候拉:已经读到之后重复拉会把用户刚拨过、服务端还没落地的那一下盖掉。
     */
    fun loadHistoryPause() {
        if (_state.value.historyPause is HistoryPause.Known) return
        _state.update { it.copy(historyPause = HistoryPause.Loading) }
        viewModelScope.launch {
            val next = when (val result = historyRepository.isPaused()) {
                is BiliResult.Ok -> HistoryPause.Known(result.value)
                else -> HistoryPause.Unavailable
            }
            _state.update { it.copy(historyPause = next) }
        }
    }

    /**
     * 拨动「暂停记录」。先落到界面上再发请求,失败**拨回原位** —— 这一页没有别的地方能
     * 反映真实状态,一个拨过去又留在那里的开关会让人以为已经生效。
     */
    fun setHistoryPaused(paused: Boolean) {
        val previous = _state.value.historyPause
        _state.update { it.copy(historyPause = HistoryPause.Known(paused)) }
        viewModelScope.launch {
            if (historyRepository.setPaused(paused) !is BiliResult.Ok) {
                _state.update { it.copy(historyPause = previous) }
            }
        }
    }

    /**
     * 查更新。**这一条只由用户点击触发**,它的结果因此要一路显示到底(查到了、已是最新、
     * 失败了都要说)。开屏那一次是另一条路,失败不打扰(见
     * [dev.bilby.ui.update.StartupUpdateViewModel])。两条路都没有后台轮询。
     */
    fun checkUpdate() {
        if (_state.value.update is UpdateState.Checking) return
        _state.update { it.copy(update = UpdateState.Checking) }
        viewModelScope.launch {
            val result = when (val check = updateRepository.check(BuildConfig.VERSION_NAME)) {
                is UpdateCheck.Available -> UpdateState.Available(check.info)
                UpdateCheck.UpToDate -> UpdateState.UpToDate
                is UpdateCheck.Failed -> UpdateState.Failed(check.message)
            }
            _state.update { it.copy(update = result) }
        }
    }

    /**
     * 下载到应用缓存目录。同名文件先删掉再下:上一次下到一半的残包会让安装器报
     * "解析包出现问题",而那句提示指不向真正的原因。
     */
    fun downloadUpdate(info: UpdateInfo, dir: File) {
        if (_state.value.update is UpdateState.Downloading) return
        _state.update { it.copy(update = UpdateState.Downloading(info, 0f)) }
        viewModelScope.launch {
            val target = File(dir, info.assetName)
            if (target.exists()) target.delete()
            val result = updateRepository.download(info, target) { progress ->
                _state.update { current ->
                    if (current.update is UpdateState.Downloading) {
                        current.copy(update = UpdateState.Downloading(info, progress))
                    } else {
                        current
                    }
                }
            }
            _state.update {
                it.copy(
                    update = result.fold(
                        onSuccess = { apk -> UpdateState.Ready(info, apk) },
                        onFailure = { error -> UpdateState.Failed(error.message ?: "下载失败") },
                    )
                )
            }
        }
    }

    /**
     * 冒烟测试:真发一次请求,把回答显示出来。
     *
     * 结果**不落盘**:它是这一刻的连通情况,存起来只会在下次进设置页时显示一个过期的
     * "成功",而那时配置可能已经改过了。
     */
    fun smokeTestLlm() {
        if (_state.value.llmTest is LlmTest.Running) return
        _state.update { it.copy(llmTest = LlmTest.Running) }
        viewModelScope.launch {
            val result = llmClient.smokeTest()
            _state.update {
                it.copy(
                    llmTest = result.fold(
                        onSuccess = { millis -> LlmTest.Ok(millis) },
                        onFailure = { error -> LlmTest.Failed(error.message ?: "请求失败") },
                    ),
                )
            }
        }
    }

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        // **全部持续收流,不是进页面时读一次快照。** 设置拆成首页加二级页之后,两层是两个
        // NavEntry、两个 ViewModel 实例:在子页改完返回,首页那一份快照仍是进来时读到的,
        // 而首页每行右侧正显示着这些值。一次性读还有另一条老路会踩 —— 播放页切画质写的是同
        // 一批键,那时设置页可能正开着。
        //
        // hardwareCodecIds 不在其中:本机有哪些硬解器一次开机内不会变,它也不来自 DataStore。
        _state.update { it.copy(hardwareCodecIds = DeviceCodecs.hardwareDecodableCodecIds) }
        viewModelScope.launch {
            settings.llmConfig.collect { llm -> _state.update { it.copy(llm = llm) } }
        }
        viewModelScope.launch {
            settings.playerPrefs.collect { prefs ->
                _state.update {
                    it.copy(
                        loaded = true,
                        codec = prefs.codec,
                        defaultQualityWifi = prefs.defaultQualityWifi,
                        defaultQualityMetered = prefs.defaultQualityMetered,
                        fastForwardSpeed = prefs.fastForwardSpeed,
                    )
                }
            }
        }
        viewModelScope.launch {
            settings.sponsorBlockPrefs.collect { prefs -> _state.update { it.copy(sponsorBlock = prefs) } }
        }
        viewModelScope.launch {
            settings.danmakuPrefs.collect { prefs -> _state.update { it.copy(danmaku = prefs) } }
        }
        viewModelScope.launch {
            settings.playbackPrefs.collect { prefs -> _state.update { it.copy(autoNext = prefs.autoNext) } }
        }
        viewModelScope.launch {
            settings.offlineConcurrency.collect { value ->
                _state.update { it.copy(offlineConcurrency = value) }
            }
        }
        viewModelScope.launch {
            settings.excludedFeedMids.collect { mids ->
                _state.update { it.copy(excludedFeedCount = mids.size) }
            }
        }
    }

    fun clearExcludedFeedMids() {
        persist { settings.clearExcludedFeedMids() }
    }

    /**
     * 落盘一律用 [NonCancellable]。设置页的每一次改动都可能紧跟着一次返回,而返回会清掉
     * 这个 ViewModel、连带取消 `viewModelScope` —— DataStore 的 `edit` 是挂起函数,
     * 取消在它完成之前到达就是**改动被丢掉**。真机上复现过:勾一个类别立刻返回,再进来还是原样。
     */
    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch(NonCancellable) { block() }
    }

    fun saveLlm(config: LlmConfig) {
        _state.update { it.copy(llm = config) }
        persist { settings.saveLlmConfig(config) }
    }

    /**
     * 设置页改默认画质。两行各写各的那一格,和播放页切画质写的是同一批键 ——
     * 那边写的是当下所在网络的那一格,见 `AudioPlaybackService.setQuality`。
     */
    fun setDefaultQuality(quality: Int, metered: Boolean) {
        _state.update {
            if (metered) it.copy(defaultQualityMetered = quality) else it.copy(defaultQualityWifi = quality)
        }
        persist { settings.saveDefaultQuality(quality, metered) }
    }

    fun setCodec(value: CodecPreference) {
        _state.update { it.copy(codec = value) }
        persist { settings.saveCodecPreference(value) }
    }

    /**
     * 读一次再写回去,不拿界面上那份拼一个新的:[dev.bilby.data.PlaybackPrefs] 里还有随机播放,
     * 而它归播放页管、随时会变。整份覆盖会把用户刚在播放页开的随机播放悄悄关掉。
     */
    fun setAutoNext(value: Boolean) {
        _state.update { it.copy(autoNext = value) }
        persist {
            val prefs = settings.playbackPrefs.first()
            settings.savePlaybackPrefs(prefs.copy(autoNext = value))
        }
    }

    /**
     * 登出。清凭据必须扛得住页面随时被销毁,所以走 [persist] 那条 `NonCancellable`;
     * 停播放服务要 Context,由调用方在 [onDone] 里做 —— 顺序是先清凭据后停服务,
     * 反过来的话中间那一瞬服务已停而凭据还在,看起来像"没登出但停了"。
     */
    fun logout(onDone: () -> Unit) {
        persist {
            settings.clearCredentials()
            onDone()
        }
    }

    fun setFastForwardSpeed(value: Float) {
        _state.update { it.copy(fastForwardSpeed = value) }
        persist { settings.saveFastForwardSpeed(value) }
    }

    fun setOfflineConcurrency(value: Int) {
        _state.update { it.copy(offlineConcurrency = value) }
        persist { settings.saveOfflineConcurrency(value) }
    }

    fun updateSponsorBlock(value: SponsorBlockPrefs) {
        _state.update { it.copy(sponsorBlock = value) }
        persist { settings.saveSponsorBlockPrefs(value) }
    }

    // 四个设置各自落盘,不合成一次"整份 DanmakuPrefs 写回去"。整份写回会连 enabled 一起写,
    // 而那一项的真实来源是播放页的弹幕按钮:设置页开着的时候用户在播放页关掉弹幕,这里再拖一下
    // 透明度,就会把 enabled 按打开设置页那一刻的旧值覆盖回去。
    fun setDanmakuOpacity(value: Float) {
        _state.update { it.copy(danmaku = it.danmaku.copy(opacity = value)) }
        persist { settings.saveDanmakuOpacity(value) }
    }

    fun setDanmakuScrollShowArea(value: Float) {
        _state.update { it.copy(danmaku = it.danmaku.copy(scrollShowArea = value)) }
        persist { settings.saveDanmakuScrollShowArea(value) }
    }

    fun setDanmakuDensity(value: DanmakuDensity) {
        _state.update { it.copy(danmaku = it.danmaku.copy(density = value)) }
        persist { settings.saveDanmakuDensity(value) }
    }

    fun setDanmakuFrameRate(value: DanmakuFrameRateCap) {
        _state.update { it.copy(danmaku = it.danmaku.copy(frameRateCap = value)) }
        persist { settings.saveDanmakuFrameRate(value) }
    }

}

/** 本机对某个编码有没有硬解。设置页只列真支持的,不列一个选了也白选的选项。 */
fun CodecPreference.requiredCodecId(): Int? = when (this) {
    CodecPreference.Auto -> null
    CodecPreference.Avc -> VideoCodecId.AVC
    CodecPreference.Hevc -> VideoCodecId.HEVC
    CodecPreference.Av1 -> VideoCodecId.AV1
}

/** 冒烟测试的状态。成功时只给耗时:能回就说明配置是对的,内容不需要看。 */
sealed interface LlmTest {
    data object Idle : LlmTest
    data object Running : LlmTest
    data class Ok(val millis: Long) : LlmTest
    data class Failed(val message: String) : LlmTest
}
