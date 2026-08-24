package dev.bilby.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import dev.bilby.BiliLog
import dev.bilby.data.DanmakuPrefs
import dev.bilby.player.AudioPlaybackService
import dev.bilby.player.PositionTick
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
        if (isLive) LiveDanmakuClock() else PlayerDanmakuClock()
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
 * 点播的弹幕时钟,位置读服务发的刻度(见 [PositionTick])。
 *
 * **不读 MediaController 的 `getCurrentPosition()`。** 它在本地做"锚点位置 + 经过时间 × 倍速"
 * 外推,而 `setPlaybackSpeed()` 立刻 masking 新倍速却不同步刷新那个锚点,于是新倍速被追认到
 * 已经过去的那段时间上,读数跳过头,session 回包落地后再纠正回来。长按加速每次都经过这个
 * 窗口,表现是松手瞬间弹幕集体跳一下。锚点是 controller 的私有状态,那一侧无解。
 *
 * 服务自己发刻度是唯一的修法:刻度带着自己的锚点,倍速一变服务立刻发新的一条。**这不是绕过
 * MediaController** —— 控制动作(播放、暂停、跳转、倍速)照旧全走 controller,这条流上只有
 * 状态,和播放页读 [AudioPlaybackService.state] 是同一个方向。
 */
private class PlayerDanmakuClock : DanmakuClock {

    private val tick get() = AudioPlaybackService.positionTicks.value

    /**
     * **每帧当场问播放器,不外推。**
     *
     * 弹幕的横坐标是播放位置的直接函数(`x = viewport.right - (t - emitTime) * 每毫秒像素`,
     * 见 [DanmakuHost] 的类注释),排布本身跟倍速无关。所以位置读数抖多少,弹幕就跳多少 ——
     * 这一层唯一要保证的事,就是 [DanmakuClock] 契约里那句"逐帧连续"。
     *
     * 半秒一条的刻度([AudioPlaybackService.positionTicks])不满足这一条,消费方必须自己外推,
     * 而外推需要一个速率,能拿到的只有 `playbackParameters.speed` —— **那是请求的倍速,不是
     * 位置正在遵守的倍速**。长按加速的那一刻它立刻变成 3×,而位置还要按 1× 走完已经写进
     * AudioTrack 的那几百毫秒(media3 用一队 checkpoint 记着新参数从哪个输出位置起算,见
     * `DefaultAudioSink.applyMediaPositionParameters`)。外推于是跑到真实位置前面,等下一条
     * 刻度落地再被拽回来 —— 按下和松开各跳一次,方向相反。
     *
     * 那个差是外推这件事本身带来的,不是刻度不够密:请求倍速和实际倍速在过渡期里本来就不是
     * 同一个数,改用"实测速率"只会把跳变翻个方向。**所以不外推**:服务与界面同进程,
     * `ExoPlayer.getCurrentPosition()` 每次调用现算、本身就连续,直接问它就没有第二个估计器,
     * 也就没有可分歧的东西([AudioPlaybackService.currentPositionMillis])。
     *
     * **这不是"改用 MediaController 的 getCurrentPosition"** —— 那一侧才是要避开的东西:
     * 它在自己进程里按"锚点 + 经过时间 × 倍速"外推,锚点是它的私有状态,变速时同样对不齐,
     * 而且外部既读不到也刷不了(见 [PositionTick])。这里问的是播放器本人。
     *
     * 服务还没起来时退回刻度:那时播放器根本不存在,读数只能是最后一次已知的位置。
     */
    override val positionMillis: Long
        get() = AudioPlaybackService.currentPositionMillis()
            ?: tick.positionAt(SystemClock.elapsedRealtime())

    override val isPlaying: Boolean get() = tick.isPlaying

    override val playbackSpeed: Float get() = tick.speed
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
 *
 * **[isPlaying] 恒为 true,不委托给播放器。** 这条时间轴是自己的,暂停时它照走 —— 委托出去
 * 的话两者对不上:帧循环在暂停时挂起,而每来一条弹幕又会触发一帧重排,画面于是按已经走掉的
 * 那段时间跳一格再冻住,一卡一卡地往前挪。暂停的是画面,弹幕照流。
 */
private class LiveDanmakuClock : DanmakuClock {
    private val start = TimeSource.Monotonic.markNow()
    override val positionMillis: Long get() = start.elapsedNow().inWholeMilliseconds
    override val isPlaying: Boolean get() = true
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
