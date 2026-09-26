package dev.bilby.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import dev.bilby.player.PlayerHandle
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.player.PlayerListener
import dev.bilby.player.PlayerPhase
import dev.bilby.player.VideoDimensions
import dev.bilby.ui.LocalPlaybackHost
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.data.SettingsStore
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.rememberLoadingVisible
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.PlayerTheme
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放器外壳能提供哪些手势。**直播把 [seek] 关掉** —— 直播那条时间轴上没有"往回拖"这回事,
 * 而手势层本身(方向锁定、浮层、长按快进)两边一模一样,没有理由各写一份。
 */
data class PlayerGestureOptions(
    /** 横划改进度、双击 ±10 秒。关掉之后横划与两侧双击都不响应。 */
    val seek: Boolean = true,
    /** 左半屏纵划调亮度、右半屏调音量。 */
    val brightnessAndVolume: Boolean = true,
    /** 长按临时加速。 */
    val fastForward: Boolean = true,
)

/**
 * 外壳交给内容层的东西:壳知道、而 slot 又必须知道的那些。
 *
 * 它同时是 [BoxScope],所以 slot 里可以直接用 `align()` 摆位置。
 */
@Stable
class PlayerShellScope internal constructor(
    private val box: BoxScope,
    val isPlaying: Boolean,
    val isFullscreen: Boolean,
    val locked: Boolean,
    val controlsVisible: Boolean,
    /** 显示用位置:拖拽中是拖到的目标,否则是播放器当前位置。 */
    val positionMillis: Long,
    /** 已缓冲到哪儿,画在进度条上。拖拽中不跟着拖走 —— 它说的是流,不是手指。 */
    val bufferedPositionMillis: Long,
    val durationMillis: Long,
    val speed: Float,
    internal val actions: PlayerShellActions,
) : BoxScope by box {

    /** 有交互发生,自动隐藏重新计时。控制条里每个按钮都该调它。 */
    fun keepControlsAwake() = actions.keepAwake()

    /** 菜单展开期间不自动隐藏控件 —— 菜单是浮在控件上的,控件收了菜单就没了依托。 */
    fun setMenuOpen(open: Boolean) = actions.setMenuOpen(open)

    fun togglePlayPause() = actions.togglePlayPause()

    fun setSpeed(value: Float) = actions.setSpeed(value)

    /** 进度条按下:先暂停,松手时按 [onSeekFinished] 决定要不要恢复。 */
    fun onSeekStart() = actions.seekStart()

    /** 拖拽中只更新本地位置,不真 seek —— 每帧 seek 会让播放器不停丢缓冲重新起播。 */
    fun onSeekTo(positionMillis: Long) = actions.seekTo(positionMillis)

    fun onSeekFinished() = actions.seekFinished()

    fun toggleFullscreen() = actions.toggleFullscreen()
}

internal class PlayerShellActions(
    val keepAwake: () -> Unit,
    val setMenuOpen: (Boolean) -> Unit,
    val togglePlayPause: () -> Unit,
    val setSpeed: (Float) -> Unit,
    val seekStart: () -> Unit,
    val seekTo: (Long) -> Unit,
    val seekFinished: () -> Unit,
    val toggleFullscreen: () -> Unit,
)

