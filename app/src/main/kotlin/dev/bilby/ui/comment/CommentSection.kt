package dev.bilby.ui.comment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ModalBottomSheet
import dev.bilby.ui.components.LoadingSpinner
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilby.R
import dev.bilby.data.CommentItem
import dev.bilby.data.CommentLink
import dev.bilby.data.CommentMention
import dev.bilby.data.CommentSort
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.ImageViewer
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.LevelBadge
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.components.SortRow
import dev.bilby.ui.components.inlineEmoteSize
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.LocalMentionColor
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.formatRelativeTime
import java.time.Instant
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

private const val PrefetchThreshold = 5

/**
 * 楼中楼超过这么多条就改用底部面板,不在主列表里就地摊开。
 *
 * **这不是给列表设的上限**,是同一份内容换一个容器:面板里那份不翻页上限、不截断,该有
 * 多少条给多少条。分界的理由是内联那条路把整组回复组合在主列表的**一个** LazyColumn item
 * 里 —— 几百条一次性进组合树,而 lazy 的懒只作用在 item 之间。少于这个数的楼就地展开更省事,
 * 多的那些交给面板,它自己有一个 LazyColumn。
 *
 * 判据取服务端报的子回复总数([CommentItem.subReplyCount],即 `rcount`),在拉之前就能定,
 * 所以不会出现"展开到一半才改换容器"。
 */
private const val SubReplyPanelThreshold = 7

/**
 * 可嵌进播放页的评论区(DESIGN 2.3)。不是整页:自带 LazyColumn 提供滚动,但不假设自己
 * 独占屏幕,调用方通过 [modifier] 给出高度约束。楼中楼默认只显示
 * [CommentItem.previewReplies],点「展开」才请求更多;超过 [SubReplyPanelThreshold] 条的
 * 那些改在底部面板里读,见 [SubReplyPanel]。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CommentSection(
    state: CommentUiState,
    onSort: (CommentSort) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onExpandReplies: (rootId: Long) -> Unit,
    onSend: (text: String, replyTo: Long?) -> Unit,
    onLike: (id: Long) -> Unit,
    onDelete: (id: Long) -> Unit,
    onSeek: (Long) -> Unit = {},
    /** 点正文里的 @ 去那个人的空间。配不到 mid 的 @ 不是链接,不会走到这里。 */
    onUserClick: (mid: Long) -> Unit = {},
    /**
     * 点正文里的站内链接。传进来的是一条 https 地址,**落地方式由调用方决定** ——
     * 全应用只有 `ui/BilbyLink.kt` 的 `destinationOf` 一个站内入口,认不出来的交给浏览器,
     * 和通知、专栏正文那两处是同一条路。评论区不自己判断能不能打开。
     */
    onOpenLink: (url: String) -> Unit = {},
    /**
     * 这一刻允不允许下拉刷新。**播放器收起着的时候要传 false**:那时下滑的意思是"把播放器
     * 拉回来",详见下面那处注释。
     */
    refreshEnabled: Boolean = true,
    /**
     * 排在排序栏之前、跟着一起滚的一块内容。**动态详情页把那条动态本身放在这里。**
     *
     * 钉在顶上不行:一条九宫格图文能占掉大半屏,把评论区压成一条缝,而那一页存在的理由
     * 就是评论。默认 null,播放页不受影响 —— 它的正文在另一个 tab 里,不进这个列表。
     */
    header: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var replyTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // **snackbar 的宿主在评论区自己身上**,不从导航层穿进来 —— 同一条判断见 `FavFolderScreen`
    // 那处注释。这里还多一层理由:评论区是播放页 tab 里的一块,它上面那半屏是播放器,
    // 把回执报到整页底部会盖在播放控件上。
    //
    // 楼中楼面板另有一份自己的([SubReplyPanel]),不共用这个:`ModalBottomSheet` 自成一个
    // window 画在活动窗口之上,这一份在它底下,从面板里复制会往一个看不见的地方报。
    val snackbarHostState = remember { SnackbarHostState() }

    // **收起是本地状态,不回收 ViewModel 里那份展开结果。** 一楼展开到第三页再收起,
    // 重新展开时如果连结果一起丢了,就是重新翻三页 —— 而用户收起的意思是"这一段先不占地方",
    // 不是"忘掉它"。ViewModel 那边按 rootRpid 缓存,这里只记哪些楼此刻不展示。
    //
    // 不进 rememberSaveable:被它记着的 expandedReplies 本来就活不过进程重建,只留一份
    // "收起"标记的话,回来看到的是一个展不开的空壳。
    val collapsed = remember { mutableStateListOf<Long>() }

    // 收起过的那一楼再点"展开"只是把它放回来,不重新发请求 —— 结果一直在 ViewModel 里。
    // 其余情形(首次展开、翻下一页、失败重试)一律透传出去。
    val expandOrRestore: (Long) -> Unit = { rootId ->
        val restored = collapsed.remove(rootId) && state.expandedReplies[rootId] != null
        if (!restored) onExpandReplies(rootId)
    }

    // 展开结果被 ViewModel 清掉时(下拉刷新、或者回复发出去之后重拉这一楼),收起标记跟着清。
    // 留着的话,重新拉回来的那一组一上来就是收着的 —— 而用户刚做的动作正是为了看它。
    LaunchedEffect(state.expandedReplies) {
        collapsed.retainAll { it in state.expandedReplies }
    }

    // 面板正在读哪一楼。整个面板长在这个 composable 里,不进导航栈:它是评论区内部的一层,
    // 页面本身没有换,返回键由 ModalBottomSheet 自己接管。
    var panelRoot by rememberSaveable { mutableStateOf<Long?>(null) }
    val panelComment = panelRoot?.let { id -> findRoot(state, id) }

    // 面板里选中的回复对象。**和 [replyTarget] 分成两份**:那一份驱动底部那条常驻输入栏,
    // 而面板正盖在它上面,把面板里的选择写进它,人看不到任何反应。两份都清空才算没有在回复。
    var panelReplyTo by rememberSaveable { mutableStateOf<Long?>(null) }

    // 面板关掉时把它带的回复态一起收掉。留着的话下次打开面板,输入层直接是展开的,
    // 而对着的是上一楼里某条已经翻不到的回复。
    LaunchedEffect(panelRoot) {
        if (panelRoot == null) panelReplyTo = null
    }

    // 面板开着的时候主楼被刷掉了(下拉刷新之后它不在第一页了),把面板一起关掉:
    // 留着一个找不到主楼的面板,里面的回复没有任何上下文。
    LaunchedEffect(panelRoot, panelComment == null) {
        if (panelRoot != null && panelComment == null) panelRoot = null
    }

    // 触底预取,写法照抄 FeedScreen:在 composition 外用 snapshotFlow 观察滚动位置,
    // 不能在 composable body 里直接调用 onLoadMore(那样每次重组都会触发一次)。
    LaunchedEffect(listState, state.hasMore, state.appending) {
        snapshotFlow { listState.layoutInfo }
            .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
            .distinctUntilChanged()
            .filter { (lastVisible, total) -> lastVisible != null && lastVisible >= total - 1 - PrefetchThreshold }
            .collect {
                if (state.hasMore && !state.appending) onLoadMore()
            }
    }

    // 输入栏跟着键盘走。放在这一层而不是输入框上:内层退让的话,输入栏上面那段列表
    // 不会跟着上移,打字时看不到自己在回复哪一条。
    Column(modifier = modifier.imePadding()) {
        // 下拉刷新只套列表,不套输入栏:输入栏是常驻控件,被下拉手势带着往下走没有道理。
        //
        // **[refreshEnabled] 为假时整个手势不接管。** 嵌套滚动从内往外传,而播放器那个收起
        // 页头的连接挂在这一整块的祖先上(见 VideoScreen),这里的刷新框离列表更近:不设这道
        // 闸的话,列表到顶后剩下的下滑量会先被刷新吃掉,播放器再也展不开 —— 空间页正是这么
        // 坏掉的。用手写的 `pullToRefresh` 而不是 `PullToRefreshBox`,只因为后者不给这个开关。
        val pullState = rememberPullToRefreshState()
        Box(
            modifier = Modifier
                .weight(1f)
                .pullToRefresh(
                    isRefreshing = state.refreshing,
                    state = pullState,
                    enabled = refreshEnabled,
                    onRefresh = onRefresh,
                ),
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Spacing.Tight),
        ) {
            header?.let { item(key = "header") { it() } }
            item(key = "sort-bar") { SortBar(state.sort, onSort) }

            state.topComment?.let { top ->
                item(key = "top-${top.rpid}") {
                    CommentRow(
                        comment = top,
                        pinned = true,
                        myMid = state.myMid,
                        expanded = state.expandedReplies[top.rpid],
                        collapsed = top.rpid in collapsed,
                        onReplyTo = { replyTarget = it.rpid },
                        onLike = onLike,
                        onDelete = onDelete,
                        onExpandReplies = expandOrRestore,
                        onCollapseReplies = { collapsed += it },
                        onOpenPanel = { panelRoot = it },
                        onSeek = onSeek,
                        onUserClick = onUserClick,
                        onOpenLink = onOpenLink,
                        snackbar = snackbarHostState,
                    )
                }
            }

            // 出错时不叠空态:一次失败不等于"这条视频没人评论",两句话一起出现只会互相拆台。
            if (!state.loading && state.error == null && state.items.isEmpty() && state.topComment == null) {
                item(key = "empty") { EmptyState(stringResource(R.string.comment_empty)) }
            }

            itemsIndexed(state.items, key = { _, comment -> comment.rpid }) { index, comment ->
                // **主楼之间画 inset 分割线。** 一条热评加上楼中楼容器可以占到半屏,
                // 只靠留白的话上一条的楼中楼和下一条的头像挨在一起,读不出哪里换了人。
                //
                // 用 inset 而不是 full-width:M3 divider 页把 inset 定义为"分隔一个区块内部
                // 的相关内容",并要求它对齐头像这类锚定元素的前缘 —— 评论列表正是那一页
                // 举的"一列邮件"的例子。full-width 是留给不相关的大段内容的,评论条与条之间
                // 不是那个关系。
                //
                // **只画在两条评论之间。** 无条件画在每条前面的话,列表第一条那根会落在排序栏
                // 底下,看起来像是给"最热/最新"加了一条下划线 —— 而排序栏和评论列表之间没有
                // 需要分隔的东西,它们是同一批内容和它的排序方式。
                if (index > 0 || state.topComment != null) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = CommentTextInset, end = Spacing.Comfortable),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
                CommentRow(
                    comment = comment,
                    pinned = false,
                    myMid = state.myMid,
                    expanded = state.expandedReplies[comment.rpid],
                    collapsed = comment.rpid in collapsed,
                    onReplyTo = { replyTarget = it.rpid },
                    onLike = onLike,
                    onDelete = onDelete,
                    onExpandReplies = expandOrRestore,
                    onCollapseReplies = { collapsed += it },
                    onOpenPanel = { panelRoot = it },
                    onSeek = onSeek,
                    onUserClick = onUserClick,
                    onOpenLink = onOpenLink,
                    snackbar = snackbarHostState,
                )
            }

            item(key = "footer") {
                if (state.loading) {
                    Box(Modifier.fillMaxWidth().padding(Spacing.Comfortable), Alignment.Center) {
                        LoadingSpinner()
                    }
                } else {
                    ListFooter(
                        appending = state.appending,
                        hasMore = state.hasMore,
                        hasItems = state.items.isNotEmpty() || state.topComment != null,
                        error = state.error,
                        onRetry = onLoadMore,
                    )
                }
            }
        }
            // LoadingIndicator 档,不是旧的箭头圈:判据和默认配色见 docs/ui-style-guide.md §2.7d。
            PullToRefreshDefaults.LoadingIndicator(
                state = pullState,
                isRefreshing = state.refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            // 贴着列表下缘,不贴屏幕下缘:再往下就是常驻输入栏,盖住它等于挡掉这一刻
            // 唯一还能做的事。只在 Android 12L 及以下会出现,见 CommentCopy.kt。
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        // 输入栏底色是 surfaceContainer,和上面列表的 surface 已经差着一档 —— 边界靠色阶
        // 表达就够了(§1.1),再压一条线是同一件事说两遍,而 divider 页要求 sparingly。
        CommentInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            replyTarget = replyTarget?.let { id -> findUname(state, id) },
            onCancelReply = { replyTarget = null },
            sending = state.sending,
            onSend = {
                onSend(inputText, replyTarget)
                inputText = ""
                replyTarget = null
            },
        )
    }

    // 面板挂在 Column 外面。挂在里面的话它会被算进上面那份 `imePadding` 的布局,
    // 而 ModalBottomSheet 自己就是一个 window,两层退让叠在一起。
    if (panelComment != null) {
        SubReplyPanel(
            root = panelComment,
            expanded = state.expandedReplies[panelComment.rpid],
            myMid = state.myMid,
            onLoadMore = { onExpandReplies(panelComment.rpid) },
            onLike = onLike,
            // **选回复对象不再关面板。** 关掉它是上一版的做法,理由是"输入栏在面板底下,
            // 面板开着打不了字" —— 那个理由只说明底部那条常驻输入栏用不上,不说明必须把面板
            // 连同上下文一起收走。人是在这一组回复里读到某条才想回它的,面板一关,他刚读的
            // 那几条、滚到的位置全没了,发完还得自己找回来。现在改成在面板之上浮一层输入,
            // 见 [ReplyInputLayer]。
            replyTo = panelReplyTo,
            replyToName = panelReplyTo?.let { id -> findUname(state, id) },
            onReplyTo = { panelReplyTo = it.rpid },
            onCancelReply = { panelReplyTo = null },
            inputText = inputText,
            onInputChange = { inputText = it },
            sending = state.sending,
            onSendReply = {
                onSend(inputText, panelReplyTo)
                inputText = ""
                panelReplyTo = null
                // 底部那条常驻输入栏和这里共用 inputText。它自己的回复对象要一起清掉,
                // 否则关掉面板之后它还顶着"回复 @某某",而那条草稿已经发出去了。
                replyTarget = null
            },
            onDelete = onDelete,
            onSeek = onSeek,
            onUserClick = onUserClick,
            onOpenLink = onOpenLink,
            onDismiss = { panelRoot = null },
        )
    }
}

