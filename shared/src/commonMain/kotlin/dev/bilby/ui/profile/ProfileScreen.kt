package dev.bilby.ui.profile

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
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
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.CaptionDragHeight
import dev.bilby.ui.LocalWindowCaption
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.CoinGlyph
import dev.bilby.ui.components.ContextMenuBox
import dev.bilby.ui.components.LevelBadge
import dev.bilby.ui.components.LocalCopyNotice
import dev.bilby.ui.components.MediaBadge
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
import dev.bilby.ui.fav.favFolderMeta
import dev.bilby.ui.history.toRowUi
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.offline.formatBytes
import dev.bilby.ui.offline.toRowUi
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.rememberCaptionSlot
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.interactiveCard
import dev.bilby.ui.theme.rememberReducedMotion
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
@Immutable
data class ProfilePreviewState<T>(
    val loading: Boolean = true,
    /** 失败说哪一句,存的是资源 id。见 [dev.bilby.ui.errorTextRes]。 */
    val error: StringResource? = null,
    val items: List<T> = emptyList(),
)

@Immutable
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
    val pinnedHeader = LocalWindowCaption.current != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        if (pinnedHeader) {
            ProfileHeader()
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
        ) {
            if (!pinnedHeader) {
                ProfileHeader()
            }
            AccountCard(
                state = state,
                wide = wide,
                onOpenSelf = onOpenSelf,
                onOpenMessages = onOpenMessages,
                onOpenFollowings = onOpenFollowings,
                onOpenCoinLog = onOpenCoinLog,
                onSettingsClick = onSettingsClick,
                onRetry = onRetryAccount,
            )

            Spacer(Modifier.height(Spacing.Hair))

            HistorySection(state.history, wide, onVideoClick, onOpenHistory, onRetryHistory)
            ToViewSection(state.toView, wide, onToViewItemClick, onOpenToView, onRetryToView)
            FavFoldersSection(state.favFolders, wide, onOpenFavFolder, onOpenFavFolders, onRetryFavFolders)
            OfflineSection(state.offline, state.offlineUsedBytes, wide, onOfflineItemClick, onOpenOffline)

            Spacer(Modifier.height(Spacing.Comfortable))
        }
    }
}

/**
 * 个人页顶栏:与订阅页 [FeedHeader] 保持一致的轻量结构——左侧页面标题加桌面拖动区，
 * 右侧仅保留窗口控制按钮，设置入口已下沉至账户快捷动作胶囊中。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProfileHeader() {
    val captionSlot = rememberCaptionSlot()
    Row(
        modifier = captionSlot.modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .padding(
                start = Spacing.Comfortable,
                end = if (captionSlot.buttons != null) Spacing.Hair else Spacing.Comfortable,
                top = Spacing.Tight,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .height(CaptionDragHeight)
                .windowDragArea(captionSlot.atTop),
        ) {
            Text(
                text = stringResource(Res.string.tab_profile),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        captionSlot.buttons?.invoke()
    }
}

private val AccountAvatarSize = 56.dp
private val AccountNameSkeletonHeight = 20.dp

/**
 * 个人页轻量身份头与快捷动作胶囊：
 * - 去除整宽深灰外框卡片与嵌套底板，直接落在页面背景之上以对齐订阅页视觉重量；
 * - 宽屏下身份信息居左、[关注 · 硬币 · 消息 · 设置] 四段动作胶囊靠右单行并列；
 *   窄屏下四段胶囊落在身份行正下方。
 */
@OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class,
)
@Composable
private fun AccountCard(
    state: ProfileUiState,
    wide: Boolean,
    onOpenSelf: (Long) -> Unit,
    onOpenMessages: () -> Unit,
    onOpenFollowings: () -> Unit,
    onOpenCoinLog: () -> Unit,
    onSettingsClick: () -> Unit,
    onRetry: () -> Unit,
) {
    val account = state.account
    val colorScheme = MaterialTheme.colorScheme

    val clipboard = LocalClipboard.current
    val copyNotice = LocalCopyNotice.current
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(Res.string.comment_copied)
    val copyNicknameLabel = stringResource(Res.string.action_copy_nickname)
    val copyUidLabel = stringResource(Res.string.action_copy_uid)
    val copyBioLabel = stringResource(Res.string.action_copy_bio)
    val menuLabel = stringResource(Res.string.menu_item_actions)

    val copyValue: (String, String) -> Unit = { label, text ->
        scope.launch {
            clipboard.setClipEntry(plainTextClip(label, text))
            copyNotice(copiedText)
        }
    }

    val identityBlock: @Composable (Modifier) -> Unit = { blockModifier ->
        Box(modifier = blockModifier) {
            if (account != null) {
                val hasBio = account.sign.isNotBlank()
                val menuTotal = if (hasBio) 3 else 2
                ContextMenuBox(
                    onClick = { onOpenSelf(account.mid) },
                    menuLabel = menuLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large),
                    menu = { close ->
                        DropdownMenuItem(
                            onClick = {
                                close()
                                copyValue(copyNicknameLabel, account.name)
                            },
                            text = { Text(copyNicknameLabel) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            shape = menuItemShape(0, menuTotal),
                        )
                        DropdownMenuItem(
                            onClick = {
                                close()
                                copyValue(copyUidLabel, account.mid.toString())
                            },
                            text = { Text(copyUidLabel) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            shape = menuItemShape(1, menuTotal),
                        )
                        if (hasBio) {
                            DropdownMenuItem(
                                onClick = {
                                    close()
                                    copyValue(copyBioLabel, account.sign)
                                },
                                text = { Text(copyBioLabel) },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                shape = menuItemShape(2, menuTotal),
                            )
                        }
                    },
                ) { _ ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = AccountAvatarSize)
                            .padding(horizontal = Spacing.Tight, vertical = Spacing.Tight),
                    ) {
                        Avatar(url = account.faceUrl, size = AccountAvatarSize)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                        ) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                                itemVerticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                                ) {
                                    Text(
                                        text = account.name,
                                        style = MaterialTheme.typography.titleLargeEmphasized,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    LevelBadge(
                                        level = account.level,
                                        senior = account.isSeniorMember,
                                        height = Dimens.LevelBadgeHeight,
                                    )
                                }
                                AccountUidPill(
                                    mid = account.mid,
                                    onCopy = { copyValue(copyUidLabel, account.mid.toString()) },
                                )
                            }

                            if (hasBio) {
                                Text(
                                    text = account.sign,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant,
                                    maxLines = if (wide) 1 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(Dimens.IconInline),
                        )
                    }
                }
            } else {
                Box(
                    contentAlignment = Alignment.CenterStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = AccountAvatarSize)
                        .padding(horizontal = Spacing.Tight, vertical = Spacing.Tight),
                ) {
                    if (state.accountError != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(state.accountError),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
                        }
                    } else {
                        SkeletonPulse {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Box(Modifier.size(AccountAvatarSize).skeleton(CircleShape))
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                                ) {
                                    SkeletonLine(
                                        Modifier.widthIn(max = 180.dp).fillMaxWidth(0.45f),
                                        height = AccountNameSkeletonHeight,
                                    )
                                    SkeletonLine(
                                        Modifier.widthIn(max = 260.dp).fillMaxWidth(0.65f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (wide) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
        ) {
            identityBlock(Modifier.weight(1f))
            AccountActions(
                account = account,
                compact = false,
                fillWidth = false,
                onOpenFollowings = onOpenFollowings,
                onOpenCoinLog = onOpenCoinLog,
                onOpenMessages = onOpenMessages,
                onSettingsClick = onSettingsClick,
                modifier = Modifier.padding(end = Spacing.Tight),
            )
        }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Cozy, vertical = Spacing.Hair),
        ) {
            identityBlock(Modifier.fillMaxWidth())
            val density = LocalDensity.current
            var actionsWidth by remember { mutableStateOf(0.dp) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Tight)
                    .onSizeChanged { with(density) { actionsWidth = it.width.toDp() } },
            ) {
                AccountActions(
                    account = account,
                    compact = actionsWidth in 1.dp..<AccountActionsStackedBreakpoint,
                    fillWidth = true,
                    onOpenFollowings = onOpenFollowings,
                    onOpenCoinLog = onOpenCoinLog,
                    onOpenMessages = onOpenMessages,
                    onSettingsClick = onSettingsClick,
                    modifier = Modifier.fillMaxWidth(),
                )
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
    val shape = MaterialTheme.shapes.extraSmall
    // 不走带 onClick 的 Surface 重载：后者会自动注入 48dp 最小触控框，在窄屏换行后把上下撑出大段空白；
    // 行级已由外层 ContextMenuBox 提供点按与复制菜单，此处保持紧凑行高并通过 Role.Button 暴露语义。
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = shape,
        modifier = modifier
            .clip(shape)
            .clickable(role = Role.Button, onClick = onCopy)
            .semantics { contentDescription = "$copyUidLabel $mid" },
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
 * 账户快捷动作四段胶囊：关注、硬币、消息、设置。
 * 视觉密度对齐订阅页顶栏胶囊（`surfaceContainer` / `surfaceContainerHigh`），消息入口保持零红点、零未读角标。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccountActions(
    account: AccountInfo?,
    compact: Boolean,
    fillWidth: Boolean,
    onOpenFollowings: () -> Unit,
    onOpenCoinLog: () -> Unit,
    onOpenMessages: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val followingLabel = stringResource(Res.string.profile_following)
    val coinsLabel = stringResource(Res.string.profile_coins)
    val messagesLabel = stringResource(Res.string.message_title)
    val settingsLabel = stringResource(Res.string.settings_title)
    val segments = remember(
        account,
        followingLabel,
        coinsLabel,
        messagesLabel,
        settingsLabel,
        onOpenFollowings,
        onOpenCoinLog,
        onOpenMessages,
        onSettingsClick,
    ) {
        listOf(
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
                label = followingLabel,
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
                label = coinsLabel,
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
                label = messagesLabel,
                onClick = onOpenMessages,
            ),
            AccountSegmentSpec(
                icon = { tint, _ ->
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(SegmentIconSize),
                    )
                },
                value = null,
                label = settingsLabel,
                tooltip = settingsLabel,
                tooltipShortcut = "Ctrl+,",
                onClick = onSettingsClick,
            ),
        )
    }
    val interactionSources = remember { List(4) { MutableInteractionSource() } }
    ButtonGroup(
        overflowIndicator = { menuState ->
            IconButton(onClick = { menuState.show() }) {
                Icon(
                    Icons.Outlined.MoreHoriz,
                    contentDescription = stringResource(Res.string.menu_item_actions),
                )
            }
        },
        horizontalArrangement = Arrangement.spacedBy(SegmentGap),
        modifier = modifier.height(IntrinsicSize.Min),
    ) {
        segments.forEachIndexed { index, segment ->
            val interactionSource = interactionSources[index]
            val segModifier = (if (fillWidth) Modifier.weight(1f) else Modifier)
                .animateWidth(interactionSource)
                .fillMaxHeight()
            customItem(
                buttonGroupContent = {
                    val itemContent: @Composable () -> Unit = {
                        AccountSegment(
                            spec = segment,
                            compact = compact,
                            shape = segmentShape(index, segments.size),
                            interactionSource = interactionSource,
                            modifier = if (fillWidth) Modifier.fillMaxSize() else segModifier,
                        )
                    }
                    if (segment.tooltip != null) {
                        Box(modifier = segModifier) {
                            BilbyTooltip(text = segment.tooltip, shortcut = segment.tooltipShortcut) {
                                itemContent()
                            }
                        }
                    } else {
                        Box(modifier = segModifier) {
                            itemContent()
                        }
                    }
                },
                menuContent = { menuState ->
                    DropdownMenuItem(
                        onClick = {
                            menuState.dismiss()
                            segment.onClick()
                        },
                        text = {
                            Text(
                                text = if (segment.value != null) {
                                    "${segment.label}$MetaSeparator${segment.value}"
                                } else {
                                    segment.label
                                },
                                style = MaterialTheme.typography.bodyMedium.tabular,
                            )
                        },
                        shape = menuItemShape(index, segments.size),
                    )
                },
            )
        }
    }
}

@Immutable
private class AccountSegmentSpec(
    val icon: @Composable (tint: Color, cutout: Color) -> Unit,
    val value: String?,
    val label: String,
    val tooltip: String? = null,
    val tooltipShortcut: String? = null,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccountSegment(
    spec: AccountSegmentSpec,
    compact: Boolean,
    shape: Shape,
    interactionSource: MutableInteractionSource,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val cardModifier = modifier
        .interactiveCard(
            shape = shape,
            baseColor = colorScheme.surfaceContainer,
            hoverColor = colorScheme.surfaceContainerHigh,
        )
        .clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            role = Role.Button,
            onClick = spec.onClick,
        )
        .heightIn(min = SegmentMinHeight)
    if (compact) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            modifier = cardModifier.padding(horizontal = Spacing.Hair, vertical = Spacing.Tight),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                spec.icon(colorScheme.onSurfaceVariant, colorScheme.surfaceContainer)
                spec.value?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLargeEmphasized.tabular,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
            Text(
                text = spec.label,
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = cardModifier.padding(horizontal = Spacing.Cozy),
        ) {
            spec.icon(colorScheme.onSurfaceVariant, colorScheme.surfaceContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
                spec.value?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLargeEmphasized.tabular,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                Text(
                    text = spec.label,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
    }
}

private fun segmentShape(index: Int, count: Int): Shape {
    val start = if (index == 0) SegmentOuterCorner else SegmentInnerCorner
    val end = if (index == count - 1) SegmentOuterCorner else SegmentInnerCorner
    return RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
}

private val AccountActionsStackedBreakpoint = 480.dp
private val SegmentOuterCorner = 16.dp
private val SegmentInnerCorner = 4.dp
private val SegmentGap = 2.dp
private val SegmentIconSize = 18.dp
private val SegmentCoinSize = 16.dp
private val SegmentMinHeight = 48.dp

private fun formatSegmentValue(value: Double): String = when {
    value >= 999_950 -> "%.1fM".format(value / 1_000_000)
    value >= 1_000 -> "%.1fK".format(value / 1_000)
    value % 1.0 == 0.0 -> value.toLong().toString()
    else -> "%.1f".format(value)
}

/** 历史记录预览：最近 [ShelfMaxCount] 条，M3E 多浏览旋转木马，点标题进完整的历史记录页。 */
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
    ) { item ->
        CarouselVideoCard(
            item = item.toRowUi(),
            bvid = item.bvid,
            upName = item.upName,
            durationSeconds = item.durationSeconds.toLong(),
            onClick = { onVideoClick(item.bvid) },
        )
    }
}

/** 稍后再看预览：同上，点标题进完整的稍后再看列表。 */
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
    ) { item ->
        CarouselVideoCard(
            item = item.toRowUi(),
            bvid = if (item.playable) item.bvid else "",
            upName = item.upName,
            durationSeconds = item.durationSeconds,
            enabled = item.playable,
            onClick = { if (item.playable) onVideoClick(item.bvid) },
        )
    }
}

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
    val recent = remember(items) {
        items.sortedByDescending { it.createdAtMillis }.take(ShelfMaxCount)
    }
    val summary = stringResource(Res.string.offline_count, items.size) + MetaSeparator + formatBytes(usedBytes)
    Column(modifier = modifier.padding(bottom = Spacing.Comfortable)) {
        ShelfTitle(stringResource(Res.string.offline_title), meta = summary, onSeeAll = onOpen)
        ProfileCarousel(items = recent, wide = wide) { item ->
            val rowUi = item.toRowUi()
            CarouselVideoCard(
                item = rowUi,
                bvid = item.bvid,
                upName = item.upName,
                durationSeconds = parseDurationSeconds(rowUi.durationText),
                onClick = { onItemClick(item) },
            )
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
    modifier: Modifier = Modifier,
    carouselCard: @Composable CarouselItemScope.(item: T) -> Unit,
) {
    if (hideWhenEmpty && !state.loading && state.error == null && state.items.isEmpty()) return
    Column(modifier = modifier.padding(bottom = Spacing.Comfortable)) {
        ShelfTitle(title, onSeeAll = onOpen.takeIf { state.items.isNotEmpty() })
        SectionBody(
            state = state,
            onRetry = onRetry,
            emptyText = emptyText,
            skeleton = { CarouselSkeleton(wide = wide) },
        ) { items ->
            ProfileCarousel(items = items, wide = wide, itemContent = carouselCard)
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

private val CarouselPreferredWidthCompact = 256.dp
private val CarouselPreferredWidthWide = 284.dp
private val CarouselHeightCompact = 160.dp
private val CarouselHeightWide = 178.dp

/**
 * M3 Expressive 货架旋转木马：常规态走渐进遮罩 `HorizontalMultiBrowseCarousel`，
 * 减弱动态模式下回退等宽 `HorizontalUncontainedCarousel`；滚轮纵向滚动无缝映射横向浏览。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ProfileCarousel(
    items: List<T>,
    wide: Boolean,
    modifier: Modifier = Modifier,
    itemContent: @Composable CarouselItemScope.(item: T) -> Unit,
) {
    val carouselState = rememberCarouselState { items.size }
    val reducedMotion = rememberReducedMotion()
    val preferredWidth = if (wide) CarouselPreferredWidthWide else CarouselPreferredWidthCompact
    val cardHeight = if (wide) CarouselHeightWide else CarouselHeightCompact
    val carouselModifier = modifier
        .fillMaxWidth()
        .height(cardHeight)
        .verticalWheelScrollsRow(carouselState)
    val padding = PaddingValues(horizontal = Spacing.Comfortable)
    if (reducedMotion) {
        HorizontalUncontainedCarousel(
            state = carouselState,
            itemWidth = preferredWidth,
            modifier = carouselModifier,
            itemSpacing = Spacing.Tight,
            contentPadding = padding,
        ) { index ->
            items.getOrNull(index)?.let { itemContent(it) }
        }
    } else {
        HorizontalMultiBrowseCarousel(
            state = carouselState,
            preferredItemWidth = preferredWidth,
            modifier = carouselModifier,
            itemSpacing = Spacing.Tight,
            contentPadding = padding,
        ) { index ->
            items.getOrNull(index)?.let { itemContent(it) }
        }
    }
}

/**
 * 旋转木马视频卡：16:10 满幅封面 + 28dp `extraLarge` 遮罩裁剪，随尾端缩窄平滑淡出底部文字，
 * 保证窄预览条只留纯净封面与角标；全幅与宽窄屏均挂载右键/长按操作菜单。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun CarouselItemScope.CarouselVideoCard(
    item: VideoRowUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bvid: String = "",
    upName: String = "",
    durationSeconds: Long = 0L,
    enabled: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    val cardShape = MaterialTheme.shapes.extraLarge
    val cardContent: @Composable (openMenu: (() -> Unit)?) -> Unit = { openMenu ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .maskClip(cardShape)
                .background(colorScheme.surfaceContainerHigh)
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = openMenu,
                )
                .alpha(if (enabled) 1f else DisabledShelfAlpha),
        ) {
            ProvideTextStyle(LocalTextStyle.current.tabular) {
                VideoCover(
                    url = item.coverUrl,
                    durationText = "",
                    progressFraction = item.progressFraction,
                    cornerRadius = 0.dp,
                    typeBadge = "",
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (item.typeBadge.isNotEmpty()) {
                MediaBadge(
                    text = item.typeBadge,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.Cozy),
                )
            }
            if (item.durationText.isNotEmpty()) {
                MediaBadge(
                    text = item.durationText,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.Cozy),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val ratio = carouselItemDrawInfo.size / carouselItemDrawInfo.maxSize.coerceAtLeast(1f)
                        alpha = ((ratio - 0.52f) / 0.48f).coerceIn(0f, 1f)
                    }
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, FixedColors.ScrimOnMedia),
                        ),
                    )
                    .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = FixedColors.OnMedia,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.meta?.let { meta ->
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall.tabular,
                            color = FixedColors.OnMedia.copy(alpha = 0.86f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer {
                                val ratio = carouselItemDrawInfo.size / carouselItemDrawInfo.maxSize.coerceAtLeast(1f)
                                alpha = ((ratio - 0.68f) / 0.32f).coerceIn(0f, 1f)
                            },
                        )
                    }
                }
            }
        }
    }

    if (enabled && bvid.isNotEmpty()) {
        ContextMenuBox(
            onClick = null,
            menuLabel = stringResource(Res.string.menu_item_actions),
            modifier = modifier.fillMaxSize(),
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
        Box(modifier = modifier.fillMaxSize()) {
            cardContent(null)
        }
    }
}

private const val DisabledShelfAlpha = 0.38f

@Composable
private fun CarouselSkeleton(wide: Boolean) {
    val width = if (wide) CarouselPreferredWidthWide else CarouselPreferredWidthCompact
    val height = if (wide) CarouselHeightWide else CarouselHeightCompact
    SkeletonPulse {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .padding(horizontal = Spacing.Comfortable),
        ) {
            repeat(CarouselSkeletonCount) { index ->
                val itemWidth = if (index == CarouselSkeletonCount - 1) width * 0.35f else width
                Box(
                    modifier = Modifier
                        .width(itemWidth)
                        .height(height)
                        .skeleton(MaterialTheme.shapes.extraLarge),
                )
            }
        }
    }
}

private const val CarouselSkeletonCount = 3

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
 * 收藏夹概览：采用 M3E 多浏览旋转木马统一承载宽窄屏，保留收藏夹封面、隐私锁与条数角标。
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
            state = state,
            onRetry = onRetry,
            emptyText = stringResource(Res.string.profile_favorites_empty),
            skeleton = { CarouselSkeleton(wide = wide) },
        ) { folders ->
            ProfileCarousel(items = folders, wide = wide) { folder ->
                CarouselFavFolderCard(
                    folder = folder,
                    onClick = { onOpenFolder(folder) },
                )
            }
        }
    }
}

/**
 * 旋转木马收藏夹卡：`extraLarge` 28dp 遮罩裁剪、右键/长按复制标题与随宽度渐显元信息。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun CarouselItemScope.CarouselFavFolderCard(
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
    val cardShape = MaterialTheme.shapes.extraLarge

    ContextMenuBox(
        onClick = null,
        menuLabel = stringResource(Res.string.menu_item_actions),
        modifier = modifier.fillMaxSize(),
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .maskClip(cardShape)
                .background(colorScheme.surfaceContainerHigh)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = openMenu,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (folder.coverUrl.isNotEmpty()) {
                BiliAsyncImage(
                    url = folder.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
            }
            if (!folder.isPublic) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.Cozy)
                        .background(FixedColors.ScrimOnMedia, CircleShape)
                        .padding(horizontal = Spacing.Tight, vertical = 3.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = FixedColors.OnMedia,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val ratio = carouselItemDrawInfo.size / carouselItemDrawInfo.maxSize.coerceAtLeast(1f)
                        alpha = ((ratio - 0.52f) / 0.48f).coerceIn(0f, 1f)
                    }
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, FixedColors.ScrimOnMedia),
                        ),
                    )
                    .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = folder.title,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = FixedColors.OnMedia,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = favFolderMeta(folder),
                        style = MaterialTheme.typography.labelSmall.tabular,
                        color = FixedColors.OnMedia.copy(alpha = 0.86f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer {
                            val ratio = carouselItemDrawInfo.size / carouselItemDrawInfo.maxSize.coerceAtLeast(1f)
                            alpha = ((ratio - 0.68f) / 0.32f).coerceIn(0f, 1f)
                        },
                    )
                }
            }
        }
    }
}

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
