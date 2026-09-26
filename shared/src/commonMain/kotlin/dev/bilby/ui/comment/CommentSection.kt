package dev.bilby.ui.comment

import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.PaneOverlay
import dev.bilby.ui.components.ComposerPanel
import dev.bilby.ui.components.PaneSheet
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.FloatingActionButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import dev.bilby.ui.components.PrefetchNearEnd
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import dev.bilby.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilby.resources.*
import dev.bilby.data.CommentItem
import dev.bilby.data.CommentLink
import dev.bilby.data.CommentMention
import dev.bilby.data.CommentSort
import dev.bilby.data.parseCommentSpans
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.BiliRichText
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.ImageViewer
import dev.bilby.ui.components.LevelBadge
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.LoadingSpinner
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.components.SelectableTextDialog
import dev.bilby.ui.components.SortRow
import dev.bilby.ui.components.rememberLoadingVisible
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import java.time.Instant

/**
 * 可嵌进播放页的评论区(DESIGN 2.3)。不是整页:自带 LazyColumn 提供滚动,但不假设自己
 * 独占屏幕,调用方通过 [modifier] 给出高度约束。
 *
 * **写评论从两处进:右下角的 FAB 评论这条视频,单击一条评论回复这个人。** 写在一张 sheet 里
 * ([ComposerPanel]),不是屏幕底部常驻的一条输入栏。常驻那一条试过三版(描边框、圆角
 * 填充框、docked toolbar),三版的共同问题是:它一直占着屏幕底部一条,而绝大多数时候人是在读;
 * 要回复谁还得先让它拿到焦点、再想办法让它放掉焦点。PiliPlus 的做法相同:评论区一个常驻的
 * FAB,写评论是一个从底部升起的面板(`pages/video/reply/view.dart` 的 `fab`、
 * `common/reply_controller.dart` 的 `onReply`)。M3 的 FAB 页把它定为"the primary action on a
 * screen",并且 "FABs remain in place on scroll",所以它不随滚动藏起来。
 *
 * **单击一条评论就是回复它**,主楼、楼中楼、详情面板里的每一条都是;长按是选中正文。
 *
 * **楼中楼一律完整显示**。回复比主楼自带的那几条多的楼,末尾一行「查看全部 N 条回复」打开
 * 详情面板([CommentThreadSheet]),那里是读一整组回复的唯一地方。
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
    /** 点了评论里的时间戳。**没有可跳的视频时传 null**,那时时间戳画成普通文字。 */
    onSeek: ((Long) -> Unit)? = null,
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
    val listState = rememberLazyListState()

    // **snackbar 的宿主在评论区自己身上**,不从导航层穿进来 —— 同一条判断见 `FavFolderScreen`
    // 那处注释。这里还多一层理由:评论区是播放页 tab 里的一块,它上面那半屏是播放器,
    // 把回执报到整页底部会盖在播放控件上。
    //
    // 详情面板另有一份自己的([CommentThreadSheet]),不共用这个:单栏时它是 `ModalBottomSheet`,
    // 自成一个 window 画在活动窗口之上,这一份在它底下,从面板里复制会往一个看不见的地方报;
    // 两栏时它盖住整个评论区,这一份同样被盖在底下。
    val snackbarHostState = remember { SnackbarHostState() }

    /**
     * 此刻要选中哪一段文字。**挂在列表之外这一层**,不挂在行里 —— 挂在行里的话,面板一打开
     * 那一行还在滚动区里,一被 `LazyColumn` 回收面板就跟着消失。
     */
    var selectionTarget by remember { mutableStateOf<String?>(null) }

    /**
     * 正在写给谁。null 是没在写;[CommentToVideo] 是评论这条视频;其余是被回复那条的 rpid。
     * 编辑面板只有这一张,主列表和详情面板都往这里写。
     */
    var composing by rememberSaveable { mutableStateOf<Long?>(null) }

    /**
     * 草稿**按回复对象各存一份**,照 PiliPlus 的 `savedReplies[key]`。写了一半关掉面板去看别的,
     * 回来点同一个人,字还在;点另一个人则是另一份,不会把写给甲的半句话带到乙那里。
     */
    val drafts = rememberSaveable(saver = DraftsSaver) { mutableStateMapOf<Long, String>() }

    /** 最近一次按下发送时写给的是谁。成功回执回来时按它清掉那一份草稿。 */
    var sentTarget by rememberSaveable { mutableStateOf<Long?>(null) }

    // 面板正在读哪一楼。整个面板长在这个 composable 里,不进导航栈:它是评论区内部的一层,
    // 页面本身没有换,返回键由面板自己接管。
    var panelRoot by rememberSaveable { mutableStateOf<Long?>(null) }
    val panelComment = panelRoot?.let { id -> findRoot(state, id) }

    // **草稿只在发出去之后才清。** 失败时草稿留在框里,原因就在编辑面板上一行。
    //
    // 成功与失败在 ViewModel 的协程里分道,界面读不到那个分支,只能读它报的成功计数
    // ([CommentUiState.sentCount])。**判据是"这个数变大了",不是"和记着的那个不一样"。**
    // 进程重建之后 ViewModel 是新的,计数从 0 起,而 rememberSaveable 恢复出来的是重建之前
    // 那个数 —— 按"不一样"判会在回到这一页的第一帧把刚恢复的草稿清掉。
    var seenSentCount by rememberSaveable { mutableIntStateOf(state.sentCount) }
    LaunchedEffect(state.sentCount) {
        if (state.sentCount <= seenSentCount) {
            seenSentCount = state.sentCount
            return@LaunchedEffect
        }
        seenSentCount = state.sentCount
        sentTarget?.let { drafts.remove(it) }
        sentTarget = null
        composing = null
    }

    // 面板开着的时候主楼被刷掉了(下拉刷新之后它不在第一页了),把面板一起关掉:
    // 留着一个找不到主楼的面板,里面的回复没有任何上下文。
    LaunchedEffect(panelRoot, panelComment == null) {
        if (panelRoot != null && panelComment == null) panelRoot = null
    }

    PrefetchNearEnd(
        listState,
        canLoad = state.hasMore && !state.appending && state.error == null,
        onLoadMore = onLoadMore,
    )

    val rowActions = CommentRowActions(
        myMid = state.myMid,
        onReply = { comment -> composing = comment.rpid },
        onLike = onLike,
        onDelete = onDelete,
        onSeek = onSeek,
        onUserClick = onUserClick,
        onOpenLink = onOpenLink,
        onSelectText = { selectionTarget = it },
    )

    /**
     * 写评论的面板,画在此刻最上面那个窗口里:楼中楼详情开着时画在那张 sheet 里面(它自成一个
     * 窗口,画在外面会被盖住),否则画在评论区上面。见 [ComposerPanel]。
     */
    val composer: (@Composable () -> Unit)? = composing?.let { target ->
        {
            val draft = drafts[target].orEmpty()
            val replyName = if (target == CommentToVideo) null else findUname(state, target)
            ComposerPanel(
                title = replyName?.let { stringResource(Res.string.comment_replying_to, it) }
                    ?: stringResource(Res.string.comment_write),
                text = draft,
                onTextChange = { drafts[target] = it },
                placeholder = stringResource(Res.string.comment_input_hint),
                sending = state.sending,
                error = state.sendError?.let { stringResource(Res.string.comment_send_failed, it) },
                counter = commentDraftCounter(draft.length),
                onSend = {
                    sentTarget = target
                    onSend(drafts[target].orEmpty(), target.takeIf { it != CommentToVideo })
                },
                // 关掉不丢草稿:它还在 [drafts] 里,下次写给同一个人时原样回来。
                onDismiss = { composing = null },
            )
        }
    }

    Box(modifier = modifier) {
        // **[refreshEnabled] 为假时整个手势不接管。** 嵌套滚动从内往外传,而播放器那个收起
        // 页头的连接挂在这一整块的祖先上(见 VideoScreen),这里的刷新框离列表更近:不设这道
        // 闸的话,列表到顶后剩下的下滑量会先被刷新吃掉,播放器再也展不开 —— 空间页正是这么
        // 坏掉的。用手写的 `pullToRefresh` 而不是 `PullToRefreshBox`,只因为后者不给这个开关。
        val pullState = rememberPullToRefreshState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pullToRefresh(
                    isRefreshing = state.refreshing,
                    state = pullState,
                    // 鼠标不能拉,理由见 RefreshBox。
                    enabled = refreshEnabled && LocalPointerSource.current.isTouchLike,
                    onRefresh = onRefresh,
                ),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // 底部留出 FAB 与导航栏:列表铺到屏幕底边(手势条下面照样是列表),最后一条
                // 能滚到 FAB 上面。
                // 顶上不再留白:第一项是排序栏,它为了 48dp 的触摸区自己已经在字的上方空出十几 dp,
                // 再加这里的 8dp 和播放页标签行的下边距,标签与"最热"之间空出一整行。
                contentPadding = PaddingValues(bottom = fabClearance()),
            ) {
                header?.let { item(key = "header") { it() } }
                item(key = "sort-bar") { SortBar(state.sort, onSort) }

                // **条与条之间只留白,不画线。** 上一版画的是 inset 分割线,真机上一屏七八根细线
                // 把列表切成一格一格,读起来像表格。M3 divider 页另有一句 "List items with
                // repetitive formats may not require an inset divider, in which using only the
                // margin between items is acceptable",评论正是重复版式,而每条开头的头像本身
                // 就标出了"换了一个人"。条间距由 [CommentRow] 的上下内边距给。
                state.topComment?.let { top ->
                    item(key = "top-${top.rpid}") {
                        CommentRow(
                            comment = top,
                            actions = rowActions,
                            pinned = true,
                            modifier = Modifier.animateItem(),
                        ) {
                            SubReplies(
                                root = top,
                                expanded = state.expandedReplies[top.rpid],
                                actions = rowActions,
                                onOpenThread = { panelRoot = top.rpid },
                            )
                        }
                    }
                }

                // 出错时不叠空态:一次失败不等于"这条视频没人评论",两句话一起出现只会互相拆台。
                if (!state.loading && state.error == null && state.items.isEmpty() && state.topComment == null) {
                    item(key = "empty") { EmptyState(stringResource(Res.string.comment_empty)) }
                }

                items(state.items, key = { comment -> comment.rpid }) { comment ->
                    CommentRow(
                        comment = comment,
                        actions = rowActions,
                        modifier = Modifier.animateItem(),
                    ) {
                        SubReplies(
                            root = comment,
                            expanded = state.expandedReplies[comment.rpid],
                            actions = rowActions,
                            onOpenThread = { panelRoot = comment.rpid },
                        )
                    }
                }

                item(key = "footer") {
                    if (state.loading) {
                        // 位置照占,指示器等够 200ms 才出现(见 [rememberLoadingVisible])——评论
                        // 常常一开口就到,画了也只够抽一下。位置留着是为了那一下不把列表顶一跳。
                        Box(Modifier.fillMaxWidth().padding(Spacing.Comfortable), Alignment.Center) {
                            if (rememberLoadingVisible()) LoadingSpinner()
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
            // 只在 Android 12L 及以下会出现,见 SelectableTextDialog。
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
            )
        }
        WriteFab(
            contentDescription = stringResource(Res.string.comment_write),
            onClick = { composing = CommentToVideo },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
        selectionTarget?.let { target ->
            SelectableTextDialog(
                text = target,
                snackbar = snackbarHostState,
                onDismiss = { selectionTarget = null },
            )
        }
        // 挂到窗口最上面:遮罩要盖住画面和标签行,点哪里都算不写了。
        if (panelComment == null) composer?.let { PaneOverlay(onDismiss = { composing = null }, content = it) }
    }

    // 详情面板挂在外面:单栏时它是 ModalBottomSheet,自己就是一个 window;两栏时它画在右栏的面板层。
    if (panelComment != null) {
        CommentThreadSheet(
            root = panelComment,
            expanded = state.expandedReplies[panelComment.rpid],
            myMid = state.myMid,
            onLoadMore = { onExpandReplies(panelComment.rpid) },
            onLike = onLike,
            onCompose = { rpid -> composing = rpid },
            composer = composer,
            onDismissComposer = { composing = null },
            onDelete = onDelete,
            onSeek = onSeek,
            // **跳走之前先关面板。** 面板自己注册了一个 BackHandler(预测式
            // 返回要用),它在组合树里比导航那一层更靠后,于是先接住返回。留着面板跳到空间页
            // 或浏览器落地页之后,这一页仍在栈里、面板仍在组合中,新页的第一次返回被它吃掉 ——
            // 表现是"返回键没反应",而实际上是在关一个看不见的面板。
            onUserClick = { mid ->
                panelRoot = null
                onUserClick(mid)
            },
            onOpenLink = { url ->
                panelRoot = null
                onOpenLink(url)
            },
            onDismiss = { panelRoot = null },
        )
    }

}

/** [composing] 里表示"评论这条视频"的那个值。rpid 从 1 起,0 不会和哪条评论撞上。 */
private const val CommentToVideo = 0L

/** 草稿表过 Bundle:摊成 [key, 正文, key, 正文…]。 */
internal val DraftsSaver = listSaver<SnapshotStateMap<Long, String>, String>(
    save = { map -> map.flatMap { (key, text) -> listOf(key.toString(), text) } },
    restore = { flat ->
        mutableStateMapOf<Long, String>().apply {
            flat.chunked(2).forEach { (key, text) -> key.toLongOrNull()?.let { put(it, text) } }
        }
    },
)

/**
 * 写评论的 FAB。贴右下角,躲开导航栏;外边距 16dp 是 M3 FAB 的默认位置。
 * 图标用铅笔(写),不用回复箭头:点它是写一条新评论,不是回复谁。
 */
@Composable
private fun WriteFab(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier
            .navigationBarsPadding()
            .padding(Spacing.Comfortable),
    ) {
        Icon(Icons.Outlined.Edit, contentDescription = contentDescription)
    }
}

