package dev.bilby.ui.profile

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.data.AccountInfo
import dev.bilby.data.AccountRepository
import dev.bilby.data.FavFolderDetail
import dev.bilby.data.FavRepository
import dev.bilby.data.HistoryItem
import dev.bilby.data.HistoryRepository
import dev.bilby.data.ToViewItem
import dev.bilby.data.ToViewRepository
import dev.bilby.offline.OfflineDownloader
import dev.bilby.offline.OfflineItem
import dev.bilby.offline.OfflineStatus
import dev.bilby.offline.OfflineStore
import dev.bilby.parseDurationSeconds
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.CaptionDragHeight
import dev.bilby.ui.CaptionLeadWithDrag
import dev.bilby.ui.LocalLiveWindowInfo
import dev.bilby.ui.LocalWindowCaption
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.CoinGlyph
import dev.bilby.ui.components.ContextMenuBox
import dev.bilby.ui.components.CoverAspectRatio
import dev.bilby.ui.components.CoverCornerRadius
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.LevelBadge
import dev.bilby.ui.components.ListCover
import dev.bilby.ui.components.LocalCopyNotice
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.components.SkeletonLine
import dev.bilby.ui.components.SkeletonPulse
import dev.bilby.ui.components.VideoCommonMenuItems
import dev.bilby.ui.components.VideoCover
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.components.plainTextClip
import dev.bilby.ui.components.skeleton
import dev.bilby.ui.components.verticalWheelScrollsRow
import dev.bilby.ui.errorTextRes
import dev.bilby.ui.fav.FavFolderCover
import dev.bilby.ui.fav.favFolderMeta
import dev.bilby.ui.history.toRowUi
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.offline.formatBytes
import dev.bilby.ui.offline.toRowUi
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.rememberCaptionSlot
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.LocalIsDarkTheme
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.interactiveCard
import dev.bilby.ui.theme.tabular
import dev.bilby.ui.toview.toRowUi
import dev.bilby.ui.windowDragArea
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/** 历史记录 / 稍后再看 / 收藏夹三个预览区共用的形状:各自独立加载、独立失败。 */
data class ProfilePreviewState<T>(
    val loading: Boolean = true,
    /** 失败说哪一句,存的是资源 id。见 [dev.bilby.ui.errorTextRes]。 */
    val error: StringResource? = null,
    val items: List<T> = emptyList(),
)

data class ProfileUiState(
    val accountLoading: Boolean = true,
    val accountError: StringResource? = null,
    val account: AccountInfo? = null,
    val history: ProfilePreviewState<HistoryItem> = ProfilePreviewState(),
    val toView: ProfilePreviewState<ToViewItem> = ProfilePreviewState(),
    val favFolders: ProfilePreviewState<FavFolderDetail> = ProfilePreviewState(),
    /**
     * 已缓存的视频。**没有 loading 也没有 error** —— 它读的是本地目录,由
     * [dev.bilby.offline.OfflineDownloader] 常驻持有,不存在"正在拉"这个状态。
     */
    val offline: List<OfflineItem> = emptyList(),
    /** 缓存目录占了多少字节。一次目录遍历,只在缓存增删时重算,见 [ProfileViewModel] 的 init。 */
    val offlineUsedBytes: Long = 0L,
)

/**
 * 账号信息、历史记录预览、稍后再看预览、收藏夹列表 —— 四样各自独立加载,一个失败
 * 不连坐其它三个。
 */
class ProfileViewModel(
    private val accountRepository: AccountRepository,
    private val historyRepository: HistoryRepository,
    private val toViewRepository: ToViewRepository,
    private val favRepository: FavRepository,
    offlineDownloader: OfflineDownloader,
    private val offlineStore: OfflineStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            offlineDownloader.items.collect { items -> _state.update { it.copy(offline = items) } }
        }
        viewModelScope.launch {
            offlineDownloader.items
                .map { items -> items.map { it.id to (it.status == OfflineStatus.Completed) }.toSet() }
                .distinctUntilChanged()
                .collect {
                    val bytes = offlineStore.usedBytes()
                    _state.update { it.copy(offlineUsedBytes = bytes) }
                }
        }
    }

    fun refresh() {
        retryAccount()
        retryHistory()
        retryToView()
        retryFavFolders()
    }

    fun retryAccount() {
        _state.update { it.copy(accountLoading = true, accountError = null) }
        viewModelScope.launch {
            when (val result = accountRepository.loadInfo()) {
                is BiliResult.Ok -> _state.update { it.copy(accountLoading = false, account = result.value) }
                else -> _state.update {
                    it.copy(accountLoading = false, accountError = result.errorTextRes("取账号信息"))
                }
            }
        }
    }

    fun retryHistory() {
        _state.update { it.copy(history = it.history.copy(loading = true, error = null)) }
        viewModelScope.launch {
            when (val result = historyRepository.loadPage(0L, 0L)) {
                is BiliResult.Ok -> _state.update {
                    it.copy(
                        history = it.history.copy(
                            loading = false,
                            items = result.value.items.take(ShelfMaxCount),
                        ),
                    )
                }

                else -> _state.update {
                    it.copy(history = it.history.copy(loading = false, error = result.errorTextRes("取历史记录预览")))
                }
            }
        }
    }

    fun retryToView() {
        _state.update { it.copy(toView = it.toView.copy(loading = true, error = null)) }
        viewModelScope.launch {
            when (val result = toViewRepository.loadList()) {
                is BiliResult.Ok -> _state.update {
                    it.copy(toView = it.toView.copy(loading = false, items = result.value.items.take(ShelfMaxCount)))
                }

                else -> _state.update {
                    it.copy(toView = it.toView.copy(loading = false, error = result.errorTextRes("取稍后再看预览")))
                }
            }
        }
    }

    fun retryFavFolders() {
        _state.update { it.copy(favFolders = it.favFolders.copy(loading = true, error = null)) }
        viewModelScope.launch {
            when (val result = favRepository.folderPage(1)) {
                is BiliResult.Ok -> _state.update {
                    it.copy(favFolders = it.favFolders.copy(loading = false, items = result.value.items))
                }

                else -> _state.update {
                    it.copy(favFolders = it.favFolders.copy(loading = false, error = result.errorTextRes("取收藏夹列表")))
                }
            }
        }
    }
}