/** 主楼(含置顶楼)。楼中楼不在这里找 —— 面板只认主楼。 */
private fun findRoot(state: CommentUiState, rpid: Long): CommentItem? =
    state.topComment?.takeIf { it.rpid == rpid } ?: state.items.find { it.rpid == rpid }

/** 找回复对象的名字。**置顶楼和它的预览楼层都要找**,理由同 `CommentViewModel.findComment`。 */
private fun findUname(state: CommentUiState, rpid: Long): String? {
    val roots = listOfNotNull(state.topComment) + state.items
    roots.find { it.rpid == rpid }?.let { return it.uname }
    roots.forEach { root -> root.previewReplies.find { it.rpid == rpid }?.let { return it.uname } }
    state.expandedReplies.values.forEach { expanded ->
        expanded.items.find { it.rpid == rpid }?.let { return it.uname }
    }
    return null
}

/** 最热 / 最新是排序,风格指南 §2.1 把排序划给 SortRow;segmented button 留给切换视图。 */
@Composable
private fun SortBar(sort: CommentSort, onSort: (CommentSort) -> Unit) {
    val options = listOf(
        CommentSort.HOT to R.string.comment_sort_hot,
        CommentSort.TIME to R.string.comment_sort_time,
    )
    SortRow(
        options = options,
        selected = sort,
        onSelect = onSort,
        modifier = Modifier.padding(horizontal = Spacing.Comfortable),
    )
}

