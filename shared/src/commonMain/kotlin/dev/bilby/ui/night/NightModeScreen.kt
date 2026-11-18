package dev.bilby.ui.night

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.data.NightModePrefs
import dev.bilby.data.WhitelistedUp
import dev.bilby.data.formatMinuteOfDay
import dev.bilby.data.formatNightRange
import dev.bilby.parseDurationSeconds
import dev.bilby.resources.Res
import dev.bilby.resources.menu_item_actions
import dev.bilby.resources.night_mode_all_ups
import dev.bilby.resources.night_mode_empty_ups
import dev.bilby.resources.night_mode_empty_videos
import dev.bilby.resources.night_mode_go_settings
import dev.bilby.resources.night_mode_hold_body
import dev.bilby.resources.night_mode_hold_title
import dev.bilby.resources.night_mode_open_space
import dev.bilby.resources.night_mode_sanctuary_manual
import dev.bilby.resources.night_mode_sanctuary_scheduled
import dev.bilby.resources.night_mode_temp_exit
import dev.bilby.resources.night_mode_until_subtitle
import dev.bilby.resources.offline_title
import dev.bilby.resources.settings_section_night_mode
import dev.bilby.resources.settings_title
import dev.bilby.resources.video_action_toview_desc
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveVideoGridContent
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.ContextMenuBox
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.PagedLayout
import dev.bilby.ui.components.RefreshAction
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.VideoCommonMenuItems
import dev.bilby.ui.components.VideoGridCard
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.components.chargingBadgeText
import dev.bilby.ui.components.fadingRightEdge
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.components.verticalWheelScrollsRow
import dev.bilby.ui.components.videoCommonMenuItemCount
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.readableWidth
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.theme.BilbyNightSanctuaryTheme
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular

/**
 * 夜间模式非模态提醒横幅：显示切入倒计时或"播完本条后进入"，并提供一键立即进入。
 */
