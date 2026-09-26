package dev.bilby.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import dev.bilby.data.SystemSessionKind
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.bilby.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.resources.*
import org.jetbrains.compose.resources.StringResource
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.readableWidth
import dev.bilby.data.Notice
import dev.bilby.data.NoticeCursor
import dev.bilby.data.NoticeKind
import dev.bilby.data.SysNotice
import dev.bilby.data.WhisperContent
import dev.bilby.data.WhisperSession
import dev.bilby.data.model.plainText
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.touchOnlyPaging
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.BiliRichText
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.PersonRowSkeleton
import dev.bilby.ui.components.RefreshAction
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.SquareCover
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
    /** 宽窗口右栏正开着的那段对话,私信列表里高亮它。分栏见 BilbyApp 的 listDetailStrategy。 */
    selectedTalker: Long?,
    onSelectTab: (MessageTab) -> Unit,
    /** 续页与刷新都指名是哪一格,理由见 [MessageViewModel.refresh]。 */
    onLoadMore: (MessageTab) -> Unit,
    onRefresh: (MessageTab) -> Unit,
    onOpenWhisper: (WhisperSession) -> Unit,
    /** 点通知里的头像,进那个人的空间。 */
    onOpenSpace: (Long) -> Unit,
    /** 点一条通知去它指的地方。服务端给的是站内链接,交给 BilbyLink 解析。 */
    onOpenUri: (String) -> Unit,
    /**
     * 点一条回复、@ 或赞。落到哪儿由导航层判断:带评论定位的进评论详情页,其余照 [onOpenUri]
     * 的路走。
     */
    onOpenNotice: (Notice) -> Unit,
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

    // pinned:标签栏就在顶栏底下,顶栏自己再滑走的话标签会跟着往上跑,而它是这块区域的控制器
    // (风格指南 §7 引 tabs 页那句"Tabs control the UI region displayed below them")。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(
                title = stringResource(Res.string.message_title),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            ) {
                // 刷新按钮跟着当前这一格走:五格各有自己的 refreshing/onRefresh,顶栏只有一个入口。
                val tabRefreshing = when (state.tab) {
                    MessageTab.Whispers -> state.whispers.refreshing
                    MessageTab.Replies -> state.replies.refreshing
                    MessageTab.Mentions -> state.mentions.refreshing
                    MessageTab.Likes -> state.likes.refreshing
                    MessageTab.Notices -> state.notices.refreshing
                }
                RefreshAction(refreshing = tabRefreshing, onRefresh = { onRefresh(state.tab) })
            }
        },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padScaffoldExceptBottom(insets).readableWidth()) {
            // **五格等宽固定,不横滚。** 原先是全名("回复我的""收到的赞"…)加可滚动标签栏,
            // 手机和宽窗口的左栏都装不下,最后一两格要横着滚才看得见,鼠标上还得按住 Shift。
            // 标签缩成一两个字之后一格 72dp 就够。
            //
            // 用 Tab 的 content 重载:text 重载每边留 16dp,一格 72dp 时只剩 40dp 给字,英文的
            // Mentions 放不下。
            //
            // **指示条认 `pager.currentPage`,不认 `state.tab`。** 上面那个效应只在 settledPage
            // 上回写 tab(路过的一页不该触发加载),于是指示条整段拖动都停在原处,翻页判定过了
            // 才突然跳一格。播放页那条指示条同一条判据,见 `video/VideoTabs.kt`。
            PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = pager.currentPage == index,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        modifier = Modifier.height(TabHeight),
                    ) {
                        Text(
                            stringResource(tab.labelRes()),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = Spacing.Hair),
                        )
                    }
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth().touchOnlyPaging()) { page ->
                val tab = tabs[page]
                val loadMore = { onLoadMore(tab) }
                val refresh = { onRefresh(tab) }
                when (tab) {
                    MessageTab.Whispers -> WhisperList(state.whispers, selectedTalker, loadMore, refresh, onOpenWhisper)
                    MessageTab.Replies ->
                        NoticeList(state.replies, Res.string.message_empty_reply, loadMore, refresh, onOpenSpace, onOpenNotice)

                    MessageTab.Mentions ->
                        NoticeList(state.mentions, Res.string.message_empty_at, loadMore, refresh, onOpenSpace, onOpenNotice)

                    MessageTab.Likes ->
                        NoticeList(state.likes, Res.string.message_empty_like, loadMore, refresh, onOpenSpace, onOpenNotice)

                    MessageTab.Notices -> SysNoticeList(state.notices, loadMore, refresh, onOpenUri)
                }
            }
        }
    }
}

