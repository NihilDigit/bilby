package dev.bilby.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.media3.common.Player
import dev.bilby.R
import dev.bilby.data.DanmakuPrefs
import dev.bilby.live.LiveMessage
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.ShareLink
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.player.ControlButton
import dev.bilby.ui.player.DanmakuButton
import dev.bilby.ui.player.MediaBackButton
import dev.bilby.ui.player.DanmakuFeed
import dev.bilby.ui.player.DanmakuFontSizeSp
import dev.bilby.ui.player.PlaybackFailure
import dev.bilby.ui.player.PlayerDanmakuLayer
import dev.bilby.ui.player.PlayerGestureOptions
import dev.bilby.ui.player.PlayerShell
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.FixedColors
import dev.nihildigit.danmaku.Danmaku
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
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
    attached: Boolean,
    danmakuPrefs: DanmakuPrefs,
    onDanmakuEnabledChange: (Boolean) -> Unit,
    onQualityChange: (Int) -> Unit,
    /**
     * 这个直播间只要声音。**页内的临时状态**:关掉直播间就没了,不跨房间,也不写进设置。
     * 它只改变一件事——退到后台时播放不停,见 [dev.bilby.ui.live.LiveRoomRoute]。
     */
    onlyAudio: Boolean,
    onOnlyAudioChange: (Boolean) -> Unit,
    onLoadMoreGuards: () -> Unit,
    /** 发一条弹幕。直播不需要暂停,也不做本地回显(见 LiveRoomViewModel.sendDanmaku)。 */
    onSendDanmaku: (String) -> Unit,
    onRetry: () -> Unit,
    /**
     * 播到一半断了,而且服务的退避重试已经用完。**和 [LiveRoomUiState.error] 不是一回事**:
     * 那个说的是房间信息或流地址没取到,此刻页面上还没有画面;这个说的是画面停在了最后一帧。
     */
    playbackError: String?,
    retryingPlayback: Boolean,
    onRetryPlayback: () -> Unit,
    /** 分享要给出 `live.bilibili.com/<roomId>`,而房间号不在 [state] 里。 */
    roomId: Long,
    onBack: () -> Unit,
    /** 进主播的个人空间。mid 由 [state] 带,这一页不认识导航。 */
    onUserClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var fullscreen by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val share = { ShareLink.liveRoom(context, roomId, state.title) }

    // 和普通视频页同一套返回语义:先解锁,再退出沉浸,最后才离开直播间。
    BackHandler(enabled = fullscreen) {
        if (locked) locked = false else fullscreen = false
    }

    // 和播放页同一条规则(见 VideoScreen 里那段"状态栏那一条填黑"):不垫页面底色,改在画面
    // 上方补一条黑边;宽屏下状态栏整条收起来。下面的 Tab 自己躲左右和底部。
    val expandedLayout = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)
    Column(modifier = modifier.fillMaxSize()) {
        if (!fullscreen && !expandedLayout) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.barsAndCutout)
                    .background(Color.Black),
            )
        }
        Box(
            modifier = (
                if (fullscreen) Modifier.fillMaxSize()
                // widthIn 在 fillMaxWidth 之前,否则上限夹不动(见 AdaptiveContent 的说明)。
                else Modifier
                    .align(Alignment.CenterHorizontally)
                    .widthIn(max = Breakpoints.MediaWidth)
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    // **上面那条黑边只是取了 inset 的高度,没有消费它**
                    // (`windowInsetsTopHeight` 不消费)。不声明的话画面里的返回和分享会以为
                    // 自己还贴着屏幕上沿,各自再躲一次系统栏 —— 表现就是这两个按钮往画面里
                    // 掉了一条状态栏的高度。播放页在同一处写着同一行,这边漏了。
                    //
                    // **只在画了那条黑边时消费。** 宽屏下画面是全出血的(没有黑边),状态栏
                    // 真的压在按钮上,那时得让它们照旧躲。
                    .then(if (expandedLayout) Modifier else Modifier.consumeWindowInsets(WindowInsets.barsAndCutout))
                ).background(Color.Black),
        ) {
            if (player != null && state.isLive && state.streamUrl != null) {
                PlayerShell(
                    player = player,
                    // 纯音频时流里根本没有视频轨,挂上画面就是一块黑。占位封面正是"播放器装的
                    // 不是这一页要的画面"该有的样子,和取流窗口里那一段共用同一条路。
                    // 屏幕常亮也认这个值,于是纯音频时屏幕能自己息掉(见 PlayerShell)。
                    attached = attached && !onlyAudio,
                    placeholderCoverUrl = state.coverUrl,
                    isFullscreen = fullscreen,
                    onFullscreenChange = { fullscreen = it },
                    locked = locked,
                    onLockedChange = { locked = it },
                    title = state.title,
                    fullBleed = true,
                    // 宽屏下画面只占中间一块,状态栏两端露在页面底色上,图标明暗顾不过来。
                    hideStatusBar = expandedLayout,
                    // 同播放页:渐变归壳画,弹幕才落在它上面。
                    topScrim = !fullscreen,
                    // 直播既不能 seek 也不能快进:前者那条时间轴上没有往回拖这回事,后者会把
                    // 倍速设成 3x —— 在一条一直往前走的流上,那只是冲到最前沿然后卡住等数据。
                    // 亮度和音量照旧,它们跟内容是什么无关。
                    gestures = PlayerGestureOptions(seek = false, fastForward = false),
                    overlay = {
                        PlayerDanmakuLayer(
                            player = player,
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
                            watched = state.watched,
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
                            onlyAudio = onlyAudio,
                            onOnlyAudioChange = {
                                onOnlyAudioChange(it)
                                keepControlsAwake()
                            },
                            onMenuOpenChange = { setMenuOpen(it) },
                            onPlayPause = { togglePlayPause() },
                            onFullscreenToggle = { toggleFullscreen() },
                        )
                    },
                )
                // 断流之后画面停在最后一帧,除了这一块以外页面上没有任何东西说明发生了什么,
                // 也没有可按的地方。盖在画面正中,和视频页同一份(见 [PlaybackFailure])。
                if (playbackError != null) {
                    PlaybackFailure(
                        message = playbackError,
                        retrying = retryingPlayback,
                        onRetry = onRetryPlayback,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            } else {
                LiveOffline(state, onBack = onBack, onShare = share, onRetry = onRetry)
            }

            if (!fullscreen && player != null && state.isLive && state.streamUrl != null) {
                // 正在直播时 PlayerShell 没有页面级返回动作,把它放在画面左上角,和普通视频页
                // 同一条返回路径;顶部渐变保证亮色画面上箭头仍有对比度。
                MediaBackButton(
                    onBack = onBack,
                    onShare = { ShareLink.liveRoom(context, roomId, state.title) },
                    scrim = false,
                )
            }
        }

        if (!fullscreen) {
            AdaptiveContent(
                // 上边没有 inset 要躲,那一侧是画面;左右和底下要躲。
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.barsAndCutout
                            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    ),
                maxWidth = Breakpoints.ReadableWidth,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    LiveAnchorRow(state = state, onUserClick = onUserClick)
                    LiveRoomTabs(
                        state = state,
                        onLoadMoreGuards = onLoadMoreGuards,
                        onSendDanmaku = onSendDanmaku,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** 未开播、或者流没取到。封面配一句话,不空着一块黑。 */
@Composable
private fun LiveOffline(
    state: LiveRoomUiState,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (state.coverUrl.isNotEmpty()) {
            BiliAsyncImage(
                url = state.coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 和正在直播时同一个位置、同一份渐变:开没开播不该改变"怎么离开这一页"。
        MediaBackButton(onBack = onBack, onShare = onShare)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.Center)
                .clip(MaterialTheme.shapes.small)
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
            // 只有失败才给重试。未开播不是错误,再拉一次也还是没开播。
            if (!state.loading && state.error != null) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry), color = FixedColors.OnMedia)
                }
            }
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
    /** 「N 人看过」整句,服务端拼好的。空串就不画这一格,见 LiveRoomUiState.watched。 */
    watched: String,
    danmakuEnabled: Boolean,
    onDanmakuEnabledChange: (Boolean) -> Unit,
    qualities: List<Int>,
    currentQn: Int,
    onQualityChange: (Int) -> Unit,
    onlyAudio: Boolean,
    onOnlyAudioChange: (Boolean) -> Unit,
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
        // 拿不到就留白,不写"0 人看过" —— 那是个具体而错误的数字。宽度照占,不然弹幕按钮
        // 会在这一句到货的那一刻横着跳一下。
        Text(
            text = watched,
            style = MaterialTheme.typography.labelMedium,
            color = FixedColors.OnMedia,
            modifier = Modifier.weight(1f).padding(start = Spacing.Hair),
        )
        DanmakuButton(danmakuEnabled, onDanmakuEnabledChange, isFullscreen)
        LiveAudioOnlyButton(onlyAudio, onOnlyAudioChange, isFullscreen)
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

/**
 * 只要声音。**换的是流本身**:服务端直接给一条纯音频流(`only_audio=1`),不是本地把画面
 * 关掉,所以省下的是那一路视频的流量和解码。
 *
 * 图标随状态换,不只靠着色区分:控制条压在任意画面上,单靠一点颜色差读不出开没开。
 */
@Composable
private fun LiveAudioOnlyButton(
    onlyAudio: Boolean,
    onOnlyAudioChange: (Boolean) -> Unit,
    isFullscreen: Boolean,
) {
    ControlButton(
        expanded = onlyAudio,
        onClick = { onOnlyAudioChange(!onlyAudio) },
        label = null,
        icon = { tint ->
            Icon(
                imageVector = if (onlyAudio) Icons.Filled.Headset else Icons.Filled.HeadsetOff,
                contentDescription = stringResource(
                    if (onlyAudio) R.string.live_audio_only_off else R.string.live_audio_only_on,
                ),
                tint = tint,
                modifier = Modifier.size(if (isFullscreen) 22.dp else 18.dp),
            )
        },
    )
}

/**
 * 主播那一行:头像、名字、这场的标题,整行进他的个人空间。
 *
 * **这一页此前没有任何地方能到主播的空间**,而"这个人还发过什么"正是看直播时最容易起的
 * 念头 —— 之前只能退回上一页再从别处找他。头像和名字本来也只存在于通知栏里,页面上一个
 * 字都没有。
 *
 * 房间标题放在这里而不是画面上:全屏那条顶栏只在全屏时出现(见 §4.1b),竖屏时标题此前
 * 无处可读。
 *
 * 整行是一个语义节点(`clickable` + `role`),不是给头像单独挂点击 —— 那样读屏会把头像和
 * 名字念成两件无关的东西,而且只有那个小圆点能点(风格指南 §3)。
 */
@Composable
private fun LiveAnchorRow(state: LiveRoomUiState, onUserClick: (Long) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        modifier = Modifier
            .fillMaxWidth()
            // mid 要到房间信息回来才有,那之前整行不可点(点了也不知道去谁那儿)。
            .clickable(role = Role.Button, enabled = state.anchorMid != 0L) {
                onUserClick(state.anchorMid)
            }
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    ) {
        Avatar(url = state.anchorFace, size = Dimens.AvatarRow)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.anchorName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.title.isNotBlank()) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.IconInline),
        )
    }
}