/** 列表底部要留的高度:FAB 56dp 加上下两份 16dp 外边距,再加导航栏。 */
@Composable
private fun fabClearance(): Dp =
    FabSize + Spacing.Comfortable * 2 +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

private val FabSize = 56.dp

/**
 * 一条评论能做的几件事。主列表、楼中楼、详情面板三处共用同一份,不在每一层把七个回调
 * 逐个往下传。
 */
internal class CommentRowActions(
    /** 非空且与某条评论的 mid 相同时,那一条可删除。 */
    val myMid: Long?,
    val onReply: (CommentItem) -> Unit,
    val onLike: (Long) -> Unit,
    val onDelete: (Long) -> Unit,
    val onSeek: ((Long) -> Unit)?,
    val onUserClick: (Long) -> Unit,
    val onOpenLink: (String) -> Unit,
    /** 长按正文要选中它,由所在那一层弹面板。 */
    val onSelectText: (String) -> Unit,
) {
    fun canDelete(comment: CommentItem): Boolean = myMid != null && myMid == comment.mid
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
        CommentSort.HOT to Res.string.comment_sort_hot,
        CommentSort.TIME to Res.string.comment_sort_time,
    )
    // 左右只垫 Tight:下拉按钮自带内边距,再垫一档页边距,▾ 就离右缘比正文远一截。
    SortRow(
        options = options,
        selected = sort,
        onSelect = onSort,
        modifier = Modifier.padding(horizontal = Spacing.Tight),
    )
}

