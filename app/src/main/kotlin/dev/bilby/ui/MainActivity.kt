package dev.bilby.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.bilby.AppContainer
import dev.bilby.BilbyApplication
import dev.bilby.BiliLog
import dev.bilby.agent.AgentIntent
import kotlinx.coroutines.launch
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.agent.AgentTraceScreen
import dev.bilby.ui.agent.AgentViewModel
import dev.bilby.ui.comment.CommentViewModel
import dev.bilby.ui.feed.FeedScreen
import dev.bilby.ui.feed.FeedViewModel
import dev.bilby.ui.login.TvLoginScreen
import dev.bilby.ui.login.TvLoginViewModel
import dev.bilby.ui.search.SearchChatScreen
import dev.bilby.ui.search.SearchChatViewModel
import dev.bilby.ui.settings.SettingsScreen
import dev.bilby.ui.settings.SettingsViewModel
import dev.bilby.ui.space.SpaceScreen
import dev.bilby.ui.space.SpaceViewModel
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.toview.ToViewScreen
import dev.bilby.ui.toview.ToViewViewModel
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.bilby.player.AudioPlaybackService
import dev.bilby.ui.listen.ListenScreen
import dev.bilby.ui.video.VideoScreen
import dev.bilby.ui.video.VideoViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as BilbyApplication).container
        setContent {
            BilbyTheme {
                BilbyApp(container)
            }
        }
    }
}