@Composable
private fun LiveRoomTabs(
    state: LiveRoomUiState,
    onLoadMoreGuards: () -> Unit,
    onSendDanmaku: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    Column(modifier = modifier) {
        SecondaryTabRow(selectedTabIndex = pager.currentPage) {
            // 点标签要真的翻页。原来两个 onClick 都是空的,只有左右滑动能换页 ——
            // 一个看得见、按得动、什么都不发生的标签,比没有标签更糟。
            Tab(
                selected = pager.currentPage == 0,
                onClick = { scope.launch { pager.animateScrollToPage(0) } },
                text = { Text(stringResource(R.string.live_tab_chat)) },
            )
            Tab(
                selected = pager.currentPage == 1,
                onClick = { scope.launch { pager.animateScrollToPage(1) } },
                text = { Text(stringResource(R.string.live_tab_guard)) },
            )
        }
        // weight 而不是 fillMaxSize:在 Column 里 fillMaxSize 会让 pager 从 tab 栏下面
        // 再要一整屏,底部被推出可视区。
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            when (page) {
                0 -> ChatPane(state, onSendDanmaku)
                else -> GuardPane(state, onLoadMoreGuards)
            }
        }
    }
}

/** 醒目留言横条 + 滚动聊天。SC 在上面是因为它是"付过钱、要被看见"的一类,不该混在流里冲走。 */
@Composable
private fun ChatPane(state: LiveRoomUiState, onSendDanmaku: (String) -> Unit) {
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
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Spacing.Comfortable,
                vertical = Spacing.Tight,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            items(state.chat, key = { it.id }) { line ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = line.name + "：",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        // 名字封顶三分之一行宽:B 站的用户名可以很长,不截断的话正文会被
                        // 挤到屏幕外,而聊天要看的是正文。
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = ChatNameMaxWidth),
                    )
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // **常驻输入栏,不是弹出面板。** 播放页那边的弹幕输入要盖一层、还要暂停视频,是因为
        // 那一行按钮下面就是简介和评论,没有它的位置;直播间本来就有一条聊天栏,输入栏接在
        // 它下面读起来就是"在这儿说话",和评论区是同一个形状。直播也没什么好暂停的。
        LiveDanmakuInput(
            sending = state.sendingDanmaku,
            error = state.sendError,
            // 未开播时不给输入:此刻画面是一张封面,服务端也会拒。
            enabled = state.isLive,
            onSend = onSendDanmaku,
        )
    }
}

