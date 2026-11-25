package dev.bilby.ui.video

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bilby.player.PlayerHandle
import dev.bilby.player.SleepTimerMode
import dev.bilby.player.SleepTimerState
import dev.bilby.player.audioQualityLabel
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.formatDurationMillis
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.player.ControlBarDanmaku
import dev.bilby.ui.player.ControlBarDanmakuField
import dev.bilby.ui.player.ControlBarPlayButton
import dev.bilby.ui.player.ControlBarTransportCluster
import dev.bilby.ui.player.ControlButton
import dev.bilby.ui.player.FullscreenButton
import dev.bilby.ui.player.InlineDanmakuBarMinWidth
import dev.bilby.ui.player.DanmakuButton
import dev.bilby.ui.player.PlayerShell
import dev.bilby.ui.player.PlayerVolumeControl
import dev.bilby.ui.player.PlaybackSpeedController
import dev.bilby.ui.player.PlayerGestureOptions
import dev.bilby.ui.player.playerControlBar
import dev.bilby.ui.player.PlayerIconButton
import dev.bilby.ui.player.HudSpecularBorderColor
import dev.bilby.ui.player.HudSpecularBorderWidth
import androidx.compose.foundation.layout.Spacer
import dev.bilby.ui.player.PlayerTooltip
import dev.bilby.ui.player.computeItemRemainingMillis
import dev.bilby.ui.player.formatSpeed
import dev.bilby.ui.player.sleepTimerLabel
import dev.bilby.data.QualityOption
import dev.bilby.player.SubtitleCue
import dev.bilby.player.SubtitleTrack
import dev.bilby.player.activeSubtitleText
import dev.bilby.player.maxCueDurationMillis
import dev.bilby.ui.player.DanmakuFeed
import dev.bilby.ui.player.DanmakuFontSizeSp
import dev.bilby.ui.player.DanmakuVoteOverlay
import dev.bilby.ui.player.isCompactVoteContainer
import dev.bilby.ui.player.rememberDanmakuVoteUiState
import dev.bilby.ui.player.rememberLargePlayer
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.useTwoPaneMediaLayout
import dev.bilby.ui.player.PlayerDanmakuLayer
import dev.bilby.ui.player.PlayerProgressState
import dev.bilby.ui.components.SeekBar
import dev.bilby.ui.components.SeekBarSegment
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular
import dev.bilby.danmaku.DanmakuVote
import dev.bilby.data.DanmakuPrefs
import dev.bilby.data.DanmakuPrefsEditor
import dev.bilby.data.PlayerControlId
import dev.bilby.data.PlayerLayoutPrefs
import dev.bilby.data.PlayerPanelItem
import dev.bilby.ui.player.LocalPlayerLayoutPrefs
import dev.nihildigit.danmaku.Danmaku
import dev.nihildigit.danmaku.SpecialDanmaku
import dev.nihildigit.danmaku.DanmakuHost
import dev.nihildigit.danmaku.DanmakuViewport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 进度条那一行和按钮那一行叠多少。
 *
 * 12dp 是进度槽下方那块空 padding(16dp)减去 4dp 余量 —— 叠满会贴到槽本身,
 * 拖到最下沿时手指就落在按钮上了。
 */
private val ControlRowOverlap = 12.dp

/**
 * 字幕离画面底沿多远。两档:控制条在屏上时要整体抬到它上面去,不然字幕压在进度条和
 * 那排按钮上;控制条收起后只留一点边距,贴太近会被圆角或手势条切到。
 *
 * 抬起量取实测的 [PlayerShellScope.controlBarHeight] 加一档间距 —— 一行与两行排法差着五十来
 * dp,常量按单行写死,竖屏全屏两行时字幕就被压在进度条上。88 是兜底:控制条首次展开、高度
 * 还没量到的那几帧别让它反而沉下去(单行内嵌约 76dp 加间距)。
 */
private val SubtitleBottomWithControls = 88.dp
private val SubtitleControlsGap = Spacing.Tight
private val SubtitleBottomBare = 24.dp
/** 字幕轨启用时,右下角投票面板抬升让开一行字幕槽的高度,避免窄宽度下与居中字幕横向重叠。 */
private val SubtitleBandClearance = 48.dp
/** 紧凑内嵌画幅下,投票卡片紧贴单行控制条或单行字幕上方,不浪费稀缺的垂直画幅。 */
private val CompactVoteBottomWithControls = 64.dp
private val CompactVoteSubtitleClearance = 32.dp

/**
 * 播放器画面 + 控件。非全屏时被塞进 16:9 容器,全屏时铺满整屏,两种形态共用这一个 composable,
 * 靠 [isFullscreen] 切换布局与控件密度。
 *
 * 这里**不做**任何"下一个视频"的自动跳转(DESIGN 1.3/2.3),全屏下也不做。
 *
 * 播放器不归这里所有(DESIGN 2.4b:播放器归后台服务),所以这个 composable 只读状态、发命令,
 * 不 prepare、不 release。**进度上报同样不在这里**:它归服务的进度会话
 * ([dev.bilby.player.ProgressSession])。这一层曾经有一条 5 秒轮询,而换条的权力在服务——
 * 听视频模式下这个 composable 根本不进组合,于是那段时间一条心跳都发不出去,自动连播时
 * 上一条的最终位置和完播也无人上报。
 *
 * @param player 状态、控制与画面的唯一入口,实际传进来的是连到播放服务的 MediaController。
 *   Surface 也走它:`COMMAND_SET_VIDEO_SURFACE` 在 MediaController 上是有的,Surface 作为
 *   Parcelable 跨 binder 送到 session 那一侧。
 */
