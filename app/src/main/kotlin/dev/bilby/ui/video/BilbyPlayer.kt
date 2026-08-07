package dev.bilby.ui.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import dev.bilby.R
import dev.bilby.formatDurationMillis
import dev.bilby.data.QualityOption
import dev.bilby.player.SubtitleCue
import dev.bilby.player.SubtitleTrack
import dev.bilby.player.cueAt
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.SeekBar
import dev.bilby.ui.components.SeekBarSegment
import dev.bilby.ui.components.SubtitleTrackMenu
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.danmaku.compose.Danmaku
import dev.danmaku.compose.DanmakuClock
import dev.danmaku.compose.DanmakuHost
import dev.danmaku.compose.DanmakuHostState
import dev.danmaku.compose.DanmakuRenderStyle
import dev.danmaku.compose.DanmakuTimeline
import dev.danmaku.compose.DanmakuTimelineConfig
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** 控件渐变最下面那一档。比 [FixedColors.PlayerControlScrim] 再深一点,兜住时间文字。 */
private val ControlScrimBottom = Color(0xB3000000)

/** 长按期间的临时倍速。 */
private const val FAST_FORWARD_SPEED = 3f

private const val CONTROLS_HIDE_DELAY_MILLIS = 3_000L

/** 双击两侧快进/快退的步长。 */
private const val DOUBLE_TAP_SEEK_MILLIS = 10_000L

/** 手势提示浮层停留多久。 */
private const val HINT_VISIBLE_MILLIS = 700L
private const val PROGRESS_REPORT_INTERVAL_MILLIS = 5_000L

/**
 * 播放器画面 + 控件。非全屏时被塞进 16:9 容器,全屏时铺满整屏,两种形态共用这一个 composable,
 * 靠 [isFullscreen] 切换布局与控件密度。
 *
 * 这里**不做**任何"下一个视频"的自动跳转(DESIGN 1.3/2.3),全屏下也不做。
 *
 * 播放器不归这里所有(DESIGN 2.4b:播放器归后台服务),所以这个 composable 只读状态、发命令,
 * 不 prepare、不 release。
 *
 * @param player 状态与控制的唯一入口,实际传进来的是连到播放服务的 MediaController。
 * @param surfacePlayer 只用来渲染画面。**MediaController 渲染不了画面**:Media3 不给它
 *   COMMAND_SET_VIDEO_SURFACE(Surface 是本地对象,递不到 session 那一侧),所以画面必须接
 *   在真的 ExoPlayer 上。两个参数指向的是同一份播放状态,不会打架。
 */
