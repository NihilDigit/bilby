package dev.bilby.ui.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.formatDurationMillis
import dev.bilby.data.QualityOption
import dev.bilby.data.SettingsStore
import dev.bilby.player.SubtitleCue
import dev.bilby.player.SubtitleTrack
import dev.bilby.player.cueAt
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.SeekBar
import dev.bilby.ui.components.SeekBarSegment
import dev.bilby.ui.components.SubtitleTrackMenu
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.data.DanmakuPrefs
import dev.danmaku.compose.Danmaku
import dev.danmaku.compose.SpecialDanmakuHostState
import dev.danmaku.compose.SpecialDanmakuHost
import dev.danmaku.compose.SpecialDanmaku
import dev.danmaku.compose.DanmakuClock
import dev.danmaku.compose.DanmakuCompiler
import dev.danmaku.compose.DanmakuDensity
import dev.danmaku.compose.DanmakuFrameRateCap
import dev.danmaku.compose.DanmakuHost
import dev.danmaku.compose.DanmakuHostState
import dev.danmaku.compose.DanmakuLayoutConfig
import dev.danmaku.compose.DanmakuRenderStyle
import dev.danmaku.compose.DanmakuTextSize
import dev.danmaku.compose.DanmakuViewport
import dev.danmaku.compose.ProcessingReport
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
    /**
     * 弹幕设置整体传入,不拆成一条一条的平行参数——理由见 [dev.bilby.ui.video.VideoViewModel]
     * 的 `danmakuPrefs`。其中 `enabled` 是总开关,关闭时 [DanmakuHost] 整个不进组合,帧循环
     * 也就不存在,不是只是不画;`scrollShowArea` 只约束滚动与顶部弹幕,底部弹幕照旧贴画面
     * 底沿(理由见 [DanmakuViewport])。
     */
    danmakuPrefs: DanmakuPrefs = DanmakuPrefs(),
    onDanmakuEnabledChange: (Boolean) -> Unit = {},
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
    // 名字带 Display 是为了和参数里的"同屏密度"(danmakuDensity)分开,这里是屏幕像素密度。
    val danmakuDisplayDensity = LocalDensity.current

    // 排布时用的画布宽高。初始 0f 不用先猜一个近似值——首帧布局出来后由 DanmakuHost 的
    // onCanvasSizeMismatch 一次纠正两个值。宽高都进了排布(高度决定视口和轨道数),所以这里
    // 不再自己挂一个 onSizeChanged 去量高:两处各量一份就会有两份可能对不齐的尺寸。
    var danmakuCanvasWidthPx by remember { mutableFloatStateOf(0f) }
    var danmakuCanvasHeightPx by remember { mutableFloatStateOf(0f) }

    // 字号是绝对量,不是画布的函数——它关乎眼睛和屏幕的距离,不关乎播放器窗口开了多大。
    // 内嵌和全屏各给一个基准(对齐 PiliPlus `danmaku_options.dart` 的默认档:15sp 基准,
    // 全屏 ×1.2 = 18sp——移动端 `danmakuFontScale` 默认 1.0、`danmakuFontScaleFS` 默认 1.2),
    // 全屏画面更大但观看距离没变,字可以略大,不是必须大。**反过来"轨道数定死、拿画布高度
    // 反推字号"是错的**:内嵌播放器只有几百像素高,除以一个固定轨道数会算出偏大的字号——
    // 这正是上一轮内嵌详情页字明显偏大的原因。
    val danmakuFontSizeSp = if (isFullscreen) DANMAKU_FONT_SIZE_SP_FULLSCREEN else DANMAKU_FONT_SIZE_SP_EMBEDDED
    val danmakuFontSizePx = danmakuFontSizeSp * danmakuDisplayDensity.density * danmakuDisplayDensity.fontScale
    val danmakuStyle = remember(danmakuFontSizePx, danmakuPrefs.opacity) {
        DanmakuRenderStyle(
            globalFontSizeSp = danmakuFontSizeSp,
            // 描边宽度跟着字号走(6%),[Stroke] 沿字形轮廓居中描,太粗会把细笔画糊住。
            strokeWidthPx = danmakuFontSizePx * DANMAKU_STROKE_TO_FONT_RATIO,
            opacity = danmakuPrefs.opacity.coerceIn(0.1f, 1f),
        )
    }

    // 行高 = 字号 × 1.6(对齐 PiliPlus `danmakuLineHeight` 默认值),留出上下行距。轨道数
    // 由它和视口高度在 DanmakuLayoutConfig 里推出来,这里不再自己算一遍。
    val danmakuTrackHeightPx = danmakuFontSizePx * DANMAKU_LINE_HEIGHT_RATIO

    // 排布与渲染 Canvas 必须用同一套字体/字号测量(DanmakuHost 类文档)。DanmakuHost 内部
    // 渲染时是拿 style.baseTextStyle 叠一份 fontSize = globalFontSizeSp 再测量
    // (measureDanmaku 的写法),这里的 measure 要复刻同一份叠加,不能只拿裸的 baseTextStyle
    // 去测——那样量出来的宽度会是 TextStyle.Default 的隐式字号,跟实际画出来的宽度对不上,
    // 排布(尤其是速度)会跟着算错。
    //
    // **按文本缓存量出来的宽高,而且这张表比画布尺寸活得久。** 文字尺寸只取决于文本和样式,
    // 跟画布多大无关;而画布尺寸在进场那零点几秒里会变好几次(控件、insets 稳定的过程),
    // 每变一次都要整池重排。不缓存的话那几次重排会连着把同一批文本重量好几遍 —— 实测一个
    // 9457 条的池在 0.6 秒内重编三次、每次约 300ms,全部压在主线程上,正对应帧耗时直方图
    // 尾部那几个 300~400ms 的帧。缓存之后重排还在,重测没了。
    //
    // 不靠 TextMeasurer 自带的 LRU:它默认只有 8 项(这里原先就没传 cacheSize),9457 条的
    // 池命中率约等于零;而把它调大意味着缓存整份 TextLayoutResult,那是渲染路径才需要的东西。
    // 排布只要两个 float,单独存一张 text → 尺寸的表既小又正好落在这次分层划出的边界上。
    val danmakuSizeCache = remember(danmakuStyle, danmakuFontSizeSp) { HashMap<String, DanmakuTextSize>() }
    val danmakuMeasure = remember(danmakuMeasurer, danmakuStyle, danmakuFontSizeSp, danmakuSizeCache) {
        val measureStyle = danmakuStyle.baseTextStyle.copy(fontSize = danmakuFontSizeSp.sp)
        // 单独声明成带类型的 val 再返回,不要让 lambda 紧跟在上一行的函数调用后面——那样
        // Kotlin 会把它解析成上一行 copy(...) 的尾随 lambda 参数,而不是这个 remember 块的
        // 返回值(TextStyle.copy 恰好也有一个函数类型末位参数,编译器不会报"语法错误",
        // 只会报一个不明所以的类型不匹配,这个坑不写清楚很难一眼看出来)。
        val measure: (Danmaku) -> DanmakuTextSize = { danmaku ->
            danmakuSizeCache.getOrPut(danmaku.text) {
                val size = danmakuMeasurer.measure(text = danmaku.text, style = measureStyle).size
                DanmakuTextSize(size.width.toFloat(), size.height.toFloat())
            }
        }
        measure
    }

    val danmakuLayout = remember(
        danmakuCanvasWidthPx,
        danmakuCanvasHeightPx,
        danmakuTrackHeightPx,
        danmakuPrefs.scrollShowArea,
    ) {
        DanmakuLayoutConfig(
            canvasWidthPx = danmakuCanvasWidthPx,
            canvasHeightPx = danmakuCanvasHeightPx,
            trackHeightPx = danmakuTrackHeightPx,
            viewport = DanmakuViewport.topAnchored(danmakuPrefs.scrollShowArea),
            scrollDurationMillis = DANMAKU_CROSS_SCREEN_MILLIS,
        )
    }
    var danmakuSession by remember { mutableStateOf<DanmakuSession?>(null) }

    // 高级弹幕的状态。跟普通弹幕共用 danmakuClock,不另起一个时钟。列表是普通赋值,
    // 没有编排期也没有 notifyChanged —— 位置按播放时间现算,seek/变速/暂停都不需要同步逻辑。
    val specialDanmakuState = remember(danmakuClock) { SpecialDanmakuHostState(danmakuClock) }
    specialDanmakuState.danmaku = specialDanmakuPool

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                danmakuSession?.hostState?.notifyChanged()
            }

            // 覆盖 seek(拖拽松手、双击 ±10 秒、播完重播)——帧循环空闲挂起时只认这两个
            // 信号源之一,不追加会导致 seek 之后弹幕要等兜底轮询(最坏 500ms)才跟上。
            //
            // 顺手把编排窗口推到新位置:seek 到窗口之外时那一段还没排过,等下面每秒一次的
            // 推进就要等最坏一秒,seek 之后会明显空一拍。窗口重建只测量落点附近的一小段
            // (DanmakuCompiler 类文档),放在这个回调里同步做得起。
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                danmakuSession?.compiler?.advanceTo(newPosition.positionMs)
                danmakuSession?.hostState?.notifyChanged()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                danmakuSession?.hostState?.notifyChanged()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 结构性重建:cid、布局(画布尺寸/显示区域/行高)、测量、密度档、帧率任一变化都要求重建
    // 会话——视口、轨道容量或宽度基准变了,旧时间轴里的速度和排布全部作废(DanmakuHost 的
    // 契约、CLAUDE.md「换 cid 清空弹幕池」)。danmakuMeasure 单独列进 key,不靠"行高也跟着
    // 字号变、所以 layout 一定会变"这个巧合:宽度的来源是它,以后字体族可配了同样得重编,
    // 而那时行高未必变。
    //
    // 画布尺寸在进场那零点几秒里会变好几次(控件、insets 稳定的过程),这里就会连着重建
    // 好几次。窗口化之后每次只测量落点附近的一小段,代价已经不在一个量级上,所以不为它引
    // 入去抖延迟——延迟会让首屏弹幕晚一拍出现,那是用户看得见的,重建不是。
    LaunchedEffect(danmakuCid, danmakuLayout, danmakuMeasure, danmakuPrefs.density, danmakuPrefs.frameRateCap) {
        danmakuSession = buildDanmakuSession(
            pool = danmakuPool,
            layout = danmakuLayout,
            density = danmakuPrefs.density,
            frameRateCap = danmakuPrefs.frameRateCap,
            clock = danmakuClock,
            measure = danmakuMeasure,
        )
    }

    // 池子变了就交给编排器:它自己判断新池子是不是旧池子的时间尾部扩展,是就接着排,不是
    // 就作废重建。这个判断以前在这里做(比较新分段的最早时间戳),现在下沉了——它需要知道
    // 排序结果和池内序号,那些都在编排器里。
    LaunchedEffect(danmakuSession, danmakuPool) {
        val session = danmakuSession ?: return@LaunchedEffect
        session.compiler.setPool(danmakuPool)
        if (session.compiler.advanceTo(danmakuClock.positionMillis)) {
            logDanmakuReport(session.compiler.report)
            session.hostState.notifyChanged()
        }
    }

    // 编排窗口跟着播放位置往前推。**编排量因此只跟窗口长度有关,与弹幕池多大无关**——整池
    // 编排里约 94% 的时间花在 TextMeasurer 上(实测 9457 条 347ms,纯调度只占 17~20ms),
    // 而同屏最多几百条,也就是说以前测了三十倍于需要的量,还全压在主线程一帧里。
    //
    // 每秒一次即可:窗口预留 30 秒,一秒的播放推进只会带进几条新弹幕。seek 不靠这个循环
    // 兜(那要等最坏一秒),走上面 onPositionDiscontinuity 那条同步路径。
    LaunchedEffect(danmakuSession) {
        val session = danmakuSession ?: return@LaunchedEffect
        while (true) {
            if (session.compiler.advanceTo(danmakuClock.positionMillis)) session.hostState.notifyChanged()
            delay(DANMAKU_WINDOW_ADVANCE_INTERVAL_MILLIS)
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
                        // 双击按落点分四段:两侧各四分之一是 ±10 秒,中间仍是播放/暂停,
                        // 对齐 PiliPlus 的左右边缘手势区域。
                        //
                        // 中间保留下来是因为双击暂停本来就在,直接换掉等于拿走一个已有的常用
                        // 操作;而三分法是 YouTube 立起来的惯例,两侧那两块也正是横屏握持时
                        // 拇指自然落到的位置。
                        onDoubleTap = { offset ->
                            val quarter = size.width / 4f
                            when {
                                offset.x < quarter -> nudgeSeek(player, -DOUBLE_TAP_SEEK_MILLIS)
                                    .also { seekNudgeMillis = -DOUBLE_TAP_SEEK_MILLIS }

                                offset.x > size.width - quarter -> nudgeSeek(player, DOUBLE_TAP_SEEK_MILLIS)
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
        if (danmakuPrefs.enabled) {
            danmakuSession?.let { session ->
                DanmakuHost(
                    state = session.hostState,
                    style = danmakuStyle,
                    onCanvasSizeMismatch = { widthPx, heightPx ->
                        danmakuCanvasWidthPx = widthPx
                        danmakuCanvasHeightPx = heightPx
                    },
                    // **竖向不留 padding,底部那条干净带由 viewport 负责。** 这里曾经是
                    // `padding(top = Cozy, bottom = Loose)`,理由和 viewport 的一模一样——
                    // 给字幕和控制条让位。两处各留一份就是留了两遍:12dp + 24dp 在 3.25 密度
                    // 下是 117px,内嵌画面高约 711px,画布只剩 594px,再取 75% 得 445px,占画面
                    // 实际只有 62.6%。设置里选的 75% 于是怎么看都像 60%,而且比例越小偏得越多
                    // (padding 是绝对值,不随比例缩放)。
                    //
                    // 横向本来也不能留:横向 padding 会让画布宽度小于排布时用的 canvasWidthPx,
                    // 直接触发 onCanvasSizeMismatch 反复重编;滚动弹幕就该从整个宽度的边缘进出。
                    //
                    // 不挂 clipToBounds:DanmakuHost 自己按视口 clipRect,视口本来就在画布之内,
                    // 外层再裁一次是空操作。
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // 高级弹幕(mode 7)独立一层,压在普通弹幕之上。**不给它任何 padding,也不套视口裁剪**:
        // 上面那一层的位置是引擎排的,收进显示区域是对的;这一层的坐标是作者按 1920×1080 写死的,
        // 收一下就等于改了他排好的构图。两层共用同一个 DanmakuClock,否则会漂——这类弹幕常常
        // 是卡着画面帧做的,漂 100ms 就废了。
        if (danmakuPrefs.enabled && specialDanmakuPool.isNotEmpty()) {
            SpecialDanmakuHost(
                state = specialDanmakuState,
                style = danmakuStyle,
                modifier = Modifier.fillMaxSize(),
            )
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

        // 锁按钮:锁上后它是唯一还能点的东西。只在全屏显示，并放在画面内部左侧，
        // 对齐 PiliPlus 的全屏播放器手势布局。
        AnimatedVisibility(
            visible = isFullscreen && controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
        ) {
            IconButton(onClick = { onLockedChange(!locked) }) {
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
                danmakuEnabled = danmakuPrefs.enabled,
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

/** 一次"重建编排"的产出:编排器 + host 状态。弹幕池的游标在编排器里,这里不再记一份。 */
private class DanmakuSession(
    val compiler: DanmakuCompiler,
    val hostState: DanmakuHostState,
)

private fun buildDanmakuSession(
    pool: List<Danmaku>,
    layout: DanmakuLayoutConfig,
    density: DanmakuDensity,
    frameRateCap: DanmakuFrameRateCap,
    clock: DanmakuClock,
    measure: (Danmaku) -> DanmakuTextSize,
): DanmakuSession {
    val compiler = DanmakuCompiler(layout, density.createScheduler(layout), measure = measure)
    compiler.setPool(pool)
    compiler.advanceTo(clock.positionMillis)
    logDanmakuReport(compiler.report)
    return DanmakuSession(
        compiler = compiler,
        hostState = DanmakuHostState(clock, compiler.timeline, frameRateCap),
    )
}

/**
 * 编排统计走日志,不进界面。它是"为什么这段弹幕比网页里少"的唯一解释,而上一版连返回值都
 * 没接住(`append` 用返回 null 表示丢弃,两个调用点都是 `forEach`),先让它在 logcat 里可查。
 *
 * 条数是**当前窗口**的,不是整池的([DanmakuCompiler] 只编排播放位置附近一段)。窗口推进
 * 每秒都在发生,那条路径不打日志——只有重建(建会话、换池子)才打,否则 logcat 里全是它。
 */
private fun logDanmakuReport(report: ProcessingReport) {
    if (report.inputCount == 0) return
    BiliLog.d(
        "弹幕编排 窗口内 ${report.inputCount} 条,上屏 ${report.scheduledCount},布局丢弃 " +
            "${report.droppedByLayoutCount},峰值同屏 ${report.peakConcurrentCount}," +
            "耗时 ${report.compileDurationMillis}ms",
    )
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
 * 编排窗口向前推进的间隔。取值只需要远小于窗口预留量(30 秒),不需要贴着帧率——推进一次
 * 只是把新进窗口的那几条排进去,不影响已经排好的画面。
 */
private const val DANMAKU_WINDOW_ADVANCE_INTERVAL_MILLIS = 1_000L

/**
 * 描边宽度相对字号的比例。[androidx.compose.ui.graphics.drawscope.Stroke] 沿字形轮廓居中描,
 * 一半会吃进字里——调太粗会把细笔画糊住,6% 是能看清描边又不糊字的经验值。
 */
private const val DANMAKU_STROKE_TO_FONT_RATIO = 0.06f

/**
 * 统一穿屏时长。固定 duration 模型下**所有**滚动弹幕都在这个时间内走完"视口宽 + 自身宽",
 * 长弹幕因此更快——不再是"基准弹幕 6 秒、长弹幕慢慢挪"。
 *
 * 取 6.5 秒是为了接住上一版的手感:那一版短弹幕的实际穿屏时间是 `6 秒 × (1 + 字宽/屏宽)`,
 * 常见的四五个字大约 6.3 秒。差别落在长弹幕上,那正是这次要改的地方。
 */
private const val DANMAKU_CROSS_SCREEN_MILLIS = 6_500L