/**
 * 整条可点:单击回复,长按选中正文(弹 [SelectableTextDialog],整条复制在面板底部)。
 *
 * **长按挂在整条上,不挂在正文上。** 上一版在正文上单独挂了一个 `detectTapGestures` 做长按,
 * 它作为子节点先拿到 down 并消费掉,落在文字上的单击就再也传不到外面这层 —— 而文字几乎铺满
 * 整条,等于把"单击回复"废掉。`combinedClickable` 把两件事收在同一个节点上,触觉反馈也由它
 * 自己给。
 *
 * **正文里的链接、@ 与时间戳不受影响,这一点查过实现。** Compose 把 `LinkAnnotation` 渲染成盖在
 * 链接范围上的子 `Box`,各自带 `combinedClickable`(foundation 的
 * `TextLinkScope.LinksComposables`),指针事件在 Main 这一趟子先于父,点在链接上由链接接住。
 * 代价是长按正好压在链接上不会弹选择面板,旁边随便哪里都能长按。头像、名字、点赞同理。
 *
 * 选中的是原文,不是渲染出来的样子:表情按服务端存的 `[doge]`,链接按正文里那串字
 * (`av170001`、`https://…`)—— 粘出去的 `av170001` 对方能打开,一行视频标题什么都不是。
 */
@Composable
private fun Modifier.replyOnClick(comment: CommentItem, actions: CommentRowActions): Modifier =
    combinedClickable(
        role = Role.Button,
        onClickLabel = stringResource(Res.string.comment_reply),
        onClick = { actions.onReply(comment) },
        onLongClickLabel = stringResource(Res.string.text_select_title),
        onLongClick = { actions.onSelectText(comment.message.trim()) },
    )

