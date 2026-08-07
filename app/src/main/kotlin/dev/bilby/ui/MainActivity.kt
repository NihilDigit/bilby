package dev.bilby.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
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
import dev.bilby.ui.comment.CommentUiState
import dev.bilby.ui.comment.CommentViewModel
import androidx.compose.material.icons.outlined.DeleteSweep
import dev.bilby.ui.fav.FavFolderScreen
import dev.bilby.ui.fav.FavFolderViewModel
import dev.bilby.ui.fav.FavHubScreen
import dev.bilby.ui.fav.FavHubViewModel
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
import dev.bilby.ui.settings.UpdateInstaller
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

    val favHubVm: FavHubViewModel = viewModel(
        factory = viewModelFactory { initializer { FavHubViewModel(container.favRepository) } },
    )

    val backStack = rememberNavBackStack(Home)

    /**
     * 播放器与队列的生命周期到此为止:**backstack 上再没有播放页,就把播放器销毁。**
     *
     * 队列从第一个播放页打开时建立,中途从空间页或「找相关」打开别的视频只是重做队列;
     * 一路返回到没有播放页了,这次播放才算结束。
     *
     * 判据是"还有没有播放页"这个纯粹的集合判断,不是"这一页是被弹出还是被覆盖" ——
     * 后者是导航层的判断,CLAUDE.md 记着它被做错过。
     *
     * 划走 app 不走这里:那时整个 composition 都没了,effect 不会跑,后台听视频照常继续
     * (要不要停由 onTaskRemoved 判断)。转屏同理,backstack 是保存恢复的。
     */
    val context = LocalContext.current
    val hasVideoPage = backStack.any { it is Video }
    LaunchedEffect(hasVideoPage) {
        if (!hasVideoPage) AudioPlaybackService.stop(context)
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        // 转场用 duration + easing,**不用主题的 spring**。M3 在 transitions 页注明转场仍在
        // 旧的缓动/时长体系上("M3 transitions use the legacy easing and duration system"),
        // spring 那套(MotionScheme)是给组件动效的;它本身也只有六个 spring spec,取不到
        // duration。expressive 的 spatial 阻尼低到会过冲,而 applying-transitions 明说
        // "Common transitions should not use overt style effects like bouncy springs"。
        //
        // 形态用 Android 的做法:**两页都只走一小段并淡入淡出**,而不是让一页走整屏。规范原文
        // "Android uses a fade as screens slide. This reduces the amount of motion, since the
        // screens don't have to slide the full width of the device." 之前那版是 iOS 的视差
        // (背景页走得比前景慢),两条路只能选一条,混着用就是现在这个既不像 Android 也不像 iOS
        // 的东西。
        transitionSpec = {
            (slideInHorizontally(slideEnter) { it / 5 } + fadeIn(fadeEnter)) togetherWith
                (slideOutHorizontally(slideExit) { -it / 5 } + fadeOut(fadeExit))
        },
        popTransitionSpec = {
            (slideInHorizontally(slideEnter) { -it / 5 } + fadeIn(fadeEnter)) togetherWith
                (slideOutHorizontally(slideExit) { it / 5 } + fadeOut(fadeExit))
        },
        // 预测式返回这一段的进度由手指给,配 tween 会让动画和手势各走各的,所以用 snap。
        //
        // 还差三样规范要求的东西,都卡在 NavDisplay 这个 API 上:progress 要先过一遍
        // standard decelerate 再用(它由 NavDisplay 内部驱动,这里拿不到)、退出页缩到 90% 与
        // 进入页从 110% 收回、35% 的 fade through 阈值。见 developer.android.com 的
        // predictive back 指南。要补齐得绕开 predictivePopTransitionSpec 自己接手势。
        predictivePopTransitionSpec = { _ ->
            (slideInHorizontally(snap()) { -it / 5 } + fadeIn(snap())) togetherWith
                (slideOutHorizontally(snap()) { it / 5 } + fadeOut(snap()))
        },
        entryProvider = entryProvider {
            entry<Home> {
                RootTabs(
                    feedVm = feedVm,
                    searchVm = searchVm,
                    toViewVm = toViewVm,
                    favHubVm = favHubVm,
                    onVideoClick = { backStack.add(Video(it)) },
                    onUserClick = { backStack.add(Space(it)) },
                    onSettingsClick = { backStack.add(Settings) },
                    onOpenFollowings = { backStack.add(Followings) },
                    onOpenToView = { backStack.add(ToViewList) },
                    onOpenFolder = { id, title -> backStack.add(FavFolderContents(id, title)) },
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
            entry<ToViewList> {
                ToViewListRoute(
                    vm = toViewVm,
                    onVideoClick = { backStack.add(Video(it)) },
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<FavFolderContents> { key ->
                FavFolderRoute(
                    container = container,
                    mediaId = key.mediaId,
                    title = key.title,
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
    ToView(R.string.tab_saved, Icons.Filled.WatchLater, Icons.Outlined.WatchLater),
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
    favHubVm: FavHubViewModel,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onOpenFollowings: () -> Unit,
    onOpenToView: () -> Unit,
    onOpenFolder: (mediaId: Long, title: String) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(RootTab.Feed) }

    val feedState by feedVm.state.collectAsStateWithLifecycle()
    val searchState by searchVm.state.collectAsStateWithLifecycle()
    val toViewState by toViewVm.state.collectAsStateWithLifecycle()
    val favHubState by favHubVm.state.collectAsStateWithLifecycle()

    // IME 退让放在 Scaffold 这一层,让底栏跟着键盘一起上移。放在内层输入框上的话,
    // 底栏仍会在键盘下方占着高度,表现为输入框与键盘之间空一条。
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            BilbyTopBar(title = stringResource(selected.label)) {
                when (selected) {
                    // 设置需要一个入口 —— 它需要一个入口,而底部三格是"我要去哪",
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

                    // 第三格是入口页,没有页级动作。"清空已看完"跟着稍后再看的列表
                    // 搬到了它自己那一页 —— 那个动作作用于列表,不作用于这一屏。
                    RootTab.ToView -> Unit
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
                    onRefresh = feedVm::refresh,
                    onItemClick = { onVideoClick(it.bvid) },
                    onUpClick = onUserClick,
                    onOpenFollowings = onOpenFollowings,
                )

                RootTab.Search -> SearchChatScreen(
                    state = searchState,
                    onInputChange = searchVm::onInputChange,
                    onModeChange = searchVm::onModeChange,
                    onOrderChange = searchVm::onOrderChanged,
                    onSend = searchVm::send,
                    onVideoClick = onVideoClick,
                    onUserClick = onUserClick,
                    onLoadMore = searchVm::loadMore,
                    onRetry = searchVm::retry,
                )

                RootTab.ToView -> FavHubScreen(
                    state = favHubState,
                    toViewCount = toViewState.count,
                    onOpenToView = onOpenToView,
                    onOpenFolder = { onOpenFolder(it.id, it.title) },
                )
            }
        }
    }
}

@Composable
private fun SettingsRoute(container: AppContainer, onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    container.settings,
                    container.spaceRepository,
                    container.llmClient,
                    container.updateRepository,
                )
            }
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
        onCheckUpdate = vm::checkUpdate,
        onDownloadUpdate = { vm.downloadUpdate(it, UpdateInstaller.downloadDir(context)) },
        onInstallUpdate = { UpdateInstaller.install(context, it) },
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
/**
 * 稍后再看的列表。"清空已看完"跟着它走,并且按 M3 的 top app bar anatomy 用图标按钮
 * (规范:headline 之后最多两个 icon button),而不是原来那个带文字的 TextButton ——
 * 文字按钮的宽度随文案变,切页时顶栏右侧会跳。
 */
@Composable
private fun ToViewListRoute(
    vm: ToViewViewModel,
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            BilbyTopBar(title = stringResource(R.string.tab_toview), onBack = onBack) {
                IconButton(onClick = vm::clearFinished, enabled = !state.clearing) {
                    Icon(
                        Icons.Outlined.DeleteSweep,
                        contentDescription = stringResource(R.string.toview_clear_finished),
                    )
                }
            }
        },
    ) { insets ->
        Box(modifier = Modifier.padding(insets)) {
            ToViewScreen(
                state = state,
                onDelete = { vm.delete(it) },
                onItemClick = { onVideoClick(it.bvid) },
                onRetry = vm::retry,
            )
        }
    }
}

@Composable
private fun FavFolderRoute(
    container: AppContainer,
    mediaId: Long,
    title: String,
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: FavFolderViewModel = viewModel(
        key = "fav-$mediaId",
        factory = viewModelFactory { initializer { FavFolderViewModel(mediaId, container.favRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BilbyTopBar(title = title, onBack = onBack) }) { insets ->
        FavFolderScreen(
            state = state,
            onItemClick = { onVideoClick(it.bvid) },
            onLoadMore = vm::loadMore,
            onRetry = vm::retry,
            contentPadding = insets,
        )
    }
}

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
    // 切集**不进 backstack**。合集里的每一集互为平级,换一集不是进了一层;走 backstack 的话
    // NavDisplay 只会按下钻处理,而这里根本没有层级关系。
    //
    // 这也把建模摆正了:压栈过一版(每集攒一层)、替换栈顶过一版(语义仍是导航),两版都是在
    // 用导航层表达一件页内的事。CLAUDE.md 记着听视频被三次错误地建模成导航目的地,切集是
    // 同一个坑的另一个入口。
    //
    // **`bvid` 必须当 key**:不给 key 的话,导航到另一条视频时这个位置会把上一条的
    // `episode` 复原回来,而 `VideoPane` 的 ViewModel key 就是它 —— 结果是点开新视频先看到
    // 上一条的详情页,要等播放器真正切过去、下面那个 LaunchedEffect 才纠正回来。
    var episode by rememberSaveable(bvid) { mutableStateOf(bvid) }

    /**
     * 听视频**提到切集之外**。
     *
     * 它是播放器的一种模式(把视频轨关掉),不是某一条视频的页面状态:队列往前走一条时
     * 下面那个 VideoPane 会整个换掉,状态留在里面的话,听着听着自动连播到下一条就会
     * 被踢回有画面的界面。
     *
     * 提到这里仍然不是导航目的地 —— 它只是换了个持有者,backstack 上依旧只有一个播放页。
     */
    //
    // key 用的是路由参数 `bvid` 而不是上面那个 `episode`:这正好表达了注释说的那条边界 ——
    // 同一条路由内切集不该退出听视频,而点开**另一条**视频是一次新的进入,该回到 [startListening]。
    var listening by rememberSaveable(bvid) { mutableStateOf(startListening) }

    /**
     * **页面跟着队列走,不是反过来。**
     *
     * 队列在服务那边,推它的可能是通知栏的下一条、耳机线控、听视频里点的某一项、或者
     * 一条播完自动连播。这些路径没有一条经过界面,所以"这一页在放哪条视频"只能由队列回答。
     *
     * 原先没有这一条:听视频里连播到别的视频、退回播放页后,页面还停在进来时那一条 ——
     * 标题、简介、点赞数全是 A 的,而播放器在放 E。
     *
     * **换页不做转场。** 横滑表达的是"我横着挪了一格",那是给用户手势配的;自动连播不是
     * 用户动作,滑一下会让人以为自己划了屏。而且它恰好和取流、prepare、codec 初始化撞在同一
     * 瞬间,滑进来的还是一块空页(详情要等一次网络往返)——三样凑在一起,读起来就是卡。
     */
    val audioState by AudioPlaybackService.state.collectAsStateWithLifecycle()
    LaunchedEffect(audioState.current?.bvid) {
        val target = audioState.current?.bvid ?: return@LaunchedEffect
        if (target != episode) episode = target
    }

    VideoPane(
        container = container,
        bvid = episode,
        listening = listening,
        onListeningChange = { listening = it },
        onUpClick = onUpClick,
        onFindRelated = onFindRelated,
        onOpenVideo = onOpenVideo,
    )
}

@Composable
private fun VideoPane(
    container: AppContainer,
    bvid: String,
    listening: Boolean,
    onListeningChange: (Boolean) -> Unit,
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
                    container.toViewRepository,
                    container.relationRepository,
                    container.subtitleRepository,
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
    val followState by vm.followState.collectAsStateWithLifecycle()
    val addedToView by vm.addedToView.collectAsStateWithLifecycle()
    val subtitleTracks by vm.subtitleTracks.collectAsStateWithLifecycle()
    val subtitleLan by vm.subtitleLan.collectAsStateWithLifecycle()
    val subtitleCues by vm.subtitleCues.collectAsStateWithLifecycle()

    // 评论用 aid 作 oid,要等视频详情回来才知道 —— 但**不能拿它卡住整页**。
    //
    // 原先这里是 `val aid = state.detail?.aid ?: return`:详情没回来就整页不画。切集时新页
    // 要等一次网络往返才有东西,那段时间屏幕上是空的。SoT 之后这个等待已经没必要了 ——
    // 标题、UP 名、封面队列项里就有,播放器的画面更是早就在放了。
    val commentVm: CommentViewModel? = state.detail?.aid?.let { aid ->
        viewModel(
            key = "comment-$aid",
            factory = viewModelFactory { initializer { CommentViewModel(container.commentRepository, aid) } },
        )
    }
    val commentState = commentVm?.state?.collectAsStateWithLifecycle()?.value ?: CommentUiState()

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
        onFindRelated = vm::findRelated,
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
        onPlayEpisode = onOpenVideo,
        onRelatedVideoClick = onOpenVideo,
        onCommentSort = { commentVm?.setSort(it) },
        onCommentLoadMore = { commentVm?.loadMore() },
        onExpandReplies = { commentVm?.expandReplies(it) },
        onSendComment = { text, parent -> commentVm?.send(text, parent) },
        onLikeComment = { commentVm?.like(it) },
        onDeleteComment = { commentVm?.delete(it) },
        listening = listening,
        onListeningChange = onListeningChange,
        subtitleTracks = subtitleTracks,
        subtitleLan = subtitleLan,
        subtitleCues = subtitleCues,
        onSelectSubtitle = vm::selectSubtitle,
    )
}

/**
 * 转场的时长与缓动。数值来自 M3 的 easing-and-duration/tokens-specs:
 * emphasized decelerate 是 `PathInterpolator(0.05, 0.7, 0.1, 1)`,medium4 是 400ms
 * ("transitions that traverse a medium area of the screen")。
 *
 * 规范没有为 forward and backward 指定具体档位——那一节只说"用平台默认"。400ms + emphasized
 * 是照它给的两个同量级例子(FAB 展开 sheet 400ms、卡片展开全屏 500ms)推的,不是规范原文。
 */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private val slideEnter = tween<IntOffset>(400, easing = EmphasizedDecelerate)
private val slideExit = tween<IntOffset>(400, easing = EmphasizedAccelerate)
private val fadeEnter = tween<Float>(400, easing = EmphasizedDecelerate)
private val fadeExit = tween<Float>(400, easing = EmphasizedAccelerate)

/** lateral 用 default 档:它只覆盖屏幕的一部分(内容区),不是整屏转场。 */
