package dev.bilby.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import dev.bilby.BiliLog
import dev.bilby.data.DanmakuPrefs
import dev.nihildigit.danmaku.Danmaku
import dev.nihildigit.danmaku.DanmakuClock
import dev.nihildigit.danmaku.DanmakuController
import dev.nihildigit.danmaku.DanmakuLayer
import dev.nihildigit.danmaku.DanmakuOptions
import dev.nihildigit.danmaku.SpecialDanmaku
import dev.nihildigit.danmaku.rememberDanmakuController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.TimeSource

/** 弹幕从哪来。两种形态的差别只在这里,排布与渲染都一样。 */
sealed interface DanmakuFeed {

    /** 整池已知(点播)。 */
    data class Pool(val danmaku: List<Danmaku>) : DanmakuFeed

    /**
     * 逐条到达(直播)。**`playTimeMillis` 会被忽略并就地重打** —— 服务端时间戳和播放器时间轴
     * 不是同一根,而渲染层手里就有播放器位置。
     */
    data class Stream(val arrivals: Flow<Danmaku>) : DanmakuFeed
}

/**
 * 把播放器接到弹幕引擎上。**这一层只做接线** —— 测量、排布、画布尺寸都在库里,见
 * [DanmakuController]。
 *
 * @param fontSizeSp 由调用方按播放器形态给(见 [DanmakuFontSizeSp]),层自己不认识"全屏"。
 * @param cid 这池弹幕属于谁。换一条要整池重编;直播没有分 P,传房间号。
 */
@Composable
fun PlayerDanmakuLayer(
    player: Player,
    prefs: DanmakuPrefs,
    feed: DanmakuFeed,
    specialPool: List<SpecialDanmaku>,
    cid: Long,
    fontSizeSp: Float,
    /**
     * 自己刚发出去的弹幕。**与 [feed] 的形态无关**,两种都可能有 —— 所以它不是
     * [DanmakuFeed.Stream] 的一个变体,而是单独一条。
     */
    selfDanmaku: Flow<Danmaku> = emptyFlow(),
    modifier: Modifier = Modifier,
) {
    val isLive = feed is DanmakuFeed.Stream
    val clock = remember(player, isLive) {
        if (isLive) LiveDanmakuClock(player) else PlayerDanmakuClock(player)
    }
    val options = remember(prefs, fontSizeSp) {
        DanmakuOptions(
            fontSizeSp = fontSizeSp,
            opacity = prefs.opacity,
            density = prefs.density,
            frameRateCap = prefs.frameRateCap,
            scrollShowArea = prefs.scrollShowArea,
        )
    }
    val controller = rememberDanmakuController(clock, options, contentKey = cid)

    controller.setSpecial(specialPool)

    // **只监听弹幕要的信号。** 播放状态归播放器壳监听(它管常亮和播放按钮);两者曾经挤在同一个
    // listener 里,于是"谁该关心什么"看不出来,弹幕这一层也就搬不走。
    DisposableEffect(player, controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) = controller.notifyChanged()

            // 覆盖 seek(拖拽松手、双击 ±10 秒、播完重播)。帧循环空闲挂起时只认这一条和兜底
            // 轮询,不接的话 seek 之后弹幕要等最坏 500ms 才跟上。
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) = controller.notifyChanged()

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
                controller.notifyChanged()
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    when (feed) {
        is DanmakuFeed.Pool -> LaunchedEffect(controller, feed.danmaku) {
            controller.setPool(feed.danmaku)
            logDanmakuReport(controller)
        }

        is DanmakuFeed.Stream -> {
            LaunchedEffect(controller, feed.arrivals) {
                feed.arrivals.collect { controller.appendNow(it) }
            }
            // 直播的时间轴只增不减,定期把已经离场的丢掉。点播不做:回退 seek 要用到过去那一段。
            LaunchedEffect(controller) {
                while (true) {
                    delay(LIVE_TRIM_INTERVAL_MILLIS)
                    controller.trimBefore(clock.positionMillis - LIVE_TRIM_KEEP_MILLIS)
                }
            }
        }
    }

    // 自己发的那条走 `appendNow`,和直播的到达流同一个入口:它要的是"此刻上屏",而不是
    // "在时间轴的某一点上"。进池子的话下一次分段追加会把它一起重编,于是它会在当前位置
    // 再飘一遍。
    LaunchedEffect(controller, selfDanmaku) {
        selfDanmaku.collect { controller.appendNow(it) }
    }

    // **关掉时整个不进组合**,不是画了个空 —— 帧循环由组合驱动,不进组合就没有那条协程。
    if (!prefs.enabled) return
    DanmakuLayer(controller, modifier)
}