/**
 * 播放器外壳:画面、手势、控件显隐、全屏、锁。**视频与直播共用这一份。**
 *
 * 壳只持有"看得见的瞬时状态"——控件显不显示、手势进行到哪、画面比例。
 * [locked] 与 [isFullscreen] **由页面持有并传进来**,不在壳里:决定它们的是返回键的语义
 * (先解锁、再退全屏、再退页面),那是页面的事,壳没有资格替它排序。
 *
 * 内容通过两个 slot 挂上来:[overlay] 压在画面之上、控制条之下(弹幕、字幕),
 * [controlBar] 是底部控制条的全部内容。两者都能从 [PlayerShellScope] 读到壳的状态。
 *
 * @param attached 播放器此刻装的是不是这一页要的东西。为 false 时不挂 [PlayerSurface],
 *   改画 [placeholderCoverUrl] —— 播放器全 app 共用一份、跨页面存活,点开新内容到它真正
 *   切过去之间有一段取流窗口,这段时间里画面上还是上一条的最后几帧。
 *   **不去暂停或销毁播放器**,那会打断后台连续播放,也违反"播放器归服务所有"。
 *
 *   它同时是屏幕常亮的判据:没有画面就没有理由拦着屏幕息。直播的纯音频模式据此传 false。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerShell(
    player: PlayerHandle,
    attached: Boolean,
    placeholderCoverUrl: String,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    locked: Boolean,
    onLockedChange: (Boolean) -> Unit,
    /** 只在全屏时显示。竖屏下标题就在播放器正下方,再印一遍是多余的。 */
    title: String,
    /**
     * 画面虽然没全屏,但已经铺到屏幕边缘、压在状态栏底下了。
     *
     * 这时状态栏的图标要转白(它落在画面的黑边或封面上),窗口也要允许铺进刘海那一条。
     */
    fullBleed: Boolean = false,
    /**
     * 再进一步:把状态栏整条收起来。
     *
     * 只有横屏两栏用得上 —— 那时状态栏一半压在黑画面上、一半压在浅色的简介栏上,而图标明暗
     * 是整条一起设的,必然有一半看不见。竖排不需要,状态栏整条都落在画面上,转白就够了。
     * 导航栏两种情况都留着。
     */
    hideStatusBar: Boolean = false,
    /**
     * 画面顶部画一条渐变。**页面在画面左上角挂了返回/分享时传 true** —— 那两个按钮画在壳
     * 外面,渐变若跟着它们走就会压在弹幕上，把顶上一两条洗淡;交给壳画,它就落在弹幕下面
     * (见 [MediaTopScrim])。
     */
    topScrim: Boolean = false,
    /**
     * 播放器状态之外的等待:取流协程在飞、失败后的重试退避。这些窗口里播放器停在 IDLE,
     * 光看 BUFFERING 盖不住,由页面把服务报的 loading 传进来,和缓冲共用同一个指示器。
     */
    externalLoading: Boolean = false,
    modifier: Modifier = Modifier,
    gestures: PlayerGestureOptions = PlayerGestureOptions(),
    /** 长按画面时的临时倍速,由设置页给(`SettingsStore.FAST_FORWARD_SPEEDS`)。 */
    fastForwardSpeed: Float = SettingsStore.DEFAULT_FAST_FORWARD_SPEED,
    /** 全屏顶栏右端的东西。只在全屏、控件可见且未锁定时组合(顶栏本身就这样)。 */
    topBarActions: @Composable RowScope.() -> Unit = {},
    /**
     * 内嵌时右上角的东西(视频页的分享)。**跟控件一起显隐**:画面上常驻一颗按钮,看视频时它
     * 一直压在画面的右上角。左上角的返回不走这里,见 [MediaBackButton] 为什么它常驻。
     */
    embeddedTopActions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable PlayerShellScope.() -> Unit = {},
    controlBar: @Composable PlayerShellScope.() -> Unit = {},
    /**
     * 盖在一切之上的面板(播放设置)。**单独一个槽,不放进 [controlBar]**:控制条整条随显隐进出,
     * 面板放在里面就被它一起收走;也不放进 [overlay]:那一层在控制条下面,面板会被控制条压住。
     */
    panel: @Composable PlayerShellScope.() -> Unit = {},
    /**
     * 窗口此刻在画中画里。**只剩画面和 overlay**:控件收起、中央播放键不画。overlay 照画,
     * 小窗里弹幕画不画由调用方按设置决定(见 BilbyPlayer)。小窗上的触摸不会交给应用,点它
     * 出来的是系统那一层(播放、暂停由 MediaSession 自动给),所以不必另外关手势。
     */
    pip: Boolean = false,
) {
    // 组件动效走 spring,不走转场那套 tween。easing-and-duration 页的注:"In the expressive
    // update, components and motion now use the motion physics system, which uses springs.
    // Products should migrate to the new system." 位移用 spatial,透明度用 effects ——
    // effects 那组是无回弹的,透明度回弹既没有物理意义也看得出来。
    //
    // 取 standard 那一套,不取外层主题的 expressive:理由同 [PlayerTheme]。这几个规格在壳的
    // 主题层外面就算好了,读 MaterialTheme 拿到的还是外层那一套,所以直接点名。
    val motion = PlayerMotion
    val spatialSpec = motion.fastSpatialSpec<IntSize>()
    // scaleIn/scaleOut 动的是 Float,和展开收起不是同一个类型参数。
    val scaleSpec = motion.fastSpatialSpec<Float>()
    val spatialOffsetSpec = motion.fastSpatialSpec<IntOffset>()
    val effectsSpec = motion.fastEffectsSpec<Float>()

    // 画面比例。**null 是"还不知道",不是一个兜底值。** 竖屏视频、4:3 老片都存在,而写死的
    // 16:9 在 9:16 的流上不是差一点:`aspectRatio(16f / 9f)` 正好铺满 16:9 的容器,画面被横着
    // 拉开,看不出这是个"还没量到"的状态。不知道就不摆比例 —— 那时还没有帧,看到的是黑底;
    // 量到之后按真实比例收进去,多出来的地方留黑边。
    //
    // 初值当场读,不等下面的 DisposableEffect:effect 里写的状态要到下一帧才重组,流已经在播时
    // (页面重建、从听视频切回来)头一帧就按未知比例铺满,画面被拉伸一下。
    var videoAspect by remember { mutableStateOf(player.videoSize.displayAspectOr(null)) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    /**
     * "要不要放",不是"此刻在不在出声"。中央播放键的形态看它:缓冲中、取流中 [isPlaying] 都是
     * false,拿它画的话,起播那几百毫秒里加载的空档会闪出一帧"停着"的正圆播放键,紧接着又变回
     * 加载、再变成暂停。
     */
    var playWhenReady by remember { mutableStateOf(player.playWhenReady) }
    var buffering by remember { mutableStateOf(player.playbackState == PlayerPhase.BUFFERING) }
    /**
     * 这个壳挂上以来播放器到过 READY 没有。**起播那一段等待和播放途中的缓冲分开对待**,
     * 见下面 loadingVisible 的说明。
     */
    var everReady by remember { mutableStateOf(player.playbackState == PlayerPhase.READY) }

    DisposableEffect(player) {
        val listener = object : PlayerListener {
            @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
            override fun onPlayWhenReadyChanged(ready: Boolean) {
                playWhenReady = ready
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == PlayerPhase.BUFFERING
                if (playbackState == PlayerPhase.READY) everReady = true
            }

            // **每一批事件都重读一次尺寸,不是只听 onVideoSizeChanged。**
            //
            // 这里的 player 是 MediaController,尺寸是从 session 同步过来的,而"同步到了"
            // 和"发了一条 onVideoSizeChanged"是两回事。翻 media3 1.10.1 的 session 源码,
            // 有两处同步不发事件:
            //
            // - 连上的那一刻,`MediaControllerImplBase.onConnected` 把整份 PlayerInfo 直接
            //   赋值,一条 Player 事件都不发;而在那之前 `MediaController.getVideoSize()`
            //   硬编码返回 `VideoSize.UNKNOWN`。也就是说"连上时就已经知道的那个尺寸"既不在
            //   下面那句初始读里,也不会以事件的形式到。
            // - 之后只要还有 masked 命令在飞,`onPlayerInfoChanged` 会把收到的 PlayerInfo
            //   攒进 `pendingPlayerInfo` 直接 return,不派发。这一页进来就发一条 masked 命令
            //   (VideoScreen 里关视频轨那句 `trackSelectionParameters`)。
            //
            // 漏掉的后果是画面一直按上一条(或未知)的比例摆着,而重读一个字段不花什么。
            override fun onEvents() {
                videoAspect = player.videoSize.displayAspectOr(videoAspect)
            }
        }
        // 接上来时流可能已经在播了(页面重建、或从听视频切回来),那一次事件早就发过。
        videoAspect = player.videoSize.displayAspectOr(videoAspect)
        buffering = player.playbackState == PlayerPhase.BUFFERING
        playWhenReady = player.playWhenReady
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 常亮的条件是**此刻真的挂着画面**,不只是在出声,更不是"页面在前台"。有画面才有理由
    // 不让屏幕息:人正在看。
    //
    // [attached] 就是这个判据,不必另开一条通道:它已经决定了挂 PlayerSurface 还是画占位封面。
    // 直播的纯音频模式因此自然落在不常亮那一侧——画面位置是一张封面,而声音本来就该能息屏
    // 接着听。听视频不受影响:那条分支上这个壳根本不会被组合。
    KeepScreenOn(isPlaying && attached)

    var position by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragPosition by remember { mutableStateOf<Long?>(null) }
    var resumeAfterDrag by remember { mutableStateOf(false) }

    var userSpeed by remember { mutableFloatStateOf(1f) }
    var isFastForwarding by remember { mutableStateOf(false) }

    var controlsVisible by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    // 每次操作控件都让自动隐藏重新计时,靠这个计数把 LaunchedEffect 重启。
    var interactionNonce by remember { mutableIntStateOf(0) }
    // 光标停在控件上(顶栏、控制条、中央键、锁)时不自动隐藏。只看移动的话,光标停在进度条上
    // 不动,3 秒后控制条就在光标底下收走了。几块控件共用一个 source:hoverable 在控件退场
    // 被移除时会补发 Exit,悬停状态不会卡在 true。
    val controlsHover = remember { MutableInteractionSource() }
    val hoveringControls by controlsHover.collectIsHoveredAsState()

    // 手势的反馈只有画面正中那个浮层,而横屏时手指往往正压在它上面。触感补的是"这一下认了"
    // 这件事:长按真的进了加速、锁真的合上了、拖到取消区了。
    val haptics = LocalHapticFeedback.current
    val playback = LocalPlaybackHost.current
    val brightness = rememberWindowBrightness()
    val volume = rememberMediaVolume()

    /** 正在进行的手势;横划与纵划在第一段位移里定死,见下面的 onDrag。 */
    var gesture by remember { mutableStateOf<PlayerGesture?>(null) }
    var dragStartX by remember { mutableFloatStateOf(0f) }

    /** 纵划时浮层要显示的百分比。 */
    var adjustValue by remember { mutableFloatStateOf(0f) }

    /**
     * 双击进退的短暂提示,非 null 时显示;正负决定贴哪一边。**提示还在时同方向再双击就累加**
     * (见 [accumulateNudge]),换方向从头算。每次双击照旧只往前后挪 10 秒,累加的只是读数。
     */
    var seekNudgeMillis by remember { mutableStateOf<Long?>(null) }

    /**
     * 双击画面中间切了播放/暂停。**让中央播放键亮一下**,它从一种形态变到另一种的那一下就是
     * 反馈;不把整套控件都唤出来 —— 人只是想停一下,不是要调什么。
     *
     * 计数器而不是布尔:连着双击两次要各亮一次,布尔第二次没有变化。
     */
    var playToggleFlash by remember { mutableIntStateOf(0) }
    var flashVisible by remember { mutableStateOf(false) }
    LaunchedEffect(playToggleFlash) {
        if (playToggleFlash == 0) return@LaunchedEffect
        flashVisible = true
        delay(PLAY_TOGGLE_FLASH_MILLIS)
        flashVisible = false
    }

    /**
     * 横划 seek 时手指是不是落在了取消区(顶部两角)。非 null 表示"松手就取消"。
     * null 表示这次拖拽压根不允许取消 —— 见 [SeekCancelZoneFraction] 上的说明。
     */
    var seekCancelArmed by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(seekNudgeMillis) {
        if (seekNudgeMillis == null) return@LaunchedEffect
        delay(HINT_VISIBLE_MILLIS)
        seekNudgeMillis = null
    }

    /**
     * 鼠标还是手指,决定点按手势按哪套约定解释,见 [PointerSource]。**鼠标**:单击播放/暂停,
     * 双击全屏,移动鼠标唤出控件 —— 桌面播放器的通行约定,鼠标没有"点一下看看控件"的必要,
     * 光标一动控件就出来了。**手指**:单击切换控件,双击按落点进退或暂停,和原来一样。
     */
    val pointerSource = LocalPointerSource.current

    /** 滚轮与方向键调音量时,音量浮层停留多久;每调一下重新计时。 */
    var levelHintNonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(levelHintNonce) {
        if (levelHintNonce == 0) return@LaunchedEffect
        delay(HINT_VISIBLE_MILLIS)
        // 这段时间里开始了拖拽的话,浮层归拖拽管,不在这里收。
        if (dragPosition == null && (gesture as? PlayerGesture.Adjust)?.kind == VerticalAdjust.Volume) gesture = null
    }

    /** 音量加减一档:滚轮一格、方向键一下都是这一步,浮层用纵划那一个。 */
    val nudgeVolume: (Float) -> Unit = nudge@{ step ->
        val control = volume ?: return@nudge
        val value = (control.current() + step).coerceIn(0f, 1f)
        control.set(value)
        adjustValue = value
        gesture = PlayerGesture.Adjust(VerticalAdjust.Volume, value)
        levelHintNonce++
    }

    val focusRequester = remember { FocusRequester() }

    /** 定下这一次拖拽在做什么。返回 null 表示这次不做事(这一档手势被关掉)。 */
    val startGesture: (Boolean, Long) -> PlayerGesture? = start@{ horizontal, playerPosition ->
        if (horizontal) {
            return@start if (gestures.seek) PlayerGesture.Seek(playerPosition) else null
        }
        if (!gestures.brightnessAndVolume) return@start null
        val onLeftHalf = dragStartX < 0.5f
        if (onLeftHalf) {
            brightness?.let { PlayerGesture.Adjust(VerticalAdjust.Brightness, it.current()) }
        } else {
            volume?.let { PlayerGesture.Adjust(VerticalAdjust.Volume, it.current()) }
        }
    }

    // **有人在看这个读数的时候查得勤一些。** 控件收起时进度条和时间都不在屏上,500ms 只是
    // 为了让下次唤出控件时数字已经是对的;控件展开或正在拖动时这个数就是屏幕上一直在动的
    // 东西,500ms 的量化让秒数看起来一顿一顿,而拖完松手那一下的回弹也要等最多半秒。
    //
    // 不一律用快的那一档:控件收起是这一页最常见的状态(还包括息屏只出声),而轮询本身要
    // 跨 binder 问一次 session。
    val pollInterval =
        if (controlsVisible || dragPosition != null) POSITION_POLL_ACTIVE_MILLIS
        else POSITION_POLL_IDLE_MILLIS
    LaunchedEffect(player, pollInterval) {
        while (true) {
            if (dragPosition == null) position = player.currentPosition
            bufferedPosition = player.bufferedPosition
            duration = player.duration.coerceAtLeast(0)
            delay(pollInterval)
        }
    }

    // 自动隐藏的时长按系统无障碍设置放宽。设置里「操作时长」调长了的人,就是来不及在 3 秒里
    // 找到按钮的人;控件里有图标、有文字、有可点的东西,三样都报上去,系统按最宽的那条给。
    val accessibilityManager = LocalAccessibilityManager.current
    val hideDelayMillis = remember(accessibilityManager) {
        accessibilityManager?.calculateRecommendedTimeoutMillis(
            CONTROLS_HIDE_DELAY_MILLIS,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: CONTROLS_HIDE_DELAY_MILLIS
    }
    // 进画中画那一刻把控件收起;出来之后照常点一下再唤出。
    LaunchedEffect(pip) { if (pip) controlsVisible = false }
    LaunchedEffect(controlsVisible, isPlaying, dragPosition, menuOpen, hoveringControls, interactionNonce) {
        // 暂停时控件常驻:此时用户多半正要点什么,把它藏掉只会逼人再点一次。
        if (controlsVisible && isPlaying && dragPosition == null && !menuOpen && !hoveringControls) {
            delay(hideDelayMillis)
            controlsVisible = false
        }
    }

    // 传布尔而不是比例本身:比例是浮点,解码过程里会有细微抖动,直接当 DisposableEffect
    // 的 key 会让全屏反复重设方向。
    FullscreenEffect(
        isFullscreen,
        // 还不知道比例时按横屏处理(旧行为):比例总在第一帧之前到,
        // EVENT_VIDEO_SIZE_CHANGED 早于 EVENT_RENDERED_FIRST_FRAME。
        isPortraitVideo = videoAspect?.let { it < 1f } == true,
        fullBleed = fullBleed,
        hideStatusBar = hideStatusBar,
    )

    val displayPosition = dragPosition ?: position

    val togglePlayPause = {
        // 看 playWhenReady,和中央播放键画出来的形态同一个判据:缓冲中键上画的是"暂停",
        // 按下去就该是暂停,而不是因为此刻还没出声就再发一次播放。
        when {
            player.playWhenReady -> player.pause()
            else -> player.playOrReplay(playback)
        }
        interactionNonce += 1
    }

    val actions = PlayerShellActions(
        keepAwake = { interactionNonce += 1 },
        setMenuOpen = { menuOpen = it },
        togglePlayPause = togglePlayPause,
        setSpeed = {
            userSpeed = it
            if (!isFastForwarding) player.setPlaybackSpeed(it)
            interactionNonce++
        },
        seekStart = {
            resumeAfterDrag = player.isPlaying
            player.pause()
        },
        seekTo = { dragPosition = it },
        seekFinished = {
            dragPosition?.let { target ->
                player.seekTo(target)
                position = target
                dragPosition = null
                if (resumeAfterDrag) player.play()
            }
            interactionNonce++
        },
        toggleFullscreen = {
            onFullscreenChange(!isFullscreen)
            interactionNonce++
        },
    )

    // 壳里的一切(控件、菜单、手势提示,以及调用方塞进来的 overlay 与控制条)都按深色主题取色,
    // 见 [PlayerTheme]。
    PlayerTheme {
    Box(
        modifier = modifier
            .background(Color.Black)
            // 鼠标:在播放器范围内移动就唤出控件并重新计时,按下时把键盘焦点拿到播放器上(快捷键
            // 要它)。挂在最外层、在 Initial 阶段只看不吃:挂在画面那一层的话,光标停在控制条上
            // 时移动事件被控制条接走,控件会在光标底下收起。焦点只在按下时拿,不在移动时拿:
            // 光标路过就抢焦点,别处输入框里正在打的字会落到播放器上。
            //
            // 光标离开播放器就立即收起,不等计时:两栏布局里光标移去右栏读评论时,控件没有理由
            // 在画面上再挂 3 秒。暂停时照旧常驻,理由同自动隐藏。菜单开着时不收:菜单是弹出层,
            // 光标移进菜单对这个 Box 来说也是一次离开。
            .pointerInput(locked) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (locked || event.changes.firstOrNull()?.type != PointerType.Mouse) continue
                        when (event.type) {
                            PointerEventType.Move -> {
                                controlsVisible = true
                                interactionNonce++
                            }
                            PointerEventType.Exit -> {
                                if (isPlaying && !menuOpen && dragPosition == null) controlsVisible = false
                            }
                            PointerEventType.Press -> focusRequester.requestFocus()
                        }
                    }
                }
            },
    ) {
        val scope = PlayerShellScope(
            box = this,
            isPlaying = isPlaying,
            isFullscreen = isFullscreen,
            locked = locked,
            controlsVisible = controlsVisible,
            positionMillis = displayPosition,
            bufferedPositionMillis = bufferedPosition,
            durationMillis = duration,
            speed = userSpeed,
            actions = actions,
        )

        // 播放器装的是不是这一页要的东西,决定挂画面还是画占位——不判断的话,点开新内容到
        // 播放器真正切过去之间那段取流 + prepare 的窗口里,这里会一直显示上一条的残留帧。
        // 占位保持容器比例,不跳布局;切回真画面**不做转场**——它和取流、prepare、codec
        // 初始化撞在同一瞬间,加动效只会让"卡"更明显。
        if (attached) {
            VideoSurface(
                player = player,
                modifier = Modifier
                    .align(Alignment.Center)
                    // 比例未知时铺满容器:此刻还没有帧,摆一个猜的比例只会让第一帧落进错的
                    // 矩形里,而 SurfaceView 上的画面是被拉到 view 的矩形里的,不会自己留边。
                    .then(videoAspect?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize()),
            )
        } else if (placeholderCoverUrl.isNotEmpty()) {
            BiliAsyncImage(
                url = placeholderCoverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 封面也拿不到时退化成纯黑——Box 本身的背景已经是黑的,不用再多画一层。

        // 取流在轮到这一条时才发生(LazyMediaSource),那段时间加上 prepare 画面全黑,
        // 没有指示的话和死机分不出来。BUFFERING 盖住取流、prepare 和播放途中的再缓冲;
        // externalLoading 补上播放器停在 IDLE 的等待(重试退避)。对观看的人全是同一件事:
        // 声音画面停了,但马上会回来 —— 所以是同一个指示器,不是几个各画各的。
        //
        // **短于 200ms 的缓冲不给指示器**([rememberLoadingVisible])。拖动进度、切清晰度、
        // 每次 seek 之后播放器都会进 BUFFERING 一两帧,画面正中闪一下圈比不闪更像出了问题。
        // 计时器嵌在条件内侧:那样每一次缓冲窗口都重新开始数,而不是整页只宽限开头那一次。
        //
        // 指示器本身画在中央播放键里(见下面的 [CenterPlayButton]),这里只算要不要显示。
        //
        // **起播那一段不走 200ms 的门槛。** 门槛是给播放途中的短暂缓冲的;起播之前页面已经在
        // 画面正中摆着同一颗键的加载形态(见 VideoScreen 的占位),壳接上来之后再等 200ms 才亮,
        // 中间就闪出一帧"暂停"的方块。所以第一次 READY 之前,在等就立刻显示。
        val waiting = buffering || externalLoading
        val loadingNow = when {
            !waiting -> false
            !everReady -> true
            else -> rememberLoadingVisible()
        }
        // **退场留一段宽限。** 起播是两段等待接力:先是取流(externalLoading),再是播放器缓冲
        // (BUFFERING),交接处常有一两帧、有时一两百毫秒两者都是 false。没有宽限的话指示器在
        // 空档里撤掉又回来,播放键跟着闪一下原形。起播那一段空档更长,宽限也给得更长。
        //
        // 初值取这一刻的读数:壳挂上时已经在等,键就直接以加载形态出现,不从别的形态变过去。
        var loadingVisible by remember { mutableStateOf(loadingNow) }
        LaunchedEffect(loadingNow) {
            if (loadingNow) {
                loadingVisible = true
            } else {
                delay(if (everReady) LOADING_EXIT_GRACE_MILLIS else STARTUP_EXIT_GRACE_MILLIS)
                loadingVisible = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // 键盘:空格播放/暂停,←→ 进退(与双击同一个步长),↑↓ 音量,F 全屏。Esc 退全屏走
                // 返回那条路(见 FullscreenEffect 旁的 BackHandler),不在这里接。
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || locked) return@onKeyEvent false
                    when (event.key) {
                        Key.Spacebar -> {
                            togglePlayPause()
                            playToggleFlash++
                        }
                        Key.DirectionLeft, Key.DirectionRight -> {
                            if (!gestures.seek) return@onKeyEvent false
                            val delta = if (event.key == Key.DirectionLeft) -DOUBLE_TAP_SEEK_MILLIS else DOUBLE_TAP_SEEK_MILLIS
                            nudgeSeek(player, delta)
                            seekNudgeMillis = accumulateNudge(seekNudgeMillis, delta)
                        }
                        Key.DirectionUp -> nudgeVolume(VOLUME_STEP)
                        Key.DirectionDown -> nudgeVolume(-VOLUME_STEP)
                        Key.F -> onFullscreenChange(!isFullscreen)
                        else -> return@onKeyEvent false
                    }
                    true
                }
                .focusRequester(focusRequester)
                .focusable()
                // 全屏时滚轮调音量。只在全屏接管:内嵌时画面在可滚动的页面顶上,滚轮是用来翻页、
                // 收起画面的。
                .pointerInput(locked, isFullscreen) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type != PointerEventType.Scroll || !isFullscreen || locked) continue
                            val change = event.changes.firstOrNull() ?: continue
                            val dy = change.scrollDelta.y
                            if (dy != 0f) {
                                nudgeVolume(if (dy < 0f) VOLUME_STEP else -VOLUME_STEP)
                                change.consume()
                            }
                        }
                    }
                }
                // 用鼠标看、控件收起且在播放时,光标跟着藏起来,不留一个箭头钉在画面上。
                .playerCursor(hidden = !pointerSource.isTouchLike && !controlsVisible && isPlaying)
                .pointerInput(player, locked, gestures) {
                    if (locked) {
                        // 锁上时只留"点一下把解锁按钮唤出来",其余手势一概不接。
                        detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                        return@pointerInput
                    }
                    detectTapGestures(
                        onTap = {
                            if (pointerSource.isTouchLike) {
                                controlsVisible = !controlsVisible
                            } else {
                                togglePlayPause()
                                playToggleFlash++
                            }
                            interactionNonce++
                        },
                        // 双击按落点分四段:两侧各四分之一是 ±10 秒,中间仍是播放/暂停,
                        // 对齐 PiliPlus 的左右边缘手势区域。
                        //
                        // 中间保留下来是因为双击暂停本来就在,直接换掉等于拿走一个已有的常用
                        // 操作;而三分法是 YouTube 立起来的惯例,两侧那两块也正是横屏握持时
                        // 拇指自然落到的位置。关掉 seek 的场景(直播)下四段退化成整屏播放/暂停。
                        onDoubleTap = double@{ offset ->
                            if (!pointerSource.isTouchLike) {
                                onFullscreenChange(!isFullscreen)
                                return@double
                            }
                            val quarter = size.width / 4f
                            when {
                                gestures.seek && offset.x < quarter ->
                                    nudgeSeek(player, -DOUBLE_TAP_SEEK_MILLIS)
                                        .also { seekNudgeMillis = accumulateNudge(seekNudgeMillis, -DOUBLE_TAP_SEEK_MILLIS) }

                                gestures.seek && offset.x > size.width - quarter ->
                                    nudgeSeek(player, DOUBLE_TAP_SEEK_MILLIS)
                                        .also { seekNudgeMillis = accumulateNudge(seekNudgeMillis, DOUBLE_TAP_SEEK_MILLIS) }

                                else -> {
                                    togglePlayPause()
                                    playToggleFlash++
                                }
                            }
                            interactionNonce++
                        },
                        onLongPress = {
                            if (!gestures.fastForward) return@detectTapGestures
                            isFastForwarding = true
                            player.setPlaybackSpeed(fastForwardSpeed)
                            // 长按到底了没有,光看画面分不出来:2x 的画面和 1x 的画面在最初那
                            // 半秒里差不多。系统长按本来就带这一下,这个手势没有理由例外。
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
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
                .pointerInput(player, locked, duration, gestures) {
                    if (locked) return@pointerInput
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val height = size.height.toFloat().coerceAtLeast(1f)
                    var accumulated = Offset.Zero

                    detectDragGestures(
                        onDragStart = { start ->
                            accumulated = Offset.Zero
                            dragStartX = start.x / width
                            // **起手就在取消区里的话,这次拖拽不给取消。** 顶部两角本来就是
                            // 横屏握持时拇指容易搭上去的地方;不排除的话,从那儿起手的 seek
                            // 一动就被判成"要取消",而用户根本没往哪儿拖。
                            seekCancelArmed =
                                if (inSeekCancelZone(start, width, height)) null else false
                        },
                        onDragEnd = {
                            // 拖拽期间只动本地位置,松手才真 seek:每帧 seek 会让播放器不停丢
                            // 缓冲重新起播,表现为拖不动。和进度条的处理是同一套。
                            if (gesture is PlayerGesture.Seek && seekCancelArmed != true) {
                                dragPosition?.let { target ->
                                    player.seekTo(target)
                                    position = target
                                }
                            }
                            gesture = null
                            dragPosition = null
                            seekCancelArmed = null
                            interactionNonce++
                        },
                        onDragCancel = {
                            gesture = null
                            dragPosition = null
                            seekCancelArmed = null
                        },
                    ) { change, delta ->
                        change.consume()
                        accumulated += delta
                        if (seekCancelArmed != null) {
                            val inZone = inSeekCancelZone(change.position, width, height)
                            // 进区的那一下给触感,出区不给:取消区没有边框,手指还压在画面上,
                            // 浮层上那行字改成"松手取消"是唯一的提示,而手指正挡在它附近。
                            // 只报"越过了门槛"这一次,所以判的是 false → true 那一次跳变。
                            if (inZone && seekCancelArmed == false) {
                                haptics.performHapticFeedback(
                                    HapticFeedbackType.GestureThresholdActivate,
                                )
                            }
                            seekCancelArmed = inZone
                        }

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
                                    VerticalAdjust.Brightness -> brightness?.set(value)
                                    VerticalAdjust.Volume -> volume?.set(value)
                                }
                            }
                        }
                    }
                },
        )

        // 顶部渐变在 overlay **之前**,于是弹幕落在它上面而不是底下。托的是页面画在壳外面的
        // 那两个按钮(返回、分享),见 [MediaTopScrim]。
        //
        // **跟控件一起显隐,不常驻。** 常驻时看视频的大部分时间里画面顶上都压着一截黑,而常驻的
        // 返回键自带半透明容器(mediaControlContainer),不靠这条渐变也看得清。控件出来时它托住
        // 右上角的分享与控件那一层,同全屏顶栏。
        androidx.compose.animation.AnimatedVisibility(
            visible = topScrim && controlsVisible && !locked,
            enter = fadeIn(effectsSpec),
            exit = fadeOut(effectsSpec),
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) { MediaTopScrim() }
        }

        // 内容层:压在画面与手势层之上、控制条之下——声明顺序即 z 序。没有 pointerInput,
        // 不拦截手势,底下的双击/拖拽照常命中。
        scope.overlay()

        // 手势反馈分三处,按手势发生在哪儿摆(见 [PlayerHud] 的说明):连续调整在正中、
        // 双击在落点那一侧、长按加速在顶上。
        //
        // 正中那一处的优先级按"哪个正在发生"排:拖拽中的进退最要紧,它还要显示能不能取消。
        val currentGesture = gesture
        val hud: PlayerHud? = when {
            // 只认画面上的横划。拖进度条时读数在手指上方的气泡里(见 SeekBar 的 timeBubble),
            // 这里再亮一个框就是同一个时间印两遍。
            dragPosition != null && currentGesture is PlayerGesture.Seek -> PlayerHud.Seek(
                targetMillis = displayPosition,
                durationMillis = duration,
                deltaMillis = displayPosition - currentGesture.startPositionMillis,
                cancelArmed = seekCancelArmed == true,
            )

            (currentGesture as? PlayerGesture.Adjust)?.kind == VerticalAdjust.Brightness -> {
                val percent = (adjustValue * 100).roundToInt()
                PlayerHud.Level(
                    icon = if (adjustValue >= 0.5f) Icons.Filled.BrightnessHigh else Icons.Filled.BrightnessLow,
                    label = stringResource(Res.string.player_brightness, percent),
                    value = adjustValue,
                )
            }

            (currentGesture as? PlayerGesture.Adjust)?.kind == VerticalAdjust.Volume -> {
                val percent = (adjustValue * 100).roundToInt()
                PlayerHud.Level(
                    icon = when {
                        adjustValue <= 0f -> Icons.AutoMirrored.Filled.VolumeOff
                        adjustValue < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                        else -> Icons.AutoMirrored.Filled.VolumeUp
                    },
                    label = stringResource(Res.string.player_volume, percent),
                    value = adjustValue,
                )
            }

            else -> null
        }
        // 退场的那几帧里 hud 已经是 null 了,而那时框还在屏上,总得有东西可画 —— 留住最后一份
        // 非空的内容,和 [dev.bilby.ui.video.TripleToast] 里的 `shown` 是同一个写法。
        var shownHud by remember { mutableStateOf<PlayerHud?>(null) }
        if (hud != null) shownHud = hud

        // 淡入淡出而不是直接出现:三种读数在同一个位置上互相替换,硬切时看起来像画面上闪了
        // 一下。**只给透明度,不给位移或缩放** —— 它是"手势正在发生"的读数,位置固定在正中
        // 才不用每次重新找;effects 那一档也正是无回弹的那组。
        AnimatedVisibility(
            visible = hud != null,
            enter = fadeIn(effectsSpec),
            exit = fadeOut(effectsSpec),
            modifier = Modifier.align(Alignment.Center),
        ) {
            shownHud?.let { PlayerHudOverlay(it) }
        }

        // 双击:贴着点下去的那一侧。方向在退场时也要留住,否则淡出那几帧会跳到另一边。
        var shownNudge by remember { mutableStateOf(0L) }
        seekNudgeMillis?.let { shownNudge = it }
        AnimatedVisibility(
            visible = seekNudgeMillis != null,
            enter = fadeIn(effectsSpec),
            exit = fadeOut(effectsSpec),
            modifier = Modifier
                .align(if (shownNudge >= 0) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(DoubleTapArcFraction),
        ) {
            DoubleTapSeekHint(shownNudge)
        }

        // 长按加速:顶上居中,从上沿滑进来 —— 它贴着哪条边就从哪条边进(transitions 页)。
        AnimatedVisibility(
            visible = isFastForwarding,
            enter = slideInVertically(spatialOffsetSpec) { -it } + fadeIn(effectsSpec),
            exit = slideOutVertically(spatialOffsetSpec) { -it } + fadeOut(effectsSpec),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.barsAndCutout)
                .padding(top = Spacing.Comfortable),
        ) {
            SpeedBoostCapsule(stringResource(Res.string.player_fast_forwarding, formatSpeed(fastForwardSpeed)))
        }

        // 全屏顶栏。全屏下没有别的东西说明"在看什么"和"怎么退出":系统栏是隐藏的,
        // 返回手势在锁屏态下也被吃掉了。竖屏不显示,那里标题就在播放器下面第一行。
        //
        // **进出方向由它贴着哪条边决定**,规范原文:"The direction a component enters is informed
        // by their location on screen, expanding away from the device edge. A menu at the top of
        // the screen expands downwards";Android 那一档还要求
        // "components expand and collapse along the x or y axis as they slide on and off screen"。
        // 所以顶栏从上边缘进出,而不是原地淡入。
        //
        // **滑半个栏高加淡入,不是展开。** 展开(expandVertically)是把整块裁着长出来,渐变底和
        // 按钮一起被切着露出,读起来像一块幕布在拉;整栏滑进来又动得太多,一轻触就是半屏在动。
        // 滑一半、透明度补上另一半,方向还在,幅度小了。
        AnimatedVisibility(
            visible = isFullscreen && controlsVisible && !locked,
            enter = slideInVertically(spatialOffsetSpec) { -it / 2 } + fadeIn(effectsSpec),
            exit = slideOutVertically(spatialOffsetSpec) { -it / 2 } + fadeOut(effectsSpec),
            modifier = Modifier.align(Alignment.TopCenter).hoverable(controlsHover),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(ControlScrimBottom, Color.Transparent)),
                    )
                    .windowInsetsPadding(WindowInsets.barsAndCutout)
                    .padding(end = Spacing.Comfortable, bottom = Spacing.Comfortable),
            ) {
                PlayerIconButton(
                    onClick = { onFullscreenChange(false) },
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.player_exit_fullscreen),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = FixedColors.OnMedia,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // **只有这一个带权重的孩子。** 之前是标题 `weight(1f, fill = false)` 再跟
                    // 一个 `Spacer(weight(1f))`,两个权重把剩余宽度对半分,右端那颗按钮因此停在
                    // 中间偏右。标题吃掉全部剩余宽度,动作自然被顶到边上。
                    modifier = Modifier.weight(1f),
                )
                // 顶栏右端的槽。**切集放这里,不放控制条** —— 控制条上的东西回答的都是"这个
                // 播放器现在怎么放"(倍速、清晰度、字幕、弹幕、全屏),而切集回答的是"在放
                // 哪一条",和它左边那个标题是同一类。风格指南 §4.3 那条判据问的正是这个。
                topBarActions()
            }
        }

        // 内嵌时的右上角。和全屏顶栏同一套进出,只是没有那条渐变底:内嵌态的渐变是上面那条
        // [topScrim],画在弹幕之下,同样跟控件走。
        AnimatedVisibility(
            visible = !isFullscreen && controlsVisible && !locked,
            enter = slideInVertically(spatialOffsetSpec) { -it / 2 } + fadeIn(effectsSpec),
            exit = slideOutVertically(spatialOffsetSpec) { -it / 2 } + fadeOut(effectsSpec),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.barsAndCutout)
                .padding(Spacing.Tight)
                .hoverable(controlsHover),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) { embeddedTopActions() }
        }

        // 锁按钮:锁上后它是唯一还能点的东西。只在全屏显示。
        //
        // **放在右侧,不是 PiliPlus 那样的左侧。** 全屏时画面铺进短边(SHORT_EDGES),挖孔就在
        // 横屏的左边缘 —— 左侧那个位置整个压在挖孔底下,而它恰恰是锁上之后唯一还能点的控件。
        // 挖孔只占一条边,右侧永远是干净的;`barsAndCutout` 那句是为了两种旋转都成立,横屏
        // 反过来持时挖孔会换到右边。
        //
        // 锁按钮浮在画面中间,不贴任何一条边,所以它走的是 enter/exit 里"在主界面语境中
        // 出现的组件"那一支:缩放加淡入,没有方向可言。
        AnimatedVisibility(
            visible = isFullscreen && controlsVisible,
            enter = scaleIn(scaleSpec) + fadeIn(effectsSpec),
            exit = scaleOut(scaleSpec) + fadeOut(effectsSpec),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .windowInsetsPadding(WindowInsets.barsAndCutout)
                .padding(end = Spacing.Cozy)
                .hoverable(controlsHover),
        ) {
            val lockDescription =
                stringResource(if (locked) Res.string.player_unlock else Res.string.player_lock)
            PlayerTooltip(lockDescription) {
                PlayerIconButton(
                    onClick = {
                        // 锁上之后整块画面都不响应,而这件事在画面上只表现为"控件没了"——
                        // 和自动隐藏长得一模一样。两种触感分开:合上和打开是相反的动作。
                        haptics.performHapticFeedback(
                            if (locked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                        )
                        onLockedChange(!locked)
                    },
                    icon = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = lockDescription,
                    // 锁着时用选中态的底色:这时它是画面上唯一的东西,得一眼认出"现在是锁着的"。
                    selected = locked,
                )
            }
        }

        // 中央播放键。**控件在时它在;控件收起而仍在加载时,只留它一个,承载加载指示。**
        //
        // 播放键原先是控制条左端一个和倍速、清晰度同样大小的裸图标,整排六七个白色线框挤在
        // 右下角,分不出哪个是主的。它是这个播放器最常按的东西,放大、放到正中。
        //
        // 锁上时不出现:锁上之后唯一能点的是解锁按钮。
        //
        // **手势读数在屏时让开。** 两者都在正中,播放键画在读数之后,控件还没收起时开始横划,
        // 暂停那个方块就压在进退读数上。手势进行中没有人要去点播放键,松手后读数淡出、它再回来。
        AnimatedVisibility(
            visible = !pip && hud == null && ((controlsVisible && !locked) || loadingVisible || flashVisible),
            enter = scaleIn(scaleSpec, initialScale = CenterButtonEnterScale) + fadeIn(effectsSpec),
            exit = scaleOut(scaleSpec, targetScale = CenterButtonEnterScale) + fadeOut(effectsSpec),
            modifier = Modifier.align(Alignment.Center).hoverable(controlsHover),
        ) {
            CenterPlayButton(
                isPlaying = playWhenReady,
                loading = loadingVisible,
                large = isFullscreen,
                onClick = {
                    togglePlayPause()
                },
            )
        }

        // 控制条贴着下边缘,所以从下边缘进出,幅度同顶栏:滑半个栏高加淡入。
        AnimatedVisibility(
            visible = controlsVisible && !locked,
            enter = slideInVertically(spatialOffsetSpec) { it / 2 } + fadeIn(effectsSpec),
            exit = slideOutVertically(spatialOffsetSpec) { it / 2 } + fadeOut(effectsSpec),
            modifier = Modifier.align(Alignment.BottomCenter).hoverable(controlsHover),
        ) {
            scope.controlBar()
        }

        scope.panel()
    }
    }
}