@Composable
fun NightReminderBanner(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Icon(
                imageVector = Icons.Outlined.Bedtime,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconInline),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium.tabular,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * 夜间静息主视图：始终包裹于 [BilbyNightSanctuaryTheme] 低唤醒深色静息色板，
 * 无底栏、无侧边导航、无动态/直播/搜索，仅展示白名单 UP 主的点播投稿与离线缓存入口。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NightModeScreen(
    prefs: NightModePrefs,
    state: NightModeUiState,
    onSelectUp: (Long?) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onVideoClick: (NightVideoItem) -> Unit,
    onAddToView: (NightVideoItem) -> Unit,
    onClearToViewNotice: () -> Unit,
    onOpenSpace: (Long) -> Unit,
    onOpenOffline: () -> Unit,
    onOpenSettings: () -> Unit,
    onTempExit: () -> Unit,
    manual: Boolean = false,
    bottomOverlay: @Composable BoxScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BilbyNightSanctuaryTheme {
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        val snackbarHostState = remember { SnackbarHostState() }
        val currentOnClearNotice by rememberUpdatedState(onClearToViewNotice)
        var showExitHoldSheet by rememberSaveable { mutableStateOf(false) }

        state.toViewNotice?.let { notice ->
            val message = stringResource(notice.message)
            LaunchedEffect(notice.id) {
                snackbarHostState.showSnackbar(message)
                currentOnClearNotice()
            }
        }

        val endFormatted = formatMinuteOfDay(prefs.endMinuteOfDay)
        val rangeFormatted = formatNightRange(prefs.startMinuteOfDay, prefs.endMinuteOfDay)
        val topSubtitle = if (manual) {
            stringResource(Res.string.night_mode_until_subtitle, endFormatted)
        } else {
            rangeFormatted
        }

        Scaffold(
            modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                BilbyTopBar(
                    title = stringResource(Res.string.settings_section_night_mode),
                    subtitle = topSubtitle,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        TextButton(onClick = { showExitHoldSheet = true }) {
                            Text(stringResource(Res.string.night_mode_temp_exit))
                        }
                        if (state.allowedUps.isNotEmpty()) {
                            val settingsLabel = stringResource(Res.string.settings_title)
                            BilbyTooltip(text = settingsLabel, shortcut = "Ctrl+,") {
                                IconButton(onClick = onOpenSettings) {
                                    Icon(Icons.Outlined.Settings, contentDescription = settingsLabel)
                                }
                            }
                            RefreshAction(
                                refreshing = state.refreshing,
                                onRefresh = onRefresh,
                            )
                        }
                    },
                )
            },
        ) { insets ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padScaffoldExceptBottom(insets),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (state.allowedUps.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .readableWidth()
                                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
                        ) {
                            NightSanctuaryHeaderCard(
                                manual = manual,
                                endFormatted = endFormatted,
                                rangeFormatted = rangeFormatted,
                                onOpenOffline = onOpenOffline,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(Spacing.Spacious),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
                            ) {
                                EmptyState(
                                    message = stringResource(Res.string.night_mode_empty_ups),
                                    icon = Icons.Outlined.Bedtime,
                                    modifier = Modifier,
                                )
                                FilledTonalButton(onClick = onOpenSettings) {
                                    Text(stringResource(Res.string.night_mode_go_settings))
                                }
                            }
                        }
                    } else {
                        val wide = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)

                        AdaptiveVideoGridContent(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) { gridCols ->
                            val useGrid = gridCols > 1
                            val effectiveColumns = GridCells.Fixed(gridCols)
                            val listPadding = if (useGrid) {
                                PaddingValues(
                                    start = Spacing.Tight - GridCardOuterPadding,
                                    end = Spacing.Tight - GridCardOuterPadding,
                                    top = Spacing.Hair,
                                    bottom = Spacing.Comfortable + navigationBarsBottom(),
                                )
                            } else {
                                PaddingValues(bottom = navigationBarsBottom())
                            }

                            RefreshBox(
                                refreshing = state.refreshing,
                                onRefresh = onRefresh,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                PagedColumn(
                                    layout = PagedLayout.Grid(effectiveColumns),
                                    items = state.items,
                                    key = { it.bvid },
                                    loading = state.loading,
                                    appending = state.appending,
                                    hasMore = state.hasMore,
                                    error = state.error?.let { stringResource(it) },
                                    emptyText = stringResource(Res.string.night_mode_empty_videos),
                                    onLoadMore = onLoadMore,
                                    onRetry = onRetry,
                                    contentPadding = listPadding,
                                    header = {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    top = Spacing.Tight,
                                                    bottom = Spacing.Hair,
                                                ),
                                        ) {
                                            NightSanctuaryHeaderCard(
                                                manual = manual,
                                                endFormatted = endFormatted,
                                                rangeFormatted = rangeFormatted,
                                                onOpenOffline = onOpenOffline,
                                                modifier = Modifier.padding(
                                                    horizontal = if (useGrid) {
                                                        Spacing.Tight + GridCardOuterPadding
                                                    } else {
                                                        Spacing.Comfortable
                                                    },
                                                ),
                                            )
                                            NightUpFilterBar(
                                                allowedUps = state.allowedUps,
                                                selectedMid = state.selectedMid,
                                                onSelectUp = onSelectUp,
                                                onOpenSpace = onOpenSpace,
                                                horizontalInset = if (useGrid) {
                                                    Spacing.Tight + GridCardOuterPadding
                                                } else {
                                                    Spacing.Comfortable
                                                },
                                            )
                                        }
                                    },
                                ) { item ->
                                    NightVideoCard(
                                        item = item,
                                        showUpName = state.selectedMid == null,
                                        useGrid = useGrid,
                                        wide = wide || useGrid,
                                        onClick = { onVideoClick(item) },
                                        onOpenSpace = onOpenSpace,
                                        onAddToView = { onAddToView(item) },
                                        modifier = if (useGrid) Modifier.padding(GridCardOuterPadding) else Modifier,
                                    )
                                }
                            }
                        }
                    }
                }
                bottomOverlay()
            }
        }

        if (showExitHoldSheet) {
            NightExitHoldSheet(
                title = stringResource(Res.string.night_mode_hold_title),
                body = stringResource(Res.string.night_mode_hold_body, endFormatted),
                onCompleted = {
                    showExitHoldSheet = false
                    onTempExit()
                },
                onDismiss = { showExitHoldSheet = false },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NightSanctuaryHeaderCard(
    manual: Boolean,
    endFormatted: String,
    rangeFormatted: String,
    onOpenOffline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = if (manual) {
        stringResource(Res.string.night_mode_sanctuary_manual, endFormatted)
    } else {
        stringResource(Res.string.night_mode_sanctuary_scheduled, rangeFormatted)
    }
    Surface(
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        ) {
            val badgeHaloColor = MaterialTheme.colorScheme.primary
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = badgeHaloColor,
            ) {
                // Static calm nocturnal radial halo behind the circular moon badge (no idle animation loop).
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(SanctuaryBadgeSize)
                        .drawBehind {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        badgeHaloColor.copy(alpha = 0.22f),
                                        Color.Transparent,
                                    ),
                                ),
                            )
                        },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bedtime,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.IconInline),
                    )
                }
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmallEmphasized.tabular,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = onOpenOffline,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = Spacing.Hair),
            ) {
                Icon(
                    imageVector = Icons.Outlined.DownloadForOffline,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.IconInline),
                )
                Text(
                    text = stringResource(Res.string.offline_title),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    maxLines = 1,
                    modifier = Modifier.padding(start = Spacing.Hair),
                )
            }
        }
    }
}

