package dev.bilby.ui.message

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.jetbrains.compose.resources.StringResource
import dev.bilby.api.BiliResult
import dev.bilby.ui.errorTextRes
import dev.bilby.data.MessagePage
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
 * 一格的状态。五格结构相同(一份列表 + 一个游标),所以共用一份泛型状态而不是各写各的;
 * 游标的形状各接口不同,由 [C] 带着(见 [MessagePage])。
 *
 * [cursor] 为 null 有两种含义,由 [loaded] 区分:还没拉过,或者已经到底了。合成一个字段的话,
 * 到底之后每次滑到底部都会重新拉第一页。
 */
data class MessageListState<T, C>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val appending: Boolean = false,
    /**
     * 下拉刷新中。**与 [loading] 分开**:首屏是一屏骨架,刷新是"列表还在、顶上转圈"。
     * 合成一个字段的话,每次下拉都会把已经读到的消息整片清掉再重画。
     */
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    /**
     * 失败时屏幕上说哪一句,存的是资源 id 而不是拼好的串 —— ViewModel 里没有 Context,
     * 拼好的中文既不跟语言设置走也没法在测试里断言。映射与理由见 [dev.bilby.ui.errorTextRes]。
     */
    val error: StringResource? = null,
    val cursor: C? = null,
) {
    val hasMore: Boolean get() = !loaded || cursor != null
}

data class MessageUiState(
    val tab: MessageTab = MessageTab.Whispers,
    /** 游标是上一页最后一个会话的微秒时间戳,见 [MessageRepository.sessions]。 */
    val whispers: MessageListState<WhisperSession, Long> = MessageListState(),
    val replies: MessageListState<Notice, NoticeCursor> = MessageListState(),
    val mentions: MessageListState<Notice, NoticeCursor> = MessageListState(),
    val likes: MessageListState<Notice, NoticeCursor> = MessageListState(),
    /** 游标是上一页最后一条自带的 `cursor`,见 [MessageRepository.sysNotices]。 */
    val notices: MessageListState<SysNotice, Long> = MessageListState(),
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

    /**
     * 下拉刷新:从头拉一遍,游标归零。
     *
     * **格子由调用方指名,不读 [MessageUiState.tab]。** 五格在一个 pager 里,翻页途中相邻两格
     * 同时在组合里,而 `tab` 只在翻页停稳之后才更新 —— 读它的话,路过的那一格触底预取会替
     * 另一格续页。
     */
    fun refresh(tab: MessageTab) = load(tab, reset = true)

    /** 续页。格子由调用方指名,理由见 [refresh]。 */
    fun loadMore(tab: MessageTab) {
        val current = tabState(tab)
        if (current.loading || current.appending || current.refreshing || !current.hasMore) return
        load(tab, append = true)
    }

    private fun tabState(tab: MessageTab): MessageListState<*, *> = with(_state.value) {
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
            MessageTab.Whispers -> loadPage(
                append, reset, "私信会话列表", MessageUiState::whispers,
                { s, l -> s.copy(whispers = l) }, WhisperSession::talkerId, repository::sessions,
            )

            MessageTab.Replies -> loadPage(
                append, reset, "回复我的", MessageUiState::replies,
                { s, l -> s.copy(replies = l) }, Notice::id, repository::replies,
            )

            MessageTab.Mentions -> loadPage(
                append, reset, "@我的", MessageUiState::mentions,
                { s, l -> s.copy(mentions = l) }, Notice::id, repository::mentions,
            )

            MessageTab.Likes -> loadPage(
                append, reset, "收到的赞", MessageUiState::likes,
                { s, l -> s.copy(likes = l) }, Notice::id, repository::likes,
            )

            MessageTab.Notices -> loadPage(
                append, reset, "系统通知", MessageUiState::notices,
                { s, l -> s.copy(notices = l) }, SysNotice::id, repository::sysNotices,
            )
        }
    }

    /**
     * 五格共用的一次取数。[read] 与 [write] 指明读写状态里的哪一格。
     *
     * 刷新保留屏上那一份,由回来的第一页整片换掉;首次加载才走骨架。
     *
     * **续页按 [key] 去重,续页没带来任何新条目就当作到底。** 会话列表的 `end_ts` 是否包含边界
     * 那一个没有实测过(notes/private-message.md §5),包含的话边界那个会话会出现两次;而一个
     * 忽略了游标、每次都回第一页的接口,在这里会变成无休止的触底续页。
     */
    private fun <T, C> loadPage(
        append: Boolean,
        reset: Boolean,
        where: String,
        read: (MessageUiState) -> MessageListState<T, C>,
        write: (MessageUiState, MessageListState<T, C>) -> MessageUiState,
        key: (T) -> Any,
        fetch: suspend (C?) -> BiliResult<MessagePage<T, C>>,
    ) {
        val cursor = if (append) read(_state.value).cursor else null
        _state.update { s ->
            write(s, read(s).copy(loading = !append && !reset, appending = append, refreshing = reset, error = null))
        }
        viewModelScope.launch {
            when (val result = fetch(cursor)) {
                is BiliResult.Ok -> _state.update { s ->
                    val current = read(s)
                    val page = result.value
                    val merged = if (append) (current.items + page.items).distinctBy(key) else page.items
                    val stalled = append && merged.size == current.items.size
                    write(
                        s,
                        current.copy(
                            items = merged,
                            cursor = if (stalled) null else page.next,
                            loading = false,
                            appending = false,
                            refreshing = false,
                            loaded = true,
                        ),
                    )
                }

                else -> {
                    val error = result.errorTextRes(where)
                    _state.update { s ->
                        write(
                            s,
                            read(s).copy(
                                loading = false,
                                appending = false,
                                refreshing = false,
                                loaded = true,
                                error = error,
                            ),
                        )
                    }
                }
            }
        }
    }
}
