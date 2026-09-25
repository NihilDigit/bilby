package dev.bilby.ui.comment

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.api.BiliResult
import dev.bilby.appendDistinctBy
import dev.bilby.data.CommentItem
import dev.bilby.data.CommentRepository
import dev.bilby.data.SettingsStore
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.ComposerPanel
import dev.bilby.ui.components.FirstScreenState
import dev.bilby.ui.components.ListSkeleton
import dev.bilby.ui.components.PersonRowSkeleton
import dev.bilby.ui.components.SelectableTextDialog
import dev.bilby.ui.components.WindowOverlay
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.errorTextRes
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.padScaffoldExceptBottom
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 评论详情页。从消息中心和带评论定位的链接进来,"追回原评论":看到有人回了一句,点进来要看
 * 的是那一句在整楼里的上下文,不是那条视频的整个评论区。
 *
 * 列表与播放页里的楼中楼面板共用 [CommentThreadList]。写回复同样是单击哪一条就回复哪一条,
 * 没有 FAB,理由同那张面板。
 *
 * @param onOpenSubject 打开评论所在的视频、动态或专栏。认不出所在内容时为 null,顶栏不放这个入口。
 */
@Composable
fun CommentThreadRoute(
    repository: CommentRepository,
    settings: SettingsStore,
    oid: Long,
    type: Int,
    rootRpid: Long,
    targetRpid: Long,
    onOpenSubject: (() -> Unit)?,
    onUserClick: (Long) -> Unit,
    onOpenLink: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: CommentThreadViewModel = viewModel(
        factory = viewModelFactory {
            initializer { CommentThreadViewModel(repository, settings, oid, type, rootRpid, targetRpid) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    CommentThreadScreen(
        state = state,
        onRetry = vm::load,
        onLoadMore = vm::loadMore,
        onLike = vm::like,
        onDelete = vm::delete,
        onSend = vm::send,
        onOpenSubject = onOpenSubject,
        onUserClick = onUserClick,
        onOpenLink = onOpenLink,
        onBack = onBack,
    )
}

@Composable
private fun CommentThreadScreen(
    state: CommentThreadUiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onLike: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onSend: (text: String, replyTo: Long) -> Unit,
    onOpenSubject: (() -> Unit)?,
    onUserClick: (Long) -> Unit,
    onOpenLink: (String) -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var selectionTarget by remember { mutableStateOf<String?>(null) }
    var composing by rememberSaveable { mutableStateOf<Long?>(null) }
    val drafts = rememberSaveable(saver = DraftsSaver) { mutableStateMapOf<Long, String>() }
    var sentTarget by rememberSaveable { mutableStateOf<Long?>(null) }

    // 草稿只在发出去之后才清,判据同 CommentSection:成功计数变大了。
    var seenSentCount by rememberSaveable { mutableIntStateOf(state.sentCount) }
    LaunchedEffect(state.sentCount) {
        if (state.sentCount > seenSentCount) {
            sentTarget?.let { drafts.remove(it) }
            sentTarget = null
            composing = null
        }
        seenSentCount = state.sentCount
    }

    // 根评论被自己删掉了,这一楼就不存在了,留在这一页只剩一串没有上下文的回复。
    LaunchedEffect(state.rootDeleted) {
        if (state.rootDeleted) onBack()
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(
                title = stringResource(R.string.comment_thread_title),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            ) {
                // 评论是冲着某个视频或动态写的,从消息点进来的人常常还没看过那一条。
                onOpenSubject?.let { open ->
                    IconButton(onClick = open) {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = stringResource(R.string.comment_thread_open_subject),
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState, Modifier.navigationBarsPadding()) },
    ) { insets ->
        val root = state.root
        FirstScreenState(
            loading = state.loading,
            error = state.error?.let { stringResource(it) },
            isEmpty = root == null,
            onRetry = onRetry,
            modifier = Modifier.padScaffoldExceptBottom(insets),
            skeleton = { ListSkeleton(row = { PersonRowSkeleton(avatarSize = Dimens.AvatarRow) }) },
        ) {
            if (root != null) {
                ThreadContent(
                    state = state,
                    root = root,
                    onLoadMore = onLoadMore,
                    actions = CommentRowActions(
                        myMid = state.myMid,
                        onReply = { comment -> composing = comment.rpid },
                        onLike = onLike,
                        onDelete = onDelete,
                        onSeek = null,
                        onUserClick = onUserClick,
                        onOpenLink = onOpenLink,
                        onSelectText = { selectionTarget = it },
                    ),
                )
            }
        }
    }

    composing?.let { target ->
        WindowOverlay {
            val draft = drafts[target].orEmpty()
            val name = state.find(target)?.uname
            ComposerPanel(
                title = name?.let { stringResource(R.string.comment_replying_to, it) }
                    ?: stringResource(R.string.comment_write),
                text = draft,
                onTextChange = { drafts[target] = it },
                placeholder = stringResource(R.string.comment_input_hint),
                sending = state.sending,
                error = state.sendError?.let { stringResource(R.string.comment_send_failed, it) },
                counter = commentDraftCounter(draft.length),
                onSend = {
                    sentTarget = target
                    onSend(drafts[target].orEmpty(), target)
                },
                onDismiss = { composing = null },
            )
        }
    }
    selectionTarget?.let { target ->
        SelectableTextDialog(
            text = target,
            snackbar = snackbarHostState,
            onDismiss = { selectionTarget = null },
        )
    }
    val missing = stringResource(R.string.comment_thread_target_missing)
    LaunchedEffect(state.targetMissing) {
        if (state.targetMissing) snackbarHostState.showSnackbar(missing)
    }
}

@Composable
private fun ThreadContent(
    state: CommentThreadUiState,
    root: CommentItem,
    onLoadMore: () -> Unit,
    actions: CommentRowActions,
) {
    val listState = rememberLazyListState()
    var highlight by remember { mutableStateOf<Long?>(null) }

    // 定位只做一次。**记在 rememberSaveable 里**:从这一页点进视频再返回时整页重新组合,
    // 不记的话又被拽回那一条,人刚往下读到的位置就丢了。
    var located by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.locatedRpid) {
        val target = state.locatedRpid ?: return@LaunchedEffect
        if (located) return@LaunchedEffect
        located = true
        val index = if (target == root.rpid) 0 else state.replies.indexOfFirst { it.rpid == target } + ThreadHeaderRows
        if (index >= 0) listState.scrollToItem(index)
        highlight = target
        delay(HighlightHoldMillis)
        highlight = null
    }

    // 触底预取,写法同楼中楼面板。
    val currentLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
            .distinctUntilChanged()
            .filter { (last, total) -> last != null && last >= total - 1 - ThreadPrefetch }
            .collect { currentLoadMore() }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        CommentThreadList(
            root = root,
            replies = state.replies,
            loadingMore = state.loadingMore,
            failed = state.moreFailed,
            actions = actions,
            onRetry = onLoadMore,
            listState = listState,
            highlightRpid = highlight,
            contentPadding = PaddingValues(bottom = Spacing.Tight + navigationBarsBottom()),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 列表里排在第一条回复之前的行:根评论、"N 条回复"那一行。 */
private const val ThreadHeaderRows = 2

/** 高亮停多久再淡出。够找到它,又不至于一直占着那一条。 */
private const val HighlightHoldMillis = 1500L

private const val ThreadPrefetch = 5

data class CommentThreadUiState(
    val root: CommentItem? = null,
    val replies: List<CommentItem> = emptyList(),
    val loading: Boolean = true,
    /** 首屏失败那一句,资源 id,见 [dev.bilby.ui.errorTextRes]。 */
    @StringRes val error: Int? = null,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
    val hasMore: Boolean = false,
    val myMid: Long? = null,
    /** 定位到的那一条。首屏读完之后才有值,界面据此滚过去并高亮一下。 */
    val locatedRpid: Long? = null,
    /** 翻了 [MaxLocatePages] 页还没找到要定位的那一条,多半已被删除。 */
    val targetMissing: Boolean = false,
    val rootDeleted: Boolean = false,
    val sending: Boolean = false,
    /** 发送失败的原因,服务端原话;理由同 CommentUiState.sendError。 */
    val sendError: String? = null,
    val sentCount: Int = 0,
) {
    fun find(rpid: Long): CommentItem? = root?.takeIf { it.rpid == rpid } ?: replies.find { it.rpid == rpid }
}

/**
 * 翻多少页去找要定位的那一条。`x/v2/reply/reply` 只能按页码从头翻(notes/comment-toview-history.md
 * §1.3),PiliPlus 走的是 gRPC 的 DetailList,能按 rpid 直接定位,HTTP 这边没有对应物。十页是
 * 两百条回复,绝大多数楼翻不到这么深;更深的那种先停下来,不为一次定位连发十几个请求。
 */
private const val MaxLocatePages = 10

/**
 * 评论详情页的数据。楼中楼接口 `x/v2/reply/reply` 同时回根评论(`data.root`)和按时间排的回复,
 * 一个接口就够。
 */
class CommentThreadViewModel(
    private val repository: CommentRepository,
    private val settings: SettingsStore,
    private val oid: Long,
    private val type: Int,
    private val rootRpid: Long,
    private val targetRpid: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(CommentThreadUiState())
    val state: StateFlow<CommentThreadUiState> = _state.asStateFlow()

    /** 已经取到的最后一页的页码。发送成功后重取这一页,新回复通常就落在里面。 */
    private var lastPage = 0

    init {
        viewModelScope.launch {
            val mid = settings.credentials.first().dedeUserId.toLongOrNull()
            _state.update { it.copy(myMid = mid) }
        }
        load()
    }

    /** 首屏:取第一页,要定位的那一条不在里面就接着往下翻,直到找到、到底或到 [MaxLocatePages]。 */
    fun load() {
        _state.update { CommentThreadUiState(myMid = it.myMid, loading = true, sentCount = it.sentCount) }
        lastPage = 0
        viewModelScope.launch {
            var page = 1
            while (true) {
                when (val result = repository.loadSubReplies(oid, rootRpid, page, type)) {
                    is BiliResult.Ok -> {
                        val sub = result.value
                        lastPage = page
                        _state.update {
                            it.copy(
                                root = sub.root ?: it.root,
                                replies = it.replies.appendDistinctBy(sub.items) { c -> c.rpid },
                                hasMore = sub.hasMore,
                            )
                        }
                        val current = _state.value
                        val found = targetRpid == rootRpid || current.replies.any { it.rpid == targetRpid }
                        val next = sub.nextPage
                        if (found || next == null || page >= MaxLocatePages) {
                            _state.update {
                                it.copy(
                                    loading = false,
                                    // 找不到就不高亮任何一条:高亮根评论会被读成"就是这一条"。
                                    locatedRpid = if (found) targetRpid else null,
                                    targetMissing = !found,
                                    // 一楼的根评论接口没给(被删、被折叠),整页就没有可显示的上下文。
                                    error = if (it.root == null) R.string.error_refused else null,
                                )
                            }
                            return@launch
                        }
                        page = next
                    }

                    else -> {
                        val error = result.errorTextRes("评论详情 $oid/$rootRpid")
                        _state.update { it.copy(loading = false, error = error) }
                        return@launch
                    }
                }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.loadingMore || !current.hasMore) return
        fetchPage(lastPage + 1)
    }

    private fun fetchPage(page: Int) {
        _state.update { it.copy(loadingMore = true, moreFailed = false) }
        viewModelScope.launch {
            when (val result = repository.loadSubReplies(oid, rootRpid, page, type)) {
                is BiliResult.Ok -> {
                    val sub = result.value
                    lastPage = maxOf(lastPage, page)
                    _state.update {
                        it.copy(
                            root = sub.root ?: it.root,
                            replies = it.replies.appendDistinctBy(sub.items) { c -> c.rpid },
                            hasMore = sub.hasMore,
                            loadingMore = false,
                        )
                    }
                }

                else -> {
                    result.errorTextRes("评论详情续页 $oid/$rootRpid")
                    _state.update { it.copy(loadingMore = false, moreFailed = true) }
                }
            }
        }
    }

    /**
     * 回复一条。成功后重取最后一页按 rpid 合并,不整楼重拉:回复按时间排,新的一条落在末尾;
     * 整楼重拉会丢掉滚动位置和已经翻出来的那几页(同 CommentViewModel.send 的那处说明)。
     */
    fun send(text: String, replyTo: Long) {
        if (text.isBlank() || _state.value.sending) return
        val target = _state.value.find(replyTo)
        _state.update { it.copy(sending = true, sendError = null) }
        viewModelScope.launch {
            when (val result = repository.postComment(oid, text, target, type)) {
                is BiliResult.Ok -> {
                    _state.update { it.copy(sending = false, sentCount = it.sentCount + 1) }
                    fetchPage(maxOf(lastPage, 1))
                }

                is BiliResult.ApiError -> {
                    BiliLog.w("评论详情发送失败(${result.code}): ${result.message}")
                    _state.update { it.copy(sending = false, sendError = "${result.message}(${result.code})") }
                }

                is BiliResult.Failure -> {
                    BiliLog.w("评论详情发送异常", result.cause)
                    _state.update { it.copy(sending = false, sendError = result.cause.message.orEmpty()) }
                }
            }
        }
    }

    /** 乐观更新,失败退回。不重取:见 CLAUDE.md 乐观更新一条。 */
    fun like(rpid: Long) {
        val comment = _state.value.find(rpid) ?: return
        val next = !comment.liked
        apply(rpid) { it.copy(liked = next, likeCount = it.likeCount + if (next) 1 else -1) }
        viewModelScope.launch {
            val result = repository.likeComment(oid, rpid, next, type)
            if (result !is BiliResult.Ok) {
                result.errorTextRes("评论详情点赞 $rpid")
                apply(rpid) { it.copy(liked = !next, likeCount = it.likeCount + if (next) -1 else 1) }
            }
        }
    }

    /** 删自己的一条。等服务端确认之后才从列表里拿掉,失败时什么都没变过,不需要放回。 */
    fun delete(rpid: Long) {
        viewModelScope.launch {
            when (val result = repository.deleteComment(oid, rpid, type)) {
                is BiliResult.Ok -> _state.update {
                    if (rpid == rootRpid) it.copy(rootDeleted = true) else it.copy(replies = it.replies.filterNot { c -> c.rpid == rpid })
                }

                else -> result.errorTextRes("评论详情删除 $rpid")
            }
        }
    }

    private fun apply(rpid: Long, transform: (CommentItem) -> CommentItem) {
        _state.update { current ->
            current.copy(
                root = current.root?.let { if (it.rpid == rpid) transform(it) else it },
                replies = current.replies.map { if (it.rpid == rpid) transform(it) else it },
            )
        }
    }
}