/**
 * 直播间的发言栏。形状照评论区的 `CommentInputBar`(风格指南 §2.7:同一件事只有一份样子),
 * 差别只有两处:这里没有"回复某人"那一行,以及多一条失败提示。
 *
 * 草稿在发送时就清空,和评论区一样。失败时那句话没了 —— 这是评论区当初的取舍,两处保持一致
 * 比这里单独更聪明重要。
 */
@Composable
private fun LiveDanmakuInput(
    sending: Boolean,
    error: String?,
    enabled: Boolean,
    onSend: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        onSend(text)
        text = ""
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.imePadding()) {
        Column {
            // 失败原因贴在输入框上面。直播间没有别的地方说这句话,而"等级不够""房间禁言"
            // 这几种失败,人得读一眼才知道下一步该干什么。
            error?.let {
                Text(
                    text = stringResource(R.string.danmaku_send_failed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(
                        start = Spacing.Comfortable,
                        end = Spacing.Comfortable,
                        top = Spacing.Tight,
                    ),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.Tight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= LiveDanmakuMaxLength) text = it },
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    placeholder = { Text(stringResource(R.string.danmaku_input_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) send() }),
                    shape = MaterialTheme.shapes.large,
                )
                FilledIconButton(onClick = send, enabled = enabled && !sending && text.isNotBlank()) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Dimens.IconInline),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.action_send),
                        )
                    }
                }
            }
        }
    }
}

