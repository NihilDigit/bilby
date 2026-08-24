package dev.bilby.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.DragInteraction
import kotlinx.coroutines.flow.filter
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
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
import dev.bilby.ui.components.formatCount
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.LoadingSpinner
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * 直播间。
 *
 * 播放器用的是和视频页同一个 [PlayerShell],只把 seek 那一档手势关掉 —— 直播的时间轴上
 * 没有"往回拖"这回事,而方向锁定、浮层、长按加速这些两边一模一样。
 *
 * 下面三屏:滚动消息流、此刻有效的醒目留言、大航海。**没有"推荐直播"那一类东西** ——
 * 进这个页面是因为用户点了某个 UP 的直播,不是因为有人替他挑了一个。
 *
 * 消息流里混着弹幕、醒目留言、上舰和系统提示,各类的样子归 [LiveFeedRow]。**礼物、进场、
 * 进场特效、红包天选、全站广播都不显示**,那是产品决定,理由见 `docs/live-room-redesign.md` §4。
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
    /**
     * 重新取一次流。**失败面板上的重试和控制条上的刷新是同一个动作**,只是入口的时机不同:
     * 面板是服务放弃之后的出口,刷新按钮让人在服务还在退避、或者画面只是卡着还没算失败时
     * 先动手。所以它们共用这一对参数,而不是各接一条路。
     */
    onReloadStream: () -> Unit,
    reloadingStream: Boolean,
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
                            reloading = reloadingStream,
                            onReload = {
                                onReloadStream()
                                keepControlsAwake()
                            },
                            onFullscreenToggle = { toggleFullscreen() },
                        )
                    },
                )
                // 断流之后画面停在最后一帧,除了这一块以外页面上没有任何东西说明发生了什么,
                // 也没有可按的地方。盖在画面正中,和视频页同一份(见 [PlaybackFailure])。
                if (playbackError != null) {
                    PlaybackFailure(
                        message = playbackError,
                        retrying = reloadingStream,
                        onRetry = onReloadStream,
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
                    trailing = { OnlineRankLabel(state.onlineRank) },
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
                        onUserClick = onUserClick,
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
 * 直播的控制条:播放/暂停、刷新、人气值、弹幕开关、纯音频、清晰度、全屏。**没有进度条**,
 * 那条时间轴上没有位置可拖;也没有分 P 和队列。
 *
 * 刷新挨着播放/暂停,因为它们是同一类动作——都直接作用于"现在这一路流"。它和失败面板分工
 * 明确:面板是服务放弃之后的出口,而网不好时画面往往只是卡着,服务还没判成失败,这个按钮
 * 让人先于服务动手。
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
    /** 正在重新取流。此刻按钮换成转圈并且按不动,免得连按叠出几次请求。 */
    reloading: Boolean,
    onReload: () -> Unit,
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
        // 取流中把图标换成转圈,而不是把图标置灰:这一栏的图标本来就是固定色(压在任意画面上),
        // 置灰要么看不出来,要么和"这个功能不可用"撞在一起。发弹幕那个按钮是同一个做法。
        IconButton(onClick = onReload, enabled = !reloading) {
            if (reloading) {
                LoadingSpinner(color = FixedColors.OnMedia)
            } else {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.live_refresh),
                    tint = FixedColors.OnMedia,
                )
            }
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
    onUserClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    /** 从上面那一栏点过来要看的那一条。用完由那一屏清掉,否则回头再切过去又会滚一次。 */
    var pendingSuperChat by remember { mutableStateOf<Long?>(null) }
    Column(modifier = modifier) {
        SecondaryTabRow(selectedTabIndex = pager.currentPage) {
            // 点标签要真的翻页。原来两个 onClick 都是空的,只有左右滑动能换页 ——
            // 一个看得见、按得动、什么都不发生的标签,比没有标签更糟。
            //
            // **「醒目留言」这一格不带条数。** 一个会自己变大的数字说的是"有新东西,回来看",
            // 正是风格指南 §4.2 否掉的那类注意力标记。PiliPlus 在聊天区右上角挂了一个
            // `SC(n)` 的胶囊,这一处不照抄。
            Tab(
                selected = pager.currentPage == 0,
                onClick = { scope.launch { pager.animateScrollToPage(0) } },
                text = { Text(stringResource(R.string.live_tab_chat)) },
            )
            Tab(
                selected = pager.currentPage == 1,
                onClick = { scope.launch { pager.animateScrollToPage(1) } },
                text = { Text(stringResource(R.string.live_tab_super_chat)) },
            )
            Tab(
                selected = pager.currentPage == 2,
                onClick = { scope.launch { pager.animateScrollToPage(2) } },
                text = { Text(stringResource(R.string.live_tab_guard)) },
            )
        }
        // weight 而不是 fillMaxSize:在 Column 里 fillMaxSize 会让 pager 从 tab 栏下面
        // 再要一整屏,底部被推出可视区。
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            when (page) {
                0 -> ChatPane(
                    state = state,
                    onSendDanmaku = onSendDanmaku,
                    onUserClick = onUserClick,
                    onSuperChatClick = { id ->
                        // 先记下要看哪一条,再翻页 —— 翻页是动画,而那一屏要拿这个 id 定位。
                        pendingSuperChat = id
                        scope.launch { pager.animateScrollToPage(1) }
                    },
                )

                1 -> SuperChatPane(
                    state = state,
                    onUserClick = onUserClick,
                    target = pendingSuperChat,
                    onTargetHandled = { pendingSuperChat = null },
                )

                else -> GuardPane(state, onLoadMoreGuards, onUserClick)
            }
        }
    }
}