@Composable
private fun BilbyApp(container: AppContainer) {
    // DataStore 第一帧是异步的:null 表示还没读出来,此时什么都不画,
    // 否则已登录用户每次冷启动都会闪一下登录页。
    val credentials by container.settings.credentials.collectAsStateWithLifecycle(initialValue = null)
    val loaded = credentials ?: return

    if (!loaded.isLoggedIn) {
        val vm: TvLoginViewModel = viewModel(
            factory = viewModelFactory { initializer { TvLoginViewModel(container.tvLoginRepository) } },
        )
        val state by vm.state.collectAsStateWithLifecycle()
        // 登录成功后 credentials 会自己更新,这里不需要额外导航
        TvLoginScreen(state = state, onRefresh = vm::restart, onDone = {})
        return
    }

    LaunchedEffect(Unit) {
        // buvid 激活只需成功一次,失败被内部吞掉:没有设备身份只影响写接口,
        // 不该让整个 app 打不开。
        container.deviceFingerprint.activateIfNeeded()
    }

    val backStack = rememberNavBackStack(Home)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider {
            entry<Home> {
                RootTabs(
                    container = container,
                    onVideoClick = { backStack.add(Video(it)) },
                    onUserClick = { backStack.add(Space(it)) },
                    onSettingsClick = { backStack.add(Settings) },
                )
            }
            entry<Settings> {
                SettingsRoute(container, onBack = { backStack.removeLastOrNull() })
            }
            entry<Video> { key ->
                VideoRoute(
                    container = container,
                    bvid = key.bvid,
                    onUpClick = { backStack.add(Space(it)) },
                    onFindRelated = { bvid, title, upName -> backStack.add(AgentRelated(bvid, title, upName)) },
                    onOpenVideo = { backStack.add(Video(it)) },
                )
            }
            entry<AgentSearch> { key ->
                AgentRoute(
                    container = container,
                    intent = AgentIntent.Query(key.query),
                    onVideoClick = { backStack.add(Video(it)) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<AgentRelated> { key ->
                AgentRoute(
                    container = container,
                    intent = AgentIntent.Related(key.bvid, key.title, key.upName),
                    onVideoClick = { backStack.add(Video(it)) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Space> { key ->
                SpaceRoute(container, key.mid, onVideoClick = { backStack.add(Video(it)) }, onBack = { backStack.removeLastOrNull() })
            }
        },
    )
}

/**
 * M3 的导航栏要求选中项用实心图标、未选中用线性图标(不只是变色)——
 * 图标形态本身就是一路状态指示,只靠颜色的话色觉障碍用户看不出当前在哪一格。
 */
private enum class RootTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Feed("动态", Icons.Filled.Subscriptions, Icons.Outlined.Subscriptions),
    Search("搜索", Icons.Filled.Search, Icons.Outlined.Search),
    ToView("稍后再看", Icons.Filled.WatchLater, Icons.Outlined.WatchLater),
}

/**
 * 三个 tab 都是显式入口:刷更新、搜索、看自己存的。没有"随便看看"那一格
 * (DESIGN 1.1 的推送式入口那一栏)。三格正好落在 M3 导航栏 3–5 个目的地的下限上。
 *
 * 三个 pane 的 ViewModel 提到这一层。它们本来就常驻(`viewModel()` 挂在 Activity 的
 * ViewModelStore 上,切 tab 不会销毁),提上来是为了让顶栏能直接拿到各页的动作 ——
 * 顶栏归 Scaffold 管,动作归页面管,不提上来就得把 composable 塞进 state 往上传,
 * 那样重组作用域会乱。
 *
 * 顶栏统一在这一层给:以前三个页面各自拿 systemBars 往内容上贴 padding,三种写法、
 * 三种留白,滚动时内容还会压到状态栏文字上。
 */
@Composable
private fun RootTabs(
    container: AppContainer,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(RootTab.Feed) }

    val feedVm: FeedViewModel = viewModel(
        factory = viewModelFactory { initializer { FeedViewModel(container.dynamicRepository) } },
    )
    val searchVm: SearchChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SearchChatViewModel(
                    container.searchRepository,
                    container.agentLoop,
                    container.agentSessionRepository,
                )
            }
        },
    )
    val toViewVm: ToViewViewModel = viewModel(
        factory = viewModelFactory { initializer { ToViewViewModel(container.toViewRepository) } },
    )

    val feedState by feedVm.state.collectAsStateWithLifecycle()
    val searchState by searchVm.state.collectAsStateWithLifecycle()
    val toViewState by toViewVm.state.collectAsStateWithLifecycle()

    // IME 退让放在 Scaffold 这一层,让底栏跟着键盘一起上移。放在内层输入框上的话,
    // 底栏仍会在键盘下方占着高度,表现为输入框与键盘之间空一条。
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            BilbyTopBar(title = selected.label) {
                when (selected) {
                    // 动态页没有内容相关的动作:这一页能做的只有往下看,刷新是拉到底自动翻页。
                    // 放个刷新按钮等于把下拉刷新那套仪式换个位置摆回来(DESIGN 2.1)。
                    // 这里唯一的图标是设置 —— 它需要一个入口,而底部三格是"我要去哪",
                    // 设置不是目的地,只能挂在某个顶栏上,动态页是启动后的第一屏。
                    RootTab.Feed -> IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }

                    // 开新会话是清空助理上下文的唯一入口(DESIGN 3.1:会话必须由用户显式开启),
                    // 属于"改变整页状态"的动作,正是 M3 说该放进顶栏的那一类。
                    RootTab.Search -> IconButton(onClick = searchVm::newSession) {
                        Icon(Icons.Filled.Add, contentDescription = "新会话")
                    }

                    RootTab.ToView -> TextButton(
                        onClick = toViewVm::clearFinished,
                        enabled = !toViewState.clearing,
                    ) {
                        Text(if (toViewState.clearing) "清空中…" else "清空已看完")
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                RootTab.entries.forEach { tab ->
                    val isSelected = selected == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selected = tab },
                        icon = {
                            Icon(
                                imageVector = if (isSelected) tab.selectedIcon else tab.unselectedIcon,
                                // 标签就在图标正下方,读屏再念一遍图标等于每格念两次。
                                contentDescription = null,
                            )
                        },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { insets ->
        // consumeWindowInsets 是关键:只 padding 不声明消费的话,子层的 imePadding()
        // 仍按屏幕底边算,会再多退让一个底部栏的高度。
        val bottom = PaddingValues(bottom = insets.calculateBottomPadding())
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = insets.calculateTopPadding())
                .padding(bottom)
                .consumeWindowInsets(bottom)
        ) {
            when (selected) {
                RootTab.Feed -> FeedScreen(
                    state = feedState,
                    onLoadMore = feedVm::loadMore,
                    onRetry = feedVm::loadFirstPage,
                    onItemClick = { onVideoClick(it.bvid) },
                )

                RootTab.Search -> SearchChatScreen(
                    state = searchState,
                    onInputChange = searchVm::onInputChange,
                    onModeChange = searchVm::onModeChange,
                    onSend = searchVm::send,
                    onVideoClick = onVideoClick,
                    onUserClick = onUserClick,
                    onLoadMore = searchVm::loadMore,
                    onRetry = searchVm::retry,
                )

                RootTab.ToView -> ToViewScreen(
                    state = toViewState,
                    onDelete = { toViewVm.delete(it) },
                    onItemClick = { onVideoClick(it.bvid) },
                    onRetry = toViewVm::retry,
                )
            }
        }
    }
}

@Composable
private fun SettingsRoute(container: AppContainer, onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SettingsViewModel(container.settings, container.spaceRepository) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsScreen(
        state = state,
        onLlmChange = vm::saveLlm,
        onCodecChange = vm::setCodec,
        onSponsorBlockChange = vm::updateSponsorBlock,
        // 停播放服务要 Context,所以由这一层做,ViewModel 只管清凭据。
        // 顺序是先清后停:反过来的话中间那一瞬服务已停但凭据还在,看起来像"没登出但停了"。
        onLogout = {
            vm.logout {
                AudioPlaybackService.stop(context)
                onBack()
            }
        },
        onBack = onBack,
    )
}