/** 双击读数的累加:同方向加上去,换方向从这一下重新算。 */
private fun accumulateNudge(current: Long?, step: Long): Long =
    if (current != null && (current > 0) == (step > 0)) current + step else step

@Composable
internal fun Overlay(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(FixedColors.ScrimOnMedia)
            .padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
    ) {
        content()
    }
}

/** 双击 ±N 秒。夹在 [0, duration] 内:越界 seek 在部分实现上会直接把播放器打到 IDLE。 */
private fun nudgeSeek(player: PlayerHandle, deltaMillis: Long) {
    val duration = player.duration.coerceAtLeast(0)
    val target = player.currentPosition + deltaMillis
    player.seekTo(if (duration > 0) target.coerceIn(0L, duration) else target.coerceAtLeast(0L))
}

/**
 * **显示比例,不是像素数之比。** `pixelWidthHeightRatio` 不为 1 的流(变形拉伸编码,少见但
 * 存在)像素本身是长方形的,按 width/height 摆会把画面压扁。media3 自己的
 * `PresentationState.getVideoSizeDp` 也是这么折算的。
 *
 * 尺寸未知(UNKNOWN,或换条时中间那一下的 0×0)时返回 [fallback]:保留上一个已知比例,
 * 画面不会先缩成一条再弹回来。
 */
