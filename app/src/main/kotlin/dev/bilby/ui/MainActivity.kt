package dev.bilby.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.entryProvider
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.bilby.AppContainer
import dev.bilby.BilbyApplication
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.agent.AgentIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.comment.CommentUiState
import dev.bilby.ui.comment.CommentViewModel
import androidx.compose.material.icons.outlined.DeleteSweep
import dev.bilby.data.FavFolder
import dev.bilby.data.PlayerPrefs
import dev.bilby.offline.OfflineItem
import dev.bilby.offline.OfflineStatus
import dev.bilby.ui.fav.FavFolderScreen
import dev.bilby.ui.fav.FavFolderViewModel
import dev.bilby.ui.feed.FeedScreen
import dev.bilby.ui.feed.FeedViewModel
import dev.bilby.ui.follow.FollowingsScreen
import dev.bilby.ui.follow.FollowingsViewModel
import dev.bilby.ui.history.HistoryScreen
import dev.bilby.ui.history.HistoryViewModel
import dev.bilby.ui.login.TvLoginScreen
import dev.bilby.ui.login.TvLoginViewModel
import dev.bilby.ui.offline.OfflineScreen
import dev.bilby.ui.offline.OfflineViewModel
import dev.bilby.ui.profile.ProfileScreen
import dev.bilby.ui.profile.ProfileViewModel
import dev.bilby.ui.search.SearchChatScreen
import dev.bilby.ui.search.SearchChatViewModel
import dev.bilby.ui.settings.SettingsScreen
import dev.bilby.ui.settings.UpdateInstaller
import dev.bilby.ui.settings.SettingsViewModel
import dev.bilby.ui.space.SpaceScreen
import dev.bilby.ui.space.SpaceViewModel
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Motion
import dev.bilby.ui.theme.rememberReducedMotion
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.update.StartupUpdateDialog
import dev.bilby.ui.update.StartupUpdateViewModel
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
import dev.bilby.ui.live.LiveRoomRoute
import dev.bilby.ui.video.VideoScreen
import dev.bilby.ui.video.VideoViewModel

private const val PROJECT_GITHUB_URL = "https://github.com/NihilDigit/bilby"

class MainActivity : ComponentActivity() {

    /**
     * 退到后台(切走、锁屏)时把普通视频暂停,听视频继续。**判断在服务那边**,这里只负责
     * 报告"前台没了"——听不听得下去取决于播放器当前装的是什么,那是服务知道的事。
     *
     * 转屏不经过这里:manifest 声明了 configChanges,Activity 不重建。
     */
    override fun onStop() {
        super.onStop()
        AudioPlaybackService.pauseForAppBackground()
    }

    /**
     * 外面递进来的那条链接。**用 MutableStateFlow 而不是直接读 `intent`**:应用已经在跑时
     * 系统走的是 [onNewIntent],那时 composition 早就建好了,只读一次 intent 的写法收不到
     * 第二条链接。
     */
    private val incomingLink = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as BilbyApplication).container
        incomingLink.value = linkFrom(intent)
        setContent {
            BilbyTheme {
                BilbyWindowChrome()
                BilbyApp(container, incomingLink)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingLink.value = linkFrom(intent)
    }

    /**
     * VIEW 递的是链接本身;SEND 递的是一段话,链接埋在里面
     * (「【标题】 https://b23.tv/xxx 复制这段内容…」)。
     */
    private fun linkFrom(intent: Intent?): String? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.dataString
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(BilbyLink::extractUrl)
        else -> null
    }
}

