package dev.bilby.ui.video

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Edit
import dev.bilby.ui.player.ControlBarDanmaku
import dev.bilby.ui.player.ControlBarDanmakuField
import dev.bilby.player.audioQualityLabel
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.bilby.player.PlayerHandle
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.formatDurationMillis
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.player.ControlButton
import dev.bilby.ui.player.ControlScrimBottom
import dev.bilby.ui.player.DanmakuButton
import dev.bilby.ui.player.PlayerShell
import dev.bilby.ui.player.PlayerIconButton
import dev.bilby.ui.player.PlayerSidePanel
import dev.bilby.ui.components.rememberExpandedSheetState
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import dev.bilby.ui.player.PlayerTooltip
import dev.bilby.ui.player.formatSpeed
import dev.bilby.data.QualityOption
import dev.bilby.data.SettingsStore
import dev.bilby.player.SubtitleCue
import dev.bilby.player.SubtitleTrack
import dev.bilby.player.cueAt
import dev.bilby.ui.player.DanmakuFeed
import dev.bilby.ui.player.DanmakuFontSizeSp
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.player.PlayerDanmakuLayer
import dev.bilby.ui.components.SeekBar
import dev.bilby.ui.components.SeekBarSegment
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.data.DanmakuPrefs
import dev.bilby.data.DanmakuPrefsEditor
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
 * 88:控制条最矮的形态(内嵌的一行)约 76dp 高,再留一档间距。
 */
private val SubtitleBottomWithControls = 88.dp
private val SubtitleBottomBare = 24.dp

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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    /** 长按画面的临时倍速,来自设置页。 */
    fastForwardSpeed: Float = SettingsStore.DEFAULT_FAST_FORWARD_SPEED,
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
) {
    /** 两栏布局:内嵌画面占整窗高度,弹幕字号与全屏同档(见 [DanmakuFontSizeSp])。 */
    val twoPane = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)
    /** 播放设置面板开着没有。见 [PlayerSettingsContent]。 */
    var settingsOpen by remember { mutableStateOf(false) }
    // 开的是哪一段。关面板时不清:退场动画里内容要保持原样,下次打开会重新赋值。
    var settingsOnly by remember { mutableStateOf<PlayerSettingsSection?>(null) }
    PlayerShell(
        player = player,
        attached = matchesCurrentPage,
        placeholderCoverUrl = placeholderCoverUrl,
        isFullscreen = isFullscreen,
        onFullscreenChange = onFullscreenChange,
        locked = locked,
        onLockedChange = onLockedChange,
        title = title,
        fullBleed = fullBleed,
        hideStatusBar = hideStatusBar,
        topScrim = topScrim,
        externalLoading = externalLoading,
        modifier = modifier,
        fastForwardSpeed = fastForwardSpeed,
        topBarActions = topBarActions,
        embeddedTopActions = embeddedTopActions,
        pip = pip,
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
                    fontSizeSp = DanmakuFontSizeSp.of(largePlayer = isFullscreen || twoPane, pip = pip),
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
            val cue = remember(subtitleCues, positionMillis) { subtitleCues.cueAt(positionMillis) }
            // 让开控制条那一下要跟着控制条一起动。控制条自己是 spring 展开的([PlayerShell]),
            // 字幕原先是硬切:点一下画面,控制条缓缓升起,而字幕已经在上一帧跳到了新位置。
            // 用 spatial 那一档 —— 这是位移,不是透明度。
            val subtitleBottom by animateDpAsState(
                targetValue = if (controlsVisible && !locked) SubtitleBottomWithControls else SubtitleBottomBare,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "subtitle-bottom",
            )
            cue?.let {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = subtitleBottom)
                        .padding(horizontal = Spacing.Loose),
                ) {
                    Text(
                        it.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = FixedColors.OnMedia,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(FixedColors.ScrimOnMedia)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        },
        controlBar = {
            PlayerControlBar(
                segments = seekBarSegments,
                position = positionMillis,
                bufferedPosition = bufferedPositionMillis,
                duration = durationMillis,
                speed = speed,
                qualities = qualities,
                currentQuality = currentQuality,
                subtitleTracks = subtitleTracks,
                currentSubtitleLan = currentSubtitleLan,
                audioOptions = audioOptions,
                currentAudio = currentAudio,
                isFullscreen = isFullscreen,
                largePlayer = isFullscreen || twoPane,
                hasDanmakuSettings = danmakuEditor != null,
                danmakuField = controlBarDanmaku,
                // 写弹幕时控制条不自动收起,同设置面板开着时。
                onDanmakuFieldFocusChange = { setMenuOpen(it) },
                onSeekStart = { onSeekStart() },
                onSeekTo = { onSeekTo(it) },
                onSeekFinished = { onSeekFinished() },
                onOpenSettings = { section ->
                    settingsOnly = section
                    settingsOpen = true
                    // 面板开着时控件不自动收:面板关了人还要回到控制条上。
                    setMenuOpen(true)
                },
                onFullscreenToggle = { toggleFullscreen() },
                danmakuEnabled = danmakuPrefs.enabled,
                onDanmakuEnabledChange = {
                    danmakuEditor?.setEnabled(it)
                    keepControlsAwake()
                },
            )
        },
        panel = {
            val close = {
                settingsOpen = false
                setMenuOpen(false)
            }
            val settings: @Composable () -> Unit = {
                PlayerSettingsContent(
                    speed = speed,
                    onSpeedChange = { setSpeed(it) },
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
                    only = settingsOnly,
                )
            }
            PlayerSettingsHost(isFullscreen, settingsOpen, settingsOnly, onDismiss = close, content = settings)
        },
    )

}

