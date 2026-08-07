package dev.bilby.ui.settings

import dev.bilby.BuildConfig
import dev.bilby.data.UpdateCheck
import dev.bilby.data.UpdateInfo
import dev.bilby.data.UpdateRepository
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.data.CodecPreference
import dev.bilby.agent.LlmClient
import dev.bilby.data.LlmConfig
import dev.bilby.data.SettingsStore
import dev.bilby.data.SpaceRepository
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

data class AccountUiState(
    val uid: String = "",
    /** 拉到昵称之前显示 UID,拉不到就一直是 UID —— 这一页不该因为一次接口失败而空着。 */
    val name: String? = null,
)

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
    val account: AccountUiState = AccountUiState(),
    val llm: LlmConfig? = null,
    val llmTest: LlmTest = LlmTest.Idle,
    val codec: CodecPreference = CodecPreference.Auto,
    val sponsorBlock: SponsorBlockPrefs = SponsorBlockPrefs(),
    /** 本机真有硬解器的编码,决定编解码那一节列出哪几项。 */
    val hardwareCodecIds: Set<Int> = emptySet(),
    val update: UpdateState = UpdateState.Idle,
)

/**
 * 设置页的状态。
 *
 * **凭据不进日志**(DESIGN 8):这个类会经手 LLM 的 API key 与登录凭据,
 * 全文没有一处 `BiliLog`,新增分支时也不要加 —— 想排查就看 UI 上的值。
 */
class SettingsViewModel(
    private val settings: SettingsStore,
    private val spaceRepository: SpaceRepository,
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
            val credentials = settings.credentials.first()
            _state.update { it.copy(account = AccountUiState(uid = credentials.dedeUserId)) }
            _state.update {
                it.copy(
                    llm = settings.llmConfig.first(),
                    codec = settings.playerPrefs.first().codec,
                    sponsorBlock = settings.sponsorBlockPrefs.first(),
                    hardwareCodecIds = DeviceCodecs.hardwareDecodableCodecIds,
                )
            }
            credentials.dedeUserId.toLongOrNull()?.let { mid ->
                val profile = spaceRepository.loadProfile(mid)
                if (profile is BiliResult.Ok) {
                    _state.update { it.copy(account = it.account.copy(name = profile.value.name)) }
                }
            }
        }
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

    /**
     * 登出。清凭据是一半,另一半是停播放服务 —— 流地址是已经取到的签名直链,
     * 不停服务的话通知栏那条还能点、点了还能接着放,一个已登出的账号还在放视频没法解释。
     * 服务由调用方停(它需要 Context),这里只负责凭据。
     */
    fun logout(onDone: () -> Unit) {
        viewModelScope.launch(NonCancellable) {
            settings.clearCredentials()
            onDone()
        }
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