@Composable
private fun StartupUpdateHost(container: AppContainer) {
    val context = LocalContext.current
    val vm: StartupUpdateViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                StartupUpdateViewModel(
                    updateRepository = container.updateRepository,
                    settings = container.settings,
                    downloadDir = UpdateInstaller.downloadDir(context),
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    StartupUpdateDialog(
        state = state,
        onDownload = vm::download,
        onInstall = { apk -> UpdateInstaller.install(context, apk) },
        onIgnore = vm::ignore,
        onDismiss = vm::dismiss,
    )
}

@Composable
private fun BilbyApp(container: AppContainer, incomingLink: MutableStateFlow<String?>) {
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

    val reducedMotion = rememberReducedMotion()
    val backStack = rememberNavBackStack(Home)

    // 新版本提示。挂在这一层而不是首页里:它和用户此刻在哪一页无关,而首页会随 tab 切换
    // 离开组合 —— 挂在那儿的话,开屏正好停在别的 tab 上就永远不弹。
    // **登录之后才挂**:上面那道 return 挡着,登录页不该被一个更新弹窗盖住。
    StartupUpdateHost(container)

    /**
     * 外面递进来的链接。短链要先展开一次才知道指向哪儿,所以这一段可能要走一次网络。
     *
     * **认不出来就什么都不做**,不吐错误也不跳首页:用户是从别的应用点过来的,这条链接可能
     * 是番剧(Non-Goal),此刻弹一句"打不开"帮不上任何忙,而应用停在原处至少没有骗人。
     * 消费掉之后把值清空,免得转屏或返回前台时再压一次同一页。
     */
    LaunchedEffect(Unit) {
        incomingLink.collect { raw ->
            val link = raw ?: return@collect
            incomingLink.value = null
            val resolved = if (BilbyLink.isShortLink(link)) {
                runCatching { container.biliClient.resolveRedirect(link) }
                    .onFailure { BiliLog.w("短链展开失败", it) }
                    .getOrNull() ?: return@collect
            } else {
                link
            }
            val destination = BilbyLink.destinationOf(resolved)
            if (destination == null) {
                BiliLog.w("认不出的链接,忽略")
                return@collect
            }
            // 压栈而不是替换:从别的应用进来时栈里只有 Home,返回该回到首页而不是退出应用。
            if (backStack.lastOrNull() != destination) backStack.add(destination)
        }
    }

    /**
     * 播放器与队列的生命周期到此为止:**backstack 上再没有播放页,就把播放器销毁。**
     *
     * 队列从第一个播放页打开时建立,中途从空间页或「找相关」打开别的视频只是重做队列;
     * 一路返回到没有播放页了,这次播放才算结束。
     *
     * 判据是"还有没有播放页"这个纯粹的集合判断,不是"这一页是被弹出还是被覆盖" ——
     * 后者是导航层的判断,CLAUDE.md 记着它被做错过。
     *
     * 划走 app 不走这里:那时整个 composition 都没了,effect 不会跑。**退到后台要不要暂停
     * 是另一件事**,由 `MainActivity.onStop` 交给服务判断(见
     * [AudioPlaybackService.pauseForAppBackground]):听视频继续,看视频暂停。划掉任务卡片
     * 又是第三件事,归 `onTaskRemoved`。
     *
     * 转屏不受影响:manifest 声明了 configChanges,Activity 不重建,onStop 不会跑。
     */
    val context = LocalContext.current
    val hasVideoPage = backStack.any { it is Video }
    LaunchedEffect(hasVideoPage) {
        if (!hasVideoPage) AudioPlaybackService.stop(context)
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        // **每个 NavEntry 一个 ViewModelStore。** 默认的 entryDecorators 只有
        // SaveableStateHolder 一个(反编译 navigation3-ui 1.1.5 核实过),于是 `viewModel()`
        // 落到 Activity 的 store 上,所有页面的 ViewModel 都活到 Activity 销毁为止 ——
        // 播放页尤其明显:队列自动连播时 key 是 "video-$episode",走一条攒一个。
        //
        // **清理挂在弹出上,不是离开组合上。** `NavEntryDecorator` 的 onPop 和 decorate 是
        // 两个独立的东西,这个装饰器把 clearKey 挂在前者。所以压播放页时 Home 只是被盖住、
        // 仍在 backstack,它的 store 不动 —— 助理循环跑在 searchVm 的 viewModelScope 上,
        // 而 searchVm 就在 Home 这个 entry 里,切 tab 和压页面都打断不了它,内存里的
        // 结果也不会被清掉。只有 Home 真的出栈(退出应用)才收摊。
        //
        // 加它就得连默认那个一起写全:这个参数是整体替换,漏掉 SaveableStateHolder 会让
        // 所有 rememberSaveable 跟着失效。
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        // **Forward and backward**,取 Android 平台默认。transitions 页原文:
        // "**Android** uses a fade as screens slide. This reduces the amount of motion, since the
        // screens don't have to slide the full width of the device.",选型那一节又说
        // "Both Android and iOS should use platform defaults for forward and backward navigation."
        //
        // 两页都只走五分之一屏:走满整屏是 lateral 的做法,而规范明说别拿 lateral 做层级导航
        // ——"Sliding content the full width of the screen is excessive for a high frequency
        // transition. It also implies an equal peer relationship which isn't accurate."
        //
        // 时长与缓动都在 `theme/Motion.kt`,那里逐条标了 token 名。
        //
        // 系统开了"减弱动效"时退成纯淡入淡出:规范第一条就是
        // "Use subtle fades instead of intense sliding or scaling animations"。
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(Motion.ForwardEnterFade) togetherWith fadeOut(Motion.ForwardExitFade)
            } else {
                (
                    slideInHorizontally(Motion.ForwardEnterSlide) { it / Motion.ForwardSlideFraction } +
                        fadeIn(Motion.ForwardEnterFade)
                    ) togetherWith (
                    slideOutHorizontally(Motion.ForwardExitSlide) { -it / Motion.ForwardSlideFraction } +
                        fadeOut(Motion.ForwardExitFade)
                    )
            }
        },
        popTransitionSpec = {
            if (reducedMotion) {
                fadeIn(Motion.ForwardEnterFade) togetherWith fadeOut(Motion.ForwardExitFade)
            } else {
                (
                    slideInHorizontally(Motion.ForwardEnterSlide) { -it / Motion.ForwardSlideFraction } +
                        fadeIn(Motion.ForwardEnterFade)
                    ) togetherWith (
                    slideOutHorizontally(Motion.ForwardExitSlide) { it / Motion.ForwardSlideFraction } +
                        fadeOut(Motion.ForwardExitFade)
                    )
            }
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
                CutoutSafe {
                    RootTabs(
                        container = container,
                        onVideoClick = { backStack.add(Video(it)) },
                        onUserClick = { backStack.add(Space(it)) },
                        onLiveClick = { backStack.add(LiveRoom(it)) },
                        onSettingsClick = { backStack.add(Settings) },
                        onOpenFollowings = { backStack.add(Followings) },
                        onOpenHistory = { backStack.add(History) },
                        onOpenToView = { backStack.add(ToViewList) },
                        onOpenOffline = { backStack.add(Offline) },
                        onOpenFavFolder = { folder ->
                            backStack.add(FavFolderContents(folder.id, folder.title))
                        },
                    )
                }
            }
            entry<Settings> {
                CutoutSafe { SettingsRoute(container, onBack = { backStack.removeLastOrNull() }) }
            }
            entry<History> {
                CutoutSafe {
                    HistoryRoute(
                        container = container,
                        onVideoClick = { backStack.add(Video(it)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<Video> { key ->
                VideoRoute(
                    container = container,
                    bvid = key.bvid,
                    startListening = key.listening,
                    cid = key.cid,
                    onUpClick = { backStack.add(Space(it)) },
                    onBack = { backStack.removeLastOrNull() },
                    // 切集是**重组,不是压栈**:换的是这一页在放哪一条,不是又进了一层。
                    // 压栈的话看五集就攒五层,返回要一集一集退回去,而合集本来是一个有限集合、
                    // 用户是在里面平移。替换栈顶之后,从任何一集返回都回到进来时的地方。
                    //
                    // 今天那个"点下一集不自动播放"的 bug 也出在这里:压栈时旧页的 onDispose
                    // 在新页起播之后才跑,把刚起播的下一集暂停了。替换栈顶让这个错位不成立。
                    onOpenVideo = { backStack[backStack.lastIndex] = Video(it) },
                )
            }
            entry<Offline> {
                CutoutSafe {
                    OfflineRoute(
                        container = container,
                        // 带上 cid:缓存列表里一条就是一个分 P,点哪一条就该播哪一条。
                        onPlay = { item -> backStack.add(Video(item.bvid, cid = item.cid)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<ToViewList> {
                CutoutSafe {
                    ToViewListRoute(
                        container = container,
                        onVideoClick = { backStack.add(Video(it)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<FavFolderContents> { key ->
                CutoutSafe {
                    FavFolderRoute(
                        container = container,
                        mediaId = key.mediaId,
                        title = key.title,
                        onVideoClick = { backStack.add(Video(it)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<Followings> {
                CutoutSafe {
                    FollowingsRoute(
                        container = container,
                        onUpClick = { backStack.add(Space(it)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<LiveRoom> { key ->
                LiveRoomRoute(
                    container,
                    key.roomId,
                    onBack = { backStack.removeLastOrNull() },
                )
            }
            entry<Space> { key ->
                CutoutSafe {
                    SpaceRoute(
                        container,
                        key.mid,
                        onVideoClick = { backStack.add(Video(it)) },
                        onListenUp = { backStack.add(Video(it, listening = true)) },
                        onLiveClick = { backStack.add(LiveRoom(it)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
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
    Profile(R.string.tab_profile, Icons.Filled.Person, Icons.Outlined.Person),
}

/**
 * 横向挖孔在这一层统一躲开,普通页面自己不用管。
 *
 * `Scaffold` 消费的是 `systemBarsForVisualComponents`,里面没有 `displayCutout`;而
 * targetSdk 35 之后窗口不再被系统让开挖孔那一条,横屏时正文左右会被切掉一块。放在这里而不是
 * 每个 Route 里,是因为漏写要转屏跑到那一页才看得见 —— 少一个目的地就少一处适配。
 *
 * **播放页和直播间不套。** 那两页的画面要铺满挖孔(全屏时尤其),躲开的是浮在画面上的按钮,
 * 由它们各自用 [dev.bilby.ui.barsAndCutout] 处理。
 */
@Composable
private fun CutoutSafe(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.horizontalCutout)) {
        content()
    }
}

/**
 * 三个 tab 都是显式入口:刷更新、搜索、看自己存的。没有"随便看看"那一格
 * (DESIGN 1.1 的推送式入口那一栏)。三格正好落在 M3 导航栏 3–5 个目的地的下限上。
 *
 * 三个 pane 的 ViewModel 提到这一层。它们本来就常驻(`viewModel()` 挂在 Activity 的
 * ViewModelStore 上,切 tab 不会销毁),提上来是为了让底栏切换时状态不丢——不提上来就得
 * 把 composable 塞进 state 往上传,那样重组作用域会乱。
 *
 * **没有顶栏。** 三个 tab 都不再有共用的 `BilbyTopBar`,理由和状态栏 inset 的处理见下面
 * `Scaffold` 调用处的注释。
 */
@Composable
private fun RootTabs(
    container: AppContainer,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onLiveClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onOpenFollowings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenToView: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenFavFolder: (FavFolder) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(RootTab.Feed) }
    val windowSize = rememberBilbyWindowSize()

    // IME 退让放在 Scaffold 这一层,让底栏跟着键盘一起上移。放在内层输入框上的话,
    // 底栏仍会在键盘下方占着高度,表现为输入框与键盘之间空一条。
    //
    // **三个根 tab 没有顶栏。** 标题以前和底栏标签逐字重复——底栏已经有标签 + 选中指示器,
    // 顶栏再写一遍是纯占位;M3 对 top app bar 的定义是"显示信息与操作",标题和操作都没有时
    // 它就是一段死高度(动态页、搜索页的普通模式正是这种情况)。真正的页级操作(设置齿轮、
    // 搜索助理模式的"新会话")现在各自长在 `ProfileScreen`/`SearchChatScreen` 自己的内容里,
    // 不再借用顶栏容器。子页面(播放页、个人空间、历史记录……)不受影响,那些标题是真信息
    // (你在哪个收藏夹里),而且没有底栏兜底"这是哪一页"。
    //
    // 状态栏 inset 没有跟着顶栏一起丢:`ScaffoldDefaults.contentWindowInsets` 默认就是
    // `WindowInsets.systemBarsForVisualComponents`(读 material3 1.5.0-alpha25 的 aar 核实过,
    // 不是照文档假设),顶栏不存在时这份 inset 不会被谁消费掉,照样流进下面 `insets` 参数,
    // 下面内容区那行 `.padding(top = insets.calculateTopPadding())` 不用改。
    if (windowSize == BilbyWindowSize.Compact) {
        Scaffold(
            modifier = Modifier.imePadding(),
            bottomBar = {
                NavigationBar {
                    RootTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab,
                            onClick = { selected = tab },
                            icon = { RootTabIcon(tab, selected == tab) },
                            label = { RootTabLabel(tab) },
                        )
                    }
                }
            },
        ) { insets ->
            RootTabsContent(
                insets = insets,
                selected = selected,
                container = container,
                onVideoClick = onVideoClick,
                onUserClick = onUserClick,
                onLiveClick = onLiveClick,
                onSettingsClick = onSettingsClick,
                onOpenFollowings = onOpenFollowings,
                onOpenHistory = onOpenHistory,
                onOpenToView = onOpenToView,
                onOpenOffline = onOpenOffline,
                onOpenFavFolder = onOpenFavFolder,
            )
        }
    } else {
        // medium 起用 rail 换掉底栏:导航贴在边缘,内容拿到完整的横向空间。三个根目的地
        // 不需要 drawer —— M3 的 navigation rail 页把 rail 作为 drawer 的优先替代。
        Row(modifier = Modifier.fillMaxSize().imePadding()) {
            NavigationRail(modifier = Modifier.fillMaxHeight()) {
                RootTab.entries.forEach { tab ->
                    NavigationRailItem(
                        selected = selected == tab,
                        onClick = { selected = tab },
                        icon = { RootTabIcon(tab, selected == tab) },
                        label = { RootTabLabel(tab) },
                    )
                }
            }
            Scaffold(modifier = Modifier.weight(1f)) { insets ->
                RootTabsContent(
                    insets = insets,
                    selected = selected,
                    container = container,
                    onVideoClick = onVideoClick,
                    onUserClick = onUserClick,
                    onLiveClick = onLiveClick,
                    onSettingsClick = onSettingsClick,
                    onOpenFollowings = onOpenFollowings,
                    onOpenHistory = onOpenHistory,
                    onOpenToView = onOpenToView,
                    onOpenOffline = onOpenOffline,
                    onOpenFavFolder = onOpenFavFolder,
                )
            }
        }
    }
}

/**
 * 底栏和 rail 共用同一份图标与标签规则,不各写一份 —— 两处分头维护的话,选中态用实心图标
 * 这条迟早只剩一处成立。
 */
@Composable
private fun RootTabIcon(tab: RootTab, selected: Boolean) {
    Icon(
        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
        // 标签就在图标旁边,读屏再念一遍图标等于每格念两次。
        contentDescription = null,
    )
}

@Composable
private fun RootTabLabel(tab: RootTab) {
    Text(stringResource(tab.label))
}

@Composable
private fun RootTabsContent(
    insets: PaddingValues,
    selected: RootTab,
    container: AppContainer,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onLiveClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onOpenFollowings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenToView: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenFavFolder: (FavFolder) -> Unit,
) {
    // 只 padding 不声明消费的话,子层的 imePadding() 会再多退让一个底栏高度。
    val bottom = PaddingValues(bottom = insets.calculateBottomPadding())
    AdaptiveContent(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = insets.calculateTopPadding())
            .padding(bottom)
            .consumeWindowInsets(bottom),
        maxWidth = Breakpoints.ReadableWidth,
    ) {
        // **Top level**:点底栏或 rail 换根目的地。规范原文
        // "The exiting screen quickly fades out **and then** the entering screen fades in. Since the
        // content of top level destinations isn't necessarily related, the motion intentionally does
        // not use grouping or persistent elements";clean fades 那条又要求
        // "Fully fade out content before fading new content in."
        //
        // 所以是淡出走完再淡入(spec 里进入那一档带着 delay),不是交叉淡化,更不是原来那种
        // 跳切 —— 跳切被单列为要避免的默认做法:"Instantly transitioning from one screen to the
        // next offers no clues to help a user orient themselves."
        //
        // **不用 lateral(横滑)**:"A lateral transition pattern is not recommended for this type of
        // navigation. It implies you can swipe between top level destinations which conflicts with
        // other components like carousels or swipe-able list items." 这个应用里正好有那两样。
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                fadeIn(Motion.TopLevelEnterFade) togetherWith fadeOut(Motion.TopLevelExitFade)
            },
            label = "rootTab",
        ) { tab ->
            when (tab) {
                RootTab.Feed -> FeedPane(
                    container = container,
                    onVideoClick = onVideoClick,
                    onUserClick = onUserClick,
                    onLiveClick = onLiveClick,
                    onOpenFollowings = onOpenFollowings,
                )

                RootTab.Search -> SearchPane(container, onVideoClick, onUserClick)

                RootTab.Profile -> ProfilePane(
                    container = container,
                    onVideoClick = onVideoClick,
                    onUserClick = onUserClick,
                    onOpenHistory = onOpenHistory,
                    onOpenToView = onOpenToView,
                    onOpenOffline = onOpenOffline,
                    onOpenFavFolder = onOpenFavFolder,
                    onSettingsClick = onSettingsClick,
                )
            }
        }
    }
}

/*
 * 三个根 pane 各自建自己的 ViewModel,**没被选中过的 tab 不建、也就不发请求**。
 *
 * 原先三个 VM 都在 NavDisplay 外面无条件创建:冷启动停在动态页时,个人页那四组请求(账号、
 * 历史、稍后再看、收藏夹)照样打出去,跟动态页自己的两组抢冷启动那几百毫秒,而用户一眼都
 * 没看见它们。
 *
 * **"建过就留着"由宿主保证,不是靠这里。** 这个 NavDisplay 没装 NavEntry 级的
 * ViewModelStore 装饰器(1.1.5 的 entryDecorators 默认值只有 SaveableStateHolder 一个,
 * 反编译核对过),`viewModel()` 因此落在 Activity 的 store 上。切走 tab 只是 composable
 * 离开组合,实例和 viewModelScope 都还在 —— 搜索助理的循环跑在 searchVm 的 scope 上,
 * 切 tab、压播放页都不打断它,这一点和重构前一样。
 *
 * 状态收集跟着下沉:三份 state 以前都在 RootTabs 顶层收,任何一个更新都要重组整个 RootTabs
 * (含底栏),现在各收各的。
 */
@Composable
private fun FeedPane(
    container: AppContainer,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onLiveClick: (Long) -> Unit,
    onOpenFollowings: () -> Unit,
) {
    val vm: FeedViewModel = viewModel(
        key = "root-feed",
        factory = viewModelFactory {
            initializer {
                FeedViewModel(
                    container.dynamicRepository,
                    container.followRepository,
                    container.settings,
                    container.feedReadPositionRepository,
                    container.feedCacheRepository,
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    FeedScreen(
        state = state,
        onLoadMore = vm::loadMore,
        onRetry = vm::loadFirstPage,
        onRefresh = vm::refresh,
        onItemClick = { onVideoClick(it.bvid) },
        onUpClick = onUserClick,
        onLiveClick = onLiveClick,
        onExcludeUp = vm::excludeUp,
        onOpenFollowings = onOpenFollowings,
        onScrollPositionChanged = vm::onVisibleTopChanged,
        onLocated = vm::onLocated,
    )
}

@Composable
private fun SearchPane(
    container: AppContainer,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
) {
    val vm: SearchChatViewModel = viewModel(
        key = "root-search",
        factory = viewModelFactory {
            initializer { SearchChatViewModel(container.searchRepository, container.agentLoop, container.settings) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val history by vm.searchHistory.collectAsStateWithLifecycle()
    SearchChatScreen(
        state = state,
        searchHistory = history,
        onHistoryClick = vm::searchFromHistory,
        onHistoryRemove = vm::removeSearchHistory,
        onInputChange = vm::onInputChange,
        onModeChange = vm::onModeChange,
        onOrderChange = vm::onOrderChanged,
        onSend = vm::send,
        onNewSession = vm::newSession,
        onVideoClick = onVideoClick,
        onUserClick = onUserClick,
        onLoadMore = vm::loadMore,
        onRetry = vm::retry,
        onRefresh = vm::refresh,
    )
}

@Composable
private fun ProfilePane(
    container: AppContainer,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenToView: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenFavFolder: (FavFolder) -> Unit,
    onSettingsClick: () -> Unit,
) {
    val vm: ProfileViewModel = viewModel(
        key = "root-profile",
        factory = viewModelFactory {
            initializer {
                ProfileViewModel(
                    container.settings,
                    container.accountRepository,
                    container.historyRepository,
                    container.toViewRepository,
                    container.favRepository,
                    container.offlineDownloader,
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 重新进入这一页时补一次概览。挂在"进组合"上而不是底栏那次点击上:从历史记录、
    // 稍后再看这些子页面返回时 Home 整个重新进组合,而那正是概览最可能已经过期的时候
    // (用户刚在那边删了东西),底栏的 onClick 覆盖不到这条路。
    //
    // 首次进入由 ViewModel 的 init 负责,refreshIfStale 会因为刚拉过而跳过。
    LaunchedEffect(Unit) { vm.refreshIfStale() }

    ProfileScreen(
        state = state,
        onVideoClick = onVideoClick,
        onOpenHistory = onOpenHistory,
        onOpenToView = onOpenToView,
        onOpenOffline = onOpenOffline,
        onOpenFavFolder = onOpenFavFolder,
        onSettingsClick = onSettingsClick,
        onOpenSelf = onUserClick,
        onRetryAccount = vm::retryAccount,
        onRetryHistory = vm::retryHistory,
        onRetryToView = vm::retryToView,
        onRetryFavFolders = vm::retryFavFolders,
    )
}

@Composable
private fun SettingsRoute(container: AppContainer, onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    container.settings,
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
        onFastForwardSpeedChange = vm::setFastForwardSpeed,
        onDanmakuOpacityChange = vm::setDanmakuOpacity,
        onDanmakuScrollShowAreaChange = vm::setDanmakuScrollShowArea,
        onDanmakuDensityChange = vm::setDanmakuDensity,
        onDanmakuFrameRateChange = vm::setDanmakuFrameRate,
        onSponsorBlockChange = vm::updateSponsorBlock,
        onOfflineConcurrencyChange = vm::setOfflineConcurrency,
        onClearExcludedFeed = vm::clearExcludedFeedMids,
        onOpenGithub = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_GITHUB_URL)))
        },
        onCheckUpdate = vm::checkUpdate,
        onDownloadUpdate = { vm.downloadUpdate(it, UpdateInstaller.downloadDir(context)) },
        onInstallUpdate = { UpdateInstaller.install(context, it) },
        // 停播放服务要 Context,所以由这一层做;顺序是先清凭据后停服务 —— 反过来的话
        // 中间那一瞬服务已停而凭据还在,看起来像"没登出但停了"。
        onLogout = { vm.logout { AudioPlaybackService.stop(context) } },
        onBack = onBack,
    )
}

/**
 * 历史记录。「我的」页的三个入口之一,独立成页且只待在这一页(DESIGN 2 节)——
 * 不喂给别的界面,没有"继续观看"式的跳转。
 */
@Composable
private fun HistoryRoute(
    container: AppContainer,
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: HistoryViewModel = viewModel(
        factory = viewModelFactory { initializer { HistoryViewModel(container.historyRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { BilbyTopBar(title = stringResource(R.string.history_title), onBack = onBack) }) { insets ->
        HistoryScreen(
            state = state,
            onItemClick = { onVideoClick(it.bvid) },
            onLoadMore = vm::loadMore,
            onRetry = vm::retry,
            onRefresh = vm::refresh,
            contentPadding = insets,
        )
    }
}

/**
 * 已缓存的视频。点一条走的是普通的播放页 —— **离线不是另一种播放**,服务在取流之前会先看
 * 这一条有没有缓存,命中就用本地文件(见 `AudioPlaybackService.playCurrent`)。所以这里不需要
 * 一个"离线播放器",也不需要给播放页传什么标记。
 */
@Composable
private fun OfflineRoute(
    container: AppContainer,
    onPlay: (OfflineItem) -> Unit,
    onBack: () -> Unit,
) {
    val vm: OfflineViewModel = viewModel(
        factory = viewModelFactory {
            initializer { OfflineViewModel(container.offlineDownloader, container.offlineStore) }
        },
    )
    val items by vm.items.collectAsStateWithLifecycle()
    val usedBytes by vm.usedBytes.collectAsStateWithLifecycle()

    // 多选态就是"选中集合非空",不另设一个布尔 —— 两者永远同真同假,而分成两个状态之后
    // "空集合 + 还在多选态"是个画得出来、退不出去的组合。
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmingBatchDelete by remember { mutableStateOf(false) }
    val selected = items.filter { it.id in selectedIds }
    // 列表里已经没有的 id 要跟着掉:删完之后集合里留着几个死 id,顶栏就一直显示"已选 3 项"。
    LaunchedEffect(items) { selectedIds = selectedIds intersect items.map { it.id }.toSet() }
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }

    Scaffold(
        topBar = {
            if (selectedIds.isEmpty()) {
                BilbyTopBar(title = stringResource(R.string.offline_title), onBack = onBack)
            } else {
                // 多选时整条顶栏换掉,返回箭头改成"退出多选"。这是 M3 的 contextual top app bar:
                // 顶栏是当前上下文里能做什么的唯一说明,多选期间那个上下文变了。
                BilbyTopBar(
                    title = stringResource(R.string.offline_selected_count, selectedIds.size),
                    onBack = { selectedIds = emptySet() },
                    actions = {
                        val all = items.map { it.id }.toSet()
                        IconButton(onClick = { selectedIds = if (selectedIds == all) emptySet() else all }) {
                            Icon(
                                Icons.Outlined.SelectAll,
                                contentDescription = stringResource(R.string.offline_sheet_select_all),
                            )
                        }
                        IconButton(
                            onClick = {
                                // 一个字节都还没下的那些直接删,理由同单条(见 OfflineScreen)。
                                if (selected.all { it.status == OfflineStatus.Queued }) {
                                    vm.deleteAll(selected)
                                    selectedIds = emptySet()
                                } else {
                                    confirmingBatchDelete = true
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
                    },
                )
            }
        },
    ) { insets ->
        OfflineScreen(
            items = items,
            usedBytes = usedBytes,
            onPlay = onPlay,
            onDelete = vm::delete,
            onRetry = vm::retry,
            selectedIds = selectedIds,
            onToggleSelection = { item ->
                selectedIds = if (item.id in selectedIds) selectedIds - item.id else selectedIds + item.id
            },
            contentPadding = insets,
        )
    }

    if (confirmingBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmingBatchDelete = false },
            title = { Text(stringResource(R.string.offline_delete_batch_title)) },
            text = { Text(stringResource(R.string.offline_delete_batch_message, selected.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingBatchDelete = false
                    vm.deleteAll(selected)
                    selectedIds = emptySet()
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingBatchDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
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
    container: AppContainer,
    onVideoClick: (String) -> Unit,
    onBack: () -> Unit,
) {
    val vm: ToViewViewModel = viewModel(
        factory = viewModelFactory { initializer { ToViewViewModel(container.toViewRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            BilbyTopBar(title = stringResource(R.string.tab_toview), onBack = onBack) {
                IconButton(
                    onClick = vm::clearFinished,
                    enabled = !state.clearing && state.items.any { it.isFinished },
                ) {
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
                onRefresh = vm::refresh,
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
            onRefresh = vm::refresh,
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
            onRefresh = vm::refresh,
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
    onLiveClick: (Long) -> Unit,
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
        onLiveClick = onLiveClick,
        onToggleFollow = vm::toggleFollow,
        // 听这位 UP 的投稿:挑第一条进播放页并直接以听的状态打开。**宿主只有播放页一个** ——
        // 空间页不承载听视频界面,否则又会多出一处需要单独维护的生命周期。
        onListenUp = {
            state.archives.items.firstOrNull()?.let { onListenUp(it.bvid) }
        },
        onBack = onBack,
        onRetry = vm::retry,
        mid = mid,
        onRefresh = vm::refresh,
    )
}

@Composable
private fun VideoRoute(
    container: AppContainer,
    bvid: String,
    startListening: Boolean = false,
    /** 导航指名的那一 P(只有缓存列表会给)。0 = 不指名,由详情/观看记录决定。 */
    cid: Long = 0,
    onUpClick: (Long) -> Unit,
    onOpenVideo: (String) -> Unit,
    onBack: () -> Unit,
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

    /**
     * **但要等队列先追上这一条,才开始跟着它走。**
     *
     * 刚点开一条新视频时,服务还在放上一条 —— 队列要重建、取流、prepare,这中间有一段
     * 几百毫秒到几秒的窗口,`audioState.queue?.current` 报的仍是**上一条**。此时若无条件跟随,
     * 这一句会立刻把 `episode` 从刚请求的 bvid 拽回旧的那条,于是"打开新播放页却在放老视频";
     * 等新的真正开播,又翻回来 —— 用户看到的是页面自己跳了两次。
     *
     * 所以先不跟:直到队列**报出这一条自己**(说明播放确实切过来了)才放行。之后的连播、
     * 通知栏切歌、耳机线控就都能正常带着页面走。
     *
     * 代价是这一条始终没播成(取流失败)时页面不再跟队列 —— 那反而是对的:此刻页面该停在
     * 用户点开的那一条上显示失败,而不是悄悄变成另一条视频的详情。
     */
    var followingQueue by remember(bvid) { mutableStateOf(false) }
    LaunchedEffect(audioState.queue?.current?.bvid) {
        val target = audioState.queue?.current?.bvid ?: return@LaunchedEffect
        if (!followingQueue) {
            if (target == bvid) followingQueue = true
            return@LaunchedEffect
        }
        if (target != episode) episode = target
    }

    VideoPane(
        container = container,
        bvid = episode,
        // **切集之后就不再是那一 P 了。** episode 会因为切集/自动连播变成别的视频,而 cid 是
        // 进这一页时那一条的分 P;不夹这一道的话,bvid 与 cid 会分属两条视频。
        cid = if (episode == bvid) cid else 0L,
        listening = listening,
        onListeningChange = { listening = it },
        onUpClick = onUpClick,
        onOpenVideo = onOpenVideo,
        onBack = onBack,
    )
}

@Composable
private fun VideoPane(
    container: AppContainer,
    bvid: String,
    cid: Long = 0,
    listening: Boolean,
    onListeningChange: (Boolean) -> Unit,
    onUpClick: (Long) -> Unit,
    onOpenVideo: (String) -> Unit,
    onBack: () -> Unit,
) {
    // **key 与 bvid 无关,一个播放页只有一个 VideoViewModel。**
    //
    // 原先是 `key = "video-$bvid"`:切集不进 backstack(见 VideoRoute),所以这个 NavEntry 的
    // ViewModelStore 在整页出栈之前一直不清 —— key 一变只是**选**了个新实例,旧实例还在 store
    // 里活着,连播走一条就攒一个,每个都挂着自己那份 AudioPlaybackService.state 的 collect。
    // Compose 的 key 决定选哪个实例,它不负责删掉旧 key 对应的那个。
    //
    // 换视频改由 VideoViewModel.switchTo 表达,它是幂等的,所以下面那句 LaunchedEffect 无脑
    // 喊即可。多个播放页同时在 backstack 上时互不干扰:每个 NavEntry 有独立的 store,
    // 常量 key 在各自的 store 里各是各的。
    val vm: VideoViewModel = viewModel(
        key = "video",
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
                    container.danmakuRepository,
                    container.offlineDownloader,
                    container.offlineStore,
                )
            }
        },
    )
    // 幂等,所以不需要在这里记"上一次是哪条"——那份状态正是以前散在 Compose 层、却管不了
    // 旧实例死活的东西。
    LaunchedEffect(bvid) { vm.switchTo(bvid) }
    val state by vm.state.collectAsStateWithLifecycle()
    val related by vm.related.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val relation by vm.relation.collectAsStateWithLifecycle()
    val coinAttempt by vm.coinAttempt.collectAsStateWithLifecycle()
    val favFolders by vm.favFolders.collectAsStateWithLifecycle()
    val sponsorSegments by vm.sponsorSegments.collectAsStateWithLifecycle()
    val followState by vm.followState.collectAsStateWithLifecycle()
    val upCard by vm.upCard.collectAsStateWithLifecycle()
    val addedToView by vm.addedToView.collectAsStateWithLifecycle()
    val subtitleTracks by vm.subtitleTracks.collectAsStateWithLifecycle()
    val subtitleLan by vm.subtitleLan.collectAsStateWithLifecycle()
    val subtitleCues by vm.subtitleCues.collectAsStateWithLifecycle()
    val danmakuPrefs by vm.danmakuPrefs.collectAsStateWithLifecycle()
    val danmakuPool by vm.danmakuPool.collectAsStateWithLifecycle()
    val cachedBvids by vm.cachedBvids.collectAsStateWithLifecycle()
    val specialDanmakuPool by vm.specialDanmakuPool.collectAsStateWithLifecycle()
    val staffFollowed by vm.staffFollowed.collectAsStateWithLifecycle()
    val playerPrefs by container.settings.playerPrefs.collectAsStateWithLifecycle(
        initialValue = PlayerPrefs(),
    )

    // 评论用 aid 作 oid,要等视频详情回来才知道 —— 但**不能拿它卡住整页**。
    //
    // 原先这里是 `val aid = state.detail?.aid ?: return`:详情没回来就整页不画。切集时新页
    // 要等一次网络往返才有东西,那段时间屏幕上是空的。SoT 之后这个等待已经没必要了 ——
    // 标题、UP 名、封面队列项里就有,播放器的画面更是早就在放了。
    // key 与 aid 无关,理由同上面的 VideoViewModel:切集不进 backstack,按 aid 分 key 只会在
    // 同一个 store 里越攒越多。换视频由 switchTo 表达。
    val commentVm: CommentViewModel? = state.detail?.aid?.let { aid ->
        val commentViewModel: CommentViewModel = viewModel(
            key = "comment",
            factory = viewModelFactory { initializer { CommentViewModel(container.commentRepository, aid) } },
        )
        LaunchedEffect(aid) { commentViewModel.switchTo(aid) }
        commentViewModel
    }
    val commentState = commentVm?.state?.collectAsStateWithLifecycle()?.value ?: CommentUiState()

    VideoScreen(
        bvid = bvid,
        cid = cid,
        state = state,
        related = related,
        commentState = commentState,
        sponsorSegments = sponsorSegments,
        // 进度只回传服务端一份(DESIGN 7)。本地不再另存:续播位置和流地址来自同一个 playurl
        // 响应,本地那份换不到任何东西,却是"新页写下上一条进度"这类串味的唯一入口。
        onReportProgress = { position, duration ->
            vm.reportHeartbeat(position, duration, finished = duration > 0 && position >= duration - 1_000)
            // 弹幕分段拉取挂在这条已有的回调上,不为它另起一条轮询——见 VideoViewModel 的注释。
            vm.onDanmakuPlaybackPosition(position)
        },
        onFindRelated = vm::findRelated,
        cachedBvids = cachedBvids,
        onCacheSelection = vm::cacheSelection,
        onUpClick = onUpClick,
        followState = followState,
        onToggleFollow = vm::toggleFollow,
        upCard = upCard,
        relation = relation,
        favFolders = favFolders,
        onLike = vm::toggleLike,
        addedToView = addedToView,
        onAddToView = vm::addToView,
        onCoin = vm::coin,
        coinAttempt = coinAttempt,
        onCoinDialogClosed = vm::clearCoinAttempt,
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
        onBack = onBack,
        onRetry = vm::retry,
        listening = listening,
        onListeningChange = onListeningChange,
        subtitleTracks = subtitleTracks,
        subtitleLan = subtitleLan,
        subtitleCues = subtitleCues,
        onSelectSubtitle = vm::selectSubtitle,
        staffFollowed = staffFollowed,
        onFollowStaff = vm::followStaff,
        danmakuPrefs = danmakuPrefs,
        onDanmakuEnabledChange = vm::setDanmakuEnabled,
        danmakuPool = danmakuPool,
        specialDanmakuPool = specialDanmakuPool,
        fastForwardSpeed = playerPrefs.fastForwardSpeed,
    )
}

/**
 * lateral(同级平移)没有在这里定义 spec:它由 `HorizontalPager` 自己承担,而规范要的正是
 * pager 的默认行为 —— "elements are grouped and slide in unison",并且**不加淡入淡出**
 * ("Fading content as it slides makes the peer relationship and swipe gesture less obvious")。
 */