@OptIn(UnstableApi::class)
@Composable
fun BilbyPlayer(
    player: Player,
    surfacePlayer: Player?,
    qualities: List<QualityOption>,
    currentQuality: Int,
    onQualityChange: (Int) -> Unit,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onListen: () -> Unit,
    onReportProgress: (positionMillis: Long, durationMillis: Long) -> Unit,
    /** 会被自动跳过的片段。只染在进度条上,不参与交互,见 [SeekBar]。 */
    seekBarSegments: List<SeekBarSegment> = emptyList(),
    /** 这条(cid)有哪些字幕轨,含 AI 生成的。为空时控制条不出现字幕按钮。 */
    subtitleTracks: List<SubtitleTrack> = emptyList(),
    /** 选中轨的语言代码,空字符串是关(默认)。 */
    currentSubtitleLan: String = "",
    onSubtitleTrackChange: (String) -> Unit = {},
    /** 选中轨的正文,按 fromMillis 升序。 */
    subtitleCues: List<SubtitleCue> = emptyList(),
    /** 弹幕总开关,默认关。关闭时 [DanmakuHost] 整个不进组合——帧循环也就不存在,不是只是不画。 */
    danmakuEnabled: Boolean = false,
    onDanmakuEnabledChange: (Boolean) -> Unit = {},
    /** 已拉到的弹幕池,累计追加。时间轴在这里(Compose 层)编译——见类注释里对时间轴管理的说明。 */
    danmakuPool: List<Danmaku> = emptyList(),
    /** 弹幕池所属的 cid,换一条(切分 P、队列走到下一条)要整池重编,不是接着追加。 */
    danmakuCid: Long = 0L,
    /**
     * 播放器此刻装的是不是这一页的视频。**判据是 `AudioPlaybackService.state.current?.bvid`
     * 是否等于这一页的 bvid**,调用方(`VideoScreen`)算好再传进来。
     *
     * 播放器全 app 共用一份、跨页面存活(DESIGN 2.4b),点开新视频到它真正切过去之间有一段
     * 取流 + prepare 的窗口——这段时间里 `surfacePlayer` 渲染的还是上一条视频的最后几帧。
     * 为 false 时不挂 [PlayerSurface],改画 [placeholderCoverUrl];为 true 时正常渲染画面。
     * **不去暂停或销毁播放器**——那会打断后台连续播放,也违反"播放器归服务所有"。
     */
    matchesCurrentPage: Boolean = true,
    /** [matchesCurrentPage] 为 false 时画的占位封面,取这一页自己的封面,不是播放器正在放的那条。 */
    placeholderCoverUrl: String = "",
    modifier: Modifier = Modifier,
    /** 只在全屏时显示。竖屏下标题就在播放器正下方,再印一遍是多余的。 */
    title: String = "",
) {
    val reportProgress by rememberUpdatedState(onReportProgress)

    // 竖屏视频、4:3 老片都存在,写死 16:9 会把画面拉变形。容器比例由外面定,画面按真实比例
    // 居中,多出来的地方留黑边。
    var videoAspect by remember { mutableFloatStateOf(16f / 9f) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }

    // ---- 弹幕:时钟、编译输入、时间轴会话 ----
    //
    // 时钟包的是 player(MediaController),不是 surfacePlayer——弹幕只要播放位置/状态,
    // 不碰画面,跟 BilbyPlayer 别处"控制一律走 controller"的约定一致。
    val danmakuClock = remember(player, surfacePlayer) { PlayerDanmakuClock(controller = player, surfacePlayer = surfacePlayer) }
    val danmakuMeasurer = rememberTextMeasurer()
    val danmakuDensity = LocalDensity.current

    // 编译时用的画布宽高。初始 0f 不用先猜一个近似值——首帧布局出来后,宽靠 DanmakuHost 的
    // onScreenWidthMismatch 纠正,高靠下面挂在同一块 Modifier 上的 onSizeChanged 纠正
    // (DanmakuHost 本身不对外暴露高度,只在内部拿它换算描边/命中判定)。
    var danmakuScreenWidthPx by remember { mutableFloatStateOf(0f) }
    var danmakuCanvasHeightPx by remember { mutableFloatStateOf(0f) }

    // 字号是绝对量,不是画布的函数——它关乎眼睛和屏幕的距离,不关乎播放器窗口开了多大。
    // 内嵌和全屏各给一个基准(对齐 PiliPlus `danmaku_options.dart` 的默认档:15sp 基准,
    // 全屏 ×1.2 = 18sp——移动端 `danmakuFontScale` 默认 1.0、`danmakuFontScaleFS` 默认 1.2),
    // 全屏画面更大但观看距离没变,字可以略大,不是必须大。**反过来"轨道数定死、拿画布高度
    // 反推字号"是错的**:内嵌播放器只有几百像素高,除以一个固定轨道数会算出偏大的字号——
    // 这正是上一轮内嵌详情页字明显偏大的原因。
    val danmakuFontSizeSp = if (isFullscreen) DANMAKU_FONT_SIZE_SP_FULLSCREEN else DANMAKU_FONT_SIZE_SP_EMBEDDED
    val danmakuStyle = remember(danmakuFontSizeSp, danmakuDensity) {
        val fontSizePx = danmakuFontSizeSp * danmakuDensity.density * danmakuDensity.fontScale
        DanmakuRenderStyle(
            globalFontSizeSp = danmakuFontSizeSp,
            // 描边宽度跟着字号走(6%),[Stroke] 沿字形轮廓居中描,太粗会把细笔画糊住。
            strokeWidthPx = fontSizePx * DANMAKU_STROKE_TO_FONT_RATIO,
            // 行高 = 字号 × 1.6(对齐 PiliPlus `danmakuLineHeight` 默认值),留出上下行距。
            trackHeightPx = fontSizePx * DANMAKU_LINE_HEIGHT_RATIO,
        )
    }

    // 轨道数才是画布相关的量,方向和字号相反:用可用高度(画布高度 × 显示区域比例——弹幕
    // 最多占用画面上半部分,不铺满整个画面,对齐 PiliPlus `danmakuShowArea` 默认值 0.5)
    // 除以行高反推,画布越高塞得下的行数越多。画布还没布局出来时先给 1 条占位,不除零——
    // 和屏宽的 0f 兜底是同一个套路,首帧过后立刻被真实值取代。
    val danmakuScrollTrackCount = remember(danmakuCanvasHeightPx, danmakuStyle) {
        danmakuTrackCount(danmakuCanvasHeightPx, danmakuStyle.trackHeightPx)
    }
    // 顶/底固定弹幕不需要占用跟滚动弹幕一样多的行——按同一个可用高度算出来的滚动轨道数
    // 打个折,不再单独给内嵌/全屏各写一个常数(那正是这次要去掉的写死模式)。
    val danmakuFixedTrackCount = remember(danmakuScrollTrackCount) {
        (danmakuScrollTrackCount * DANMAKU_FIXED_TRACK_RATIO).roundToInt().coerceAtLeast(1)
    }

    // 编译时间轴与渲染 Canvas 必须用同一套字体/字号测量(DanmakuHost 类文档)。DanmakuHost
    // 内部渲染时是拿 style.baseTextStyle 叠一份 fontSize = globalFontSizeSp 再测量
    // (measureDanmaku 的写法),这里的 measureWidth 要复刻同一份叠加,不能只拿裸的
    // baseTextStyle 去测——那样量出来的宽度会是 TextStyle.Default 的隐式字号,跟实际画出来
    // 的宽度对不上,排布(尤其是速度)会跟着算错。
    val danmakuMeasureWidth = remember(danmakuMeasurer, danmakuStyle, danmakuFontSizeSp) {
        val measureStyle = danmakuStyle.baseTextStyle.copy(fontSize = danmakuFontSizeSp.sp)
        // 单独声明成带类型的 val 再返回,不要让 lambda 紧跟在上一行的函数调用后面——那样
        // Kotlin 会把它解析成上一行 copy(...) 的尾随 lambda 参数,而不是这个 remember 块的
        // 返回值(TextStyle.copy 恰好也有一个函数类型末位参数,编译器不会报"语法错误",
        // 只会报一个不明所以的类型不匹配,这个坑不写清楚很难一眼看出来)。
        val measure: (String) -> Float = { text -> danmakuMeasurer.measure(text = text, style = measureStyle).size.width.toFloat() }
        measure
    }
    var danmakuSession by remember { mutableStateOf<DanmakuSession?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                danmakuSession?.hostState?.notifyChanged()
            }

            // 覆盖 seek(拖拽松手、双击 ±10 秒、播完重播)——帧循环空闲挂起时只认这两个
            // 信号源之一,不追加会导致 seek 之后弹幕要等兜底轮询(最坏 500ms)才跟上。
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                danmakuSession?.hostState?.notifyChanged()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                danmakuSession?.hostState?.notifyChanged()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 结构性重建:cid、屏宽、轨道数、字号/描边(danmakuStyle)任一变化都要求整池重编——
    // measureWidth 的度量基准或轨道容量变了,旧时间轴里的宽度/速度/排布全部作废
    // (DanmakuHost 的契约、CLAUDE.md「换 cid 清空弹幕池」)。danmakuStyle 单独列进 key 是
    // 为了兜住一种巧合:轨道数是对画布高度取整算出来的,style 变了但恰好没有跨过取整边界时
    // 轨道数不变,如果只认轨道数,这次重建就会被跳过,而 measureWidth 其实已经换了字号——
    // 旧 session 会一直沿用旧字号测出来的宽度。用 LaunchedEffect 而不是 remember(key) 是
    // 因为下面追加逻辑发现乱序时也要走同一条重建路径,remember(key) 只能被 key 变化触发,
    // 做不到那条触发。
    LaunchedEffect(danmakuCid, danmakuScreenWidthPx, danmakuScrollTrackCount, danmakuFixedTrackCount, danmakuStyle) {
        danmakuSession = buildDanmakuSession(
            pool = danmakuPool,
            screenWidthPx = danmakuScreenWidthPx,
            scrollTrackCount = danmakuScrollTrackCount,
            fixedTrackCount = danmakuFixedTrackCount,
            clock = danmakuClock,
            measureWidth = danmakuMeasureWidth,
        )
    }

    // 池增长后追加新条目,不整池重编——DanmakuTimeline.append 就是为这个留的。分段按到达
    // 顺序通常是递增的,但用户倒着 seek 到一段还没拉过的更早时间时,新分段可能比已经排布
    // 的条目更早到达,那种情况下 append"只影响尾部"的前提不成立,这里退回整池重编,
    // 不强行拿乱序数据去追加。
    LaunchedEffect(danmakuSession, danmakuPool) {
        val session = danmakuSession ?: return@LaunchedEffect
        if (danmakuPool.size <= session.appliedCount) return@LaunchedEffect
        val delta = danmakuPool.subList(session.appliedCount, danmakuPool.size)
        if (delta.any { it.playTimeMillis < session.maxTimeMillis }) {
            danmakuSession = buildDanmakuSession(
                pool = danmakuPool,
                screenWidthPx = danmakuScreenWidthPx,
                scrollTrackCount = danmakuScrollTrackCount,
                fixedTrackCount = danmakuFixedTrackCount,
                clock = danmakuClock,
                measureWidth = danmakuMeasureWidth,
            )
        } else {
            delta.sortedBy { it.playTimeMillis }.forEach(session.timeline::append)
            session.appliedCount = danmakuPool.size
            session.maxTimeMillis = maxOf(session.maxTimeMillis, delta.maxOf { it.playTimeMillis })
            session.hostState.notifyChanged()
        }
    }

    // 播放时屏幕常亮,暂停时不常亮——条件看的是播放状态,不是"页面在前台"。
    //
    // 作用域只覆盖内嵌与全屏这两种画面可见的形态,不覆盖听视频:后者的全部意义就是
    // 息屏后台放(DESIGN 2.4b「息屏后台,不能后台就不成立」),常亮会把它变成一个耗电的
    // 笑话。这里不用另外传一个 listening 标志去关掉它——VideoScreen 在听视频分支上提前
    // return,BilbyPlayer 根本不会被组合,作用域天然就是对的。
    val view = LocalView.current
    DisposableEffect(isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }

    // 画面尺寸问渲染画面的那个播放器,不问 controller:controller 那边的 videoSize 要等 session
    // 同步,慢半拍就是画面先按 16:9 铺开再跳一下。
    DisposableEffect(surfacePlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoAspect = videoSize.aspectOr(videoAspect)
            }
        }
        // 接上来时流可能已经在播了(页面重建、或从听视频切回来),那一次 onVideoSizeChanged
        // 早就发过,只监听会一直停在默认的 16:9。
        surfacePlayer?.let { videoAspect = it.videoSize.aspectOr(videoAspect) }
        surfacePlayer?.addListener(listener)
        onDispose { surfacePlayer?.removeListener(listener) }
    }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragPosition by remember { mutableStateOf<Long?>(null) }
    var resumeAfterDrag by remember { mutableStateOf(false) }

    var userSpeed by remember { mutableFloatStateOf(1f) }
    var isFastForwarding by remember { mutableStateOf(false) }

    var controlsVisible by remember { mutableStateOf(true) }

    /**
     * 锁屏:横屏看视频时手容易碰到画面,一碰就暂停或快进。锁上之后除了解锁按钮,
     * 所有手势与控件都不响应。
     */
    var locked by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // 每次操作控件都让自动隐藏重新计时,靠这个计数把 LaunchedEffect 重启。
    var interactionNonce by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val brightness = rememberSystemBrightness(context)
    val volume = rememberMediaVolume(context)

    /** 正在进行的手势;横划与纵划在第一段位移里定死,见下面的 onDrag。 */
    var gesture by remember { mutableStateOf<PlayerGesture?>(null) }
    var dragStartX by remember { mutableFloatStateOf(0f) }

    /** 纵划时浮层要显示的百分比。 */
    var adjustValue by remember { mutableFloatStateOf(0f) }

    /** 双击 ±10 秒的短暂提示,非 null 时显示;正负决定文案。 */
    var seekNudgeMillis by remember { mutableStateOf<Long?>(null) }

    /** 亮度权限没给时的提示。跳设置页是离开应用的动作,不说一声会很突兀。 */
    var needsWriteSettings by remember { mutableStateOf(false) }

    LaunchedEffect(seekNudgeMillis) {
        if (seekNudgeMillis == null) return@LaunchedEffect
        delay(HINT_VISIBLE_MILLIS)
        seekNudgeMillis = null
    }

    LaunchedEffect(needsWriteSettings) {
        if (!needsWriteSettings) return@LaunchedEffect
        delay(HINT_VISIBLE_MILLIS)
        needsWriteSettings = false
    }

    /**
     * 定下这一次拖拽在做什么。返回 null 表示这次不做事(亮度没权限)。
     *
     * 亮度是设备级设置,要 `WRITE_SETTINGS`,而那个权限只能跳系统设置页去拨
     * (见 [SystemBrightness])。第一次划到它时把人送过去,并留一句提示。
     */
    val startGesture: (Boolean, Long) -> PlayerGesture? = start@{ horizontal, playerPosition ->
        if (horizontal) return@start PlayerGesture.Seek(playerPosition)
        val onLeftHalf = dragStartX < 0.5f
        if (onLeftHalf) {
            if (!brightness.canWrite()) {
                needsWriteSettings = true
                brightness.requestPermission()
                return@start null
            }
            PlayerGesture.Adjust(VerticalAdjust.Brightness, brightness.current())
        } else {
            PlayerGesture.Adjust(VerticalAdjust.Volume, volume.current())
        }
    }

    LaunchedEffect(player) {
        while (true) {
            if (dragPosition == null) position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            delay(500)
        }
    }

    // 进程被杀不会走 onDispose,所以播放中也定期回传一次进度。服务端那份是续播的唯一来源,
    // 只在退出时报一次的话,被杀掉的那次观看等于没发生过。
    LaunchedEffect(player) {
        while (true) {
            delay(PROGRESS_REPORT_INTERVAL_MILLIS)
            if (player.isPlaying) reportProgress(player.currentPosition, player.duration.coerceAtLeast(0))
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, dragPosition, menuOpen, interactionNonce) {
        // 暂停时控件常驻:此时用户多半正要点什么,把它藏掉只会逼人再点一次。
        if (controlsVisible && isPlaying && dragPosition == null && !menuOpen) {
            delay(CONTROLS_HIDE_DELAY_MILLIS)
            controlsVisible = false
        }
    }

    // 传布尔而不是比例本身:比例是浮点,解码过程里会有细微抖动,直接当 DisposableEffect
    // 的 key 会让全屏反复重设方向。
    FullscreenEffect(isFullscreen, isPortraitVideo = videoAspect < 1f)

    BackHandler(enabled = isFullscreen) { onFullscreenChange(false) }

    val displayPosition = dragPosition ?: position

    // 二分查找,不逐帧线性扫:见 SubtitleCue.kt 上的注释。落在两句之间的空档里时是 null,
    // 什么都不画——句间停顿本来就没有字幕在念。
    val currentCue = remember(subtitleCues, displayPosition) { subtitleCues.cueAt(displayPosition) }

    Box(modifier = modifier.background(Color.Black)) {
        // 播放器装的是不是这一页的视频,决定挂画面还是画占位——不判断的话,点开新视频到
        // 播放器真正切过去之间那段取流 + prepare 的窗口里,这里会一直显示上一条视频的残留帧
        // (surfacePlayer 是跨页面存活的同一个播放器,这段时间它还没换流)。占位保持 16:9,
        // 不跳布局;切回真画面**不做转场**——它和取流、prepare、codec 初始化撞在同一瞬间,
        // 加动效只会让"卡"更明显,VideoScreen.kt 里"自动换页不做转场"是同一条道理。
        if (matchesCurrentPage && surfacePlayer != null) {
            PlayerSurface(
                player = surfacePlayer,
                modifier = Modifier.align(Alignment.Center).aspectRatio(videoAspect),
            )
        } else if (placeholderCoverUrl.isNotEmpty()) {
            BiliAsyncImage(
                url = placeholderCoverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 封面也拿不到(详情还没回来)时退化成纯黑——Box 本身的背景已经是黑的,这里不用
        // 再多画一层。

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(player, locked) {
                    if (locked) {
                        // 锁上时只留"点一下把解锁按钮唤出来",其余手势一概不接。
                        detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                        return@pointerInput
                    }
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            interactionNonce++
                        },
                        // 双击按落点分三段:两侧各三分之一是 ±10 秒,中间那段仍是播放/暂停。
                        //
                        // 中间保留下来是因为双击暂停本来就在,直接换掉等于拿走一个已有的常用
                        // 操作;而三分法是 YouTube 立起来的惯例,两侧那两块也正是横屏握持时
                        // 拇指自然落到的位置。
                        onDoubleTap = { offset ->
                            val third = size.width / 3f
                            when {
                                offset.x < third -> nudgeSeek(player, -DOUBLE_TAP_SEEK_MILLIS)
                                    .also { seekNudgeMillis = -DOUBLE_TAP_SEEK_MILLIS }

                                offset.x > size.width - third -> nudgeSeek(player, DOUBLE_TAP_SEEK_MILLIS)
                                    .also { seekNudgeMillis = DOUBLE_TAP_SEEK_MILLIS }

                                player.isPlaying -> player.pause()
                                // 播完之后位置停在末尾,直接 play() 不会有反应,应有行为是重播。
                                player.playbackState == Player.STATE_ENDED -> {
                                    player.seekTo(0)
                                    player.play()
                                }

                                else -> player.play()
                            }
                            interactionNonce++
                        },
                        onLongPress = {
                            isFastForwarding = true
                            player.setPlaybackSpeed(FAST_FORWARD_SPEED)
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (isFastForwarding) {
                                isFastForwarding = false
                                // 恢复到用户选的倍速而不是 1.0:用户可能本来就在 1.5x 看,
                                // 长按只是临时叠加,松手不该把他的设置抹掉。
                                player.setPlaybackSpeed(userSpeed)
                            }
                        },
                    )
                }
                // 拖拽单独一个 pointerInput:和上面的点按检测并列而不是塞进同一个块。
                // 两者天然互斥 —— 位移超过 touch slop 之后点按检测就不会触发了。
                .pointerInput(player, locked, duration) {
                    if (locked) return@pointerInput
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val height = size.height.toFloat().coerceAtLeast(1f)
                    var accumulated = Offset.Zero

                    detectDragGestures(
                        onDragStart = { start ->
                            accumulated = Offset.Zero
                            dragStartX = start.x / width
                        },
                        onDragEnd = {
                            // 拖拽期间只动本地位置,松手才真 seek:每帧 seek 会让播放器不停丢
                            // 缓冲重新起播,表现为拖不动。和进度条的处理是同一套。
                            if (gesture is PlayerGesture.Seek) {
                                dragPosition?.let { target ->
                                    player.seekTo(target)
                                    position = target
                                    reportProgress(target, player.duration.coerceAtLeast(0))
                                }
                            }
                            gesture = null
                            dragPosition = null
                            interactionNonce++
                        },
                        onDragCancel = {
                            gesture = null
                            dragPosition = null
                        },
                    ) { change, delta ->
                        change.consume()
                        accumulated += delta

                        // 方向在第一段位移里定下,之后不再改判:不锁轴的话,横划途中手指
                        // 稍微飘一点就会跳去改音量。
                        val current = gesture ?: startGesture(
                            abs(accumulated.x) >= abs(accumulated.y),
                            player.currentPosition,
                        )?.also { gesture = it } ?: return@detectDragGestures

                        when (current) {
                            is PlayerGesture.Seek -> {
                                // 整屏宽对应多长:按时长的四分之一取,夹在 1 到 5 分钟之间。
                                // 定长(比如恒定 2 分钟)在长视频里要划很多次;按整段时长又会让
                                // 长视频一格几十秒,微调不了。
                                val span = (duration / 4).coerceIn(60_000L, 300_000L)
                                val target = current.startPositionMillis +
                                    (accumulated.x / width * span).toLong()
                                dragPosition = target.coerceIn(0L, duration.coerceAtLeast(0L))
                            }

                            is PlayerGesture.Adjust -> {
                                // 向上是变大,所以减去 y 的位移(屏幕坐标向下为正)。
                                val value = (current.startValue - accumulated.y / height)
                                    .coerceIn(0f, 1f)
                                adjustValue = value
                                when (current.kind) {
                                    VerticalAdjust.Brightness -> brightness.set(value)
                                    VerticalAdjust.Volume -> volume.set(value)
                                }
                            }
                        }
                    }
                },
        )

        // 弹幕层:压在画面与手势层之上、控制条(下面的 AnimatedVisibility 那几块)之下——
        // 声明顺序即 z 序。**关闭时整个不进组合**,不是画了个空——DanmakuHostState 的帧循环
        // 由 LaunchedEffect(state) 驱动,不进组合就没有这个协程,不会白烧一条 vsync 循环。
        // 没有 pointerInput,不拦截手势,下面的双击/拖拽照常命中。
        if (danmakuEnabled) {
            danmakuSession?.let { session ->
                DanmakuHost(
                    state = session.hostState,
                    style = danmakuStyle,
                    onScreenWidthMismatch = { actualWidthPx -> danmakuScreenWidthPx = actualWidthPx },
                    // 只留竖向边距,不留横向:上边缘贴视频自带角标/台标,下边缘和字幕(贴底画,
                    // 见下面 currentCue 那块)、控制条抢位置,留白让轨道从两边缩进来。下边比
                    // 上边多留——字幕在那儿。横向不能留:横向 padding 会让画布宽度小于编译时
                    // 用的 screenWidthPx,直接触发 onScreenWidthMismatch 反复重编;滚动弹幕就该
                    // 从整个宽度的边缘进出,不该在留白处提前截断。
                    //
                    // clipToBounds:没有裁剪的话,靠近顶/底轨道的弹幕会画出这块 padding 之外
                    // (内嵌形态下就是画到下面的简介/评论区上),表现为"边上有奇怪的东西"。
                    //
                    // onSizeChanged 放在 padding 之后:量的是留白之后、Canvas 实际拿到的高度,
                    // 和 DanmakuHost 内部真正用来画的尺寸对齐,不是整个播放器容器的高度。
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = Spacing.Cozy, bottom = Spacing.Loose)
                        .clipToBounds()
                        .onSizeChanged { size -> danmakuCanvasHeightPx = size.height.toFloat() },
                )
            }
        }

        // 纵划的浮层。和快进提示一样贴在正中偏上,不压住底部控件。
        (gesture as? PlayerGesture.Adjust)?.let { adjust ->
            Overlay(modifier = Modifier.align(Alignment.Center)) {
                Text(
                    stringResource(
                        when (adjust.kind) {
                            VerticalAdjust.Brightness -> R.string.player_brightness
                            VerticalAdjust.Volume -> R.string.player_volume
                        },
                        (adjustValue * 100).roundToInt(),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = FixedColors.OnMedia,
                )
            }
        }

        seekNudgeMillis?.let { delta ->
            Overlay(modifier = Modifier.align(Alignment.Center)) {
                Text(
                    stringResource(
                        if (delta >= 0) R.string.player_seek_forward else R.string.player_seek_backward,
                        abs(delta) / 1000,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = FixedColors.OnMedia,
                )
            }
        }

        if (needsWriteSettings) {
            Overlay(modifier = Modifier.align(Alignment.Center)) {
                Text(
                    stringResource(R.string.player_need_write_settings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FixedColors.OnMedia,
                )
            }
        }

        if (isFastForwarding) {
            Overlay(modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.FastForward,
                        contentDescription = null,
                        tint = FixedColors.OnMedia,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "  " + stringResource(
                            R.string.player_fast_forwarding,
                            formatSpeed(FAST_FORWARD_SPEED),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = FixedColors.OnMedia,
                    )
                }
            }
        }

        if (dragPosition != null) {
            Overlay(modifier = Modifier.align(Alignment.Center)) {
                Text(
                    "${formatDurationMillis(displayPosition)} / ${formatDurationMillis(duration)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = FixedColors.OnMedia,
                )
            }
        }

        // 字幕层。**不走 Media3 的 SubtitleConfiguration**:那要求先把 JSON 转成 VTT 再挂到
        // MediaItem 上,而这里的 MediaItem 是 AudioPlaybackService 拼的 DASH 合并源,插字幕轨
        // 要动到播放器所有权那一层。还有个更硬的理由:听视频模式下 VideoScreen.kt 那句
        // `setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)` 会把视频轨连带的字幕轨一起关掉——
        // 走播放器原生字幕的话,切到听视频字幕就会跟着消失,而这里的字幕来自独立的接口,不受
        // 视频轨开关影响。贴底而不是压中间:不挡画面主体,也不常驻遮住控制条位置——控件常驻
        // 显示时字幕整体上移让开,和进度条那句浮层的处理是同一个思路。
        currentCue?.let { cue ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (controlsVisible && !locked) 88.dp else 24.dp)
                    .padding(horizontal = 24.dp),
            ) {
                Text(
                    cue.text,
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

        // 全屏顶栏。全屏下没有别的东西说明"在看什么"和"怎么退出":系统栏是隐藏的,
        // 返回手势在锁屏态下也被吃掉了。竖屏不显示,那里标题就在播放器下面第一行。
        AnimatedVisibility(
            visible = isFullscreen && controlsVisible && !locked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(ControlScrimBottom, Color.Transparent)),
                    )
                    .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.systemBars))
                    .padding(end = 16.dp, bottom = 16.dp),
            ) {
                IconButton(onClick = { onFullscreenChange(false) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.player_exit_fullscreen),
                        tint = FixedColors.OnMedia,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = FixedColors.OnMedia,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 锁按钮:锁上后它是唯一还能点的东西。放右侧——横屏握持时右手拇指够得到,
        // 而左侧那个位置和退出全屏的返回箭头在同一边,容易误按成退出。
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        ) {
            IconButton(onClick = { locked = !locked }) {
                Icon(
                    imageVector = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = stringResource(
                        if (locked) R.string.player_unlock else R.string.player_lock,
                    ),
                    tint = FixedColors.OnMedia,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerControlBar(
                segments = seekBarSegments,
                isPlaying = isPlaying,
                position = displayPosition,
                duration = duration,
                speed = userSpeed,
                qualities = qualities,
                currentQuality = currentQuality,
                isFullscreen = isFullscreen,
                onPlayPause = {
                    when {
                        player.isPlaying -> player.pause()
                        // 播完之后位置停在末尾,直接 play() 无事发生(或在某些实现上直接抛)。
                        // 应有行为是从头再播一遍。
                        player.playbackState == Player.STATE_ENDED -> {
                            player.seekTo(0)
                            player.play()
                        }

                        else -> player.play()
                    }
                    interactionNonce++
                },
                onSeekStart = {
                    resumeAfterDrag = player.isPlaying
                    player.pause()
                },
                // 拖拽中只更新本地位置:每帧 seek 会让播放器不停丢缓冲重新起播,
                // 表现为拖不动。真正的 seek 留到松手时一次完成。
                onSeekTo = { dragPosition = it },
                onSeekFinished = {
                    dragPosition?.let { target ->
                        player.seekTo(target)
                        position = target
                        dragPosition = null
                        if (resumeAfterDrag) player.play()
                        reportProgress(target, player.duration.coerceAtLeast(0))
                    }
                    interactionNonce++
                },
                onSpeedChange = {
                    userSpeed = it
                    if (!isFastForwarding) player.setPlaybackSpeed(it)
                    interactionNonce++
                },
                onQualityChange = {
                    onQualityChange(it)
                    interactionNonce++
                },
                subtitleTracks = subtitleTracks,
                currentSubtitleLan = currentSubtitleLan,
                onSubtitleTrackChange = {
                    onSubtitleTrackChange(it)
                    interactionNonce++
                },
                onFullscreenToggle = {
                    onFullscreenChange(!isFullscreen)
                    interactionNonce++
                },
                onListen = onListen,
                onMenuOpenChange = { menuOpen = it },
                danmakuEnabled = danmakuEnabled,
                onDanmakuEnabledChange = {
                    onDanmakuEnabledChange(it)
                    interactionNonce++
                },
            )
        }
    }
}

@Composable
private fun PlayerControlBar(
    segments: List<SeekBarSegment>,
    isPlaying: Boolean,
    position: Long,
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
    onListen: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    danmakuEnabled: Boolean,
    onDanmakuEnabledChange: (Boolean) -> Unit,
) {
    val safeInsets = WindowInsets.displayCutout.union(WindowInsets.systemBars)
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
        // 全屏时系统栏被藏了,但挖孔和手势条的位置照旧,控件贴边会被切掉一半。
        .then(if (isFullscreen) Modifier.windowInsetsPadding(safeInsets) else Modifier)
        .padding(
            start = if (isFullscreen) 16.dp else 8.dp,
            end = if (isFullscreen) 16.dp else 8.dp,
            top = 16.dp,
            bottom = if (isFullscreen) 8.dp else 0.dp,
        )

    // 进度条独占一行:挤在按钮行里只剩几十 dp 可拖,而拖拽是这里最主要的操作。
    Column(modifier = container) {
        SeekBar(position, duration, onSeekStart, onSeekTo, onSeekFinished, Modifier.fillMaxWidth(), segments = segments)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayPauseButton(isPlaying, onPlayPause, if (isFullscreen) 30.dp else 22.dp)
            Text(
                "${formatDurationMillis(position)} / ${formatDurationMillis(duration)}",
                style = if (isFullscreen) MaterialTheme.typography.labelLarge
                else MaterialTheme.typography.labelSmall,
                color = FixedColors.OnMedia,
            )
            Spacer(Modifier.weight(1f))
            SpeedButton(speed, onSpeedChange, onMenuOpenChange, isFullscreen)
            QualityButton(qualities, currentQuality, onQualityChange, onMenuOpenChange, isFullscreen)
            SubtitleButton(subtitleTracks, currentSubtitleLan, onSubtitleTrackChange, onMenuOpenChange, isFullscreen)
            DanmakuButton(danmakuEnabled, onDanmakuEnabledChange, isFullscreen)
            // 听视频和全屏是同一类东西:都是播放页内的状态,都不换播放器、不交接进度。
            // 同构的两个动作放在一起,以前它在下面的简介区,和一堆内容动作混着。
            IconButton(onClick = onListen) {
                Icon(
                    Icons.Filled.Headphones,
                    contentDescription = stringResource(R.string.player_listen),
                    tint = FixedColors.OnMedia,
                    modifier = Modifier.size(if (isFullscreen) 26.dp else 22.dp),
                )
            }
            FullscreenButton(isFullscreen, onFullscreenToggle, if (isFullscreen) 26.dp else 22.dp)
        }
    }
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
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
            contentDescription = stringResource(
                if (isFullscreen) R.string.player_exit_fullscreen else R.string.player_fullscreen,
            ),
            tint = FixedColors.OnMedia,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun SpeedButton(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    isFullscreen: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ControlButton(
            expanded = expanded,
            onClick = { expanded = true; onMenuOpenChange(true) },
            label = if (speed == 1f) null else formatSpeed(speed),
            icon = { tint ->
                Icon(
                    Icons.Filled.Speed,
                    stringResource(R.string.player_speed),
                    tint = tint,
                    modifier = Modifier.size(if (isFullscreen) 22.dp else 18.dp),
                )
            },
        )
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
                    trailingIcon = if (option == speed) selectedMark else null,
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
    isFullscreen: Boolean,
) {
    if (qualities.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = qualities.firstOrNull { it.quality == currentQuality }?.label
    Box {
        ControlButton(
            expanded = expanded,
            onClick = { expanded = true; onMenuOpenChange(true) },
            label = if (isFullscreen) currentLabel else null,
            icon = { tint ->
                Icon(
                    Icons.Filled.HighQuality,
                    stringResource(R.string.player_quality),
                    tint = tint,
                    modifier = Modifier.size(if (isFullscreen) 22.dp else 18.dp),
                )
            },
        )
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
                    trailingIcon = if (option.quality == currentQuality) selectedMark else null,
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
    isFullscreen: Boolean,
) {
    if (tracks.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = tracks.firstOrNull { it.lan == currentLan }?.displayName
    Box {
        ControlButton(
            expanded = expanded,
            onClick = { expanded = true; onMenuOpenChange(true) },
            label = if (isFullscreen) currentLabel else null,
            icon = { tint ->
                Icon(
                    Icons.Filled.Subtitles,
                    stringResource(R.string.player_subtitle),
                    tint = tint,
                    modifier = Modifier.size(if (isFullscreen) 22.dp else 18.dp),
                )
            },
        )
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

/**
 * 弹幕开关。和 [SpeedButton]/[QualityButton]/[SubtitleButton] 同一套形状,但不弹菜单——
 * 只有开/关两态,点一下直接切换。借用 [ControlButton] 的 `expanded` 参数表达"开着"这个
 * 高亮态,不是真的有下拉菜单要展开。
 */
@Composable
private fun DanmakuButton(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isFullscreen: Boolean,
) {
    ControlButton(
        expanded = enabled,
        onClick = { onEnabledChange(!enabled) },
        label = null,
        icon = { tint ->
            Icon(
                Icons.AutoMirrored.Filled.Comment,
                stringResource(R.string.player_danmaku),
                tint = tint,
                modifier = Modifier.size(if (isFullscreen) 22.dp else 18.dp),
            )
        },
    )
}

/** 图标按钮,可选地在图标右边挂一小段文字(当前倍速、当前清晰度)。 */
@Composable
private fun ControlButton(
    expanded: Boolean,
    onClick: () -> Unit,
    label: String?,
    icon: @Composable (Color) -> Unit,
) {
    val tint = if (expanded) MaterialTheme.colorScheme.primary else FixedColors.OnMedia
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 8.dp),
    ) {
        icon(tint)
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                // **不设宽度上限**:这里的标签是画质档名("1080P60"、"1080P 高码率"),
                // 截断之后两个档看起来一模一样,那正是这个标签唯一要回答的问题。
                // 只在全屏显示(内嵌时 label 传 null),横屏有的是宽度,不会挤掉别的控件。
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

private val selectedMark: @Composable () -> Unit = {
    Text("·", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Overlay(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(FixedColors.ScrimOnMedia)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        content()
    }
}

/**
 * 全屏的两件事:Activity 转到画面朝向 + 隐藏系统栏。两者都是 Activity 级的全局状态,离开
 * 这个 composable 必须还原,否则退到列表页还卡在横屏。
 *
 * **朝向跟着画面走,不是一律横屏。** 竖屏视频转横屏之后画面只能缩到中间一条,两侧全是黑边,
 * 等于全屏把可视面积改小了;竖屏视频的全屏就该竖着占满。用 SENSOR_* 而不是 USER_*,
 * 是为了让人仍能把设备翻过来(倒持、左右手)。
 */
@Composable
private fun FullscreenEffect(isFullscreen: Boolean, isPortraitVideo: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(isFullscreen, isPortraitVideo) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            activity.requestedOrientation =
                if (isPortraitVideo) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insets.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * LocalContext 拿到的不保证是 Activity:主题、配置覆写都会套一层 ContextWrapper,
 * 层数不固定,所以只能一路解包到底。
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 尺寸未知时(还没解码出第一帧)返回 [fallback],别把画面压成 0 宽。 */
private fun VideoSize.aspectOr(fallback: Float): Float =
    if (width > 0 && height > 0) width * pixelWidthHeightRatio / height else fallback

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"


/**
 * 双击两侧的定量跳转。夹在 [0, duration] 内 —— 末尾再往前跳会停在结尾并触发播完逻辑,
 * 那不是用户按这一下的意思。时长未知(还没解析出来)时不夹上界。
 */
private fun nudgeSeek(player: Player, deltaMillis: Long) {
    val duration = player.duration.coerceAtLeast(0)
    val target = player.currentPosition + deltaMillis
    player.seekTo(if (duration > 0) target.coerceIn(0L, duration) else target.coerceAtLeast(0L))
}

// ---- 弹幕:时钟适配、时间轴会话 ----

/**
 * 把 Media3 的 [Player] 包成 [DanmakuClock]。**倍速跟着播放时钟**:2 倍速时 [playbackSpeed]
 * 报 2,弹幕也跟着 2 倍速滚,排布本身与倍速无关,暂停/seek/变速的同步因此全部免费
 * (DanmakuClock 类文档)。三个属性都是轮询读取,不需要是 Compose State。
 *
 * **三个属性优先读 [surfacePlayer],为空时才退回 [controller]。** [surfacePlayer] 是
 * `AudioPlaybackService.currentPlayer`——同进程持有的那个 ExoPlayer 本体,`VideoScreen.kt`
 * 现在只拿它挂画面(`PlayerSurface`)。这里额外把它接给弹幕的位置读数用,理由是绕开
 * [controller](MediaController)一个真实的实现缺陷:MediaController 是 session 的跨进程
 * 代理,`getCurrentPosition()` 自己在本地做"锚点位置 + 经过时间 × 倍速"外推
 * (media3-session:1.10.1,`MediaUtils.getUpdatedCurrentPositionMs`),而 `setPlaybackSpeed()`
 * 本地立刻 masking 新倍速、却不同步刷新那个锚点——锚点要等 session 跨进程回包才更新,
 * 倍速刚变的这段窗口里外推值会用新倍速乘上"旧锚点到现在的整段时间"而跳过头,回包落地后
 * 又被纠正回去,表现为弹幕集体抖一下(根因详见 `DanmakuHostState.kt` 删掉的
 * `PositionInterpolator` 那段历史注释)。`surfacePlayer` 直接读渲染器的真实进度,不存在
 * masking,也没有 IPC 延迟,天然满足 [DanmakuClock] 文档要求的"逐帧连续"。
 *
 * **读位置不是发命令,不违反"控制一律走 controller,不碰 currentPlayer"那条约定**
 * (`VideoScreen.kt`)——那条约束的是控制命令:绕开 session 直接对 `currentPlayer` 下命令,
 * 会让通知栏、耳机线控、界面三方对播放状态各说各话。这里只读不写,不改变任何一方的状态,
 * 不在那条约束覆盖的范围内。
 *
 * `surfacePlayer` 为 null(服务还没绑定,或者听视频切回来的短暂窗口)时退回 [controller]——
 * 行为退化成上面说的那条有缺陷的路径(抖一下),不是崩溃或空白。
 */
private class PlayerDanmakuClock(
    private val controller: Player,
    private val surfacePlayer: Player?,
) : DanmakuClock {
    private val source: Player get() = surfacePlayer ?: controller
    override val positionMillis: Long get() = source.currentPosition
    override val isPlaying: Boolean get() = source.isPlaying
    override val playbackSpeed: Float get() = source.playbackParameters.speed
}

/** 一次"整池重编"的产出:时间轴 + host 状态,连同弹幕池追加到第几条的游标。 */
private class DanmakuSession(
    val timeline: DanmakuTimeline,
    val hostState: DanmakuHostState,
    var appliedCount: Int,
    var maxTimeMillis: Long,
)

private fun buildDanmakuSession(
    pool: List<Danmaku>,
    screenWidthPx: Float,
    scrollTrackCount: Int,
    fixedTrackCount: Int,
    clock: DanmakuClock,
    measureWidth: (String) -> Float,
): DanmakuSession {
    val timeline = DanmakuTimeline.compile(
        pool,
        danmakuTimelineConfig(screenWidthPx, scrollTrackCount, fixedTrackCount),
        measureWidth,
    )
    return DanmakuSession(
        timeline = timeline,
        hostState = DanmakuHostState(clock, timeline),
        appliedCount = pool.size,
        maxTimeMillis = pool.maxOfOrNull { it.playTimeMillis } ?: Long.MIN_VALUE,
    )
}

/**
 * 轨道数由调用方按"画布高度 × 显示区域比例 ÷ 行高"([danmakuTrackCount])算好再传进来——
 * 这个函数本身不知道画布多高,只负责把算好的数字和屏宽拼成时间轴需要的配置。
 */
private fun danmakuTimelineConfig(
    screenWidthPx: Float,
    scrollTrackCount: Int,
    fixedTrackCount: Int,
): DanmakuTimelineConfig {
    // baseSpeedPxPerMillis 按"多少秒滚完一屏"反推,不写死一个像素/毫秒常数——那个数只对
    // 某个假设宽度的屏幕成立,换一台宽度不同的设备,"几秒滚完一屏"这个用户能直接感知的量
    // 就会跟着跑偏。screenWidthPx 是 0f(还没布局出来)时算出来的速度也是 0,无害——
    // 时间轴很快会因为 onScreenWidthMismatch 触发的重建而重新算过。
    val baseSpeed = screenWidthPx / (DANMAKU_CROSS_SCREEN_SECONDS * 1000f)
    return DanmakuTimelineConfig(
        screenWidthPx = screenWidthPx,
        scrollTrackCount = scrollTrackCount,
        topTrackCount = fixedTrackCount,
        bottomTrackCount = fixedTrackCount,
        baseSpeedPxPerMillis = baseSpeed,
    )
}

/**
 * 画布高度 -> 轨道数,方向和字号相反(字号是绝对量,轨道数才该随画布变)。画布还没布局出来
 * (`canvasHeightPx <= 0f`)或者 `trackHeightPx` 尚不合法时先给 1 条占位,不除零——首帧过后
 * 立刻被真实值取代,和屏宽/画布高度的 0f 兜底是同一个套路。
 */
private fun danmakuTrackCount(canvasHeightPx: Float, trackHeightPx: Float): Int {
    if (canvasHeightPx <= 0f || trackHeightPx <= 0f) return 1
    val availableHeightPx = canvasHeightPx * DANMAKU_SHOW_AREA_RATIO
    return (availableHeightPx / trackHeightPx).toInt().coerceAtLeast(1)
}

/**
 * 字号基准(sp),内嵌/全屏各一档。对齐 PiliPlus `danmaku_options.dart` 的默认档:
 * 15sp 基准 ×(移动端 `danmakuFontScale` 默认 1.0 / `danmakuFontScaleFS` 默认 1.2)。
 * 字号是绝对量,不随画布高度变——全屏画面更大但观看距离没变,字略大是审美选择,不是必须。
 */
private const val DANMAKU_FONT_SIZE_SP_EMBEDDED = 15f
private const val DANMAKU_FONT_SIZE_SP_FULLSCREEN = 18f

/** 行高相对字号的倍数,对齐 PiliPlus `danmakuLineHeight` 默认值。 */
private const val DANMAKU_LINE_HEIGHT_RATIO = 1.6f

/**
 * 弹幕最多占用画面高度的比例,对齐 PiliPlus `danmakuShowArea` 默认值:只占上半部分,
 * 不铺满整个画面,给下方的字幕、简介留出空间。以后做"弹幕显示区域"设置项时它就是那个旋钮。
 */
private const val DANMAKU_SHOW_AREA_RATIO = 0.5f

/** 顶/底固定弹幕不需要占用跟滚动弹幕一样多的行,按滚动轨道数打个折。 */
private const val DANMAKU_FIXED_TRACK_RATIO = 0.3f

/**
 * 描边宽度相对字号的比例。[androidx.compose.ui.graphics.drawscope.Stroke] 沿字形轮廓居中描,
 * 一半会吃进字里——调太粗会把细笔画糊住,6% 是能看清描边又不糊字的经验值。
 */
private const val DANMAKU_STROKE_TO_FONT_RATIO = 0.06f

/** 基准弹幕(参照文本"哈哈哈哈")滚过一屏所需的秒数,贴近 B 站默认手感。 */
private const val DANMAKU_CROSS_SCREEN_SECONDS = 6f