private fun VideoDimensions.displayAspectOr(fallback: Float?): Float? {
    if (width <= 0 || height <= 0) return fallback
    val par = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
    return width * par / height
}

private const val CONTROLS_HIDE_DELAY_MILLIS = 3_000L

/** 加载指示撤掉之前再留多久,见 loadingVisible。比两段等待之间的空档长,又短到看不出拖沓。 */
private const val LOADING_EXIT_GRACE_MILLIS = 300L

/** 双击切播放后中央播放键亮多久。够看清它变了形,又不久到像是控件被唤出来了。 */
private const val PLAY_TOGGLE_FLASH_MILLIS = 700L

/** 起播那一段的宽限:取流落地到 prepare 进 BUFFERING 之间的空档比播放途中长。 */
private const val STARTUP_EXIT_GRACE_MILLIS = 800L

/** 播放器里的动效,见 [PlayerTheme]。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val PlayerMotion = MotionScheme.standard()

/** 中央播放键进出时从多大缩放起。0.6 而不是 0:从无到有的缩放太猛,读起来像弹出一个对话框。 */
private const val CenterButtonEnterScale = 0.6f
private const val DOUBLE_TAP_SEEK_MILLIS = 10_000L

/** 滚轮一格、方向键一下的音量步长。 */
private const val VOLUME_STEP = 0.05f
private const val HINT_VISIBLE_MILLIS = 700L

