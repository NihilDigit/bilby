package com.bilby.ui

import android.os.Bundle
import android.util.Log
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.bilby.AppContainer
import com.bilby.BilbyApplication
import com.bilby.agent.AgentIntent
import com.bilby.api.BiliResult
import com.bilby.ui.agent.AgentTraceScreen
import com.bilby.ui.agent.AgentViewModel
import com.bilby.ui.comment.CommentViewModel
import com.bilby.ui.feed.FeedScreen
import com.bilby.ui.feed.FeedViewModel
import com.bilby.ui.login.LoginScreen
import com.bilby.ui.login.LoginViewModel
import com.bilby.ui.search.SearchChatScreen
import com.bilby.ui.search.SearchChatViewModel
import com.bilby.ui.space.SpaceScreen
import com.bilby.ui.space.SpaceViewModel
import com.bilby.ui.theme.BilbyTheme
import com.bilby.ui.toview.ToViewScreen
import com.bilby.ui.toview.ToViewViewModel
import com.bilby.ui.video.VideoScreen
import com.bilby.ui.video.VideoViewModel

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
        val vm: LoginViewModel = viewModel(
            factory = viewModelFactory { initializer { LoginViewModel(container.authRepository) } },
        )
        val state by vm.state.collectAsStateWithLifecycle()
        LoginScreen(state = state, onRefresh = vm::restart)
        return
    }

    // 每次冷启动检查一次是否该刷新 Cookie。cookie/info 自己会判断,不该刷时只是一次极轻的
    // 请求;放在这里而不是每次请求前检查,是因为 B 站的判断粒度本来就是"每日第一次访问"。
    LaunchedEffect(Unit) {
        val result = container.cookieRefresher.refreshIfNeeded()
        if (result is BiliResult.ApiError) {
            Log.w("Bilby", "cookie 刷新失败(${result.code}): ${result.message}")
        }
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
                )
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
                )
            }
            entry<AgentRelated> { key ->
                AgentRoute(
                    container = container,
                    intent = AgentIntent.Related(key.bvid, key.title, key.upName),
                    onVideoClick = { backStack.add(Video(it)) },
                )
            }
            entry<Space> { key ->
                SpaceRoute(container, key.mid, onVideoClick = { backStack.add(Video(it)) }, onBack = { backStack.removeLastOrNull() })
            }
        },
    )
}

private enum class RootTab(val label: String, val icon: ImageVector) {
    Feed("动态", Icons.Filled.Subscriptions),
    Search("搜索", Icons.Filled.Search),
    ToView("稍后再看", Icons.Filled.WatchLater),
}

/**
 * 三个 tab 都是显式入口:刷更新、搜索、看自己存的。没有"随便看看"那一格
 * (DESIGN 1.1 的推送式入口那一栏)。
 *
 * 三个 pane 各自持有 ViewModel 并常驻,切走再切回保留滚动位置和搜索结果 ——
 * 每次切 tab 都重新拉一遍等于把"刷完"的状态清零。
 */
@Composable
private fun RootTabs(
    container: AppContainer,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(RootTab.Feed) }

    // IME 退让放在 Scaffold 这一层,让底栏跟着键盘一起上移。放在内层输入框上的话,
    // 底栏仍会在键盘下方占着高度,表现为输入框与键盘之间空一条。
    Scaffold(
        modifier = Modifier.imePadding(),
        bottomBar = {
            NavigationBar {
                RootTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
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
                .padding(bottom)
                .consumeWindowInsets(bottom)
        ) {
            when (selected) {
                RootTab.Feed -> FeedRoute(container, onVideoClick)
                RootTab.Search -> SearchRoute(container, onVideoClick, onUserClick)
                RootTab.ToView -> ToViewRoute(container, onVideoClick)
            }
        }
    }
}

@Composable
private fun FeedRoute(container: AppContainer, onVideoClick: (String) -> Unit) {
    val vm: FeedViewModel = viewModel(
        factory = viewModelFactory { initializer { FeedViewModel(container.dynamicRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    FeedScreen(
        state = state,
        onLoadMore = vm::loadMore,
        onRetry = vm::loadFirstPage,
        onItemClick = { onVideoClick(it.bvid) },
    )
}

@Composable
private fun SearchRoute(
    container: AppContainer,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
) {
    val vm: SearchChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SearchChatViewModel(container.searchRepository, container.agentLoop) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    SearchChatScreen(
        state = state,
        onInputChange = vm::onInputChange,
        onModeChange = vm::onModeChange,
        onSend = vm::send,
        onVideoClick = onVideoClick,
        onUserClick = onUserClick,
        onLoadMore = vm::loadMore,
        onRetry = vm::retry,
    )
}

@Composable
private fun ToViewRoute(container: AppContainer, onVideoClick: (String) -> Unit) {
    val vm: ToViewViewModel = viewModel(
        factory = viewModelFactory { initializer { ToViewViewModel(container.toViewRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    ToViewScreen(
        state = state,
        onDelete = { vm.delete(it) },
        onClearFinished = vm::clearFinished,
        onItemClick = { onVideoClick(it.bvid) },
        onRetry = vm::retry,
    )
}

@Composable
private fun AgentRoute(container: AppContainer, intent: AgentIntent, onVideoClick: (String) -> Unit) {
    val vm: AgentViewModel = viewModel(
        key = "agent-$intent",
        factory = viewModelFactory { initializer { AgentViewModel(container.agentLoop, intent) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    AgentTraceScreen(state = state, onVideoClick = onVideoClick, onRetry = vm::start)
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
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val related by vm.related.collectAsStateWithLifecycle()
    val relation by vm.relation.collectAsStateWithLifecycle()
    val favFolders by vm.favFolders.collectAsStateWithLifecycle()

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
        // 心跳挂在同一个时机上:一次算本地进度(冷启动续播用),一次上报服务端(跨端续播用)。
        onSaveProgress = { position, duration ->
            vm.saveProgress(position, duration)
            vm.reportHeartbeat(position, duration, finished = duration > 0 && position >= duration - 1_000)
        },
        onQualityChange = vm::setQuality,
        onFindRelated = vm::findRelated,
        onUpClick = onUpClick,
        relation = relation,
        favFolders = favFolders,
        onLike = vm::toggleLike,
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