/**
 * 高能榜人数,摆在画面右上角分享的左边。
 *
 * **没收到过这条命令就不画**,不写"高能榜 0" —— 那是个具体而错误的数字(同一条判断见
 * 「N 人看过」那一格)。这条命令进房后几秒才来,所以开场那几秒这里是空的。
 *
 * 量级折算走 [formatCount]:服务端在这条上只给数字,不像 `WATCHED_CHANGE` 给拼好的句子。
 */
@Composable
private fun OnlineRankLabel(count: Int) {
    if (count <= 0) return
    Text(
        text = stringResource(R.string.live_online_rank, formatCount(count.toLong())),
        style = MaterialTheme.typography.labelMedium,
        color = FixedColors.OnMedia,
        modifier = Modifier.padding(end = Spacing.Hair),
    )
}

/**
 * 醒目留言那一栏。**没有底色,不占布局,浮在聊天最上面**(由调用方 align 到顶)。
 *
 * 这个位置上的横条当初被撤掉有两条理由(`docs/live-room-redesign.md` §2),现在两条都不成立:
 * "窄屏一次只露得出一张半卡片"由 chip 解掉(一个 chip 只有头像加金额,一屏排得下四五个);
 * "零与非零之间跳一次高度"由叠放解掉 —— 它根本不参与布局,聊天列表一格都不让。代价是盖住
 * 最上面那几行,而那是这一栏里最旧的几条,正在往上走,自己会走开。
 *
 * **只放生效中的那些。** 过期的进不来,否则这一栏会一直长;要看本场早前的切到那一屏。
 */
@Composable
private fun SuperChatStrip(
    superChats: List<LiveMessage.SuperChat>,
    onSuperChatClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 秒表在这一层,重组范围就是这一栏。和 SuperChatPane 各有一只:提到共同的父层去,
    // 每秒会把整个 pager 连着两屏一起重组。
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowSeconds = System.currentTimeMillis() / 1000
        }
    }
    val live = superChats.filter { it.endTimeSeconds > nowSeconds }
    if (live.isEmpty()) return
    val dark = MaterialTheme.colorScheme.surface.luminance() < DarkSurfaceLuminance
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = Spacing.Comfortable,
            vertical = Spacing.Tight,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(live, key = { it.id }) { sc ->
            SuperChatChip(
                sc = sc,
                tier = FixedColors.superChatTier(sc.priceYuan, dark),
                onClick = { onSuperChatClick(sc.id) },
            )
        }
    }
}

/**
 * 一个 chip:头像加金额,没有昵称 —— 带上昵称一个 chip 宽一倍,一屏排不下两个,而头像本身
 * 就是"是谁"的线索。
 *
 * **档位色画在金额上,不画成底色。** 那六个色值是按"在 surface 上当前景色"挑的
 * (见 FixedColors.superChatTier),拿去当底色要另配一套对比达标的文字色。
 */
@Composable
private fun SuperChatChip(sc: LiveMessage.SuperChat, tier: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.Hair, end = Spacing.Cozy, top = Spacing.Hair, bottom = Spacing.Hair),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Avatar(url = sc.senderFace, size = SuperChatChipAvatar)
            Text(
                text = stringResource(R.string.live_super_chat_price, sc.priceYuan),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = tier,
            )
        }
    }
}

