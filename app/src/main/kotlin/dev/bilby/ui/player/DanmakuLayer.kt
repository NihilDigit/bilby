package dev.bilby.ui.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import dev.bilby.BiliLog
import dev.bilby.data.DanmakuPrefs
import dev.nihildigit.danmaku.Danmaku
import dev.nihildigit.danmaku.DanmakuClock
import dev.nihildigit.danmaku.DanmakuCompiler
import dev.nihildigit.danmaku.DanmakuDensity
import dev.nihildigit.danmaku.DanmakuFrameRateCap
import dev.nihildigit.danmaku.DanmakuHost
import dev.nihildigit.danmaku.DanmakuHostState
import dev.nihildigit.danmaku.DanmakuLayoutConfig
import dev.nihildigit.danmaku.DanmakuRenderStyle
import dev.nihildigit.danmaku.DanmakuTextSize
import dev.nihildigit.danmaku.DanmakuViewport
import dev.nihildigit.danmaku.SpecialDanmaku
import dev.nihildigit.danmaku.SpecialDanmakuHost
import dev.nihildigit.danmaku.SpecialDanmakuHostState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * 弹幕从哪来。两种形态的差别只在这里,时钟、测量、排布、渲染都一样。
 */
sealed interface DanmakuFeed {

    /** 整池已知(点播)。换一份就交给编排器判断是不是尾部扩展,是就接着排,不是就重建。 */
    data class Pool(val danmaku: List<Danmaku>) : DanmakuFeed

    /**
     * 逐条到达(直播)。**[arrivals] 里的 `playTimeMillis` 会被忽略并就地重打** ——
     * 服务端给的时间戳和播放器的时间轴不是同一根,对时是一整类问题;用播放器此刻的位置打戳,
     * 两者天然同轴,一步都不用对。
     *
     * 时间轴只增不减,所以这一支会定期调用 `trimBefore` 把已经离场的丢掉,否则一场几小时的
     * 直播会把每一条弹幕连同排布结果一直攒在内存里。
     */
    data class Stream(val arrivals: Flow<Danmaku>) : DanmakuFeed
}

/**
 * 弹幕层。**视频与直播共用这一份** —— 两者的差别只在弹幕从哪来(整池 vs 逐条到达),
 * 而时钟、测量、排布、渲染这几步完全一样。
 *
 * 这一层不认识"全屏"这类播放器形态,字号由调用方按形态给进来(见 [DanmakuFontSizeSp])。
 * 它也不认识播放页:除了 [player] 和几份数据,不读任何页面状态。
 *
 * **关闭时整个不进组合**,不是画了个空 —— [DanmakuHostState] 的帧循环由
 * `LaunchedEffect(state)` 驱动,不进组合就没有这个协程,不会白烧一条 vsync 循环。
 * 没有 pointerInput,不拦截手势,底下的双击/拖拽照常命中。
 *
 * @param player 控制器(MediaController)。只用来监听 seek 与变速,不下命令。
 * @param surfacePlayer 同进程的播放器本体,位置读数优先走它,理由见 [PlayerDanmakuClock]。
 * @param fontSizeSp 弹幕字号。**绝对量,不是画布的函数** —— 它关乎眼睛和屏幕的距离,
 *   不关乎播放器窗口开了多大。
 * @param cid 弹幕池所属的分 P;换一条要整池重编,不是接着追加。直播没有分 P,传房间号即可,
 *   语义是"这池弹幕属于谁"。
 */