@Composable
private fun CommentRow(
    comment: CommentItem,
    pinned: Boolean,
    myMid: Long?,
    expanded: ExpandedReplies?,
    collapsed: Boolean,
    onReplyTo: (CommentItem) -> Unit,
    onLike: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onExpandReplies: (Long) -> Unit,
    onCollapseReplies: (Long) -> Unit,
    onOpenPanel: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onUserClick: (Long) -> Unit,
    onOpenLink: (String) -> Unit,
    /** 长按复制的回执落在哪。就近取所在 window 的那一份,理由见 [CommentSection] 里的注释。 */
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.Comfortable, end = Spacing.Cozy, top = Spacing.Cozy, bottom = Spacing.Hair),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
    ) {
        // 头像本身仍是 36dp,热区往下补到 48dp:这一列的高度由右边那一整条评论决定,
        // 往下扩不挤走任何东西。对齐取 TopCenter 而不是默认的居中,否则头像会跟着下沉 6dp。
        Box(
            modifier = Modifier
                .heightIn(min = Dimens.MinTouchTarget)
                .openSpace(comment, onUserClick),
            contentAlignment = Alignment.TopCenter,
        ) {
            Avatar(url = comment.avatarUrl, size = Dimens.AvatarRow)
        }
        Column(modifier = Modifier.weight(1f)) {
            // 头部两行(名字 / 时间  属地)是一块整的元信息,照 PiliPlus 的
            // `reply_item_grpc.dart` 的 _buildHeader。
            //
            // **两行合起来是一个可点节点。** 名字那一行只有 20dp 高,单独接点击按不中;
            // 而时间和属地说的也是同一个人发的这条评论,读屏把它们念成一个"去他的空间"
            // 比念成两件事更接近实情。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.MinTouchTarget)
                    .openSpace(comment, onUserClick),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // **用户名低一档强调,不用满对比度。** 一屏几十条评论,真正要读的是正文;
                    // 名字和正文一样重的话,视线会被每条开头的名字拽住,整片看起来就是一团。
                    //
                    // 低强调的文字角色是 onSurfaceVariant。`outline` 是描边角色,只保证 3:1,
                    // 浅色主题下这一行是 4.3:1,不到小字要求的 4.5:1。
                    Text(
                        text = comment.uname,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    LevelBadge(level = comment.level, senior = comment.isSeniorMember, height = Dimens.LevelBadgeHeight)
                    if (comment.isUploader) {
                        Tag(
                            stringResource(R.string.comment_tag_up),
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if (pinned) {
                        Tag(
                            stringResource(R.string.comment_tag_pinned),
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                SubLine(comment)
            }

            Spacer(Modifier.height(Spacing.Tight))
            CommentText(
                message = comment.message,
                emotes = comment.emotes,
                mentions = comment.mentions,
                style = CommentBodyStyle,
                links = comment.links,
                onSeek = onSeek,
                onUserClick = onUserClick,
                onOpenLink = onOpenLink,
                // 复制原文,不是渲染出来的样子。理由见 [copyOnLongPress] 的 `text` 参数,
                // 以及下面 SubReplyRow 那处同样的调用。
                modifier = Modifier.copyOnLongPress(comment.message.trim(), snackbar),
            )

            if (comment.pictureUrls.isNotEmpty()) {
                PictureGrid(
                    urls = comment.pictureUrls,
                    onClick = { index -> viewerIndex = index },
                    modifier = Modifier.padding(top = Spacing.Tight),
                )
            }

            CommentActions(
                comment = comment,
                canDelete = myMid != null && myMid == comment.mid,
                onLike = onLike,
                onDelete = onDelete,
                onReply = { onReplyTo(comment) },
            )
            SubReplies(
                comment = comment,
                expanded = expanded,
                collapsed = collapsed,
                myMid = myMid,
                onExpandReplies = onExpandReplies,
                onCollapseReplies = onCollapseReplies,
                onOpenPanel = onOpenPanel,
                onReplyTo = onReplyTo,
                onLike = onLike,
                onDelete = onDelete,
                onSeek = onSeek,
                onUserClick = onUserClick,
                onOpenLink = onOpenLink,
                snackbar = snackbar,
            )
        }
    }

    viewerIndex?.let { index ->
        ImageViewer(
            urls = comment.pictureUrls,
            initialIndex = index,
            onDismiss = { viewerIndex = null },
        )
    }
}

/**
 * 头像与名字点开这个人的空间。**复用 [CommentText] 那条 `onUserClick`**,不另开一条平行的
 * 回调:正文里的 @提及 和头部的名字指的是同一件事(去这个人的空间),两条路会各自漂移。
 *
 * `mid` 为 0 时不可点 —— 那是接口没给出人(注销的账号),点进去只会是一个空空间。
 *
 * 读屏靠 `onClickLabel` 念出去处:头像的 `contentDescription` 按风格指南 §3 是 null
 * (旁边就是同一个人的名字),没有它读屏只会念一句"按钮"。
 */
@Composable
private fun Modifier.openSpace(comment: CommentItem, onUserClick: (Long) -> Unit): Modifier {
    val label = stringResource(R.string.comment_open_space, comment.uname)
    return clickable(
        enabled = comment.mid != 0L,
        onClickLabel = label,
        role = Role.Button,
    ) { onUserClick(comment.mid) }
}

/** "3 小时前  IP属地:广东"。11sp,和名字同属元信息那一块。 */
@Composable
private fun SubLine(comment: CommentItem) {
    val text = listOf(formatRelativeTime(comment.ctimeEpochSeconds), comment.ipLocation)
        .filter { it.isNotBlank() }
        .joinToString(MetaSeparator)
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 评论正文的字号与行高。**行高是这一屏可读性的主要杠杆**:`bodyMedium` 是 14/22,
 * 而 PiliPlus 的评论正文用的是 `TextStyle(height: 1.75, fontSize: 14)`,也就是 14/24.5。
 * 汉字墨迹几乎占满 em 框,22 的行距在一条五六行的长评论里会糊成一片。这里取 14/24。
 *
 * 不改 `Typography` 里的 `bodyMedium`:那一档还给列表标题、队列条目等等用着,
 * 它们要的是紧凑,不是宽松。行高是按**这段文字有多长**定的,不是按字号定的。
 */
private val CommentBodyStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp)

/**
 * 摊开之后的楼中楼正文。**字号和主楼一样是 14sp,只把行高收一档(24 → 22)。**
 *
 * 上一版取的是 `bodySmall` 12sp:那是把层级压在字号上,而一条回复和一条评论是同一种东西 ——
 * 读者读到楼中楼时并没有换一副眼镜。层级由头像尺寸和缩进承担(见 [SubReplyAvatarSize]),
 * 那两样不影响这段文字本身好不好读。PiliPlus 在楼中楼那一级同样是 14 号字
 * (`reply_item_grpc.dart` 的 `_buildContent`,`replyLevel` 只改缩进和截断,不改字号)。
 */
private val SubReplyBodyStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)

/** 预览层那两三行。同字号,行高再收一档 —— 它只有两行的位置,行距占掉的就是内容。 */
private val SubReplyPreviewStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp)

/** 预览层每条截到两行。PiliPlus 的 `replyItemRow` 同样是 2。 */
private const val SubReplyPreviewMaxLines = 2

/**
 * 楼中楼的头像,主楼那档([Dimens.AvatarRow] 36dp)的三分之二。
 *
 * **不进 `Dimens`,因为它不是第四档通用头像尺寸**,是这一处的层级刻度:主楼 36、回复 24,
 * 差 12dp 一眼分得出,而风格指南 §2.7 挡掉的是"36 和 40 谁也看不出来"那种档位。
 * 要在别处也画一圈小头像时再提到 `Dimens` 里,现在提上去只会是一个只有一个调用点的常量。
 */
private val SubReplyAvatarSize = 24.dp

private val GridSpacing = 4.dp

/**
 * 评论配图。张数决定列数,照 PiliPlus 的
 * `common/widgets/image_grid/image_grid_builder.dart`:1 张单独放大,2 与 4 张走两列,
 * 其余走三列;格子是正方形,超出的部分裁掉,**点开才看全**。
 *
 * 单张那格用 4:3 而不是按原图比例,是因为接口层的 `ReplyPictureDto` 只解析了 `img_src`,
 * 没有 `img_width`/`img_height` —— 拿不到原始比例就没法像 PiliPlus 那样按比例定尺寸。
 * 补这两个字段要动 `api/dto`,不在这一轮的边界内。
 */
@Composable
private fun PictureGrid(urls: List<String>, onClick: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (urls.size == 1) {
        BiliAsyncImage(
            url = urls[0],
            contentDescription = stringResource(R.string.comment_picture),
            modifier = modifier
                .fillMaxWidth(SinglePictureWidthFraction)
                .aspectRatio(4f / 3f)
                .clip(MaterialTheme.shapes.small)
                .clickable(role = Role.Button) { onClick(0) },
        )
        return
    }

    val columns = if (urls.size == 2 || urls.size == 4) 2 else 3
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(GridSpacing)) {
        // 带上原始下标再分行。用 indexOf 反查会在同一条评论重复配同一张图时全部指回第一张,
        // 点第三张打开的是第一张。
        urls.withIndex().chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GridSpacing), modifier = Modifier.fillMaxWidth()) {
                row.forEach { (index, url) ->
                    BiliAsyncImage(
                        url = url,
                        contentDescription = stringResource(R.string.comment_picture),
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.small)
                            .clickable(role = Role.Button) { onClick(index) },
                    )
                }
                // 最后一行不满时补空位,否则两张图会被拉宽到占满整行。
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val SinglePictureWidthFraction = 0.7f