/**
 * 此刻仍在有效期内的醒目留言,按到期时间从近到远排 —— 这一屏读的是"还剩多久",最快消失的
 * 那条该在最上面。进房前发出的那些由 `getMessageList` 补上(见 `LiveRoomViewModel`)。
 *
 * **到期由本地这只秒表判,列表按它过滤。** 到期时刻是服务端定的,但没有一条命令通知"这条到点
 * 了",所以本地每秒对一次表。撤回是另一回事,那个有命令([LiveMessage.SuperChatRemoved])。
 */
@Composable
private fun SuperChatPane(
    state: LiveRoomUiState,
    onUserClick: (Long) -> Unit,
    target: Long? = null,
    onTargetHandled: () -> Unit = {},
) {
    var nowSeconds by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowSeconds = System.currentTimeMillis() / 1000
        }
    }
    // 两节都从同一份日志派生(见 LiveRoomUiState.sessionSuperChats):生效中按到期时间排,
    // 最快消失的在最上面;本场早前按发出时间倒序。
    val live = state.sessionSuperChats
        .filter { it.endTimeSeconds > nowSeconds }
        .sortedBy { it.endTimeSeconds }
    val earlier = state.sessionSuperChats.filter { it.endTimeSeconds <= nowSeconds }
    val listState = rememberLazyListState()
    /**
     * 从上面那一栏点过来的那一条:滚过去,并让它亮一下。
     *
     * **只滚不亮不够** —— 这一屏十条卡片长得一模一样,滚到位之后读者不知道该看哪一条。
     */
    var highlighted by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(target, live.size) {
        val id = target ?: return@LaunchedEffect
        val index = live.indexOfFirst { it.id == id }
        onTargetHandled()
        if (index < 0) return@LaunchedEffect
        listState.animateScrollToItem(index)
        highlighted = id
        delay(HighlightMillis)
        highlighted = null
    }

    if (live.isEmpty() && earlier.isEmpty()) {
        // 正在补历史时给转圈,补完仍然空才说"暂无" —— 两者读起来完全不同,而这一屏在补历史
        // 的那一两秒里本来就是空的。
        if (state.superChatsLoading) {
            FullScreenLoading(Modifier.fillMaxSize())
        } else {
            EmptyState(stringResource(R.string.live_super_chat_empty), Modifier.fillMaxSize())
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.Comfortable),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        items(live, key = { superChatKey(it) }) { sc ->
            SuperChatSummaryCard(
                sc = sc,
                remainingSeconds = sc.endTimeSeconds - nowSeconds,
                highlighted = sc.id == highlighted,
                onUserClick = onUserClick,
            )
        }

        // 本场早前那一节。**只在拿到内容时才出现**:拿不到(没开开关、这位主播没被收录、
        // 这一场没在录、任何一步失败)就是没有这一节,不解释也不报错。
        if (earlier.isNotEmpty()) {
            item(key = "earlier-header") {
                Text(
                    text = stringResource(R.string.live_super_chat_earlier),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.Tight),
                )
            }
            items(earlier, key = { superChatKey(it) }) { sc ->
                SuperChatSummaryCard(
                    sc = sc,
                    remainingSeconds = null,
                    highlighted = sc.id == highlighted,
                    onUserClick = onUserClick,
                )
            }
            if (state.hasArchive) {
                item(key = "earlier-source") {
                    // 站外来的东西要说一句从哪来。**只在真有站外内容时说** —— 开关关着时这一节
                    // 里全是你在场期间过期的那些,标 danmakus 就是句假话(见 hasArchive)。
                    Text(
                        text = stringResource(R.string.live_super_chat_source),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacing.Tight),
                    )
                }
            }
        }
    }
}