private const val PreviewCount = 3
private const val ShelfMaxCount = 10

/**
 * 「我的」:大尺寸身份卡([AccountCard]) + 历史记录、稍后再看、收藏夹、离线缓存四节概览。
 * 整页按自然 16:10 比例纵向滚动,不再按窗口高度反向挤压货架卡片。
 */
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onVideoClick: (String) -> Unit,
    /** 稍后再看预览里的一条。队列是稍后再看,与 [onVideoClick] 分开。 */
    onToViewItemClick: (String) -> Unit,
    /** 缓存预览里的一条。一行是一个分 P,所以给整条缓存,不只给 bvid。 */
    onOfflineItemClick: (OfflineItem) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenToView: () -> Unit,
    onOpenOffline: () -> Unit,
    /** 进消息页。入口严格不带任何未读计数或红点。 */
    onOpenMessages: () -> Unit,
    /** 账号卡上的「关注」那一格,进关注列表。 */
    onOpenFollowings: () -> Unit,
    /** 账号卡上的「硬币」那一格,进硬币记录。 */
    onOpenCoinLog: () -> Unit,
    onOpenFavFolder: (FavFolderDetail) -> Unit,
    /** 进收藏夹列表页。新建、改名、删除收藏夹都在那里。 */
    onOpenFavFolders: () -> Unit,
    onSettingsClick: () -> Unit,
    /** 点账号那一块进自己的空间。 */
    onOpenSelf: (Long) -> Unit,
    onRetryAccount: () -> Unit,
    onRetryHistory: () -> Unit,
    onRetryToView: () -> Unit,
    onRetryFavFolders: () -> Unit,
    /** 每变一次就回到顶部。重按底栏上当前这一格时由 MainActivity 递增。 */
    scrollToTop: Int = 0,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val scrollState = rememberScrollState()
    var handledScrollToTop by remember { mutableIntStateOf(scrollToTop) }
    LaunchedEffect(scrollToTop) {
        if (scrollToTop != handledScrollToTop) {
            handledScrollToTop = scrollToTop
            scrollState.animateScrollTo(0)
        }
    }

    val wide = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)
    val caption = LocalWindowCaption.current
    val liveWindow = LocalLiveWindowInfo.current ?: LocalWindowInfo.current
    val density = LocalDensity.current
    val edgeTolerancePx = with(density) { 32.dp.toPx() }
    var rootTouchesTopRight by remember { mutableStateOf(false) }
    var cardAtTop by remember { mutableStateOf(true) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .onGloballyPositioned { coordinates ->
                if (!coordinates.isAttached) return@onGloballyPositioned
                val bounds = coordinates.boundsInWindow()
                rootTouchesTopRight = bounds.top <= edgeTolerancePx &&
                    bounds.right >= liveWindow.containerSize.width - edgeTolerancePx
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            if (wide || caption != null) {
                AccountCard(
                    state = state,
                    onOpenSelf = onOpenSelf,
                    onOpenMessages = onOpenMessages,
                    onOpenFollowings = onOpenFollowings,
                    onOpenCoinLog = onOpenCoinLog,
                    onSettingsClick = onSettingsClick,
                    onRetry = onRetryAccount,
                    onCaptionAtTopChanged = { cardAtTop = it },
                )
            } else {
                AdaptiveContent(maxWidth = Breakpoints.ReadableWidth) {
                    AccountCard(
                        state = state,
                        onOpenSelf = onOpenSelf,
                        onOpenMessages = onOpenMessages,
                        onOpenFollowings = onOpenFollowings,
                        onOpenCoinLog = onOpenCoinLog,
                        onSettingsClick = onSettingsClick,
                        onRetry = onRetryAccount,
                        onCaptionAtTopChanged = { cardAtTop = it },
                    )
                }
            }

            Spacer(Modifier.height(Spacing.Hair))

            HistorySection(state.history, wide, onVideoClick, onOpenHistory, onRetryHistory)
            ToViewSection(state.toView, wide, onToViewItemClick, onOpenToView, onRetryToView)
            FavFoldersSection(state.favFolders, wide, onOpenFavFolder, onOpenFavFolders, onRetryFavFolders)
            OfflineSection(state.offline, state.offlineUsedBytes, wide, onOfflineItemClick, onOpenOffline)

            Spacer(Modifier.height(Spacing.Comfortable))
        }

        // 桌面端下滚离开顶部身份卡后,右上角仍保留轻量拖动条与窗口控制按钮,保证随时可最小化/关闭窗口。
        if (caption != null && rootTouchesTopRight && !cardAtTop && scrollState.value > 0) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.92f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = Spacing.Tight, end = Spacing.Comfortable),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = Spacing.Tight, end = Spacing.Hair),
                ) {
                    Spacer(Modifier.width(24.dp).height(CaptionDragHeight).windowDragArea(true))
                    caption.Buttons()
                }
            }
        }
    }
}

