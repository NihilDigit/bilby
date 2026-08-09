package dev.bilby.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.common.Player
import dev.bilby.R
import dev.bilby.data.DanmakuPrefs
import dev.bilby.live.LiveMessage
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.player.ControlButton
import dev.bilby.ui.player.DanmakuButton
import dev.bilby.ui.player.DanmakuFeed
import dev.bilby.ui.player.DanmakuFontSizeSp
import dev.bilby.ui.player.PlayerDanmakuLayer
import dev.bilby.ui.player.PlayerGestureOptions
import dev.bilby.ui.player.PlayerShell
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.FixedColors
import dev.nihildigit.danmaku.Danmaku
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * 直播间。
 *
 * 播放器用的是和视频页同一个 [PlayerShell],只把 seek 那一档手势关掉 —— 直播的时间轴上
 * 没有"往回拖"这回事,而方向锁定、浮层、长按加速这些两边一模一样。
 *
 * 下面两屏:第一屏是醒目留言横条加滚动聊天,第二屏是大航海。**没有"推荐直播"那一类东西** ——
 * 进这个页面是因为用户点了某个 UP 的直播,不是因为有人替他挑了一个。
 */
@Composable
fun LiveRoomScreen(
    state: LiveRoomUiState,
    danmaku: Flow<Danmaku>,
    player: Player?,
    surfacePlayer: Player?,
    attached: Boolean,
    danmakuPrefs: DanmakuPrefs,
    onDanmakuEnabledChange: (Boolean) -> Unit,
    onQualityChange: (Int) -> Unit,
    onLoadMoreGuards: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var fullscreen by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .then(if (fullscreen) Modifier else Modifier.windowInsetsPadding(WindowInsets.statusBars)),
    ) {
        Box(
            modifier = (
                if (fullscreen) Modifier.fillMaxSize()
                else Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                ).background(Color.Black),
        ) {
            if (player != null && state.isLive && state.streamUrl != null) {
                PlayerShell(
                    player = player,
                    surfacePlayer = surfacePlayer,
                    attached = attached,
                    placeholderCoverUrl = state.coverUrl,
                    isFullscreen = fullscreen,
                    onFullscreenChange = { fullscreen = it },
                    locked = locked,
                    onLockedChange = { locked = it },
                    title = state.title,
                    // 直播既不能 seek 也不能快进:前者那条时间轴上没有往回拖这回事,后者会把
                    // 倍速设成 3x —— 在一条一直往前走的流上,那只是冲到最前沿然后卡住等数据。
                    // 亮度和音量照旧,它们跟内容是什么无关。
                    gestures = PlayerGestureOptions(seek = false, fastForward = false),
                    overlay = {
                        PlayerDanmakuLayer(
                            player = player,
                            surfacePlayer = surfacePlayer,
                            prefs = danmakuPrefs,
                            feed = DanmakuFeed.Stream(danmaku),
                            specialPool = emptyList(),
                            // 直播没有分 P,房间号就是"这池弹幕属于谁"。
                            cid = state.anchorMid,
                            fontSizeSp = if (fullscreen) DanmakuFontSizeSp.Fullscreen else DanmakuFontSizeSp.Embedded,
                        )
                    },
                    controlBar = {
                        LiveControlBar(
                            isPlaying = isPlaying,
                            isFullscreen = isFullscreen,
                            online = state.online,
                            danmakuEnabled = danmakuPrefs.enabled,
                            onDanmakuEnabledChange = {
                                onDanmakuEnabledChange(it)
                                keepControlsAwake()
                            },
                            qualities = state.qualities,
                            currentQn = state.currentQn,
                            onQualityChange = {
                                onQualityChange(it)
                                keepControlsAwake()
                            },
                            onMenuOpenChange = { setMenuOpen(it) },
                            onPlayPause = { togglePlayPause() },
                            onFullscreenToggle = { toggleFullscreen() },
                        )
                    },
                )
            } else {
                LiveOffline(state, onBack = onBack)
            }
        }

        if (!fullscreen) {
            LiveRoomTabs(
                state = state,
                onLoadMoreGuards = onLoadMoreGuards,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 未开播、或者流没取到。封面配一句话,不空着一块黑。 */
@Composable
private fun LiveOffline(state: LiveRoomUiState, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.coverUrl.isNotEmpty()) {
            BiliAsyncImage(
                url = state.coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(Spacing.Tight),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                tint = FixedColors.OnMedia,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(Spacing.Tight))
                .background(FixedColors.ScrimOnMedia)
                .padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
        ) {
            Text(
                text = when {
                    state.loading -> stringResource(R.string.live_loading)
                    state.error != null -> state.error
                    else -> stringResource(R.string.live_offline)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = FixedColors.OnMedia,
            )
        }
    }
}

/**
 * 直播的控制条:播放/暂停、人气值、弹幕开关、清晰度、全屏。**没有进度条**,那条时间轴上
 * 没有位置可拖;也没有分 P 和队列。
 */
@Composable
private fun LiveControlBar(
    isPlaying: Boolean,
    isFullscreen: Boolean,
    online: Long,
    danmakuEnabled: Boolean,
    onDanmakuEnabledChange: (Boolean) -> Unit,
    qualities: List<Int>,
    currentQn: Int,
    onQualityChange: (Int) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    onPlayPause: () -> Unit,
    onFullscreenToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Tight),
    ) {
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(
                    if (isPlaying) R.string.player_pause else R.string.player_play,
                ),
                tint = FixedColors.OnMedia,
            )
        }
        Text(
            text = stringResource(R.string.live_online, online),
            style = MaterialTheme.typography.labelMedium,
            color = FixedColors.OnMedia,
            modifier = Modifier.weight(1f).padding(start = Spacing.Hair),
        )
        DanmakuButton(danmakuEnabled, onDanmakuEnabledChange, isFullscreen)
        if (qualities.size > 1) {
            LiveQualityButton(
                qualities = qualities,
                currentQn = currentQn,
                isFullscreen = isFullscreen,
                onSelect = onQualityChange,
                onMenuOpenChange = onMenuOpenChange,
            )
        }
        IconButton(onClick = onFullscreenToggle) {
            Icon(
                imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = stringResource(
                    if (isFullscreen) R.string.player_exit_fullscreen else R.string.player_fullscreen,
                ),
                tint = FixedColors.OnMedia,
            )
        }
    }
}