/**
 * 正文相对屏幕左缘的缩进 = 页边距 16 + 头像 36 + 头像与文字的间距 12。
 * inset 分割线对齐到这里,也就是对齐头像的**后**缘、正文的前缘(M3 divider 页对
 * inset divider 的要求:与锚定元素对齐)。
 */
private val CommentTextInset = Spacing.Comfortable + Dimens.AvatarRow + Spacing.Cozy

/**
 * 点赞 / 回复 / 删除。三个按钮以前都是 32dp 见方,低于 48dp 的最小触摸目标 ——
 * 在正文旁边一行密排着,误触相邻按钮的概率不低,而"删除"就在里面。
 * 现在统一用默认尺寸的 IconButton(自带 48dp 触摸区),视觉上仍靠 16dp 的图标保持轻。
 */
@Composable
private fun CommentActions(
    comment: CommentItem,
    canDelete: Boolean,
    onLike: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onReply: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onLike(comment.rpid) }) {
            Icon(
                imageVector = if (comment.liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                contentDescription = stringResource(
                    if (comment.liked) R.string.comment_unlike else R.string.comment_like,
                ),
                tint = if (comment.liked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(SmallIconSize),
            )
        }
        Text(
            text = "${comment.likeCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onReply) { Text(stringResource(R.string.comment_reply)) }
        if (canDelete) {
            var confirming by remember { mutableStateOf(false) }
            IconButton(onClick = { confirming = true }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.comment_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(SmallIconSize),
                )
            }
            // 删除不可逆,而这个图标就排在点赞和回复旁边。同一套确认对话框投币和删除缓存
            // 已经在用,标准不一致比没有确认更糟。
            if (confirming) {
                AlertDialog(
                    onDismissRequest = { confirming = false },
                    title = { Text(stringResource(R.string.comment_delete_confirm)) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirming = false
                            onDelete(comment.rpid)
                        }) { Text(stringResource(R.string.action_delete)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirming = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
            }
        }
    }
}

private val SmallIconSize = 16.dp

@Composable
private fun SubReplies(
    comment: CommentItem,
    expanded: ExpandedReplies?,
    collapsed: Boolean,
    myMid: Long?,
    onExpandReplies: (Long) -> Unit,
    onCollapseReplies: (Long) -> Unit,
    onOpenPanel: (Long) -> Unit,
    onReplyTo: (CommentItem) -> Unit,
    onLike: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onUserClick: (Long) -> Unit,
    onOpenLink: (String) -> Unit,
    snackbar: SnackbarHostState,
) {
    // 大楼交给面板,这里只留预览层和一个入口。**expanded 一律不看**:回复发出去之后
    // ViewModel 会给这一楼重新拉一份(见 CommentViewModel.send),不挡住的话那几百条会
    // 落回主列表里就地摊开,正是面板要避免的那件事。
    val usesPanel = comment.subReplyCount >= SubReplyPanelThreshold

    // 此刻摊开着的那一份结果。收起过就当没有,哪怕它还缓存在 ViewModel 里。
    val openThread = expanded?.takeIf { !collapsed && !usesPanel }

    // 已展开就用展开结果(含翻页累加),否则用主楼自带的预览楼层垫着,避免展开前一片空白。
    //
    // **第一页还在飞的时候仍然用预览层**:`expanded` 一被创建就非 null 但 items 是空的,
    // 直接读它会让已经显示着的两三条回复在点下按钮的瞬间消失,只剩一个转圈 —— 看起来像
    // "一点就把内容点没了"。
    val shown = openThread?.items?.takeIf { it.isNotEmpty() } ?: comment.previewReplies
    val remaining = comment.subReplyCount - shown.size
    if (shown.isEmpty() && remaining <= 0) return

    // 这一组的入口只有一个动作:把它打开。走面板还是就地摊开是容器的选择,不是两件事,
    // 所以预览层的每一条、整组末尾那一行、以及它们的读屏标签都指向这同一个 lambda。
    val openThisThread: () -> Unit =
        if (usesPanel) ({ onOpenPanel(comment.rpid) }) else ({ onExpandReplies(comment.rpid) })

    // **一个容器装下整组楼中楼,不是每条一张卡片。** 以前每条各套一个 Surface,
    // 三条回复就是三块圆角色块摞在一起,比主楼本身还抢眼。PiliPlus 的 `replyItemRow`
    // 也是一个容器里排若干行(`lib/pages/video/reply/widgets/reply_item_grpc.dart`)。
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.Tight),
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.Hair)) {
            // 时间序这件事只在摊开之后才有意义:预览层是服务端挑的几条,本来就不成序列。
            if (openThread != null) TimeOrderNotice()
            shown.forEach { sub ->
                // 预览层和摊开之后是两种形态,不是同一种的两个开关位。预览是"这一楼底下在说
                // 什么"的一瞥:没有头像、没有按钮、名字接进正文同一段、两行截断。摊开之后每条
                // 都是一条完整的回复,该有的都有 —— 这正是 PiliPlus 的分法,`replyItemRow`
                // 与 `ReplyItemGrpc(replyLevel: 2)` 两个 widget,不是一个 widget 的两个参数。
                if (openThread == null) {
                    SubReplyPreviewRow(
                        comment = sub,
                        onOpenThread = openThisThread,
                        onSeek = onSeek,
                        onUserClick = onUserClick,
                        onOpenLink = onOpenLink,
                        snackbar = snackbar,
                    )
                } else {
                    SubReplyRow(
                        comment = sub,
                        rootAuthorMid = comment.mid,
                        canDelete = myMid != null && myMid == sub.mid,
                        onReplyTo = onReplyTo,
                        onLike = onLike,
                        onDelete = onDelete,
                        onSeek = onSeek,
                        onUserClick = onUserClick,
                        onOpenLink = onOpenLink,
                        snackbar = snackbar,
                        modifier = Modifier.padding(horizontal = Spacing.Tight),
                    )
                }
            }
            // **每一条出口都要留下痕迹。** 以前失败和"这一页什么都没返回"两种结局都落进
            // `expanded != null && !hasMore && items 为空`,而这个组合在下面一条分支都不匹配 ——
            // 按钮消失、回复没有、错误画在整个评论区的页脚上,看起来就是"点了展开什么都没发生"。
            when {
                // 大楼的入口通向面板,数字报的是总数而不是"还剩多少":面板里是完整的一份,
                // 预览的那两三条也在里面。
                usesPanel -> SubReplyActionRow(
                    text = stringResource(R.string.comment_open_replies_panel, comment.subReplyCount),
                    onClick = openThisThread,
                )

                openThread == null && remaining > 0 -> SubReplyActionRow(
                    text = stringResource(R.string.comment_expand_replies, remaining),
                    onClick = openThisThread,
                )

                openThread != null && openThread.loadingMore -> Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.MinTouchTarget),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingSpinner()
                }

                // 失败就地重试:按钮和它解释的那次点击在同一个容器里。
                openThread?.error != null -> SubReplyActionRow(
                    text = stringResource(R.string.comment_replies_failed) + "  " +
                        stringResource(R.string.action_retry),
                    onClick = { onExpandReplies(comment.rpid) },
                )

                openThread != null && openThread.hasMore -> SubReplyActionRow(
                    text = stringResource(R.string.comment_load_more),
                    onClick = { onExpandReplies(comment.rpid) },
                )

                // 服务端说到头了,但一条都没给出来(计数里含已删除或被折叠的回复时会这样)。
                openThread != null && shown.isEmpty() -> Text(
                    text = stringResource(R.string.comment_no_more_replies),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Tight),
                )
            }
            // 收起单独一行,不和上面那些并排:两个按钮挤在一行里,每个都只剩半幅宽度可点,
            // 而整行可点正是这一轮要修的事。一楼能摊开几百条,收起之前唯一的办法是一路滚过去。
            if (openThread != null && shown.isNotEmpty()) {
                SubReplyActionRow(
                    text = stringResource(R.string.comment_collapse_replies),
                    onClick = { onCollapseReplies(comment.rpid) },
                    emphasized = false,
                )
            }
        }
    }
}

/**
 * 楼中楼预览层的一条:名字接在正文前面,同一段文字流,两行截断,没有头像也没有按钮。
 *
 * **整条点下去是打开这一组,不是回复它。** 预览是一瞥,不是可操作的条目 —— 想说话得先看清
 * 上下文,而这里只有两行。回复和点赞的入口在摊开之后的 [SubReplyRow] 上。
 * PiliPlus 的 `replyItemRow` 也是整条 `InkWell` 通向 `replyReply`。
 */
