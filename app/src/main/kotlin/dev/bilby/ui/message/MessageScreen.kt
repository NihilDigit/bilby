package dev.bilby.ui.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.R
import dev.bilby.data.Notice
import dev.bilby.data.SysNotice
import dev.bilby.data.WhisperSession
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * 消息。**五格并列:私信、回复、@、赞、通知。**
 *
 * 这五类是同一件事的五个来源,分成两个入口就要在「我的」页占两行。私信排第一,它是唯一
 * 双向的。
 *
 * **一个计数都不显示,也不画红点**(DESIGN 1.3)。它们把"有新东西"变成一个替用户安排注意力
 * 的信号;这一页是用户自己走进来的,进来之后未读那几条自然在最上面。
 *
 * 标签用 **primary**:它们紧贴顶栏,代表这一页的主内容分区 —— 与空间页那三个同一档,
 * 而不是播放页那种"在内容区里再分一层"的 secondary。
 */
@Composable
fun MessageScreen(
    state: MessageUiState,
    onSelectTab: (MessageTab) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onOpenWhisper: (WhisperSession) -> Unit,
    /** 点一条通知去它指的地方。服务端给的是站内链接,交给 BilbyLink 解析。 */
    onOpenUri: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = MessageTab.entries
    val pager = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    // 翻页和标签是同一个状态的两种改法,所以只在**停下来**之后同步一次:用 currentPage 的话,
    // 手指拖过中点就会触发一次加载,而那一页可能只是路过。
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { onSelectTab(tabs[it]) }
    }
    LaunchedEffect(state.tab) {
        val index = tabs.indexOf(state.tab)
        if (index != pager.currentPage) pager.animateScrollToPage(index)
    }

    Scaffold(
        modifier = modifier,
        topBar = { BilbyTopBar(title = stringResource(R.string.message_title), onBack = onBack) },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            // 五格在窄屏上放不下等宽固定标签("私信""回复我的""@我的""收到的赞""系统通知"),
            // 所以用可滚动的那一种:它按内容给宽度,装不下就横滚,而不是把每一格挤到三个字。
            PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(state.tab), edgePadding = Spacing.Cozy) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = tabs.indexOf(state.tab) == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        text = { Text(stringResource(tab.labelRes()), maxLines = 1, softWrap = false) },
                    )
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                when (tabs[page]) {
                    MessageTab.Whispers -> WhisperList(state.whispers, onLoadMore, onRefresh, onOpenWhisper)
                    MessageTab.Replies -> NoticeList(state.replies, R.string.message_empty_reply, onLoadMore, onRefresh, onOpenUri)
                    MessageTab.Mentions -> NoticeList(state.mentions, R.string.message_empty_at, onLoadMore, onRefresh, onOpenUri)
                    MessageTab.Likes -> NoticeList(state.likes, R.string.message_empty_like, onLoadMore, onRefresh, onOpenUri)
                    MessageTab.Notices -> SysNoticeList(state.notices, onLoadMore)
                }
            }
        }
    }
}

private fun MessageTab.labelRes(): Int = when (this) {
    MessageTab.Whispers -> R.string.message_tab_whisper
    MessageTab.Replies -> R.string.message_tab_reply
    MessageTab.Mentions -> R.string.message_tab_at
    MessageTab.Likes -> R.string.message_tab_like
    MessageTab.Notices -> R.string.message_tab_notice
}

@Composable
private fun WhisperList(
    state: MessageListState<WhisperSession>,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (WhisperSession) -> Unit,
) {
    PagedColumn(
        items = state.items,
        key = { it.talkerId },
        loading = state.loading,
        appending = false,
        // 会话列表没有分页,一次给的就是全部活跃会话。
        hasMore = false,
        error = state.error,
        emptyText = stringResource(R.string.message_empty_whisper),
        onLoadMore = onLoadMore,
        onRetry = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) { session ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(session) }
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(url = session.faceUrl, size = Dimens.AvatarRow)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // 系统通知号查不到名字和头像(`user/cards` 对它返回空),给一个中性
                        // 的说法 —— 一个没有名字的空行看起来像这一条坏了。
                        text = session.name.ifBlank { stringResource(R.string.whisper_system_account) },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatRelativeTime(session.timeSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                // **未读不画红点也不画数字。** 未读的会话自然排在最前(服务端按时间排),
                // 而一个数字的用处只有催人回来,那是 DESIGN 1.3 点名不做的。
                Text(
                    text = session.lastMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NoticeList(
    state: MessageListState<Notice>,
    emptyTextRes: Int,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onOpenUri: (String) -> Unit,
) {
    PagedColumn(
        items = state.items,
        key = { it.id },
        loading = state.loading,
        appending = state.appending,
        hasMore = state.hasMore,
        error = state.error,
        emptyText = stringResource(emptyTextRes),
        onLoadMore = onLoadMore,
        onRetry = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) { notice ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 没有去处的通知不给点击:一个按下去有涟漪、然后什么都不发生的行读起来像坏了。
                .then(if (notice.uri.isBlank()) Modifier else Modifier.clickable { onOpenUri(notice.uri) })
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        ) {
            Avatar(url = notice.avatarUrl, size = Dimens.AvatarRow)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (notice.actorCount > 1) {
                            stringResource(R.string.message_actors, notice.name, notice.actorCount)
                        } else {
                            notice.name
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatRelativeTime(notice.timeSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (notice.body.isNotBlank()) {
                    Text(text = notice.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                // 被回复的原文装进一层容器:没有它,一句"说得对"读不出在说什么;而它不是这条
                // 通知的主角,所以压一档底色、缩一档字号。
                notice.quoted?.let { quoted ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(top = Spacing.Hair),
                    ) {
                        Text(
                            text = quoted,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(Spacing.Tight),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SysNoticeList(state: MessageListState<SysNotice>, onLoadMore: () -> Unit) {
    PagedColumn(
        items = state.items,
        key = { it.id },
        loading = state.loading,
        appending = state.appending,
        hasMore = state.hasMore,
        error = state.error,
        emptyText = stringResource(R.string.message_empty_notice),
        onLoadMore = onLoadMore,
        modifier = Modifier.fillMaxSize(),
    ) { notice ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = notice.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 系统通知给的是拼好的时间字符串,不是时间戳,所以这里不折成"3 小时前"。
                Text(
                    text = notice.timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                text = notice.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
