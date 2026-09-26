package dev.bilby.ui

import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.PointerSource
import dev.bilby.ui.components.trackPointerSource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Forum
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenLoading
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.bilby.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bilby.data.AppearancePrefs
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.bilby.AppContainer
import dev.bilby.AppContainerOwner
import dev.bilby.BiliLog
import dev.bilby.resources.*
import org.jetbrains.compose.resources.StringResource
import dev.bilby.agent.AgentIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import dev.bilby.ui.article.ArticleScreen
import dev.bilby.ui.article.ArticleViewModel
import dev.bilby.ui.dynamic.DynamicAction
import dev.bilby.ui.dynamic.DynamicDetailScreen
import dev.bilby.ui.dynamic.DynamicDetailViewModel
import dev.bilby.ui.dynamic.OtherDynamicsScreen
import dev.bilby.ui.dynamic.OtherDynamicsViewModel
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.BvidCodec
import dev.bilby.ui.comment.CommentThreadRoute
import dev.bilby.ui.comment.CommentUiState
import dev.bilby.ui.comment.CommentViewModel
import dev.bilby.data.FavFolderDetail
import dev.bilby.data.PlayerPrefs
import dev.bilby.data.FavRepository
import dev.bilby.data.QueueContext
import dev.bilby.data.SpaceRepository
import dev.bilby.ui.space.queueContext
import dev.bilby.data.QueueSource
import dev.bilby.offline.OfflineItem
import dev.bilby.offline.OfflineStatus
import androidx.compose.material.icons.outlined.Add
import dev.bilby.data.FavVideo
import dev.bilby.ui.fav.FavFolderTopBar
import dev.bilby.ui.fav.FavFolderScreen
import dev.bilby.ui.fav.FavFolderViewModel
import dev.bilby.ui.fav.FavFoldersScreen
import dev.bilby.ui.fav.FavFoldersViewModel
import dev.bilby.ui.feed.FeedScreen
import dev.bilby.ui.feed.FeedViewModel
import dev.bilby.ui.follow.BlacklistScreen
import dev.bilby.ui.follow.BlacklistViewModel
import dev.bilby.ui.follow.FollowingsScreen
import dev.bilby.ui.follow.FollowingsSearchField
import dev.bilby.ui.follow.FollowingsSearchFieldMaxWidth
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import dev.bilby.ui.follow.FollowOrderOptions
import dev.bilby.ui.follow.canSort
import dev.bilby.ui.components.SortMenu
import dev.bilby.ui.follow.FollowingsViewModel
import dev.bilby.ui.history.HistoryScreen
import dev.bilby.ui.history.HistoryViewModel
import dev.bilby.ui.login.TvLoginScreen
import dev.bilby.ui.login.TvLoginViewModel
import dev.bilby.ui.offline.OfflineScreen
import dev.bilby.ui.offline.OfflineViewModel
import dev.bilby.data.CoinLogRepository
import dev.bilby.ui.profile.CoinLogRoute
import dev.bilby.ui.profile.ProfileScreen
import dev.bilby.ui.message.MessagePushesRoute
import dev.bilby.ui.message.MessageScreen
import dev.bilby.ui.message.MessageTab
import dev.bilby.ui.message.MessageViewModel
import dev.bilby.ui.message.WhisperScreen
import dev.bilby.ui.message.PushFeedScreen
import dev.bilby.ui.message.WhisperViewModel
import dev.bilby.ui.profile.ProfileViewModel
import dev.bilby.ui.search.SearchChatScreen
import dev.bilby.ui.search.SearchChatViewModel
import dev.bilby.ui.search.SearchResultActions
import dev.bilby.ui.search.SearchResultScreen
import dev.bilby.ui.search.SearchResultViewModel
import dev.bilby.ui.search.SearchTab
import dev.bilby.ui.components.RefreshAction
import dev.bilby.ui.settings.AboutSettingsPage
import dev.bilby.ui.settings.AgentSettingsPage
import dev.bilby.ui.settings.AppearanceSettingsPage
import dev.bilby.ui.settings.PlaybackSettingsPage
import dev.bilby.data.model.ArticleRef
import dev.bilby.data.model.FeedEntry
import dev.bilby.ui.settings.ExcludedFeedPage
import dev.bilby.ui.settings.PrivacySettingsPage
import dev.bilby.ui.settings.SettingsScreen
import dev.bilby.ui.settings.SettingsSection
import dev.bilby.ui.settings.SponsorCategoriesPage
import dev.bilby.ui.settings.SettingsViewModel
import dev.bilby.data.SpaceCollectionItem
import dev.bilby.ui.space.CollectionScreen
import dev.bilby.ui.space.CollectionViewModel
import dev.bilby.ui.space.SpaceScreen
import dev.bilby.ui.space.SpaceViewModel
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Motion
import dev.bilby.ui.theme.rememberReducedMotion
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.update.StartupUpdatePrompt
import dev.bilby.ui.toview.ToViewClear
import dev.bilby.ui.toview.ToViewClearDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import dev.bilby.ui.toview.ToViewScreen
import dev.bilby.ui.toview.ToViewViewModel
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.bilby.ui.listen.ListenScreen
import dev.bilby.ui.live.LiveRoomRoute
import dev.bilby.ui.video.PlayerFrame
import dev.bilby.ui.video.VideoScreen
import dev.bilby.ui.video.VideoViewModel

private const val PROJECT_GITHUB_URL = "https://github.com/NihilDigit/bilby"

/**
 * 整个应用的界面,各平台入口(Android 的 MainActivity、桌面的窗口)只需在外面提供
 * [LocalSystemActions] 与 [LocalPlaybackHost],再调这一个。
 *
 * @param incomingLink 外面递进来的链接。**用 MutableStateFlow 而不是一次性参数**:应用已经在跑时
 *   又来一条(Android 的 onNewIntent),那时 composition 早就建好了。
 * @param windowChrome 平台窗口的装饰(Android 的系统栏明暗),画在主题里面,跟着明暗变。
 */
@Composable
fun BilbyRoot(
    container: AppContainer,
    incomingLink: MutableStateFlow<String?>,
    windowChrome: @Composable () -> Unit = {},
) {
    // 读到之前用默认值(跟随系统、按壁纸取色):DataStore 头一次读在一两帧之内,选了别的
    // 配色的人会看到一帧默认色。不为这一帧在主线程上同步读盘。
    val appearance by container.settings.appearancePrefs
        .collectAsStateWithLifecycle(initialValue = AppearancePrefs())
    BilbyTheme(appearance) {
        windowChrome()
        // 导航层的提示浮在整棵树上面。放在这里而不是某个页面的 Scaffold 里:说这句话的
        // 是压栈动作,而压栈能从任何一页发起,各页面的 Scaffold 都会跟着页面一起换掉。
        val snackbarHostState = remember { SnackbarHostState() }
        val pointerSource = remember { PointerSource() }
        // 分享在桌面上是复制链接,按下去界面上看不出变化;报一句,见 SystemActions.shareCopiesLink。
        val scope = rememberCoroutineScope()
        val copiedText = stringResource(Res.string.share_link_copied)
        val baseActions = LocalSystemActions.current
        val systemActions = remember(baseActions, copiedText) {
            baseActions.withShareNotice {
                scope.launch { snackbarHostState.showSnackbar(copiedText, withDismissAction = true) }
            }
        }
        Box(modifier = Modifier.fillMaxSize().trackPointerSource(pointerSource)) {
            CompositionLocalProvider(
                LocalPointerSource provides pointerSource,
                LocalSystemActions provides systemActions,
            ) {
                BilbyApp(container, incomingLink, snackbarHostState)
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .imePadding(),
            )
        }
    }
}

@Composable
private fun StartupUpdateHost(container: AppContainer) {
    // 不做应用内更新的平台上不检查:查到了也只能摆一个按不下去的按钮。
    val updater = container.platform.updater ?: return
    StartupUpdatePrompt(updater, container.settings, container.persistScope)
}