@Composable
private fun LiveRoomTabs(
    state: LiveRoomUiState,
    onLoadMoreGuards: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(pageCount = { 2 })
    Column(modifier = modifier) {
        SecondaryTabRow(selectedTabIndex = pager.currentPage) {
            Tab(
                selected = pager.currentPage == 0,
                onClick = {},
                text = { Text(stringResource(R.string.live_tab_chat)) },
            )
            Tab(
                selected = pager.currentPage == 1,
                onClick = {},
                text = { Text(stringResource(R.string.live_tab_guard)) },
            )
        }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> ChatPane(state)
                else -> GuardPane(state, onLoadMoreGuards)
            }
        }
    }
}

/** 醒目留言横条 + 滚动聊天。SC 在上面是因为它是"付过钱、要被看见"的一类,不该混在流里冲走。 */
@Composable
private fun ChatPane(state: LiveRoomUiState) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.superChats.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.Cozy),
            ) {
                items(state.superChats, key = { it.id }) { sc -> SuperChatCard(sc) }
            }
        }

        val listState = rememberLazyListState()
        // 新的一行追在末尾,列表自己跟到底 —— 直播的聊天栏停在中间就等于没在看直播了。
        //
        // key 用**最新一条的 id**,不是条数:列表封顶 200 条,到顶之后条数恒为 200,拿它当
        // key 就再也不会触发了。也不在 LaunchedEffect 里 snapshotFlow `state` —— 它是普通
        // 参数不是 State 对象,块里捕获的是启动那一刻那一份。
        //
        // 用瞬时的 scrollToItem 而不是 animateScrollToItem:直播消息密集,动画每来一条就被
        // 取消重来一次,永远走不完,看上去就是不动。
        val lastId = state.chat.lastOrNull()?.id
        LaunchedEffect(lastId) {
            if (lastId != null) listState.scrollToItem(state.chat.lastIndex)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Spacing.Comfortable,
                vertical = Spacing.Tight,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            items(state.chat, key = { it.id }) { line ->
                Row {
                    Text(
                        text = line.name + "：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun SuperChatCard(sc: LiveMessage.SuperChat) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(Spacing.Tight),
        modifier = Modifier.width(Dimens.TraceCardWidth * 2),
    ) {
        Column(modifier = Modifier.padding(Spacing.Cozy)) {
            Text(
                text = sc.senderName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sc.message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.live_super_chat_price, sc.priceYuan),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun GuardPane(state: LiveRoomUiState, onLoadMore: () -> Unit) {
    val listState = rememberLazyListState()
    // 触底再拉下一页。分页是接口给的(page/page_size),不一次拉完 —— 大主播的大航海是几千人。
    LaunchedEffect(listState, state.guards.items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                val total = state.guards.items.size
                if (last != null && total > 0 && last >= total - GUARD_PREFETCH) onLoadMore()
            }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.Comfortable),
        verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
    ) {
        items(state.guards.items, key = { it.uid }) { guard ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                BiliAsyncImage(
                    url = guard.face,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.AvatarSmall).clip(RoundedCornerShape(50)),
                )
                Text(
                    text = guard.username,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(start = Spacing.Cozy),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        when (guard.guardLevel) {
                            1 -> R.string.live_guard_governor
                            2 -> R.string.live_guard_admiral
                            else -> R.string.live_guard_captain
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (state.guards.loading) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(Spacing.Comfortable)) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(24.dp),
                    )
                }
            }
        }
    }
}