/**
 * 一条主楼,或详情面板里的一条回复(那里回复是主角,和主楼同一个排法)。
 *
 * @param threadAuthorMid 所在那一楼的楼主。非 0 时,这个人的回复挂「楼主」标记。
 * @param replies 排在底行之后的楼中楼。详情面板里不传。
 */
@Composable
private fun CommentRow(
    comment: CommentItem,
    actions: CommentRowActions,
    modifier: Modifier = Modifier,
    pinned: Boolean = false,
    threadAuthorMid: Long = 0L,
    replies: (@Composable () -> Unit)? = null,
) {
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    // 上 12、下 4:底行按钮的触摸区比图标高出 8dp,视觉上已经留了一截,再加 4 与下一条的
    // 12 合起来,两条评论之间是 24dp 左右的空白 —— 这就是没有分割线之后的分隔。
    //
    // 内边距在点击之后:涟漪铺满整条,贴着屏幕两边,而不是缩在内容那一块里。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .replyOnClick(comment, actions)
            .padding(start = Spacing.Comfortable, end = Spacing.Comfortable, top = Spacing.Cozy, bottom = Spacing.Hair),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
    ) {
        // 头像本身仍是 36dp,热区往下补到 48dp:这一列的高度由右边那一整条评论决定,
        // 往下扩不挤走任何东西。对齐取 TopCenter 而不是默认的居中,否则头像会跟着下沉 6dp。
        Box(
            modifier = Modifier
                .heightIn(min = Dimens.MinTouchTarget)
                .openSpace(comment, actions.onUserClick),
            contentAlignment = Alignment.TopCenter,
        ) {
            Avatar(url = comment.avatarUrl, size = Dimens.AvatarRow)
        }
        Column(modifier = Modifier.weight(1f)) {
            // **头部只有名字这一行。** 时间与属地在正文下面,和点赞排成一行(见 [CommentFooter])。
            // 名字那一行只有 16dp 高,单独按不中;去空间的主要落点是左边的头像(热区补到 48dp),
            // 名字是附带的第二个落点。
            NameLine(comment = comment, modifier = Modifier.openSpace(comment, actions.onUserClick)) {
                CommentTags(comment, pinned = pinned, threadAuthorMid = threadAuthorMid)
            }
            Spacer(Modifier.height(Spacing.Hair))
            CommentText(
                message = comment.message,
                emotes = comment.emotes,
                mentions = comment.mentions,
                style = CommentBodyStyle,
                links = comment.links,
                onSeek = actions.onSeek,
                onUserClick = actions.onUserClick,
                onOpenLink = actions.onOpenLink,
            )
            if (comment.pictureUrls.isNotEmpty()) {
                PictureGrid(
                    urls = comment.pictureUrls,
                    onClick = { index -> viewerIndex = index },
                    modifier = Modifier.padding(top = Spacing.Tight),
                )
            }
            CommentFooter(comment = comment, actions = actions, endInset = RootFooterEndInset)
            replies?.invoke()
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
    val label = stringResource(Res.string.comment_open_space, comment.uname)
    return clickable(
        enabled = comment.mid != 0L,
        onClickLabel = label,
        role = Role.Button,
    ) { onUserClick(comment.mid) }
}

/**
 * 名字、等级,以及调用方给的标记。**用户名低一档强调,不用满对比度**:一屏几十条评论,真正要读的
 * 是正文;名字和正文一样重的话,视线会被每条开头的名字拽住。低强调的文字角色是
 * `onSurfaceVariant`,`outline` 是描边角色,浅色主题下只有 4.3:1。
 */
@Composable
private fun NameLine(
    comment: CommentItem,
    modifier: Modifier = Modifier,
    tags: @Composable () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
        modifier = modifier,
    ) {
        Text(
            text = comment.uname,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        LevelBadge(level = comment.level, senior = comment.isSeniorMember, height = Dimens.LevelBadgeHeight)
        tags()
    }
}

/**
 * 「UP 主」「楼主」「置顶」。前两枚互斥:UP 主在自己视频下发的楼,楼主和 UP 主是同一个人,
 * 并排挂两枚只是把同一件事说了两遍,而 UP 主是其中信息量更大的那个。
 */
@Composable
private fun CommentTags(comment: CommentItem, pinned: Boolean, threadAuthorMid: Long) {
    when {
        comment.isUploader -> Tag(
            stringResource(Res.string.comment_tag_up),
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )

        threadAuthorMid != 0L && comment.mid == threadAuthorMid -> Tag(
            stringResource(Res.string.comment_tag_thread_author),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
    if (pinned) {
        Tag(
            stringResource(Res.string.comment_tag_pinned),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/** "3 小时前  IP属地:广东"。 */
@Composable
private fun commentMeta(comment: CommentItem): String =
    listOf(
        formatRelativeTime(comment.ctimeEpochSeconds),
        comment.ipRegion.takeIf { it.isNotEmpty() }?.let { stringResource(Res.string.comment_ip_location, it) }.orEmpty(),
    )
        .filter { it.isNotBlank() }
        .joinToString(MetaSeparator)

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
 * 楼中楼正文。**字号和主楼一样是 14sp,只把行高收一档(24 → 22)。** 一条回复和一条评论是
 * 同一种东西,读者读到楼中楼时并没有换一副眼镜;层级由头像尺寸、缩进与底色承担。PiliPlus 在
 * 楼中楼那一级同样是 14 号字(`reply_item_grpc.dart` 的 `_buildContent`)。
 */
private val SubReplyBodyStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp)

private val GridSpacing = 4.dp

/**
 * 评论配图。张数决定列数,照 PiliPlus 的
 * `common/widgets/image_grid/image_grid_builder.dart`:1 张单独放大,2 与 4 张走两列,
 * 其余走三列;格子是正方形,超出的部分裁掉,**点开才看全**。
 *
 * 单张那格用 4:3 而不是按原图比例,是因为接口层的 `ReplyPictureDto` 只解析了 `img_src`,
 * 没有 `img_width`/`img_height` —— 拿不到原始比例就没法像 PiliPlus 那样按比例定尺寸。
 */
@Composable
private fun PictureGrid(urls: List<String>, onClick: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (urls.size == 1) {
        BiliAsyncImage(
            url = urls[0],
            contentDescription = stringResource(Res.string.comment_picture),
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
                        contentDescription = stringResource(Res.string.comment_picture),
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
 * 正文下面那一行:左边时间与属地,右边点赞与删除。主楼、楼中楼、面板里的回复共用。
 *
 * **时间打头,不是按钮打头。** 这一行从按钮开始的话,图标带着按钮自己的内边距,比正文左缘
 * 缩进一截;时间是文字,天然贴着正文左缘。点赞落在行尾,和 PiliPlus 一样
 * (`reply_item_grpc.dart` 的 `buttonAction`)。
 *
 * **没有回复按钮**:单击整条就是回复(见 [replyOnClick])。
 *
 * 按钮取 XS 档,**视觉 32、触摸 48**:material 组件自己补足 48dp 的触摸区(icon-buttons 页
 * "Extra small and small icon buttons must have a target size of 48x48dp or larger"),
 * 这一行因此仍是 48dp 高,多出来的上下各 8dp 正好是它和正文、和下一条之间的留白。
 */
@Composable
private fun CommentFooter(
    comment: CommentItem,
    actions: CommentRowActions,
    modifier: Modifier = Modifier,
    /** 右端再往里收多少。主楼传 [RootFooterEndInset],见那里。 */
    endInset: Dp = 0.dp,
) {
    Row(
        modifier = modifier
            .tuckUp(FooterTuck)
            .fillMaxWidth()
            .padding(end = endInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = commentMeta(comment),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(
            modifier = Modifier.offset(x = FooterTrailingNudge),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LikeButton(comment = comment, onLike = actions.onLike)
            if (actions.canDelete(comment)) DeleteButton(onDelete = { actions.onDelete(comment.rpid) })
        }
    }
}

/**
 * 点赞:图标 + 计数,一个按钮。计数原先是按钮旁边一段不可点的字,按在数字上什么都不发生。
 * 零赞不写 0,只留图标 —— 一列评论里满是 "0" 只是噪声。
 *
 * 已赞换实心图标并取 `primary`,不只是变色(icon-buttons 页对 toggle 的要求:未选中描边、
 * 选中实心)。
 */
@Composable
private fun LikeButton(comment: CommentItem, onLike: (Long) -> Unit) {
    val tint = if (comment.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    TextButton(
        onClick = { onLike(comment.rpid) },
        modifier = Modifier.heightIn(min = ButtonDefaults.ExtraSmallContainerHeight),
        contentPadding = PaddingValues(horizontal = Spacing.Tight),
        colors = ButtonDefaults.textButtonColors(contentColor = tint),
    ) {
        Icon(
            imageVector = if (comment.liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
            contentDescription = stringResource(if (comment.liked) Res.string.comment_unlike else Res.string.comment_like),
            modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
        )
        if (comment.likeCount > 0) {
            Spacer(Modifier.width(ButtonDefaults.ExtraSmallIconSpacing))
            Text(text = "${comment.likeCount}", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 删除不可逆,而这个图标就排在点赞旁边。同一套确认对话框投币和删除缓存已经在用。 */
@Composable
private fun DeleteButton(onDelete: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    IconButton(onClick = { confirming = true }, modifier = Modifier.size(IconButtonDefaults.extraSmallContainerSize())) {
        Icon(
            Icons.Outlined.Delete,
            contentDescription = stringResource(Res.string.comment_delete),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconButtonDefaults.extraSmallIconSize),
        )
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(Res.string.comment_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onDelete()
                }) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

/**
 * 主楼的点赞往里收一截,和它底下楼中楼的点赞落在同一条竖线上。楼中楼的正文右缘比主楼的窄了
 * 容器的内边距(12dp),两级各自贴着自己的右缘时,点赞一上一下错开这么多,一屏看下来是锯齿。
 * 对齐到楼中楼那一条而不是反过来:楼中楼的点赞再往右就贴到容器边上了。
 */
private val RootFooterEndInset = Spacing.Cozy

/**
 * 尾部按钮右移这一截,图标的右缘才对得上页边距:按钮的内边距是透明的,不挪的话点赞图标
 * 比上面正文的右缘缩进一个内边距。
 */
private val FooterTrailingNudge = Spacing.Tight

/**
 * 底行往上压进正文的高度。这一行的高度是点赞按钮 48dp 的触摸区撑的,图标只有 32dp,正文与
 * "时间  属地"之间因此空出一大截。压上去的这 8dp 落在正文最后一行字形下方的行距里,不盖字;
 * 按钮的触摸区照旧是 48dp。
 */
private val FooterTuck = Spacing.Tight

/**
 * 往上压 [amount]:照常测量,对外报的高度少这一截,摆的时候上移同样多,于是盖住上一个兄弟的
 * 底边。不用 `offset`:它只挪画的位置不挪量出来的尺寸,上移之后底下会空出同样高的一条。
 */
private fun Modifier.tuckUp(amount: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val tuck = amount.roundToPx().coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - tuck) { placeable.place(0, -tuck) }
}

/**
 * 一楼的楼中楼,**一律完整显示**:头像、名字与标记、正文、配图、时间与点赞,单击回复这一条。
 *
 * 上一版有一个预览态:两行截断、没有时间和按钮,点一下才长成完整的样子。这一下的代价是每条
 * 回复都要点两次才能回它,而它换来的只是两三条回复各省一行。
 *
 * 显示哪几条:这一楼**已经拿全**时显示全部 —— 主楼自带的几条就是全部,或者刚发完回复、
 * ViewModel 把这一楼重新拉了一份完整的(见 CommentViewModel.send)。没拿全就显示主楼自带的
 * 那几条,末尾一行「查看全部 N 条回复」打开详情面板。**主列表里不翻页**:一楼能有几百条,
 * 在这里就地摊开会把后面的主楼推出好几屏,而收起之前唯一的办法是一路滚过去。
 *
 * **一个容器装下整组,不是每条一张卡片。** 每条各套一个 Surface 的话,三条回复就是三块圆角
 * 色块摞在一起,比主楼本身还抢眼。PiliPlus 的 `replyItemRow` 也是一个容器里排若干行。
 */
@Composable
private fun SubReplies(
    root: CommentItem,
    expanded: ExpandedReplies?,
    actions: CommentRowActions,
    onOpenThread: () -> Unit,
) {
    // 「拿全」只认小楼。详情面板和主列表共用同一份展开结果,面板一路翻到最后一页之后这一楼也是
    // 「拿全」的 —— 不设上限的话,关掉面板时几百条回复就地摊在主列表里,正是上面说的那种情况。
    val complete = expanded
        ?.takeIf { !it.hasMore && !it.loadingMore && it.items.isNotEmpty() && it.items.size <= InlineThreadLimit }
        ?.items
    val shown = complete ?: root.previewReplies
    val hasMore = complete == null && root.subReplyCount > root.previewReplies.size
    if (shown.isEmpty() && !hasMore) return

    // 圆角取 medium 12dp,与容器内边距 12dp 相配(optical roundness:外圆角 − 内边距 ≈ 内层
    // 圆角,里面最大的元素是圆头像)。
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.Tight),
    ) {
        Column(modifier = Modifier.padding(top = Spacing.Tight, bottom = if (hasMore) 0.dp else Spacing.Hair)) {
            shown.forEach { sub ->
                // **按 rpid 给每条一个稳定身份。** 重新拉过的那一份和主楼自带的几条是两个列表
                // 对象,开头几条是同一批回复;不给 key 的话一旦有条被删或者服务端换了顺序,
                // 位置就错开,正文和头像会串到别人身上。
                key(sub.rpid) {
                    SubReplyRow(
                        comment = sub,
                        rootAuthorMid = root.mid,
                        actions = actions,
                    )
                }
            }
            if (hasMore) {
                ViewAllRow(
                    text = stringResource(Res.string.comment_open_replies_panel, root.subReplyCount),
                    onClick = onOpenThread,
                )
            }
        }
    }
}

/**
 * 楼中楼里的一条。**结构和主楼是同一套**:头像、名字与标记、正文、配图、底行。层级只由三样
 * 表达:24dp 的小头像([Dimens.AvatarNested])、缩在主楼正文左缘之后、以及容器的底色。
 */
@Composable
private fun SubReplyRow(
    comment: CommentItem,
    /** 主楼作者的 mid,用来认出"楼主"。 */
    rootAuthorMid: Long,
    actions: CommentRowActions,
) {
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    // 左右 12dp、上 4dp:容器顶上另有 8dp,底行按钮的触摸区又带着 8dp 的留白,
    // 条与条之间因此是 12dp 左右。上一版左右只有 8dp,文字贴着容器边。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .replyOnClick(comment, actions)
            .padding(start = Spacing.Cozy, end = Spacing.Cozy, top = Spacing.Hair),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = Dimens.MinTouchTarget)
                .openSpace(comment, actions.onUserClick),
            contentAlignment = Alignment.TopCenter,
        ) {
            Avatar(url = comment.avatarUrl, size = Dimens.AvatarNested)
        }
        Column(modifier = Modifier.weight(1f)) {
            NameLine(comment = comment, modifier = Modifier.openSpace(comment, actions.onUserClick)) {
                CommentTags(comment, pinned = false, threadAuthorMid = rootAuthorMid)
            }
            Spacer(Modifier.height(Spacing.Hair))
            // 回复楼中楼时正文开头自带一截"回复 @某某 :",那是发送方写进 message 的字面文本
            // (见 CommentRepository.postComment),原样留着 —— 它就是这条的正文,而剥掉它要去猜
            // 那一截到底有多长,名字改过就会剥错。
            CommentText(
                message = comment.message,
                emotes = comment.emotes,
                mentions = comment.mentions,
                style = SubReplyBodyStyle,
                links = comment.links,
                onSeek = actions.onSeek,
                onUserClick = actions.onUserClick,
                onOpenLink = actions.onOpenLink,
            )
            // 配图在每一级都画。一条只发了张图的回复不画的话渲染成一行空白,看起来像接口少给了
            // 内容。PiliPlus 在每一级都画 `ImageGridView`。
            if (comment.pictureUrls.isNotEmpty()) {
                PictureGrid(
                    urls = comment.pictureUrls,
                    onClick = { index -> viewerIndex = index },
                    modifier = Modifier.padding(top = Spacing.Tight),
                )
            }
            // 和主楼共用同一行,不另写一份小号的:点赞在两级走的是同一个 `x/v2/reply/action`
            // (notes §1.7),长得不一样只会让人以为这里的点赞是另一回事。
            CommentFooter(comment = comment, actions = actions)
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
 * 「查看全部 N 条回复」。**整行可点,不是一个靠左的文字按钮**:它排在一个占满宽度的容器里,
 * 右边那一大片空白看上去同属这一行,点下去却没有反应的话读起来就是坏了。48dp 的最小触摸
 * 目标(风格指南 §3)也顺带满足。
 *
 * 字的左缘对齐楼中楼正文,不对齐头像:它说的是"还有多少条回复",属于上面那几条的延续。
 * `labelMedium` 取 `primary`:一屏好几个楼都有这一行,`labelLarge` 的粗体比评论正文还醒目;
 * 颜色仍是 primary,判据是 §2.7c 那条「primary 只标此刻能点进去的入口」。
 */
@Composable
private fun ViewAllRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = SubReplyTextInset, end = Spacing.Cozy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 主列表里一楼最多就地摊开几条。服务端随主楼附带三条预览,再加上刚发的一两条回复;
 * 比这多的楼只在详情面板里读。
 */
private const val InlineThreadLimit = 5

/** 楼中楼正文在容器里的左缘:容器内边距 + 头像 + 头像与文字的间距。 */
private val SubReplyTextInset = Spacing.Cozy + Dimens.AvatarNested + Spacing.Tight

/** 面板占屏高的比例。留出上面那一截是为了看得见底下还是评论区,而不是"进了一个新页面"。 */
private const val ThreadSheetHeightFraction = 0.9f

/**
 * 一楼的完整回复,底部面板形态。「查看全部 N 条回复」打开它。
 *
 * **主楼是列表的第一条,跟着一起滚。** 上一版把主楼钉在面板顶上(最多十行)、下面画一条线,
 * 一条长主楼能占掉面板的三分之一,回复只剩下半截;而人打开面板是来读回复的,主楼读过了。
 * 回复和主楼同一个排法(36dp 头像、同样的底行):在这里回复是主角,不再是缩在容器里的附属。
 *
 * 写回复和主列表是同一套:单击哪一条就回复哪一条,点主楼就是回复这一楼。**这里不放 FAB**:
 * 面板是来读和回这一组回复的,主楼就排在第一条,再挂一个"回复这一楼"的按钮是同一件事的
 * 第二个入口。单栏时编辑面板画在这张 sheet 里面(见 [ComposerPanel])。
 *
 * **不是一个导航目的地。** 它长在 [CommentSection] 里,页面没有换,返回键由面板
 * 自己注册的 BackHandler 接管([PaneSheet])。数据和主列表共用 `CommentViewModel.expandedReplies`,翻页仍然是
 * `expandReplies(rootId)`,没有第二份分页状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentThreadSheet(
    root: CommentItem,
    expanded: ExpandedReplies?,
    myMid: Long?,
    onLoadMore: () -> Unit,
    onLike: (Long) -> Unit,
    /** 写一条回复给这个 rpid。回复这一楼本身传的是主楼的 rpid。 */
    onCompose: (Long) -> Unit,
    /** 正在写时的编辑面板,画在这张 sheet 里面(它自成一个窗口)。 */
    composer: (@Composable () -> Unit)?,
    /** 关掉编辑面板。两栏时点左栏的画面要能关它(见 SidePaneDismissLayer)。 */
    onDismissComposer: () -> Unit,
    onDelete: (Long) -> Unit,
    onSeek: ((Long) -> Unit)?,
    onUserClick: (Long) -> Unit,
    onOpenLink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 打开就把第一页拉起来。已经有结果的直接用那一份(读过一次、或者刚发完回复被重拉过)。
    val loadFirstPage by rememberUpdatedState(onLoadMore)
    LaunchedEffect(root.rpid) {
        if (expanded == null) loadFirstPage()
    }

    // **面板自己的 snackbar 宿主,不用外面那一份**,理由见 [CommentSection] 里那处。
    val snackbarHostState = remember { SnackbarHostState() }
    var selectionTarget by remember { mutableStateOf<String?>(null) }

    val actions = CommentRowActions(
        myMid = myMid,
        onReply = { comment -> onCompose(comment.rpid) },
        onLike = onLike,
        onDelete = onDelete,
        onSeek = onSeek,
        onUserClick = onUserClick,
        onOpenLink = onOpenLink,
        onSelectText = { selectionTarget = it },
    )

    val listState = rememberLazyListState()
    val shown = expanded?.items?.takeIf { it.isNotEmpty() } ?: root.previewReplies
    val hasMore = expanded?.hasMore == true
    val loadingMore = expanded?.loadingMore != false // 结果还没建起来的那一瞬也算在读

    // 触底预取,和主列表同一个组件。面板里手动点"加载更多"翻一百条太费事。
    PrefetchNearEnd(
        listState,
        canLoad = hasMore && !loadingMore && expanded?.error == null,
        onLoadMore = onLoadMore,
    )

    // 跳过半开:一组回复就是要往下读的,停在半开只是多一次上拉。
    PaneSheet(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.comment_thread_title),
    ) {
        val inPane = inPane
        Box(modifier = Modifier.bodyHeight(ThreadSheetHeightFraction)) {
            CommentThreadList(
                root = root,
                replies = shown,
                loadingMore = loadingMore,
                failed = expanded?.error != null,
                actions = actions,
                onRetry = onLoadMore,
                listState = listState,
                modifier = Modifier.fillMaxSize(),
            )
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            // sheet 自成一个窗口,编辑面板只能画在它里面;画在右栏时同主列表,挂到右栏最上面。
            if (inPane) composer?.let { PaneOverlay(onDismiss = onDismissComposer, content = it) } else composer?.invoke()
        }
        selectionTarget?.let { target ->
            SelectableTextDialog(
                text = target,
                snackbar = snackbarHostState,
                onDismiss = { selectionTarget = null },
            )
        }
    }
}

/**
 * 一楼的完整列表:根评论、"N 条回复"那一行、每条回复、底部的三种结局。**详情面板
 * ([CommentThreadSheet])与评论详情页([CommentThreadScreen])共用这一份**,两处长得一样,
 * 一处改了另一处不会漂走。翻页与预取归调用方:面板的数据在 CommentViewModel 的展开状态里,
 * 详情页另有自己的 ViewModel。
 *
 * @param highlightRpid 此刻要高亮的那一条,null 即不高亮。调用方负责在一会儿之后把它清掉,
 *   高亮随之淡出(见 [flashBackground])。
 * @param failed 最近一次续页失败。底部给一个重试,不说"没有更多了"。
 */
@Composable
internal fun CommentThreadList(
    root: CommentItem,
    replies: List<CommentItem>,
    loadingMore: Boolean,
    failed: Boolean,
    actions: CommentRowActions,
    onRetry: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    highlightRpid: Long? = null,
    contentPadding: PaddingValues = PaddingValues(bottom = Spacing.Tight),
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        item(key = "root") {
            CommentRow(
                comment = root,
                actions = actions,
                modifier = Modifier.flashBackground(highlightRpid == root.rpid),
            )
        }
        item(key = "replies-header") {
            // 主楼与回复之间的分界。不画线:这里要说的是"下面是几条回复",
            // 一句话比一根线说得清楚。
            Text(
                text = stringResource(Res.string.comment_thread_replies, root.subReplyCount),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(
                    start = Spacing.Comfortable,
                    end = Spacing.Comfortable,
                    top = Spacing.Tight,
                    bottom = Spacing.Hair,
                ),
            )
        }
        items(replies, key = { sub -> sub.rpid }) { sub ->
            CommentRow(
                comment = sub,
                actions = actions,
                threadAuthorMid = root.mid,
                modifier = Modifier
                    .animateItem()
                    .flashBackground(highlightRpid == sub.rpid),
            )
        }
        item(key = "thread-footer") {
            // 三种结局各有各的出口:失败必须说出来,否则看起来就是"翻到这里就没有了"。
            when {
                loadingMore -> Box(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.Comfortable),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingSpinner()
                }

                failed -> Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(onClick = onRetry) {
                        Text(
                            stringResource(Res.string.comment_replies_failed) + "  " +
                                stringResource(Res.string.action_retry),
                        )
                    }
                }

                else -> Box(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.Comfortable),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(Res.string.comment_no_more_replies),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 被定位的那一条铺一层底色,[active] 变回 false 时淡出。
 *
 * 用 secondaryContainer 整块铺底,不是描边或加粗:要回答的是"刚才点的是哪一条",一块底色
 * 在一屏评论里扫一眼就找得到,而它淡掉之后这一条和别的回复长得一样,不留一个常驻的标记。
 * 透明度只在淡入淡出的过程里用,停住时是 0 或 1,不是兑出来的一个常驻底色(风格指南 §3)。
 */
@Composable
private fun Modifier.flashBackground(active: Boolean): Modifier {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(active) {
        alpha.animateTo(
            targetValue = if (active) 1f else 0f,
            animationSpec = tween(if (active) FlashInMillis else FlashOutMillis),
        )
    }
    val color = MaterialTheme.colorScheme.secondaryContainer
    return drawBehind { drawRect(color.copy(alpha = alpha.value)) }
}

private const val FlashInMillis = 200
private const val FlashOutMillis = 900

/**
 * "UP 主""楼主""置顶"这类标记。容器色和文字色成对取自同一组 role,不再用
 * `color.copy(alpha=0.15f)` 兑一个底色 —— 那样兑出来的对比度取决于底下是什么。
 *
 * **和等级徽章差不多高**:它们排在同一行、同样是名字的附注。上一版用 `labelSmall` 11sp 加上下
 * 内边距,比等级徽章高出一截,排在楼中楼 12sp 的名字后面几乎和名字一样重。字号取 9sp,
 * 同 PiliPlus 的 `PBadge(size: small, fontSize: 9)`。
 */
@Composable
private fun Tag(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = TagTextSize, lineHeight = TagTextSize),
            modifier = Modifier.padding(horizontal = TagHorizontalPadding, vertical = TagVerticalPadding),
        )
    }
}

private val TagTextSize = 9.sp
private val TagHorizontalPadding = 3.dp
private val TagVerticalPadding = 1.dp

/**
 * 评论正文。**解析在 `data/CommentRichText.kt`,渲染在 `ui/components/BiliRichText.kt`**,
 * 这里只把评论区特有的几个参数摊平送过去。
 *
 * 留着这层薄壳而不是让各调用点各自 parse 一遍,是因为解析结果要 `remember` 住:评论列表
 * 滚动时重组很频繁,而一条正文的 token 扫描和 @ 归属只跟这几个入参有关,和滚动无关。
 */
@Composable
private fun CommentText(
    message: String,
    emotes: Map<String, String>,
    mentions: List<CommentMention>,
    style: androidx.compose.ui.text.TextStyle,
    maxLines: Int = Int.MAX_VALUE,
    /** 正文里被服务端标成链接的那几段,见 [dev.bilby.data.CommentLink]。 */
    links: Map<String, CommentLink> = emptyMap(),
    onSeek: ((Long) -> Unit)? = null,
    onUserClick: (Long) -> Unit = {},
    onOpenLink: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spans = remember(message, emotes, mentions, links) {
        parseCommentSpans(message, emotes, links, mentions)
    }
    BiliRichText(
        spans = spans,
        style = style,
        onLinkClick = onOpenLink,
        onMentionClick = onUserClick,
        onSeek = onSeek,
        maxLines = maxLines,
        modifier = modifier,
    )
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
    ipRegion = "广东",
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