@Composable
fun DanmakuLayer(
    player: Player,
    surfacePlayer: Player?,
    prefs: DanmakuPrefs,
    feed: DanmakuFeed,
    specialPool: List<SpecialDanmaku>,
    cid: Long,
    fontSizeSp: Float,
    modifier: Modifier = Modifier,
) {
    // 时钟包的是 player(MediaController),不是 surfacePlayer 的引用本身 —— 弹幕只要播放
    // 位置/状态,不碰画面,跟"控制一律走 controller"的约定一致。
    val clock = remember(player, surfacePlayer) { PlayerDanmakuClock(controller = player, surfacePlayer = surfacePlayer) }
    val measurer = rememberTextMeasurer()
    // 名字带 Display 是为了和"同屏密度"(prefs.density)分开,这里是屏幕像素密度。
    val displayDensity = LocalDensity.current

    // 排布时用的画布宽高。初始 0f 不用先猜一个近似值——首帧布局出来后由 DanmakuHost 的
    // onCanvasSizeMismatch 一次纠正两个值。宽高都进了排布(高度决定视口和轨道数),所以这里
    // 不再自己挂一个 onSizeChanged 去量高:两处各量一份就会有两份可能对不齐的尺寸。
    var canvasWidthPx by remember { mutableFloatStateOf(0f) }
    var canvasHeightPx by remember { mutableFloatStateOf(0f) }

    val fontSizePx = fontSizeSp * displayDensity.density * displayDensity.fontScale
    val style = remember(fontSizePx, prefs.opacity) {
        DanmakuRenderStyle(
            globalFontSizeSp = fontSizeSp,
            // 描边宽度跟着字号走,Stroke 沿字形轮廓居中描,太粗会把细笔画糊住。
            strokeWidthPx = fontSizePx * DANMAKU_STROKE_TO_FONT_RATIO,
            opacity = prefs.opacity.coerceIn(0.1f, 1f),
        )
    }

    // 行高 = 字号 × 1.6(对齐 PiliPlus `danmakuLineHeight` 默认值),留出上下行距。轨道数
    // 由它和视口高度在 DanmakuLayoutConfig 里推出来,这里不再自己算一遍。
    val trackHeightPx = fontSizePx * DANMAKU_LINE_HEIGHT_RATIO

    // 排布与渲染 Canvas 必须用同一套字体/字号测量(DanmakuHost 类文档)。DanmakuHost 内部
    // 渲染时是拿 style.baseTextStyle 叠一份 fontSize = globalFontSizeSp 再测量,这里的
    // measure 要复刻同一份叠加,不能只拿裸的 baseTextStyle 去测——那样量出来的宽度会是
    // TextStyle.Default 的隐式字号,跟实际画出来的宽度对不上,排布(尤其是速度)会跟着算错。
    //
    // **按文本缓存量出来的宽高,而且这张表比画布尺寸活得久。** 文字尺寸只取决于文本和样式,
    // 跟画布多大无关;而画布尺寸在进场那零点几秒里会变好几次(控件、insets 稳定的过程),
    // 每变一次都要整池重排。不缓存的话那几次重排会连着把同一批文本重量好几遍 —— 实测一个
    // 9457 条的池在 0.6 秒内重编三次、每次约 300ms,全部压在主线程上。缓存之后重排还在,
    // 重测没了。
    //
    // 不靠 TextMeasurer 自带的 LRU:它默认只有 8 项,9457 条的池命中率约等于零;而把它调大
    // 意味着缓存整份 TextLayoutResult,那是渲染路径才需要的东西。排布只要两个 float。
    val sizeCache = remember(style, fontSizeSp) { HashMap<String, DanmakuTextSize>() }
    val measure = remember(measurer, style, fontSizeSp, sizeCache) {
        val measureStyle = style.baseTextStyle.copy(fontSize = fontSizeSp.sp)
        // 单独声明成带类型的 val 再返回,不要让 lambda 紧跟在上一行的函数调用后面——那样
        // Kotlin 会把它解析成上一行 copy(...) 的尾随 lambda 参数,而不是这个 remember 块的
        // 返回值(TextStyle.copy 恰好也有一个函数类型末位参数,编译器不会报"语法错误",
        // 只会报一个不明所以的类型不匹配)。
        val fn: (Danmaku) -> DanmakuTextSize = { danmaku ->
            sizeCache.getOrPut(danmaku.text) {
                val size = measurer.measure(text = danmaku.text, style = measureStyle).size
                DanmakuTextSize(size.width.toFloat(), size.height.toFloat())
            }
        }
        fn
    }

    val layout = remember(canvasWidthPx, canvasHeightPx, trackHeightPx, prefs.scrollShowArea) {
        DanmakuLayoutConfig(
            canvasWidthPx = canvasWidthPx,
            canvasHeightPx = canvasHeightPx,
            trackHeightPx = trackHeightPx,
            viewport = DanmakuViewport.topAnchored(prefs.scrollShowArea),
            scrollDurationMillis = DANMAKU_CROSS_SCREEN_MILLIS,
        )
    }
    var session by remember { mutableStateOf<DanmakuSession?>(null) }

    // 高级弹幕的状态。跟普通弹幕共用同一个时钟,不另起一个 —— 这类弹幕常常是卡着画面帧做的,
    // 两层各走各的时钟漂 100ms 就废了。列表是普通赋值,没有编排期也没有 notifyChanged:
    // 位置按播放时间现算,seek/变速/暂停都不需要同步逻辑。
    val specialState = remember(clock) { SpecialDanmakuHostState(clock) }
    specialState.danmaku = specialPool

    // **只监听弹幕自己要的信号。** 播放状态归播放器壳监听(它要管常亮和播放按钮),这里曾经
    // 和它挤在同一个 listener 里,于是"谁该关心什么"看不出来,弹幕层也就搬不走。
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                session?.hostState?.notifyChanged()
            }

            // 覆盖 seek(拖拽松手、双击 ±10 秒、播完重播)——帧循环空闲挂起时只认这两个
            // 信号源之一,不追加会导致 seek 之后弹幕要等兜底轮询(最坏 500ms)才跟上。
            //
            // 顺手把编排窗口推到新位置:seek 到窗口之外时那一段还没排过,等下面每秒一次的
            // 推进就要等最坏一秒,seek 之后会明显空一拍。窗口重建只测量落点附近的一小段,
            // 放在这个回调里同步做得起。
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                session?.compiler?.advanceTo(newPosition.positionMs)
                session?.hostState?.notifyChanged()
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                session?.hostState?.notifyChanged()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 结构性重建:cid、布局(画布尺寸/显示区域/行高)、测量、密度档、帧率任一变化都要求重建
    // 会话——视口、轨道容量或宽度基准变了,旧时间轴里的速度和排布全部作废。measure 单独列进
    // key,不靠"行高也跟着字号变、所以 layout 一定会变"这个巧合:宽度的来源是它,以后字体族
    // 可配了同样得重编,而那时行高未必变。
    //
    // 画布尺寸在进场那零点几秒里会变好几次,这里就会连着重建好几次。窗口化之后每次只测量
    // 落点附近的一小段,代价已经不在一个量级上,所以不为它引入去抖延迟——延迟会让首屏弹幕
    // 晚一拍出现,那是用户看得见的,重建不是。
    LaunchedEffect(cid, layout, measure, prefs.density, prefs.frameRateCap) {
        session = buildDanmakuSession(
            pool = (feed as? DanmakuFeed.Pool)?.danmaku.orEmpty(),
            layout = layout,
            density = prefs.density,
            frameRateCap = prefs.frameRateCap,
            clock = clock,
            measure = measure,
        )
    }

    // 池子变了就交给编排器:它自己判断新池子是不是旧池子的时间尾部扩展,是就接着排,不是
    // 就作废重建。
    LaunchedEffect(session, feed) {
        val current = session ?: return@LaunchedEffect
        when (feed) {
            is DanmakuFeed.Pool -> {
                current.compiler.setPool(feed.danmaku)
                if (current.compiler.advanceTo(clock.positionMillis)) {
                    logDanmakuReport(current.compiler)
                    current.hostState.notifyChanged()
                }
            }

            is DanmakuFeed.Stream -> feed.arrivals.collect { danmaku ->
                // 到达即打戳。往后挪一点点是给排布留出的最小提前量 —— 排在"此刻"的弹幕
                // 已经错过了当前这一帧,会被 visibleAt 直接跳过。
                val at = clock.positionMillis + LIVE_EMIT_LEAD_MILLIS
                if (current.compiler.append(danmaku.copy(playTimeMillis = at))) {
                    current.compiler.advanceTo(clock.positionMillis)
                    current.hostState.notifyChanged()
                }
            }
        }
    }

    // 直播的时间轴只增不减,定期把已经离场的丢掉。点播不做:那边回退 seek 要用到过去那一段。
    if (feed is DanmakuFeed.Stream) {
        LaunchedEffect(session) {
            val current = session ?: return@LaunchedEffect
            while (true) {
                delay(LIVE_TRIM_INTERVAL_MILLIS)
                current.compiler.trimBefore(clock.positionMillis - LIVE_TRIM_KEEP_MILLIS)
            }
        }
    }

    // 编排窗口跟着播放位置往前推。**编排量因此只跟窗口长度有关,与弹幕池多大无关**——整池
    // 编排里约 94% 的时间花在 TextMeasurer 上(实测 9457 条 347ms,纯调度只占 17~20ms),
    // 而同屏最多几百条。
    //
    // 每秒一次即可:窗口预留 30 秒,一秒的播放推进只会带进几条新弹幕。seek 不靠这个循环兜
    // (那要等最坏一秒),走上面 onPositionDiscontinuity 那条同步路径。
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        while (true) {
            if (current.compiler.advanceTo(clock.positionMillis)) current.hostState.notifyChanged()
            delay(DANMAKU_WINDOW_ADVANCE_INTERVAL_MILLIS)
        }
    }

    if (!prefs.enabled) return

    session?.let { current ->
        DanmakuHost(
            state = current.hostState,
            style = style,
            onCanvasSizeMismatch = { widthPx, heightPx ->
                canvasWidthPx = widthPx
                canvasHeightPx = heightPx
            },
            // **竖向不留 padding,底部那条干净带由 viewport 负责。** 这里曾经是
            // `padding(top = Cozy, bottom = Loose)`,理由和 viewport 的一模一样——给字幕和
            // 控制条让位。两处各留一份就是留了两遍:12dp + 24dp 在 3.25 密度下是 117px,
            // 内嵌画面高约 711px,画布只剩 594px,再取 75% 得 445px,占画面实际只有 62.6%。
            // 设置里选的 75% 于是怎么看都像 60%,而且比例越小偏得越多(padding 是绝对值,
            // 不随比例缩放)。
            //
            // 横向本来也不能留:横向 padding 会让画布宽度小于排布时用的 canvasWidthPx,
            // 直接触发 onCanvasSizeMismatch 反复重编;滚动弹幕就该从整个宽度的边缘进出。
            //
            // 不挂 clipToBounds:DanmakuHost 自己按视口 clipRect,视口本来就在画布之内,
            // 外层再裁一次是空操作。
            modifier = modifier.fillMaxSize(),
        )
    }

    // 高级弹幕独立一层,压在普通弹幕之上。**不给它任何 padding,也不套视口裁剪**:上面那一层
    // 的位置是引擎排的,收进显示区域是对的;这一层的坐标是作者按参考画幅写死的,收一下就等于
    // 改了他排好的构图。
    if (specialPool.isNotEmpty()) {
        SpecialDanmakuHost(
            state = specialState,
            style = style,
            modifier = modifier.fillMaxSize(),
        )
    }
}

