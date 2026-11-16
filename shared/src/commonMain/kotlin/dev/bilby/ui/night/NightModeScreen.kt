package dev.bilby.ui.night

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
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
import dev.bilby.resources.night_mode_temp_exit
import dev.bilby.resources.offline_title
import dev.bilby.resources.settings_section_night_mode
import dev.bilby.resources.settings_title
import dev.bilby.resources.video_action_toview_desc
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveListContent
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.ContextMenuBox
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.PagedLayout
import dev.bilby.ui.components.RefreshAction
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.VideoCommonMenuItems
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.components.fadingRightEdge
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.components.videoCommonMenuItemCount
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

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
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

/**
 * 夜间极简主视图：无底栏、无侧边导航、无动态/直播/搜索，仅展示白名单 UP 主的点播投稿。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    bottomOverlay: @Composable BoxScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
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

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            BilbyTopBar(
                title = stringResource(Res.string.settings_section_night_mode),
                subtitle = formatNightRange(prefs.startMinuteOfDay, prefs.endMinuteOfDay),
                scrollBehavior = scrollBehavior,
                actions = {
                    TextButton(onClick = { showExitHoldSheet = true }) {
                        Text(stringResource(Res.string.night_mode_temp_exit))
                    }
                    if (state.allowedUps.isNotEmpty()) {
                        RefreshAction(
                            refreshing = state.refreshing,
                            onRefresh = onRefresh,
                        )
                    }
                    val offlineLabel = stringResource(Res.string.offline_title)
                    IconButton(onClick = onOpenOffline) {
                        Icon(Icons.Outlined.DownloadForOffline, contentDescription = offlineLabel)
                    }
                    val settingsLabel = stringResource(Res.string.settings_title)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = settingsLabel)
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
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.allowedUps.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(Spacing.Spacious),
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
                NightUpFilterBar(
                    allowedUps = state.allowedUps,
                    selectedMid = state.selectedMid,
                    onSelectUp = onSelectUp,
                    onOpenSpace = onOpenSpace,
                )
                AdaptiveListContent(modifier = Modifier.weight(1f).fillMaxWidth()) { columns ->
                    RefreshBox(
                        refreshing = state.refreshing,
                        onRefresh = onRefresh,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        PagedColumn(
                            layout = PagedLayout.Grid(columns),
                            items = state.items,
                            key = { it.bvid },
                            loading = state.loading,
                            appending = state.appending,
                            hasMore = state.hasMore,
                            error = state.error?.let { stringResource(it) },
                            emptyText = stringResource(Res.string.night_mode_empty_videos),
                            onLoadMore = onLoadMore,
                            onRetry = onRetry,
                            contentPadding = PaddingValues(bottom = navigationBarsBottom()),
                        ) { item ->
                            NightVideoCard(
                                item = item,
                                showUpName = state.selectedMid == null,
                                onClick = { onVideoClick(item) },
                                onOpenSpace = onOpenSpace,
                                onAddToView = { onAddToView(item) },
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
            body = stringResource(Res.string.night_mode_hold_body, formatMinuteOfDay(prefs.endMinuteOfDay)),
            onCompleted = {
                showExitHoldSheet = false
                onTempExit()
            },
            onDismiss = { showExitHoldSheet = false },
        )
    }
}

@Composable
private fun NightUpFilterBar(
    allowedUps: List<WhitelistedUp>,
    selectedMid: Long?,
    onSelectUp: (Long?) -> Unit,
    onOpenSpace: (Long) -> Unit,
) {
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
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Comfortable),
        ) {
            if (allowedUps.size > 1) {
                FilterChip(
                    selected = selectedMid == null,
                    onClick = { onSelectUp(null) },
                    label = { Text(stringResource(Res.string.night_mode_all_ups)) },
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

@Composable
private fun NightVideoCard(
    item: NightVideoItem,
    showUpName: Boolean,
    onClick: () -> Unit,
    onOpenSpace: (Long) -> Unit,
    onAddToView: () -> Unit,
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
    )
    val durationSeconds = parseDurationSeconds(item.video.durationText)
    ContextMenuBox(
        onClick = null,
        menuLabel = stringResource(Res.string.menu_item_actions),
        menu = { close ->
            val commonCount = videoCommonMenuItemCount(
                bvid = item.bvid,
                upMid = item.up.mid,
                onOpenUpSpace = onOpenSpace,
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
        },
    ) { openMenu ->
        VideoRow(
            item = ui,
            onClick = onClick,
            onLongClick = openMenu,
        )
    }
}
