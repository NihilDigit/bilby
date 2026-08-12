package dev.bilby.ui.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.data.MessageRepository
import dev.bilby.data.Notice
import dev.bilby.data.NoticeCursor
import dev.bilby.data.SysNotice
import dev.bilby.data.WhisperSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 消息页的五格。**私信排第一**:它是唯一双向的,其余四格都是只读回执。
 *
 * 顺序同时决定了默认停在哪一格 —— 打开消息页最常见的意图是看有没有人找我说话。
 */
enum class MessageTab { Whispers, Replies, Mentions, Likes, Notices }

/**
 * 一格的状态。五格结构相同(一份列表 + 一个游标),所以共用一份泛型状态而不是各写各的。
 *
 * [cursor] 为 null 有两种含义,由 [loaded] 区分:还没拉过,或者已经到底了。合成一个字段的话,
 * 到底之后每次滑到底部都会重新拉第一页。
 */
data class MessageListState<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val appending: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val cursor: NoticeCursor? = null,
) {
    val hasMore: Boolean get() = !loaded || cursor != null
}

data class MessageUiState(
    val tab: MessageTab = MessageTab.Whispers,
    val whispers: MessageListState<WhisperSession> = MessageListState(),
    val replies: MessageListState<Notice> = MessageListState(),
    val mentions: MessageListState<Notice> = MessageListState(),
    val likes: MessageListState<Notice> = MessageListState(),
    val notices: MessageListState<SysNotice> = MessageListState(),
)

/**
 * 消息中心与私信会话列表。
 *
 * **每一格第一次被选中时才拉**,不是进页面就把五个接口全打一遍:四格里多数人只看第一格,
 * 而那五个请求分属三个主机。切回已经拉过的一格不重拉 —— 这一页不是实时的,来回切标签
 * 每次都转圈只会显得它很慢。要刷新有下拉。
 */
class MessageViewModel(private val repository: MessageRepository) : ViewModel() {

    private val _state = MutableStateFlow(MessageUiState())
    val state: StateFlow<MessageUiState> = _state.asStateFlow()

    init {
        load(MessageTab.Whispers)
    }

    fun selectTab(tab: MessageTab) {
        _state.update { it.copy(tab = tab) }
        if (!tabState(tab).loaded) load(tab)
    }

    /** 下拉刷新:从头拉一遍,游标归零。 */
    fun refresh() = load(_state.value.tab, reset = true)

    fun loadMore() {
        val tab = _state.value.tab
        val current = tabState(tab)
        // 私信会话列表不分页:服务端一次给的就是全部活跃会话,没有游标可续。
        if (tab == MessageTab.Whispers) return
        if (current.loading || current.appending || !current.hasMore) return
        load(tab, append = true)
    }

    private fun tabState(tab: MessageTab): MessageListState<*> = with(_state.value) {
        when (tab) {
            MessageTab.Whispers -> whispers
            MessageTab.Replies -> replies
            MessageTab.Mentions -> mentions
            MessageTab.Likes -> likes
            MessageTab.Notices -> notices
        }
    }

    private fun load(tab: MessageTab, append: Boolean = false, reset: Boolean = false) {
        when (tab) {
            MessageTab.Whispers -> loadWhispers()
            MessageTab.Replies -> loadNotices(tab, append, reset) { repository.replies(it) }
            MessageTab.Mentions -> loadNotices(tab, append, reset) { repository.mentions(it) }
            MessageTab.Likes -> loadNotices(tab, append, reset) { repository.likes(it) }
            MessageTab.Notices -> loadSysNotices(reset)
        }
    }

    private fun loadWhispers() {
        _state.update { it.copy(whispers = it.whispers.copy(loading = true, error = null)) }
        viewModelScope.launch {
            when (val result = repository.sessions()) {
                is BiliResult.Ok -> _state.update {
                    it.copy(
                        whispers = it.whispers.copy(
                            items = result.value,
                            loading = false,
                            loaded = true,
                        ),
                    )
                }

                else -> _state.update {
                    it.copy(whispers = it.whispers.copy(loading = false, loaded = true, error = result.describe()))
                }
            }
        }
    }

    private fun loadNotices(
        tab: MessageTab,
        append: Boolean,
        reset: Boolean,
        fetch: suspend (NoticeCursor?) -> BiliResult<dev.bilby.data.NoticePage>,
    ) {
        val current = noticeState(tab)
        val cursor = if (append) current.cursor else null
        updateNotices(tab) {
            it.copy(loading = !append, appending = append, error = null, items = if (reset) emptyList() else it.items)
        }
        viewModelScope.launch {
            when (val result = fetch(cursor)) {
                is BiliResult.Ok -> updateNotices(tab) {
                    it.copy(
                        items = if (append) it.items + result.value.items else result.value.items,
                        cursor = result.value.nextCursor,
                        loading = false,
                        appending = false,
                        loaded = true,
                    )
                }

                else -> updateNotices(tab) {
                    it.copy(loading = false, appending = false, loaded = true, error = result.describe())
                }
            }
        }
    }

    /**
     * 系统通知的游标是**最后一条自己带的 `cursor`**,不是响应里另给的一份 —— 这个接口没有
     * cursor 字段,续页要用上一页最后一条的值。到底的判据是"这一页少于一整页"。
     */
    private fun loadSysNotices(reset: Boolean) {
        val current = _state.value.notices
        val append = current.loaded && !reset && current.items.isNotEmpty()
        val cursor = if (append) current.items.last().cursor else null
        _state.update {
            it.copy(
                notices = it.notices.copy(
                    loading = !append,
                    appending = append,
                    error = null,
                    items = if (reset) emptyList() else it.notices.items,
                ),
            )
        }
        viewModelScope.launch {
            when (val result = repository.sysNotices(cursor)) {
                is BiliResult.Ok -> _state.update {
                    it.copy(
                        notices = it.notices.copy(
                            items = if (append) it.notices.items + result.value else result.value,
                            loading = false,
                            appending = false,
                            loaded = true,
                            // 借用同一个字段表达"还有没有下一页":空一页即到底。
                            cursor = result.value.lastOrNull()?.let { last -> NoticeCursor(last.cursor, 0) },
                        ),
                    )
                }

                else -> _state.update {
                    it.copy(notices = it.notices.copy(loading = false, appending = false, loaded = true, error = result.describe()))
                }
            }
        }
    }

    private fun noticeState(tab: MessageTab): MessageListState<Notice> = with(_state.value) {
        when (tab) {
            MessageTab.Replies -> replies
            MessageTab.Mentions -> mentions
            else -> likes
        }
    }

    private fun updateNotices(tab: MessageTab, block: (MessageListState<Notice>) -> MessageListState<Notice>) {
        _state.update {
            when (tab) {
                MessageTab.Replies -> it.copy(replies = block(it.replies))
                MessageTab.Mentions -> it.copy(mentions = block(it.mentions))
                else -> it.copy(likes = block(it.likes))
            }
        }
    }
}

/** 失败的一句话。两类失败要分开说:业务码能告诉人为什么,网络异常只能说没连上。 */
internal fun BiliResult<*>.describe(): String = when (this) {
    is BiliResult.Ok -> ""
    is BiliResult.ApiError -> "$message($code)"
    is BiliResult.Failure -> cause.message ?: "网络错误"
}