@Composable
private fun NightUpFilterBar(
    allowedUps: List<WhitelistedUp>,
    selectedMid: Long?,
    onSelectUp: (Long?) -> Unit,
    onOpenSpace: (Long) -> Unit,
    horizontalInset: androidx.compose.ui.unit.Dp = Spacing.Comfortable,
) {
    val colors = MaterialTheme.colorScheme
    val chipColors = FilterChipDefaults.filterChipColors(
        containerColor = colors.surfaceContainer,
        labelColor = colors.onSurfaceVariant,
        selectedContainerColor = colors.secondaryContainer,
        selectedLabelColor = colors.onSecondaryContainer,
    )
    val chipScrollState = rememberScrollState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier
                .weight(1f)
                .fadingRightEdge()
                .verticalWheelScrollsRow(chipScrollState)
                .horizontalScroll(chipScrollState)
                .padding(horizontal = horizontalInset),
        ) {
            if (allowedUps.size > 1) {
                FilterChip(
                    selected = selectedMid == null,
                    onClick = { onSelectUp(null) },
                    label = { Text(stringResource(Res.string.night_mode_all_ups)) },
                    shape = CircleShape,
                    border = null,
                    colors = chipColors,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                )
            }
            allowedUps.forEach { up ->
                FilterChip(
                    selected = selectedMid == up.mid,
                    onClick = { onSelectUp(up.mid) },
                    label = { Text(up.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = if (up.faceUrl.isNotEmpty()) {
                        { Avatar(url = up.faceUrl, size = FilterChipDefaults.IconSize) }
                    } else {
                        null
                    },
                    shape = CircleShape,
                    border = null,
                    colors = chipColors,
                    modifier = Modifier.minimumInteractiveComponentSize(),
                )
            }
        }
        if (selectedMid != null) {
            TextButton(
                onClick = { onOpenSpace(selectedMid) },
                modifier = Modifier.padding(end = Spacing.Tight),
            ) {
                Text(stringResource(Res.string.night_mode_open_space))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NightVideoCard(
    item: NightVideoItem,
    showUpName: Boolean,
    useGrid: Boolean,
    wide: Boolean,
    onClick: () -> Unit,
    onOpenSpace: (Long) -> Unit,
    onAddToView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = VideoRowUi(
        title = item.video.title,
        coverUrl = item.video.coverUrl,
        durationText = item.video.durationText,
        upName = item.up.name.takeIf { showUpName },
        upFaceUrl = item.up.faceUrl.takeIf { showUpName && it.isNotEmpty() },
        dateText = formatRelativeTime(item.video.publishedAtEpochSeconds),
        playText = item.video.playCountText,
        danmakuText = item.video.danmakuCountText,
        typeBadge = chargingBadgeText(item.video.chargingOnly),
    )
    val durationSeconds = parseDurationSeconds(item.video.durationText)
    val menuItems: @Composable ColumnScope.(close: () -> Unit) -> Unit = { close ->
        val commonCount = videoCommonMenuItemCount(
            bvid = item.bvid,
            upMid = item.up.mid,
            onOpenUpSpace = onOpenSpace,
            title = item.video.title,
        )
        val totalCount = 1 + commonCount
        DropdownMenuItem(
            onClick = {
                close()
                onAddToView()
            },
            text = { Text(stringResource(Res.string.video_action_toview_desc)) },
            leadingIcon = { Icon(Icons.Outlined.WatchLater, contentDescription = null) },
            shape = menuItemShape(0, totalCount),
        )
        VideoCommonMenuItems(
            bvid = item.bvid,
            title = item.video.title,
            close = close,
            upMid = item.up.mid,
            onOpenUpSpace = onOpenSpace,
            coverUrl = item.video.coverUrl,
            upName = item.up.name,
            durationSeconds = durationSeconds,
            startIndex = 1,
            totalCount = totalCount,
        )
    }

    var menuOpen by remember { mutableStateOf(false) }
    val isTouch = LocalPointerSource.current.isTouchLike
    val actionsLabel = stringResource(Res.string.menu_item_actions)
    val overflowSlot: (@Composable () -> Unit)? = if (wide) {
        null
    } else {
        {
            Box {
                BilbyTooltip(text = actionsLabel) {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = actionsLabel,
                        )
                    }
                }
                val popup: @Composable () -> Unit = {
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        shape = MenuDefaults.shape,
                        containerColor = MenuDefaults.containerColor,
                    ) {
                        menuItems { menuOpen = false }
                    }
                }
                if (isTouch) {
                    popup()
                } else {
                    MaterialExpressiveTheme(
                        colorScheme = MaterialTheme.colorScheme,
                        motionScheme = NightDesktopMenuMotionScheme,
                        typography = MaterialTheme.typography,
                        shapes = MaterialTheme.shapes,
                        content = popup,
                    )
                }
            }
        }
    }

    ContextMenuBox(
        onClick = null,
        menuLabel = actionsLabel,
        modifier = modifier,
        menu = menuItems,
    ) { openMenu ->
        if (useGrid) {
            VideoGridCard(
                ui = ui,
                onClick = onClick,
                onLongClick = openMenu,
                onUpClick = if (showUpName) ({ onOpenSpace(item.up.mid) }) else null,
                overflow = overflowSlot,
            )
        } else {
            VideoRow(
                item = ui,
                onClick = onClick,
                onLongClick = openMenu,
                overflow = overflowSlot,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object NightDesktopMenuMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
}

private val SanctuaryBadgeSize = 36.dp
private val GridCardOuterPadding = 2.dp
