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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import dev.bilby.R
import dev.bilby.formatDurationMillis
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.player.ControlButton
import dev.bilby.ui.player.ControlScrimBottom
import dev.bilby.ui.player.DanmakuButton
import dev.bilby.ui.player.PlayerShell
import dev.bilby.ui.player.PlayerTooltip
import dev.bilby.ui.player.formatSpeed
import dev.bilby.data.QualityOption
import dev.bilby.data.SettingsStore
import dev.bilby.player.SubtitleCue
import dev.bilby.player.SubtitleTrack
import dev.bilby.player.cueAt
import dev.bilby.ui.player.DanmakuFeed
import dev.bilby.ui.player.DanmakuFontSizeSp
import dev.bilby.ui.player.PlayerDanmakuLayer
import dev.bilby.ui.components.SeekBar
import dev.bilby.ui.components.SeekBarSegment
import dev.bilby.ui.components.SubtitleTrackMenu
import dev.bilby.ui.components.menuSelectedMark
import dev.bilby.ui.components.selectedSemantics
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.data.DanmakuPrefs
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

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * 字幕离画面底沿多远。两档:控制条在屏上时要整体抬到它上面去,不然字幕压在进度条和
 * 那排按钮上;控制条收起后只留一点边距,贴太近会被圆角或手势条切到。
 *
 * 88 是量出来的:控制条最矮的形态(内嵌、不分行)约 72dp 高,再留一档间距。
 */
private val SubtitleBottomWithControls = 88.dp
private val SubtitleBottomBare = 24.dp

/** 时间读数里的数字,量宽度时统一换成 0。 */
private val DigitPattern = Regex("\\d")

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
@OptIn(UnstableApi::class)
@Composable
fun BilbyPlayer(
    player: Player,
    qualities: List<QualityOption>,
    currentQuality: Int,
    onQualityChange: (Int) -> Unit,
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
    onDanmakuEnabledChange: (Boolean) -> Unit = {},
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
     * 为 false 时不挂 [PlayerSurface],改画 [placeholderCoverUrl];为 true 时正常渲染画面。
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
) {
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
        overlay = {
            // 弹幕层:字号由这里按形态给,层自己不认识"全屏"。
            PlayerDanmakuLayer(
                player = player,
                prefs = danmakuPrefs,
                feed = DanmakuFeed.Pool(danmakuPool),
                specialPool = specialDanmakuPool,
                selfDanmaku = selfDanmaku,
                cid = danmakuCid,
                fontSizeSp = if (isFullscreen) DanmakuFontSizeSp.Fullscreen else DanmakuFontSizeSp.Embedded,
            )

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
                isPlaying = isPlaying,
                position = positionMillis,
                bufferedPosition = bufferedPositionMillis,
                duration = durationMillis,
                speed = speed,
                qualities = qualities,
                currentQuality = currentQuality,
                isFullscreen = isFullscreen,
                onPlayPause = { togglePlayPause() },
                onSeekStart = { onSeekStart() },
                onSeekTo = { onSeekTo(it) },
                onSeekFinished = { onSeekFinished() },
                onSpeedChange = { setSpeed(it) },
                onQualityChange = {
                    onQualityChange(it)
                    keepControlsAwake()
                },
                subtitleTracks = subtitleTracks,
                currentSubtitleLan = currentSubtitleLan,
                onSubtitleTrackChange = {
                    onSubtitleTrackChange(it)
                    keepControlsAwake()
                },
                onFullscreenToggle = { toggleFullscreen() },
                onMenuOpenChange = { setMenuOpen(it) },
                danmakuEnabled = danmakuPrefs.enabled,
                onDanmakuEnabledChange = {
                    onDanmakuEnabledChange(it)
                    keepControlsAwake()
                },
            )
        },
    )

}