/**
 * 播放器形态对应的弹幕字号。全屏画面更大但观看距离没变,字可以略大,**不是必须大**。
 *
 * 对齐 PiliPlus `danmaku_options.dart` 的默认档:15sp 基准,全屏 ×1.2 = 18sp。
 *
 * **反过来"轨道数定死、拿画布高度反推字号"是错的**:内嵌播放器只有几百像素高,除以一个
 * 固定轨道数会算出偏大的字号——那正是内嵌详情页字明显偏大过的原因。
 */
object DanmakuFontSizeSp {
    const val Embedded = 15f
    const val Fullscreen = 18f
}

/**
 * 弹幕位置的读数来源。
 *
 * 位置优先读 [surfacePlayer](同进程的播放器本体)而不是 [controller](MediaController),
 * 是为了绕开后者一个真实的实现缺陷:MediaController 是 session 的跨进程代理,
 * `getCurrentPosition()` 自己在本地做"锚点位置 + 经过时间 × 倍速"外推
 * (media3-session:1.10.1,`MediaUtils.getUpdatedCurrentPositionMs`),而 `setPlaybackSpeed()`
 * 本地立刻 masking 新倍速、却不同步刷新那个锚点——锚点要等 session 跨进程回包才更新,
 * 倍速刚变的这段窗口里外推值会用新倍速乘上"旧锚点到现在的整段时间"而跳过头,回包落地后
 * 又被纠正回去,表现为弹幕集体抖一下。`surfacePlayer` 直接读渲染器的真实进度,不存在
 * masking,也没有 IPC 延迟,天然满足 [DanmakuClock] 文档要求的"逐帧连续"。
 *
 * **读位置不是发命令,不违反"控制一律走 controller,不碰 currentPlayer"那条约定** ——
 * 那条约束的是控制命令:绕开 session 直接对 currentPlayer 下命令,会让通知栏、耳机线控、
 * 界面三方对播放状态各说各话。这里只读不写。
 *
 * [surfacePlayer] 为 null(服务还没绑定,或者听视频切回来的短暂窗口)时退回 [controller] ——
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
    logDanmakuReport(compiler)
    return DanmakuSession(
        compiler = compiler,
        hostState = DanmakuHostState(clock, compiler.timeline, frameRateCap),
    )
}

/**
 * 编排统计走日志,不进界面。它是"为什么这段弹幕比网页里少"的唯一解释。
 *
 * 条数是**当前窗口**的,不是整池的(编排器只排播放位置附近一段)。窗口推进每秒都在发生,
 * 那条路径不打日志——只有重建(建会话、换池子)才打,否则 logcat 里全是它。
 */