/** 直播弹幕的长度上限比点播短(B 站自己的输入框就是 20)。 */
private const val LiveDanmakuMaxLength = 20

/**
 * 一条醒目留言。**头像 + 名字 + 金额一行,留言一段**,读的顺序就是"谁、多少钱、说了什么"。
 *
 * 三处照风格指南改过:
 *
 * - 圆角走 `shapes.medium`,不再拿间距刻度当半径(`RoundedCornerShape(Spacing.Tight)`)——
 *   §1.4 的形状档由信息密度定,和间距是两套刻度,混用等于把两个决定绑在一起。
 * - 名字取 `outline`、留言满对比度,与评论区同一条(§2.7b):一排卡片扫过去,先看见的该是
 *   留言而不是每张卡开头的人名。
 * - 金额单独装进一个 `tertiaryContainer` 的小块。它是这条留言之所以在这儿的原因,得读得出来;
 *   而容器色和文字色成对取自同一组 role,不用 alpha 兑(§3 最后一条)。**没有用 B 站自己那对
 *   `background_color`**:那两个色是照白底设计的,深色主题下直接糊,而且它编码的是价位档,
 *   金额本身已经写在那儿了。
 *
 * 圆角按 optical roundness:外 12 − 内边距 12 = 0,取刻度末档 `extraSmall` 4(同 §2.7c)。
 */
@Composable
private fun SuperChatCard(sc: LiveMessage.SuperChat) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.width(Dimens.SuperChatWidth),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Cozy),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                Avatar(url = sc.senderFace, size = Dimens.AvatarRow)
                Text(
                    text = sc.senderName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = stringResource(R.string.live_super_chat_price, sc.priceYuan),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = Spacing.Hair),
                    )
                }
            }
            Text(
                text = sc.message,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
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
    // 首屏失败给整屏错误,空列表给空态 —— 原来两种情况都只是一片什么都没有的白。
    if (state.guards.items.isEmpty()) {
        when {
            state.guards.error != null ->
                FullScreenError(state.guards.error, onLoadMore, Modifier.fillMaxSize())

            !state.guards.loading ->
                EmptyState(stringResource(R.string.live_guard_empty), Modifier.fillMaxSize())

            else -> FullScreenLoading(Modifier.fillMaxSize())
        }
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.Comfortable),
        verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
    ) {
        items(state.guards.items, key = { it.uid }) { guard ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(url = guard.face, size = Dimens.AvatarRow)
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
        item(key = "footer") {
            // 翻页失败不清列表,只在底下加一行重试。
            ListFooter(
                appending = state.guards.loading,
                hasMore = true,
                hasItems = true,
                error = state.guards.error,
                onRetry = onLoadMore,
            )
        }
    }
}

/** 聊天里用户名的宽度上限。留给正文的空间才是这一栏的主体。 */
private val ChatNameMaxWidth = 112.dp

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