@Composable
private fun PlayerControlBar(
    segments: List<SeekBarSegment>,
    isPlaying: Boolean,
    position: Long,
    bufferedPosition: Long,
    duration: Long,
    speed: Float,
    qualities: List<QualityOption>,
    currentQuality: Int,
    isFullscreen: Boolean,
    onPlayPause: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onQualityChange: (Int) -> Unit,
    subtitleTracks: List<SubtitleTrack>,
    currentSubtitleLan: String,
    onSubtitleTrackChange: (String) -> Unit,
    onFullscreenToggle: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    danmakuEnabled: Boolean,
    onDanmakuEnabledChange: (Boolean) -> Unit,
) {
    val safeInsets = WindowInsets.barsAndCutout
    val container = Modifier
        .fillMaxWidth()
        // 渐变而不是一整条半透明黑。控件底下是画面本身,一条硬边的黑带会把画面横着切一刀,
        // 而渐变只在最需要对比度的地方(文字所在的下缘)压到最暗。B 站与 PiliPlus 的
        // 播放器同样是自下而上的渐变。
        .background(
            Brush.verticalGradient(
                listOf(Color.Transparent, FixedColors.PlayerControlScrim, ControlScrimBottom),
            ),
        )
        // 挖孔和手势条会切掉贴边的控件。**无条件躲**,不再只在全屏时躲:横屏两栏下画面
        // 也是全出血的,手势条同样压在控制条上。竖排时页面那一层已经躲过并消费掉了这份
        // inset,这里量到的是 0,不会重复叠加。
        .windowInsetsPadding(safeInsets)
        .padding(
            start = if (isFullscreen) 16.dp else 8.dp,
            end = if (isFullscreen) 16.dp else 8.dp,
            top = 16.dp,
            bottom = if (isFullscreen) 8.dp else 0.dp,
        )

    val timeText = "${formatDurationMillis(position)} / ${formatDurationMillis(duration)}"
    // 量宽度用的是位数相同、数字全换成 0 的模板,不是当下的读数:Roboto 的数字等宽,量出来
    // 一样宽,而模板不随秒数变 —— 用真读数的话,跨过 9:59 → 10:00 的那一秒整条控制条会从
    // 一行翻成两行。
    val timeTemplate = timeText.replace(DigitPattern, "0")
    val timeStyle =
        if (isFullscreen) MaterialTheme.typography.labelLarge
        else MaterialTheme.typography.labelSmall
    val labelStyle = MaterialTheme.typography.labelSmall
    val secondaryIconSize = if (isFullscreen) 22.dp else 18.dp

    val speedLabel = if (speed == 1f) null else formatSpeed(speed)
    val currentQualityLabel = qualities.firstOrNull { it.quality == currentQuality }?.label
    val currentSubtitleLabel = subtitleTracks.firstOrNull { it.lan == currentSubtitleLan }?.displayName

    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(modifier = container) {
        fun width(text: String, style: TextStyle): Dp =
            with(density) { measurer.measure(text, style).size.width.toDp() }

        // 一个次级按钮占多宽:图标加左右各 8dp,带档名时再加 4dp 和档名本身;不足触摸下限的
        // 按下限算([ControlButton] 自己就是这么撑的)。
        fun buttonWidth(label: String?): Dp {
            val content = secondaryIconSize + 8.dp * 2 +
                (label?.let { 4.dp + width(it, labelStyle) } ?: 0.dp)
            return maxOf(content, Dimens.MinTouchTarget)
        }

        fun secondaryWidth(withLabels: Boolean): Dp {
            var total = buttonWidth(speedLabel)
            if (qualities.isNotEmpty()) total += buttonWidth(currentQualityLabel.takeIf { withLabels })
            if (subtitleTracks.isNotEmpty()) {
                total += buttonWidth(currentSubtitleLabel.takeIf { withLabels })
            }
            // 弹幕开关只有图标,而且只在全屏留在这条控制条上。
            if (isFullscreen) total += Dimens.MinTouchTarget
            return total
        }

        /** 一行摆完要多宽:播放键 + 读数 + 次级控件 + 全屏键,两颗 IconButton 各按触摸下限算。 */
        fun rowWidth(withLabels: Boolean): Dp =
            Dimens.MinTouchTarget * 2 + width(timeTemplate, timeStyle) + secondaryWidth(withLabels)

        // **摆不下就把次级控件另起一行,判据是量出来的宽度,不是"是不是全屏"。**
        //
        // `Row` 既不换行也不缩:装不下时它照旧按声明顺序摆,排在最后的全屏按钮就落到容器
        // 外面去了 —— 看不见也点不着,而它是内嵌与全屏之间唯一的来回。全屏那套密度光触摸
        // 下限就摆不下:竖屏视频全屏(或平板分屏)只有 360–410dp,去掉两边 16dp 剩 328dp,
        // 而四个次级按钮各占 [Dimens.MinTouchTarget] 就是 192dp,加播放键、全屏键和 14sp 的
        // 读数约 400dp。内嵌那套只要 315dp 左右(三个按钮、11sp 读数、不给档名),360dp 上
        // 照旧一行 —— 风格指南 §7 那条"compact 逐像素照旧"因此没有被动到。
        val labelled = isFullscreen &&
            maxWidth >= Breakpoints.StackedControlBar &&
            rowWidth(withLabels = true) <= maxWidth
        val stacked = rowWidth(withLabels = labelled) > maxWidth

        // 两种排布摆的是同一组控件,所以只写一份 —— 各写一份的话分行那一支迟早少一个按钮。
        val secondary: @Composable () -> Unit = {
            SecondaryControls(
                qualities = qualities,
                currentQuality = currentQuality,
                subtitleTracks = subtitleTracks,
                currentSubtitleLan = currentSubtitleLan,
                danmakuEnabled = danmakuEnabled,
                speed = speed,
                speedLabel = speedLabel,
                qualityLabel = currentQualityLabel.takeIf { labelled },
                subtitleLabel = currentSubtitleLabel.takeIf { labelled },
                iconSize = secondaryIconSize,
                isFullscreen = isFullscreen,
                onSpeedChange = onSpeedChange,
                onQualityChange = onQualityChange,
                onSubtitleTrackChange = onSubtitleTrackChange,
                onDanmakuEnabledChange = onDanmakuEnabledChange,
                onMenuOpenChange = onMenuOpenChange,
            )
        }

        // 进度条独占一行:挤在按钮行里只剩几十 dp 可拖,而拖拽是这里最主要的操作。
        //
        // **两行负间距叠着放。** 两者都是 48dp 的触摸区,而画出来的东西一个 16dp(进度槽)、
        // 一个 22dp(图标),各自上下留着十几 dp 的空 —— 两块空 padding 摞在一起就是 29dp 的
        // 视觉空隙,读起来像两组不相干的控件。让它们共用一部分:叠 [ControlRowOverlap] 之后
        // 看着是一组,而两边的触摸区都还在 36dp 以上。
        //
        // 进度条画在上层([zIndex]),叠掉的那一截归它 —— 拖拽要的精度比点一个 22dp 的图标高,
        // 而按钮叠掉的只是自己顶上的空 padding,图标本身一点没被盖到。
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(-ControlRowOverlap),
        ) {
            SeekBar(
                position,
                duration,
                onSeekStart,
                onSeekTo,
                onSeekFinished,
                Modifier.fillMaxWidth().zIndex(1f),
                bufferedPosition = bufferedPosition,
                segments = segments,
            )
            // 按钮区自己一列:负间距只该吃进度槽下面那块空 padding,两行按钮之间没有那块空档,
            // 叠上去就是图标压图标。
            Column(modifier = Modifier.fillMaxWidth()) {
                if (stacked) {
                    // 靠右:它接着下面那一行的右端,而左端是播放键和读数。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        secondary()
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayPauseButton(isPlaying, onPlayPause, if (isFullscreen) 30.dp else 22.dp)
                    Text(
                        timeText,
                        style = timeStyle,
                        color = FixedColors.OnMedia,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // **这一行唯一带权重的孩子**,而且是读数而不是一个 Spacer:带权重的孩子
                        // 拿的是无权重的孩子量完之后剩下的那份,所以挤起来先让的是读数,不是排在
                        // 最后的全屏按钮 —— 上面那套宽度估算只决定给不给档名、要不要分行,估歪了
                        // 也不会把按钮顶到容器外面去。左对齐加 fill,右端的按钮照旧贴着边
                        // (两个权重对半分剩余宽度会让它停在中间偏右,壳里的全屏顶栏踩过)。
                        modifier = Modifier.weight(1f),
                    )
                    if (!stacked) secondary()
                    FullscreenButton(isFullscreen, onFullscreenToggle, if (isFullscreen) 26.dp else 22.dp)
                }
            }
        }
    }
}