/** 只有文字的标签高度,同 M3 tabs 规格(与 text 重载一致)。 */
private val TabHeight = 48.dp

private fun MessageTab.labelRes(): StringResource = when (this) {
    MessageTab.Whispers -> Res.string.message_tab_whisper
    MessageTab.Replies -> Res.string.message_tab_reply
    MessageTab.Mentions -> Res.string.message_tab_at
    MessageTab.Likes -> Res.string.message_tab_like
    MessageTab.Notices -> Res.string.message_tab_notice
}

@Composable
private fun WhisperList(
    state: MessageListState<WhisperSession, Long>,
    selectedTalker: Long?,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (WhisperSession) -> Unit,
) {
    // **推送会话不进这里。** 最后一条是 UP 主投稿推送的会话,入口在订阅页页头的「UP 主推送」
    // (MessagePushesScreen 自己从头翻会话列表)。关注的 UP 一多,这类会话能占掉会话列表的大半,
    // 而用户来私信这一格要找的是有人跟他说的话。用户回一句之后最后一条变成文字,会话自然回到这里。
    val sessions = remember(state.items) { state.items.filterNot { it.lastIsUpPush } }
    RefreshBox(
        refreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        PagedColumn(
            items = sessions,
            key = { it.talkerId },
            // 行的边距、头像与两行字同 PersonRowSkeleton,直接用那一份。
            skeletonRow = { PersonRowSkeleton() },
            loading = state.showsSkeleton,
            appending = state.appending,
            hasMore = state.hasMore,
            // state 里存的是资源 id(见 [MessageListState.error]),文案在这一层取。
            error = state.error?.let { stringResource(it) },
            emptyText = stringResource(Res.string.message_empty_whisper),
            onLoadMore = onLoadMore,
            onRetry = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) { session ->
            ConversationRow(session, selected = session.talkerId == selectedTalker, onClick = { onOpen(session) })
        }
    }
}

/**
 * 首屏骨架的判据。**还没拉过也算在读**:翻页途中相邻那一格已经组合出来,而它要等翻页停稳
 * 才开始拉(见 [MessageScreen] 的 settledPage),这段时间里它是空的 —— 按空列表画的话,
 * 手指划过去先看到一句"还没有人回复你",停稳之后才换成骨架再换成内容。
 */
internal val MessageListState<*, *>.showsSkeleton: Boolean get() = loading || (!loaded && items.isEmpty())

/**
 * 会话列表的一行:头像、名字与时间、最后一句话。边距与头像尺寸同搜索结果的用户行
 * 和 PersonRowSkeleton,一个人一行的列表在全应用是同一个样子。
 *
 * **未读不画红点、不画数字,也不加粗。** 未读的会话自然排在最前(服务端按时间排),而这三种
 * 标记的用处都只是催人回来,那是 DESIGN 1.3 点名不做的。
 */