@Composable
fun BilbyPlayer(
    player: PlayerHandle,
    qualities: List<QualityOption>,
    currentQuality: Int,
    onQualityChange: (Int) -> Unit,
    /** 音质那一段,见 [PlayerSettingsContent]。 */
    audioOptions: List<Int> = emptyList(),
    currentAudio: Int = 0,
    onAudioChange: (Int) -> Unit = {},
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    /** 平板/宽屏双栏左列等大尺寸内嵌画面：复用全屏形态的控件排布，全屏专属壳层仍由 [isFullscreen] 控制。 */
    spaciousEmbedded: Boolean = false,
    /** 会被自动跳过的片段。只染在进度条上,不参与交互,见 [SeekBar]。 */
    seekBarSegments: List<SeekBarSegment> = emptyList(),
    /** 这条(cid)有哪些字幕轨,含 AI 生成的。为空时控制条不出现字幕按钮。 */
    subtitleTracks: List<SubtitleTrack> = emptyList(),
    /** 选中轨的语言代码,空字符串是关(默认)。 */
    currentSubtitleLan: String = "",
    onSubtitleTrackChange: (String) -> Unit = {},
    /** 选中轨的正文,按 fromMillis 升序。 */
    subtitleCues: List<SubtitleCue> = emptyList(),
    /**
     * 弹幕设置整体传入,不拆成一条一条的平行参数——理由见 [dev.bilby.ui.video.VideoViewModel]
     * 的 `danmakuPrefs`。其中 `enabled` 是总开关,关闭时 [DanmakuHost] 整个不进组合,帧循环
     * 也就不存在,不是只是不画;`scrollShowArea` 只约束滚动与顶部弹幕,底部弹幕照旧贴画面
     * 底沿(理由见 [DanmakuViewport])。
     */
    danmakuPrefs: DanmakuPrefs = DanmakuPrefs(),
    /** 控制条上的弹幕开关与设置面板里的弹幕各项都经它改。为 null 时面板里没有弹幕那一段。 */
    danmakuEditor: DanmakuPrefsEditor? = null,
    /** 定时关闭,与听视频界面共享同一份后台倒计时。onSleepTimer 非空时控制条与设置面板提供定时入口。 */
    sleepTimer: SleepTimerState = SleepTimerState(),
    onSleepTimer: ((SleepTimerMode) -> Unit)? = null,
    onSleepTimerAdjust: (Int) -> Unit = {},
    gestures: PlayerGestureOptions = PlayerGestureOptions(),
    speedController: PlaybackSpeedController,
    /**
     * 控件锁。横屏看视频时手容易碰到画面,一碰就暂停或快进;锁上之后除了解锁按钮,所有手势
     * 与控件都不响应。状态提在 VideoScreen,因为返回键要按"先解锁、再退出全屏"的顺序处理它。
     */
    locked: Boolean = false,
    onLockedChange: (Boolean) -> Unit = {},
    /** 已拉到的弹幕池,累计追加。时间轴在这里(Compose 层)编译——见类注释里对时间轴管理的说明。 */
    danmakuPool: List<Danmaku> = emptyList(),
    /**
     * mode 7 高级弹幕,与 [danmakuPool] 分开传。它们不选轨、不判碰撞、不受显示区域约束,
     * 渲染走独立的一层——合进同一个池只会让排布那条链路上到处是"这条是不是 7"的分支。
     */
    specialDanmakuPool: List<SpecialDanmaku> = emptyList(),
    /** 视频内的互动投票弹幕(`#VOTE#`),渲染在画面右下角([DanmakuVoteOverlay])。 */
    danmakuVotes: List<DanmakuVote> = emptyList(),
    onVoteDanmaku: (voteId: Long, optionIdx: Int, progressMillis: Long) -> Unit = { _, _, _ -> },
    /** 自己刚发出去的那条,立刻上屏。**不并进 [danmakuPool]**,见 VideoViewModel.selfDanmaku。 */
    selfDanmaku: Flow<Danmaku> = emptyFlow(),
    /** 弹幕池所属的 cid,换一条(切分 P、队列走到下一条)要整池重编,不是接着追加。 */
    danmakuCid: Long = 0L,
    /**
     * 播放器此刻装的是不是这一页的视频。**判据是 `AudioPlaybackService.state.current?.bvid`
     * 是否等于这一页的 bvid**,调用方(`VideoScreen`)算好再传进来。
     *
     * 播放器全 app 共用一份、跨页面存活(DESIGN 2.4b),点开新视频到它真正切过去之间有一段
     * 取流 + prepare 的窗口——这段时间里画面渲染的还是上一条视频的最后几帧。
     * 为 false 时不挂画面([dev.bilby.ui.player.VideoSurface]),改画 [placeholderCoverUrl];为 true 时正常渲染画面。
     * **不去暂停或销毁播放器**——那会打断后台连续播放,也违反"播放器归服务所有"。
     */
    matchesCurrentPage: Boolean = true,
    /** [matchesCurrentPage] 为 false 时画的占位封面,取这一页自己的封面,不是播放器正在放的那条。 */
    placeholderCoverUrl: String = "",
    /** 透传给 [PlayerShell]:画面压在状态栏底下。 */
    fullBleed: Boolean = false,
    /** 透传给 [PlayerShell]:两栏下把状态栏收起来。 */
    hideStatusBar: Boolean = false,
    /** 见 [PlayerShell] 的同名参数:页面在画面上挂了返回/分享,渐变交给壳画。 */
    topScrim: Boolean = false,
    /** 见 [PlayerShell] 的同名参数:取流/重试退避这类播放器状态之外的等待。 */
    externalLoading: Boolean = false,
    modifier: Modifier = Modifier,
    /** 只在全屏时显示。竖屏下标题就在播放器正下方,再印一遍是多余的。 */
    title: String = "",
    /** 全屏顶栏右端的东西,现在是切集入口。见 [PlayerShell] 的同名参数。 */
    topBarActions: @Composable RowScope.() -> Unit = {},
    /** 内嵌时右上角的东西,现在是分享。见 [PlayerShell] 的同名参数。 */
    embeddedTopActions: @Composable RowScope.() -> Unit = {},
    /** 窗口在画中画里。见 [PlayerShell] 的同名参数;弹幕画不画看 [DanmakuPrefs.inPip]。 */
    pip: Boolean = false,
    /** 宽排法控制条里的弹幕输入框。null 即不给(没登录、或这一形态不在这里发)。 */
    controlBarDanmaku: ControlBarDanmaku? = null,
    /** 单栏下控制条没有内联输入框时,`Enter` 唤起标签行弹幕胶囊的回调。 */
    onRequestExternalDanmakuInput: (() -> Unit)? = null,
    /** 是否在播放器画面内渲染长按倍速覆盖层;竖屏单栏内嵌时由外层画在画面下方,传 false。 */
    showSpeedOverlay: Boolean = true,
    /** 播放器面板与中央簇自定义配置。 */
    layoutPrefs: PlayerLayoutPrefs = LocalPlayerLayoutPrefs.current,
) {
    /** 两栏布局(含紧凑高度横屏)或大画面内嵌:控制条与中央簇按全屏形态排布，全屏专属壳层仍按 [isFullscreen] 门控。 */
    val twoPane = rememberBilbyWindowSize().useTwoPaneMediaLayout
    val largePlayer = rememberLargePlayer(isFullscreen) || twoPane || spaciousEmbedded
    /** 播放设置面板开着没有。见 [PlayerSettingsContent]。 */
    var settingsOpen by remember { mutableStateOf(false) }
    // 开的是哪一段。关面板时不清:退场动画里内容要保持原样,下次打开会重新赋值。
    var settingsOnly by remember { mutableStateOf<PlayerSettingsSection?>(null) }
    var danmakuFocusNonce by remember { mutableStateOf(0) }
    val voteUiState = rememberDanmakuVoteUiState(danmakuCid)
    PlayerShell(
        player = player,
        attached = matchesCurrentPage,
        placeholderCoverUrl = placeholderCoverUrl,
        isFullscreen = isFullscreen,
        onFullscreenChange = onFullscreenChange,
        spaciousEmbedded = spaciousEmbedded || twoPane,
        locked = locked,
        onLockedChange = onLockedChange,
        title = title,
        fullBleed = fullBleed,
        hideStatusBar = hideStatusBar,
        topScrim = topScrim,
        externalLoading = externalLoading,
        modifier = modifier,
        speedController = speedController,
        showSpeedOverlay = showSpeedOverlay,
        gestures = gestures,
        layoutPrefs = layoutPrefs,
        topBarActions = topBarActions,
        topBarControlRenderer = { control ->
            val showBg = control.showBackground
            when (control.id) {
                PlayerControlId.Settings -> PlayerSettingsButton(showBackground = showBg) {
                    settingsOnly = null
                    settingsOpen = true
                }
                PlayerControlId.DanmakuToggle -> DanmakuButton(
                    enabled = danmakuPrefs.enabled,
                    onEnabledChange = { danmakuEditor?.setEnabled(it) },
                    isFullscreen = isFullscreen,
                    showBackground = showBg,
                )
                PlayerControlId.Volume -> PlayerVolumeControl(showBackground = showBg)
                PlayerControlId.Speed -> {
                    val desc = stringResource(Res.string.player_speed)
                    PlayerTooltip(desc, anchorAbove = false) {
                        ControlButton(
                            expanded = false,
                            onClick = {
                                settingsOnly = PlayerSettingsSection.Speed
                                settingsOpen = true
                            },
                            label = formatSpeed(speedController.speed),
                            showBackground = showBg,
                            icon = { tint -> Icon(Icons.Filled.Speed, desc, tint = tint, modifier = Modifier.size(ChipIconSize)) },
                        )
                    }
                }
                PlayerControlId.Quality -> qualities.takeIf { it.size > 1 }
                    ?.firstOrNull { it.quality == currentQuality }?.label?.let { label ->
                        val desc = stringResource(Res.string.player_quality)
                        PlayerTooltip(desc, anchorAbove = false) {
                            ControlButton(
                                expanded = false,
                                onClick = {
                                    settingsOnly = PlayerSettingsSection.Quality
                                    settingsOpen = true
                                },
                                label = label,
                                showBackground = showBg,
                                icon = { tint -> Icon(Icons.Filled.HighQuality, desc, tint = tint, modifier = Modifier.size(ChipIconSize)) },
                            )
                        }
                    }
                PlayerControlId.Audio -> currentAudio.takeIf { audioOptions.size > 1 }?.let { audioId ->
                    val desc = stringResource(Res.string.player_audio_quality)
                    PlayerTooltip(desc, anchorAbove = false) {
                        ControlButton(
                            expanded = false,
                            onClick = {
                                settingsOnly = PlayerSettingsSection.Audio
                                settingsOpen = true
                            },
                            label = audioQualityLabel(audioId),
                            showBackground = showBg,
                            icon = { tint -> Icon(Icons.Filled.GraphicEq, desc, tint = tint, modifier = Modifier.size(ChipIconSize)) },
                        )
                    }
                }
                PlayerControlId.Subtitle -> if (subtitleTracks.isNotEmpty()) {
                    val desc = stringResource(Res.string.player_subtitle)
                    PlayerTooltip(desc, anchorAbove = false) {
                        ControlButton(
                            expanded = currentSubtitleLan.isNotEmpty(),
                            onClick = {
                                settingsOnly = PlayerSettingsSection.Subtitle
                                settingsOpen = true
                            },
                            label = subtitleTracks.firstOrNull { it.lan == currentSubtitleLan }?.displayName,
                            showBackground = showBg,
                            icon = { tint -> Icon(Icons.Filled.Subtitles, desc, tint = tint, modifier = Modifier.size(ChipIconSize)) },
                        )
                    }
                }
                PlayerControlId.SleepTimer -> if (onSleepTimer != null) {
                    val desc = stringResource(Res.string.sleep_timer_off)
                    PlayerTooltip(desc, anchorAbove = false) {
                        ControlButton(
                            expanded = sleepTimer.mode != SleepTimerMode.Off,
                            onClick = {
                                settingsOnly = PlayerSettingsSection.SleepTimer
                                settingsOpen = true
                            },
                            label = null,
                            showBackground = showBg,
                            icon = { tint -> Icon(Icons.Filled.Bedtime, desc, tint = tint, modifier = Modifier.size(ChipIconSize)) },
                        )
                    }
                }
                PlayerControlId.Fullscreen -> FullscreenButton(
                    isFullscreen = isFullscreen,
                    onClick = { onFullscreenChange(!isFullscreen) },
                    showBackground = showBg,
                )
                else -> Unit
            }
        },
        embeddedTopActions = embeddedTopActions,
        pip = pip,
        activeSubtitles = matchesCurrentPage && subtitleCues.isNotEmpty(),
        wheelAdjustsVolume = isFullscreen || twoPane || spaciousEmbedded,
        onToggleDanmaku = danmakuEditor?.let { editor -> { editor.setEnabled(!danmakuPrefs.enabled) } },
        onRequestDanmakuInput = when {
            controlBarDanmaku != null -> ({ danmakuFocusNonce += 1 })
            onRequestExternalDanmakuInput != null -> onRequestExternalDanmakuInput
            else -> null
        },
        overlay = {
            // 弹幕层:字号由这里按形态给,层自己不认识"全屏"。
            if (!pip || danmakuPrefs.inPip) {
                PlayerDanmakuLayer(
                    player = player,
                    prefs = danmakuPrefs,
                    feed = DanmakuFeed.Pool(danmakuPool),
                    specialPool = specialDanmakuPool,
                    selfDanmaku = selfDanmaku,
                    cid = danmakuCid,
                    fontSizeSp = DanmakuFontSizeSp.of(
                        largePlayer = largePlayer,
                        pip = pip,
                        scale = danmakuPrefs.fontScale,
                    ),
                    avoidSystemBars = !pip,
                )
            }

            // 字幕层。**不走 Media3 的 SubtitleConfiguration**:那要求先把 JSON 转成 VTT 再挂到
            // MediaItem 上,而这里的 MediaItem 是 AudioPlaybackService 拼的 DASH 合并源,插字幕轨
            // 要动到播放器所有权那一层。还有个更硬的理由:听视频模式下 VideoScreen.kt 那句
            // `setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)` 会把视频轨连带的字幕轨一起关掉——
            // 走播放器原生字幕的话,切到听视频字幕就会跟着消失,而这里的字幕来自独立的接口,不受
            // 视频轨开关影响。贴底而不是压中间:不挡画面主体,也不常驻遮住控制条位置——控件常驻
            // 显示时字幕整体上移让开。
            //
            // 二分查找,不逐帧线性扫:见 SubtitleCue.kt 上的注释。落在两句之间的空档里时是 null,
            // 什么都不画——句间停顿本来就没有字幕在念。
            val subtitleBottomTarget = when {
                pip -> Spacing.Tight
                controlsVisible && !locked -> maxOf(SubtitleBottomWithControls, controlBarHeight + SubtitleControlsGap)
                else -> SubtitleBottomBare
            }
            if (matchesCurrentPage && subtitleCues.isNotEmpty()) {
                SubtitleOverlayLayer(
                    subtitleCues = subtitleCues,
                    progress = progressState,
                    subtitleBottomTarget = subtitleBottomTarget,
                    pip = pip,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if (matchesCurrentPage && danmakuPrefs.enabled && !pip && !locked && danmakuVotes.isNotEmpty()) {
                val compactVote = isCompactVoteContainer(
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    fallbackCompact = !largePlayer,
                )
                val hasSubtitles = subtitleCues.isNotEmpty()
                val voteBottomTarget = if (compactVote) {
                    when {
                        controlsVisible -> if (controlBarHeight > 0.dp) {
                            controlBarHeight + Spacing.Hair
                        } else {
                            CompactVoteBottomWithControls
                        }
                        hasSubtitles -> SubtitleBottomBare + CompactVoteSubtitleClearance
                        else -> Spacing.Tight
                    }
                } else {
                    subtitleBottomTarget +
                        if (hasSubtitles && (largePlayer || !controlsVisible)) SubtitleBandClearance else 0.dp
                }
                val voteBottom by animateDpAsState(
                    targetValue = voteBottomTarget,
                    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    label = "vote-bottom",
                )
                val voteHorizontalMargin = if (compactVote) Spacing.Tight else Spacing.Comfortable
                val voteTopMargin = if (compactVote) Spacing.Hair else Spacing.Tight
                DanmakuVoteOverlay(
                    votes = danmakuVotes,
                    positionMillis = { progressState.displayPositionMillis },
                    cid = danmakuCid,
                    onVote = onVoteDanmaku,
                    uiState = voteUiState,
                    containerWidth = containerWidth,
                    containerHeight = containerHeight,
                    compactFallback = !largePlayer,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .then(
                            if (isFullscreen) {
                                Modifier.windowInsetsPadding(
                                    WindowInsets.barsAndCutout.only(WindowInsetsSides.Horizontal),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .padding(
                            top = voteTopMargin,
                            bottom = voteBottom,
                            start = voteHorizontalMargin,
                            end = voteHorizontalMargin,
                        ),
                )
            }
        },
        controlBar = {
            PlayerControlBar(
                segments = seekBarSegments,
                progress = progressState,
                speed = speed,
                qualities = qualities,
                currentQuality = currentQuality,
                subtitleTracks = subtitleTracks,
                currentSubtitleLan = currentSubtitleLan,
                audioOptions = audioOptions,
                currentAudio = currentAudio,
                sleepTimer = sleepTimer,
                hasSleepTimer = onSleepTimer != null,
                isFullscreen = isFullscreen,
                largePlayer = largePlayer,
                danmakuField = controlBarDanmaku,
                danmakuFocusNonce = danmakuFocusNonce,
                // 写弹幕时控制条不自动收起,同设置面板开着时。
                onDanmakuFieldFocusChange = { setMenuOpen(it) },
                onSeekStart = { onSeekStart() },
                onSeekTo = { onSeekTo(it) },
                onSeekFinished = { onSeekFinished() },
                onOpenSettings = { section ->
                    settingsOnly = section
                    settingsOpen = true
                    setMenuOpen(true)
                },
                onFullscreenToggle = { toggleFullscreen() },
                playWhenReady = playWhenReady,
                canPrevious = canPrevious,
                canNext = canNext,
                onTogglePlayPause = { togglePlayPause() },
                onPrevious = { seekToPrevious() },
                onNext = { seekToNext() },
                onSeekByMillis = { seekByMillis(it) },
                locked = locked,
                onToggleLock = { onLockedChange(!locked) },
                danmakuEnabled = danmakuPrefs.enabled,
                onDanmakuEnabledChange = {
                    danmakuEditor?.setEnabled(it)
                    keepControlsAwake()
                },
                layoutPrefs = layoutPrefs,
                allowSeek = gestures.seek,
                pip = pip,
            )
        },
        panel = {
            if (settingsOpen) setMenuOpen(true)
            val close = {
                settingsOpen = false
                setMenuOpen(false)
            }
            val settings: @Composable () -> Unit = {
                val itemRemainingMillis = computeItemRemainingMillis(
                    progressState.displayPositionMillis,
                    progressState.durationMillis,
                    speed,
                )
                PlayerSettingsContent(
                    speed = speed,
                    onSpeedChange = { speedController.applySpeed(it) },
                    qualities = qualities,
                    currentQuality = currentQuality,
                    onQualityChange = onQualityChange,
                    subtitleTracks = subtitleTracks,
                    currentSubtitleLan = currentSubtitleLan,
                    onSubtitleTrackChange = onSubtitleTrackChange,
                    audioOptions = audioOptions,
                    currentAudio = currentAudio,
                    onAudioChange = onAudioChange,
                    danmakuPrefs = danmakuPrefs,
                    danmakuEditor = danmakuEditor,
                    sleepTimer = sleepTimer,
                    itemRemainingMillis = itemRemainingMillis,
                    onSleepTimer = onSleepTimer,
                    onSleepTimerAdjust = onSleepTimerAdjust,
                    onDismiss = close,
                    only = settingsOnly,
                    fullHeightHost = largePlayer,
                )
            }
            PlayerSettingsHost(isFullscreen, settingsOpen, settingsOnly, onDismiss = close, content = settings)
        },
    )

}

@Composable
private fun SubtitleOverlayLayer(
    subtitleCues: List<SubtitleCue>,
    progress: PlayerProgressState,
    subtitleBottomTarget: Dp,
    pip: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val maxDuration = remember(subtitleCues) { subtitleCues.maxCueDurationMillis() }
    val posMillis = progress.displayPositionMillis
    val text = remember(subtitleCues, maxDuration, posMillis) {
        subtitleCues.activeSubtitleText(posMillis, maxDuration)
    }
    val subtitleBottom by animateDpAsState(
        targetValue = subtitleBottomTarget,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "subtitle-bottom",
    )
    text?.let { subtitleText ->
        val subtitleShape = MaterialTheme.shapes.small
        Box(
            modifier = modifier
                .padding(bottom = subtitleBottom)
                .padding(horizontal = if (pip) Spacing.Tight else Spacing.Loose),
        ) {
            Text(
                subtitleText,
                style = if (pip) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyLarge,
                color = FixedColors.OnMedia,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(subtitleShape)
                    .background(FixedColors.ScrimOnMedia)
                    .border(HudSpecularBorderWidth, HudSpecularBorderColor, subtitleShape)
                    .padding(
                        horizontal = if (pip) Spacing.Tight else Spacing.Cozy,
                        vertical = if (pip) Spacing.Hair else 6.dp,
                    ),
            )
        }
    }
}

/**
 * 底部控制条。播放键不在这里,在画面正中(见 PlayerShell 的 CenterPlayButton)。
 *
 * 两种排法,按画面大小分:
 *
 * - **窄**(单栏内嵌):一行 ——「时间 进度条 时长 [弹幕] ⚙ ⛶」。倍速、清晰度、字幕收进 ⚙
 *   打开的整块设置面板。内嵌画面只有两百来 dp 高,两行控制条加上顶上的返回键,中间就放不下
 *   播放键了。
 * - **堆叠**(全屏 —— 横竖屏皆是,以及双栏里的内嵌画面):进度条独占一行,下面一行是读数
 *   与控件。全屏后进度条是这一屏最主要的操作,单行排法里它被挤在时间和三枚按钮中间只剩
 *   两百来 dp,拖着费劲;独占一行后整屏宽都是它的。控件按宽度分两档:够宽(≥480)时换成
 *   写着当前档位的 chip(「1.5x」「1080P」)加弹幕设置键;竖屏手机的宽度放不下那些,保持
 *   紧凑的 [弹幕] ⚙ ⛶ 三键,档位照常在面板里调。
 *
 * 原先是一行按钮加一套"量一遍装不装得下、装不下就给不给档名、再装不下就分两行"的估算,
 * 窄屏上是六七个一样大的白色线框挤在右下角。现在窄的时候档位全在面板里,估算就不需要了。
 */
@Composable
private fun PlayerControlBar(
    segments: List<SeekBarSegment>,
    progress: PlayerProgressState,
    speed: Float,
    qualities: List<QualityOption>,
    currentQuality: Int,
    subtitleTracks: List<SubtitleTrack>,
    currentSubtitleLan: String,
    audioOptions: List<Int>,
    currentAudio: Int,
    sleepTimer: SleepTimerState,
    hasSleepTimer: Boolean,
    isFullscreen: Boolean,
    /** 全屏,或双栏里那块占满整列高度的内嵌画面。只有这时才考虑宽排法。 */
    largePlayer: Boolean,
    danmakuField: ControlBarDanmaku?,
    danmakuFocusNonce: Int = 0,
    onDanmakuFieldFocusChange: (Boolean) -> Unit,
    onSeekStart: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    /** 打开设置面板;带上一段就只开那一段,null 开整块。 */
    onOpenSettings: (PlayerSettingsSection?) -> Unit,
    onFullscreenToggle: () -> Unit,
    playWhenReady: Boolean,
    canPrevious: Boolean,
    canNext: Boolean,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekByMillis: (Long) -> Unit,
    locked: Boolean,
    onToggleLock: () -> Unit,
    danmakuEnabled: Boolean,
    onDanmakuEnabledChange: (Boolean) -> Unit,
    layoutPrefs: PlayerLayoutPrefs = PlayerLayoutPrefs.Default,
    allowSeek: Boolean = true,
    /**
     * 小窗:只留读数和进度条。弹幕、设置、全屏都藏起来 —— 几百 dp 宽的小窗排不下一整排,
     * 小窗里要做的也只是看到哪了、拖一下;要调什么回到窗口里调。
     */
    pip: Boolean = false,
) {
    val container = Modifier.playerControlBar(isFullscreen)
    val density = LocalDensity.current
    var barWidth by remember { mutableStateOf(0.dp) }
    val isTouchLike = LocalPointerSource.current.isTouchLike
    val activeLayout = layoutPrefs.layoutFor(isLargeOrFullscreen = largePlayer && !pip)
    val centerActive = layoutPrefs.isCenterControlsActive(isTouchLike)
    val transportInCenter = centerActive && activeLayout.center.showCenterPlay
    val transportInAnyBar = !transportInCenter &&
        (activeLayout.hasInBottomBar(PlayerControlId.PlayPause) || activeLayout.hasInTopBar(PlayerControlId.PlayPause))

    fun isHiddenByTransportCluster(id: PlayerControlId): Boolean = when (id) {
        PlayerControlId.PlayPause -> transportInCenter
        else -> (transportInCenter || transportInAnyBar) && activeLayout.center.hasControl(id)
    }

    val seekBar: @Composable (Modifier) -> Unit = { modifier ->
        BoundSeekBar(
            progress = progress,
            onSeekStart = onSeekStart,
            onSeekTo = onSeekTo,
            onSeekFinished = onSeekFinished,
            segments = segments,
            modifier = modifier
                // **全屏时让开系统返回手势的那一条。** 全屏横屏下进度条两端就是屏幕左右边缘,
                // 从边缘起手往里拖进度会被系统读成返回。内嵌不让:那时两端离屏幕边缘本来就只有
                // 这条控制条的内边距,再收一截就短得难拖。
                .then(
                    if (isFullscreen) {
                        Modifier.windowInsetsPadding(
                            WindowInsets.systemGestures.only(WindowInsetsSides.Horizontal),
                        )
                    } else {
                        Modifier
                    },
                ),
        )
    }

    val qualityLabel = qualities.takeIf { it.size > 1 }
        ?.firstOrNull { it.quality == currentQuality }?.label
    val audioLabel = currentAudio.takeIf { audioOptions.size > 1 }?.let { audioQualityLabel(it) }
    val subtitleLabel = subtitleTracks.firstOrNull { it.lan == currentSubtitleLan }?.displayName

    Box(
        modifier = container.onSizeChanged { barWidth = with(density) { it.width.toDp() } },
    ) {
        // 堆叠:全屏或双栏里占满整列的内嵌画面,进度条才值得独占一行。宽一档(≥480)再摆 chip。
        // 宽排同时展示清晰度/音质/字幕等 chip 时,需更宽(≥820dp)才常驻内联弹幕输入框,避免挤压右端全屏键。
        val stacked = largePlayer && !pip
        val wide = stacked && barWidth >= Breakpoints.StackedControlBar
        val roomy = barWidth >= if (wide) WideInlineDanmakuBarMinWidth else InlineDanmakuBarMinWidth
        var composing by remember { mutableStateOf(false) }
        LaunchedEffect(danmakuFocusNonce) {
            if (danmakuFocusNonce > 0 && danmakuField != null) composing = true
        }
        if (pip) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!transportInCenter) {
                    ControlBarPlayButton(isPlaying = playWhenReady, onClick = onTogglePlayPause)
                }
                BoundTimeLabel(
                    progress = progress,
                    isDuration = false,
                    modifier = Modifier.padding(start = if (transportInCenter) Spacing.Tight else Spacing.Hair),
                )
                seekBar(Modifier.weight(1f))
                BoundTimeLabel(progress = progress, isDuration = true, secondary = true, modifier = Modifier.padding(end = Spacing.Tight))
            }
        } else if (!stacked) {
            val hasTimeItem = activeLayout.hasInBottomBar(PlayerControlId.TimeLabel)
            var renderedSeekBar = false
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                if (composing && danmakuField != null) {
                    ControlBarDanmakuField(
                        draft = danmakuField.draft,
                        onDraftChange = danmakuField.onDraftChange,
                        maxLength = danmakuField.maxLength,
                        sending = danmakuField.sending,
                        error = danmakuField.error,
                        onSend = danmakuField.onSend,
                        onFocusChange = { focused ->
                            onDanmakuFieldFocusChange(focused)
                            danmakuField.onComposingChange(focused)
                            if (!focused) composing = false
                        },
                        autoFocus = true,
                        focusRequestNonce = danmakuFocusNonce,
                        modifier = Modifier.weight(1f).widthIn(min = 72.dp),
                    )
                    if (activeLayout.hasInBottomBar(PlayerControlId.Volume)) PlayerVolumeControl()
                    if (isFullscreen && activeLayout.hasInBottomBar(PlayerControlId.DanmakuToggle)) {
                        DanmakuButton(danmakuEnabled, onDanmakuEnabledChange, isFullscreen)
                    }
                    if (activeLayout.hasInBottomBar(PlayerControlId.Settings)) {
                        PlayerSettingsButton { onOpenSettings(null) }
                    }
                    if (activeLayout.hasInBottomBar(PlayerControlId.Fullscreen)) {
                        FullscreenButton(isFullscreen, onFullscreenToggle)
                    }
                } else {
                    for (item in activeLayout.bottomBar) {
                        when (item) {
                            PlayerPanelItem.Spacer -> {
                                if (!renderedSeekBar && !hasTimeItem) {
                                    seekBar(Modifier.weight(1f))
                                    renderedSeekBar = true
                                } else if (!hasTimeItem) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                            is PlayerPanelItem.Control -> {
                                if (isHiddenByTransportCluster(item.id)) continue
                                val showBg = item.showBackground
                                when (item.id) {
                                    PlayerControlId.PlayPause -> ControlBarTransportCluster(
                                        config = activeLayout.center,
                                        isPlaying = playWhenReady,
                                        canPrevious = canPrevious,
                                        canNext = canNext,
                                        allowSeek = allowSeek,
                                        onTogglePlayPause = onTogglePlayPause,
                                        onPrevious = onPrevious,
                                        onNext = onNext,
                                        onSeekDeltaMillis = onSeekByMillis,
                                    )
                                    PlayerControlId.Previous -> if (canPrevious || canNext) {
                                        val prevLabel = stringResource(Res.string.player_previous)
                                        PlayerTooltip(prevLabel) {
                                            PlayerIconButton(
                                                onClick = onPrevious,
                                                enabled = canPrevious,
                                                icon = Icons.Filled.SkipPrevious,
                                                contentDescription = prevLabel,
                                                showBackground = showBg,
                                            )
                                        }
                                    }
                                    PlayerControlId.Next -> if (canPrevious || canNext) {
                                        val nextLabel = stringResource(Res.string.player_next)
                                        PlayerTooltip(nextLabel) {
                                            PlayerIconButton(
                                                onClick = onNext,
                                                enabled = canNext,
                                                icon = Icons.Filled.SkipNext,
                                                contentDescription = nextLabel,
                                                showBackground = showBg,
                                            )
                                        }
                                    }
                                    PlayerControlId.SeekBackward -> if (allowSeek) {
                                        val sec = layoutPrefs.seekButtonSeconds
                                        val label = stringResource(Res.string.player_seek_backward_seconds, sec)
                                        PlayerTooltip(label) {
                                            PlayerIconButton(
                                                onClick = { onSeekByMillis(-sec * 1_000L) },
                                                icon = Icons.Filled.FastRewind,
                                                contentDescription = label,
                                                showBackground = showBg,
                                            )
                                        }
                                    }
                                    PlayerControlId.SeekForward -> if (allowSeek) {
                                        val sec = layoutPrefs.seekButtonSeconds
                                        val label = stringResource(Res.string.player_seek_forward_seconds, sec)
                                        PlayerTooltip(label) {
                                            PlayerIconButton(
                                                onClick = { onSeekByMillis(sec * 1_000L) },
                                                icon = Icons.Filled.FastForward,
                                                contentDescription = label,
                                                showBackground = showBg,
                                            )
                                        }
                                    }
                                    PlayerControlId.TimeLabel -> {
                                        BoundTimeLabel(
                                            progress = progress,
                                            isDuration = false,
                                            modifier = Modifier.padding(start = if (transportInCenter) Spacing.Tight else 0.dp),
                                        )
                                        seekBar(Modifier.weight(1f))
                                        renderedSeekBar = true
                                        BoundTimeLabel(
                                            progress = progress,
                                            isDuration = true,
                                            secondary = true,
                                        )
                                    }
                                    PlayerControlId.DanmakuInput -> if (danmakuField != null) {
                                        ControlBarDanmakuField(
                                            draft = danmakuField.draft,
                                            onDraftChange = danmakuField.onDraftChange,
                                            maxLength = danmakuField.maxLength,
                                            sending = danmakuField.sending,
                                            error = danmakuField.error,
                                            onSend = danmakuField.onSend,
                                            onFocusChange = { focused ->
                                                composing = focused
                                                onDanmakuFieldFocusChange(focused)
                                                danmakuField.onComposingChange(focused)
                                            },
                                            autoFocus = false,
                                            focusRequestNonce = danmakuFocusNonce,
                                            modifier = Modifier
                                                .weight(1f)
                                                .widthIn(min = 72.dp),
                                        )
                                    }
                                    PlayerControlId.Volume -> PlayerVolumeControl(showBackground = showBg)
                                    PlayerControlId.DanmakuToggle -> if (isFullscreen) {
                                        DanmakuButton(danmakuEnabled, onDanmakuEnabledChange, isFullscreen, showBackground = showBg)
                                    }
                                    PlayerControlId.Speed -> {
                                        val desc = stringResource(Res.string.player_speed)
                                        PlayerTooltip(desc) {
                                            ControlButton(
                                                expanded = false,
                                                onClick = { onOpenSettings(PlayerSettingsSection.Speed) },
                                                label = formatSpeed(speed),
                                                showBackground = showBg,
                                                icon = { tint -> Icon(Icons.Filled.Speed, desc, tint = tint, modifier = Modifier.size(ChipIconSize)) },
                                            )
                                        }
                                    }
                                    PlayerControlId.SleepTimer -> if (hasSleepTimer && sleepTimer.mode != SleepTimerMode.Off) {
                                        BoundActiveSleepTimerChip(
                                            sleepTimer = sleepTimer,
                                            progress = progress,
                                            speed = speed,
                                            showLabel = barWidth >= Breakpoints.StackedControlBar,
                                            showBackground = showBg,
                                            onClick = { onOpenSettings(PlayerSettingsSection.SleepTimer) },
                                        )
                                    }
                                    PlayerControlId.Settings -> PlayerSettingsButton(showBackground = showBg) { onOpenSettings(null) }
                                    PlayerControlId.Fullscreen -> FullscreenButton(isFullscreen, onFullscreenToggle, showBackground = showBg)
                                    else -> Unit
                                }
                            }
                        }
                    }
                    if (!renderedSeekBar) {
                        seekBar(Modifier.weight(1f))
                    }
                }
            }
        } else {
            // **两行负间距叠着放。** 进度条独占首行，第二行严格按 KDE Plasma 面板模型有序排布 [activeLayout.bottomBar]。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(-ControlRowOverlap),
            ) {
                seekBar(Modifier.fillMaxWidth())
                val hasBottomSpacer = activeLayout.bottomBar.any { it == PlayerPanelItem.Spacer }
                val hasDanmakuPill = danmakuField != null && activeLayout.hasInBottomBar(PlayerControlId.DanmakuInput)
                // 弹幕输入框本身以 weight(1f) 弹性胶囊占位，自带弹性空白能力，避免与相邻 Spacer 重复瓜分宽度。
                val danmakuAbsorbsSpacer = hasDanmakuPill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (item in activeLayout.bottomBar) {
                        when (item) {
                            PlayerPanelItem.Spacer -> {
                                if ((!composing || roomy) && !danmakuAbsorbsSpacer) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                            is PlayerPanelItem.Control -> {
                                if (isHiddenByTransportCluster(item.id)) continue
                                val showBg = item.showBackground
                                when (item.id) {
                                    PlayerControlId.PlayPause -> if (!composing || roomy) {
                                        ControlBarTransportCluster(
                                            config = activeLayout.center,
                                            isPlaying = playWhenReady,
                                            canPrevious = canPrevious,
                                            canNext = canNext,
                                            allowSeek = allowSeek,
                                            onTogglePlayPause = onTogglePlayPause,
                                            onPrevious = onPrevious,
                                            onNext = onNext,
                                            onSeekDeltaMillis = onSeekByMillis,
                                        )
                                    }
                                    PlayerControlId.Previous -> if ((!composing || roomy) && (canPrevious || canNext)) {
                                        val prevLabel = stringResource(Res.string.player_previous)
                                        PlayerTooltip(prevLabel) {
                                            PlayerIconButton(
                                                onClick = onPrevious,
                                                enabled = canPrevious,
                                                icon = Icons.Filled.SkipPrevious,
                                                contentDescription = prevLabel,
                                                showBackground = showBg,
                                            )
                                        }
                                    }
                                    PlayerControlId.Next -> if ((!composing || roomy) && (canPrevious || canNext)) {
                                        val nextLabel = stringResource(Res.string.player_next)
                                        PlayerTooltip(nextLabel) {
                                            PlayerIconButton(
                                                onClick = onNext,
                                                enabled = canNext,
                                                icon = Icons.Filled.SkipNext,
                                                contentDescription = nextLabel,
                                                showBackground = showBg,
                                            )
                                        }
                                    }
                                    PlayerControlId.SeekBackward -> if ((!composing || roomy) && allowSeek) {
                                        val sec = layoutPrefs.seekButtonSeconds
                                        val label = stringResource(Res.string.player_seek_backward_seconds, sec)
                                        PlayerTooltip(label) {
                                            PlayerIconButton(
                                                onClick = { onSeekByMillis(-sec * 1_000L) },
                                                icon = Icons.Filled.FastRewind,
                                                contentDescription = label,
                                                showBackground = showBg,
                                            )
                                        }
                                    }
                                    PlayerControlId.SeekForward -> if ((!composing || roomy) && allowSeek) {
                                        val sec = layoutPrefs.seekButtonSeconds
                                        val label = stringResource(Res.string.player_seek_forward_seconds, sec)
                                        PlayerTooltip(label) {
                                            PlayerIconButton(
                                                onClick = { onSeekByMillis(sec * 1_000L) },
                                                icon = Icons.Filled.FastForward,
                                                contentDescription = label,
                                                showBackground = showBg,
                                            )
                                        }
                                    }
                                    PlayerControlId.TimeLabel -> if (!composing || roomy) {
                                        Row(
                                            verticalAlignment = Alignment.Bottom,
                                            modifier = Modifier.padding(
                                                start = if (transportInCenter) Spacing.Tight else 0.dp,
                                            ),
                                        ) {
                                            BoundTimeLabel(progress = progress, isDuration = false, large = true)
                                            BoundTimeLabel(progress = progress, isDuration = true, large = true, secondary = true, prefix = " / ")
                                        }
                                    }
                                    PlayerControlId.Volume -> if (!composing || roomy) {
                                        PlayerVolumeControl(showBackground = showBg)
                                    }
                                    PlayerControlId.DanmakuInput -> if (danmakuField != null) {
                                        // 以 weight(1f) 弹性胶囊直接充当弹性空白：固定宽度图标按钮优先完整测量，绝不挤压右端全屏键，
                                        // 剩余宽度全部由弹幕输入框胶囊优雅承接并在窄宽下平滑收缩。
                                        ControlBarDanmakuField(
                                            draft = danmakuField.draft,
                                            onDraftChange = danmakuField.onDraftChange,
                                            maxLength = danmakuField.maxLength,
                                            sending = danmakuField.sending,
                                            error = danmakuField.error,
                                            onSend = danmakuField.onSend,
                                            onFocusChange = { focused ->
                                                composing = focused
                                                onDanmakuFieldFocusChange(focused)
                                                danmakuField.onComposingChange(focused)
                                            },
                                            autoFocus = false,
                                            focusRequestNonce = danmakuFocusNonce,
                                            modifier = Modifier
                                                .weight(1f)
                                                .widthIn(min = 72.dp),
                                        )
                                    }
                                    PlayerControlId.Speed -> if (wide && !composing) {
                                        val speedDescription = stringResource(Res.string.player_speed)
                                        PlayerTooltip(speedDescription) {
                                            ControlButton(
                                                expanded = false,
                                                onClick = { onOpenSettings(PlayerSettingsSection.Speed) },
                                                label = formatSpeed(speed),
                                                showBackground = showBg,
                                                icon = { tint -> Icon(Icons.Filled.Speed, speedDescription, tint = tint, modifier = Modifier.size(ChipIconSize)) },
                                            )
                                        }
                                    }
                                    PlayerControlId.Quality -> if (wide && !composing && qualityLabel != null) {
                                        val description = stringResource(Res.string.player_quality)
                                        PlayerTooltip(description) {
                                            ControlButton(
                                                expanded = false,
                                                onClick = { onOpenSettings(PlayerSettingsSection.Quality) },
                                                label = qualityLabel,
                                                showBackground = showBg,
                                                icon = { tint -> Icon(Icons.Filled.HighQuality, description, tint = tint, modifier = Modifier.size(ChipIconSize)) },
                                            )
                                        }
                                    }
                                    PlayerControlId.Audio -> if (wide && !composing && audioLabel != null) {
                                        val description = stringResource(Res.string.player_audio_quality)
                                        PlayerTooltip(description) {
                                            ControlButton(
                                                expanded = false,
                                                onClick = { onOpenSettings(PlayerSettingsSection.Audio) },
                                                label = audioLabel,
                                                showBackground = showBg,
                                                icon = { tint -> Icon(Icons.Filled.GraphicEq, description, tint = tint, modifier = Modifier.size(ChipIconSize)) },
                                            )
                                        }
                                    }
                                    PlayerControlId.Subtitle -> if (wide && !composing && subtitleTracks.isNotEmpty()) {
                                        val description = stringResource(Res.string.player_subtitle)
                                        PlayerTooltip(description) {
                                            ControlButton(
                                                expanded = subtitleLabel != null,
                                                onClick = { onOpenSettings(PlayerSettingsSection.Subtitle) },
                                                label = subtitleLabel,
                                                showBackground = showBg,
                                                icon = { tint -> Icon(Icons.Filled.Subtitles, description, tint = tint, modifier = Modifier.size(ChipIconSize)) },
                                            )
                                        }
                                    }
                                    PlayerControlId.SleepTimer -> if (hasSleepTimer && !composing) {
                                        val active = sleepTimer.mode != SleepTimerMode.Off
                                        if (active) {
                                            BoundActiveSleepTimerChip(
                                                sleepTimer = sleepTimer,
                                                progress = progress,
                                                speed = speed,
                                                showLabel = wide && barWidth >= Breakpoints.Medium,
                                                showBackground = showBg,
                                                onClick = { onOpenSettings(PlayerSettingsSection.SleepTimer) },
                                            )
                                        } else if (wide) {
                                            val description = stringResource(Res.string.sleep_timer_off)
                                            PlayerTooltip(description) {
                                                ControlButton(
                                                    expanded = false,
                                                    onClick = { onOpenSettings(PlayerSettingsSection.SleepTimer) },
                                                    label = null,
                                                    showBackground = showBg,
                                                    icon = { tint -> Icon(Icons.Filled.Bedtime, description, tint = tint, modifier = Modifier.size(ChipIconSize)) },
                                                )
                                            }
                                        }
                                    }
                                    PlayerControlId.DanmakuToggle -> DanmakuButton(danmakuEnabled, onDanmakuEnabledChange, largePlayer, showBackground = showBg)
                                    PlayerControlId.Settings -> PlayerSettingsButton(showBackground = showBg) { onOpenSettings(null) }
                                    PlayerControlId.Lock -> if (isFullscreen) {
                                        val lockDesc = stringResource(if (locked) Res.string.player_unlock else Res.string.player_lock)
                                        PlayerTooltip(lockDesc) {
                                            PlayerIconButton(
                                                onClick = onToggleLock,
                                                icon = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                                contentDescription = lockDesc,
                                                selected = locked,
                                                showBackground = showBg,
                                            )
                                        }
                                    }
                                    PlayerControlId.Fullscreen -> FullscreenButton(isFullscreen, onFullscreenToggle, showBackground = showBg)
                                    else -> Unit
                                }
                            }
                        }
                    }
                    if (!hasBottomSpacer && !composing && !danmakuAbsorbsSpacer) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 隔离高频率进度条重绘，外层 [PlayerControlBar] 不会因播放位置变化而每 200ms/60fps 重组。 */
@Composable
private fun BoundSeekBar(
    progress: PlayerProgressState,
    onSeekStart: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    segments: List<SeekBarSegment>,
    modifier: Modifier = Modifier,
) {
    SeekBar(
        position = progress.displayPositionMillis,
        duration = progress.durationMillis,
        onSeekStart = onSeekStart,
        onSeekTo = onSeekTo,
        onSeekFinished = onSeekFinished,
        modifier = modifier,
        bufferedPosition = progress.bufferedPositionMillis,
        segments = segments,
    )
}

/**
 * 读数。当前位置用 onSurface,总时长用 onSurfaceVariant:两级明度,一眼先读到"现在在哪"。
 * 数字取等宽(`tnum`):秒数每跳一下,等宽之外的数字会让整行左右抖。
 */
@Composable
private fun BoundTimeLabel(
    progress: PlayerProgressState,
    isDuration: Boolean,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    secondary: Boolean = false,
    prefix: String = "",
) {
    val millis = if (isDuration) progress.durationMillis else progress.displayPositionMillis
    Text(
        text = prefix + formatDurationMillis(millis),
        style = (if (large) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium).tabular,
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = modifier,
    )
}

/** 窄控制条在定时激活时露出的紧凑提示 chip,没开定时时不占窄条位置;点它直达定时面板。 */
@Composable
private fun BoundActiveSleepTimerChip(
    sleepTimer: SleepTimerState,
    progress: PlayerProgressState,
    speed: Float,
    showLabel: Boolean,
    showBackground: Boolean = true,
    onClick: () -> Unit,
) {
    val itemRemainingMillis = if (sleepTimer.mode == SleepTimerMode.EndOfItem) {
        computeItemRemainingMillis(progress.displayPositionMillis, progress.durationMillis, speed)
    } else {
        null
    }
    val activeLabel = sleepTimerLabel(sleepTimer, itemRemainingMillis) ?: return
    val label = activeLabel.takeIf { showLabel }
    val description = stringResource(Res.string.sleep_timer_off)
    PlayerTooltip(description) {
        ControlButton(
            expanded = true,
            onClick = onClick,
            label = label,
            showBackground = showBackground,
            icon = { tint ->
                if (label == null) {
                    Icon(Icons.Filled.Bedtime, description, tint = tint, modifier = Modifier.size(ChipIconSize))
                }
            },
        )
    }
}

private val ChipIconSize = 18.dp
private val WideInlineDanmakuBarMinWidth = 820.dp