/**
 * 底部控制条。播放键不在这里,在画面正中(见 PlayerShell 的 CenterPlayButton)。
 *
 * 两种排法,按画面大小分:
 *
 * - **窄**(单栏内嵌,以及竖屏视频的全屏):一行 ——「时间 进度条 时长 [弹幕] ⚙ ⛶」。倍速、
 *   清晰度、字幕收进 ⚙ 打开的整块设置面板。内嵌画面只有两百来 dp 高,两行控制条加上顶上的
 *   返回键,中间就放不下播放键了。
 * - **宽**(全屏,或双栏里的内嵌画面,且宽度够):进度条独占一行,下面一行是读数和几枚写着
 *   当前档位的 chip(「1.5x」「1080P」),每一枚只开自己那一段;弹幕开关旁边的 ⚙ 只开弹幕那一段。
 *   不只看宽度:单栏横屏的内嵌画面也有四五百 dp 宽,高度却放不下两行。
 *
 * 原先是一行按钮加一套"量一遍装不装得下、装不下就给不给档名、再装不下就分两行"的估算,
 * 窄屏上是六七个一样大的白色线框挤在右下角。现在窄的时候档位全在面板里,估算就不需要了。
 */
@Composable
private fun PlayerControlBar(
    segments: List<SeekBarSegment>,
    position: Long,
    bufferedPosition: Long,
    duration: Long,
    speed: Float,
    qualities: List<QualityOption>,
    currentQuality: Int,
    subtitleTracks: List<SubtitleTrack>,
    currentSubtitleLan: String,
    audioOptions: List<Int>,
    currentAudio: Int,
    isFullscreen: Boolean,
    /** 全屏,或双栏里那块占满整列高度的内嵌画面。只有这时才考虑宽排法。 */
    largePlayer: Boolean,
    /** 有没有弹幕设置可调。没有时宽排法不画那枚弹幕设置键。 */
    hasDanmakuSettings: Boolean,
    danmakuField: ControlBarDanmaku?,
    onDanmakuFieldFocusChange: (Boolean) -> Unit,
    onSeekStart: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    /** 打开设置面板;带上一段就只开那一段,null 开整块。 */
    onOpenSettings: (PlayerSettingsSection?) -> Unit,
    onFullscreenToggle: () -> Unit,
    danmakuEnabled: Boolean,
    onDanmakuEnabledChange: (Boolean) -> Unit,
) {
    val safeInsets = WindowInsets.barsAndCutout
    val container = Modifier
        .fillMaxWidth()
        // 渐变而不是一整条半透明黑。控件底下是画面本身,一条硬边的黑带会把画面横着切一刀。
        // 按钮现在自带容器(见 PlayerControls 的 mediaControlContainer),渐变只需托住读数。
        .background(
            Brush.verticalGradient(
                listOf(Color.Transparent, FixedColors.PlayerControlScrim, ControlScrimBottom),
            ),
        )
        // 挖孔和手势条会切掉贴边的控件。**无条件躲**:横屏两栏下画面也是全出血的。竖排时页面
        // 那一层已经躲过并消费掉了这份 inset,这里量到的是 0,不会重复叠加。
        .windowInsetsPadding(safeInsets)
        .padding(
            start = if (isFullscreen) Spacing.Comfortable else Spacing.Tight,
            end = if (isFullscreen) Spacing.Comfortable else Spacing.Tight,
            top = Spacing.Comfortable,
            bottom = Spacing.Tight,
        )

    val seekBar: @Composable (Modifier) -> Unit = { modifier ->
        SeekBar(
            position,
            duration,
            onSeekStart,
            onSeekTo,
            onSeekFinished,
            modifier
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
            bufferedPosition = bufferedPosition,
            segments = segments,
        )
    }

    BoxWithConstraints(modifier = container) {
        val wide = largePlayer && maxWidth >= Breakpoints.StackedControlBar
        val roomy = maxWidth >= InlineDanmakuBarMinWidth
        if (!wide) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TimeLabel(position, modifier = Modifier.padding(start = Spacing.Tight))
                seekBar(Modifier.weight(1f))
                TimeLabel(duration, secondary = true, modifier = Modifier.padding(end = Spacing.Hair))
                // 全屏没有标签行,弹幕开关只能在这里;内嵌时它在标签行那枚胶囊里。
                if (isFullscreen) DanmakuButton(danmakuEnabled, onDanmakuEnabledChange, isFullscreen)
                PlayerSettingsButton { onOpenSettings(null) }
                FullscreenButton(isFullscreen, onFullscreenToggle)
            }
        } else {
            // **两行负间距叠着放。** 两者都是 48dp 的触摸区,画出来的东西却只有十几 dp 高,两块
            // 空 padding 摞在一起读起来像两组不相干的控件。叠 [ControlRowOverlap] 之后看着是一组,
            // 两边的触摸区都还在 36dp 以上。进度条画在上层,叠掉的那一截归它。
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(-ControlRowOverlap),
            ) {
                seekBar(Modifier.fillMaxWidth().zIndex(1f))
                // 弹幕输入框在读数与 chip 之间,同网页端。这一行放不下它和整排 chip 时,它收成一枚
                // 「发弹幕」键,点开之后 chip 让位、输入框铺开,同窄屏胶囊写弹幕时视图切换退场。
                var composing by remember { mutableStateOf(false) }
                val showField = danmakuField != null && (roomy || composing)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier.padding(start = Spacing.Tight),
                    ) {
                        TimeLabel(position, large = true)
                        TimeLabel(duration, large = true, secondary = true, prefix = " / ")
                    }
                    if (showField) {
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
                            autoFocus = !roomy,
                            modifier = Modifier.weight(1f).padding(horizontal = Spacing.Cozy),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (roomy || !composing) {
                        SettingChips(
                            speed = speed,
                            // 只有一档时没什么可换,chip 不画(面板里那一段同样不画)。
                            qualityLabel = qualities.takeIf { it.size > 1 }
                                ?.firstOrNull { it.quality == currentQuality }?.label,
                            audioLabel = currentAudio.takeIf { audioOptions.size > 1 }?.let { audioQualityLabel(it) },
                            subtitleLabel = subtitleTracks.firstOrNull { it.lan == currentSubtitleLan }?.displayName,
                            hasSubtitles = subtitleTracks.isNotEmpty(),
                            onOpenSection = onOpenSettings,
                        )
                    }
                    if (danmakuField != null && !showField) {
                        val label = stringResource(Res.string.danmaku_send)
                        PlayerTooltip(label) {
                            PlayerIconButton(onClick = { composing = true }, icon = Icons.Filled.Edit, contentDescription = label)
                        }
                    }
                    DanmakuButton(danmakuEnabled, onDanmakuEnabledChange, isFullscreen)
                    if (hasDanmakuSettings) DanmakuSettingsButton { onOpenSettings(PlayerSettingsSection.Danmaku) }
                    FullscreenButton(isFullscreen, onFullscreenToggle)
                }
            }
        }
    }
}