/**
 * 倍速 / 画质 / 字幕 / 弹幕 / 听视频。抽出来只是为了让上面那个 Row 读得完,没有第二个调用方。
 *
 * **档名由调用方给,不在这里按"是不是全屏"算。** 给不给档名取决于这一行量出来装不装得下
 * (见 [PlayerControlBar]),而算那个的地方必须先知道档名有多长。
 */
@Composable
private fun SecondaryControls(
    qualities: List<QualityOption>,
    currentQuality: Int,
    subtitleTracks: List<SubtitleTrack>,
    currentSubtitleLan: String,
    danmakuEnabled: Boolean,
    speed: Float,
    speedLabel: String?,
    qualityLabel: String?,
    subtitleLabel: String?,
    iconSize: Dp,
    isFullscreen: Boolean,
    onSpeedChange: (Float) -> Unit,
    onQualityChange: (Int) -> Unit,
    onSubtitleTrackChange: (String) -> Unit,
    onDanmakuEnabledChange: (Boolean) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
) {
    SpeedButton(speed, speedLabel, onSpeedChange, onMenuOpenChange, iconSize)
    QualityButton(qualities, currentQuality, onQualityChange, onMenuOpenChange, qualityLabel, iconSize)
    SubtitleButton(
        subtitleTracks,
        currentSubtitleLan,
        onSubtitleTrackChange,
        onMenuOpenChange,
        subtitleLabel,
        iconSize,
    )
    // **弹幕开关只在全屏留在这条控制条上。** 内嵌时它在标签行右端、挨着"发弹幕"(见 VideoTabs),
    // 那里两个控件说的是同一件事:这条视频的弹幕看不看、发不发。全屏没有标签行,只能回到这里,
    // 发弹幕则不给 —— 横屏起键盘会铺掉大半个画面,而弹幕是发给眼前这一帧的。
    //
    // 直播间不受影响:它没有标签行,开关一直在控制条上(见 LiveRoomScreen)。
    if (isFullscreen) DanmakuButton(danmakuEnabled, onDanmakuEnabledChange, isFullscreen)
    // 听视频**不在这条控制条上**,它在简介页的动作栏里、挨着稍后再看(见 VideoTabs)。
    // 这条控制条上的东西回答的都是"这个播放器现在怎么放"(倍速、清晰度、字幕、弹幕、全屏),
    // 而听视频换掉的是整页的形态。放在这里时它混在五个播放参数中间,读不出这层区别。
}