@Composable
private fun SubReplyPreviewRow(
    comment: CommentItem,
    onOpenThread: () -> Unit,
    onSeek: (Long) -> Unit,
    onUserClick: (Long) -> Unit,
    onOpenLink: (String) -> Unit,
    snackbar: SnackbarHostState,
) {
    val label = stringResource(R.string.comment_view_thread)
    val nameColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 名字靠字重和颜色跟正文分开,不靠 primary。一格预览里三个名字全染成可点色的话,
    // 这一小块会比它所属的主楼还响,而它们在这一层本来就点不动(整条通向的是打开这一组)。
    val prefix = remember(comment.uname, comment.parentRpid, comment.rootRpid, nameColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Medium)) {
                append(comment.uname)
            }
            // **回复对象已经写在正文开头时,名字后面不加冒号。** 那一截"回复 @某某 :"是发送方
            // 拼进 message 的字面文本(见 CommentRepository.postComment),再补一个冒号读出来
            // 就是"甲:回复 @乙 :……"。判据是 parentRpid,不是去正文里认那几个字 ——
            // 正文本来就可能以"回复"两个字开头。那一截自带前导空格,所以这里补空字符串。
            append(if (comment.parentRpid == comment.rootRpid) ": " else "")
        }
    }
    // **这一处的长按挂在整行上,不挂在正文上**,和另外三处不同。原因是这一行本来就可点
    // (点开这一组):正文上再放一个消费型的手势检测器,它作为子节点会先拿到 down 并消费掉,
    // 于是落在文字上的短按再也传不到外面这层 clickable —— 而文字几乎铺满整行,等于把
    // "点开这一组"废掉。`combinedClickable` 把两件事收在同一个节点上,不存在谁先谁后。
    //
    // 触觉反馈由 combinedClickable 自己给,所以这里用的是不带震动的 [rememberCopyAction]。
    val copy = rememberCopyAction(comment.message.trim(), snackbar)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                role = Role.Button,
                onClickLabel = label,
                onClick = onOpenThread,
                onLongClickLabel = stringResource(R.string.comment_copy),
                onLongClick = copy,
            )
            .padding(horizontal = Spacing.Tight, vertical = Spacing.Tight),
    ) {
        CommentText(
            message = comment.message,
            emotes = comment.emotes,
            mentions = comment.mentions,
            style = SubReplyPreviewStyle,
            maxLines = SubReplyPreviewMaxLines,
            prefix = prefix,
            links = comment.links,
            onSeek = onSeek,
            onUserClick = onUserClick,
            onOpenLink = onOpenLink,
        )
    }
}

/**
 * 「按发布时间先后排列」。
 *
 * **是一行说明,不是排序控件。** `x/v2/reply/reply` 的 `sort` 参数服务端忽略 —— 传 0/1/2/3
 * 返回完全相同(notes §1.3),楼中楼只有时间序这一种。主列表顶上那个 [SortBar] 是真能换的,
 * 这里摆一个同样式的控件只会让人以为楼中楼也能换成最热,点下去没有反应。
 */
@Composable
private fun TimeOrderNotice(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.comment_replies_time_ordered),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
    )
}

/**
 * 「展开 N 条回复」这一类整组入口。**整行可点,不是一个靠左的文字按钮。**
 *
 * `TextButton` 的触摸区只有文字那一小截,而它排在一个占满宽度的容器里 —— 右边那一大片空白
 * 看上去同属这一行,点下去却没有反应。占满宽度之后,可点范围和它看起来的范围一致,
 * 48dp 的最小触摸目标(风格指南 §3)也顺带满足了。
 *
 * @param emphasized 这一行通向更多内容时为真,取 `primary`。判据是 §2.7c 那条
 *   「primary 只标此刻能点进去的入口」:展开、查看全部、加载更多都是入口,收起不是。
 */
@Composable
private fun SubReplyActionRow(
    text: String,
    onClick: () -> Unit,
    emphasized: Boolean = true,
    centered: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.Tight, vertical = Spacing.Tight),
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (emphasized) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * 摊开之后的楼中楼一条。**结构和主楼是同一套**:头像、名字、等级、时间与属地、正文、配图、
 * 一行动作(点赞 / 回复 / 删除)。层级由两件事表达,不由"少给几样信息"表达:
 *
 * - **头像尺寸**:主楼 36dp,这里 [SubReplyAvatarSize] 24dp。
 * - **缩进**:这一整组落在主楼正文的左缘([CommentTextInset])之后,又各自让出一个头像宽。
 *
 * 上一版把层级压在"没有头像、没有时间、正文小一号"上,结果是摊开之后的一条回复读起来像
 * 一行日志:说话的人是谁没有面孔,什么时候说的看不到,而点赞和回复根本没有入口。
 * PiliPlus 在这一级用的就是主楼那个 widget(`ReplyItemGrpc(replyLevel: 2)`),
 * 只把缩进和截断换掉。
 *
 * 容器色见外层:M3 把 surface container 这一族定义为"容器填充",`surfaceVariant`
 * 现在主要是给它的 on 色(低强调文字)留位置的,拿它当底在深色主题下会亮出一大截。
 */
@Composable
private fun SubReplyRow(
    comment: CommentItem,
    /** 主楼作者的 mid,用来认出"楼主"。楼主同时是 UP 主时只挂 UP 主那一枚,见下。 */
    rootAuthorMid: Long,
    canDelete: Boolean,
    onReplyTo: (CommentItem) -> Unit,
    onLike: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onUserClick: (Long) -> Unit,
    onOpenLink: (String) -> Unit,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    // **整条不再是一个"回复"按钮。** 那是上一版没有可见入口时的将就:一条看不出能点的
    // 文字,点下去会把输入栏切到回复态,而点赞和去空间在同一块区域里各自还想要自己的落点。
    // 现在回复是这一行末尾一个写着"回复"的按钮,整条因此可以让给里面那几个真正的目标。
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.Tight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        // 头像 24dp,热区往下补到 48dp:这一列的高度由右边整条回复决定,往下扩不挤走任何东西。
        Box(
            modifier = Modifier
                .heightIn(min = Dimens.MinTouchTarget)
                .openSpace(comment, onUserClick),
            contentAlignment = Alignment.TopCenter,
        ) {
            Avatar(url = comment.avatarUrl, size = SubReplyAvatarSize)
        }
        Column(modifier = Modifier.weight(1f)) {
            // 名字与"时间  属地"两行合成一个可点节点,理由同主楼:名字那一行只有 20dp 高,
            // 单独接点击按不中,而这两行说的是同一个人发的同一条回复。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.MinTouchTarget)
                    .openSpace(comment, onUserClick),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = comment.uname,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    LevelBadge(
                        level = comment.level,
                        senior = comment.isSeniorMember,
                        height = Dimens.LevelBadgeHeight,
                    )
                    // 两枚标记互斥。UP 主在自己视频下发的主楼,楼主和 UP 主是同一个人,并排挂
                    // 两枚只是把同一件事说了两遍,而 UP 主是其中信息量更大的那个。
                    when {
                        comment.isUploader -> Tag(
                            stringResource(R.string.comment_tag_up),
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )

                        comment.mid != 0L && comment.mid == rootAuthorMid -> Tag(
                            stringResource(R.string.comment_tag_thread_author),
                            MaterialTheme.colorScheme.secondaryContainer,
                            MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                SubLine(comment)
            }

            Spacer(Modifier.height(Spacing.Hair))
            // 摊开之后不截行:摊开就是用户明确要读这一组,而一条被永久截断的回复在这个页面里
            // 没有第二个地方能读全。
            CommentText(
                message = comment.message,
                emotes = comment.emotes,
                mentions = comment.mentions,
                style = SubReplyBodyStyle,
                links = comment.links,
                onSeek = onSeek,
                onUserClick = onUserClick,
                onOpenLink = onOpenLink,
                // **复制原文,不是渲染出来的样子。** 表情按服务端存的 `[doge]` 进剪贴板,
                // 链接同样按正文里那串字(`av170001`、`https://…`)而不是渲染出来的标题:
                // 粘出去的 `av170001` 对方能打开,粘出去一行视频标题什么都不是。
                //
                // 回复楼中楼时正文开头自带一截"回复 @某某 :",那是发送方写进 message 的
                // 字面文本(见 CommentRepository.postComment),原样留着 —— 它就是这条的正文,
                // 而剥掉它要去猜那一截到底有多长,名字改过就会剥错。
                modifier = Modifier.copyOnLongPress(comment.message.trim(), snackbar),
            )

            // 配图以前整个没画。一条只发了张图的回复因此渲染成一行空白 —— 看起来像接口少给了
            // 内容,而它在主楼那一级一直是好的。PiliPlus 在每一级都画 `ImageGridView`。
            if (comment.pictureUrls.isNotEmpty()) {
                PictureGrid(
                    urls = comment.pictureUrls,
                    onClick = { index -> viewerIndex = index },
                    modifier = Modifier.padding(top = Spacing.Tight),
                )
            }

            // 和主楼共用同一组动作,不另写一份小号的:点赞在两级走的是同一个
            // `x/v2/reply/action`(notes §1.7),回复走同一个 `x/v2/reply/add`,
            // 长得不一样只会让人以为这里的点赞是另一回事。
            CommentActions(
                comment = comment,
                canDelete = canDelete,
                onLike = onLike,
                onDelete = onDelete,
                onReply = { onReplyTo(comment) },
            )
        }
    }

    viewerIndex?.let { index ->
        ImageViewer(
            urls = comment.pictureUrls,
            initialIndex = index,
            onDismiss = { viewerIndex = null },
        )
    }
}

