package dev.bilby.ui.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.data.MessageRepository
import dev.bilby.data.SettingsStore
import dev.bilby.data.WhisperMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WhisperUiState(
    val name: String = "",
    val faceUrl: String = "",
    /** 对面不是一个人(系统通知号)。见 WhisperSession.isSystem —— 那时没有空间可去。 */
    val isSystem: Boolean = false,
    val selfMid: Long = 0,
    val messages: List<WhisperMessage> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val sending: Boolean = false,
    val sendError: String? = null,
)

/**
 * 一个会话。
 *
 * **发送成功后重拉一遍,不本地插一条。** 私信的 `msg_seqno` 由服务端定,而它是列表的 key
 * 和标记已读的凭据;本地造一个假的会在重拉时变成两条。这与点播弹幕的即时回显是两种情况:
 * 那边没有 id 冲突问题,而且弹幕的即时感本身就是内容的一部分。
 */
class WhisperViewModel(
    private val talkerId: Long,
    talkerName: String,
    talkerFaceUrl: String,
    isSystem: Boolean,
    private val repository: MessageRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(
        WhisperUiState(name = talkerName, faceUrl = talkerFaceUrl, isSystem = isSystem),
    )
    val state: StateFlow<WhisperUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update { it.copy(selfMid = settings.credentials.first().dedeUserId.toLongOrNull() ?: 0L) }
        }
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.messages(talkerId)) {
                is BiliResult.Ok -> {
                    _state.update { it.copy(messages = result.value, loading = false) }
                    markRead(result.value)
                }

                else -> _state.update { it.copy(loading = false, error = result.describe()) }
            }
        }
    }

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || _state.value.sending) return
        val selfMid = _state.value.selfMid
        if (selfMid == 0L) return
        _state.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            when (val result = repository.send(selfMid, talkerId, message)) {
                is BiliResult.Ok -> {
                    _state.update { it.copy(sending = false) }
                    load()
                }

                else -> _state.update { it.copy(sending = false, sendError = result.describe()) }
            }
        }
    }

    /**
     * 标记读到哪儿了。**尽力而为**:失败只记一行日志——这一步不影响这一页的任何显示,
     * 而为它弹一句错误只会让人以为消息没收到。
     */
    private fun markRead(messages: List<WhisperMessage>) {
        val lastSeqno = messages.lastOrNull()?.seqno ?: return
        viewModelScope.launch {
            val result = repository.ack(talkerId, lastSeqno)
            if (result !is BiliResult.Ok) BiliLog.w("标记私信已读失败: $result")
        }
    }
}
