package dev.bilby.ui

import dev.bilby.ui.components.BilbySearchTopBar
import dev.bilby.ui.components.EditableFocusTracker
import dev.bilby.ui.components.LocalCopyNotice
import dev.bilby.ui.components.LocalEditableFocusTracker
import dev.bilby.ui.components.LocalPageRefreshRegistry
import dev.bilby.ui.components.LocalPageSearchRegistry
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.NeedsCopyNotice
import dev.bilby.ui.components.PageSearchRegistry
import dev.bilby.ui.components.PointerSource
import dev.bilby.ui.components.RegisterPageRefresh
import dev.bilby.ui.components.RegisterPageSearch
import dev.bilby.ui.components.ShortcutsSheet
import dev.bilby.ui.components.readClipboardText
import dev.bilby.ui.components.rememberPageRefreshRegistry
import dev.bilby.ui.components.trackPointerSource
import dev.bilby.data.HistoryItem
import dev.bilby.data.SidePanelId
import dev.bilby.data.SideSheetPrefs
import dev.bilby.ui.search.directDestination
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenLoading
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import dev.bilby.data.AccountInfo
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.components.SkeletonPulse
import dev.bilby.ui.components.skeleton
import dev.bilby.ui.profile.AccountIdentityContextMenu
import dev.bilby.ui.profile.ProfileUiState
import dev.bilby.ui.profile.formatSegmentValue
import dev.bilby.ui.theme.interactiveCard
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
import dev.bilby.data.FeedUpStripPosition
import dev.bilby.data.NavigationPrefs
import dev.bilby.data.RailUtilityId
import dev.bilby.data.RootTabId
import dev.bilby.data.StartupDestination
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import dev.bilby.AppContainer
import dev.bilby.AppContainerOwner
import dev.bilby.BiliLog
import dev.bilby.resources.*
import org.jetbrains.compose.resources.StringResource
import dev.bilby.agent.AgentIntent
import dev.bilby.api.BiliResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dev.bilby.ui.article.ArticleScreen
import dev.bilby.ui.article.ArticleViewModel
import dev.bilby.ui.dynamic.DynamicAction
import dev.bilby.ui.dynamic.DynamicDetailScreen
import dev.bilby.ui.dynamic.DynamicDetailViewModel
import dev.bilby.ui.dynamic.OtherDynamicsScreen
import dev.bilby.ui.dynamic.OtherDynamicsViewModel
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.DesktopMenuMotionTheme
import dev.bilby.ui.components.DestructiveConfirmButton
import dev.bilby.ui.components.menuItemShape
import dev.bilby.BvidCodec
import dev.bilby.ui.comment.CommentThreadRoute
import dev.bilby.ui.comment.CommentUiState
import dev.bilby.ui.comment.CommentViewModel
import dev.bilby.data.FavFolderDetail
import dev.bilby.data.LocalEmoteRepository
import dev.bilby.data.NightWindowStatus
import dev.bilby.data.PlayerPrefs
import dev.bilby.data.evaluateNightMode
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.widthIn
import dev.bilby.data.CustomQueueEntry
import dev.bilby.player.PlaybackCommand
import dev.bilby.player.PlayerConnection
import dev.bilby.player.PlayerHandle
import dev.bilby.player.SleepTimerMode
import dev.bilby.ui.components.LocalQueueActions
import dev.bilby.ui.components.PaneSheet
import dev.bilby.ui.components.QueueMenuActions
import dev.bilby.ui.player.EpisodeList
import dev.bilby.ui.player.EpisodeRow
import dev.bilby.ui.player.EpisodeTarget
import dev.bilby.ui.player.QueueEditActions
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Dimens
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
import dev.bilby.ui.message.MessageViewModel
import dev.bilby.ui.message.WhisperScreen
import dev.bilby.ui.message.PushFeedScreen
import dev.bilby.ui.message.WhisperViewModel
import dev.bilby.ui.profile.ProfileViewModel
import dev.bilby.ui.search.SearchChatScreen
import dev.bilby.ui.search.SearchChatViewModel
import dev.bilby.ui.search.AgentActions
import dev.bilby.ui.search.SearchResultActions
import dev.bilby.ui.search.SearchResultScreen
import dev.bilby.ui.search.SearchResultViewModel
import dev.bilby.ui.search.SearchTab
import dev.bilby.ui.player.LocalPlayerLayoutPrefs
import dev.bilby.ui.player.PlayerGestureOptions
import dev.bilby.ui.settings.AboutSettingsPage
import dev.bilby.ui.settings.AgentSettingsPage
import dev.bilby.ui.settings.AppearanceSettingsPage
import dev.bilby.ui.settings.NavigationCustomizationPage
import dev.bilby.ui.settings.PlayerCustomizationPage
import dev.bilby.ui.settings.PlaybackSettingsPage
import dev.bilby.data.model.ArticleRef
import dev.bilby.data.model.FeedEntry
import dev.bilby.ui.settings.ExcludedFeedPage
import dev.bilby.ui.settings.PrivacySettingsPage
import dev.bilby.ui.settings.SettingsScreen
import dev.bilby.ui.settings.SettingsSection
import dev.bilby.ui.settings.NightModeSettingsPage
import dev.bilby.ui.settings.ScheduleRulePickerSheet
import dev.bilby.ui.settings.SponsorCategoriesPage
import dev.bilby.ui.settings.SettingsViewModel
import dev.bilby.ui.night.NightModeScreen
import dev.bilby.ui.night.NightModeViewModel
import dev.bilby.ui.night.NightReminderBanner
import dev.bilby.data.SpaceCollectionItem
import dev.bilby.ui.space.CollectionScreen
import dev.bilby.ui.space.CollectionViewModel
import dev.bilby.ui.space.SpaceScreen
import dev.bilby.ui.space.SpaceViewModel
import dev.bilby.ui.theme.Motion
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.rememberReducedMotion
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.LocalIsDarkTheme
import dev.bilby.ui.theme.tabular
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
    wrapTheme: Boolean = true,
) {
    // 读到之前用默认值(跟随系统、按壁纸取色):DataStore 头一次读在一两帧之内,选了别的
    // 配色的人会看到一帧默认色。不为这一帧在主线程上同步读盘。
    val appearance by container.settings.appearancePrefs
        .collectAsStateWithLifecycle(initialValue = AppearancePrefs())
    val rootContent: @Composable () -> Unit = {
        windowChrome()
        // 导航层的提示浮在整棵树上面。放在这里而不是某个页面的 Scaffold 里:说这句话的
        // 是压栈动作,而压栈能从任何一页发起,各页面的 Scaffold 都会跟着页面一起换掉。
        val snackbarHostState = remember { SnackbarHostState() }
        val pointerSource = remember { PointerSource() }
        val editableFocusTracker = remember { EditableFocusTracker() }
        val pageRefreshRegistry = rememberPageRefreshRegistry()
        val pageSearchRegistry = remember { PageSearchRegistry() }
        val triggerBack = rememberBackTrigger()
        // 分享在桌面上是复制链接,按下去界面上看不出变化;报一句,见 SystemActions.shareCopiesLink。
        val scope = rememberCoroutineScope()
        val copiedText = stringResource(Res.string.share_link_copied)
        val baseActions = LocalSystemActions.current
        val systemActions = remember(baseActions, copiedText) {
            baseActions.withShareNotice {
                scope.launch { snackbarHostState.showSnackbar(copiedText, withDismissAction = true) }
            }
        }
        val copyNotice = remember(scope, snackbarHostState) {
            { message: String ->
                if (NeedsCopyNotice) {
                    scope.launch { snackbarHostState.showSnackbar(message, withDismissAction = true) }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .trackPointerSource(pointerSource)
                .mouseBackNavigation(triggerBack),
        ) {
            CompositionLocalProvider(
                LocalPointerSource provides pointerSource,
                LocalEditableFocusTracker provides editableFocusTracker,
                LocalPageRefreshRegistry provides pageRefreshRegistry,
                LocalPageSearchRegistry provides pageSearchRegistry,
                LocalCopyNotice provides copyNotice,
                LocalSystemActions provides systemActions,
                LocalEmoteRepository provides container.emoteRepository,
                LocalVideoGridColumns provides appearance.videoGridColumns,
            ) {
                BilbyApp(
                    container = container,
                    incomingLink = incomingLink,
                    snackbarHostState = snackbarHostState,
                    predictiveBack = appearance.predictiveBack,
                )
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
    if (wrapTheme) {
        BilbyTheme(appearance, content = rootContent)
    } else {
        rootContent()
    }
}

@Composable
private fun StartupUpdateHost(container: AppContainer) {
    // 不做应用内更新的平台上不检查:查到了也只能摆一个按不下去的按钮。
    val updater = container.platform.updater ?: return
    StartupUpdatePrompt(updater, container.settings, container.persistScope)
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun BilbyApp(
    container: AppContainer,
    incomingLink: MutableStateFlow<String?>,
    snackbarHostState: SnackbarHostState,
    predictiveBack: Boolean = true,
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
        val appFullscreen = LocalAppFullscreen.current
        val loginFocusRequester = remember { FocusRequester() }
        BackHandler(enabled = appFullscreen?.isFullscreen == true) {
            appFullscreen?.setFullscreen(false)
        }
        LaunchedEffect(Unit) {
            runCatching { loginFocusRequester.requestFocus() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val controller = appFullscreen ?: return@onPreviewKeyEvent false
                    val bare = !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed && !event.isMetaPressed
                    val alt = event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed
                    when {
                        bare && event.key == Key.F11 -> {
                            controller.toggleWindowFullscreen()
                            true
                        }
                        alt && !event.isShiftPressed && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                            controller.toggleWindowFullscreen()
                            true
                        }
                        bare && event.key == Key.Escape && controller.isFullscreen -> {
                            controller.setFullscreen(false)
                            true
                        }
                        else -> false
                    }
                }
                .focusRequester(loginFocusRequester)
                .focusable(),
        ) {
            // 登录成功后 credentials 会自己更新,这里不需要额外导航
            TvLoginScreen(state = state, onRefresh = vm::restart, onDone = {})
        }
        return
    }

    // 夜间模式配置在冷启动首帧内先读完再建导航树，落在生效窗口内时不闪过常规信息流。
    val nightPrefsState by container.settings.nightModePrefs.collectAsStateWithLifecycle(initialValue = null)
    val nightPrefs = nightPrefsState ?: return
    val navigationPrefsState by container.settings.navigationPrefs.collectAsStateWithLifecycle(initialValue = null)
    val navigationPrefs = navigationPrefsState ?: return
    val nightStatus by produceState(
        initialValue = evaluateNightMode(nightPrefs, System.currentTimeMillis()),
        key1 = nightPrefs,
    ) {
        val initialNow = System.currentTimeMillis()
        value = evaluateNightMode(nightPrefs, initialNow)
        if (nightPrefs.manualUntilEpochMillis > 0L && initialNow >= nightPrefs.manualUntilEpochMillis) {
            container.persistScope.launch { container.settings.clearNightModeManualUntil() }
        }
        if (!nightPrefs.enabled && nightPrefs.manualUntilEpochMillis <= 0L) return@produceState
        while (true) {
            delay(if (nightPrefs.enabled) NIGHT_ACTIVE_TICK_MILLIS else NIGHT_IDLE_TICK_MILLIS)
            val now = System.currentTimeMillis()
            val next = evaluateNightMode(nightPrefs, now)
            if (next != value) value = next
            if (nightPrefs.manualUntilEpochMillis > 0L && now >= nightPrefs.manualUntilEpochMillis) {
                container.persistScope.launch { container.settings.clearNightModeManualUntil() }
                if (!nightPrefs.enabled) break
            }
        }
    }
    val activeAllowedUps = (nightStatus as? NightWindowStatus.Active)?.allowedUps.orEmpty()
    val allowedMids = remember(activeAllowedUps) { activeAllowedUps.mapTo(HashSet()) { it.mid } }
    var nightEngaged by remember { mutableStateOf(nightStatus is NightWindowStatus.Active) }
    var showScheduleRulePicker by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // buvid 激活只需成功一次,失败被内部吞掉:没有设备身份只影响写接口,
        // 不该让整个 app 打不开。
        container.deviceFingerprint.activateIfNeeded()
    }

    val reducedMotion = rememberReducedMotion()
    val backStack = rememberNavBackStack(DestinationSavedState, Home)

    var appliedStartupDestination by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (appliedStartupDestination) return@LaunchedEffect
        appliedStartupDestination = true
        if (nightEngaged) return@LaunchedEffect
        val subDestination: NavKey? = when (navigationPrefs.startupPage) {
            StartupDestination.ToView -> ToViewList
            StartupDestination.FavFolders -> FavFolders
            StartupDestination.History -> History
            StartupDestination.Offline -> Offline
            StartupDestination.Feed,
            StartupDestination.Search,
            StartupDestination.Library,
            -> null
        }
        if (subDestination != null && backStack.size == 1 && backStack.firstOrNull() == Home) {
            backStack.pushUnique(subDestination)
        }
    }

    val snackbarScope = rememberCoroutineScope()
    val system = LocalSystemActions.current
    val playback = LocalPlaybackHost.current
    val appAudioState by playback.state.collectAsStateWithLifecycle()
    val resolvedAuthors = remember { mutableStateMapOf<String, Set<Long>>() }
    val offlineVerified = remember { mutableStateMapOf<String, Boolean>() }

    var deferredForBvid by remember { mutableStateOf<String?>(null) }
    var sawDeferredCurrent by remember { mutableStateOf(false) }
    var idleCountdownSeconds by remember { mutableStateOf<Int?>(null) }

    val currentNightEngaged by rememberUpdatedState(nightEngaged)
    val currentAllowedMids by rememberUpdatedState(allowedMids)
    val currentNightStatus by rememberUpdatedState(nightStatus)
    val currentAudioState by rememberUpdatedState(appAudioState)

    val alreadyHere = stringResource(Res.string.nav_already_here)
    val blockedUpText = stringResource(Res.string.night_mode_blocked_up)
    val blockedUnavailableText = stringResource(Res.string.night_mode_blocked_unavailable)
    val nightEnteredNotice = stringResource(Res.string.night_mode_entered_notice)

    val showNightBlocked: (String) -> Unit = { message ->
        snackbarScope.launch { snackbarHostState.showSnackbar(message) }
    }

    suspend fun isLocallyCachedOffline(bvid: String): Boolean {
        offlineVerified[bvid]?.let { return it }
        val cached = container.offlineStore.completedFor(bvid) != null
        offlineVerified[bvid] = cached
        return cached
    }

    suspend fun resolveAuthorsFor(bvid: String): Set<Long>? {
        resolvedAuthors[bvid]?.let { return it }
        return when (val res = container.videoRepository.getVideoDetail(bvid)) {
            is BiliResult.Ok -> buildSet {
                add(res.value.up.mid)
                res.value.staff.forEach { add(it.mid) }
            }.also { resolvedAuthors[bvid] = it }
            else -> null
        }
    }

    fun activeVideoBvid(video: Video): String =
        if (currentAudioState.queue?.frameId == video.frame) {
            currentAudioState.queue?.current?.bvid ?: video.bvid
        } else {
            video.bvid
        }

    fun isVideoAllowed(video: Video, mids: Set<Long>): Boolean {
        val bvid = activeVideoBvid(video)
        if (offlineVerified[bvid] == true) return true
        val frameDetached = currentAudioState.queue?.frameId == video.frame &&
            currentAudioState.queue?.detached == true
        if (video.context is QueueContext.Offline && !frameDetached) return true
        resolvedAuthors[bvid]?.let { authors ->
            return authors.any { it in mids }
        }
        if (frameDetached) return false
        return when (val ctx = video.context) {
            is QueueContext.NightMode -> ctx.ups.isNotEmpty() &&
                ctx.ups.all { it.mid in mids } &&
                (ctx.selectedMid == null || ctx.selectedMid in mids)
            is QueueContext.UpArchive -> ctx.mid in mids
            is QueueContext.Collection -> ctx.mid in mids
            else -> false
        }
    }

    fun isDestinationAllowed(key: NavKey, mids: Set<Long>): Boolean = when (key) {
        Home, Offline, Settings -> true
        is SettingsPage -> true
        is Space -> key.mid in mids
        is CollectionContents -> key.mid in mids
        is Video -> isVideoAllowed(key, mids)
        else -> false
    }

    fun pruneBackStackForNightMode(mids: Set<Long>) {
        var stoppedSourceRemoved = false
        val activeFrameId = currentAudioState.queue?.frameId
        for (index in backStack.lastIndex downTo 1) {
            val entry = backStack[index]
            if (!isDestinationAllowed(entry, mids)) {
                if (entry is LiveRoom) stoppedSourceRemoved = true
                if (entry is Video && entry.frame == activeFrameId) stoppedSourceRemoved = true
                backStack.removeAt(index)
            }
        }
        if (stoppedSourceRemoved || backStack.none { it is Video }) {
            playback.stop()
        }
    }

    fun pruneDraftQueueForNightMode() {
        val snapshot = container.customQueueDraft.items.value
        if (snapshot.isEmpty()) return
        snackbarScope.launch {
            for (item in snapshot) {
                if (isLocallyCachedOffline(item.bvid)) continue
                val authors = resolveAuthorsFor(item.bvid)
                if (authors == null || authors.none { it in currentAllowedMids }) {
                    container.customQueueDraft.remove(item.bvid)
                }
            }
        }
    }

    fun engageNightModeNow(showNotice: Boolean) {
        deferredForBvid = null
        sawDeferredCurrent = false
        idleCountdownSeconds = null
        nightEngaged = true
        pruneBackStackForNightMode(currentAllowedMids)
        pruneDraftQueueForNightMode()
        if (showNotice) {
            snackbarScope.launch { snackbarHostState.showSnackbar(nightEnteredNotice) }
        }
    }

    val engageManualRule: (String) -> Unit = { ruleId ->
        container.persistScope.launch {
            container.settings.engageNightModeManually(ruleId)
        }
        engageNightModeNow(showNotice = true)
    }

    val enterNightModeManually: () -> Unit = {
        when (nightPrefs.rules.size) {
            0 -> backStack.pushUnique(SettingsPage(SettingsSection.NightMode))
            1 -> engageManualRule(nightPrefs.rules.first().id)
            else -> showScheduleRulePicker = true
        }
    }

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
    val push: (NavKey) -> Unit = remember(
        backStack,
        snackbarHostState,
        snackbarScope,
        alreadyHere,
        blockedUpText,
        blockedUnavailableText,
    ) {
        fun pushConfirmed(key: NavKey) {
            if (!backStack.pushUnique(key.withNewFrame())) {
                // 连点几下只留最后一条:SnackbarHostState 自带 MutatorMutex,新的一条会
                // 取消正在显示的那条,不用自己去 dismiss。
                snackbarScope.launch { snackbarHostState.showSnackbar(alreadyHere) }
            }
        }
        { key ->
            if (!currentNightEngaged) {
                pushConfirmed(key)
            } else {
                val mids = currentAllowedMids
                when (key) {
                    Home, Offline, Settings -> pushConfirmed(key)
                    is SettingsPage -> pushConfirmed(key)
                    is LiveRoom -> showNightBlocked(blockedUnavailableText)
                    is Space -> if (key.mid in mids) pushConfirmed(key) else showNightBlocked(blockedUpText)
                    is CollectionContents -> if (key.mid in mids) pushConfirmed(key) else showNightBlocked(blockedUpText)
                    is Video -> {
                        val ctx = key.context
                        when {
                            ctx is QueueContext.Offline -> pushConfirmed(key)
                            ctx is QueueContext.NightMode -> {
                                val allowed = ctx.ups.isNotEmpty() &&
                                    ctx.ups.all { it.mid in mids } &&
                                    (ctx.selectedMid == null || ctx.selectedMid in mids)
                                if (allowed) pushConfirmed(key) else showNightBlocked(blockedUpText)
                            }
                            ctx is QueueContext.UpArchive -> if (ctx.mid in mids) pushConfirmed(key) else showNightBlocked(blockedUpText)
                            ctx is QueueContext.Collection -> if (ctx.mid in mids) pushConfirmed(key) else showNightBlocked(blockedUpText)
                            else -> {
                                val cached = resolvedAuthors[key.bvid]
                                if (offlineVerified[key.bvid] == true || (cached != null && cached.any { it in mids })) {
                                    pushConfirmed(key)
                                } else if (offlineVerified[key.bvid] == false && cached != null) {
                                    showNightBlocked(blockedUpText)
                                } else {
                                    snackbarScope.launch {
                                        if (isLocallyCachedOffline(key.bvid)) {
                                            pushConfirmed(key)
                                            return@launch
                                        }
                                        val authors = resolveAuthorsFor(key.bvid)
                                        if (!currentNightEngaged || (authors != null && authors.any { it in currentAllowedMids })) {
                                            pushConfirmed(key)
                                        } else {
                                            showNightBlocked(blockedUpText)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> showNightBlocked(blockedUnavailableText)
                }
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
    val openLink: (String) -> Unit = { url ->
        val destination = BilbyLink.destinationOf(url)
        when {
            destination != null -> push(destination)
            currentNightEngaged -> showNightBlocked(blockedUnavailableText)
            else -> system.openInBrowser(url)
        }
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

    var appPlayerHandle by remember { mutableStateOf<PlayerHandle?>(null) }
    DisposableEffect(hasVideoPage, playback) {
        if (!hasVideoPage) {
            appPlayerHandle = null
            onDispose {}
        } else {
            val conn = playback.connect { appPlayerHandle = it }
            onDispose {
                appPlayerHandle = null
                conn.release()
            }
        }
    }
    fun sendPlaybackCommand(command: PlaybackCommand) {
        val handle = appPlayerHandle
        if (handle != null) {
            handle.send(command)
        } else {
            var released = false
            var oneShot: PlayerConnection? = null
            oneShot = playback.connect { connected ->
                connected.send(command)
                released = true
                oneShot?.release()
            }
            if (released) oneShot.release()
        }
    }

    LaunchedEffect(deferredForBvid != null) {
        sendPlaybackCommand(PlaybackCommand.SetNightHoldCurrentEnd(deferredForBvid != null))
    }

    suspend fun classifyActiveVodOrFallback(startIdleCountdown: Boolean) {
        val state = currentAudioState
        val topIsLive = backStack.lastOrNull() is LiveRoom ||
            state.queue?.current?.bvid?.startsWith("live:") == true
        val activeVodBvid = state.queue?.current?.bvid
            ?.takeIf {
                !topIsLive &&
                    state.queue.frameId != null &&
                    hasVideoPage &&
                    (state.isPlaying || state.loading)
            }
        if (activeVodBvid != null) {
            val whitelisted = isLocallyCachedOffline(activeVodBvid) ||
                resolveAuthorsFor(activeVodBvid)?.any { it in currentAllowedMids } == true
            if (whitelisted) {
                engageNightModeNow(showNotice = true)
            } else {
                idleCountdownSeconds = null
                deferredForBvid = activeVodBvid
                sawDeferredCurrent = true
            }
        } else if (startIdleCountdown) {
            idleCountdownSeconds = NIGHT_IDLE_COUNTDOWN_SECONDS
        } else {
            engageNightModeNow(showNotice = true)
        }
    }

    // 夜间模式状态协调：冷启动已在窗口内时无闪切入；运行中跨入窗口按当前实际活跃播放会话分流。
    var prevNightStatus by remember { mutableStateOf<NightWindowStatus?>(null) }
    LaunchedEffect(nightStatus) {
        val previous = prevNightStatus
        prevNightStatus = nightStatus
        if (previous == null) return@LaunchedEffect

        when {
            nightStatus !is NightWindowStatus.Active -> {
                deferredForBvid = null
                sawDeferredCurrent = false
                idleCountdownSeconds = null
                nightEngaged = false
            }

            previous is NightWindowStatus.Overridden -> {
                if (nightEngaged) return@LaunchedEffect
                engageNightModeNow(showNotice = true)
            }

            previous is NightWindowStatus.Inactive -> {
                if (nightEngaged) return@LaunchedEffect
                classifyActiveVodOrFallback(startIdleCountdown = true)
            }

            else -> {
                if (!nightEngaged && deferredForBvid == null && idleCountdownSeconds == null) {
                    engageNightModeNow(showNotice = false)
                }
            }
        }
    }

    LaunchedEffect(nightEngaged, allowedMids) {
        if (nightEngaged) {
            pruneBackStackForNightMode(allowedMids)
            pruneDraftQueueForNightMode()
        }
    }

    // 夜间模式生效期间：任何活跃队列换条（含缓存队列追加了在线视频、后台听视频自动切条）都按单条校验本地副本或白名单身份。
    LaunchedEffect(nightEngaged, appAudioState.queue?.current?.bvid, allowedMids) {
        if (!nightEngaged) return@LaunchedEffect
        val bvid = appAudioState.queue?.current?.bvid ?: return@LaunchedEffect
        if (bvid.startsWith("live:")) {
            playback.stop()
            return@LaunchedEffect
        }
        if (isLocallyCachedOffline(bvid)) return@LaunchedEffect
        val authors = resolveAuthorsFor(bvid)
        if (!currentNightEngaged) return@LaunchedEffect
        if (authors == null || authors.none { it in currentAllowedMids }) {
            val activeFrameId = currentAudioState.queue?.frameId
            for (index in backStack.lastIndex downTo 1) {
                val entry = backStack[index]
                if (entry is Video && (entry.frame == activeFrameId || entry.bvid == bvid)) {
                    backStack.removeAt(index)
                }
            }
            playback.stop()
            showNightBlocked(blockedUpText)
        }
    }

    LaunchedEffect(idleCountdownSeconds != null) {
        if (idleCountdownSeconds == null) return@LaunchedEffect
        while (true) {
            val remaining = idleCountdownSeconds ?: break
            if (remaining <= 0) {
                classifyActiveVodOrFallback(startIdleCountdown = false)
                break
            }
            delay(1_000L)
            val currentRemaining = idleCountdownSeconds ?: break
            idleCountdownSeconds = (currentRemaining - 1).coerceAtLeast(0)
        }
    }

    LaunchedEffect(
        deferredForBvid,
        hasVideoPage,
        appAudioState.stoppedAtEnd,
        appAudioState.queue?.current?.bvid,
        nightStatus,
    ) {
        val waitingBvid = deferredForBvid ?: return@LaunchedEffect
        if (nightStatus !is NightWindowStatus.Active) {
            deferredForBvid = null
            sawDeferredCurrent = false
            return@LaunchedEffect
        }
        val currentQueueBvid = appAudioState.queue?.current?.bvid
        if (currentQueueBvid == waitingBvid) {
            sawDeferredCurrent = true
        }
        val switchedBvid = sawDeferredCurrent && currentQueueBvid != waitingBvid
        val finishedHere = appAudioState.stoppedAtEnd &&
            (!hasVideoPage || currentQueueBvid == null || currentQueueBvid == waitingBvid)
        if (!hasVideoPage || switchedBvid || finishedHere) {
            engageNightModeNow(showNotice = true)
        }
    }

    val addedNextText = stringResource(Res.string.queue_added_next)
    val addedText = stringResource(Res.string.queue_added)
    val queueMenuActions = remember(
        container,
        playback,
        snackbarHostState,
        snackbarScope,
        addedNextText,
        addedText,
        blockedUpText,
    ) {
        QueueMenuActions { item, playNext ->
            val enqueueNow = {
                val targetVideo = backStack.lastOrNull { it is Video } as? Video
                if (targetVideo != null) {
                    sendPlaybackCommand(PlaybackCommand.EnqueueItem(targetVideo.frame, item, playNext))
                } else {
                    container.customQueueDraft.enqueue(item, playNext)
                }
                snackbarScope.launch {
                    snackbarHostState.showSnackbar(if (playNext) addedNextText else addedText)
                }
            }
            if (!currentNightEngaged) {
                enqueueNow()
            } else {
                snackbarScope.launch {
                    val allowed = isLocallyCachedOffline(item.bvid) ||
                        resolveAuthorsFor(item.bvid)?.any { it in currentAllowedMids } == true
                    if (!currentNightEngaged || allowed) {
                        enqueueNow()
                    } else {
                        snackbarHostState.showSnackbar(blockedUpText)
                    }
                }
            }
        }
    }
    val playDraftQueue: (String?) -> Unit = { chosenBvid ->
        val taken = container.customQueueDraft.consumeAll()
        if (taken.isNotEmpty()) {
            val entries = taken.map {
                CustomQueueEntry(
                    bvid = it.bvid,
                    title = it.title,
                    upName = it.upName,
                    coverUrl = it.coverUrl,
                    durationSeconds = it.durationSeconds,
                )
            }
            val firstBvid = chosenBvid?.takeIf { b -> entries.any { it.bvid == b } } ?: entries.first().bvid
            push(Video(bvid = firstBvid, context = QueueContext.Custom(entries)))
        }
    }

    // 列表与详情并排:material3 adaptive 的 ListDetailSceneStrategy。条目用 listPane / detailPane
    // 标记,栈顶往下连续带标记的几条拼成一个 ListDetailPaneScaffold。
    //
    // 分几栏不用它默认的 calculatePaneScaffoldDirective:那一套按 WindowSizeClass 算,与全应用
    // 按 rememberBilbyWindowSize 取的断点各算各的;栏间 24dp 的空隙也不要,两栏各是一整页,
    // 自带顶栏和底色。
    //
    // 返回用 PopLatest,一次出一条。默认的 PopUntilScaffoldValueChange 在两栏时出栈不改变
    // 分栏形态,会把整组一次退光,设置里从二级子页返回就退出了设置。
    val pushesAwaitingFirst = remember { mutableStateOf(false) }
    val desktopFramed = LocalIsDesktopWindow.current
    val listDetailWide = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)
    val listDetailDirective = remember(listDetailWide, desktopFramed) {
        PaneScaffoldDirective(
            maxHorizontalPartitions = if (listDetailWide) 2 else 1,
            horizontalPartitionSpacerSize = if (desktopFramed && listDetailWide) Spacing.Tight else 0.dp,
            maxVerticalPartitions = 1,
            verticalPartitionSpacerSize = 0.dp,
            defaultPanePreferredWidth = ListPaneWidth,
            excludedBounds = emptyList(),
        )
    }
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>(
        backNavigationBehavior = BackNavigationBehavior.PopLatest,
        directive = listDetailDirective,
    )
    // 详情占着右栏时点列表里另一条,是换掉这一栏,不是再压一层:否则返回要一层层退回去,
    // 而屏上始终只看得到一条。窄窗口下详情盖住列表,点不到别的,走不到这一支。
    val openBesideList: (NavKey) -> Unit = { key ->
        val last = backStack.lastOrNull()
        if ((last is Whisper && last.inListPane) || (last is CommentThread && last.inListPane)) {
            backStack.removeLastOrNull()
        }
        push(key)
    }
    val selectedTalker = (backStack.lastOrNull() as? Whisper)?.takeIf { it.inListPane }?.talkerId

    // 设置的两栏:宽窗口下右栏总开着一页。进设置时连同第一页一起压;从窄拖宽、右栏空着时由
    // 占位补上第一页(见 Settings 那个 entry)。于是右栏的顶层页出栈时设置要整个一起出栈,
    // 不然右栏空了又被补上,返回键退不出设置。
    val openSettings: () -> Unit = {
        push(Settings)
        if (listDetailWide) {
            push(SettingsPage(if (currentNightEngaged) SettingsSection.NightMode else SettingsSection.Appearance))
        }
    }
    // 左栏点一项是换掉右栏,理由同 openWhisper;连同压在上面的二级子页一起换掉。
    val openSettingsSection: (SettingsSection) -> Unit = { section ->
        if (listDetailWide) {
            while (backStack.lastOrNull() is SettingsPage) backStack.removeLastOrNull()
        }
        push(SettingsPage(section))
    }
    val closeSettings: () -> Unit = {
        while (backStack.size > 1 && backStack.lastOrNull() !is Settings) backStack.removeLastOrNull()
        backStack.removeLastOrNull()
    }
    val popBack: () -> Unit = {
        val popped = backStack.removeLastOrNull()
        if (listDetailWide && popped is SettingsPage && backStack.lastOrNull() is Settings) {
            backStack.removeLastOrNull()
        }
    }
    // 右栏开着的是哪一页:紧挨着设置首页的那一条。二级子页算在它下面。
    val openSettingsRoot = backStack.indexOfLast { it is Settings }
        .takeIf { it >= 0 }
        ?.let { backStack.getOrNull(it + 1) as? SettingsPage }
        ?.section

    val editableFocus = LocalEditableFocusTracker.current
    val pageRefresh = LocalPageRefreshRegistry.current
    val pageSearch = LocalPageSearchRegistry.current
    val appFullscreen = LocalAppFullscreen.current
    val triggerBack = rememberBackTrigger()
    val clipboard = LocalClipboard.current
    val rootFocusRequester = remember { FocusRequester() }
    var shortcutsOpen by remember { mutableStateOf(false) }
    var rootTabRequest by remember { mutableStateOf<Pair<RootTab, Int>?>(null) }
    var focusSearchNonce by remember { mutableIntStateOf(0) }

    BackHandler(enabled = appFullscreen?.isFullscreen == true && backStack.size == 1) {
        appFullscreen?.setFullscreen(false)
    }

    LaunchedEffect(Unit) {
        if (!editableFocus.isEditableFocused) {
            runCatching { rootFocusRequester.requestFocus() }
        }
    }

    val switchRootTab: (RootTab) -> Boolean = { tab ->
        val playingPage = backStack.any { it is Video || it is LiveRoom }
        if (playingPage) {
            false
        } else {
            while (backStack.size > 1) backStack.removeLastOrNull()
            rootTabRequest = tab to ((rootTabRequest?.second ?: 0) + 1)
            true
        }
    }

    CompositionLocalProvider(LocalQueueActions provides queueMenuActions) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val ctrl = event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
                val alt = event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed
                val bare = !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed && !event.isMetaPressed

                // F1 / Ctrl+/ 快捷键面板
                if ((bare && event.key == Key.F1) || (ctrl && event.key == Key.Slash)) {
                    shortcutsOpen = !shortcutsOpen
                    return@onPreviewKeyEvent true
                }
                // F11 窗口级全屏（即使输入框或播放器聚焦也统一切换窗口全屏/退出所有全屏）
                if (bare && event.key == Key.F11) {
                    val controller = appFullscreen ?: return@onPreviewKeyEvent false
                    controller.toggleWindowFullscreen()
                    return@onPreviewKeyEvent true
                }
                // Ctrl+, 打开设置
                if (ctrl && !event.isShiftPressed && event.key == Key.Comma) {
                    if (backStack.none { it is Settings }) openSettings()
                    return@onPreviewKeyEvent true
                }
                // Ctrl+1/2/3 切换当前可见主导航目的地(播放与直播中不切走页面)
                if (ctrl && !event.isShiftPressed) {
                    val targetTab = when (event.key) {
                        Key.One, Key.NumPad1 -> navigationPrefs.primaryTabs.getOrNull(0)?.toRootTab()
                        Key.Two, Key.NumPad2 -> navigationPrefs.primaryTabs.getOrNull(1)?.toRootTab()
                        Key.Three, Key.NumPad3 -> navigationPrefs.primaryTabs.getOrNull(2)?.toRootTab()
                        else -> null
                    }
                    if (targetTab != null) {
                        if (currentNightEngaged) {
                            showNightBlocked(blockedUnavailableText)
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent switchRootTab(targetTab)
                    }
                }
                // Ctrl+F 优先唤起当前页内搜索，未注册时回退至主搜索框；首页下的单键 / 聚焦主搜索框
                val isCtrlF = ctrl && !event.isShiftPressed && event.key == Key.F
                val isHomeSlash =
                    bare && !editableFocus.isEditableFocused && backStack.lastOrNull() is Home && event.key == Key.Slash
                if (isCtrlF || isHomeSlash) {
                    if (isCtrlF && pageSearch.trigger()) {
                        return@onPreviewKeyEvent true
                    }
                    if (currentNightEngaged) {
                        showNightBlocked(blockedUnavailableText)
                        return@onPreviewKeyEvent true
                    }
                    if (switchRootTab(RootTab.Search)) {
                        focusSearchNonce++
                        return@onPreviewKeyEvent true
                    }
                }
                // 以下动作在输入框聚焦时一律放行给文本编辑
                if (editableFocus.isEditableFocused) return@onPreviewKeyEvent false

                // Alt+Enter 窗口级全屏
                if (alt && !event.isShiftPressed && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                    val controller = appFullscreen ?: return@onPreviewKeyEvent false
                    controller.toggleWindowFullscreen()
                    return@onPreviewKeyEvent true
                }
                // Alt+← 浏览器式返回
                if (alt && !event.isShiftPressed && event.key == Key.DirectionLeft) {
                    triggerBack()
                    return@onPreviewKeyEvent true
                }
                // F5 / Ctrl+R 刷新当前最上层页面
                if ((bare && event.key == Key.F5) || (ctrl && !event.isShiftPressed && event.key == Key.R)) {
                    return@onPreviewKeyEvent pageRefresh.trigger()
                }
                // Ctrl+V 直接跳转剪贴板里的站内链接或 BV / av 号
                if (ctrl && !event.isShiftPressed && event.key == Key.V) {
                    snackbarScope.launch {
                        val text = readClipboardText(clipboard)?.trim()?.takeIf { it.isNotEmpty() } ?: return@launch
                        val direct = directDestination(text)
                        if (direct != null) {
                            push(direct)
                            return@launch
                        }
                        val url = BilbyLink.extractUrl(text)
                        if (url != null && BilbyLink.isShortLink(url)) {
                            incomingLink.value = url
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                false
            }
            .focusRequester(rootFocusRequester)
            .focusable(),
    ) {
    val navFramedBackdrop = LocalIsDesktopWindow.current
    // 用 State 持有最新的开关值与 popBack，并在 remember(backStack) 之外更新：
    // 若在 remember(backStack) 内部直接捕获 Boolean 参数 predictiveBack，decorate 闭包会永久停在
    // 首帧默认值（true），设置页切换或 DataStore 异步回填后 BackHandler 永远不会启用。
    val predictiveBackState = rememberUpdatedState(predictiveBack)
    val popBackState = rememberUpdatedState(popBack)
    val predictiveBackToggleDecorator = remember(backStack) {
        NavEntryDecorator<NavKey>(
            decorate = { entry ->
                // 关闭预测性返回时由标准 BackHandler 兜底拦截页级返回，跳过系统手势实时缩放预览，
                // 松手后触发 popTransitionSpec；页面内部后注册的 BackHandler 仍优先响应。
                BackHandler(enabled = !predictiveBackState.value && backStack.size > 1) {
                    popBackState.value()
                }
                entry.Content()
            },
            onPop = {},
        )
    }
    NavDisplay(
        backStack = backStack,
        onBack = popBack,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (navFramedBackdrop) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                } else {
                    Modifier
                },
            ),
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
            predictiveBackToggleDecorator,
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
                    if (nightEngaged) {
                        val activeNightStatus = nightStatus as? NightWindowStatus.Active
                        DesktopIslandFrame {
                            NightModeHomeRoute(
                                container = container,
                                activeStatus = activeNightStatus,
                                hasVideoPage = hasVideoPage,
                                onPlayDraftQueue = playDraftQueue,
                                onVideoClick = { bvid, context -> push(Video(bvid, context = context)) },
                                onOpenSpace = { push(Space(it)) },
                                onOpenOffline = { push(Offline) },
                                onOpenSettings = openSettings,
                                onTempExit = {
                                    nightEngaged = false
                                    container.persistScope.launch {
                                        container.settings.exitNightModeTemporarily()
                                    }
                                },
                            )
                        }
                    } else {
                        RootTabs(
                            container = container,
                            navigationPrefs = navigationPrefs,
                            hasVideoPage = hasVideoPage,
                            tabRequest = rootTabRequest,
                            focusSearchNonce = focusSearchNonce,
                            onPlayDraftQueue = playDraftQueue,
                            onVideoClick = { push(Video(it)) },
                            onVideoInContext = { bvid, context -> push(Video(bvid, context = context)) },
                            onUserClick = { push(Space(it)) },
                            onLiveClick = { push(LiveRoom(it)) },
                            onSettingsClick = openSettings,
                            onOpenFollowings = { push(Followings) },
                            onOpenCoinLog = { push(CoinLog) },
                            onOpenOtherDynamics = { push(OtherDynamics) },
                            onOpenPushes = { push(MessagePushes) },
                            onEnterNightMode = enterNightModeManually,
                            onOpenArticle = { ref -> push(ArticlePage(ref.id, ref.isRead)) },
                            onOpenHistory = { push(History) },
                            onOpenToView = { push(ToViewList) },
                            onOpenOffline = { push(Offline) },
                            onOpenFavFolder = { folder ->
                                push(FavFolderContents(folder.id, folder.title))
                            },
                            onOpenFavFolders = { push(FavFolders) },
                            onOpenMessages = { push(Messages) },
                            onOpen = { push(it) },
                        )
                    }
                }
            }
            entry<Settings>(
                metadata = ListDetailSceneStrategy.listPane(
                    sceneKey = SettingsScene,
                    detailPlaceholder = {
                        DesktopIslandFrame(
                            role = if (listDetailWide) DesktopPaneRole.SplitEnd else DesktopPaneRole.Single,
                        ) {
                            Box(Modifier.fillMaxSize())
                        }
                        LaunchedEffect(Unit) {
                            push(SettingsPage(if (currentNightEngaged) SettingsSection.NightMode else SettingsSection.Appearance))
                        }
                    },
                ),
            ) {
                DesktopIslandFrame(
                    role = if (listDetailWide) DesktopPaneRole.SplitStart else DesktopPaneRole.Single,
                ) {
                    CutoutSafe {
                        SettingsRoute(
                            container = container,
                            onOpenSection = openSettingsSection,
                            onBack = closeSettings,
                            twoPane = listDetailWide,
                            openSection = openSettingsRoot,
                        )
                    }
                }
            }
            // 二级子页也是详情:分栏只取最上面那条详情画进右栏,压在一级页上的子页因此仍在右栏。
            entry<SettingsPage>(metadata = ListDetailSceneStrategy.detailPane(SettingsScene)) { key ->
                // 右栏的顶层页不画返回:它不是"进来的",退出设置在左栏的顶栏上。二级子页照常返回上一级。
                val paneRoot = listDetailWide &&
                    backStack.getOrNull(backStack.indexOf(key) - 1) is Settings
                DesktopIslandFrame(
                    role = if (listDetailWide) DesktopPaneRole.SplitEnd else DesktopPaneRole.Single,
                ) {
                    CutoutSafe {
                        SettingsPageRoute(
                            container = container,
                            section = key.section,
                            onOpenSection = { push(SettingsPage(it)) },
                            onOpenBlacklist = { push(Blacklist) },
                            onBack = if (paneRoot) null else popBack,
                        )
                    }
                }
            }
            entry<History> {
                DesktopIslandFrame {
                    CutoutSafe {
                        HistoryRoute(
                            container = container,
                            onVideoClick = { push(Video(it)) },
                            onUpClick = { push(Space(it)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                }
            }
            entry<Video> { key ->
                val isTopVideo = (backStack.lastOrNull { it is Video } as? Video)?.frame == key.frame
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
                    onOpenVideo = { nextBvid ->
                        if (currentNightEngaged) {
                            push(Video(nextBvid))
                        } else {
                            backStack.replaceTopUnique(Video(nextBvid).withNewFrame())
                        }
                    },
                    onOpenLink = openLink,
                    onSearchTag = if (nightEngaged) null else { tag -> push(SearchResult(tag)) },
                    disableDiscovery = nightEngaged,
                    nightBanner = if (isTopVideo && deferredForBvid != null) {
                        {
                            NightReminderBanner(
                                text = stringResource(Res.string.night_mode_reminder_video),
                                actionLabel = stringResource(Res.string.night_mode_switch_now),
                                onAction = { engageNightModeNow(showNotice = true) },
                            )
                        }
                    } else {
                        null
                    },
                    onAuthorResolved = { resolvedBvid, upMid, staffMids ->
                        val authors = buildSet {
                            add(upMid)
                            addAll(staffMids)
                        }
                        resolvedAuthors[resolvedBvid] = authors
                        val whitelisted = authors.any { it in currentAllowedMids }
                        if (currentNightEngaged) {
                            if (!whitelisted) {
                                snackbarScope.launch {
                                    if (!isLocallyCachedOffline(resolvedBvid) && currentNightEngaged) {
                                        if (backStack.remove(key) && backStack.none { it is Video }) {
                                            playback.stop()
                                        }
                                        showNightBlocked(blockedUpText)
                                    }
                                }
                            }
                        } else if (deferredForBvid == resolvedBvid && whitelisted) {
                            engageNightModeNow(showNotice = true)
                        }
                    },
                )
            }
            entry<SearchResult> { key ->
                DesktopIslandFrame {
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
            }
            // 五格都分栏:私信开在右栏,回复、@、赞点开的评论详情也开在右栏。
            entry<Messages>(
                metadata = ListDetailSceneStrategy.listPane(
                    sceneKey = MessagesScene,
                    // 右栏空着时说一句它是干什么的:这里没有在加载什么,画骨架就是在假装加载。
                    detailPlaceholder = {
                        DesktopIslandFrame(
                            role = if (listDetailWide) DesktopPaneRole.SplitEnd else DesktopPaneRole.Single,
                        ) {
                            MessageDetailPlaceholder()
                        }
                    },
                ),
            ) {
                DesktopIslandFrame(
                    role = if (listDetailWide) DesktopPaneRole.SplitStart else DesktopPaneRole.Single,
                ) {
                    CutoutSafe {
                        MessagesRoute(
                            container = container,
                            selectedTalker = selectedTalker,
                            onOpenWhisper = {
                                openBesideList(Whisper(it.talkerId, it.name, it.faceUrl, it.isSystem, inListPane = true))
                            },
                            onOpenSpace = { push(Space(it)) },
                            // 通知里的 uri 是站内链接,认得出来就在应用内落地,认不出来
                            // (活动页、会员购这类)交给浏览器 —— 同专栏正文里的链接一条路。
                            onOpenUri = openLink,
                            // 回复、@、被赞的评论带着评论定位,落到评论详情页;认不出定位的(被赞的是
                            // 视频或动态)照网页地址走。native_uri 是 bilibili:// scheme,不交给浏览器。
                            onOpenNotice = { notice ->
                                val thread = BilbyLink.commentThreadOf(notice.nativeUri, notice.subjectId, notice.businessId)
                                when {
                                    thread != null -> openBesideList(thread.copy(inListPane = true))
                                    notice.uri.isNotBlank() -> openLink(notice.uri)
                                }
                            },
                            onBack = {
                                while (backStack.size > 1 && backStack.lastOrNull() !is Messages) {
                                    backStack.removeLastOrNull()
                                }
                                if (backStack.lastOrNull() is Messages) {
                                    backStack.removeLastOrNull()
                                }
                            },
                        )
                    }
                }
            }
            entry<CommentThread>(metadata = { key -> listDetailMetadata(key.inListPane, MessagesScene) }) { key ->
                // 评论所在的内容:视频稿件 1、专栏 12、动态 17。其余类型(图文动态 11 的 oid 是
                // 相簿 id,不是动态 id)认不出去处,顶栏不放入口。
                val subject: NavKey? = when (key.type) {
                    1 -> Video(BvidCodec.fromAid(key.oid))
                    12 -> ArticlePage(key.oid.toString(), isRead = true)
                    17 -> DynamicDetail(key.oid.toString())
                    else -> null
                }
                val inSplit = listDetailWide && key.inListPane && backStack.any { it is Messages || it is MessagePushes }
                DesktopIslandFrame(
                    role = if (inSplit) DesktopPaneRole.SplitEnd else DesktopPaneRole.Single,
                ) {
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
            }
            entry<CoinLog> {
                DesktopIslandFrame {
                    CutoutSafe {
                        // 仓库无状态、只有一个接口,就地造一份,不进 AppContainer。
                        CoinLogRoute(
                            repository = remember { CoinLogRepository(container.biliClient) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                }
            }
            entry<MessagePushes>(
                // 还在等自动打开的第一个会话时转圈(见 MessagePushesRoute.autoOpenFirst);打开过又关掉、
                // 或者根本没有推送会话时,同消息页那一句,否则就是一直转下去。
                metadata = ListDetailSceneStrategy.listPane(
                    sceneKey = MessagesScene,
                    detailPlaceholder = {
                        DesktopIslandFrame(
                            role = if (listDetailWide) DesktopPaneRole.SplitEnd else DesktopPaneRole.Single,
                        ) {
                            if (pushesAwaitingFirst.value) FullScreenLoading() else MessageDetailPlaceholder()
                        }
                    },
                ),
            ) {
                DesktopIslandFrame(
                    role = if (listDetailWide) DesktopPaneRole.SplitStart else DesktopPaneRole.Single,
                ) {
                    CutoutSafe {
                        MessagePushesRoute(
                            repository = container.messageRepository,
                            selectedTalker = selectedTalker,
                            autoOpenFirst = listDetailWide,
                            onAwaitingFirstChange = { pushesAwaitingFirst.value = it },
                            onOpenWhisper = {
                                openBesideList(
                                    Whisper(it.talkerId, it.name, it.faceUrl, it.isSystem, upPushes = true, inListPane = true),
                                )
                            },
                            onBack = {
                                while (backStack.size > 1 && backStack.lastOrNull() !is MessagePushes) {
                                    backStack.removeLastOrNull()
                                }
                                if (backStack.lastOrNull() is MessagePushes) {
                                    backStack.removeLastOrNull()
                                }
                            },
                        )
                    }
                }
            }
            entry<Whisper>(metadata = { key -> listDetailMetadata(key.inListPane, MessagesScene) }) { key ->
                val inSplit = listDetailWide && key.inListPane && backStack.any { it is Messages || it is MessagePushes }
                DesktopIslandFrame(
                    role = if (inSplit) DesktopPaneRole.SplitEnd else DesktopPaneRole.Single,
                ) {
                    CutoutSafe {
                        WhisperRoute(
                            container = container,
                            key = key,
                            onOpenSpace = { push(Space(key.talkerId)) },
                            onOpenVideo = { push(Video(it)) },
                            // 私信里的专栏带的是 cv 号(推送的 rid、分享的 id),走旧版那套接口。
                            onOpenArticle = { push(ArticlePage(it, isRead = true)) },
                            onOpenLink = openLink,
                            // 压一层,不是换掉:返回回到推送视图。不带分栏标记,宽窗口下整页是聊天。
                            onOpenFullChat = { push(key.copy(upPushes = false, inListPane = false)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                }
            }
            entry<Offline> {
                DesktopIslandFrame {
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
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                }
            }
            entry<ToViewList> {
                DesktopIslandFrame {
                    CutoutSafe {
                        ToViewListRoute(
                            container = container,
                            onVideoClick = { bvid, context -> push(Video(bvid, context = context)) },
                            onListen = { bvid, context -> push(Video(bvid, listening = true, context = context)) },
                            onUpClick = { push(Space(it)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                }
            }
            entry<FavFolderContents> { key ->
                DesktopIslandFrame {
                    CutoutSafe {
                        FavFolderRoute(
                            container = container,
                            mediaId = key.mediaId,
                            title = key.title,
                            onVideoClick = { bvid, context -> push(Video(bvid, context = context)) },
                            onListen = { bvid, context -> push(Video(bvid, listening = true, context = context)) },
                            onUpClick = { push(Space(it)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                }
            }
            entry<FavFolders> {
                DesktopIslandFrame {
                    CutoutSafe {
                        FavFoldersRoute(
                            container = container,
                            onOpenFolder = { push(FavFolderContents(it.id, it.title)) },
                            onVideoClick = { push(Video(it)) },
                            onUpClick = { push(Space(it)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                }
            }
            entry<Blacklist> {
                DesktopIslandFrame {
                    CutoutSafe {
                        BlacklistRoute(container = container, onBack = { backStack.removeLastOrNull() })
                    }
                }
            }
            entry<DynamicDetail> { key ->
                DesktopIslandFrame {
                    CutoutSafe {
                        DynamicDetailRoute(
                            container = container,
                            id = key.id,
                            push = push,
                            onOpenLink = openLink,
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                }
            }
            entry<Followings> {
                DesktopIslandFrame {
                    CutoutSafe {
                        FollowingsRoute(
                            container = container,
                            onUpClick = { push(Space(it)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                }
            }
            entry<LiveRoom> { key ->
                val playerPrefs by container.settings.playerPrefs.collectAsStateWithLifecycle(
                    initialValue = PlayerPrefs(),
                )
                CompositionLocalProvider(LocalPlayerLayoutPrefs provides playerPrefs.layout) {
                    LiveRoomRoute(
                        container,
                        key.roomId,
                        onBack = { backStack.removeLastOrNull() },
                        onUserClick = { push(Space(it)) },
                    )
                }
            }
            entry<Space> { key ->
                val scope = rememberCoroutineScope()
                DesktopIslandFrame {
                    CutoutSafe {
                        SpaceRoute(
                            container = container,
                            mid = key.mid,
                            vodOnly = nightEngaged,
                            onVideoClick = { bvid, context -> push(Video(bvid, context = context)) },
                            onLiveClick = if (nightEngaged) null else { roomId -> push(LiveRoom(roomId)) },
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
            }
            entry<CollectionContents> { key ->
                DesktopIslandFrame {
                    CutoutSafe {
                        CollectionRoute(
                            container = container,
                            key = key,
                            onVideoClick = { bvid, context -> push(Video(bvid, context = context)) },
                            onBack = { backStack.removeLastOrNull() },
                        )
                    }
                }
            }
            entry<OtherDynamics> {
                val scope = rememberCoroutineScope()
                DesktopIslandFrame {
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
            }
            entry<ArticlePage> { key ->
                DesktopIslandFrame {
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
            }
        },
    )
    if (idleCountdownSeconds != null) {
        val countdown = idleCountdownSeconds ?: 0
        val topBarInset = WindowInsets.barsAndCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
        NightReminderBanner(
            text = stringResource(Res.string.night_mode_reminder_idle, countdown),
            actionLabel = stringResource(Res.string.night_mode_switch_now),
            onAction = { engageNightModeNow(showNotice = true) },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = Breakpoints.ReadableWidth)
                .windowInsetsPadding(topBarInset)
                .padding(
                    top = NightIdleBannerTopOffset,
                    start = Spacing.Comfortable,
                    end = Spacing.Comfortable,
                ),
        )
    }
    if (shortcutsOpen) {
        ShortcutsSheet(onDismiss = { shortcutsOpen = false })
    }
    if (showScheduleRulePicker) {
        ScheduleRulePickerSheet(
            rules = nightPrefs.rules,
            onSelect = { rule ->
                showScheduleRulePicker = false
                engageManualRule(rule.id)
            },
            onDismiss = { showScheduleRulePicker = false },
        )
    }
    }
    }
}

/**
 * M3 的导航栏要求选中项用实心图标、未选中用线性图标(不只是变色)——
 * 图标形态本身就是一路状态指示,只靠颜色的话色觉障碍用户看不出当前在哪一格。
 */
private enum class RootTab(
    private val label: StringResource,
    private val selectedIcon: ImageVector,
    private val unselectedIcon: ImageVector,
    private val railLabel: StringResource = label,
    private val railSelectedIcon: ImageVector = selectedIcon,
    private val railUnselectedIcon: ImageVector = unselectedIcon,
) {
    Feed(Res.string.tab_feed, Icons.Filled.Subscriptions, Icons.Outlined.Subscriptions),
    Search(Res.string.tab_search, Icons.Filled.Search, Icons.Outlined.Search),
    Profile(
        label = Res.string.tab_profile,
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person,
        railLabel = Res.string.tab_library,
        railSelectedIcon = Icons.Filled.VideoLibrary,
        railUnselectedIcon = Icons.Outlined.VideoLibrary,
    ),
    ;

    fun labelRes(sideRail: Boolean): StringResource = if (sideRail) railLabel else label

    fun icon(selected: Boolean, sideRail: Boolean): ImageVector = if (sideRail) {
        if (selected) railSelectedIcon else railUnselectedIcon
    } else {
        if (selected) selectedIcon else unselectedIcon
    }
}

internal enum class DesktopPaneRole {
    Single,
    SplitStart,
    SplitEnd,
}

/**
 * 桌面与宽屏主从双栏的统一圆角内容岛外壳:
 * - 桌面端(`LocalIsDesktopWindow.current`)外框保持 `surfaceContainer` 底色与 8dp 顶栏拖拽边及四周内缩,
 *   消除根页与压栈二级页切换时右上角窗口控制按钮 8dp 跳变,全屏切换时也维持组合树稳定不丢状态;
 * - 主从双栏(`ListDetailSceneStrategy`)下左右两栏各自裁成独立的 `shapes.large` 圆角岛,中间由 8dp 隔断露出 `surfaceContainer` 底框。
 */
@Composable
internal fun DesktopIslandFrame(
    role: DesktopPaneRole = DesktopPaneRole.Single,
    content: @Composable () -> Unit,
) {
    val desktopFramed = LocalIsDesktopWindow.current
    if (!desktopFramed) {
        content()
        return
    }
    LocalWindowCaption.current?.Host()
    val startInset = if (role == DesktopPaneRole.SplitEnd) 0.dp else Spacing.Tight
    val endInset = if (role == DesktopPaneRole.SplitStart) 0.dp else Spacing.Tight
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.Tight)
                .windowDragArea(),
        )
        DesktopIslandSurface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = startInset, end = endInset, bottom = Spacing.Tight),
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DesktopIslandSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val currentScheme = MaterialTheme.colorScheme
    val islandScheme = if (LocalIsDarkTheme.current) {
        currentScheme
    } else {
        remember(currentScheme) {
            currentScheme.copy(
                background = currentScheme.surfaceContainerLowest,
                surface = currentScheme.surfaceContainerLowest,
            )
        }
    }
    MaterialExpressiveTheme(
        colorScheme = islandScheme,
        motionScheme = MaterialTheme.motionScheme,
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
    ) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.large,
            color = islandScheme.surface,
            contentColor = islandScheme.onSurface,
            content = content,
        )
    }
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
private fun RootTabId.toRootTab(): RootTab = when (this) {
    RootTabId.Feed -> RootTab.Feed
    RootTabId.Search -> RootTab.Search
    RootTabId.Library -> RootTab.Profile
}

private fun initialRootTab(prefs: NavigationPrefs): RootTab {
    val clean = prefs.sanitize()
    val directTab = clean.startupPage.rootTabId
    if (directTab != null && directTab in clean.primaryTabs) {
        return directTab.toRootTab()
    }
    if (clean.startupPage.isLibrarySubDestination && RootTabId.Library in clean.primaryTabs) {
        return RootTab.Profile
    }
    return clean.primaryTabs.first().toRootTab()
}

@Composable
private fun RootTabs(
    container: AppContainer,
    navigationPrefs: NavigationPrefs,
    hasVideoPage: Boolean,
    tabRequest: Pair<RootTab, Int>? = null,
    focusSearchNonce: Int = 0,
    onPlayDraftQueue: (String?) -> Unit,
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
    onEnterNightMode: () -> Unit,
    onOpenArticle: (ArticleRef) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenToView: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenFavFolder: (FavFolderDetail) -> Unit,
    onOpenFavFolders: () -> Unit,
    onOpenMessages: () -> Unit,
    /** 搜索框里的编号或链接直接打开的那一页,可以是任何一种目的地。 */
    onOpen: (NavKey) -> Unit,
) {
    val cleanNavPrefs = remember(navigationPrefs) { navigationPrefs.sanitize() }
    val visibleTabs = remember(cleanNavPrefs.primaryTabs) {
        cleanNavPrefs.primaryTabs.map { it.toRootTab() }
    }
    var selected by rememberSaveable { mutableStateOf(initialRootTab(cleanNavPrefs)) }
    LaunchedEffect(visibleTabs) {
        if (selected !in visibleTabs) {
            selected = visibleTabs.first()
        }
    }
    val windowSize = rememberBilbyWindowSize()

    var feedScrollToTop by remember { mutableIntStateOf(0) }
    var profileScrollToTop by remember { mutableIntStateOf(0) }
    var searchHomeClicks by remember { mutableIntStateOf(0) }
    val onTabClick: (RootTab) -> Unit = { tab ->
        if (selected == tab) {
            when (tab) {
                RootTab.Feed -> feedScrollToTop++
                RootTab.Profile -> profileScrollToTop++
                RootTab.Search -> searchHomeClicks++
            }
        } else {
            selected = tab
        }
    }
    LaunchedEffect(tabRequest?.second) {
        val target = tabRequest?.first ?: return@LaunchedEffect
        if (selected != target) selected = target
    }
    val currentSelected = selected
    val profileVm: ProfileViewModel = viewModel(
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
    val profileState by profileVm.state.collectAsStateWithLifecycle()

    if (windowSize == BilbyWindowSize.Compact) {
        DesktopIslandFrame {
            Scaffold(
                bottomBar = {
                    ShortNavigationBar {
                        visibleTabs.forEach { tab ->
                            ShortNavigationBarItem(
                                selected = currentSelected == tab,
                                onClick = { onTabClick(tab) },
                                icon = { RootTabIcon(tab, currentSelected == tab, sideRail = false) },
                                label = { RootTabLabel(tab, currentSelected == tab, sideRail = false) },
                            )
                        }
                    }
                },
            ) { insets ->
                RootTabsContent(
                    insets = insets,
                    selected = currentSelected,
                    showAccountHeader = true,
                    feedUpStripPosition = cleanNavPrefs.feedUpStripPosition,
                    profileVm = profileVm,
                    feedScrollToTop = feedScrollToTop,
                    profileScrollToTop = profileScrollToTop,
                    focusSearchNonce = focusSearchNonce,
                    searchHomeClicks = searchHomeClicks,
                    container = container,
                    hasVideoPage = hasVideoPage,
                    onPlayDraftQueue = onPlayDraftQueue,
                    onVideoClick = onVideoClick,
                    onVideoInContext = onVideoInContext,
                    onUserClick = onUserClick,
                    onLiveClick = onLiveClick,
                    onSettingsClick = onSettingsClick,
                    onOpenFollowings = onOpenFollowings,
                    onOpenCoinLog = onOpenCoinLog,
                    onOpenOtherDynamics = onOpenOtherDynamics,
                    onOpenPushes = onOpenPushes,
                    onEnterNightMode = onEnterNightMode,
                    onOpenArticle = onOpenArticle,
                    onOpenHistory = onOpenHistory,
                    onOpenToView = onOpenToView,
                    onOpenOffline = onOpenOffline,
                    onOpenFavFolder = onOpenFavFolder,
                    onOpenFavFolders = onOpenFavFolders,
                    onOpenMessages = onOpenMessages,
                    onOpen = onOpen,
                )
            }
        }
    } else {
        LocalWindowCaption.current?.Host()
        val desktopFramed = LocalIsDesktopWindow.current
        val isHeightCompact = rememberIsHeightCompact()
        val tabsScaffold: @Composable (Modifier) -> Unit = { scaffoldModifier ->
            Scaffold(modifier = scaffoldModifier) { insets ->
                RootTabsContent(
                    insets = insets,
                    selected = currentSelected,
                    showAccountHeader = false,
                    feedUpStripPosition = cleanNavPrefs.feedUpStripPosition,
                    profileVm = profileVm,
                    feedScrollToTop = feedScrollToTop,
                    profileScrollToTop = profileScrollToTop,
                    focusSearchNonce = focusSearchNonce,
                    searchHomeClicks = searchHomeClicks,
                    container = container,
                    hasVideoPage = hasVideoPage,
                    onPlayDraftQueue = onPlayDraftQueue,
                    onVideoClick = onVideoClick,
                    onVideoInContext = onVideoInContext,
                    onUserClick = onUserClick,
                    onLiveClick = onLiveClick,
                    onSettingsClick = onSettingsClick,
                    onOpenFollowings = onOpenFollowings,
                    onOpenCoinLog = onOpenCoinLog,
                    onOpenOtherDynamics = onOpenOtherDynamics,
                    onOpenPushes = onOpenPushes,
                    onEnterNightMode = onEnterNightMode,
                    onOpenArticle = onOpenArticle,
                    onOpenHistory = onOpenHistory,
                    onOpenToView = onOpenToView,
                    onOpenOffline = onOpenOffline,
                    onOpenFavFolder = onOpenFavFolder,
                    onOpenFavFolders = onOpenFavFolders,
                    onOpenMessages = onOpenMessages,
                    onOpen = onOpen,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (desktopFramed) {
                        Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (desktopFramed) {
                // 顶部 8dp 通栏拖拽边覆盖左侧导航轨与右侧内容岛，避免与内部控件抢命中。
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Spacing.Tight)
                        .windowDragArea(),
                )
            }
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                RootNavigationRail(
                    selected = currentSelected,
                    visibleTabs = visibleTabs,
                    railUtilities = cleanNavPrefs.railUtilities,
                    isHeightCompact = isHeightCompact,
                    desktopFramed = desktopFramed,
                    profileState = profileState,
                    onTabClick = onTabClick,
                    onOpenSelf = onUserClick,
                    onRetryAccount = profileVm::retryAccount,
                    onOpenFollowings = onOpenFollowings,
                    onOpenPushes = onOpenPushes,
                    onOpenOtherDynamics = onOpenOtherDynamics,
                    onOpenMessages = onOpenMessages,
                    onOpenFavFolders = onOpenFavFolders,
                    onOpenOffline = onOpenOffline,
                    onOpenHistory = onOpenHistory,
                    onOpenToView = onOpenToView,
                    onSettingsClick = onSettingsClick,
                )
                if (desktopFramed) {
                    DesktopIslandSurface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = Spacing.Tight, bottom = Spacing.Tight),
                    ) {
                        tabsScaffold(Modifier.fillMaxSize())
                    }
                } else {
                    tabsScaffold(Modifier.weight(1f))
                }
            }
        }
    }
}

private val SlimRailWidth = 80.dp
private val RailAvatarSize = 40.dp
private val RailUtilityVisualSize = 40.dp
private val RailUtilityIconSize = 20.dp
private val RailCompactHeightThresholdDesktop = 500.dp
private val RailCompactHeightThresholdInset = 540.dp

@Composable
private fun RootNavigationRail(
    selected: RootTab,
    visibleTabs: List<RootTab>,
    railUtilities: List<RailUtilityId>,
    isHeightCompact: Boolean,
    desktopFramed: Boolean,
    profileState: ProfileUiState,
    onTabClick: (RootTab) -> Unit,
    onOpenSelf: (Long) -> Unit,
    onRetryAccount: () -> Unit,
    onOpenFollowings: () -> Unit,
    onOpenPushes: () -> Unit,
    onOpenOtherDynamics: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenFavFolders: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenToView: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val containerHeightDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val compactHeightThreshold = if (desktopFramed) {
        RailCompactHeightThresholdDesktop
    } else {
        RailCompactHeightThresholdInset
    }
    val compactRailLayout = isHeightCompact || containerHeightDp < compactHeightThreshold
    val railInsets = if (desktopFramed) WindowInsets(0) else NavigationRailDefaults.windowInsets
    val verticalInset = if (compactRailLayout) Spacing.Hair else Spacing.Cozy
    val scrollModifier = if (compactRailLayout) {
        Modifier.verticalScroll(rememberScrollState())
    } else {
        Modifier
    }

    Surface(
        color = if (desktopFramed) Color.Transparent else NavigationRailDefaults.ContainerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxHeight()
            .width(SlimRailWidth),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(railInsets)
                .then(scrollModifier)
                .padding(top = verticalInset, bottom = verticalInset),
        ) {
            RootRailAccountHeader(
                state = profileState,
                onOpenSelf = onOpenSelf,
                onRetry = onRetryAccount,
            )

            if (compactRailLayout) {
                Spacer(Modifier.height(Spacing.Tight))
            } else {
                Spacer(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(if (desktopFramed) Modifier.windowDragArea() else Modifier),
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compactRailLayout) Spacing.Hair else Spacing.Tight),
                modifier = Modifier.fillMaxWidth(),
            ) {
                visibleTabs.forEach { tab ->
                    NavigationRailItem(
                        selected = selected == tab,
                        onClick = { onTabClick(tab) },
                        icon = { RootTabIcon(tab, selected == tab, sideRail = true) },
                        label = { RootTabLabel(tab, selected == tab, sideRail = true) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (compactRailLayout) {
                Spacer(Modifier.height(Spacing.Tight))
            } else {
                Spacer(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(if (desktopFramed) Modifier.windowDragArea() else Modifier),
                )
            }

            RootRailUtilitySection(
                account = profileState.account,
                utilities = railUtilities,
                onOpenFollowings = onOpenFollowings,
                onOpenPushes = onOpenPushes,
                onOpenOtherDynamics = onOpenOtherDynamics,
                onOpenMessages = onOpenMessages,
                onOpenFavFolders = onOpenFavFolders,
                onOpenOffline = onOpenOffline,
                onOpenHistory = onOpenHistory,
                onOpenToView = onOpenToView,
                onSettingsClick = onSettingsClick,
            )
        }
    }
}

@Composable
private fun RootRailAccountHeader(
    state: ProfileUiState,
    onOpenSelf: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    val account = state.account
    val colorScheme = MaterialTheme.colorScheme
    when {
        account != null -> {
            BilbyTooltip(text = "${account.name}${MetaSeparator}UID: ${account.mid}") {
                AccountIdentityContextMenu(
                    account = account,
                    onOpenSelf = onOpenSelf,
                    shape = CircleShape,
                    modifier = Modifier
                        .size(Dimens.MinTouchTarget)
                        .interactiveCard(
                            shape = CircleShape,
                            baseColor = Color.Transparent,
                            hoverColor = colorScheme.surfaceContainerHigh,
                        ),
                ) { _ ->
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Avatar(url = account.faceUrl, size = RailAvatarSize)
                    }
                }
            }
        }

        state.accountError != null -> {
            val retryText = stringResource(Res.string.action_retry)
            BilbyTooltip(text = "${stringResource(state.accountError)}$MetaSeparator$retryText") {
                IconButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Outlined.AccountCircle,
                        contentDescription = retryText,
                        tint = colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        else -> {
            SkeletonPulse {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(Dimens.MinTouchTarget),
                ) {
                    Box(Modifier.size(RailAvatarSize).skeleton(CircleShape))
                }
            }
        }
    }
}

@Composable
private fun RootRailUtilitySection(
    account: AccountInfo?,
    utilities: List<RailUtilityId>,
    onOpenFollowings: () -> Unit,
    onOpenPushes: () -> Unit,
    onOpenOtherDynamics: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpenFavFolders: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenToView: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val followingLabel = stringResource(Res.string.profile_following)
    val pushesLabel = stringResource(Res.string.feed_up_pushes_entry)
    val dynamicsLabel = stringResource(Res.string.nav_custom_utility_dynamics)
    val messagesLabel = stringResource(Res.string.message_title)
    val favFoldersLabel = stringResource(Res.string.fav_folders_title)
    val offlineLabel = stringResource(Res.string.offline_title)
    val historyLabel = stringResource(Res.string.history_title)
    val toViewLabel = stringResource(Res.string.tab_toview)
    val settingsLabel = stringResource(Res.string.settings_title)
    val followingValue = account?.following?.let { formatSegmentValue(it.toDouble()) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        utilities.forEach { utility ->
            when (utility) {
                RailUtilityId.Followings -> RootRailUtilityItem(
                    label = followingLabel,
                    tooltip = followingValue?.let { "$it $followingLabel" } ?: followingLabel,
                    onClick = onOpenFollowings,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.PeopleAlt,
                            contentDescription = followingLabel,
                            tint = tint,
                            modifier = Modifier.size(RailUtilityIconSize),
                        )
                    },
                )

                RailUtilityId.Pushes -> RootRailUtilityItem(
                    label = pushesLabel,
                    tooltip = pushesLabel,
                    onClick = onOpenPushes,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Mail,
                            contentDescription = pushesLabel,
                            tint = tint,
                            modifier = Modifier.size(RailUtilityIconSize),
                        )
                    },
                )

                RailUtilityId.Dynamics -> RootRailUtilityItem(
                    label = dynamicsLabel,
                    tooltip = dynamicsLabel,
                    onClick = onOpenOtherDynamics,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Forum,
                            contentDescription = dynamicsLabel,
                            tint = tint,
                            modifier = Modifier.size(RailUtilityIconSize),
                        )
                    },
                )

                RailUtilityId.Messages -> RootRailUtilityItem(
                    label = messagesLabel,
                    tooltip = messagesLabel,
                    onClick = onOpenMessages,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Mail,
                            contentDescription = messagesLabel,
                            tint = tint,
                            modifier = Modifier.size(RailUtilityIconSize),
                        )
                    },
                )

                RailUtilityId.FavFolders -> RootRailUtilityItem(
                    label = favFoldersLabel,
                    tooltip = favFoldersLabel,
                    onClick = onOpenFavFolders,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.CollectionsBookmark,
                            contentDescription = favFoldersLabel,
                            tint = tint,
                            modifier = Modifier.size(RailUtilityIconSize),
                        )
                    },
                )

                RailUtilityId.Offline -> RootRailUtilityItem(
                    label = offlineLabel,
                    tooltip = offlineLabel,
                    onClick = onOpenOffline,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.DownloadForOffline,
                            contentDescription = offlineLabel,
                            tint = tint,
                            modifier = Modifier.size(RailUtilityIconSize),
                        )
                    },
                )

                RailUtilityId.History -> RootRailUtilityItem(
                    label = historyLabel,
                    tooltip = historyLabel,
                    onClick = onOpenHistory,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.History,
                            contentDescription = historyLabel,
                            tint = tint,
                            modifier = Modifier.size(RailUtilityIconSize),
                        )
                    },
                )

                RailUtilityId.ToView -> RootRailUtilityItem(
                    label = toViewLabel,
                    tooltip = toViewLabel,
                    onClick = onOpenToView,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.WatchLater,
                            contentDescription = toViewLabel,
                            tint = tint,
                            modifier = Modifier.size(RailUtilityIconSize),
                        )
                    },
                )

                RailUtilityId.Settings -> RootRailUtilityItem(
                    label = settingsLabel,
                    tooltip = settingsLabel,
                    shortcut = "Ctrl+,",
                    onClick = onSettingsClick,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = settingsLabel,
                            tint = tint,
                            modifier = Modifier.size(RailUtilityIconSize),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun RootRailUtilityItem(
    label: String,
    tooltip: String,
    shortcut: String? = null,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    BilbyTooltip(text = tooltip, shortcut = shortcut, anchorAbove = true) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(Dimens.MinTouchTarget)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(RailUtilityVisualSize)
                    .interactiveCard(
                        shape = CircleShape,
                        baseColor = Color.Transparent,
                        hoverColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
            ) {
                icon()
            }
        }
    }
}

/**
 * 底栏和 rail 共用同一份图标与标签规则,不各写一份 —— 两处分头维护的话,选中态用实心图标
 * 这条迟早只剩一处成立。
 */
@Composable
private fun RootTabIcon(tab: RootTab, selected: Boolean, sideRail: Boolean) {
    Icon(
        imageVector = tab.icon(selected = selected, sideRail = sideRail),
        // 标签就在图标旁边,读屏再念一遍图标等于每格念两次。
        contentDescription = null,
    )
}

/**
 * 选中项采用 M3 Expressive `labelMediumEmphasized`,未选中为 `labelMedium`。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RootTabLabel(tab: RootTab, selected: Boolean, sideRail: Boolean) {
    Text(
        text = stringResource(tab.labelRes(sideRail = sideRail)),
        style = if (selected) {
            MaterialTheme.typography.labelMediumEmphasized
        } else {
            MaterialTheme.typography.labelMedium
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RootTabsContent(
    insets: PaddingValues,
    selected: RootTab,
    showAccountHeader: Boolean,
    feedUpStripPosition: FeedUpStripPosition,
    profileVm: ProfileViewModel,
    /** 每变一次就回到顶部。计数器而不是布尔:连按两下要滚两次,而布尔第二下没有变化。 */
    feedScrollToTop: Int,
    profileScrollToTop: Int,
    focusSearchNonce: Int,
    searchHomeClicks: Int,
    container: AppContainer,
    hasVideoPage: Boolean,
    onPlayDraftQueue: (String?) -> Unit,
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
    onEnterNightMode: () -> Unit,
    onOpenArticle: (ArticleRef) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenToView: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenFavFolder: (FavFolderDetail) -> Unit,
    onOpenFavFolders: () -> Unit,
    onOpenMessages: () -> Unit,
    onOpen: (NavKey) -> Unit,
) {
    val bottom = PaddingValues(bottom = insets.calculateBottomPadding())
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = insets.calculateTopPadding())
            .padding(bottom)
            .consumeWindowInsets(bottom),
    ) {
        val tabStates = rememberSaveableStateHolder()
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                fadeIn(Motion.TopLevelEnterFade) togetherWith fadeOut(Motion.TopLevelExitFade)
            },
            label = "rootTab",
        ) { tab ->
            tabStates.SaveableStateProvider(tab.name) {
            when (tab) {
                RootTab.Feed -> FeedPane(
                    container = container,
                    scrollToTop = feedScrollToTop,
                    upStripPosition = feedUpStripPosition,
                    onVideoClick = onVideoClick,
                    onUserClick = onUserClick,
                    onLiveClick = onLiveClick,
                    onOpenFollowings = onOpenFollowings,
                    onOpenOtherDynamics = onOpenOtherDynamics,
                    onOpenPushes = onOpenPushes,
                    onEnterNightMode = onEnterNightMode,
                    onOpenArticle = onOpenArticle,
                )

                RootTab.Search -> SearchPane(
                    container = container,
                    focusSearchNonce = focusSearchNonce,
                    searchHomeClicks = searchHomeClicks,
                    onVideoClick = onVideoClick,
                    onUserClick = onUserClick,
                    onOpenArticle = onOpenArticle,
                    onOpen = onOpen,
                )

                RootTab.Profile -> ProfilePane(
                    container = container,
                    vm = profileVm,
                    scrollToTop = profileScrollToTop,
                    showAccountHeader = showAccountHeader,
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
        DraftQueueOverlay(
            container = container,
            hasVideoPage = hasVideoPage,
            onPlayDraftQueue = onPlayDraftQueue,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoxScope.DraftQueueOverlay(
    container: AppContainer,
    hasVideoPage: Boolean,
    onPlayDraftQueue: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val draftItems by container.customQueueDraft.items.collectAsStateWithLifecycle()
    var draftSheetOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(draftItems.isEmpty(), hasVideoPage) {
        if (draftItems.isEmpty() || hasVideoPage) draftSheetOpen = false
    }
    AnimatedVisibility(
        visible = !hasVideoPage && draftItems.isNotEmpty() && !draftSheetOpen,
        modifier = modifier.align(Alignment.BottomCenter),
    ) {
        DraftQueueBar(
            count = draftItems.size,
            onOpen = { draftSheetOpen = true },
            onPlay = { onPlayDraftQueue(null) },
            onClear = container.customQueueDraft::clear,
        )
    }
    if (draftSheetOpen && !hasVideoPage && draftItems.isNotEmpty()) {
        val draftRows = remember(draftItems) {
            draftItems.map { item ->
                EpisodeRow(
                    bvid = item.bvid,
                    title = item.title,
                    upName = item.upName,
                    coverUrl = item.coverUrl,
                    durationSeconds = item.durationSeconds,
                    isCurrent = false,
                )
            }
        }
        val draftEditActions = remember(container.customQueueDraft) {
            QueueEditActions(
                onMove = container.customQueueDraft::move,
                onRemove = container.customQueueDraft::remove,
                onPlayNext = { item -> container.customQueueDraft.enqueue(item, playNext = true) },
                allowRemoveLast = true,
            )
        }
        PaneSheet(
            onDismissRequest = { draftSheetOpen = false },
            title = stringResource(Res.string.queue_draft_summary, draftItems.size),
            skipPartiallyExpanded = true,
            headerTrailing = {
                TextButton(
                    onClick = {
                        container.customQueueDraft.clear()
                        draftSheetOpen = false
                    },
                ) {
                    Text(stringResource(Res.string.action_clear))
                }
                FilledTonalButton(
                    onClick = {
                        draftSheetOpen = false
                        onPlayDraftQueue(null)
                    },
                    contentPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = Spacing.Hair),
                ) {
                    Text(
                        text = stringResource(Res.string.queue_draft_play),
                        style = MaterialTheme.typography.labelLargeEmphasized,
                    )
                }
            },
        ) {
            EpisodeList(
                rows = draftRows,
                onSelect = { target ->
                    if (target is EpisodeTarget.Video) {
                        draftSheetOpen = false
                        onPlayDraftQueue(target.bvid)
                    }
                },
                editActions = draftEditActions,
                editing = true,
                contentPadding = PaddingValues(
                    start = Spacing.Comfortable,
                    end = Spacing.Comfortable,
                    bottom = Spacing.Loose,
                ),
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DraftQueueBar(
    count: Int,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onClear: () -> Unit,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    ) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .clickable(role = Role.Button, onClick = onOpen)
                .padding(horizontal = Spacing.Tight, vertical = Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.PlaylistPlay,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Dimens.IconInline),
            )
            Text(
                text = stringResource(Res.string.queue_draft_summary, count),
                style = MaterialTheme.typography.labelLargeEmphasized.tabular,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(Spacing.Tight))
        FilledTonalButton(
            onClick = onPlay,
            contentPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = Spacing.Hair),
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconInline),
            )
            Text(
                text = stringResource(Res.string.queue_draft_play),
                style = MaterialTheme.typography.labelLargeEmphasized,
                modifier = Modifier.padding(start = Spacing.Hair),
            )
        }
        IconButton(onClick = onClear) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(Res.string.action_clear),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.IconInline),
            )
        }
    }
}

@Composable
private fun FeedPane(
    container: AppContainer,
    scrollToTop: Int,
    upStripPosition: FeedUpStripPosition,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onLiveClick: (Long) -> Unit,
    onOpenFollowings: () -> Unit,
    onOpenOtherDynamics: () -> Unit,
    /** 订阅页标题行的「推送」:UP 主推送来的那些私信会话,见 MessagePushesScreen。 */
    onOpenPushes: () -> Unit,
    onEnterNightMode: () -> Unit,
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
    RegisterPageRefresh(refreshing = state.refreshing, onRefresh = vm::refresh)
    FeedScreen(
        state = state,
        scrollToTop = scrollToTop,
        upStripPosition = upStripPosition,
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
        onEnterNightMode = onEnterNightMode,
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
    focusSearchNonce: Int,
    searchHomeClicks: Int,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onOpenArticle: (ArticleRef) -> Unit,
    onOpen: (NavKey) -> Unit,
) {
    val vm: SearchChatViewModel = viewModel(
        key = "root-search",
        factory = viewModelFactory {
            initializer {
                SearchChatViewModel(
                    container.searchRepository,
                    container.agentLoop,
                    container.settings,
                    resolveShortLink = container.biliClient::resolveRedirect,
                )
            }
        },
    )
    var lastHomeClick by remember { mutableIntStateOf(searchHomeClicks) }
    LaunchedEffect(searchHomeClicks) {
        if (searchHomeClicks != lastHomeClick) {
            lastHomeClick = searchHomeClicks
            vm.backToLanding()
        }
    }
    val currentOnOpen by rememberUpdatedState(onOpen)
    LaunchedEffect(vm) { vm.open.collect { currentOnOpen(it) } }
    val state by vm.state.collectAsStateWithLifecycle()
    val history by vm.searchHistory.collectAsStateWithLifecycle()
    val agentAvailable by vm.agentAvailable.collectAsStateWithLifecycle()
    val agentPanel by vm.agentPanel.collectAsStateWithLifecycle()
    val searchLanding by remember(container.settings) {
        container.settings.appearancePrefs.map { it.searchLanding }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = true)
    val activeNormalList = when (state.normal.tab) {
        SearchTab.Video -> state.normal.videos
        SearchTab.User -> state.normal.users
        SearchTab.Article -> state.normal.articles
    }
    val normalRefreshing = activeNormalList.loading && activeNormalList.items.isNotEmpty()
    if (state.normal.query.isNotEmpty()) {
        RegisterPageRefresh(refreshing = normalRefreshing, onRefresh = vm::refresh)
    }
    SearchChatScreen(
        state = state,
        focusSearchNonce = focusSearchNonce,
        refreshing = normalRefreshing,
        onRefresh = vm::refresh,
        searchHistory = history,
        onHistoryClick = vm::searchFromHistory,
        onHistoryRemove = vm::removeSearchHistory,
        onHistoryClear = vm::clearSearchHistory,
        onSuggestionClick = vm::searchSuggestion,
        searchLanding = searchLanding,
        onBackToLanding = vm::backToLanding,
        onInputChange = vm::onInputChange,
        onSearch = vm::search,
        onAskAgent = vm::askAgent,
        onLeaveAgent = vm::leaveAgent,
        agentAvailable = agentAvailable,
        agentPanel = agentPanel,
        onAgentPanelOpenChange = { vm.setAgentPanelOpen(it) },
        onAgentPanelWidthChange = { vm.setAgentPanelWidth(it) },
        agentActions = AgentActions(
            onInputChange = vm::onAgentInputChange,
            onSend = vm::sendToAgent,
            onNewSession = vm::newSession,
            onRetry = vm::retryAgent,
            onVideoClick = onVideoClick,
        ),
        resultActions = SearchResultActions(
            onTabSelected = vm::onTabSelected,
            onOrderChange = vm::onOrderChanged,
            onDurationChange = vm::onDurationChanged,
            onPubTimeChange = vm::onPubTimeChanged,
            onZoneChange = vm::onZoneChanged,
            onArticleOrderChange = vm::onArticleOrderChanged,
            onUserOrderChange = vm::onUserOrderChanged,
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
    LaunchedEffect(state.tab) {
        scrollBehavior.state.contentOffset = 0f
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            // 与列表里的下拉同一个判据,按当前那一栏取,见 SearchResults 里的 RefreshBox。
            val list = when (state.tab) {
                SearchTab.Video -> state.videos
                SearchTab.User -> state.users
                SearchTab.Article -> state.articles
            }
            BilbyTopBar(
                title = keyword,
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                refreshing = list.loading && list.items.isNotEmpty(),
                onRefresh = vm::retry,
            )
        },
    ) { insets ->
        SearchResultScreen(
            state = state,
            actions = SearchResultActions(
                onTabSelected = vm::onTabSelected,
                onOrderChange = vm::onOrderChanged,
                onDurationChange = vm::onDurationChanged,
                onPubTimeChange = vm::onPubTimeChanged,
                onZoneChange = vm::onZoneChanged,
                onArticleOrderChange = vm::onArticleOrderChanged,
                onUserOrderChange = vm::onUserOrderChanged,
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
    vm: ProfileViewModel,
    scrollToTop: Int,
    showAccountHeader: Boolean,
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
    val state by vm.state.collectAsStateWithLifecycle()

    // 重新进入这一页时整个重取。挂在"进组合"上而不是底栏那次点击上:从历史记录、稍后再看
    // 这些子页面返回时 Home 整个重新进组合,而那正是概览最可能已经过期的时候(用户刚在那边
    // 删了东西),底栏的 onClick 覆盖不到这条路。
    //
    // 首次进入时 ViewModel 的 init 已经拉过一次,这里会再来一次 —— 认了。少这一次的写法
    // (去抖 + 版本号)试过,漏的比省的多,见 [ProfileViewModel.refresh]。
    LaunchedEffect(Unit) { vm.refresh() }
    val refreshing = state.accountLoading ||
        state.history.loading ||
        state.toView.loading ||
        state.favFolders.loading
    RegisterPageRefresh(refreshing = refreshing, onRefresh = vm::refresh)

    ProfileScreen(
        state = state,
        scrollToTop = scrollToTop,
        showAccountHeader = showAccountHeader,
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
        onRefresh = vm::refresh,
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
    twoPane: Boolean,
    openSection: SettingsSection?,
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
        twoPane = twoPane,
        openSection = openSection,
    )
}

/** 设置的二级页面。七页共用一条路由,分支在这里,理由见 `SettingsPage` 的说明。 */
@Composable
private fun SettingsPageRoute(
    container: AppContainer,
    section: SettingsSection,
    onOpenSection: (SettingsSection) -> Unit,
    onOpenBlacklist: () -> Unit,
    onBack: (() -> Unit)?,
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
            onMotionStyleChange = vm::setMotionStyle,
            onStartTabChange = vm::setStartTab,
            onSearchLandingChange = vm::setSearchLanding,
            onPredictiveBackChange = vm::setPredictiveBack,
            onVideoGridColumnsChange = vm::setVideoGridColumns,
            onLanguageChange = system::applyLanguage,
            onBack = onBack,
        )

        SettingsSection.Playback -> PlaybackSettingsPage(
            state = state,
            onWifiQualityChange = { vm.setDefaultQuality(it, metered = false) },
            onMeteredQualityChange = { vm.setDefaultQuality(it, metered = true) },
            onCodecChange = vm::setCodec,
            onEndModeChange = vm::setEndMode,
            onPbpOutroCompletionChange = vm::setPbpOutroCompletion,
            onWifiAudioChange = { vm.setDefaultAudio(it, metered = false) },
            onMeteredAudioChange = { vm.setDefaultAudio(it, metered = true) },
            onWifiLiveQualityChange = { vm.setDefaultLiveQuality(it, metered = false) },
            onMeteredLiveQualityChange = { vm.setDefaultLiveQuality(it, metered = true) },
            onPickUpdatesDefaultChange = vm::setPlayerPickUpdatesDefault,
            onSponsorBlockChange = vm::updateSponsorBlock,
            onOpenSponsorCategories = { onOpenSection(SettingsSection.SponsorCategories) },
            onOfflineConcurrencyChange = vm::setOfflineConcurrency,
            onBack = onBack,
        )

        SettingsSection.Gestures -> PlayerCustomizationPage(
            state = state,
            onUpdateLayout = vm::updatePlayerLayout,
            onResetLayout = vm::resetPlayerLayout,
            onDoubleTapSeekChange = vm::setDoubleTapSeek,
            onLongPressSpeedChange = vm::setLongPressSpeed,
            onLongPressSpeedDialChange = vm::setLongPressSpeedDial,
            onDefaultHoldSpeedChange = vm::setDefaultHoldSpeed,
            onSwipeSeekChange = vm::setSwipeSeek,
            onSwipeBrightnessVolumeChange = vm::setSwipeBrightnessVolume,
            onBack = onBack,
        )

        SettingsSection.Navigation -> NavigationCustomizationPage(
            state = state,
            onUpdatePrefs = vm::updateNavigationPrefs,
            onResetPrefs = vm::resetNavigationPrefs,
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

        SettingsSection.NightMode -> {
            val followPicker by vm.followPicker.collectAsStateWithLifecycle()
            NightModeSettingsPage(
                state = state,
                followPicker = followPicker,
                onLoadFollowings = vm::loadFollowingsIfNeeded,
                onLoadMoreFollowings = vm::loadMoreFollowings,
                onSearchFollowings = vm::searchFollowings,
                onEnabledChange = vm::setNightModeEnabled,
                onUpsertRule = vm::upsertScheduleRule,
                onRuleEnabledChange = vm::setScheduleRuleEnabled,
                onDeleteRule = vm::deleteScheduleRule,
                onEngageRuleManually = vm::engageNightModeManually,
                onClearOverride = vm::clearNightModeOverride,
                onBack = onBack,
            )
        }
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
                followRepository = container.followRepository,
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HistoryRoute(
    container: AppContainer,
    onVideoClick: (String) -> Unit,
    onUpClick: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm: HistoryViewModel = viewModel(
        factory = viewModelFactory { initializer { HistoryViewModel(container.historyRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    // null 表示不在多选态。空集合是多选态里的合法状态 —— 顶栏的「选择」要在一条都没选的
    // 时候就进得去。
    var selectedIds by remember { mutableStateOf<Set<Long>?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchFocusNonce by remember { mutableIntStateOf(0) }
    val searching = searchOpen || state.appliedKeyword.isNotBlank()
    RegisterPageSearch(
        onTrigger = {
            selectedIds = null
            searchOpen = true
            searchFocusNonce++
        },
    )
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmingDeleteSelected by remember { mutableStateOf(false) }
    var confirmingDeleteSingle by remember { mutableStateOf<HistoryItem?>(null) }
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
    LaunchedEffect(state.items.isEmpty()) {
        if (state.items.isEmpty()) scrollBehavior.state.contentOffset = 0f
    }
    val layoutDirection = LocalLayoutDirection.current
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(
                targetState = selectedIds,
                transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
                contentKey = { it != null },
                label = "history-top-bar",
            ) { ids ->
                if (ids == null) {
                    BilbySearchTopBar(
                        title = stringResource(Res.string.history_title),
                        searching = searching,
                        query = state.keyword,
                        placeholder = stringResource(Res.string.history_search_hint),
                        onQueryChange = vm::onKeywordChange,
                        onSearch = vm::search,
                        onOpenSearch = {
                            selectedIds = null
                            searchOpen = true
                        },
                        onCloseSearch = {
                            searchOpen = false
                            vm.closeSearch()
                        },
                        onBack = onBack,
                        scrollBehavior = scrollBehavior,
                        refreshing = state.refreshing,
                        onRefresh = vm::refresh,
                        focusRequestNonce = searchFocusNonce,
                        actions = {
                            IconButton(
                                onClick = {
                                    searchOpen = false
                                    selectedIds = emptySet()
                                },
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
                            DesktopMenuMotionTheme {
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                    shape = MenuDefaults.shape,
                                    containerColor = MenuDefaults.containerColor,
                                ) {
                                    DropdownMenuItem(
                                        onClick = {
                                            menuExpanded = false
                                            confirmingClearFinished = true
                                        },
                                        text = { Text(stringResource(Res.string.history_clear_finished)) },
                                        shape = menuItemShape(0, 2),
                                        enabled = finished.isNotEmpty(),
                                    )
                                    DropdownMenuItem(
                                        onClick = {
                                            menuExpanded = false
                                            confirmingClearAll = true
                                        },
                                        text = { Text(stringResource(Res.string.history_clear_all)) },
                                        shape = menuItemShape(1, 2),
                                        enabled = state.items.isNotEmpty(),
                                    )
                                }
                            }
                        },
                    )
                } else {
                    // 多选时顶栏只留退出按钮与选中计数，具体批量动作收到底部拇指区浮动工具条。
                    BilbyTopBar(
                        title = stringResource(Res.string.history_selected_count, ids.size),
                        onBack = { selectedIds = null },
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            AnimatedVisibility(
                visible = selectedIds != null,
                enter = slideInVertically { it } + fadeIn(fadeSpec),
                exit = slideOutVertically { it } + fadeOut(fadeSpec),
            ) {
                val ids = selectedIds ?: emptySet()
                val all = remember(state.items) { state.items.map { it.oid }.toSet() }
                val allSelected = all.isNotEmpty() && ids == all
                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
                ) {
                    ToggleButton(
                        checked = allSelected,
                        onCheckedChange = { selectedIds = if (allSelected) emptySet() else all },
                    ) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = null)
                        Spacer(Modifier.width(Spacing.Hair))
                        Text(stringResource(Res.string.history_select_all))
                    }
                    Spacer(Modifier.width(Spacing.Tight))
                    DestructiveConfirmButton(
                        text = stringResource(Res.string.action_delete),
                        enabled = ids.isNotEmpty() && !state.mutating,
                        onClick = { confirmingDeleteSelected = true },
                    )
                }
            }
        },
    ) { insets ->
        val effectivePadding = if (selectedIds != null) {
            PaddingValues(
                start = insets.calculateStartPadding(layoutDirection),
                top = insets.calculateTopPadding(),
                end = insets.calculateEndPadding(layoutDirection),
                bottom = insets.calculateBottomPadding() + ToViewFabClearance,
            )
        } else {
            insets
        }
        HistoryScreen(
            state = state,
            onItemClick = { onVideoClick(it.bvid) },
            onLoadMore = vm::loadMore,
            onRetry = vm::retry,
            onRefresh = vm::refresh,
            selectedIds = selectedIds,
            onToggleSelection = { item ->
                if (searchOpen && state.appliedKeyword.isBlank()) searchOpen = false
                val current = selectedIds ?: emptySet()
                selectedIds = if (item.oid in current) current - item.oid else current + item.oid
            },
            onDeleteSingle = { item -> confirmingDeleteSingle = item },
            onUpClick = onUpClick,
            onDismissMutationError = vm::dismissMutationError,
            contentPadding = effectivePadding,
        )
    }

    // 三个确认框各说各的范围。删除不可撤销,而这三件事影响的条数各不相同 —— 合并成一句
    // "确定删除吗"就把范围这个唯一值得确认的东西省掉了。
    confirmingDeleteSingle?.let { target ->
        HistoryConfirmDialog(
            message = stringResource(Res.string.history_delete_one_confirm),
            confirmLabel = stringResource(Res.string.action_delete),
            onConfirm = { vm.delete(listOf(target)) },
            onDismiss = { confirmingDeleteSingle = null },
        )
    }
    if (confirmingDeleteSelected) {
        HistoryConfirmDialog(
            message = stringResource(Res.string.history_delete_confirm, selected.size),
            confirmLabel = stringResource(Res.string.action_delete),
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
            confirmLabel = stringResource(Res.string.action_clear),
            onConfirm = { vm.delete(finished) },
            onDismiss = { confirmingClearFinished = false },
        )
    }
    if (confirmingClearAll) {
        HistoryConfirmDialog(
            message = stringResource(Res.string.history_clear_all_confirm),
            confirmLabel = stringResource(Res.string.action_clear),
            onConfirm = {
                vm.clearAll()
                selectedIds = null
            },
            onDismiss = { confirmingClearAll = false },
        )
    }
}

/** 只有标题的确认框:陈述动作即止,确认按钮用高危填充钮标出动词（删除/清空）。 */
@Composable
private fun HistoryConfirmDialog(
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(message) },
        confirmButton = {
            DestructiveConfirmButton(
                text = confirmLabel,
                onClick = {
                    onDismiss()
                    onConfirm()
                },
            )
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

/**
 * 分栏的场景键。消息页、推送页与它们打开的私信、评论详情同属一组:推送页也可能压在消息页上,
 * 同组时右栏跟着栈顶那个列表走。
 */
private const val MessagesScene = "messages"
private const val SettingsScene = "settings"

/**
 * 左栏定宽:一行只有头像、名字和一行摘要,再宽也只是摘要长一点;右栏才要宽度。
 * 设置首页五行入口,同一个宽度够用。
 */
private val ListPaneWidth = 380.dp

/** 只有从列表打开的详情带分栏标记,理由见 [Whisper.inListPane]。 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private fun listDetailMetadata(inListPane: Boolean, sceneKey: Any): Map<String, Any> =
    if (inListPane) ListDetailSceneStrategy.detailPane(sceneKey) else emptyMap()

/** 右栏没有打开任何一条时的那一句,消息页与推送页共用。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageDetailPlaceholder() {
    Column(modifier = Modifier.fillMaxSize()) {
        if (LocalIsDesktopWindow.current) {
            BilbyTopBar(title = "")
        }
        EmptyState(
            stringResource(Res.string.message_detail_placeholder),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            icon = Icons.Outlined.Forum,
        )
    }
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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

    // null 表示不在多选态,理由见 [OfflineScreen] 那个参数的注释。
    var selectedIds by remember { mutableStateOf<Set<String>?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchFocusNonce by remember { mutableIntStateOf(0) }
    val searching = searchOpen || searchQuery.isNotEmpty()
    RegisterPageSearch(
        onTrigger = {
            selectedIds = null
            searchOpen = true
            searchFocusNonce++
        },
    )
    var confirmingBatchDelete by remember { mutableStateOf(false) }
    var selectableIds by remember { mutableStateOf<Set<String>>(items.mapTo(LinkedHashSet()) { it.id }) }
    val selected = remember(items, selectedIds, selectableIds) {
        val activeIds = selectedIds?.intersect(selectableIds).orEmpty()
        items.filter { it.id in activeIds }
    }
    // 切换标签/子视图或删除条目后，自动裁掉当前视图不再可选的 id；纯分组卡片无可选行时自动退出多选。
    LaunchedEffect(selectableIds) {
        val current = selectedIds ?: return@LaunchedEffect
        selectedIds = if (selectableIds.isEmpty()) {
            null
        } else {
            current.intersect(selectableIds)
        }
    }
    BackHandler(enabled = selectedIds != null) { selectedIds = null }

    // 见 [SearchResultRoute];两条顶栏同接一个 behavior,同历史记录页。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    LaunchedEffect(items.isEmpty()) {
        if (items.isEmpty()) scrollBehavior.state.contentOffset = 0f
    }
    val layoutDirection = LocalLayoutDirection.current
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedContent(
                targetState = selectedIds,
                transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
                contentKey = { it != null },
                label = "offline-top-bar",
            ) { ids ->
                if (ids == null) {
                    BilbySearchTopBar(
                        title = stringResource(Res.string.offline_title),
                        searching = searching,
                        query = searchQuery,
                        placeholder = stringResource(Res.string.offline_search_hint),
                        onQueryChange = { searchQuery = it },
                        onSearch = {},
                        onOpenSearch = {
                            selectedIds = null
                            searchOpen = true
                        },
                        onCloseSearch = {
                            searchOpen = false
                            searchQuery = ""
                        },
                        onBack = onBack,
                        scrollBehavior = scrollBehavior,
                        focusRequestNonce = searchFocusNonce,
                        actions = {
                            // 多选此前只能长按进入,而长按没有任何视觉提示。照历史记录页的做法
                            // 在顶栏放一个入口,长按保留；仅当前视图存在可选行时显示。
                            if (selectableIds.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        searchOpen = false
                                        searchQuery = ""
                                        selectedIds = emptySet()
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.Checklist,
                                        contentDescription = stringResource(Res.string.action_select),
                                    )
                                }
                            }
                        },
                    )
                } else {
                    // 多选时顶栏只留退出按钮与选中计数，批量操作移至底部拇指区浮动工具条。
                    BilbyTopBar(
                        title = stringResource(Res.string.offline_selected_count, ids.intersect(selectableIds).size),
                        onBack = { selectedIds = null },
                        scrollBehavior = scrollBehavior,
                    )
                }
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            AnimatedVisibility(
                visible = selectedIds != null,
                enter = slideInVertically { it } + fadeIn(fadeSpec),
                exit = slideOutVertically { it } + fadeOut(fadeSpec),
            ) {
                val all = selectableIds
                val ids = selectedIds?.intersect(all) ?: emptySet()
                val allSelected = all.isNotEmpty() && ids == all
                val hasPausable = selected.any {
                    it.status == OfflineStatus.Queued || it.status == OfflineStatus.Running
                }
                val hasResumable = selected.any {
                    it.status == OfflineStatus.Paused || it.status == OfflineStatus.Failed
                }
                val toolbarActionPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = Spacing.Hair)
                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
                ) {
                    ToggleButton(
                        checked = allSelected,
                        onCheckedChange = { selectedIds = if (allSelected) emptySet() else all },
                        enabled = all.isNotEmpty(),
                        contentPadding = toolbarActionPadding,
                    ) {
                        Icon(
                            Icons.Outlined.SelectAll,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.IconInline),
                        )
                        Spacer(Modifier.width(Spacing.Hair))
                        Text(
                            stringResource(Res.string.offline_sheet_select_all),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    if (hasPausable) {
                        Spacer(Modifier.width(Spacing.Tight))
                        FilledTonalButton(
                            onClick = {
                                vm.pauseSelected(ids)
                                selectedIds = null
                            },
                            contentPadding = toolbarActionPadding,
                        ) {
                            Icon(
                                Icons.Filled.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(Dimens.IconInline),
                            )
                            Spacer(Modifier.width(Spacing.Hair))
                            Text(
                                stringResource(Res.string.player_pause),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    if (hasResumable) {
                        Spacer(Modifier.width(Spacing.Tight))
                        FilledTonalButton(
                            onClick = {
                                vm.resumeSelected(ids)
                                selectedIds = null
                            },
                            contentPadding = toolbarActionPadding,
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(Dimens.IconInline),
                            )
                            Spacer(Modifier.width(Spacing.Hair))
                            Text(
                                stringResource(Res.string.offline_action_resume),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    Spacer(Modifier.width(Spacing.Tight))
                    DestructiveConfirmButton(
                        text = stringResource(Res.string.action_delete),
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            // 一个字节都还没下的那些直接删,理由同单条(见 OfflineScreen)。
                            if (selected.all { it.status == OfflineStatus.Queued && it.downloadedBytes == 0L }) {
                                vm.deleteAll(selected)
                                selectedIds = null
                            } else {
                                confirmingBatchDelete = true
                            }
                        },
                    )
                }
            }
        },
    ) { insets ->
        val effectivePadding = if (selectedIds != null) {
            PaddingValues(
                start = insets.calculateStartPadding(layoutDirection),
                top = insets.calculateTopPadding(),
                end = insets.calculateEndPadding(layoutDirection),
                bottom = insets.calculateBottomPadding() + ToViewFabClearance,
            )
        } else {
            insets
        }
        OfflineScreen(
            items = items,
            usedBytes = usedBytes,
            onPlay = onPlay,
            onDelete = vm::delete,
            onRetry = vm::retry,
            onPause = vm::pause,
            onResume = vm::resume,
            onPauseAll = vm::pauseAll,
            onResumeAll = vm::resumeAll,
            onRetryAllFailed = vm::retryAllFailed,
            onClearQueue = vm::clearQueue,
            onDeleteAll = vm::deleteAll,
            selectedIds = selectedIds,
            onToggleSelection = { item ->
                if (searchOpen && searchQuery.isEmpty()) searchOpen = false
                val current = selectedIds.orEmpty()
                selectedIds = if (item.id in current) current - item.id else current + item.id
            },
            onSelectableIdsChanged = { selectableIds = it },
            searchQuery = searchQuery,
            contentPadding = effectivePadding,
        )
    }

    if (confirmingBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmingBatchDelete = false },
            title = { Text(stringResource(Res.string.offline_delete_batch_title)) },
            text = { Text(stringResource(Res.string.offline_delete_batch_message, selected.size)) },
            confirmButton = {
                DestructiveConfirmButton(
                    text = stringResource(Res.string.action_delete),
                    onClick = {
                        confirmingBatchDelete = false
                        vm.deleteAll(selected)
                        selectedIds = null
                    },
                )
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
    onUpClick: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm: ToViewViewModel = viewModel(
        factory = viewModelFactory { initializer { ToViewViewModel(container.toViewRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmingClear by remember { mutableStateOf<ToViewClear?>(null) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchFocusNonce by remember { mutableIntStateOf(0) }
    val searching = searchOpen || searchQuery.isNotEmpty()
    RegisterPageSearch(
        onTrigger = {
            searchOpen = true
            searchFocusNonce++
        },
    )

    // 移出成功之后给一次撤销。稍后再看是远程列表,删掉就得回 B 站重新找一遍那条视频,
    // 而这个删除按钮紧挨着整行的可点区。
    val removedMessage = stringResource(Res.string.toview_removed)
    val undoLabel = stringResource(Res.string.action_undo)
    LaunchedEffect(state.lastRemoved) {
        val removed = state.lastRemoved ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = removedMessage,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Short,
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
    LaunchedEffect(state.asc, state.items.isEmpty()) {
        scrollBehavior.state.contentOffset = 0f
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbySearchTopBar(
                title = stringResource(Res.string.tab_toview),
                searching = searching,
                query = searchQuery,
                placeholder = stringResource(Res.string.toview_search_hint),
                onQueryChange = { searchQuery = it },
                onSearch = {},
                onOpenSearch = { searchOpen = true },
                onCloseSearch = {
                    searchOpen = false
                    searchQuery = ""
                },
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                refreshing = state.loading && state.items.isNotEmpty(),
                onRefresh = vm::refresh,
                focusRequestNonce = searchFocusNonce,
                actions = {
                    IconButton(
                        onClick = { confirmingClear = ToViewClear.Invalid },
                        enabled = !state.clearing && state.items.isNotEmpty(),
                    ) {
                        Icon(
                            Icons.Outlined.LinkOff,
                            contentDescription = stringResource(Res.string.toview_clear_invalid),
                        )
                    }
                },
            )
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
            onAscChanged = vm::setAsc,
            onListenItem = { onListen(it.bvid, QueueContext.ToView(state.asc)) },
            onUpClick = onUpClick,
            onRefresh = vm::refresh,
            searchQuery = searchQuery,
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
    onUpClick: (Long) -> Unit,
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

    // 中档可折叠顶栏配合 exitUntilCollapsedScrollBehavior，滚动时收起释放列表空间。
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LaunchedEffect(state.order, state.appliedKeyword, state.items.isEmpty()) {
        scrollBehavior.state.contentOffset = 0f
        scrollBehavior.state.heightOffset = 0f
    }
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
            onListenItem = { item -> onListen(item.bvid, queueContext(item)) },
            onOrderChanged = vm::setOrder,
            onLoadMore = vm::loadMore,
            onRetry = vm::retry,
            onRefresh = vm::refresh,
            onNoticeShown = vm::dismissNotice,
            editorActions = vm.manager.editorActions,
            deletionActions = vm.manager.deletionActions,
            onUpClick = onUpClick,
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
    LaunchedEffect(state.source, state.order, state.query, state.items.isEmpty()) {
        scrollBehavior.state.contentOffset = 0f
    }
    // 宽屏时排序进名单上方的筛选行(见 WideFilterRow),搜索框换到顶栏正中,刷新统一锚定在顶栏左侧:
    // 放在筛选行里,48dp 的输入框和 32dp 的芯片挤在一条线上,高低不齐。
    val wide = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(
                title = stringResource(Res.string.followings_title),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                refreshing = state.refreshing,
                onRefresh = vm::refresh,
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
private fun NightModeHomeRoute(
    container: AppContainer,
    activeStatus: NightWindowStatus.Active?,
    hasVideoPage: Boolean,
    onPlayDraftQueue: (String?) -> Unit,
    onVideoClick: (String, QueueContext) -> Unit,
    onOpenSpace: (Long) -> Unit,
    onOpenOffline: () -> Unit,
    onOpenSettings: () -> Unit,
    onTempExit: () -> Unit,
) {
    val vm: NightModeViewModel = viewModel(
        key = "night-mode",
        factory = viewModelFactory {
            initializer {
                NightModeViewModel(
                    spaceRepository = container.spaceRepository,
                    toViewRepository = container.toViewRepository,
                )
            }
        },
    )
    val activeRules = activeStatus?.activeRules.orEmpty()
    val activeAllowedUps = activeStatus?.allowedUps.orEmpty()
    val activeRuleName = activeRules.singleOrNull()?.name?.trim().orEmpty()
    LaunchedEffect(activeAllowedUps, activeRuleName) {
        vm.syncActiveContext(activeAllowedUps, activeRuleName)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    NightModeScreen(
        activeRules = activeRules,
        windowEndEpochMillis = activeStatus?.window?.endEpochMillis,
        state = state,
        manual = activeStatus?.manual ?: true,
        onSelectUp = vm::selectUp,
        onLoadMore = vm::loadMore,
        onRetry = vm::retry,
        onRefresh = vm::refresh,
        onVideoClick = { item -> onVideoClick(item.bvid, vm.buildQueueContext(item)) },
        onAddToView = vm::addToView,
        onClearToViewNotice = vm::clearToViewNotice,
        onOpenSpace = onOpenSpace,
        onOpenOffline = onOpenOffline,
        onOpenSettings = onOpenSettings,
        onTempExit = onTempExit,
        bottomOverlay = {
            DraftQueueOverlay(
                container = container,
                hasVideoPage = hasVideoPage,
                onPlayDraftQueue = onPlayDraftQueue,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    )
}

@Composable
private fun SpaceRoute(
    container: AppContainer,
    mid: Long,
    onVideoClick: (String, QueueContext) -> Unit,
    onLiveClick: ((Long) -> Unit)?,
    onCollectionClick: (SpaceCollectionItem) -> Unit,
    onDynamicAction: (DynamicAction) -> Unit,
    onBack: () -> Unit,
    vodOnly: Boolean = false,
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
    val upName = state.profile?.name.orEmpty()
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
            onVideoClick(
                item.bvid,
                state.archives.queueContext(
                    mid = mid,
                    page = index / SpaceRepository.ARCHIVE_PAGE_SIZE + 1,
                    upName = upName,
                ),
            )
        },
        onDynamicAction = onDynamicAction,
        onDynamicVideoClick = { item ->
            item.video?.let { video ->
                onVideoClick(video.bvid, QueueContext.UpDynamics(mid, item.pageOffset, upName = upName))
            }
        },
        onLikeDynamic = vm::likeDynamic,
        onLiveClick = onLiveClick,
        onToggleFollow = vm::toggleFollow,
        onSetBlocked = vm::setBlocked,
        onOpenGroupPicker = vm::openGroupPicker,
        onCloseGroupPicker = vm::closeGroupPicker,
        onToggleGroup = vm::toggleGroup,
        onSaveGroups = vm::saveGroups,
        onBack = onBack,
        onRetry = vm::retry,
        mid = mid,
        onRefresh = vm::refresh,
        onDynamicsSheetOpenChange = vm::setDynamicsSheetOpen,
        onDynamicsSheetWidthChange = vm::setDynamicsSheetWidth,
        vodOnly = vodOnly,
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
    push: (NavKey) -> Unit,
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
    val commentState = commentVm?.state?.collectAsStateWithLifecycle()?.value ?: CommentUiState()

    DynamicDetailScreen(
        state = state,
        commentState = commentState,
        onBack = onBack,
        onRetry = vm::retry,
        onAction = { action ->
            // 已经在这条动态里时不再原地压栈自身;嵌套在里面的被转发原动态(id 不同)、直播、
            // 预约、视频、专栏与链接全部按同一套规则跳转。
            if (action is DynamicAction.OpenDynamic && action.id == id) {
                // 已在当前动态页
            } else {
                handleDynamicAction(action, system, container, scope, push)
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
    onVideoClick: (String) -> Unit,
    onUpClick: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm: FavFoldersViewModel = viewModel(
        factory = viewModelFactory { initializer { FavFoldersViewModel(container.favRepository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val editor by vm.manager.editor.collectAsStateWithLifecycle()
    val deletion by vm.manager.deletion.collectAsStateWithLifecycle()
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchFocusNonce by remember { mutableIntStateOf(0) }
    val searching = searchOpen || state.appliedKeyword.isNotBlank()
    RegisterPageSearch(
        onTrigger = {
            searchOpen = true
            searchFocusNonce++
        },
    )
    // 从某个收藏夹返回时这一页重新进组合,那正是列表最可能过期的时候。判据见 onEnter。
    LaunchedEffect(Unit) { vm.onEnter() }
    // 见 [SearchResultRoute]。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    LaunchedEffect(state.folders.isEmpty(), state.appliedKeyword) {
        if (state.folders.isEmpty() || state.appliedKeyword.isNotBlank()) {
            scrollBehavior.state.contentOffset = 0f
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbySearchTopBar(
                title = stringResource(Res.string.fav_folders_title),
                searching = searching,
                query = state.keyword,
                placeholder = stringResource(Res.string.fav_all_search_hint),
                onQueryChange = vm::onKeywordChange,
                onSearch = vm::search,
                onOpenSearch = { searchOpen = true },
                onCloseSearch = {
                    searchOpen = false
                    vm.closeSearch()
                },
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                refreshing = if (state.appliedKeyword.isNotBlank()) state.searchRefreshing else state.refreshing,
                onRefresh = if (state.appliedKeyword.isNotBlank()) vm::refreshSearch else vm::refresh,
                focusRequestNonce = searchFocusNonce,
                actions = {
                    // 新建是这一页的页级操作,放顶栏。它原先是右下角的 FAB:FAB 要让开列表最后一行,
                    // 而一个不常用的操作不值得常驻在内容上面。
                    IconButton(onClick = vm.manager::startCreate) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(Res.string.fav_folder_create))
                    }
                },
            )
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
            onVideoClick = { onVideoClick(it.bvid) },
            onUpClick = onUpClick,
            onLoadMoreSearch = vm::loadMoreSearch,
            onRetrySearch = vm::retrySearch,
            onRefreshSearch = vm::refreshSearch,
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
    LaunchedEffect(state.users.isEmpty()) {
        if (state.users.isEmpty()) scrollBehavior.state.contentOffset = 0f
    }
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
    onSearchTag: ((String) -> Unit)?,
    onBack: () -> Unit,
    disableDiscovery: Boolean = false,
    nightBanner: (@Composable () -> Unit)? = null,
    onAuthorResolved: (bvid: String, upMid: Long, staffMids: Set<Long>) -> Unit = { _, _, _ -> },
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
        disableDiscovery = disableDiscovery,
        nightBanner = nightBanner,
        onAuthorResolved = { upMid, staffMids -> onAuthorResolved(shownBvid, upMid, staffMids) },
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
    onSearchTag: ((String) -> Unit)?,
    onBack: () -> Unit,
    disableDiscovery: Boolean = false,
    nightBanner: (@Composable () -> Unit)? = null,
    onAuthorResolved: (upMid: Long, staffMids: Set<Long>) -> Unit = { _, _ -> },
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
    val subtitlePrefs by vm.subtitlePrefs.collectAsStateWithLifecycle()
    val danmakuPrefs by vm.danmakuPrefs.collectAsStateWithLifecycle()
    val danmakuPool by vm.danmakuPool.collectAsStateWithLifecycle()
    val cached by vm.cached.collectAsStateWithLifecycle()
    val specialDanmakuPool by vm.specialDanmakuPool.collectAsStateWithLifecycle()
    val danmakuVotes by vm.danmakuVotes.collectAsStateWithLifecycle()
    val danmakuSend by vm.danmakuSend.collectAsStateWithLifecycle()
    val staffFollowed by vm.staffFollowed.collectAsStateWithLifecycle()
    val playerPrefs by container.settings.playerPrefs.collectAsStateWithLifecycle(
        initialValue = PlayerPrefs(),
    )
    val panelPrefs by remember(container) { container.settings.sidePanel(SidePanelId.VideoTabs) }
        .collectAsStateWithLifecycle(initialValue = SideSheetPrefs())
    val agentAvailable by remember(container) {
        container.settings.llmConfig.map { it.isConfigured }
    }.collectAsStateWithLifecycle(initialValue = false)

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

    CompositionLocalProvider(LocalPlayerLayoutPrefs provides playerPrefs.layout) {
    VideoScreen(
        bvid = bvid,
        frame = frame,
        state = state,
        videoTags = videoTags,
        onLoadTags = vm::loadVideoTags,
        onTagClick = if (disableDiscovery) null else onSearchTag,
        related = related,
        commentState = commentState,
        sponsorSegments = sponsorSegments,
        onFindRelated = if (disableDiscovery || !agentAvailable) null else vm::findRelated,
        onAskRelated = vm::askRelated,
        onRetryRelated = vm::retryRelated,
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
        subtitlePrefs = subtitlePrefs,
        subtitleEditor = vm.subtitleEditor,
        staffFollowed = staffFollowed,
        onFollowStaff = vm::followStaff,
        danmakuPrefs = danmakuPrefs,
        danmakuEditor = vm.danmakuEditor,
        danmakuPool = danmakuPool,
        specialDanmakuPool = specialDanmakuPool,
        danmakuVotes = danmakuVotes,
        onVoteDanmaku = vm::voteDanmaku,
        selfDanmaku = vm.selfDanmaku,
        danmakuSend = danmakuSend,
        onSendDanmaku = vm::sendDanmaku,
        onDanmakuSendConsumed = vm::clearDanmakuSend,
        gestures = PlayerGestureOptions(
            swipeSeek = playerPrefs.swipeSeek,
            doubleTapSeek = playerPrefs.doubleTapSeek,
            doubleTapSeekSeconds = playerPrefs.layout.effectiveDoubleTapSeekSeconds,
            brightnessAndVolume = playerPrefs.swipeBrightnessVolume,
            fastForward = playerPrefs.longPressSpeed,
            fastForwardDial = playerPrefs.longPressSpeedDial,
            leftVerticalSwipe = playerPrefs.layout.gestures.leftVerticalSwipe,
            rightVerticalSwipe = playerPrefs.layout.gestures.rightVerticalSwipe,
            verticalZoneFraction = playerPrefs.layout.gestures.verticalZoneFraction,
            swipeSeekSensitivity = playerPrefs.layout.gestures.swipeSeekSensitivity,
            swipeSeekCancelZone = playerPrefs.layout.gestures.swipeSeekCancelZone,
            doubleTapZoneFraction = playerPrefs.layout.gestures.doubleTapZoneFraction,
        ),
        defaultHoldSpeed = playerPrefs.defaultHoldSpeed,
        panelPrefs = panelPrefs,
        onPanelWidthChange = { widthDp ->
            container.persistScope.launch {
                container.settings.saveSidePanelWidth(SidePanelId.VideoTabs, widthDp)
            }
        },
        nightBanner = nightBanner,
        onAuthorResolved = onAuthorResolved,
    )
    }
}

private const val NIGHT_ACTIVE_TICK_MILLIS = 1_000L
private const val NIGHT_IDLE_TICK_MILLIS = 15_000L
private const val NIGHT_IDLE_COUNTDOWN_SECONDS = 10
private val NightIdleBannerTopOffset = 64.dp

/**
 * lateral(同级平移)没有在这里定义 spec:它由 `HorizontalPager` 自己承担,而规范要的正是
 * pager 的默认行为 —— "elements are grouped and slide in unison",并且**不加淡入淡出**
 * ("Fading content as it slides makes the peer relationship and swipe gesture less obvious")。
 */