/** 面板占屏高的比例。留出上面那一截是为了看得见底下还是评论区,而不是"进了一个新页面"。 */
private const val PanelHeightFraction = 0.88f

/**
 * 主楼正文在面板顶上最多铺几行。**它在主列表里是完整的**,面板存在的理由是下面那几百条回复;
 * 顶上钉着的这一块要是能长到半屏,回复就没地方了。
 */
private const val PanelRootMaxLines = 10

/**
 * 一楼的完整楼中楼,底部面板形态。超过 [SubReplyPanelThreshold] 条的楼走这条路。
 *
 * **不是一个导航目的地。** 它长在 [CommentSection] 里,页面没有换,返回键由 `ModalBottomSheet`
 * 自己注册的 BackHandler 接管 —— 走导航要么新增一个 NavKey(而 Navigation 3 的 backstack 不去重,
 * 见 `ui/NavBackStackPolicy.kt`),要么在播放页上叠一层,两条都比这里要处理的事多。
 *
 * 数据和内联那条路共用 `CommentViewModel.expandedReplies`:面板只是另一个显示这份结果的地方,
 * 没有第二份分页状态。翻页仍然是 `expandReplies(rootId)`。
 *
 * **回复某一条不关面板**,在面板之上浮一层输入([ReplyInputLayer])。上一版是关掉面板再让
 * 底部那条常驻输入栏接手,而人是在这一组回复里读到某条才想回它的 —— 面板一关,他刚读的那几条
 * 和滚到的位置全没了。
 *
 * 输入层长在**这个 sheet 自己的窗口里**,不是 [CommentSection] 里的兄弟节点:`ModalBottomSheet`
 * 是一个独立的 window,画在活动窗口之上,兄弟节点会被它整个盖住。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubReplyPanel(
    root: CommentItem,
    expanded: ExpandedReplies?,
    myMid: Long?,
    onLoadMore: () -> Unit,
    onLike: (Long) -> Unit,
    /** 面板里此刻在回复哪一条。null 即没有在回复,输入层不出现。 */
    replyTo: Long?,
    replyToName: String?,
    onReplyTo: (CommentItem) -> Unit,
    onCancelReply: () -> Unit,
    inputText: String,
    onInputChange: (String) -> Unit,
    sending: Boolean,
    onSendReply: () -> Unit,
    onDelete: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onUserClick: (Long) -> Unit,
    onOpenLink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 打开就把第一页拉起来。已经有结果的直接用那一份(读过一次、或者刚发完回复被重拉过)。
    val loadFirstPage by rememberUpdatedState(onLoadMore)
    LaunchedEffect(root.rpid) {
        if (expanded == null) loadFirstPage()
    }

    // **面板自己的 snackbar 宿主,不用外面那一份。** `ModalBottomSheet` 自成一个 window,
    // 画在活动窗口之上;评论区那份 host 在它底下,从面板里复制会报到一个看不见的地方。
    val snackbarHostState = remember { SnackbarHostState() }

    val listState = rememberLazyListState()
    val shown = expanded?.items?.takeIf { it.isNotEmpty() } ?: root.previewReplies
    val hasMore = expanded?.hasMore == true
    val loadingMore = expanded?.loadingMore != false // 结果还没建起来的那一瞬也算在读

    // 触底预取,和主列表同一套写法。面板里手动点"加载更多"翻一百条太费事。
    LaunchedEffect(listState, hasMore, loadingMore) {
        snapshotFlow { listState.layoutInfo }
            .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
            .distinctUntilChanged()
            .filter { (lastVisible, total) -> lastVisible != null && lastVisible >= total - 1 - PrefetchThreshold }
            .collect {
                if (hasMore && !loadingMore) onLoadMore()
            }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        // 输入层要浮在这一列之上,所以外面套一个 Box —— Column 里再加一项只会把列表挤短。
        Box(modifier = Modifier.fillMaxHeight(PanelHeightFraction)) {
            Column(modifier = Modifier.fillMaxSize()) {
                PanelRoot(
                    root = root,
                    onLike = onLike,
                    onReply = { onReplyTo(root) },
                    onSeek = onSeek,
                    onUserClick = onUserClick,
                    onOpenLink = onOpenLink,
                    snackbar = snackbarHostState,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TimeOrderNotice(modifier = Modifier.padding(start = Spacing.Comfortable))
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = Spacing.Loose),
                ) {
                    itemsIndexed(shown, key = { _, sub -> sub.rpid }) { index, sub ->
                        // 条与条之间画 inset 分割线,理由同主列表:面板里这一列是 uncontained 的
                        // (M3 lists 页把 gap 留给 contained 列表),而每条占四行以上,只靠留白
                        // 读不出哪里换了人。左端对齐楼中楼头像的后缘,也就是这一条正文的前缘。
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(
                                    start = Spacing.Comfortable + SubReplyAvatarSize + Spacing.Tight,
                                    end = Spacing.Comfortable,
                                ),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        SubReplyRow(
                            comment = sub,
                            rootAuthorMid = root.mid,
                            canDelete = myMid != null && myMid == sub.mid,
                            onReplyTo = onReplyTo,
                            onLike = onLike,
                            onDelete = onDelete,
                            onSeek = onSeek,
                            onUserClick = onUserClick,
                            onOpenLink = onOpenLink,
                            snackbar = snackbarHostState,
                            modifier = Modifier.padding(horizontal = Spacing.Comfortable),
                        )
                    }

                    item(key = "panel-footer") {
                        // 三种结局各有各的出口,理由同内联那条路:失败必须说出来,否则
                        // 看起来就是"翻到这里就没有了"。
                        when {
                            loadingMore -> Box(
                                modifier = Modifier.fillMaxWidth().padding(Spacing.Comfortable),
                                contentAlignment = Alignment.Center,
                            ) {
                                LoadingSpinner()
                            }

                            expanded?.error != null -> SubReplyActionRow(
                                text = stringResource(R.string.comment_replies_failed) + "  " +
                                    stringResource(R.string.action_retry),
                                onClick = onLoadMore,
                                centered = true,
                            )

                            else -> Box(
                                modifier = Modifier.fillMaxWidth().padding(Spacing.Comfortable),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.comment_no_more_replies),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            // 复制回执落在面板自己这一层。排在输入层之前:两者都贴着下缘,而输入层是此刻
            // 正在用的东西,不该被一条一闪而过的回执压住。
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            // 浮在面板内容之上。回复对象一为空就整层退场,面板本身不动 —— 位置和滚动偏移
            // 都在那个没有被重组掉的 LazyColumn 里。
            if (replyTo != null) {
                ReplyInputLayer(
                    targetName = replyToName,
                    text = inputText,
                    onTextChange = onInputChange,
                    sending = sending,
                    onSend = onSendReply,
                    onDismiss = onCancelReply,
                )
            }
        }
    }
}

/**
 * 钉在面板顶上的主楼。**不复用 [CommentRow]**:那一份自己会再画一组楼中楼,而这里正是
 * 那组楼中楼的容器,套下去就是无限层。
 *
 * 删除入口不在这里 —— 主列表里那一行有,而在这个面板里删掉主楼,面板自己就没有了立足处。
 * 配图同理留在主列表:一个九宫格钉在顶上会把回复挤到屏幕外,而它在底下那一屏是完整的。
 */
@Composable
private fun PanelRoot(
    root: CommentItem,
    onLike: (Long) -> Unit,
    onReply: () -> Unit,
    onSeek: (Long) -> Unit,
    onUserClick: (Long) -> Unit,
    onOpenLink: (String) -> Unit,
    snackbar: SnackbarHostState,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.Comfortable, end = Spacing.Cozy, bottom = Spacing.Hair),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = Dimens.MinTouchTarget)
                .openSpace(root, onUserClick),
            contentAlignment = Alignment.TopCenter,
        ) {
            Avatar(url = root.avatarUrl, size = Dimens.AvatarRow)
        }
        Column(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.MinTouchTarget)
                    .openSpace(root, onUserClick),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = root.uname,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    LevelBadge(level = root.level, senior = root.isSeniorMember, height = Dimens.LevelBadgeHeight)
                    if (root.isUploader) {
                        Tag(
                            stringResource(R.string.comment_tag_up),
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                SubLine(root)
            }
            Spacer(Modifier.height(Spacing.Tight))
            CommentText(
                message = root.message,
                emotes = root.emotes,
                mentions = root.mentions,
                style = CommentBodyStyle,
                maxLines = PanelRootMaxLines,
                links = root.links,
                onSeek = onSeek,
                onUserClick = onUserClick,
                onOpenLink = onOpenLink,
                // 这里截了行(见 [PanelRootMaxLines]),但复制的是整条正文 ——
                // 截断是这块地方放不下,不是内容只有这么多。
                modifier = Modifier.copyOnLongPress(root.message.trim(), snackbar),
            )
            CommentActions(
                comment = root,
                canDelete = false,
                onLike = onLike,
                onDelete = {},
                onReply = onReply,
            )
        }
    }
}