@Composable
private fun AgentRoute(
    container: AppContainer,
    intent: AgentIntent,
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: AgentViewModel = viewModel(
        key = "agent-$intent",
        factory = viewModelFactory { initializer { AgentViewModel(container.agentLoop, intent) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    AgentTraceScreen(state = state, onVideoClick = onVideoClick, onRetry = vm::start, onBack = onBack)
}

@Composable
private fun SpaceRoute(
    container: AppContainer,
    mid: Long,
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: SpaceViewModel = viewModel(
        key = "space-$mid",
        factory = viewModelFactory { initializer { SpaceViewModel(mid, container.spaceRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    SpaceScreen(
        state = state,
        onTabSelected = vm::onTabSelected,
        onArchiveOrderChanged = vm::onArchiveOrderChanged,
        onArchiveKeywordChanged = vm::onArchiveKeywordChanged,
        onArchiveSearch = vm::onArchiveSearch,
        onLoadMoreArchives = vm::loadMoreArchives,
        onLoadMoreDynamics = vm::loadMoreDynamics,
        onLoadMoreCollections = vm::loadMoreCollections,
        onCollectionClick = vm::openCollection,
        onCollectionDetailBack = vm::closeCollectionDetail,
        onLoadMoreCollectionDetail = vm::loadMoreCollectionDetail,
        onVideoClick = { onVideoClick(it.bvid) },
        onBack = onBack,
        onRetry = vm::retry,
    )
}

@Composable
private fun VideoRoute(
    container: AppContainer,
    bvid: String,
    onUpClick: (Long) -> Unit,
    onFindRelated: (bvid: String, title: String, upName: String) -> Unit,
    onOpenVideo: (String) -> Unit,
) {
    val vm: VideoViewModel = viewModel(
        key = "video-$bvid",
        factory = viewModelFactory {
            initializer {
                VideoViewModel(
                    bvid,
                    container.videoRepository,
                    container.database.playbackProgressDao(),
                    container.agentLoop,
                    container.heartbeatReporter,
                    container.videoActionRepository,
                    container.settings,
                    container.sponsorBlockRepository,
                    container.queueSourceRepository,
                    container.toViewRepository,
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val related by vm.related.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val relation by vm.relation.collectAsStateWithLifecycle()
    val favFolders by vm.favFolders.collectAsStateWithLifecycle()
    val sponsorSegments by vm.sponsorSegments.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val addedToView by vm.addedToView.collectAsStateWithLifecycle()

    // 评论用 aid 作 oid,要等视频详情回来才知道;拿到之前先不建 ViewModel。
    val aid = state.detail?.aid ?: return
    val commentVm: CommentViewModel = viewModel(
        key = "comment-$aid",
        factory = viewModelFactory { initializer { CommentViewModel(container.commentRepository, aid) } },
    )
    val commentState by commentVm.state.collectAsStateWithLifecycle()

    VideoScreen(
        state = state,
        related = related,
        commentState = commentState,
        sponsorSegments = sponsorSegments,
        // 心跳挂在同一个时机上:一次算本地进度(冷启动续播用),一次上报服务端(跨端续播用)。
        onSaveProgress = { position, duration ->
            vm.saveProgress(position, duration)
            vm.reportHeartbeat(position, duration, finished = duration > 0 && position >= duration - 1_000)
        },
        onQualityChange = vm::setQuality,
        onFindRelated = vm::findRelated,
        queue = queue,
        // 点队列里的一条 = 切到那个视频。这是确定性导航,不是推荐。
        onPlayQueueItem = onOpenVideo,
        onToggleShuffle = vm::toggleShuffle,
        // 听视频:先按合集找队列,不属于合集才退到 UP 投稿(DESIGN 2.4b)。
        // 听视频播的就是页面上这份队列,不重新构造(DESIGN 2.4b:队列不是听视频的特产)。
        // 听视频只是换个界面:同一个播放器、同一份队列,所以这里只做两件事——
        // 把队列交给服务、跳到听视频页。进度不需要交接,因为播放器根本没换。
        onListen = {
            val items = queue.items
            if (items.isEmpty()) {
                BiliLog.w("听视频:队列为空,无法开始")
            } else {
                val startIndex = items.indexOfFirst { it.bvid == queue.currentBvid }.coerceAtLeast(0)
                AudioPlaybackService.start(context, items, startIndex, queue.shuffled)
            }
        },
        onUpClick = onUpClick,
        relation = relation,
        favFolders = favFolders,
        onLike = vm::toggleLike,
        addedToView = addedToView,
        onAddToView = vm::addToView,
        onCoin = vm::coin,
        onOpenFavPicker = vm::openFavPicker,
        onFavConfirm = vm::confirmFavorite,
        onPlayPart = { vm.playPart(it) },
        onPlayEpisode = onOpenVideo,
        onRelatedVideoClick = onOpenVideo,
        onCommentSort = commentVm::setSort,
        onCommentLoadMore = commentVm::loadMore,
        onExpandReplies = commentVm::expandReplies,
        onSendComment = commentVm::send,
        onLikeComment = commentVm::like,
        onDeleteComment = commentVm::delete,
    )
}