@Composable
private fun BilbyApp(
    container: AppContainer,
    incomingLink: MutableStateFlow<String?>,
    snackbarHostState: SnackbarHostState,
) {
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
    val backStack = rememberNavBackStack(DestinationSavedState, Home)

    /**
     * 压栈的唯一入口。同一个 key 在栈里只留一份,规则与理由见 [pushUnique]。
     *
     * **目标就是当前这一页时说一句。** 那一下真的什么都不该发生(空间页里点他自己的 @ 就是
     * 这种),但一个按下去有涟漪、然后毫无动静的链接读起来和坏掉没有区别 —— 这一句是
     * 在回答"我点到了吗"。
     *
     * 用 snackbar 而不是系统 `Toast`:Toast 不参与主题,字号、圆角、深浅色全是系统的,
     * 和它盖着的这个应用对不上。host 在 `setContent` 那一层,理由见那里。
     */
    val snackbarScope = rememberCoroutineScope()
    val alreadyHere = stringResource(Res.string.nav_already_here)
    val push: (NavKey) -> Unit = remember(backStack, snackbarHostState, snackbarScope, alreadyHere) {
        { key ->
            if (!backStack.pushUnique(key.withNewFrame())) {
                // 连点几下只留最后一条:SnackbarHostState 自带 MutatorMutex,新的一条会
                // 取消正在显示的那条,不用自己去 dismiss。
                snackbarScope.launch { snackbarHostState.showSnackbar(alreadyHere) }
            }
        }
    }

    /**
     * 正文里的一条链接。**先按站内解析,认不出来才交给浏览器。**
     *
     * 专栏正文、通知、评论里引的多是 BV 号和别人的空间,那些在这个应用里有落点,一律外跳
     * 等于把读者赶出去再走回来;而活动页、会员购这类站内没有对应页面,只能外跳。
     *
     * 收在这一层是因为它出现在四个地方(专栏、通知、评论、动态详情),而判断只有一条。
     * 原先每处各抄一份,四份里已经有一份写法不同。
     */
    val system = LocalSystemActions.current
    val playback = LocalPlaybackHost.current
    val openLink: (String) -> Unit = { url ->
        val destination = BilbyLink.destinationOf(url)
        if (destination != null) push(destination) else system.openInBrowser(url)
    }

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
            push(destination)
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
     * [dev.bilby.player.PlaybackHost.pauseForAppBackground]):听视频继续,看视频暂停。划掉任务卡片
     * 又是第三件事,归 `onTaskRemoved`。
     *
     * 转屏不受影响:manifest 声明了 configChanges,Activity 不重建,onStop 不会跑。
     */
    val hasVideoPage = backStack.any { it is Video }
    LaunchedEffect(hasVideoPage) {
        if (!hasVideoPage) playback.stop()
    }

    // 队列栈的帧与导航栈上的视频页一一对应,出栈的那些由服务丢掉(docs/queue-redesign.md
    // 决定 3)。帧的先后只在这里,服务不另记。
    val videoFrames = backStack.mapNotNullTo(HashSet()) { (it as? Video)?.frame }
    LaunchedEffect(videoFrames) { playback.retainFrames(videoFrames) }

    // 私信的列表加详情,见 ListDetailScene。「消息」页停在哪一格决定它要不要分栏;这个值读在这里、
    // 当作 remember 的键,变了就换一个策略实例,NavDisplay 才会重算场景(理由见 ListPane)。
    val messagesOnWhispers = remember { mutableStateOf(true) }
    val pushesAwaitingFirst = remember { mutableStateOf(false) }
    val listDetailWide = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)
    val listDetailStrategy = remember(listDetailWide, messagesOnWhispers.value) {
        ListDetailSceneStrategy(listDetailWide)
    }
    // 对话占着右栏时点列表里另一个会话,是换掉这一栏,不是再压一层:否则返回要一层层退回去,
    // 而屏上始终只看得到一段对话。窄窗口下对话盖住列表,点不到别的会话,走不到这一支。
    val openWhisper: (Whisper) -> Unit = { key ->
        if (backStack.lastOrNull() is Whisper) backStack.removeLastOrNull()
        push(key)
    }
    val selectedTalker = (backStack.lastOrNull() as? Whisper)?.talkerId

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        sceneStrategies = listOf(listDetailStrategy),
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
        // 预测式返回。形状和普通返回一致(五分之一屏的滑动加淡出),差别只在时间轴归手指管:
        // NavDisplay 把手势进度喂给 SeekableTransitionState.seekTo,规格因此是"进度 → 取值"的
        // 映射,不是"放多久"。缓动与时长的取法见 Motion.PredictivePopSlide 上的说明。
        //
        // 减弱动效时同样退成纯淡出,判据同上面两条。
        predictivePopTransitionSpec = { _ ->
            if (reducedMotion) {
                fadeIn(Motion.PredictivePopFade) togetherWith fadeOut(Motion.PredictivePopFade)
            } else {
                (
                    slideInHorizontally(Motion.PredictivePopSlide) { -it / Motion.ForwardSlideFraction } +
                        fadeIn(Motion.PredictivePopFade)
                    ) togetherWith (
                    slideOutHorizontally(Motion.PredictivePopSlide) { it / Motion.ForwardSlideFraction } +
                        fadeOut(Motion.PredictivePopFade)
                    )
            }
        },
        entryProvider = entryProvider {
            entry<Home> {
                CutoutSafe {
                    RootTabs(
                        container = container,
                        onVideoClick = { push(Video(it)) },
                        onVideoInContext = { bvid, context -> push(Video(bvid, context = context)) },
                        onUserClick = { push(Space(it)) },
                        onLiveClick = { push(LiveRoom(it)) },
                        onSettingsClick = { push(Settings) },
                        onOpenFollowings = { push(Followings) },
                        onOpenCoinLog = { push(CoinLog) },
                        onOpenOtherDynamics = { push(OtherDynamics) },
                        onOpenPushes = { push(MessagePushes) },
                        onOpenArticle = { ref -> push(ArticlePage(ref.id, ref.isRead)) },
                        onOpenHistory = { push(History) },
                        onOpenToView = { push(ToViewList) },
                        onOpenOffline = { push(Offline) },
                        onOpenFavFolder = { folder ->
                            push(FavFolderContents(folder.id, folder.title))
                        },
                        onOpenFavFolders = { push(FavFolders) },
                        onOpenMessages = { push(Messages) },
                    )
                }
            }
            entry<Settings> {
                CutoutSafe {
                    SettingsRoute(
                        container = container,
                        onOpenSection = { push(SettingsPage(it)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<SettingsPage> { key ->
                CutoutSafe {
                    SettingsPageRoute(
                        container = container,
                        section = key.section,
                        onOpenSection = { push(SettingsPage(it)) },
                        onOpenBlacklist = { push(Blacklist) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<History> {
                CutoutSafe {
                    HistoryRoute(
                        container = container,
                        onVideoClick = { push(Video(it)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<Video> { key ->
                VideoRoute(
                    container = container,
                    key = key,
                    onUpClick = { push(Space(it)) },
                    onOpenQueueSource = {
                        push(CollectionContents(it.mid, it.id, it.isSeason, it.name))
                    },
                    onBack = { backStack.removeLastOrNull() },
                    // 切集是**重组,不是压栈**:换的是这一页在放哪一条,不是又进了一层。
                    // 压栈的话看五集就攒五层,返回要一集一集退回去,而合集本来是一个有限集合、
                    // 用户是在里面平移。替换栈顶之后,从任何一集返回都回到进来时的地方。
                    //
                    // 今天那个"点下一集不自动播放"的 bug 也出在这里:压栈时旧页的 onDispose
                    // 在新页起播之后才跑,把刚起播的下一集暂停了。替换栈顶让这个错位不成立。
                    onOpenVideo = { backStack.replaceTopUnique(Video(it).withNewFrame()) },
                    onOpenLink = openLink,
                    onSearchTag = { push(SearchResult(it)) },
                )
            }
            entry<SearchResult> { key ->
                CutoutSafe {
                    SearchResultRoute(
                        container = container,
                        keyword = key.keyword,
                        onVideoClick = { push(Video(it)) },
                        onUserClick = { push(Space(it)) },
                        onArticleClick = { cv -> push(ArticlePage(cv.toString(), isRead = true)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<Messages>(
                metadata = ListDetailSceneStrategy.listPane(
                    // 右栏空着时说一句它是干什么的:这里没有在加载什么,画骨架就是在假装加载。
                    ListPane(showsDetail = { messagesOnWhispers.value }, placeholder = { MessageDetailPlaceholder() }),
                ),
            ) {
                CutoutSafe {
                    MessagesRoute(
                        container = container,
                        selectedTalker = selectedTalker,
                        onWhisperTabShown = { messagesOnWhispers.value = it },
                        onOpenWhisper = { openWhisper(Whisper(it.talkerId, it.name, it.faceUrl, it.isSystem)) },
                        onOpenSpace = { push(Space(it)) },
                        // 通知里的 uri 是站内链接,认得出来就在应用内落地,认不出来
                        // (活动页、会员购这类)交给浏览器 —— 同专栏正文里的链接一条路。
                        onOpenUri = openLink,
                        // 回复、@、被赞的评论带着评论定位,落到评论详情页;认不出定位的(被赞的是
                        // 视频或动态)照网页地址走。native_uri 是 bilibili:// scheme,不交给浏览器。
                        onOpenNotice = { notice ->
                            val thread = BilbyLink.commentThreadOf(notice.nativeUri, notice.subjectId, notice.businessId)
                            when {
                                thread != null -> push(thread)
                                notice.uri.isNotBlank() -> openLink(notice.uri)
                            }
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<CommentThread> { key ->
                // 评论所在的内容:视频稿件 1、专栏 12、动态 17。其余类型(图文动态 11 的 oid 是
                // 相簿 id,不是动态 id)认不出去处,顶栏不放入口。
                val subject: NavKey? = when (key.type) {
                    1 -> Video(BvidCodec.fromAid(key.oid))
                    12 -> ArticlePage(key.oid.toString(), isRead = true)
                    17 -> DynamicDetail(key.oid.toString())
                    else -> null
                }
                CutoutSafe {
                    CommentThreadRoute(
                        repository = container.commentRepository,
                        settings = container.settings,
                        oid = key.oid,
                        type = key.type,
                        rootRpid = key.rootRpid,
                        targetRpid = key.targetRpid,
                        onOpenSubject = subject?.let { { push(it) } },
                        onUserClick = { push(Space(it)) },
                        onOpenLink = openLink,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<CoinLog> {
                CutoutSafe {
                    // 仓库无状态、只有一个接口,就地造一份,不进 AppContainer。
                    CoinLogRoute(
                        repository = remember { CoinLogRepository(container.biliClient) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<MessagePushes>(
                // 还在等自动打开的第一个会话时转圈(见 MessagePushesRoute.autoOpenFirst);打开过又关掉、
                // 或者根本没有推送会话时,同消息页那一句,否则就是一直转下去。
                metadata = ListDetailSceneStrategy.listPane(
                    ListPane(
                        showsDetail = { true },
                        placeholder = {
                            if (pushesAwaitingFirst.value) FullScreenLoading() else MessageDetailPlaceholder()
                        },
                    ),
                ),
            ) {
                CutoutSafe {
                    MessagePushesRoute(
                        repository = container.messageRepository,
                        selectedTalker = selectedTalker,
                        autoOpenFirst = listDetailWide,
                        onAwaitingFirstChange = { pushesAwaitingFirst.value = it },
                        onOpenWhisper = {
                            openWhisper(Whisper(it.talkerId, it.name, it.faceUrl, it.isSystem, upPushes = true))
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<Whisper>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                CutoutSafe {
                    WhisperRoute(
                        container = container,
                        key = key,
                        onOpenSpace = { push(Space(key.talkerId)) },
                        onOpenVideo = { push(Video(it)) },
                        // 私信里的专栏带的是 cv 号(推送的 rid、分享的 id),走旧版那套接口。
                        onOpenArticle = { push(ArticlePage(it, isRead = true)) },
                        onOpenLink = openLink,
                        // 压一层,不是换掉:返回回到推送视图。宽窗口下这一页前面不是列表,
                        // 不分栏,整页是聊天(见 ListDetailScene 只认紧挨着的上一条)。
                        onOpenFullChat = { push(key.copy(upPushes = false)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<Offline> {
                CutoutSafe {
                    OfflineRoute(
                        container = container,
                        // 缓存列表里一行就是一个分 P,点哪一行就该播哪一行。**指名走
                        // [PartRequest] 而不是路由参数**:它是这一下的意图,取走即弃,
                        // 不该在转屏重建之后又把播放器推回这一 P(见 Destinations 的 Video)。
                        onPlay = { item ->
                            container.partRequest.request(item.bvid, item.cid)
                            push(Video(item.bvid, context = QueueContext.Offline))
                        },
                        onListenAll = { item ->
                            container.partRequest.request(item.bvid, item.cid)
                            push(Video(item.bvid, listening = true, context = QueueContext.Offline))
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<ToViewList> {
                CutoutSafe {
                    ToViewListRoute(
                        container = container,
                        onVideoClick = { bvid, context -> push(Video(bvid, context = context)) },
                        onListen = { bvid, context -> push(Video(bvid, listening = true, context = context)) },
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
                        onVideoClick = { bvid, context -> push(Video(bvid, context = context)) },
                        onListen = { bvid, context -> push(Video(bvid, listening = true, context = context)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<FavFolders> {
                CutoutSafe {
                    FavFoldersRoute(
                        container = container,
                        onOpenFolder = { push(FavFolderContents(it.id, it.title)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<Blacklist> {
                CutoutSafe {
                    BlacklistRoute(container = container, onBack = { backStack.removeLastOrNull() })
                }
            }
            entry<DynamicDetail> { key ->
                CutoutSafe {
                    DynamicDetailRoute(
                        container = container,
                        id = key.id,
                        onVideoClick = { push(Video(it)) },
                        onUpClick = { push(Space(it)) },
                        onArticleClick = { id, isRead -> push(ArticlePage(id, isRead)) },
                        onOpenLink = openLink,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<Followings> {
                CutoutSafe {
                    FollowingsRoute(
                        container = container,
                        onUpClick = { push(Space(it)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<LiveRoom> { key ->
                LiveRoomRoute(
                    container,
                    key.roomId,
                    onBack = { backStack.removeLastOrNull() },
                    onUserClick = { push(Space(it)) },
                )
            }
            entry<Space> { key ->
                val scope = rememberCoroutineScope()
                CutoutSafe {
                    SpaceRoute(
                        container,
                        key.mid,
                        onVideoClick = { bvid, context -> push(Video(bvid, context = context)) },
                        onListenUp = { bvid, context -> push(Video(bvid, listening = true, context = context)) },
                        onLiveClick = { push(LiveRoom(it)) },
                        onCollectionClick = {
                            push(CollectionContents(key.mid, it.id, it.isSeason, it.name))
                        },
                        onDynamicAction = { action ->
                            handleDynamicAction(action, system, container, scope, push)
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<CollectionContents> { key ->
                CutoutSafe {
                    CollectionRoute(
                        container = container,
                        key = key,
                        onVideoClick = { bvid, context -> push(Video(bvid, context = context)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<OtherDynamics> {
                val scope = rememberCoroutineScope()
                CutoutSafe {
                    OtherDynamicsRoute(
                        container = container,
                        onAction = { action ->
                            handleDynamicAction(action, system, container, scope, push)
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }
            entry<ArticlePage> { key ->
                CutoutSafe {
                    ArticleRoute(
                        container = container,
                        key = key,
                        onOpenLink = openLink,
                        onUpClick = { push(Space(it)) },
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
    val label: StringResource,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Feed(Res.string.tab_feed, Icons.Filled.Subscriptions, Icons.Outlined.Subscriptions),
    Search(Res.string.tab_search, Icons.Filled.Search, Icons.Outlined.Search),
    Profile(Res.string.tab_profile, Icons.Filled.Person, Icons.Outlined.Person),
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
    /**
     * 「我的」页稍后再看、缓存预览里的一条:队列是那一节对应的列表,不是这条视频的归属。
     */
    onVideoInContext: (String, QueueContext) -> Unit,
    onUserClick: (Long) -> Unit,
    onLiveClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onOpenFollowings: () -> Unit,
    onOpenCoinLog: () -> Unit,
    onOpenOtherDynamics: () -> Unit,
    /** 订阅页标题行的「推送」:UP 主推送来的那些私信会话,见 MessagePushesScreen。 */
    onOpenPushes: () -> Unit,
    onOpenArticle: (ArticleRef) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenToView: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenFavFolder: (FavFolderDetail) -> Unit,
    onOpenFavFolders: () -> Unit,
    onOpenMessages: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(RootTab.Feed) }
    val windowSize = rememberBilbyWindowSize()

    // 重按当前 tab 回到顶部,navigation-bar.md 的明文要求。
    //
    // **每个 tab 一个计数器,不共用一个。** 共用的话,在动态页连点几下再切到个人页,
    // 个人页看到的是一个变过的计数器,会跟着滚一次它自己没被点过的。
    //
    // 搜索页不接:那一页是对话式的,输入在下、一轮轮结果往上滚,顶端是最早的一轮,
    // 回到顶不是"重来一次",而是翻到最旧的地方。
    var feedScrollToTop by remember { mutableIntStateOf(0) }
    var profileScrollToTop by remember { mutableIntStateOf(0) }
    val onTabClick: (RootTab) -> Unit = { tab ->
        if (selected == tab) {
            when (tab) {
                RootTab.Feed -> feedScrollToTop++
                RootTab.Profile -> profileScrollToTop++
                RootTab.Search -> Unit
            }
        } else {
            selected = tab
        }
    }

    // 键盘弹起时盖住底栏,底栏不跟着上移:navigation-bar.md 的 Placement 一节列明底栏可以被
    // 键盘临时遮住,示例正是搜索。跟着上移的话底栏夹在输入框和键盘之间,白占一条高度。
    // IME 退让因此只加在内容区,见 [RootTabsContent]。
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
            bottomBar = {
                // flexible 款(64dp)。baseline 的 NavigationBar 高 80dp,M3 Expressive 已标为
                // "no longer recommended"(navigation-bar.md)。
                ShortNavigationBar {
                    RootTab.entries.forEach { tab ->
                        ShortNavigationBarItem(
                            selected = selected == tab,
                            onClick = { onTabClick(tab) },
                            icon = { RootTabIcon(tab, selected == tab) },
                            label = { RootTabLabel(tab, selected == tab) },
                        )
                    }
                }
            },
        ) { insets ->
            RootTabsContent(
                insets = insets,
                selected = selected,
                feedScrollToTop = feedScrollToTop,
                profileScrollToTop = profileScrollToTop,
                container = container,
                onVideoClick = onVideoClick,
                onVideoInContext = onVideoInContext,
                onUserClick = onUserClick,
                onLiveClick = onLiveClick,
                onSettingsClick = onSettingsClick,
                onOpenFollowings = onOpenFollowings,
                onOpenCoinLog = onOpenCoinLog,
                onOpenOtherDynamics = onOpenOtherDynamics,
        onOpenPushes = onOpenPushes,
                onOpenArticle = onOpenArticle,
                onOpenHistory = onOpenHistory,
                onOpenToView = onOpenToView,
                onOpenOffline = onOpenOffline,
                onOpenFavFolder = onOpenFavFolder,
                onOpenFavFolders = onOpenFavFolders,
                onOpenMessages = onOpenMessages,
            )
        }
    } else {
        // medium 起用 rail 换掉底栏:导航贴在边缘,内容拿到完整的横向空间。三个根目的地
        // 不需要 drawer —— M3 的 navigation rail 页把 rail 作为 drawer 的优先替代。
        //
        // collapsed 款(WideNavigationRail 的默认态)。baseline 的 NavigationRail 已被
        // navigation-rail.md 标为 "no longer recommended"。目的地居中:同一页对平板给的就是
        // 这个摆法,三格顶在屏幕最上沿时握持的那只手要伸到最远处。
        Row(modifier = Modifier.fillMaxSize()) {
            WideNavigationRail(
                modifier = Modifier.fillMaxHeight(),
                arrangement = Arrangement.Center,
            ) {
                RootTab.entries.forEach { tab ->
                    WideNavigationRailItem(
                        selected = selected == tab,
                        onClick = { onTabClick(tab) },
                        icon = { RootTabIcon(tab, selected == tab) },
                        label = { RootTabLabel(tab, selected == tab) },
                        railExpanded = false,
                    )
                }
            }
            Scaffold(modifier = Modifier.weight(1f)) { insets ->
                RootTabsContent(
                    insets = insets,
                    selected = selected,
                    feedScrollToTop = feedScrollToTop,
                    profileScrollToTop = profileScrollToTop,
                    container = container,
                    onVideoClick = onVideoClick,
                    onVideoInContext = onVideoInContext,
                    onUserClick = onUserClick,
                    onLiveClick = onLiveClick,
                    onSettingsClick = onSettingsClick,
                    onOpenFollowings = onOpenFollowings,
                    onOpenCoinLog = onOpenCoinLog,
                    onOpenOtherDynamics = onOpenOtherDynamics,
        onOpenPushes = onOpenPushes,
                    onOpenArticle = onOpenArticle,
                    onOpenHistory = onOpenHistory,
                    onOpenToView = onOpenToView,
                    onOpenOffline = onOpenOffline,
                    onOpenFavFolder = onOpenFavFolder,
                    onOpenFavFolders = onOpenFavFolders,
                    onOpenMessages = onOpenMessages,
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

/**
 * 选中项的标签加粗。navigation-bar.md 的 Visual indicators 一节要求 "a bold label for
 * selected destinations",而 ShortNavigationBarItem 的 token 只有一份标签字体,不分选中态。
 */
@Composable
private fun RootTabLabel(tab: RootTab, selected: Boolean) {
    Text(stringResource(tab.label), fontWeight = if (selected) FontWeight.Bold else null)
}

@Composable
private fun RootTabsContent(
    insets: PaddingValues,
    selected: RootTab,
    /** 每变一次就回到顶部。计数器而不是布尔:连按两下要滚两次,而布尔第二下没有变化。 */
    feedScrollToTop: Int,
    profileScrollToTop: Int,
    container: AppContainer,
    onVideoClick: (String) -> Unit,
    onVideoInContext: (String, QueueContext) -> Unit,
    onUserClick: (Long) -> Unit,
    onLiveClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onOpenFollowings: () -> Unit,
    onOpenCoinLog: () -> Unit,
    onOpenOtherDynamics: () -> Unit,
    /** 订阅页标题行的「推送」:UP 主推送来的那些私信会话,见 MessagePushesScreen。 */
    onOpenPushes: () -> Unit,
    onOpenArticle: (ArticleRef) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenToView: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenFavFolder: (FavFolderDetail) -> Unit,
    onOpenFavFolders: () -> Unit,
    onOpenMessages: () -> Unit,
) {
    // 只 padding 不声明消费的话,后面的 imePadding() 会再多退让一个底栏高度。消费之后它只让出
    // 键盘高出底栏的那一截,内容正好停在键盘上沿,底栏留在键盘下面。
    val bottom = PaddingValues(bottom = insets.calculateBottomPadding())
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = insets.calculateTopPadding())
            .padding(bottom)
            .consumeWindowInsets(bottom)
            .imePadding(),
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
            // 限宽下放到各个 tab:订阅页宽屏是网格,要拿到 rail 右边的全部宽度,行长由格宽
            // 管;另外两页仍是单栏,照旧限宽。
            when (tab) {
                RootTab.Feed -> FeedPane(
                    container = container,
                    scrollToTop = feedScrollToTop,
                    onVideoClick = onVideoClick,
                    onUserClick = onUserClick,
                    onLiveClick = onLiveClick,
                    onOpenFollowings = onOpenFollowings,
                    onOpenOtherDynamics = onOpenOtherDynamics,
        onOpenPushes = onOpenPushes,
                    onOpenArticle = onOpenArticle,
                )

                RootTab.Search -> AdaptiveContent(maxWidth = Breakpoints.ReadableWidth) {
                    SearchPane(container, onVideoClick, onUserClick, onOpenArticle)
                }

                // 不限宽:宽屏每节是一排卡片,宽度换成条数(见 ProfileScreen);窄屏账号卡自己限宽。
                RootTab.Profile -> ProfilePane(
                    container = container,
                    scrollToTop = profileScrollToTop,
                    onVideoClick = onVideoClick,
                    onVideoInContext = onVideoInContext,
                    onUserClick = onUserClick,
                    onOpenHistory = onOpenHistory,
                    onOpenToView = onOpenToView,
                    onOpenOffline = onOpenOffline,
                    onOpenFavFolder = onOpenFavFolder,
                    onOpenFavFolders = onOpenFavFolders,
                    onOpenMessages = onOpenMessages,
                    onOpenFollowings = onOpenFollowings,
                    onOpenCoinLog = onOpenCoinLog,
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
    scrollToTop: Int,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onLiveClick: (Long) -> Unit,
    onOpenFollowings: () -> Unit,
    onOpenOtherDynamics: () -> Unit,
    /** 订阅页标题行的「推送」:UP 主推送来的那些私信会话,见 MessagePushesScreen。 */
    onOpenPushes: () -> Unit,
    onOpenArticle: (ArticleRef) -> Unit,
) {
    val vm: FeedViewModel = viewModel(
        key = "root-feed",
        factory = viewModelFactory {
            initializer {
                FeedViewModel(
                    container.dynamicFeedStore,
                    container.followRepository,
                    container.settings,
                    container.feedReadPositionRepository,
                    container.toViewRepository,
                    container.persistScope,
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    FeedScreen(
        state = state,
        scrollToTop = scrollToTop,
        onLoadMore = vm::loadMore,
        onRetry = vm::retry,
        onRefresh = vm::refresh,
        onItemClick = { entry ->
            when (entry) {
                is FeedEntry.Video -> onVideoClick(entry.bvid)
                is FeedEntry.Article -> onOpenArticle(entry.ref)
            }
        },
        onUpClick = onUserClick,
        onLiveClick = onLiveClick,
        onExcludeUp = vm::excludeUp,
        onOpenFollowings = onOpenFollowings,
        onOpenOtherDynamics = onOpenOtherDynamics,
        onOpenPushes = onOpenPushes,
        onScrollPositionChanged = vm::onVisibleTopChanged,
        onLocated = vm::onLocated,
        onEnter = vm::onEnterScreen,
        onUndoExclude = vm::undoExclude,
        onExcludeUndoShown = vm::clearExcludeUndo,
        onAddToView = vm::addToView,
        onToViewNoticeShown = vm::clearToViewNotice,
    )
}

@Composable
private fun SearchPane(
    container: AppContainer,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onOpenArticle: (ArticleRef) -> Unit,
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
        onHistoryClear = vm::clearSearchHistory,
        onInputChange = vm::onInputChange,
        onModeChange = vm::onModeChange,
        onSend = vm::send,
        onNewSession = vm::newSession,
        onVideoClick = onVideoClick,
        onRetry = vm::retry,
        resultActions = SearchResultActions(
            onTabSelected = vm::onTabSelected,
            onOrderChange = vm::onOrderChanged,
            onDurationChange = vm::onDurationChanged,
            onArticleOrderChange = vm::onArticleOrderChanged,
            onVideoClick = onVideoClick,
            onUserClick = onUserClick,
            // 搜索结果里的专栏只有 cv 号,走 read 那一套(notes/space-and-search.md 2.12)。
            onArticleClick = { cv -> onOpenArticle(ArticleRef(cv.toString(), isRead = true)) },
            onLoadMore = vm::loadMore,
            onRetry = vm::refresh,
        ),
    )
}

@Composable
private fun SearchResultRoute(
    container: AppContainer,
    keyword: String,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onArticleClick: (Long) -> Unit,
    onBack: () -> Unit,
) {
    // keyword 是这个目的地的身份,同一个词压两次由 pushUnique 挡住,不需要 switchTo。
    val vm: SearchResultViewModel = viewModel(
        factory = viewModelFactory {
            initializer { SearchResultViewModel(keyword, container.searchRepository) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    // **这一页起,每个二级页面的顶栏都接上滚动行为。** `BilbyTopBar` 一直有这个参数,但没有
    // 一个调用方传过,于是顶栏和内容之间没有边界:列表第一行贴着顶栏往上走,看起来像是从
    // 标题底下钻出来的。pinned 而不是 enterAlways —— 顶栏上写着"这是哪一页",而这些页面没有
    // 底栏兜底;滑走之后返回箭头也跟着走了。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(title = keyword, onBack = onBack, scrollBehavior = scrollBehavior) {
                // 与列表里的下拉同一个判据,按当前那一栏取,见 SearchResults 里的 RefreshBox。
                val list = when (state.tab) {
                    SearchTab.Video -> state.videos
                    SearchTab.User -> state.users
                    SearchTab.Article -> state.articles
                }
                RefreshAction(refreshing = list.loading && list.items.isNotEmpty(), onRefresh = vm::retry)
            }
        },
    ) { insets ->
        SearchResultScreen(
            state = state,
            actions = SearchResultActions(
                onTabSelected = vm::onTabSelected,
                onOrderChange = vm::onOrderChanged,
                onDurationChange = vm::onDurationChanged,
                onArticleOrderChange = vm::onArticleOrderChanged,
                onVideoClick = onVideoClick,
                onUserClick = onUserClick,
                onArticleClick = onArticleClick,
                onLoadMore = vm::loadMore,
                onRetry = vm::retry,
            ),
            modifier = Modifier.padScaffoldExceptBottom(insets),
        )
    }
}

@Composable
private fun ProfilePane(
    container: AppContainer,
    scrollToTop: Int,
    onVideoClick: (String) -> Unit,
    onVideoInContext: (String, QueueContext) -> Unit,
    onUserClick: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenToView: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenFavFolder: (FavFolderDetail) -> Unit,
    onOpenFavFolders: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenFollowings: () -> Unit,
    onOpenCoinLog: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val vm: ProfileViewModel = viewModel(
        key = "root-profile",
        factory = viewModelFactory {
            initializer {
                ProfileViewModel(
                    container.accountRepository,
                    container.historyRepository,
                    container.toViewRepository,
                    container.favRepository,
                    container.offlineDownloader,
                    container.offlineStore,
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // 重新进入这一页时整个重取。挂在"进组合"上而不是底栏那次点击上:从历史记录、稍后再看
    // 这些子页面返回时 Home 整个重新进组合,而那正是概览最可能已经过期的时候(用户刚在那边
    // 删了东西),底栏的 onClick 覆盖不到这条路。
    //
    // 首次进入时 ViewModel 的 init 已经拉过一次,这里会再来一次 —— 认了。少这一次的写法
    // (去抖 + 版本号)试过,漏的比省的多,见 [ProfileViewModel.refresh]。
    LaunchedEffect(Unit) { vm.refresh() }

    ProfileScreen(
        state = state,
        scrollToTop = scrollToTop,
        onVideoClick = onVideoClick,
        // 预览按列表页的默认排序(最近添加在前)取,队列跟着同一个方向。
        onToViewItemClick = { onVideoInContext(it, QueueContext.ToView()) },
        // 缓存一行是一个分 P,指名走 PartRequest,理由同缓存页(见 Offline 那个 entry)。
        onOfflineItemClick = { item ->
            container.partRequest.request(item.bvid, item.cid)
            onVideoInContext(item.bvid, QueueContext.Offline)
        },
        onOpenHistory = onOpenHistory,
        onOpenToView = onOpenToView,
        onOpenOffline = onOpenOffline,
        onOpenFavFolder = onOpenFavFolder,
        onOpenFavFolders = onOpenFavFolders,
        onOpenMessages = onOpenMessages,
        onOpenFollowings = onOpenFollowings,
        onOpenCoinLog = onOpenCoinLog,
        onSettingsClick = onSettingsClick,
        onOpenSelf = onUserClick,
        onRetryAccount = vm::retryAccount,
        onRetryHistory = vm::retryHistory,
        onRetryToView = vm::retryToView,
        onRetryFavFolders = vm::retryFavFolders,
    )
}

/**
 * 设置。**首页只有入口,内容在 [SettingsPageRoute]**;两层各是一个 NavEntry,因此各有一个
 * `SettingsViewModel` 实例 —— 它们靠持续收 DataStore 的流保持一致,不靠互相通知
 * (见 `SettingsViewModel` 的 init)。
 */
@Composable
private fun SettingsRoute(
    container: AppContainer,
    onOpenSection: (SettingsSection) -> Unit,
    onBack: () -> Unit,
) {
    val vm = rememberSettingsViewModel(container)
    val state by vm.state.collectAsStateWithLifecycle()
    val playback = LocalPlaybackHost.current
    // 首页先把「暂停记录」读回来:它是服务端的值,隐私页打开时若还在读,开关要晚一拍才出现。
    // 读到的值记在 HistoryRepository 里,隐私页的 ViewModel 另起一个也拿得到。
    LaunchedEffect(Unit) { vm.loadHistoryPause() }
    SettingsScreen(
        state = state,
        onOpenSection = onOpenSection,
        // 顺序是先清凭据后停服务 —— 反过来的话中间那一瞬服务已停而凭据还在,
        // 看起来像"没登出但停了"。
        onLogout = { vm.logout { playback.stop() } },
        onBack = onBack,
    )
}

/** 设置的二级页面。七页共用一条路由,分支在这里,理由见 `SettingsPage` 的说明。 */
@Composable
private fun SettingsPageRoute(
    container: AppContainer,
    section: SettingsSection,
    onOpenSection: (SettingsSection) -> Unit,
    onOpenBlacklist: () -> Unit,
    onBack: () -> Unit,
) {
    val vm = rememberSettingsViewModel(container)
    val state by vm.state.collectAsStateWithLifecycle()
    val system = LocalSystemActions.current
    when (section) {
        SettingsSection.Appearance -> AppearanceSettingsPage(
            state = state,
            // 每次进这一页现读:Android 13+ 上它可能在系统设置里被改过,不在我们的状态里。
            language = remember(system) { system.currentLanguage() },
            onModeChange = vm::setThemeMode,
            onPureBlackChange = vm::setPureBlack,
            onPaletteChange = vm::setThemePalette,
            onLanguageChange = system::applyLanguage,
            onBack = onBack,
        )

        SettingsSection.Playback -> PlaybackSettingsPage(
            state = state,
            onWifiQualityChange = { vm.setDefaultQuality(it, metered = false) },
            onMeteredQualityChange = { vm.setDefaultQuality(it, metered = true) },
            onCodecChange = vm::setCodec,
            onFastForwardSpeedChange = vm::setFastForwardSpeed,
            onAutoNextChange = vm::setAutoNext,
            onWifiAudioChange = { vm.setDefaultAudio(it, metered = false) },
            onMeteredAudioChange = { vm.setDefaultAudio(it, metered = true) },
            onPickUpdatesDefaultChange = vm::setPlayerPickUpdatesDefault,
            onSponsorBlockChange = vm::updateSponsorBlock,
            onOpenSponsorCategories = { onOpenSection(SettingsSection.SponsorCategories) },
            onOfflineConcurrencyChange = vm::setOfflineConcurrency,
            onBack = onBack,
        )

        SettingsSection.SponsorCategories -> SponsorCategoriesPage(
            state = state,
            onChange = vm::updateSponsorBlock,
            onBack = onBack,
        )

        SettingsSection.Agent -> AgentSettingsPage(
            state = state,
            onLlmChange = vm::saveLlm,
            onSmokeTest = vm::smokeTestLlm,
            onBack = onBack,
        )

        SettingsSection.Privacy -> {
            // 「暂停记录」是服务端状态,进这一页才知道 —— 别的开关都从 SettingsStore 读得出来。
            LaunchedEffect(Unit) { vm.loadHistoryPause() }
            PrivacySettingsPage(
                state = state,
                onHistoryPausedChange = vm::setHistoryPaused,
                onRetryHistoryPause = vm::loadHistoryPause,
                onOpenBlacklist = onOpenBlacklist,
                onOpenExcludedFeed = { onOpenSection(SettingsSection.ExcludedFeed) },
                onDanmakusArchiveChange = vm::setDanmakusArchive,
                onBack = onBack,
            )
        }

        SettingsSection.ExcludedFeed -> ExcludedFeedPage(
            state = state,
            onRestore = vm::restoreExcludedFeedMid,
            onClearAll = vm::clearExcludedFeedMids,
            onBack = onBack,
        )

        SettingsSection.About -> AboutSettingsPage(
            updater = container.platform.updater,
            onOpenGithub = { system.openInBrowser(PROJECT_GITHUB_URL) },
            onBack = onBack,
        )
    }
}

@Composable
private fun rememberSettingsViewModel(container: AppContainer): SettingsViewModel = viewModel(
    factory = viewModelFactory {
        initializer {
            SettingsViewModel(
                container.settings,
                container.llmClient,
                container.historyRepository,
                container.persistScope,
            )
        }
    },
)

/**
 * 历史记录。「我的」页的三个入口之一,独立成页且只待在这一页(DESIGN 2 节)——
 * 不喂给别的界面,没有"继续观看"式的跳转。
 *
 * 多选的外壳照 [OfflineRoute]:选中集合放在这里而不是 ViewModel,它是这一页的界面状态,
 * 离开这一页就该没有。两处偏离见 [HistoryScreen] 的说明:进入多选不只有长按一条路;
 * 删除不可撤销(服务端没有恢复接口),所以用确认对话框,不用可撤销的 Snackbar。
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

    // null 表示不在多选态。空集合是多选态里的合法状态 —— 顶栏的「选择」要在一条都没选的
    // 时候就进得去。
    var selectedIds by remember { mutableStateOf<Set<Long>?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmingDeleteSelected by remember { mutableStateOf(false) }
    var confirmingClearFinished by remember { mutableStateOf(false) }
    var confirmingClearAll by remember { mutableStateOf(false) }

    val selected = state.items.filter { selectedIds?.contains(it.oid) == true }
    // 「清空已看完」没有服务端接口,是本地筛出 progress == -1 再走同一条批量删除
    // (notes/comment-toview-history.md §3.7),范围因此只到已经加载的这些。
    val finished = state.items.filter { it.isFinished }

    // 历史是游标翻页、列表会往下追加,每次列表变了都要把选中集合与它求交:删掉的 id 留在
    // 集合里,顶栏就一直显示"已选 3 项",而那几条早就不在屏上了。
    LaunchedEffect(state.items) {
        selectedIds = selectedIds?.intersect(state.items.map { it.oid }.toSet())
    }
    BackHandler(enabled = selectedIds != null) { selectedIds = null }

    // 见 [SearchResultRoute] 里那段说明。多选态那条顶栏也接同一个 behavior:两条是同一个位置
    // 的两种长相,只有一条变色的话切进多选就像顶栏换了一层底。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // **进出多选是换一条顶栏,不是换几个图标**,所以两条之间淡入淡出,不硬切。
            // 硬切的表现是标题和整排动作在一帧里全变,而人只按了一下「选择」—— 读起来像
            // 跳到了另一个页面。风格指南 §6 那张表:这是组件层的显隐,取 motionScheme 的
            // effects 档,不是转场的 tween。
            //
            // **`contentKey` 认的是"在不在多选态",不是那个集合本身。** 不给的话每勾一条
            // (集合换了个新实例)就重新淡一次,而「已选 3 项」正是要在淡不动的时候连续变。
            // 退场那一帧 AnimatedContent 手里仍是上一个非空集合,所以 ids 在那条分支里不为 null。
            // transitionSpec 不是组合上下文,主题里的 spec 要先在外面取出来。
            val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedContent(
                targetState = selectedIds,
                transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
                contentKey = { it != null },
                label = "history-top-bar",
            ) { ids ->
                if (ids == null) {
                    BilbyTopBar(
                        title = stringResource(Res.string.history_title),
                        onBack = onBack,
                        scrollBehavior = scrollBehavior,
                        actions = {
                            RefreshAction(state.refreshing, vm::refresh)
                            IconButton(
                                onClick = { selectedIds = emptySet() },
                                enabled = state.items.isNotEmpty() && !state.mutating,
                            ) {
                                Icon(
                                    Icons.Outlined.Checklist,
                                    contentDescription = stringResource(Res.string.action_select),
                                )
                            }
                            // 两个清空收进溢出菜单:M3 的 top app bar anatomy 里 headline 之后最多
                            // 两个 icon button,而这一页的常态还要留一个给「选择」。
                            IconButton(onClick = { menuExpanded = true }, enabled = !state.mutating) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(Res.string.history_more),
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.history_clear_finished)) },
                                    enabled = finished.isNotEmpty(),
                                    onClick = {
                                        menuExpanded = false
                                        confirmingClearFinished = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.history_clear_all)) },
                                    enabled = state.items.isNotEmpty(),
                                    onClick = {
                                        menuExpanded = false
                                        confirmingClearAll = true
                                    },
                                )
                            }
                        },
                    )
                } else {
                    // 多选时整条顶栏换掉,返回箭头改成"退出多选"(M3 的 contextual top app bar)。
                    BilbyTopBar(
                        title = stringResource(Res.string.history_selected_count, ids.size),
                        onBack = { selectedIds = null },
                        scrollBehavior = scrollBehavior,
                        actions = {
                            val all = state.items.map { it.oid }.toSet()
                            IconButton(onClick = { selectedIds = if (ids == all) emptySet() else all }) {
                                Icon(
                                    Icons.Outlined.SelectAll,
                                    contentDescription = stringResource(Res.string.history_select_all),
                                )
                            }
                            IconButton(
                                onClick = { confirmingDeleteSelected = true },
                                enabled = ids.isNotEmpty() && !state.mutating,
                            ) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = stringResource(Res.string.action_delete),
                                )
                            }
                        },
                    )
                }
            }
        },
    ) { insets ->
        HistoryScreen(
            state = state,
            onItemClick = { onVideoClick(it.bvid) },
            onLoadMore = vm::loadMore,
            onRetry = vm::retry,
            onRefresh = vm::refresh,
            selectedIds = selectedIds,
            onToggleSelection = { item ->
                val current = selectedIds ?: emptySet()
                selectedIds = if (item.oid in current) current - item.oid else current + item.oid
            },
            onDismissMutationError = vm::dismissMutationError,
            contentPadding = insets,
        )
    }

    // 三个确认框各说各的范围。删除不可撤销,而这三件事影响的条数各不相同 —— 合并成一句
    // "确定删除吗"就把范围这个唯一值得确认的东西省掉了。
    if (confirmingDeleteSelected) {
        HistoryConfirmDialog(
            message = stringResource(Res.string.history_delete_confirm, selected.size),
            onConfirm = {
                vm.delete(selected)
                selectedIds = null
            },
            onDismiss = { confirmingDeleteSelected = false },
        )
    }
    if (confirmingClearFinished) {
        HistoryConfirmDialog(
            message = stringResource(Res.string.history_clear_finished_confirm, finished.size),
            onConfirm = { vm.delete(finished) },
            onDismiss = { confirmingClearFinished = false },
        )
    }
    if (confirmingClearAll) {
        HistoryConfirmDialog(
            message = stringResource(Res.string.history_clear_all_confirm),
            onConfirm = {
                vm.clearAll()
                selectedIds = null
            },
            onDismiss = { confirmingClearAll = false },
        )
    }
}

/** 只有标题的确认框:陈述动作即止,不解释删除意味着什么。 */
@Composable
private fun HistoryConfirmDialog(message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(stringResource(Res.string.action_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/**
 * 消息中心与私信会话列表。**五格里只有第一格进页面就拉**,其余各自第一次被选中时才拉
 * (见 [MessageViewModel])。
 */
@Composable
private fun MessagesRoute(
    container: AppContainer,
    /** 右栏正开着的那段对话,列表里高亮它。没有右栏或没开着时为 null。 */
    selectedTalker: Long?,
    /** 停在私信那一格没有。只有那一格分栏,见 ListDetailScene。 */
    onWhisperTabShown: (Boolean) -> Unit,
    onOpenWhisper: (dev.bilby.data.WhisperSession) -> Unit,
    onOpenSpace: (Long) -> Unit,
    onOpenUri: (String) -> Unit,
    onOpenNotice: (dev.bilby.data.Notice) -> Unit,
    onBack: () -> Unit,
) {
    val vm: MessageViewModel = viewModel(
        factory = viewModelFactory { initializer { MessageViewModel(container.messageRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val onWhispers = state.tab == MessageTab.Whispers
    LaunchedEffect(onWhispers) { onWhisperTabShown(onWhispers) }
    MessageScreen(
        state = state,
        selectedTalker = selectedTalker,
        onSelectTab = vm::selectTab,
        onLoadMore = vm::loadMore,
        onRefresh = vm::refresh,
        onOpenWhisper = onOpenWhisper,
        onOpenSpace = onOpenSpace,
        onOpenUri = onOpenUri,
        onOpenNotice = onOpenNotice,
        onBack = onBack,
    )
}

/** 私信右栏没有对话时的那一句,消息页与推送页共用。 */
@Composable
private fun MessageDetailPlaceholder() {
    EmptyState(
        stringResource(Res.string.message_detail_placeholder),
        modifier = Modifier.fillMaxSize(),
        icon = Icons.Outlined.Forum,
    )
}

/** 一个私信会话。名字由路由带,顶栏第一帧就有标题。 */
@Composable
private fun WhisperRoute(
    container: AppContainer,
    key: Whisper,
    onOpenSpace: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenArticle: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    /** 推送视图顶栏那一颗:进同一个会话的完整聊天。 */
    onOpenFullChat: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: WhisperViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                WhisperViewModel(
                    key.talkerId,
                    key.talkerName,
                    key.talkerFaceUrl,
                    key.isSystem,
                    container.messageRepository,
                    container.settings,
                )
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    if (key.upPushes) {
        PushFeedScreen(
            state = state,
            onRetry = vm::load,
            onLoadOlder = vm::loadOlder,
            onOpenSpace = onOpenSpace,
            onOpenVideo = onOpenVideo,
            onOpenLink = onOpenLink,
            onOpenFullChat = onOpenFullChat,
            onBack = onBack,
        )
        return
    }
    WhisperScreen(
        state = state,
        onSend = vm::send,
        onRetry = vm::load,
        onLoadOlder = vm::loadOlder,
        onOpenSpace = onOpenSpace,
        onOpenVideo = onOpenVideo,
        onOpenArticle = onOpenArticle,
        onOpenLink = onOpenLink,
        onBack = onBack,
    )
}

/**
 * 已缓存的视频。点一条走的是普通的播放页 —— **离线不是另一种播放**,服务在取流之前会先看
 * 这一条有没有缓存,命中就用本地文件(见 `AudioPlaybackService.resolveStream`)。所以这里不需要
 * 一个"离线播放器",也不需要给播放页传什么标记。
 */
@Composable
private fun OfflineRoute(
    container: AppContainer,
    onPlay: (OfflineItem) -> Unit,
    onListenAll: (OfflineItem) -> Unit,
    onBack: () -> Unit,
) {
    val vm: OfflineViewModel = viewModel(
        factory = viewModelFactory {
            initializer { OfflineViewModel(container.offlineDownloader, container.offlineStore) }
        },
    )
    val items by vm.items.collectAsStateWithLifecycle()
    val usedBytes by vm.usedBytes.collectAsStateWithLifecycle()

    // null 表示不在多选态,理由见 [OfflineScreen] 那个参数的注释。
    var selectedIds by remember { mutableStateOf<Set<String>?>(null) }
    var confirmingBatchDelete by remember { mutableStateOf(false) }
    val selected = items.filter { it.id in selectedIds.orEmpty() }
    // 列表里已经没有的 id 要跟着掉:删完之后集合里留着几个死 id,顶栏就一直显示"已选 3 项"。
    LaunchedEffect(items) { selectedIds = selectedIds?.intersect(items.map { it.id }.toSet()) }
    BackHandler(enabled = selectedIds != null) { selectedIds = null }

    // 见 [SearchResultRoute];两条顶栏同接一个 behavior,同历史记录页。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // 淡入淡出而不是硬切,`contentKey` 认"在不在多选态" —— 判据与理由逐条同
            // [HistoryRoute],两页的多选顶栏本来就该是同一个做法。
            // transitionSpec 不是组合上下文,主题里的 spec 要先在外面取出来。
            val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedContent(
                targetState = selectedIds,
                transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
                contentKey = { it != null },
                label = "offline-top-bar",
            ) { ids ->
                if (ids == null) {
                    BilbyTopBar(
                        title = stringResource(Res.string.offline_title),
                        onBack = onBack,
                        scrollBehavior = scrollBehavior,
                        actions = {
                            // 多选此前只能长按进入,而长按没有任何视觉提示。照历史记录页的做法
                            // 在顶栏放一个入口,长按保留。
                            IconButton(
                                onClick = { selectedIds = emptySet() },
                                enabled = items.isNotEmpty(),
                            ) {
                                Icon(
                                    Icons.Outlined.Checklist,
                                    contentDescription = stringResource(Res.string.action_select),
                                )
                            }
                        },
                    )
                } else {
                    // 多选时整条顶栏换掉,返回箭头改成"退出多选"。这是 M3 的 contextual top app bar:
                    // 顶栏是当前上下文里能做什么的唯一说明,多选期间那个上下文变了。
                    BilbyTopBar(
                        title = stringResource(Res.string.offline_selected_count, ids.size),
                        onBack = { selectedIds = null },
                        scrollBehavior = scrollBehavior,
                        actions = {
                            val all = items.map { it.id }.toSet()
                            IconButton(onClick = { selectedIds = if (ids == all) emptySet() else all }) {
                                Icon(
                                    Icons.Outlined.SelectAll,
                                    contentDescription = stringResource(Res.string.offline_sheet_select_all),
                                )
                            }
                            IconButton(
                                enabled = selected.isNotEmpty(),
                                onClick = {
                                    // 一个字节都还没下的那些直接删,理由同单条(见 OfflineScreen)。
                                    if (selected.all { it.status == OfflineStatus.Queued }) {
                                        vm.deleteAll(selected)
                                        selectedIds = null
                                    } else {
                                        confirmingBatchDelete = true
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = stringResource(Res.string.action_delete),
                                )
                            }
                        },
                    )
                }
            }
        },
    ) { insets ->
        OfflineScreen(
            items = items,
            usedBytes = usedBytes,
            onPlay = onPlay,
            onDelete = vm::delete,
            onRetry = vm::retry,
            onListenAll = onListenAll,
            selectedIds = selectedIds,
            onToggleSelection = { item ->
                val current = selectedIds.orEmpty()
                selectedIds = if (item.id in current) current - item.id else current + item.id
            },
            contentPadding = insets,
        )
    }

    if (confirmingBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmingBatchDelete = false },
            title = { Text(stringResource(Res.string.offline_delete_batch_title)) },
            text = { Text(stringResource(Res.string.offline_delete_batch_message, selected.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingBatchDelete = false
                    vm.deleteAll(selected)
                    selectedIds = null
                }) { Text(stringResource(Res.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingBatchDelete = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

/** 稍后再看列表底部让出的高度:FAB 56dp 加上它离屏幕底边的 16dp,再留一点缝。 */
private val ToViewFabClearance = 88.dp

/**
 * 稍后再看的列表。两种清空分开放,按用得多少排:
 * - **清空已看完是 FAB。** 稍后再看是一张要常常清的清单,看完一批清一批是这一页最常做的事,
 *   它值得一个拇指够得着的位置。只在有已看完的条目时出现 —— 没有可清的时候一个按不动的
 *   FAB 只是挡住最后一行。
 * - **清空已失效在顶栏右上角**,一个图标。失效是偶尔才攒起来的,不该和上面那个抢位置。
 */
@Composable
private fun ToViewListRoute(
    container: AppContainer,
    onVideoClick: (String, QueueContext) -> Unit,
    onListen: (String, QueueContext) -> Unit,
    onBack: () -> Unit,
) {
    val vm: ToViewViewModel = viewModel(
        factory = viewModelFactory { initializer { ToViewViewModel(container.toViewRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmingClear by remember { mutableStateOf<ToViewClear?>(null) }

    // 移出成功之后给一次撤销。稍后再看是远程列表,删掉就得回 B 站重新找一遍那条视频,
    // 而这个删除按钮紧挨着整行的可点区。
    val removedMessage = stringResource(Res.string.toview_removed)
    val undoLabel = stringResource(Res.string.action_undo)
    LaunchedEffect(state.lastRemoved) {
        val removed = state.lastRemoved ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = removedMessage,
            actionLabel = undoLabel,
        )
        if (result == SnackbarResult.ActionPerformed) vm.undoDelete(removed) else vm.consumeRemoved()
    }

    val notice = state.notice
    val noticeText = notice?.let { stringResource(it.action, stringResource(it.reason)) }
    LaunchedEffect(notice?.id) {
        if (notice == null || noticeText == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(noticeText)
        vm.dismissNotice(notice)
    }

    // 见 [SearchResultRoute]。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(
                title = stringResource(Res.string.tab_toview),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            ) {
                RefreshAction(refreshing = state.loading && state.items.isNotEmpty(), onRefresh = vm::refresh)
                IconButton(
                    onClick = { confirmingClear = ToViewClear.Invalid },
                    enabled = !state.clearing && state.items.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Outlined.LinkOff,
                        contentDescription = stringResource(Res.string.toview_clear_invalid),
                    )
                }
            }
        },
        floatingActionButton = {
            val finishedCount = state.items.count { it.isFinished }
            AnimatedVisibility(
                visible = finishedCount > 0 && !state.clearing,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { confirmingClear = ToViewClear.Finished },
                    icon = { Icon(Icons.Outlined.DoneAll, contentDescription = null) },
                    text = { Text(stringResource(Res.string.toview_clear_finished)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        // insets 交给列表当 contentPadding,不在外面套 padding:列表要画到导航栏底下,
        // 最后一行靠 contentPadding 让开,同其他列表页。底部再让出 FAB 的高度,最后一行的
        // 移出按钮才不会压在 FAB 底下。
        val layoutDirection = LocalLayoutDirection.current
        ToViewScreen(
            state = state,
            onDelete = { vm.delete(it) },
            onItemClick = { onVideoClick(it.bvid, QueueContext.ToView(state.asc)) },
            onRetry = vm::retry,
            onListenAll = { onListen(it.bvid, QueueContext.ToView(state.asc)) },
            onAscChanged = vm::setAsc,
            onRefresh = vm::refresh,
            contentPadding = PaddingValues(
                start = insets.calculateStartPadding(layoutDirection),
                top = insets.calculateTopPadding(),
                end = insets.calculateEndPadding(layoutDirection),
                bottom = insets.calculateBottomPadding() + ToViewFabClearance,
            ),
        )
    }

    // 批量清空不给撤销:一次删掉的可能是几十条,撤销要逐条加回去,中途失败留下的是半截列表。
    // 所以这一条走确认,和缓存删除、拉黑那几处一个待遇。
    confirmingClear?.let { kind ->
        ToViewClearDialog(
            kind = kind,
            finishedCount = state.items.count { it.isFinished },
            onConfirm = {
                confirmingClear = null
                vm.clear(kind)
            },
            onDismiss = { confirmingClear = null },
        )
    }
}

@Composable
private fun FavFolderRoute(
    container: AppContainer,
    mediaId: Long,
    title: String,
    onVideoClick: (String, QueueContext) -> Unit,
    onListen: (String, QueueContext) -> Unit,
    onBack: () -> Unit,
) {
    val vm: FavFolderViewModel = viewModel(
        key = "fav-$mediaId",
        factory = viewModelFactory { initializer { FavFolderViewModel(mediaId, container.favRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val editor by vm.manager.editor.collectAsStateWithLifecycle()
    val deletion by vm.manager.deletion.collectAsStateWithLifecycle()
    // 路由带来的标题只管第一帧;接口回来之后以它为准 —— 名字可能在这一页或别处改过。
    val folderTitle = state.info?.title ?: title

    // 这个收藏夹没了,这一页也就没有东西可看。
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }

    // 队列就是眼前这份:排序与生效中的搜索词一起带上,页号按这条在列表里的位置算。
    val queueContext = { item: FavVideo ->
        val index = state.items.indexOf(item).coerceAtLeast(0)
        val page = index / FavRepository.Paging.PAGE_SIZE + 1
        QueueContext.FavFolder(mediaId, folderTitle, page, state.order, state.appliedKeyword)
    }

    // 见 [SearchResultRoute]。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            FavFolderTopBar(
                title = folderTitle,
                folder = state.info,
                keyword = state.keyword,
                appliedKeyword = state.appliedKeyword,
                cleaning = state.cleaning,
                refreshing = state.refreshing,
                onRefresh = vm::refresh,
                onKeywordChange = vm::onKeywordChange,
                onSearch = vm::search,
                onCloseSearch = vm::closeSearch,
                onEdit = vm.manager::startEdit,
                onCleanInvalid = vm::cleanInvalid,
                onDelete = vm.manager::startDelete,
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { insets ->
        FavFolderScreen(
            state = state,
            editor = editor,
            deletion = deletion,
            onItemClick = { item -> onVideoClick(item.bvid, queueContext(item)) },
            onListen = { item -> onListen(item.bvid, queueContext(item)) },
            onOrderChanged = vm::setOrder,
            onLoadMore = vm::loadMore,
            onRetry = vm::retry,
            onRefresh = vm::refresh,
            onNoticeShown = vm::dismissNotice,
            editorActions = vm.manager.editorActions,
            deletionActions = vm.manager.deletionActions,
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
        factory = viewModelFactory {
            initializer { FollowingsViewModel(container.followRepository, container.relationRepository) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    // 见 [SearchResultRoute]。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    // 宽屏时刷新与排序进名单上方的筛选行(见 WideFilterRow),搜索框换到顶栏正中:
    // 放在筛选行里,48dp 的输入框和 32dp 的芯片挤在一条线上,高低不齐。
    val wide = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(
                title = stringResource(Res.string.followings_title),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                center = if (wide) {
                    {
                        // 外层定一半宽,里面再封顶。反过来写 fillMaxWidth(0.5f).widthIn(max) 封不住,
                        // 理由见 AdaptiveContent。
                        Box(modifier = Modifier.fillMaxWidth(0.5f), contentAlignment = Alignment.Center) {
                            FollowingsSearchField(
                                query = state.query,
                                onSearch = vm::search,
                                modifier = Modifier.widthIn(max = FollowingsSearchFieldMaxWidth).fillMaxWidth(),
                            )
                        }
                    }
                } else {
                    null
                },
                actions = {
                    if (wide) return@BilbyTopBar
                    RefreshAction(state.refreshing, vm::refresh)
                    // 排序放顶栏右端,省下列表上方单独一行。仍是 SortMenu 那颗写着当前档位的
                    // 下拉,不换成一个图标:图标读不出现在按什么排。
                    if (state.canSort) {
                        SortMenu(
                            options = FollowOrderOptions,
                            selected = state.order,
                            onSelect = vm::selectOrder,
                        )
                    }
                },
            )
        },
    ) { insets ->
        FollowingsScreen(
            state = state,
            onUpClick = onUpClick,
            onLoadMore = vm::loadMore,
            onRetry = vm::retry,
            onSelectSource = vm::selectSource,
            onSearch = vm::search,
            onRefresh = vm::refresh,
            onOpenGroupManager = vm::openGroupManager,
            onCloseGroupManager = vm::closeGroupManager,
            onCreateGroup = vm::createGroup,
            onRenameGroup = vm::renameGroup,
            onDeleteGroup = vm::deleteGroup,
            onOpenGroupPicker = vm::openGroupPicker,
            onCloseGroupPicker = vm::closeGroupPicker,
            onToggleGroup = vm::toggleGroup,
            onSaveGroups = vm::saveGroups,
            onBlock = vm::block,
            onUnfollow = vm::unfollow,
            onSelectOrder = vm::selectOrder,
            onEnsureSpecial = vm::ensureSpecial,
            onLoadMoreSpecial = vm::loadMoreSpecial,
            contentPadding = insets,
        )
    }
}

@Composable
private fun SpaceRoute(
    container: AppContainer,
    mid: Long,
    onVideoClick: (String, QueueContext) -> Unit,
    onListenUp: (String, QueueContext) -> Unit,
    onLiveClick: (Long) -> Unit,
    onCollectionClick: (SpaceCollectionItem) -> Unit,
    onDynamicAction: (DynamicAction) -> Unit,
    onBack: () -> Unit,
) {
    val vm: SpaceViewModel = viewModel(
        key = "space-$mid",
        factory = viewModelFactory {
            initializer {
                SpaceViewModel(
                    mid,
                    container.spaceRepository,
                    container.relationRepository,
                    container.dynamicRepository,
                    container.followRepository,
                    container.settings,
                )
            }
        },
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
        onCollectionClick = onCollectionClick,
        // 队列就是投稿栏眼前这份:排序与生效中的搜索词一起带上,页号按这条在列表里的位置算。
        onVideoClick = { item ->
            val index = state.archives.items.indexOf(item).coerceAtLeast(0)
            onVideoClick(item.bvid, state.archives.queueContext(mid, page = index / SpaceRepository.ARCHIVE_PAGE_SIZE + 1))
        },
        onDynamicAction = onDynamicAction,
        onDynamicVideoClick = { item ->
            item.video?.let { video -> onVideoClick(video.bvid, QueueContext.UpDynamics(mid, item.pageOffset)) }
        },
        onLikeDynamic = vm::likeDynamic,
        onLiveClick = onLiveClick,
        onToggleFollow = vm::toggleFollow,
        onSetBlocked = vm::setBlocked,
        onOpenGroupPicker = vm::openGroupPicker,
        onCloseGroupPicker = vm::closeGroupPicker,
        onToggleGroup = vm::toggleGroup,
        onSaveGroups = vm::saveGroups,
        // 听这位 UP 的投稿:挑第一条进播放页并直接以听的状态打开。**宿主只有播放页一个** ——
        // 空间页不承载听视频界面,否则又会多出一处需要单独维护的生命周期。
        onListenUp = {
            state.archives.items.firstOrNull()?.let { onListenUp(it.bvid, state.archives.queueContext(mid, page = 1)) }
        },
        onBack = onBack,
        onRetry = vm::retry,
        mid = mid,
        onRefresh = vm::refresh,
        onDynamicsSheetOpenChange = vm::setDynamicsSheetOpen,
        onDynamicsSheetWidthChange = vm::setDynamicsSheetWidth,
    )
}

/**
 * 合集/系列目录。VM 的 key 带上合集身份而不是只带 mid:同一个 UP 的两个合集是两份目录,
 * 共用一个 VM 的话第二个会读到第一个的列表。
 */
@Composable
private fun CollectionRoute(
    container: AppContainer,
    key: CollectionContents,
    onVideoClick: (String, QueueContext) -> Unit,
    onBack: () -> Unit,
) {
    val vm: CollectionViewModel = viewModel(
        key = "collection-${key.mid}-${key.isSeason}-${key.id}",
        factory = viewModelFactory {
            initializer {
                CollectionViewModel(key.mid, key.id, key.isSeason, container.spaceRepository)
            }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    CollectionScreen(
        title = key.name,
        state = state,
        onBack = onBack,
        onLoadMore = { vm.loadMore() },
        onRefresh = vm::refresh,
        onVideoClick = { item ->
            val index = state.items.indexOf(item).coerceAtLeast(0)
            val page = index / SpaceRepository.COLLECTION_PAGE_SIZE + 1
            onVideoClick(item.bvid, QueueContext.Collection(key.mid, key.id, key.isSeason, key.name, page))
        },
    )
}

@Composable
private fun OtherDynamicsRoute(
    container: AppContainer,
    onAction: (DynamicAction) -> Unit,
    onBack: () -> Unit,
) {
    val vm: OtherDynamicsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { OtherDynamicsViewModel(container.dynamicFeedStore, container.dynamicRepository) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    OtherDynamicsScreen(
        state = state,
        onBack = onBack,
        onRefresh = vm::refresh,
        onLoadMore = vm::loadMore,
        onRetry = vm::retry,
        onAction = onAction,
        onLike = vm::like,
    )
}

/**
 * 一条动态本身,带评论区。
 *
 * **进来先拉一次详情,不接列表页递过来的卡片。** 评论区的 oid 与 type 只在服务端下发的
 * `basic` 里(notes/dynamic-cards.md),而列表项的 `basic` 可能缺;让正常路径和那条回落
 * 走同一次请求,回落才会真的被走到 —— 只在缺失时才用的分支,坏了没人会发现。这一页本来
 * 也需要这次请求:列表里的正文可能只是摘要。
 */
@Composable
private fun DynamicDetailRoute(
    container: AppContainer,
    id: String,
    onVideoClick: (String) -> Unit,
    onUpClick: (Long) -> Unit,
    onArticleClick: (String, Boolean) -> Unit,
    onOpenLink: (String) -> Unit,
    onBack: () -> Unit,
) {
    val system = LocalSystemActions.current
    val scope = rememberCoroutineScope()
    val vm: DynamicDetailViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DynamicDetailViewModel(container.dynamicRepository, id) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // 评论区要等 oid 落地才建得起来,和播放页同一个形状。拿不到就是这条动态没有评论区
    // (comment_type 为 0,或者 comment_id_str 不是一串数字)。
    val reference = state.card?.interaction?.takeIf { it.hasComments }
    val oid = reference?.commentId?.toLongOrNull()
    val commentVm: CommentViewModel? = if (oid != null) {
        viewModel(
            key = "dyn-comment",
            factory = viewModelFactory {
                initializer {
                    CommentViewModel(container.commentRepository, oid, reference.commentType, myMid = container::myMid)
                }
            },
        )
    } else {
        null
    }
    val commentState by (commentVm?.state ?: MutableStateFlow(CommentUiState()))
        .collectAsStateWithLifecycle()

    DynamicDetailScreen(
        state = state,
        commentState = commentState,
        onBack = onBack,
        onRetry = vm::retry,
        onAction = { action ->
            when (action) {
                is DynamicAction.OpenVideo -> onVideoClick(action.bvid)
                is DynamicAction.OpenUser -> onUpClick(action.mid)
                is DynamicAction.OpenArticle -> onArticleClick(action.id, action.isRead)
                // 已经在一条动态里了,再点进"这条动态"没有去处。其余的交给公共那段。
                is DynamicAction.OpenDynamic -> Unit
                else -> handleDynamicAction(action, system, container, scope) { }
            }
        },
        onLike = vm::like,
        onCommentSort = { commentVm?.setSort(it) },
        onCommentRefresh = { commentVm?.refresh() },
        onCommentLoadMore = { commentVm?.loadMore() },
        onOpenLink = onOpenLink,
        onExpandReplies = { commentVm?.expandReplies(it) },
        onSendComment = { text, replyTo -> commentVm?.send(text, replyTo) },
        onLikeComment = { commentVm?.like(it) },
        onDeleteComment = { commentVm?.delete(it) },
    )
}

/**
 * 收藏夹列表。新建、改名、删除都落在这里 —— 收藏夹是用户自己建的有限存货,和稍后再看同一栏。
 */
@Composable
private fun FavFoldersRoute(
    container: AppContainer,
    onOpenFolder: (FavFolderDetail) -> Unit,
    onBack: () -> Unit,
) {
    val vm: FavFoldersViewModel = viewModel(
        factory = viewModelFactory { initializer { FavFoldersViewModel(container.favRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val editor by vm.manager.editor.collectAsStateWithLifecycle()
    val deletion by vm.manager.deletion.collectAsStateWithLifecycle()
    // 从某个收藏夹返回时这一页重新进组合,那正是列表最可能过期的时候。判据见 onEnter。
    LaunchedEffect(Unit) { vm.onEnter() }
    // 见 [SearchResultRoute]。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(
                title = stringResource(Res.string.fav_folders_title),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            ) {
                // 新建是这一页的页级操作,放顶栏。它原先是右下角的 FAB:FAB 要让开列表最后一行,
                // 而一个不常用的操作不值得常驻在内容上面。
                RefreshAction(state.refreshing, vm::refresh)
                IconButton(onClick = vm.manager::startCreate) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(Res.string.fav_folder_create))
                }
            }
        },
    ) { insets ->
        FavFoldersScreen(
            state = state,
            editor = editor,
            deletion = deletion,
            onOpenFolder = onOpenFolder,
            onEdit = vm.manager::startEdit,
            onDelete = vm.manager::startDelete,
            onLoadMore = vm::loadMore,
            onRetry = vm::retry,
            onRefresh = vm::refresh,
            editorActions = vm.manager.editorActions,
            deletionActions = vm.manager.deletionActions,
            contentPadding = insets,
        )
    }
}

/** 黑名单。名字和头像都不可点:拉黑之后对方的空间本来就进不去。 */
@Composable
private fun BlacklistRoute(container: AppContainer, onBack: () -> Unit) {
    val vm: BlacklistViewModel = viewModel(
        factory = viewModelFactory { initializer { BlacklistViewModel(container.relationRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    // 见 [SearchResultRoute]。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(
                title = stringResource(Res.string.blacklist_title),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { insets ->
        BlacklistScreen(
            state = state,
            onUnblock = vm::unblock,
            onLoadMore = vm::loadMore,
            onRetry = vm::retry,
            contentPadding = insets,
        )
    }
}

/**
 * 一张动态卡片被点开时去哪儿。**这一段在 backstack 这一层,不在卡片里**:卡片只说"要去
 * 哪种东西",怎么去是导航的事。
 *
 * [DynamicAction.OpenUrl] 先按站内解析,认不出来才交给浏览器 —— 动态里贴的多是 BV 号和
 * 别人的空间,那些在这个应用里有落点;番剧、活动页没有,那才是浏览器该接手的。
 */
private fun handleDynamicAction(
    action: DynamicAction,
    system: SystemActions,
    container: AppContainer,
    scope: CoroutineScope,
    push: (NavKey) -> Unit,
) {
    when (action) {
        is DynamicAction.OpenVideo -> push(Video(action.bvid))
        is DynamicAction.OpenLive -> push(LiveRoom(action.roomId))
        // 房间号要现查(预约卡片只给 mid)。查不到就退到这个人的空间页 —— 那是"这个人在哪"
        // 的答案,而一个查不出房间的 mid 多半是没开通过直播间。
        is DynamicAction.OpenLiveOfUser -> scope.launch {
            val roomId = container.spaceRepository.liveRoomId(action.mid)
            push(if (roomId != null) LiveRoom(roomId) else Space(action.mid))
        }
        is DynamicAction.OpenArticle -> push(ArticlePage(action.id, action.isRead))
        is DynamicAction.OpenUser -> push(Space(action.mid))
        is DynamicAction.OpenDynamic -> push(DynamicDetail(action.id))
        is DynamicAction.OpenUrl -> {
            val destination = BilbyLink.destinationOf(action.url)
            if (destination != null) push(destination) else system.openInBrowser(action.url)
        }
    }
}

/**
 * 专栏阅读页。VM 的 key 带上 `isRead`:同一串数字在两套编号里是两篇不同的文章,只用 id
 * 做 key 会让先打开的那篇被后打开的那篇读到。
 */
@Composable
private fun ArticleRoute(
    container: AppContainer,
    key: ArticlePage,
    onOpenLink: (String) -> Unit,
    onUpClick: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm: ArticleViewModel = viewModel(
        key = "article-${key.isRead}-${key.id}",
        factory = viewModelFactory {
            initializer { ArticleViewModel(key.id, key.isRead, container.articleRepository) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    ArticleScreen(
        state = state,
        onBack = onBack,
        onRetry = vm::retry,
        onLinkClick = onOpenLink,
        onMentionClick = onUpClick,
    )
}

@Composable
private fun VideoRoute(
    container: AppContainer,
    key: Video,
    onUpClick: (Long) -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenQueueSource: (QueueSource) -> Unit,
    onOpenVideo: (String) -> Unit,
    onSearchTag: (String) -> Unit,
    onBack: () -> Unit,
) {
    val frame = remember(key.frame) { PlayerFrame(key.frame, key.context) }

    /**
     * 这一页此刻显示哪条视频。**这一页是活帧时就是队列当前条**,页面跟着队列走,不是反过来:
     * 推队列的可能是通知栏的下一条、耳机线控、听视频里点的某一项、或者一条播完自动连播,
     * 这些路径没有一条经过界面。
     *
     * 不是活帧时停在离开时那一条。它随页面保存:返回到这一页时服务按快照换回队列,进程被杀
     * 之后则按它和入口上下文重建(见 AudioPlaybackService.activateFrame)。
     *
     * 原先靠路由 bvid 与一道"队列报出这一条之后才开始跟"的门同步,那道门是 `remember`,
     * 进 UP 主页一趟就丢,回来之后页面不再跟队列,而重连控制器时按页面手上的旧 bvid 重发
     * 打开命令,把播放器拽回旧的那条。帧 id 让"跟不跟"只剩一个判据。
     *
     * 切集**不进 backstack**:合集里的每一集互为平级,换一集不是进了一层。
     *
     * **换页不做转场。** 横滑表达的是"我横着挪了一格",那是给用户手势配的;自动连播不是
     * 用户动作,滑一下会让人以为自己划了屏。
     */
    var shownBvid by rememberSaveable(key.frame) { mutableStateOf(key.bvid) }
    val audioState by LocalPlaybackHost.current.state.collectAsStateWithLifecycle()
    val isLiveFrame = audioState.queue?.frameId == key.frame
    val queueCurrent = audioState.queue?.current?.bvid
    LaunchedEffect(isLiveFrame, queueCurrent) {
        if (isLiveFrame && queueCurrent != null) shownBvid = queueCurrent
    }

    /**
     * 听视频**提到切集之外**。
     *
     * 它是播放器的一种模式(把视频轨关掉),不是某一条视频的页面状态:队列往前走一条时
     * 下面那个 VideoPane 会整个换掉,状态留在里面的话,听着听着自动连播到下一条就会
     * 被踢回有画面的界面。
     *
     * 提到这里仍然不是导航目的地 —— 它只是换了个持有者,backstack 上依旧只有一个播放页。
     * key 是帧:同一页内切集不退出听视频,而点开另一条视频是新的一帧,回到 [Video.listening]。
     */
    var listening by rememberSaveable(key.frame) { mutableStateOf(key.listening) }

    VideoPane(
        container = container,
        bvid = shownBvid,
        frame = frame,
        listening = listening,
        onListeningChange = { listening = it },
        onUpClick = onUpClick,
        onOpenLink = onOpenLink,
        onOpenQueueSource = onOpenQueueSource,
        onOpenVideo = onOpenVideo,
        onSearchTag = onSearchTag,
        onBack = onBack,
    )
}

@Composable
private fun VideoPane(
    container: AppContainer,
    bvid: String,
    frame: PlayerFrame,
    listening: Boolean,
    onListeningChange: (Boolean) -> Unit,
    onUpClick: (Long) -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenQueueSource: (QueueSource) -> Unit,
    onOpenVideo: (String) -> Unit,
    onSearchTag: (String) -> Unit,
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
                    container.videoActionRepository,
                    container.settings,
                    container.sponsorBlockRepository,
                    container.toViewRepository,
                    container.relationRepository,
                    container.subtitleRepository,
                    container.danmakuRepository,
                    container.offlineDownloader,
                    container.offlineStore,
                    container.platform.playback,
                    container.persistScope,
                )
            }
        },
    )
    // 幂等,所以不需要在这里记"上一次是哪条"——那份状态正是以前散在 Compose 层、却管不了
    // 旧实例死活的东西。
    LaunchedEffect(bvid) { vm.switchTo(bvid) }
    val state by vm.state.collectAsStateWithLifecycle()
    val videoTags by vm.videoTags.collectAsStateWithLifecycle()
    val related by vm.related.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val relation by vm.relation.collectAsStateWithLifecycle()
    val coinAttempt by vm.coinAttempt.collectAsStateWithLifecycle()
    val tripleOutcome by vm.tripleOutcome.collectAsStateWithLifecycle()
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
    val cached by vm.cached.collectAsStateWithLifecycle()
    val specialDanmakuPool by vm.specialDanmakuPool.collectAsStateWithLifecycle()
    val danmakuSend by vm.danmakuSend.collectAsStateWithLifecycle()
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
            factory = viewModelFactory {
                initializer { CommentViewModel(container.commentRepository, aid, myMid = container::myMid) }
            },
        )
        LaunchedEffect(aid) { commentViewModel.switchTo(aid) }
        commentViewModel
    }
    val commentState = commentVm?.state?.collectAsStateWithLifecycle()?.value ?: CommentUiState()

    VideoScreen(
        bvid = bvid,
        frame = frame,
        state = state,
        videoTags = videoTags,
        onLoadTags = vm::loadVideoTags,
        onTagClick = onSearchTag,
        related = related,
        commentState = commentState,
        sponsorSegments = sponsorSegments,
        onFindRelated = vm::findRelated,
        cached = cached,
        onCacheSelection = vm::cacheSelection,
        onUpClick = onUpClick,
        onOpenQueueSource = onOpenQueueSource,
        followState = followState,
        onToggleFollow = vm::toggleFollow,
        upCard = upCard,
        relation = relation,
        favFolders = favFolders,
        onLike = vm::toggleLike,
        onTriple = vm::triple,
        tripleOutcome = tripleOutcome,
        addedToView = addedToView,
        onToggleToView = vm::toggleToView,
        onCoin = vm::coin,
        coinAttempt = coinAttempt,
        onCoinDialogClosed = vm::clearCoinAttempt,
        onOpenFavPicker = vm::openFavPicker,
        onFavConfirm = vm::confirmFavorite,
        onRelatedVideoClick = onOpenVideo,
        onOpenLink = onOpenLink,
        onCommentSort = { commentVm?.setSort(it) },
        onCommentRefresh = { commentVm?.refresh() },
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
        danmakuEditor = vm.danmakuEditor,
        danmakuPool = danmakuPool,
        specialDanmakuPool = specialDanmakuPool,
        selfDanmaku = vm.selfDanmaku,
        danmakuSend = danmakuSend,
        onSendDanmaku = vm::sendDanmaku,
        onDanmakuSendConsumed = vm::clearDanmakuSend,
        fastForwardSpeed = playerPrefs.fastForwardSpeed,
    )
}

/**
 * lateral(同级平移)没有在这里定义 spec:它由 `HorizontalPager` 自己承担,而规范要的正是
 * pager 的默认行为 —— "elements are grouped and slide in unison",并且**不加淡入淡出**
 * ("Fading content as it slides makes the peer relationship and swipe gesture less obvious")。
 */