private fun logDanmakuReport(compiler: DanmakuCompiler) {
    val report = compiler.report
    if (report.inputCount == 0) return
    // 峰值同屏要单独问时间轴:它是 O(n log n) 的一次扫描,只有真要打这行日志时才值得算。
    BiliLog.d(
        "弹幕编排 窗口内 ${report.inputCount} 条,上屏 ${report.scheduledCount},布局丢弃 " +
            "${report.droppedByLayoutCount},峰值同屏 ${compiler.timeline.peakConcurrency()}," +
            "耗时 ${report.compileDurationMillis}ms",
    )
}

/** 行高相对字号的倍数,对齐 PiliPlus `danmakuLineHeight` 默认值。 */
private const val DANMAKU_LINE_HEIGHT_RATIO = 1.6f

/**
 * 描边宽度相对字号的比例。Stroke 沿字形轮廓居中描,太粗会把细笔画糊住。
 */
private const val DANMAKU_STROKE_TO_FONT_RATIO = 0.06f

/**
 * 编排窗口向前推进的间隔。取值只需要远小于窗口预留量(30 秒),不需要贴着帧率。
 */
private const val DANMAKU_WINDOW_ADVANCE_INTERVAL_MILLIS = 1_000L

/**
 * 统一穿屏时长。固定 duration 模型下**所有**滚动弹幕都在这个时间内走完"视口宽 + 自身宽",
 * 长弹幕因此更快。
 */
private const val DANMAKU_CROSS_SCREEN_MILLIS = 6_500L

/**
 * 直播弹幕从到达到进场之间留的提前量。排在"此刻"的那一条已经错过当前帧,`visibleAt` 会直接
 * 跳过它;给一帧多一点就够,不是为了凑预热窗口。
 */
private const val LIVE_EMIT_LEAD_MILLIS = 100L

/** 裁剪间隔。这一步是 O(丢掉的条数),不必频繁。 */
private const val LIVE_TRIM_INTERVAL_MILLIS = 30_000L

/** 裁剪时在播放位置之前保留多久。留一个穿屏时长的余量,免得把还在屏上的裁掉。 */
private const val LIVE_TRIM_KEEP_MILLIS = 15_000L
