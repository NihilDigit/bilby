package dev.bilby.ui.settings

import dev.bilby.BuildConfig
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
import dev.danmaku.compose.DanmakuDensity
import dev.danmaku.compose.DanmakuFrameRateCap
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
    val codec: CodecPreference = CodecPreference.Auto,
    val sponsorBlock: SponsorBlockPrefs = SponsorBlockPrefs(),
    /** 本机真有硬解器的编码,决定编解码那一节列出哪几项。 */
    val hardwareCodecIds: Set<Int> = emptySet(),
    /** 弹幕设置整体存一份,不为每个档位开一个平行字段——理由同 VideoViewModel 的 danmakuPrefs。 */
    val danmaku: DanmakuPrefs = DanmakuPrefs(),
    /** 首页排除了多少个 UP。为 0 时那一行不显示 —— 没排除过的人不需要看见这个概念。 */
    val excludedFeedCount: Int = 0,
    val update: UpdateState = UpdateState.Idle,
)

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
) : ViewModel() {

    /**
     * 查更新。**只在用户点这一下时才跑**:没有启动时自动检查,也没有后台轮询 ——
     * 那属于会主动打扰人的东西,和这个 app 不做红点与推送是同一条约束(DESIGN 1.3)。
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
        viewModelScope.launch {
            _state.update {
                it.copy(
                    llm = settings.llmConfig.first(),
                    codec = settings.playerPrefs.first().codec,
                    sponsorBlock = settings.sponsorBlockPrefs.first(),
                    hardwareCodecIds = DeviceCodecs.hardwareDecodableCodecIds,
                    danmaku = settings.danmakuPrefs.first(),
                )
            }
        }
        // 这一项持续跟着走而不是只读一次:清空之后那一行要立刻反映出来。
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

    fun setCodec(value: CodecPreference) {
        _state.update { it.copy(codec = value) }
        persist { settings.saveCodecPreference(value) }
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