@Composable
internal fun ConversationRow(
    session: WhisperSession,
    onClick: () -> Unit,
    /** 这一段对话正开在右栏。整行染色,同历史页的多选:人从左往右扫,不找行尾的记号。 */
    selected: Boolean = false,
) {
    SessionRowLayout(
        selected = selected,
        // 系统会话也画接口给的那张图(account_info 的 pic_url),与普通会话同一个样子。
        leading = { Avatar(url = session.faceUrl, size = Dimens.AvatarStack) },
        // 接口给了名字(account_info 或用户卡片)就用接口的;系统会话没给时按种类给一个,
        // 连种类都认不出时给一个中性的说法 —— 一个没有名字的空行看起来像这一条坏了。
        title = session.name.ifBlank {
            stringResource(session.systemKind?.nameRes() ?: Res.string.whisper_system_account)
        },
        time = formatRelativeTime(session.timeSeconds),
        preview = session.lastMessage.preview(),
        onClick = onClick,
    )
}

/** 会话列表一行的骨架:左边一个 48dp 的圆,右边名字加时间、一行摘要。 */
@Composable
private fun SessionRowLayout(
    leading: @Composable () -> Unit,
    title: String,
    time: String,
    preview: String,
    onClick: () -> Unit,
    selected: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Hair / 2)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.Tight),
                )
            }
            Text(
                // 摘要只有一行,换行符会让第二行整个被截掉,读到的只是第一行。
                text = preview.replace('\n', ' '),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun SystemSessionKind.nameRes(): StringResource = when (this) {
    SystemSessionKind.LiveAssistant -> Res.string.whisper_system_live
    SystemSessionKind.UpAssistant -> Res.string.whisper_system_up
    SystemSessionKind.CustomerService -> Res.string.whisper_system_service
    SystemSessionKind.PayAssistant -> Res.string.whisper_system_pay
    SystemSessionKind.Other -> Res.string.whisper_system_account
}

/**
 * 会话列表那一行的"最后一句话"。视频、专栏、卡片折成标题,系统通知折成它的标题 —— 这一行只有
 * 一行高,而完整的样子在会话里。没有文字的几种用方括号标出类别,与官方客户端的摘要一致。
 */
@Composable
private fun WhisperContent.preview(): String = when (this) {
    is WhisperContent.Text -> spans.plainText()
    is WhisperContent.Image -> stringResource(
        if (sticker) Res.string.message_preview_sticker else Res.string.message_preview_image,
    )

    is WhisperContent.Video -> title
    is WhisperContent.VideoPush -> stringResource(Res.string.message_preview_video, title)
    is WhisperContent.Article -> title
    is WhisperContent.Link -> title
    is WhisperContent.Notice -> title.ifBlank { text }
    is WhisperContent.Hint -> text
    WhisperContent.Withdrawn -> stringResource(Res.string.message_preview_withdrawn)
    is WhisperContent.Unsupported -> ""
}

@Composable
private fun NoticeList(
    state: MessageListState<Notice, NoticeCursor>,
    emptyTextRes: StringResource,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSpace: (Long) -> Unit,
    onOpenNotice: (Notice) -> Unit,
) {
    RefreshBox(
        refreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        PagedColumn(
            items = state.items,
            key = { it.id },
            skeletonRow = { NoticeRowSkeleton() },
            loading = state.showsSkeleton,
            appending = state.appending,
            hasMore = state.hasMore,
            // state 里存的是资源 id(见 [MessageListState.error]),文案在这一层取。
            error = state.error?.let { stringResource(it) },
            emptyText = stringResource(emptyTextRes),
            onLoadMore = onLoadMore,
            onRetry = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) { notice ->
            NoticeRow(notice, onOpenSpace, onOpenNotice)
        }
    }
}

/**
 * 回复、@、赞的一行。自上而下回答四个问题:谁、做了什么、说了什么、是冲着用户的哪样东西。
 *
 * - **谁**是这一行的主要信息,和会话列表里的对方名字同一档:原先它是最弱的描边色、字号还比
 *   正文小,一列通知扫下来只剩下正文。头像可点,进那个人的空间。
 * - **做了什么**单独一行低强调的字。原先没有这一行,三个标签页的行长得一模一样,一条赞和
 *   一条 @ 只能靠自己在哪一页来分辨。
 * - **冲着哪样东西**装进一层容器:没有它,一句"说得对"读不出在说什么;而它不是这条通知的
 *   主角,所以压一档底色、缩一档字号。接口给了缩略图(视频封面、动态首图)时摆在行尾。
 *
 * 整行点开通知指向的地方;没有去处的不给点击 —— 一个按下去有涟漪、然后什么都不发生的行
 * 读起来像坏了。
 */
@Composable
private fun NoticeRow(notice: Notice, onOpenSpace: (Long) -> Unit, onOpenNotice: (Notice) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (notice.uri.isBlank() && notice.nativeUri.isBlank()) {
                    Modifier
                } else {
                    Modifier.clickable { onOpenNotice(notice) }
                },
            )
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
    ) {
        val spaceLabel = stringResource(Res.string.comment_open_space, notice.name)
        Avatar(
            url = notice.avatarUrl,
            size = Dimens.AvatarStack,
            modifier = if (notice.actorMid == 0L) {
                Modifier
            } else {
                // 先裁再点:涟漪要落在圆里,不是头像外接的那个方块上。
                Modifier
                    .clip(CircleShape)
                    .clickable(onClickLabel = spaceLabel, role = Role.Button) { onOpenSpace(notice.actorMid) }
            },
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (notice.actorCount > 1) {
                        stringResource(Res.string.message_actors, notice.name, notice.actorCount)
                    } else {
                        notice.name
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = formatRelativeTime(notice.timeSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.Tight),
                )
            }
            Text(
                text = notice.actionText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (notice.body.isNotBlank()) {
                Text(
                    text = notice.body,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
        if (notice.imageUrl.isNotBlank()) {
            SquareCover(url = notice.imageUrl, size = Dimens.AvatarStack)
        }
    }
}

/** 动作那一行。对象类别是服务端给的中文名,缺了就退到一个泛称。 */
@Composable
private fun Notice.actionText(): String {
    val target = business.ifBlank { stringResource(Res.string.message_business_fallback) }
    return when (kind) {
        NoticeKind.Reply -> stringResource(Res.string.message_action_reply)
        NoticeKind.Comment -> stringResource(Res.string.message_action_comment, target)
        NoticeKind.Mention -> stringResource(Res.string.message_action_mention, target)
        NoticeKind.Like -> stringResource(Res.string.message_action_like, target)
    }
}

@Composable
private fun SysNoticeList(
    state: MessageListState<SysNotice, Long>,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onOpenUri: (String) -> Unit,
) {
    RefreshBox(
        refreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        PagedColumn(
            items = state.items,
            key = { it.id },
            skeletonRow = { SysNoticeRowSkeleton() },
            loading = state.showsSkeleton,
            appending = state.appending,
            hasMore = state.hasMore,
            // state 里存的是资源 id(见 [MessageListState.error]),文案在这一层取。
            error = state.error?.let { stringResource(it) },
            emptyText = stringResource(Res.string.message_empty_notice),
            onLoadMore = onLoadMore,
            onRetry = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) { notice ->
            SysNoticeRow(notice, onOpenUri)
        }
    }
}

/**
 * 系统通知的一行。没有"谁",只有标题、时间和正文;正文里的链接可点(解析见
 * `MessageRepository` 的 sysNoticeSpans),整行不可点 —— 一条通知可能带两三个链接,整行点开
 * 哪一个都是猜。
 */
@Composable
private fun SysNoticeRow(notice: SysNotice, onOpenUri: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = notice.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 系统通知给的是拼好的时间字符串,不是时间戳,所以这里不折成"3 小时前"。
            Text(
                text = notice.timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.Tight),
            )
        }
        BiliRichText(
            spans = notice.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            onLinkClick = onOpenUri,
            // 系统通知的正文里没有 @。
            onMentionClick = {},
        )
    }
}