@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit, iconSize: Dp) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(
                if (isPlaying) R.string.player_pause else R.string.player_play,
            ),
            tint = FixedColors.OnMedia,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun FullscreenButton(isFullscreen: Boolean, onClick: () -> Unit, iconSize: Dp) {
    val description = stringResource(
        if (isFullscreen) R.string.player_exit_fullscreen else R.string.player_fullscreen,
    )
    PlayerTooltip(description) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                contentDescription = description,
                tint = FixedColors.OnMedia,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun SpeedButton(
    speed: Float,
    label: String?,
    onSpeedChange: (Float) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    iconSize: Dp,
) {
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.player_speed)
    Box {
        PlayerTooltip(description) {
            ControlButton(
                expanded = expanded,
                onClick = { expanded = true; onMenuOpenChange(true) },
                label = label,
                icon = { tint ->
                    Icon(
                        Icons.Filled.Speed,
                        description,
                        tint = tint,
                        modifier = Modifier.size(iconSize),
                    )
                },
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false; onMenuOpenChange(false) },
        ) {
            SPEED_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(formatSpeed(option)) },
                    onClick = {
                        expanded = false
                        onMenuOpenChange(false)
                        onSpeedChange(option)
                    },
                    trailingIcon = if (option == speed) menuSelectedMark else null,
                    modifier = Modifier.selectedSemantics(option == speed),
                )
            }
        }
    }
}

@Composable
private fun QualityButton(
    qualities: List<QualityOption>,
    currentQuality: Int,
    onQualityChange: (Int) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    label: String?,
    iconSize: Dp,
) {
    if (qualities.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.player_quality)
    Box {
        PlayerTooltip(description) {
            ControlButton(
                expanded = expanded,
                onClick = { expanded = true; onMenuOpenChange(true) },
                label = label,
                icon = { tint ->
                    Icon(
                        Icons.Filled.HighQuality,
                        description,
                        tint = tint,
                        modifier = Modifier.size(iconSize),
                    )
                },
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false; onMenuOpenChange(false) },
        ) {
            qualities.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onMenuOpenChange(false)
                        onQualityChange(option.quality)
                    },
                    trailingIcon = if (option.quality == currentQuality) menuSelectedMark else null,
                    modifier = Modifier.selectedSemantics(option.quality == currentQuality),
                )
            }
        }
    }
}

/**
 * 字幕轨选择,和 [SpeedButton]/[QualityButton] 是同一套形状。没有轨(这条视频没有字幕、
 * 或者还没拉回来)时不出现——一个只有"关闭"一个选项的菜单是纯噪声。
 */
@Composable
private fun SubtitleButton(
    tracks: List<SubtitleTrack>,
    currentLan: String,
    onChange: (String) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    label: String?,
    iconSize: Dp,
) {
    if (tracks.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.player_subtitle)
    Box {
        PlayerTooltip(description) {
            ControlButton(
                expanded = expanded,
                onClick = { expanded = true; onMenuOpenChange(true) },
                label = label,
                icon = { tint ->
                    Icon(
                        Icons.Filled.Subtitles,
                        description,
                        tint = tint,
                        modifier = Modifier.size(iconSize),
                    )
                },
            )
        }
        // 菜单内容和听视频封面右上角那个按钮共用一份,见 SubtitleTrackMenu 上的注释。
        SubtitleTrackMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false; onMenuOpenChange(false) },
            tracks = tracks,
            currentLan = currentLan,
            onSelect = onChange,
        )
    }
}

