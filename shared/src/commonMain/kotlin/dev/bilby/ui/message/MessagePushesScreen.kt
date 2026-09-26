package dev.bilby.ui.message

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.api.BiliResult
import dev.bilby.data.MessageRepository
import dev.bilby.data.WhisperSession
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.PersonRowSkeleton
import dev.bilby.ui.components.RefreshAction
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.errorTextRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「UP 主推送」子页:会话列表里最后一条是 UP 主投稿推送的那些会话,收起规则见
 * [WhisperSession.lastIsUpPush] 与 MessageScreen 的 foldUpPushes。
 *
 * **自己从头翻会话列表**,不接会话列表那一页读到的那几页:从那边带过来的话,这里只能看到
 * 那边已经翻到的部分,而推送会话恰恰常常排在后面。
 */
@Composable
fun MessagePushesRoute(
    repository: MessageRepository,
    /** 宽窗口右栏正开着的那段对话,列表里高亮它。分栏见 BilbyApp 的 listDetailStrategy。 */
    selectedTalker: Long?,
    /**
     * 右栏此刻并排显示着。这时第一页一到就把第一个会话开在右栏:推送会话
     * 读的是"最近推了什么",人进来要看的多半就是最上面那一条,而右栏空着只是一块骨架。
     * 窄窗口不开,开了就是一进页面整页被对话盖住。
     */
    autoOpenFirst: Boolean,
    /** 此刻还在等第一个会话自动打开没有。右栏空着时据此转圈还是说一句,见 BilbyApp。 */
    onAwaitingFirstChange: (Boolean) -> Unit,
    onOpenWhisper: (WhisperSession) -> Unit,
    onBack: () -> Unit,
) {
    val vm: MessagePushesViewModel = viewModel(
        factory = viewModelFactory { initializer { MessagePushesViewModel(repository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    // 每次进这一页只替人选一次:关掉右栏回到骨架是人的选择,不该又被选回去。存进 saveable,
    // 转屏和从对话里点进别处再回来都不重来。
    var autoOpened by rememberSaveable { mutableStateOf(false) }
    val first = state.items.firstOrNull()
    LaunchedEffect(autoOpenFirst, first) {
        if (autoOpenFirst && !autoOpened && first != null && selectedTalker == null) {
            autoOpened = true
            onOpenWhisper(first)
        }
    }
    // 读完了却一个推送会话都没有、或者读失败了,就不再等:不然右栏会一直转下去。
    val awaitingFirst = autoOpenFirst && !autoOpened && state.error == null &&
        !(state.loaded && state.items.isEmpty())
    LaunchedEffect(awaitingFirst) { onAwaitingFirstChange(awaitingFirst) }
    MessagePushesScreen(
        state = state,
        selectedTalker = selectedTalker,
        onLoadMore = vm::loadMore,
        onRefresh = vm::refresh,
        onOpenWhisper = onOpenWhisper,
        onBack = onBack,
    )
}

@Composable
private fun MessagePushesScreen(
    state: MessageListState<WhisperSession, Long>,
    selectedTalker: Long?,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onOpenWhisper: (WhisperSession) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(
                title = stringResource(Res.string.message_up_pushes),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            ) {
                RefreshAction(refreshing = state.refreshing, onRefresh = onRefresh)
            }
        },
    ) { insets ->
        AdaptiveContent(modifier = Modifier.padScaffoldExceptBottom(insets)) {
            RefreshBox(
                refreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                PagedColumn(
                    items = state.items,
                    key = { it.talkerId },
                    skeletonRow = { PersonRowSkeleton() },
                    loading = state.showsSkeleton,
                    appending = state.appending,
                    hasMore = state.hasMore,
                    error = state.error?.let { stringResource(it) },
                    emptyText = stringResource(Res.string.message_empty_pushes),
                    onLoadMore = onLoadMore,
                    onRetry = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) { session ->
                    ConversationRow(
                        session,
                        selected = session.talkerId == selectedTalker,
                        onClick = { onOpenWhisper(session) },
                    )
                }
            }
        }
    }
}

/**
 * 翻的是整份会话列表,留下的只是推送会话。
 *
 * **一页里一个推送都没有时自己接着翻**,直到翻出至少一个或者到底:列表是空的时候屏上只有骨架,
 * PagedColumn 的触底预取不在组合里,没有人会替它续页。翻出第一个之后续页交还给触底预取,
 * 不一口气把几百个会话翻完。
 */
class MessagePushesViewModel(private val repository: MessageRepository) : ViewModel() {

    private val _state = MutableStateFlow(MessageListState<WhisperSession, Long>())
    val state: StateFlow<MessageListState<WhisperSession, Long>> = _state.asStateFlow()

    /** 已经见过的会话 id,含非推送的。续页没带来任何新 id 即当作到底,理由同 MessageViewModel.loadPage。 */
    private val seen = mutableSetOf<Long>()

    init {
        load(append = false, reset = false)
    }

    fun refresh() = load(append = false, reset = true)

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.appending || current.refreshing || !current.hasMore) return
        load(append = true, reset = false)
    }

    private fun load(append: Boolean, reset: Boolean) {
        _state.update {
            it.copy(loading = !append && !reset, appending = append, refreshing = reset, error = null)
        }
        viewModelScope.launch {
            var cursor = if (append) _state.value.cursor else null
            if (!append) seen.clear()
            var collected = if (append) _state.value.items else emptyList()
            while (true) {
                when (val result = repository.sessions(cursor)) {
                    is BiliResult.Ok -> {
                        val page = result.value
                        val fresh = page.items.filter { seen.add(it.talkerId) }
                        collected = collected + fresh.filter { it.lastIsUpPush }
                        cursor = if (fresh.isEmpty()) null else page.next
                        if (collected.isNotEmpty() || cursor == null) break
                    }

                    else -> {
                        val error = result.errorTextRes("UP 主推送会话")
                        _state.update {
                            it.copy(loading = false, appending = false, refreshing = false, loaded = true, error = error)
                        }
                        return@launch
                    }
                }
            }
            _state.update {
                it.copy(
                    items = collected,
                    cursor = cursor,
                    loading = false,
                    appending = false,
                    refreshing = false,
                    loaded = true,
                )
            }
        }
    }
}