/**
 * 读数。当前位置用 onSurface,总时长用 onSurfaceVariant:两级明度,一眼先读到"现在在哪"。
 * 数字取等宽(`tnum`):秒数每跳一下,等宽之外的数字会让整行左右抖。
 */
@Composable
private fun TimeLabel(
    millis: Long,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    secondary: Boolean = false,
    prefix: String = "",
) {
    Text(
        text = prefix + formatDurationMillis(millis),
        style = (if (large) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium)
            .copy(fontFeatureSettings = "tnum"),
        color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * 宽排法里那几枚写着当前档位的 chip,用来快速换档。**每一枚只打开设置面板里自己那一段**
 * (见 [PlayerSettingsSection])。
 * 没有字幕轨时那一枚不画,字幕关着时只画图标(写「关闭」读起来像按下去会关掉)。
 * 音质只有一条音轨时不画,同清晰度。
 */
@Composable
private fun SettingChips(
    speed: Float,
    qualityLabel: String?,
    audioLabel: String?,
    subtitleLabel: String?,
    hasSubtitles: Boolean,
    onOpenSection: (PlayerSettingsSection) -> Unit,
) {
    val speedDescription = stringResource(Res.string.player_speed)
    PlayerTooltip(speedDescription) {
        ControlButton(
            expanded = false,
            onClick = { onOpenSection(PlayerSettingsSection.Speed) },
            label = formatSpeed(speed),
            icon = { tint -> Icon(Icons.Filled.Speed, speedDescription, tint = tint, modifier = Modifier.size(ChipIconSize)) },
        )
    }
    if (qualityLabel != null) {
        val description = stringResource(Res.string.player_quality)
        PlayerTooltip(description) {
            ControlButton(
                expanded = false,
                onClick = { onOpenSection(PlayerSettingsSection.Quality) },
                label = qualityLabel,
                icon = { tint -> Icon(Icons.Filled.HighQuality, description, tint = tint, modifier = Modifier.size(ChipIconSize)) },
            )
        }
    }
    if (audioLabel != null) {
        val description = stringResource(Res.string.player_audio_quality)
        PlayerTooltip(description) {
            ControlButton(
                expanded = false,
                onClick = { onOpenSection(PlayerSettingsSection.Audio) },
                label = audioLabel,
                icon = { tint -> Icon(Icons.Filled.GraphicEq, description, tint = tint, modifier = Modifier.size(ChipIconSize)) },
            )
        }
    }
    if (hasSubtitles) {
        val description = stringResource(Res.string.player_subtitle)
        PlayerTooltip(description) {
            ControlButton(
                expanded = false,
                onClick = { onOpenSection(PlayerSettingsSection.Subtitle) },
                label = subtitleLabel,
                icon = { tint -> Icon(Icons.Filled.Subtitles, description, tint = tint, modifier = Modifier.size(ChipIconSize)) },
            )
        }
    }
}

private val ChipIconSize = 18.dp

/**
 * 宽排法那一行常驻弹幕输入框所需的宽度:读数、四枚带档名的 chip、三颗图标键之外,输入框还剩
 * 两百来 dp。双栏最窄时左栏只有五百多 dp,够不着这一档,输入框收成一枚键。
 */
private val InlineDanmakuBarMinWidth = 720.dp

@Composable
private fun FullscreenButton(isFullscreen: Boolean, onClick: () -> Unit) {
    val description = stringResource(
        if (isFullscreen) Res.string.player_exit_fullscreen else Res.string.player_fullscreen,
    )
    PlayerTooltip(description) {
        PlayerIconButton(
            onClick = onClick,
            icon = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
            contentDescription = description,
        )
    }
}


