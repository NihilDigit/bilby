package dev.bilby.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.data.CodecPreference
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

data class SettingsUiState(
    val account: AccountUiState = AccountUiState(),
    val llm: LlmConfig? = null,
    val codec: CodecPreference = CodecPreference.Auto,
    val sponsorBlock: SponsorBlockPrefs = SponsorBlockPrefs(),
    val tech: PlaybackTechInfo = PlaybackTechInfo(),
    /** 本机真有硬解器的编码,决定编解码那一节列出哪几项。 */
    val hardwareCodecIds: Set<Int> = emptySet(),
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
) : ViewModel() {

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
        // 解码器名在开始解码后才有值,倍速算法在音频格式确定后才准 —— 都得跟着流走,
        // 不能只在进页面时取一次快照。
        viewModelScope.launch {
            PlayerFactory.techInfo.collect { info -> _state.update { it.copy(tech = info) } }
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