private const val GUARD_PREFETCH = 5

/**
 * 清晰度。档名只在全屏显示 —— 内嵌时控制条窄,一个图标就够,而档名("原画""蓝光")截断之后
 * 反而分不出档。
 */
@Composable
private fun LiveQualityButton(
    qualities: List<Int>,
    currentQn: Int,
    isFullscreen: Boolean,
    onSelect: (Int) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ControlButton(
            expanded = expanded,
            onClick = {
                expanded = true
                onMenuOpenChange(true)
            },
            label = if (isFullscreen) stringResource(qualityLabel(currentQn)) else null,
            icon = { tint ->
                Icon(
                    Icons.Filled.HighQuality,
                    contentDescription = stringResource(R.string.player_quality),
                    tint = tint,
                    modifier = Modifier.size(if (isFullscreen) 22.dp else 18.dp),
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                onMenuOpenChange(false)
            },
        ) {
            // 服务端给的顺序是从低到高,菜单里反过来:清晰度菜单上手就该看见最好的那档。
            qualities.sortedDescending().forEach { qn ->
                DropdownMenuItem(
                    text = { Text(stringResource(qualityLabel(qn))) },
                    onClick = {
                        expanded = false
                        onMenuOpenChange(false)
                        onSelect(qn)
                    },
                    trailingIcon = if (qn == currentQn) {
                        { Text("·", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** 档位号到档名。取值见 PiliPlus `api.dart` 对 `getRoomPlayInfo` 的注释。 */
private fun qualityLabel(qn: Int): Int = when (qn) {
    30000 -> R.string.live_quality_dolby
    20000 -> R.string.live_quality_4k
    10000 -> R.string.live_quality_original
    400 -> R.string.live_quality_blu_ray
    250 -> R.string.live_quality_ultra
    150 -> R.string.live_quality_high
    else -> R.string.live_quality_smooth
}