private val InlineActionsThreshold = 880.dp
private val AccountAvatarSize = Dimens.AvatarHero
private val AccountActionIconSize = 24.dp
private val WideAccountActionsWidth = 396.dp
private val AccountNameSkeletonHeight = 22.dp

/**
 * 个人页主视觉身份卡:
 * - 容器色浅色取 `surfaceContainer`、深色取 `surfaceContainerLow`,配 `shapes.extraLarge`(28dp)与 `16dp` 内边距;
 * - 72dp `AvatarHero` + `headlineSmallEmphasized` 昵称 + `LevelBadge` + 一键复制 `UID` 胶囊 + 右键/长按复制菜单;
 * - 关注 / 硬币 / 消息三段式 `surfaceContainerHigh` 操作条(消息入口严格零红点、零未读角标)。
 */
@OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class,
)
@Composable
private fun AccountCard(
    state: ProfileUiState,
    onOpenSelf: (Long) -> Unit,
    onOpenMessages: () -> Unit,
    onOpenFollowings: () -> Unit,
    onOpenCoinLog: () -> Unit,
    onSettingsClick: () -> Unit,
    onRetry: () -> Unit,
    onCaptionAtTopChanged: (Boolean) -> Unit = {},
) {
    val account = state.account
    val colorScheme = MaterialTheme.colorScheme
    val isDark = LocalIsDarkTheme.current
    val cardColor = if (isDark) colorScheme.surfaceContainerLow else colorScheme.surfaceContainer
    val hasWindowCaption = LocalWindowCaption.current != null
    val captionSlot = rememberCaptionSlot(topInset = 32.dp)
    LaunchedEffect(captionSlot.atTop) {
        onCaptionAtTopChanged(captionSlot.atTop)
    }

    val clipboard = LocalClipboard.current
    val copyNotice = LocalCopyNotice.current
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(Res.string.comment_copied)
    val copyNicknameLabel = stringResource(Res.string.action_copy_nickname)
    val copyUidLabel = stringResource(Res.string.action_copy_uid)
    val menuLabel = stringResource(Res.string.menu_item_actions)

    val copyValue: (String, String) -> Unit = { label, text ->
        scope.launch {
            clipboard.setClipEntry(plainTextClip(label, text))
            copyNotice(copiedText)
        }
    }

    BoxWithConstraints(
        modifier = captionSlot.modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    ) {
        val inlineActions = maxWidth >= InlineActionsThreshold

        Surface(
            color = cardColor,
            contentColor = colorScheme.onSurface,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
                modifier = Modifier.padding(Spacing.Comfortable),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                    modifier = Modifier.fillMaxWidth().heightIn(min = AccountAvatarSize),
                ) {
                    CaptionLeadWithDrag(
                        dragEnabled = captionSlot.atTop,
                        dragHeight = AccountAvatarSize,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (account != null) {
                            ContextMenuBox(
                                onClick = { onOpenSelf(account.mid) },
                                menuLabel = menuLabel,
                                modifier = Modifier
                                    .then(if (hasWindowCaption) Modifier else Modifier.fillMaxWidth())
                                    .clip(MaterialTheme.shapes.large),
                                menu = { close ->
                                    DropdownMenuItem(
                                        onClick = {
                                            close()
                                            copyValue(copyNicknameLabel, account.name)
                                        },
                                        text = { Text(copyNicknameLabel) },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                        shape = menuItemShape(0, 2),
                                    )
                                    DropdownMenuItem(
                                        onClick = {
                                            close()
                                            copyValue(copyUidLabel, account.mid.toString())
                                        },
                                        text = { Text(copyUidLabel) },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                        shape = menuItemShape(1, 2),
                                    )
                                },
                            ) { _ ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
                                    modifier = if (hasWindowCaption) {
                                        Modifier.padding(end = Spacing.Tight)
                                    } else {
                                        Modifier.fillMaxWidth()
                                    },
                                ) {
                                    Avatar(url = account.faceUrl, size = AccountAvatarSize)
                                    Column(
                                        modifier = Modifier.weight(1f, fill = !hasWindowCaption),
                                        verticalArrangement = Arrangement.spacedBy(Spacing.Hair + 2.dp),
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                                        ) {
                                            Text(
                                                text = account.name,
                                                style = MaterialTheme.typography.headlineSmallEmphasized,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            LevelBadge(
                                                level = account.level,
                                                senior = account.isSeniorMember,
                                                height = Dimens.LevelBadgeHeight,
                                            )
                                            Icon(
                                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(Dimens.IconInline),
                                            )
                                        }

                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                                            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                                            itemVerticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            AccountUidPill(
                                                mid = account.mid,
                                                onCopy = { copyValue(copyUidLabel, account.mid.toString()) },
                                            )
                                        }

                                        if (account.sign.isNotBlank()) {
                                            Text(
                                                text = account.sign,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Box(modifier = if (hasWindowCaption) Modifier else Modifier.fillMaxWidth()) {
                                if (state.accountError != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = stringResource(state.accountError),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f, fill = false),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
                                    }
                                } else {
                                    SkeletonPulse {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
                                        ) {
                                            Box(Modifier.size(AccountAvatarSize).skeleton(CircleShape))
                                            Column(
                                                modifier = if (hasWindowCaption) {
                                                    Modifier
                                                } else {
                                                    Modifier.weight(1f)
                                                },
                                                verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                                            ) {
                                                SkeletonLine(
                                                    if (hasWindowCaption) Modifier.width(136.dp) else Modifier.fillMaxWidth(0.5f),
                                                    height = AccountNameSkeletonHeight,
                                                )
                                                SkeletonLine(
                                                    if (hasWindowCaption) Modifier.width(96.dp) else Modifier.fillMaxWidth(0.35f),
                                                )
                                                SkeletonLine(
                                                    if (hasWindowCaption) Modifier.width(200.dp) else Modifier.fillMaxWidth(0.8f),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (inlineActions) {
                        AccountActions(
                            account = account,
                            onOpenFollowings = onOpenFollowings,
                            onOpenCoinLog = onOpenCoinLog,
                            onOpenMessages = onOpenMessages,
                            modifier = Modifier.width(WideAccountActionsWidth),
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val settingsLabel = stringResource(Res.string.settings_title)
                        BilbyTooltip(text = settingsLabel, shortcut = "Ctrl+,") {
                            IconButton(onClick = onSettingsClick) {
                                Icon(
                                    Icons.Outlined.Settings,
                                    contentDescription = settingsLabel,
                                    modifier = Modifier.size(AccountActionIconSize),
                                )
                            }
                        }
                        captionSlot.buttons?.invoke()
                    }
                }

                if (!inlineActions) {
                    AccountActions(
                        account = account,
                        onOpenFollowings = onOpenFollowings,
                        onOpenCoinLog = onOpenCoinLog,
                        onOpenMessages = onOpenMessages,
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountUidPill(
    mid: Long,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copyUidLabel = stringResource(Res.string.action_copy_uid)
    Surface(
        onClick = onCopy,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier.semantics { contentDescription = "$copyUidLabel $mid" },
    ) {
        Text(
            text = "UID: $mid",
            style = MaterialTheme.typography.labelSmall.tabular,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = Spacing.Tight, vertical = 2.dp),
        )
    }
}

/**
 * 账号卡底下的三段式操作条:关注、硬币、消息。
 * 消息入口严格不带任何未读计数或红点(DESIGN 1.3)。
 */
@Composable
private fun AccountActions(
    account: AccountInfo?,
    onOpenFollowings: () -> Unit,
    onOpenCoinLog: () -> Unit,
    onOpenMessages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = listOf(
        AccountSegmentSpec(
            icon = { tint, _ ->
                Icon(
                    Icons.Outlined.PeopleAlt,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(SegmentIconSize),
                )
            },
            value = account?.following?.let { formatSegmentValue(it.toDouble()) },
            label = stringResource(Res.string.profile_following),
            onClick = onOpenFollowings,
        ),
        AccountSegmentSpec(
            icon = { tint, cutout ->
                CoinGlyph(
                    tint = tint,
                    filled = false,
                    cutout = cutout,
                    size = SegmentCoinSize,
                )
            },
            value = account?.let { formatSegmentValue(it.coins) },
            label = stringResource(Res.string.profile_coins),
            onClick = onOpenCoinLog,
        ),
        AccountSegmentSpec(
            icon = { tint, _ ->
                Icon(
                    Icons.Outlined.Mail,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(SegmentIconSize),
                )
            },
            value = null,
            label = stringResource(Res.string.message_title),
            onClick = onOpenMessages,
            weight = MessagesSegmentWeight,
        ),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(SegmentGap),
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
    ) {
        segments.forEachIndexed { index, segment ->
            AccountSegment(
                spec = segment,
                shape = segmentShape(index, segments.size),
                modifier = Modifier.weight(segment.weight).fillMaxHeight(),
            )
        }
    }
}

private class AccountSegmentSpec(
    val icon: @Composable (tint: Color, cutout: Color) -> Unit,
    val value: String?,
    val label: String,
    val onClick: () -> Unit,
    val weight: Float = 1f,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccountSegment(spec: AccountSegmentSpec, shape: Shape, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair + 2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .interactiveCard(
                shape = shape,
                baseColor = colorScheme.surfaceContainerHigh,
                hoverColor = colorScheme.surfaceContainerHighest,
            )
            .clickable(role = Role.Button, onClick = spec.onClick)
            .heightIn(min = SegmentMinHeight)
            .padding(horizontal = Spacing.Tight),
    ) {
        spec.icon(colorScheme.onSurfaceVariant, colorScheme.surfaceContainerHigh)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
            spec.value?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.titleSmallEmphasized.tabular,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Text(
                text = spec.label,
                style = MaterialTheme.typography.labelMediumEmphasized,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

private fun segmentShape(index: Int, count: Int): Shape {
    val start = if (index == 0) SegmentOuterCorner else SegmentInnerCorner
    val end = if (index == count - 1) SegmentOuterCorner else SegmentInnerCorner
    return RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
}

private val SegmentOuterCorner = 16.dp
private val SegmentInnerCorner = 4.dp
private val SegmentGap = 2.dp
private val SegmentIconSize = 22.dp
private val SegmentCoinSize = 19.dp
private const val MessagesSegmentWeight = 0.92f
private val SegmentMinHeight = 48.dp

private fun formatSegmentValue(value: Double): String = when {
    value >= 999_950 -> "%.1fM".format(value / 1_000_000)
    value >= 1_000 -> "%.1fK".format(value / 1_000)
    value % 1.0 == 0.0 -> value.toLong().toString()
    else -> "%.1f".format(value)
}

/** 历史记录预览:最近 [PreviewCount] 条,点标题进完整的历史记录页。 */
@Composable
private fun HistorySection(
    state: ProfilePreviewState<HistoryItem>,
    wide: Boolean,
    onVideoClick: (String) -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PreviewSection(
        modifier = modifier,
        title = stringResource(Res.string.history_title),
        state = state,
        emptyText = stringResource(Res.string.history_empty),
        onOpen = onOpen,
        onRetry = onRetry,
        wide = wide,
        shelfCard = { item, cardModifier ->
            ShelfVideoCard(
                item = item.toRowUi(),
                bvid = item.bvid,
                upName = item.upName,
                durationSeconds = item.durationSeconds.toLong(),
                onClick = { onVideoClick(item.bvid) },
                modifier = cardModifier,
            )
        },
    ) { item, index, count ->
        PreviewRow(
            item = item.toRowUi(),
            index = index,
            count = count,
            onClick = { onVideoClick(item.bvid) },
        )
    }
}

/** 稍后再看预览:同上,点标题进完整的稍后再看列表。 */
@Composable
private fun ToViewSection(
    state: ProfilePreviewState<ToViewItem>,
    wide: Boolean,
    onVideoClick: (String) -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PreviewSection(
        modifier = modifier,
        title = stringResource(Res.string.tab_toview),
        state = state,
        emptyText = stringResource(Res.string.toview_empty),
        onOpen = onOpen,
        onRetry = onRetry,
        hideWhenEmpty = true,
        wide = wide,
        shelfCard = { item, cardModifier ->
            ShelfVideoCard(
                item = item.toRowUi(),
                bvid = if (item.playable) item.bvid else "",
                upName = item.upName,
                durationSeconds = item.durationSeconds,
                enabled = item.playable,
                onClick = { if (item.playable) onVideoClick(item.bvid) },
                modifier = cardModifier,
            )
        },
    ) { item, index, count ->
        PreviewRow(
            item = item.toRowUi(),
            index = index,
            count = count,
            enabled = item.playable,
            onClick = { if (item.playable) onVideoClick(item.bvid) },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewRow(
    item: VideoRowUi,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    SegmentedListItem(
        onClick = onClick,
        enabled = enabled,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap),
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = {
            ProvideTextStyle(LocalTextStyle.current.tabular) {
                ListCover(
                    url = item.coverUrl,
                    durationText = item.durationText,
                    progressFraction = item.progressFraction,
                    width = PreviewCoverWidth,
                    cornerRadius = PreviewCoverCorner,
                    typeBadge = item.typeBadge,
                )
            }
        },
        supportingContent = item.meta?.let { meta ->
            {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall.tabular,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) {
        Text(
            item.title,
            style = MaterialTheme.typography.bodyMediumEmphasized,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val PreviewCoverWidth = 96.dp
private val PreviewCoverCorner = 8.dp

/**
 * 缓存预览。一条都没有时整节不画。
 */
@Composable
private fun OfflineSection(
    items: List<OfflineItem>,
    usedBytes: Long,
    wide: Boolean,
    onItemClick: (OfflineItem) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val recent = items.sortedByDescending { it.createdAtMillis }
    val preview = recent.take(PreviewCount)
    val summary = stringResource(Res.string.offline_count, items.size) + MetaSeparator + formatBytes(usedBytes)
    Column(modifier = modifier.padding(bottom = Spacing.Comfortable)) {
        ShelfTitle(stringResource(Res.string.offline_title), meta = summary, onSeeAll = onOpen)
        if (wide) {
            Shelf(recent.take(ShelfMaxCount)) { item, cardModifier ->
                val rowUi = item.toRowUi()
                ShelfVideoCard(
                    item = rowUi,
                    bvid = item.bvid,
                    upName = item.upName,
                    durationSeconds = parseDurationSeconds(rowUi.durationText),
                    onClick = { onItemClick(item) },
                    modifier = cardModifier,
                )
            }
            return@Column
        }
        Column(modifier = Modifier.padding(horizontal = Spacing.Comfortable)) {
            preview.forEachIndexed { index, item ->
                PreviewRow(
                    item = item.toRowUi(),
                    index = index,
                    count = preview.size,
                    onClick = { onItemClick(item) },
                )
            }
        }
    }
}

@Composable
private fun <T> PreviewSection(
    title: String,
    state: ProfilePreviewState<T>,
    emptyText: String,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    hideWhenEmpty: Boolean = false,
    wide: Boolean,
    shelfCard: @Composable (item: T, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
    itemRow: @Composable (item: T, index: Int, count: Int) -> Unit,
) {
    if (hideWhenEmpty && !state.loading && state.error == null && state.items.isEmpty()) return
    Column(modifier = modifier.padding(bottom = Spacing.Comfortable)) {
        ShelfTitle(title, onSeeAll = onOpen.takeIf { state.items.isNotEmpty() })
        SectionBody(
            state,
            onRetry,
            emptyText,
            skeleton = { if (wide) ShelfLoading() else PreviewRowsSkeleton() },
        ) { items ->
            if (wide) {
                Shelf(items, shelfCard)
                return@SectionBody
            }
            val preview = items.take(PreviewCount)
            Column(modifier = Modifier.padding(horizontal = Spacing.Comfortable)) {
                preview.forEachIndexed { index, item -> itemRow(item, index, preview.size) }
            }
        }
    }
}

/**
 * 一节的标题行:升级至 `titleMediumEmphasized`,计数摘要启用等宽数字。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShelfTitle(
    title: String,
    meta: String? = null,
    onSeeAll: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .padding(horizontal = Spacing.Tight)
            .then(
                if (onSeeAll != null) {
                    Modifier
                        .interactiveCard(
                            shape = MaterialTheme.shapes.small,
                            baseColor = Color.Transparent,
                            hoverColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            enableScale = false,
                        )
                        .clickable(role = Role.Button, onClick = onSeeAll)
                } else {
                    Modifier.clip(MaterialTheme.shapes.small)
                },
            )
            .padding(horizontal = Spacing.Tight),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
            meta?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium.tabular,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        if (onSeeAll != null) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(Res.string.profile_see_all),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(Dimens.IconAction),
            )
        }
    }
}

/**
 * 宽屏货架:按 `228.dp` 最小卡片宽度自然排布 16:10 封面卡片,移除高度反向压缩逻辑。
 */
@Composable
private fun <T> Shelf(items: List<T>, card: @Composable (item: T, modifier: Modifier) -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val available = (maxWidth - Spacing.Comfortable * 2).coerceAtLeast(ShelfCardMinWidth)
        val columns = shelfColumns(available)
        val cardWidth = (available - ShelfGap * (columns - 1)) / columns
        val rowState = rememberLazyListState()
        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = Spacing.Comfortable),
            horizontalArrangement = Arrangement.spacedBy(ShelfGap),
            modifier = Modifier.verticalWheelScrollsRow(rowState),
        ) {
            items(items) { card(it, Modifier.width(cardWidth)) }
        }
    }
}

private fun shelfColumns(width: Dp): Int =
    ((width + ShelfGap) / (ShelfCardMinWidth + ShelfGap)).toInt().coerceAtLeast(1)

private val ShelfCardMinWidth = 228.dp
private val ShelfGap = Spacing.Cozy

/**
 * 货架视频卡:16:10 封面 + 两行强调标题 + 等宽状态文案,带悬停色阶抬升与右键/长按复制入队菜单。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ShelfVideoCard(
    item: VideoRowUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bvid: String = "",
    upName: String = "",
    durationSeconds: Long = 0L,
    enabled: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    val cardContent: @Composable (openMenu: (() -> Unit)?) -> Unit = { openMenu ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .interactiveCard(
                    shape = MaterialTheme.shapes.large,
                    baseColor = colorScheme.surfaceContainer,
                    hoverColor = if (enabled) {
                        lerp(colorScheme.surfaceContainer, colorScheme.surfaceContainerHigh, 0.65f)
                    } else {
                        colorScheme.surfaceContainer
                    },
                    enableScale = enabled,
                )
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = openMenu,
                )
                .alpha(if (enabled) 1f else DisabledShelfAlpha)
                .padding(Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair + 2.dp),
        ) {
            ProvideTextStyle(LocalTextStyle.current.tabular) {
                VideoCover(
                    url = item.coverUrl,
                    durationText = item.durationText,
                    progressFraction = item.progressFraction,
                    cornerRadius = Dimens.CoverCornerGrid,
                    typeBadge = item.typeBadge,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMediumEmphasized,
                color = colorScheme.onSurface,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.meta?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.tabular,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (enabled && bvid.isNotEmpty()) {
        ContextMenuBox(
            onClick = null,
            menuLabel = stringResource(Res.string.menu_item_actions),
            modifier = modifier,
            menu = { close ->
                VideoCommonMenuItems(
                    bvid = bvid,
                    title = item.title,
                    close = close,
                    coverUrl = item.coverUrl,
                    upName = upName,
                    durationSeconds = durationSeconds,
                )
            },
        ) { openMenu ->
            cardContent(openMenu)
        }
    } else {
        Box(modifier = modifier) {
            cardContent(null)
        }
    }
}

private const val DisabledShelfAlpha = 0.38f

@Composable
private fun ShelfLoading() {
    FullScreenLoading(Modifier.fillMaxWidth().height(ShelfLoadingHeight))
}

private val ShelfLoadingHeight = 200.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewRowsSkeleton() {
    SkeletonPulse {
        Column(modifier = Modifier.padding(horizontal = Spacing.Comfortable)) {
            repeat(PreviewCount) { index ->
                SegmentedListItem(
                    onClick = {},
                    enabled = false,
                    shapes = ListItemDefaults.segmentedShapes(index = index, count = PreviewCount),
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    modifier = Modifier.padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap),
                    verticalAlignment = Alignment.CenterVertically,
                    leadingContent = {
                        Box(
                            Modifier
                                .width(PreviewCoverWidth)
                                .aspectRatio(CoverAspectRatio)
                                .skeleton(RoundedCornerShape(PreviewCoverCorner)),
                        )
                    },
                    supportingContent = { SkeletonLine(Modifier.fillMaxWidth(0.4f).padding(top = Spacing.Hair)) },
                ) {
                    SkeletonLine(Modifier.fillMaxWidth(0.85f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> SectionBody(
    state: ProfilePreviewState<T>,
    onRetry: () -> Unit,
    emptyText: String,
    skeleton: @Composable () -> Unit,
    content: @Composable (List<T>) -> Unit,
) {
    val phase: SectionPhase = when {
        state.loading && state.items.isEmpty() -> SectionPhase.Loading
        state.error != null -> SectionPhase.Failed(state.error)
        state.items.isEmpty() -> SectionPhase.Empty
        else -> SectionPhase.Content
    }
    Crossfade(
        targetState = phase,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "profile-section",
    ) { current ->
        when (current) {
            SectionPhase.Loading -> skeleton()
            is SectionPhase.Failed -> InlineSectionError(stringResource(current.message), onRetry)
            SectionPhase.Empty -> InlineSectionMessage(emptyText)
            SectionPhase.Content -> content(state.items)
        }
    }
}

private sealed interface SectionPhase {
    data object Loading : SectionPhase
    data class Failed(val message: StringResource) : SectionPhase
    data object Empty : SectionPhase
    data object Content : SectionPhase
}

/**
 * 收藏夹概览:宽屏走等宽自然货架卡片,窄屏多个收藏夹走横滑卡片、仅单个时走分段横排卡片。
 */
@Composable
private fun FavFoldersSection(
    state: ProfilePreviewState<FavFolderDetail>,
    wide: Boolean,
    onOpenFolder: (FavFolderDetail) -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(bottom = Spacing.Comfortable)) {
        ShelfTitle(stringResource(Res.string.fav_folders_title), onSeeAll = onOpen)
        SectionBody(
            state,
            onRetry,
            stringResource(Res.string.profile_favorites_empty),
            skeleton = { if (wide) ShelfLoading() else FolderCardsSkeleton() },
        ) { folders ->
            if (wide) {
                Shelf(folders.take(ShelfMaxCount)) { folder, cardModifier ->
                    ProfileFavFolderCard(
                        folder = folder,
                        onClick = { onOpenFolder(folder) },
                        modifier = cardModifier,
                    )
                }
                return@SectionBody
            }
            val only = folders.singleOrNull()
            if (only != null) {
                Box(modifier = Modifier.padding(horizontal = Spacing.Comfortable)) {
                    SingleFolderRow(only, onClick = { onOpenFolder(only) })
                }
                return@SectionBody
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.Comfortable),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            ) {
                items(folders, key = { it.id }) { folder ->
                    ProfileFavFolderCard(
                        folder = folder,
                        onClick = { onOpenFolder(folder) },
                        modifier = Modifier.width(CompactFolderCardWidth),
                    )
                }
            }
        }
    }
}

private val CompactFolderCardWidth = Dimens.ListCoverWidth + Spacing.Tight * 3

/**
 * 个人页收藏夹卡片:带悬停容器反馈、强调标题、等宽视频条数与右键/长按复制标题动作。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ProfileFavFolderCard(
    folder: FavFolderDetail,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val copyNotice = LocalCopyNotice.current
    val scope = rememberCoroutineScope()
    val copyTitleLabel = stringResource(Res.string.action_copy_title)
    val copiedText = stringResource(Res.string.comment_copied)

    ContextMenuBox(
        onClick = null,
        menuLabel = stringResource(Res.string.menu_item_actions),
        modifier = modifier,
        menu = { close ->
            DropdownMenuItem(
                onClick = {
                    close()
                    scope.launch {
                        clipboard.setClipEntry(plainTextClip(copyTitleLabel, folder.title))
                        copyNotice(copiedText)
                    }
                },
                text = { Text(copyTitleLabel) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                shape = menuItemShape(0, 1),
            )
        },
    ) { openMenu ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .interactiveCard(
                    shape = MaterialTheme.shapes.large,
                    baseColor = colorScheme.surfaceContainer,
                    hoverColor = lerp(colorScheme.surfaceContainer, colorScheme.surfaceContainerHigh, 0.65f),
                )
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = openMenu,
                )
                .padding(Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair + 2.dp),
        ) {
            FavFolderCover(
                url = folder.coverUrl,
                modifier = Modifier.fillMaxWidth(),
                private = !folder.isPublic,
            )
            Text(
                text = folder.title,
                style = MaterialTheme.typography.bodyMediumEmphasized,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(Res.string.fav_folder_count, folder.count),
                style = MaterialTheme.typography.labelSmall.tabular,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SingleFolderRow(folder: FavFolderDetail, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = {
            FavFolderCover(
                url = folder.coverUrl,
                private = !folder.isPublic,
                modifier = Modifier.width(PreviewCoverWidth),
            )
        },
        supportingContent = {
            Text(
                favFolderMeta(folder),
                style = MaterialTheme.typography.labelSmall.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        },
    ) {
        Text(
            folder.title,
            style = MaterialTheme.typography.bodyMediumEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FolderCardsSkeleton() {
    SkeletonPulse {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .padding(horizontal = Spacing.Comfortable),
        ) {
            repeat(FolderSkeletonCount) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    modifier = Modifier
                        .width(CompactFolderCardWidth)
                        .clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(Spacing.Tight),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(CoverAspectRatio)
                            .skeleton(RoundedCornerShape(CoverCornerRadius)),
                    )
                    SkeletonLine(Modifier.fillMaxWidth(0.7f))
                    SkeletonLine(Modifier.fillMaxWidth(0.4f))
                }
            }
        }
    }
}

private const val FolderSkeletonCount = 3

@Composable
private fun InlineSectionError(message: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
    }
}

@Composable
private fun InlineSectionMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    )
}

// ---- Preview ----

private fun previewHistoryItem(oid: Long, title: String) = HistoryItem(
    oid = oid,
    kid = oid + 1,
    bvid = "BV1aa$oid",
    title = title,
    coverUrl = "https://i0.hdslb.com/bfs/archive/preview.jpg",
    durationText = "12:34",
    durationSeconds = 754,
    upName = "某知名UP主",
    viewAtEpochSeconds = 0L,
    progressSeconds = 300,
)

@Preview(showBackground = true, name = "已登录")
@Composable
private fun ProfileScreenPreview() {
    BilbyTheme {
        ProfileScreen(
            state = ProfileUiState(
                accountLoading = false,
                account = AccountInfo(
                    mid = 1L,
                    name = "某知名用户",
                    faceUrl = "",
                    level = 5,
                    isSeniorMember = false,
                    sign = "一句话签名,可能很长也可能没有",
                    coins = 42.5,
                    following = 128,
                ),
                history = ProfilePreviewState(
                    loading = false,
                    items = listOf(previewHistoryItem(1, "看到一半的视频"), previewHistoryItem(2, "已经看完的视频")),
                ),
                toView = ProfilePreviewState(loading = false, items = emptyList()),
                favFolders = ProfilePreviewState(
                    loading = false,
                    items = listOf(
                        FavFolderDetail(id = 1, title = "默认收藏夹", intro = "", count = 42, attr = 0),
                        FavFolderDetail(id = 2, title = "私密的收藏夹", intro = "", count = 7, attr = 3),
                    ),
                ),
            ),
            onVideoClick = {},
            onToViewItemClick = {},
            onOfflineItemClick = {},
            onOpenHistory = {},
            onOpenToView = {},
            onOpenOffline = {},
            onOpenMessages = {},
            onOpenFavFolder = {},
            onOpenFavFolders = {},
            onOpenFollowings = {},
            onOpenCoinLog = {},
            onSettingsClick = {},
            onOpenSelf = {},
            onRetryAccount = {},
            onRetryHistory = {},
            onRetryToView = {},
            onRetryFavFolders = {},
        )
    }
}

@Preview(showBackground = true, name = "账号信息读取失败")
@Composable
private fun ProfileScreenErrorPreview() {
    BilbyTheme {
        ProfileScreen(
            state = ProfileUiState(accountLoading = false, accountError = Res.string.error_network),
            onVideoClick = {},
            onToViewItemClick = {},
            onOfflineItemClick = {},
            onOpenHistory = {},
            onOpenToView = {},
            onOpenOffline = {},
            onOpenMessages = {},
            onOpenFavFolder = {},
            onOpenFavFolders = {},
            onOpenFollowings = {},
            onOpenCoinLog = {},
            onSettingsClick = {},
            onOpenSelf = {},
            onRetryAccount = {},
            onRetryHistory = {},
            onRetryToView = {},
            onRetryFavFolders = {},
        )
    }
}
