package dev.bilby.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import dev.bilby.R
import dev.bilby.agent.AgentIntent
import kotlinx.coroutines.launch
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.agent.AgentTraceScreen
import dev.bilby.ui.agent.AgentViewModel
import dev.bilby.ui.comment.CommentViewModel
import dev.bilby.ui.feed.FeedScreen
import dev.bilby.ui.feed.FeedViewModel
import dev.bilby.ui.follow.FollowingsScreen
import dev.bilby.ui.follow.FollowingsViewModel
import dev.bilby.ui.login.TvLoginScreen
import dev.bilby.ui.login.TvLoginViewModel
import dev.bilby.ui.search.SearchChatScreen
import dev.bilby.ui.search.SearchMode
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

    // 三个根 pane 的 ViewModel 建在 NavDisplay **外面**,宿主是 Activity 的 ViewModelStore。
    //
    // 建在 entry<Home> 里面时,作用域取决于 Navigation3 给每个 NavEntry 装的
    // ViewModelStore 装饰器,而助理循环正跑在 searchVm 的 viewModelScope 上——它能不能扛过
    // "点开搜索结果里的视频"和"切到动态页",就成了一个依赖框架默认值的问题。提到这里之后
    // 它们与 backstack 无关:压进播放页、切 tab 都只是 composable 离开组合,循环照跑。
    val feedVm: FeedViewModel = viewModel(
        factory = viewModelFactory { initializer { FeedViewModel(container.dynamicRepository, container.followRepository) } },
    )
    val searchVm: SearchChatViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SearchChatViewModel(container.searchRepository, container.agentLoop) }
        },
    )
    val toViewVm: ToViewViewModel = viewModel(
        factory = viewModelFactory { initializer { ToViewViewModel(container.toViewRepository) } },
    )

    // 在 composable 作用域里先取出来:下面几个 transitionSpec 的 lambda 不是 @Composable,
    // 在里面读不到 MaterialTheme。
    //
    // M3 把"东西在空间里移动"和"东西淡入淡出"分成两套 spec:位移带一点回弹才像实体,
    // 透明度不该回弹(会闪)。
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val fade = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    val backStack = rememberNavBackStack(Home)
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        // Nav3 默认那套是缩放 + 淡入淡出:新页从画面中央放大出来,旧页缩回去。它读起来像
        // "这一页替换了那一页",而这里发生的是"压进去一层",两者的空间关系对不上;加上默认
        // 时长偏长,退出时那一下缩小尤其显眼。
        //
        // 换成沿 X 轴的位移。**真正在移动的那一页走整屏,被压住的那页只走 1/4 做视差。**
        //
        // 距离一度是反的(进来的走 1/4、让开的走 1/12),结果是返回时上层页只挪了一点就淡没,
        // 看上去像直接切到了上一屏 —— 动画其实播了,只是没有一页走过足够的距离让人看见。
        // 视差方向也固定:压进去时新页从右侧来、旧页往左退;返回时整个反过来。
        //
        // 淡入淡出只给做视差的那一页。走整屏的那页不能再淡:它会在走完之前就透明掉,
        // 于是"看不见动画"这件事换个方式又发生一遍。
        transitionSpec = {
            slideInHorizontally(spatial) { it } togetherWith
                (slideOutHorizontally(spatial) { -it / 4 } + fadeOut(fade))
        },
        popTransitionSpec = {
            (slideInHorizontally(spatial) { -it / 4 } + fadeIn(fade)) togetherWith
                slideOutHorizontally(spatial) { it }
        },
        // 预测式返回用同一套形状,但**不能带自己的时长**:这一段的进度由手指给,配上 tween
        // 就成了动画和手势各走各的。用 snap,每一帧都停在手势当前的位置上,松手后由框架接管
        // 剩下的部分。
        predictivePopTransitionSpec = { _ ->
            (slideInHorizontally(snap()) { -it / 4 } + fadeIn(snap())) togetherWith
                slideOutHorizontally(snap()) { it }
        },
        entryProvider = entryProvider {
            entry<Home> {
                RootTabs(
                    feedVm = feedVm,
                    searchVm = searchVm,
                    toViewVm = toViewVm,
                    onVideoClick = { backStack.add(Video(it)) },
                    onUserClick = { backStack.add(Space(it)) },
                    onSettingsClick = { backStack.add(Settings) },
                    onOpenFollowings = { backStack.add(Followings) },
                )
            }
            entry<Settings> {
                SettingsRoute(container, onBack = { backStack.removeLastOrNull() })
            }
            entry<Video> { key ->
                VideoRoute(
                    container = container,
                    bvid = key.bvid,
                    startListening = key.listening,
                    onUpClick = { backStack.add(Space(it)) },
                    onFindRelated = { bvid, title, upName -> backStack.add(AgentRelated(bvid, title, upName)) },
                    // 切集是**重组,不是压栈**:换的是这一页在放哪一条,不是又进了一层。
                    // 压栈的话看五集就攒五层,返回要一集一集退回去,而合集本来是一个有限集合、
                    // 用户是在里面平移。替换栈顶之后,从任何一集返回都回到进来时的地方。
                    //
                    // 今天那个"点下一集不自动播放"的 bug 也出在这里:压栈时旧页的 onDispose
                    // 在新页起播之后才跑,把刚起播的下一集暂停了。替换栈顶让这个错位不成立。
                    onOpenVideo = { backStack[backStack.lastIndex] = Video(it) },
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
            entry<Followings> {
                FollowingsRoute(
                    container = container,
                    onUpClick = { backStack.add(Space(it)) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Space> { key ->
                SpaceRoute(
                    container,
                    key.mid,
                    onVideoClick = { backStack.add(Video(it)) },
                    onListenUp = { backStack.add(Video(it, listening = true)) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
        },
    )
}

/**
 * M3 的导航栏要求选中项用实心图标、未选中用线性图标(不只是变色)——
 * 图标形态本身就是一路状态指示,只靠颜色的话色觉障碍用户看不出当前在哪一格。
 */
private enum class RootTab(
    @param:StringRes val label: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Feed(R.string.tab_feed, Icons.Filled.Subscriptions, Icons.Outlined.Subscriptions),
    Search(R.string.tab_search, Icons.Filled.Search, Icons.Outlined.Search),
    ToView(R.string.tab_toview, Icons.Filled.WatchLater, Icons.Outlined.WatchLater),
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
    feedVm: FeedViewModel,
    searchVm: SearchChatViewModel,
    toViewVm: ToViewViewModel,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onOpenFollowings: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(RootTab.Feed) }

    val feedState by feedVm.state.collectAsStateWithLifecycle()
    val searchState by searchVm.state.collectAsStateWithLifecycle()
    val toViewState by toViewVm.state.collectAsStateWithLifecycle()

    // IME 退让放在 Scaffold 这一层,让底栏跟着键盘一起上移。放在内层输入框上的话,
    // 底栏仍会在键盘下方占着高度,表现为输入框与键盘之间空一条。
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            BilbyTopBar(title = stringResource(selected.label)) {
                when (selected) {
                    // 动态页没有内容相关的动作:这一页能做的只有往下看,刷新是拉到底自动翻页。
                    // 放个刷新按钮等于把下拉刷新那套仪式换个位置摆回来(DESIGN 2.1)。
                    // 这里唯一的图标是设置 —— 它需要一个入口,而底部三格是"我要去哪",
                    // 设置不是目的地,只能挂在某个顶栏上,动态页是启动后的第一屏。
                    RootTab.Feed -> IconButton(onClick = onSettingsClick) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }

                    // 开新会话是清空助理上下文的唯一入口(DESIGN 3.1:会话必须由用户显式开启),
                    // 属于"改变整页状态"的动作,正是 M3 说该放进顶栏的那一类。
                    // 「新会话」只属于助理:普通搜索没有会话可开,留一个按不出效果的加号
                    // 只会让人以为自己漏了什么。
                    RootTab.Search -> if (searchState.mode == SearchMode.Agent) {
                        IconButton(onClick = searchVm::newSession) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.search_new_session),
                            )
                        }
                    } else {
                        Unit
                    }

                    RootTab.ToView -> TextButton(
                        onClick = toViewVm::clearFinished,
                        enabled = !toViewState.clearing,
                    ) {
                        Text(
                            stringResource(
                                if (toViewState.clearing) {
                                    R.string.toview_clearing
                                } else {
                                    R.string.toview_clear_finished
                                },
                            ),
                        )
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
                        label = { Text(stringResource(tab.label)) },
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
                    onUpClick = onUserClick,
                    onOpenFollowings = onOpenFollowings,
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
            initializer { SettingsViewModel(container.settings, container.spaceRepository, container.llmClient) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsScreen(
        state = state,
        onLlmChange = vm::saveLlm,
        onSmokeTestLlm = vm::smokeTestLlm,
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

/**
 * 关注列表。二级页面,自带返回的顶栏 —— 它不是根 tab,不该借用 RootTabs 那一层的 Scaffold。
 */
@Composable
private fun FollowingsRoute(
    container: AppContainer,
    onUpClick: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm: FollowingsViewModel = viewModel(
        factory = viewModelFactory { initializer { FollowingsViewModel(container.followRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { BilbyTopBar(title = stringResource(R.string.followings_title), onBack = onBack) },
    ) { insets ->
        FollowingsScreen(
            state = state,
            onUpClick = onUpClick,
            onLoadMore = vm::loadMore,
            onRetry = vm::retry,
            contentPadding = insets,
        )
    }
}

@Composable
private fun SpaceRoute(
    container: AppContainer,
    mid: Long,
    onVideoClick: (String) -> Unit,
    onListenUp: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: SpaceViewModel = viewModel(
        key = "space-$mid",
        factory = viewModelFactory { initializer { SpaceViewModel(mid, container.spaceRepository, container.relationRepository) } },
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
        onToggleFollow = vm::toggleFollow,
        // 听这位 UP 的投稿:挑第一条进播放页并直接以听的状态打开。**宿主只有播放页一个** ——
        // 空间页不承载听视频界面,否则又会多出一处需要单独维护的生命周期。
        onListenUp = {
            state.archives.items.firstOrNull()?.let { onListenUp(it.bvid) }
        },
        onBack = onBack,
        onRetry = vm::retry,
    )
}

@Composable
private fun VideoRoute(
    container: AppContainer,
    bvid: String,
    startListening: Boolean = false,
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
                    container.agentLoop,
                    container.heartbeatReporter,
                    container.videoActionRepository,
                    container.settings,
                    container.sponsorBlockRepository,
                    container.queueSourceRepository,
                    container.toViewRepository,
                    container.relationRepository,
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
    val followState by vm.followState.collectAsStateWithLifecycle()
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
        // 进度只回传服务端一份(DESIGN 7)。本地不再另存:续播位置和流地址来自同一个 playurl
        // 响应,本地那份换不到任何东西,却是"新页写下上一条进度"这类串味的唯一入口。
        onReportProgress = { position, duration ->
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
        followState = followState,
        onToggleFollow = vm::toggleFollow,
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
        startListening = startListening,
    )
}

