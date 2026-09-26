package dev.bilby.ui.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.data.MessageRepository
import dev.bilby.data.SettingsStore
import dev.bilby.data.WhisperMessage
import dev.bilby.data.WhisperPage
import dev.bilby.resources.*
import dev.bilby.ui.errorTextRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

data class WhisperUiState(
    val name: String = "",
    val faceUrl: String = "",
    /** 对面不是一个人(系统通知号)。见 WhisperSession.isSystem —— 那时没有空间可去。 */
    val isSystem: Boolean = false,
    val selfMid: Long = 0,
    /** 按时间先后排,最早的在前。 */
    val messages: List<WhisperMessage> = emptyList(),
    val loading: Boolean = true,
    /** 首屏失败的那一句,资源 id,理由同 [MessageListState.error]。 */
    val error: StringResource? = null,
    /** 比屏上最早那条更早的还有没有。往上翻到顶时据此决定要不要再取一段。 */
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    /**
     * 取更早那一段失败的那一句。非空时不再自动往上取,顶端改成一行重试:否则顶端那个转圈行
     * 一出一没,布局一变就又触发一次,失败了再来(见 PrefetchNearEnd)。
     */
    val olderError: StringResource? = null,
    val sending: Boolean = false,
    val sendError: SendError? = null,
    /**
     * 成功发出过几条。**草稿只在这个数变大之后才清**(见 [WhisperScreen] 的 `WhisperInput`)。
     *
     * 界面那侧把草稿存在 `rememberSaveable` 里,而这一份状态是发送结果唯一的落点:成功与失败
     * 在协程里分道,界面拿不到那个分支。用计数而不是布尔量是因为连发两条时布尔量的第二次
     * 没有边沿,那条草稿会留在框里。
     */
    val sentCount: Int = 0,
)

/**
 * 一次发送为什么失败。
 *
 * **服务端给了原话就用原话**,不折成"操作被拒绝"那一档([dev.bilby.ui.errorTextRes] 的做法):
 * 私信被拒的理由是人要读的 —— "对方主动回复或关注你前，最多发送1条消息"、"对方设置了隐私"
 * —— 读了才知道下一步是等、改,还是算了。网络这类没有原话的失败才落到资源 id。
 */
data class SendError(val serverMessage: String?, val fallback: StringResource)

/**
 * 一个会话。
 *
 * **发送成功后补取新的那一段,不本地插一条。** 私信的 `msg_seqno` 由服务端定,而它是列表的
 * key 和标记已读的凭据;本地造一个假的会在补取时变成两条。这与点播弹幕的即时回显是两种情况:
 * 那边没有 id 冲突问题,而且弹幕的即时感本身就是内容的一部分。
 *
 * **补取不整段重拉。** 整段重拉拿回的是最新的一页,用户往上翻出来的旧消息会整片消失。
 * 三种取法(首屏、往上翻、发送后补取)的结果都按 seqno 合并进同一份列表,见 [merge]。
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

    /** 最早与最晚那条的序列号,续页与补取的游标。取自过滤前的原始条目,见 [WhisperPage]。 */
    private var oldestSeqno: Long? = null
    private var newestSeqno: Long? = null

    /** 首屏,以及首屏失败后的重试。 */
    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.messages(talkerId)) {
                is BiliResult.Ok -> {
                    val page = result.value
                    oldestSeqno = page.oldestSeqno
                    newestSeqno = page.newestSeqno
                    _state.update { it.copy(messages = merge(it.messages, page), loading = false, hasOlder = page.hasOlder) }
                    markRead()
                }

                else -> {
                    val error = result.errorTextRes("私信会话 $talkerId")
                    _state.update { it.copy(loading = false, error = error) }
                }
            }
        }
    }

    /**
     * 往上翻到顶,再取更早的一段。失败只记日志、不占屏:这时屏上已经有一整段对话,
     * 一次没取到,再往上拨一下就会重试。
     */
    fun loadOlder() {
        val current = _state.value
        val before = oldestSeqno ?: return
        if (current.loading || current.loadingOlder || !current.hasOlder) return
        _state.update { it.copy(loadingOlder = true, olderError = null) }
        viewModelScope.launch {
            when (val result = repository.messages(talkerId, beforeSeqno = before)) {
                is BiliResult.Ok -> {
                    val page = result.value
                    page.oldestSeqno?.let { oldest -> oldestSeqno = minOf(oldest, before) }
                    _state.update {
                        val merged = merge(it.messages, page)
                        // 一段没带来任何新条目也当作到顶,理由同消息中心的续页(MessageViewModel.loadPage)。
                        val stalled = merged.size == it.messages.size
                        it.copy(messages = merged, loadingOlder = false, hasOlder = page.hasOlder && !stalled)
                    }
                }

                else -> {
                    val error = result.errorTextRes("私信更早的消息 $talkerId")
                    _state.update { it.copy(loadingOlder = false, olderError = error) }
                }
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
                // 计数前进只发生在这里,界面靠它认出"这条真的发出去了",才把草稿清掉。
                is BiliResult.Ok -> {
                    _state.update { it.copy(sending = false, sendError = null, sentCount = it.sentCount + 1) }
                    fetchNewer()
                }

                is BiliResult.ApiError -> {
                    BiliLog.w("发送私信失败(${result.code}): ${result.message}")
                    _state.update {
                        it.copy(
                            sending = false,
                            sendError = SendError(result.message.takeIf { m -> m.isNotBlank() }, Res.string.error_refused),
                        )
                    }
                }

                is BiliResult.Failure -> {
                    val fallback = result.errorTextRes("发送私信")
                    _state.update { it.copy(sending = false, sendError = SendError(null, fallback)) }
                }
            }
        }
    }

    /**
     * 补取比屏上最晚那条更晚的消息:刚发出去的那条,以及发送期间对方回过来的。
     * 屏上还什么都没有(首屏失败后直接发了一条)时退回首屏那一取。
     */
    private fun fetchNewer() {
        val after = newestSeqno ?: return load()
        viewModelScope.launch {
            when (val result = repository.messages(talkerId, afterSeqno = after)) {
                is BiliResult.Ok -> {
                    val page = result.value
                    page.newestSeqno?.let { newest -> newestSeqno = maxOf(newest, after) }
                    _state.update { it.copy(messages = merge(it.messages, page)) }
                    markRead()
                }

                else -> result.errorTextRes("私信补取 $talkerId")
            }
        }
    }

    /** 按 seqno 合并、去重、按时间先后排。三种取法都经过这里,见类说明。 */
    private fun merge(current: List<WhisperMessage>, page: WhisperPage): List<WhisperMessage> =
        (current + page.messages).associateBy { it.seqno }.values.sortedBy { it.seqno }

    /**
     * 标记读到哪儿了。**尽力而为**:失败只记一行日志——这一步不影响这一页的任何显示,
     * 而为它弹一句错误只会让人以为消息没收到。
     */
    private fun markRead() {
        val lastSeqno = newestSeqno ?: return
        viewModelScope.launch {
            when (val result = repository.ack(talkerId, lastSeqno)) {
                is BiliResult.Ok -> Unit
                is BiliResult.ApiError -> BiliLog.w("标记私信已读失败(${result.code}): ${result.message}")
                is BiliResult.Failure -> BiliLog.w("标记私信已读异常", result.cause)
            }
        }
    }
}