/**
 * 汇总屏里的一条。比流里那一行多一个剩余时间,少一条档位色竖条 —— 这一屏本来就按到期时间排,
 * 顺序已经说明了紧迫程度。
 *
 * **剩余时间是数字,不是进度条。** 规范给这种已知终点的等待留的形态确实是 determinate 进度条,
 * 但一条持续跑动的进度条在直播间里就是一处一直在动的东西,而 M3 自己的可用性页写着动效要
 * "use it sparingly since motion can be distracting"。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SuperChatSummaryCard(
    sc: LiveMessage.SuperChat,
    /** 剩余时间。**null 表示这一条早过期了**(本场早前那一节),那时不画倒计时。 */
    remainingSeconds: Long?,
    highlighted: Boolean,
    onUserClick: (Long) -> Unit,
) {
    // 亮一下用描边,不换底色:换底色会让这张卡片在那一两秒里看起来是"另一类"的东西,
    // 而它只是刚被指过来的那一条。
    val border by animateColorAsState(
        targetValue = if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent,
        label = "superChatHighlight",
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(HighlightBorderWidth, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Cozy),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(role = Role.Button) { onUserClick(sc.senderMid) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                ) {
                    Avatar(url = sc.senderFace, size = Dimens.AvatarRow)
                    Text(
                        text = sc.senderName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SuperChatPrice(sc.priceYuan)
            }
            Text(
                text = sc.message,
                style = MaterialTheme.typography.bodyMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            remainingSeconds?.let {
                Text(
                    text = stringResource(R.string.live_super_chat_remaining, formatRemaining(it)),
                    style = MaterialTheme.typography.labelMedium.copy(
                        // **等宽数字。** 不加这一句,秒数每跳一下宽度就变一次,整行跟着抖。
                        fontFeatureSettings = TabularFigures,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 列表键。**不用 id** —— danmakus 补来的那些 id 是合成的(见 LiveRoomViewModel.toMessage),
 * 撞一次 LazyColumn 就会崩在 "Key was already used" 上。这一串是精确值,不经哈希。
 */
private fun superChatKey(sc: LiveMessage.SuperChat) =
    "${sc.senderMid}-${sc.startTimeSeconds}-${sc.message.length}"

/** `分:秒`,秒补零。超过一小时的档位也照分钟数写下去,不另分一段小时。 */
private fun formatRemaining(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}"
}

/** OpenType 的等宽数字特性。数字会变的地方都该开,否则每变一次布局抖一次。 */
private const val TabularFigures = "tnum"

/**
 * 消息流加发言栏。
 *
 * 弹幕、醒目留言、上舰、系统提示排在同一条流里,各类的样子归 [LiveFeedRow]。
 *
 * **醒目留言此前是这一屏顶上一条横滚的卡片带,现在撤掉了。** 两个代价是实打实的:数量在零和
 * 非零之间变化时整条列表要跳一次高度;窄屏上一次只露得出一张半卡片,而横滚压在一条本来就要
 * 竖滚的列表上面。现在它在流里出现一次(不会错过),完整清单在旁边那一屏。
 */
@Composable
private fun ChatPane(
    state: LiveRoomUiState,
    onSendDanmaku: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onSuperChatClick: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        val listState = rememberLazyListState()
        /*
         * **只在本来就贴着底的时候跟着新消息走。** 用户往上翻是在读某一条,而这一栏每秒新增
         * 几十行,无条件跟到底等于把他刚找到的位置一次次拽走。往上翻之后列表就停在那儿,
         * 新消息在下面堆着;翻回底部,又开始跟。
         *
         * 判据留 [BottomSlack] 条余量,不是"最后一条正好可见":这段 LaunchedEffect 跑在新条目
         * 已经进列表、但布局还没跑过的那一刻,`layoutInfo` 是上一帧的,严格比较会永远判成
         * "已经离开底部",于是再也不跟。
         *
         * key 用**最新一条的 id**,不是条数:列表封顶 200 条,到顶之后条数恒为 200,拿它当
         * key 就再也不会触发了。也不在 LaunchedEffect 里 snapshotFlow `state` —— 它是普通
         * 参数不是 State 对象,块里捕获的是启动那一刻那一份。
         *
         * 用瞬时的 scrollToItem 而不是 animateScrollToItem:直播消息密集,动画每来一条就被
         * 取消重来一次,永远走不完,看上去就是不动。同一条理由让新行没有进入动画。
         */
        /*
         * **跟不跟是一个记着的意图,不是每次现算的几何。**
         *
         * 先前两版都从 `layoutInfo` 推断"此刻是不是贴着底",再决定要不要跟。那个推断在三种
         * 情况下会翻:布局还没跑过时读到的是上一帧;动画落点差几像素;列表封顶从前面挤掉旧条
         * 导致下标整体前移。每翻一次,表现都是"再也不跟了",而症状处补一个余量或补一次收口
         * 只是把下一次翻推远一点。
         *
         * 现在只记一个 [following]:用户自己拖走就置 false,滚动停在底部或者按了「回到最新」
         * 就置 true。新消息来了只问它,不问几何。
         */
        var following by remember { mutableStateOf(true) }
        // 手指一搭上就算翻走了。**程序化滚动不产生 DragInteraction**,所以这里认的确实是人做的。
        LaunchedEffect(listState) {
            listState.interactionSource.interactions.collect {
                if (it is DragInteraction.Start) following = false
            }
        }
        // 滚动停下来时如果正好在底部,就重新开始跟 —— 不管这一下是谁滚的:用户自己划回底部
        // 和按「回到最新」滚回底部,想要的是同一件事。
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }
                .filter { !it }
                .collect { if (!listState.canScrollForward) following = true }
        }

        val lastId = state.feed.lastOrNull()?.id
        /** 「回到最新」那一下要用的实时条数:按下那一刻的下标在动画走完前就过期了。 */
        val feedSize by rememberUpdatedState(state.feed.size.coerceAtLeast(1))
        LaunchedEffect(lastId) {
            if (lastId != null && following) listState.scrollToItem(state.feed.lastIndex)
        }
        val scope = rememberCoroutineScope()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = Spacing.Comfortable,
                    vertical = Spacing.Tight,
                ),
                // 行之间只留间隔,不画分割线:M3 的 lists 页把 gap 定为 contained 列表的默认答案,
                // 分割线留给"没有容器、且确实需要更强分隔"的列表。重复版式的条目更是明写着可以
                // 只靠间距。
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                items(state.feed, key = { it.id }) { item -> LiveFeedRow(item, onUserClick) }
            }
            // **浮在聊天上面,不占一栏。** 被盖住的是最上面那几行,也就是这一栏里最旧的几条,
            // 而它们正在往上走 —— 盖住是暂时的,自己会走开。换来的是列表一格高度都不让,
            // 有没有醒目留言都不影响读到的行数。
            SuperChatStrip(
                superChats = state.sessionSuperChats,
                onSuperChatClick = onSuperChatClick,
                modifier = Modifier.align(Alignment.TopCenter),
            )
            // 翻上去之后给一条回程。**不带条数** —— 一个自己会变大的数字说的是"有多少条在等你",
            // 那是风格指南 §4.2 否掉的那类注意力标记;这一颗只回答"怎么回去"。
            //
            // 用 animateScrollToItem:这一下是用户自己按的,一次滚动动画正是他要的反馈;
            // 上面跟着新消息走的那处不能用动画,理由见那段注释。
            androidx.compose.animation.AnimatedVisibility(
                visible = !following && state.feed.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.Tight),
            ) {
                Surface(
                    onClick = {
                        // **先立意图,再滚。** 动画落在哪儿不再影响跟不跟 —— 就算这几百毫秒里
                        // 又来了几条、落点差在末尾之上,下一条消息也会把它接回底部。
                        following = true
                        scope.launch { listState.animateScrollToItem(feedSize - 1) }
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                    // 靠容器色浮起来,不加投影:M3E 把"分层"交给色彩,而这一颗压着的是一列
                    // 深浅不一的聊天行,投影在其中一些上面几乎看不出来。
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.IconInline),
                        )
                        Text(
                            text = stringResource(R.string.live_chat_jump_latest),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
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
                        LoadingSpinner()
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


@Composable
private fun GuardPane(state: LiveRoomUiState, onLoadMore: () -> Unit, onUserClick: (Long) -> Unit) {
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
            // 整行可点:这一行从头到尾都在说同一个人。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onUserClick(guard.uid) },
            ) {
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

private const val GUARD_PREFETCH = 5

/** chip 里的头像。和 [Dimens.AvatarRow] 同一档:这一栏是要被看见的,小一号就淹在聊天里了。 */
private val SuperChatChipAvatar = Dimens.AvatarRow

/** 跳过去那一条亮多久。 */
private const val HighlightMillis = 1_500L

private val HighlightBorderWidth = 2.dp

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
                val current = qn == currentQn
                DropdownMenuItem(
                    text = { Text(stringResource(qualityLabel(qn))) },
                    onClick = {
                        expanded = false
                        onMenuOpenChange(false)
                        onSelect(qn)
                    },
                    // 勾 + 语义两路都给:图标那一路读屏念不出来(勾没有可见文字,
                    // 描述交给 selected),只给语义则看得见的人分辨不出选中的是哪一档。
                    trailingIcon = if (current) {
                        {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.semantics { selected = current },
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