/** 控件在屏上、或者正在拖:这个读数是用户此刻盯着的东西。见取值处的说明。 */
private const val POSITION_POLL_ACTIVE_MILLIS = 200L

/** 控件收起(含息屏只出声):没人看,只要下次唤出时是对的就行。 */
private const val POSITION_POLL_IDLE_MILLIS = 500L

/** 控制条渐变的最暗端。壳的全屏顶栏与视频控制条共用同一个值,两处各写一份就会渐变对不上。 */
internal val ControlScrimBottom = Color(0xB3000000)

internal fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

/**
 * 手指在不在"松手取消"的角上。左上和右上各取宽高的 15%。
 *
 * 取顶部两角而不是某一侧:横划 seek 的位移是横向的,取消动作必须往**垂直方向**走才不会
 * 和进退本身混淆;而两角都给,是因为左手右手拖的方向不一样,只给一角等于只照顾一只手。
 */
private fun inSeekCancelZone(point: Offset, width: Float, height: Float): Boolean {
    val inTop = point.y <= height * SeekCancelZoneFraction
    val nearSide = point.x <= width * SeekCancelZoneFraction ||
        point.x >= width * (1f - SeekCancelZoneFraction)
    return inTop && nearSide
}

private const val SeekCancelZoneFraction = 0.15f