/**
 * "UP主""置顶"这类标记。容器色和文字色成对取自同一组 role,不再用 `color.copy(alpha=0.15f)`
 * 兑一个底色 —— 那样兑出来的对比度取决于底下是什么,深色主题里经常糊成一团。
 */
@Composable
private fun Tag(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = Spacing.Hair, vertical = 1.dp),
        )
    }
}

@Composable
private fun CommentInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    replyTarget: String?,
    onCancelReply: () -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            if (replyTarget != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.Comfortable, end = Spacing.Hair)
                        .heightIn(min = Dimens.MinTouchTarget),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.comment_replying_to, replyTarget),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onCancelReply) { Text(stringResource(R.string.action_cancel)) }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.Tight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            if (replyTarget != null) {
                                stringResource(R.string.comment_input_hint_reply, replyTarget)
                            } else {
                                stringResource(R.string.comment_input_hint)
                            },
                        )
                    },
                    maxLines = 4,
                    shape = MaterialTheme.shapes.large,
                )
                FilledIconButton(onClick = onSend, enabled = !sending && text.isNotBlank()) {
                    if (sending) {
                        LoadingSpinner()
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.action_send),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 正文里要特殊处理的三种东西:表情占位符 `[doge]`、@提及、跳转链接。
 * 一次扫描全认出来 —— 分成两遍就得处理"第二遍的匹配落在第一遍的替换里"这种交叉。
 *
 * 表情键的长度设了上限:`[` 到 `]` 之间不限长的话,一句"[这里省略一万字]看看"会被整段
 * 当成一个表情键去查表(查不到,原样显示,但白扫一遍)。B 站的表情名都很短。
 */
internal val RichTokenRegex = Regex("""\[[^\[\]]{1,20}]|@[^\s@]+|https?://\S+|(?<!\d)(?:(\d{1,2}):)?(\d{1,2}):(\d{2})(?!\d)""")

/**
 * 带上这条评论自己的链接 key 之后的扫描器。没有链接时直接复用 [RichTokenRegex],
 * 不为每条评论重新编译一个正则 —— 评论列表滚动时这是热路径,而绝大多数评论没有链接。
 *
 * **key 按长度倒序排在最前面。** 一条评论里 `av2` 和 `av2333` 可以同时出现,正则的交替是
 * 最左最先匹配,短的排在前面就会把长的咬掉一截,剩下 `333` 落在正文里。PiliPlus 拼这个正则
 * 时按 map 原序 join(`reply_item_grpc.dart` 的 `_buildMessage`),没有排序,这一点不照抄。
 *
 * 链接 key 排在 [RichTokenRegex] 之前还有第二个作用:一条整链接(`https://...`)同时命中
 * 这里的 key 和那边的 URL 分支,先匹配到 key 才能拿到标题。
 */
internal fun richTokenRegex(links: Map<String, CommentLink>): Regex {
    if (links.isEmpty()) return RichTokenRegex
    val keys = links.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    return Regex("$keys|${RichTokenRegex.pattern}")
}

/**
 * 一个 @ 能点开:[mid] 是去处,[length] 是这个 token 里属于名字的那一截。
 * 后者存在是因为正则按空白切,"@张三,你看" 会整串落进一个 token,而只有 "@张三" 是人。
 */
internal data class MentionLink(val mid: Long, val length: Int)

/**
 * 把正文里的 @ 和接口给的 [CommentMention] 对上,返回「token 起点 -> 去处」。
 *
 * **不能只按名字匹配**:members 里的 uname 是此刻的昵称,正文留的是发帖当时的昵称,
 * 抽样 11 条有 5 条对不上(全是 "回复 @旧名 :" 这种楼中楼)。所以分两轮:
 *
 * 一、名字能对上的先认领,长名字优先 —— 否则昵称 "abc" 会抢走 "@abcd" 这个 token。
 * 二、剩下的人按出现顺序配剩下的 token,**且只在两边数量相等时才配**。数量不等意味着
 *    正文里有 @ 不属于任何一个人(邮箱、"@一下"),这时按顺序配会把链接接到别人身上,
 *    而一个指向错误用户的链接比不可点更糟。
 */
internal fun resolveMentions(
    tokens: List<MatchResult>,
    mentions: List<CommentMention>,
): Map<Int, MentionLink> {
    if (mentions.isEmpty()) return emptyMap()
    val atTokens = tokens.filter { it.value.startsWith('@') }
    if (atTokens.isEmpty()) return emptyMap()

    val links = mutableMapOf<Int, MentionLink>()
    val unnamed = mutableListOf<CommentMention>()
    for (mention in mentions.sortedByDescending { it.uname.length }) {
        val needle = "@${mention.uname}"
        val hit = atTokens.firstOrNull { it.range.first !in links && it.value.startsWith(needle) }
        if (hit != null) links[hit.range.first] = MentionLink(mention.mid, needle.length) else unnamed += mention
    }

    val free = atTokens.filter { it.range.first !in links }
    if (unnamed.size == free.size) {
        free.forEachIndexed { index, token ->
            links[token.range.first] = MentionLink(unnamed[index].mid, token.value.length)
        }
    }
    return links
}

/**
 * 评论正文。
 *
 * **表情内联回文字流**,不再是"正文里留着 `[doge]` 三个字、底下另起一行摆一排图标"——
 * 那样读者得自己把图标和占位符对应回去,而且表情出现两次时下面那排根本对不上。
 * 走 `InlineTextContent`:占位符在 [AnnotatedString] 里留一个带 id 的空位,
 * 渲染时把图片填进去,换行、对齐、选中都跟着文字走。
 *
 * @提及与链接标成 [LocalMentionColor]。**能配到 mid 的 @ 是链接,配不到的只染色** ——
 * 见 [resolveMentions],不是所有 @ 都能确定指向谁。
 *
 * 站内链接由 `jump_url` 解析出标题和去处(见 CommentRepository.toCommentLink),在这里排在
 * 最前面:同一串 `https://...` 也匹配裸 URL 那一支,落到那边就只剩一串地址。
 */
@Composable
private fun CommentText(
    message: String,
    emotes: Map<String, String>,
    mentions: List<CommentMention>,
    style: androidx.compose.ui.text.TextStyle,
    maxLines: Int = Int.MAX_VALUE,
    /**
     * 接在正文之前、和正文同属一段文字流的一截。楼中楼预览层用它把发言人的名字放在正文前面。
     *
     * 名字单独一个 `Text` 的话,一条三个字的回复也要占掉两行 —— 而预览层拢共只有两三行的
     * 位置。接进同一段之后,名字和正文一起折行、一起按 [maxLines] 截断。
     */
    prefix: AnnotatedString? = null,
    /** 正文里被服务端标成链接的那几段,见 [dev.bilby.data.CommentLink]。 */
    links: Map<String, CommentLink> = emptyMap(),
    onSeek: (Long) -> Unit = {},
    onUserClick: (Long) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val mention = LocalMentionColor.current
    val used = remember(message, emotes) { linkedMapOf<String, String>() }

    // 时间戳走 LinkAnnotation 而不是自己接 pointerInput:点击命中、按压反馈和无障碍(读屏会把
    // 它读成链接)都由文本层负责。手写那版要把 TextLayoutResult 存进 State 再当 pointerInput
    // 的 key,于是每次布局都要撤销重建一次手势检测器——评论列表滚动时那是热路径。
    //
    // 回调用 rememberUpdatedState 取最新的一份:注解串是 remember 出来的,直接捕获会把第一次
    // 组合时的 onSeek 焊死在里面,而它捕获着当时的 MediaController。
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnUserClick by rememberUpdatedState(onUserClick)
    val currentOnOpenLink by rememberUpdatedState(onOpenLink)
    val text = remember(message, emotes, mentions, mention, prefix, links) {
        used.clear()
        val tokens = richTokenRegex(links).findAll(message).toList()
        val mentionLinks = resolveMentions(tokens, mentions)
        buildAnnotatedString {
            prefix?.let { append(it) }
            var last = 0
            for (match in tokens) {
                append(message.substring(last, match.range.first))
                val token = match.value
                val siteLink = links[token]
                val emoteUrl = emotes[token]
                val timestampMillis = if (emoteUrl == null) parseTimestampMillis(token) else null
                val mentionLink = mentionLinks[match.range.first]
                when {
                    // 链接排在最前面。一条 `https://...` 同时是这里的 key 和下面的 URL 分支,
                    // 而只有这一支拿得到标题 —— 落到 URL 分支就又是一串裸地址。
                    siteLink != null -> withLink(
                        LinkAnnotation.Clickable(
                            tag = "site-link",
                            styles = TextLinkStyles(style = SpanStyle(color = mention)),
                        ) { currentOnOpenLink(siteLink.url) },
                    ) {
                        // 图标用本地矢量,不拉接口给的 `prefix_icon`。视频链接那张图全站是同一
                        // 张,拉它等于每条评论多一次图床请求;更实际的问题是行内占位符要先定死
                        // 宽高,而那张图的比例接口不给 —— 猜错就是一张被压扁的图标。
                        //
                        // 不传 alternateText,用它默认的替换字符。**传空串是不行的**:
                        // 占位符的注解盖的就是这段文字,长度为 0 就等于没有占位符,图标不画。
                        appendInlineContent(LinkIconId)
                        append(siteLink.title)
                    }

                    emoteUrl != null -> {
                        used[token] = emoteUrl
                        appendInlineContent(token, token)
                    }

                    timestampMillis != null -> withLink(
                        LinkAnnotation.Clickable(
                            tag = "timestamp",
                            styles = TextLinkStyles(
                                style = SpanStyle(color = mention, textDecoration = TextDecoration.Underline),
                            ),
                        ) { currentOnSeek(timestampMillis) },
                    ) { append(token) }

                    mentionLink != null -> {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "mention",
                                styles = TextLinkStyles(style = SpanStyle(color = mention)),
                            ) { currentOnUserClick(mentionLink.mid) },
                        ) { append(token.take(mentionLink.length)) }
                        // 名字后面粘着的标点回归正文色:"@张三,你看" 里只有前半截是人。
                        append(token.drop(mentionLink.length))
                    }

                    else -> withStyle(SpanStyle(color = mention)) { append(token) }
                }
                last = match.range.last + 1
            }
            append(message.substring(last))
        }
    }

    // 尺寸与动态、专栏共用一处判断:同一个表情在三页里该是同一个大小,而三处正文字号不同。
    // 楼中楼的行高比主楼矮一档,那一档里表情跟着收 —— 收的判断也在 inlineEmoteSize 里。
    val emoteSize = inlineEmoteSize(style)
    val inline = used.mapValues { (_, url) ->
        InlineTextContent(
            Placeholder(emoteSize, emoteSize, PlaceholderVerticalAlign.TextCenter),
        ) {
            BiliAsyncImage(url = url, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    } + if (links.isEmpty()) {
        emptyMap()
    } else {
        // 图标跟着字号走,不跟着表情走:它是这段文字的一部分,比正文大一圈会把行撑开。
        val iconSize = style.fontSize
        mapOf(
            LinkIconId to InlineTextContent(
                Placeholder(iconSize, iconSize, PlaceholderVerticalAlign.TextCenter),
            ) {
                Icon(
                    imageVector = Icons.Outlined.OndemandVideo,
                    contentDescription = null,
                    tint = mention,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )
    }

    Text(
        text = text,
        style = style,
        inlineContent = inline,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * 站内链接前那枚图标的 inline content id。**全评论区一个 id 就够**:图标只有一种,
 * 而 `InlineTextContent` 的 id 只需要在同一段 [AnnotatedString] 里区分不同的占位符 ——
 * 表情要一个 token 一个 id 是因为每个表情的图不一样。
 */
private const val LinkIconId = "site-link-icon"

private fun parseTimestampMillis(token: String): Long? {
    val parts = token.split(':').mapNotNull { it.toLongOrNull() }
    if (parts.size != token.count { it == ':' } + 1) return null
    val seconds = when (parts.size) {
        2 -> {
            if (parts[1] >= 60) return null
            parts[0] * 60 + parts[1]
        }
        3 -> {
            if (parts[1] >= 60 || parts[2] >= 60) return null
            parts[0] * 3600 + parts[1] * 60 + parts[2]
        }
        else -> return null
    }
    return seconds * 1000L
}

// ---- Preview ----

// 显式写返回类型:它在 previewReplies 里递归调用自己,不写会让类型推断绕不出来。
//
// **递归靠 [nested] 收口。** 无条件递归一层的写法在 @Preview 里是直接 StackOverflow:
// 每一层的楼中楼又各自造一层楼中楼,没有底。
private fun previewComment(
    rpid: Long,
    uname: String,
    message: String,
    likes: Int,
    isUp: Boolean = false,
    level: Int = 4,
    nested: Boolean = true,
): CommentItem = CommentItem(
    rpid = rpid,
    rootRpid = rpid,
    // mid 与 rpid 是两回事(一个是人,一个是这条评论),预览里给个不撞号的假值,
    // 免得下一个人照着这里以为它们可以混用 —— 真实映射在 CommentRepository 里取 member.mid。
    mid = 900_000L + rpid,
    uname = uname,
    avatarUrl = "",
    isUploader = isUp,
    ipLocation = "IP属地：广东",
    level = level,
    isSeniorMember = false,
    ctimeEpochSeconds = Instant.now().epochSecond - 3600,
    likeCount = likes,
    liked = false,
    message = message,
    emotes = emptyMap(),
    mentions = emptyList(),
    links = emptyMap(),
    pictureUrls = emptyList(),
    subReplyCount = if (nested) 2 else 0,
    // 楼中楼要挂回这一楼:rootRpid/parentRpid 都指向主楼,才是"直接回复主楼"那一种,
    // 名字后面因此带冒号(见 SubReplyRow)。
    previewReplies = if (!nested) {
        emptyList()
    } else {
        listOf(
            previewComment(rpid * 100 + 1, "路人甲", "楼上说得对 @楼主", 3, nested = false)
                .copy(rootRpid = rpid, parentRpid = rpid),
        )
    },
)

@Preview(showBackground = true, name = "评论区")
@Composable
private fun CommentSectionPreview() {
    BilbyTheme {
        CommentSection(
            state = CommentUiState(
                myMid = 1L,
                topComment = previewComment(1L, "UP主置顶", "感谢支持,下期见 https://b23.tv/xxx", 200, isUp = true),
                items = listOf(
                    previewComment(2L, "热心网友", "这期讲得很清楚,做了笔记", 88),
                    previewComment(3L, "路人乙", "催更催更", 5),
                ),
                hasMore = false,
            ),
            onSort = {},
            onRefresh = {},
            onLoadMore = {},
            onExpandReplies = {},
            onSend = { _, _ -> },
            onLike = {},
            onDelete = {},
            modifier = Modifier.height(600.dp),
        )
    }
}