/**
 * 播放器形态对应的弹幕字号。全屏画面更大但观看距离没变,字可以略大,**不是必须大**。
 * 对齐 PiliPlus `danmaku_options.dart` 的默认档:15sp 基准,全屏 ×1.2 = 18sp。
 *
 * **反过来"轨道数定死、拿画布高度反推字号"是错的**:内嵌播放器只有几百像素高,除以一个固定
 * 轨道数会算出偏大的字号 —— 那正是内嵌详情页字明显偏大过的原因。
 */
object DanmakuFontSizeSp {
    const val Embedded = 15f
    const val Fullscreen = 18f
}

/**
 * 点播的弹幕时钟,位置读 MediaController。
 *
 * **已知会在倍速刚变的那一小段窗口里抖一下。** MediaController 的 `getCurrentPosition()`
 * 在本地做"锚点位置 + 经过时间 × 倍速"外推(media3-session:1.10.1,
 * `MediaUtils.getUpdatedCurrentPositionMs`),而 `setPlaybackSpeed()` 立刻 masking 新倍速
 * 却不同步刷新那个锚点,于是新倍速被追认到已经过去的那段时间上,外推值跳过头,session 回包
 * 落地后再纠正回来。长按加速每次都会经过这个窗口。
 *
 * 此前绕开它的办法是直接读同进程 ExoPlayer 的进度,那个静态引用随 Surface 改走 controller
 * 一并删掉了 —— 跨进程能成立的东西不该靠同进程的巧合。
 */
private class PlayerDanmakuClock(private val player: Player) : DanmakuClock {
    override val positionMillis: Long get() = player.currentPosition
    override val isPlaying: Boolean get() = player.isPlaying
    override val playbackSpeed: Float get() = player.playbackParameters.speed
}

/**
 * 直播的弹幕时钟:**一条自己的单调时间轴**,和媒体时间轴无关。
 *
 * 不能用播放器的位置。直播 HLS 的 `currentPosition` 是相对于**滑动窗口**的,旧分片被丢弃、
 * 窗口起点前移时读数会往回跳,而编排器把"位置回退"理解成 seek:清空时间轴,同时开始拒收
 * 新的追加。表现是弹幕出现几秒后集体消失,周期性复现。
 *
 * 直播也不需要跟媒体时间轴对齐:弹幕是"此刻到达、此刻显示",没有 seek 也没有回看,唯一的
 * 要求是单调地按 1 倍速往前走。
 */
private class LiveDanmakuClock(private val player: Player) : DanmakuClock {
    private val start = TimeSource.Monotonic.markNow()
    override val positionMillis: Long get() = start.elapsedNow().inWholeMilliseconds
    override val isPlaying: Boolean get() = player.isPlaying
    override val playbackSpeed: Float get() = 1f
}

/**
 * 编排统计走日志,不进界面。它是"为什么这段弹幕比网页里少"的唯一解释。
 *
 * 条数是**当前窗口**的,不是整池的。窗口推进每秒都在发生,那条路径不打日志 —— 只有换池子
 * 才打,否则 logcat 里全是它。
 */
private fun logDanmakuReport(controller: DanmakuController) {
    val report = controller.report
    if (report.inputCount == 0) return
    // 峰值同屏要单独走一次全表扫描,只有真要打这行日志时才值得。
    BiliLog.d(
        "弹幕编排 窗口内 ${report.inputCount} 条,上屏 ${report.scheduledCount},布局丢弃 " +
            "${report.droppedByLayoutCount},峰值同屏 ${controller.peakConcurrency()}," +
            "耗时 ${report.compileDurationMillis}ms",
    )
}

/** 裁剪间隔。这一步是 O(丢掉的条数),不必频繁。 */
private const val LIVE_TRIM_INTERVAL_MILLIS = 30_000L

/** 裁剪时在播放位置之前保留多久。留一个穿屏时长的余量,免得把还在屏上的裁掉。 */
private const val LIVE_TRIM_KEEP_MILLIS = 15_000L
