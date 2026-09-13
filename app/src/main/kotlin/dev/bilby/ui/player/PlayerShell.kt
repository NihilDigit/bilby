package dev.bilby.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.ui.compose.PlayerSurface
import dev.bilby.R
import dev.bilby.data.SettingsStore
import dev.bilby.formatDurationMillis
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.rememberLoadingVisible
import dev.bilby.ui.theme.FixedColors
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
    player: Player,
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
    overlay: @Composable PlayerShellScope.() -> Unit = {},
    controlBar: @Composable PlayerShellScope.() -> Unit = {},
) {
    // 组件动效走 spring,不走转场那套 tween。easing-and-duration 页的注:"In the expressive
    // update, components and motion now use the motion physics system, which uses springs.
    // Products should migrate to the new system." 位移用 spatial,透明度用 effects ——
    // effects 那组是无回弹的,透明度回弹既没有物理意义也看得出来。
    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<IntSize>()
    // scaleIn/scaleOut 动的是 Float,和展开收起不是同一个类型参数。
    val scaleSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    // 画面比例。**null 是"还不知道",不是一个兜底值。** 竖屏视频、4:3 老片都存在,而写死的
    // 16:9 在 9:16 的流上不是差一点:`aspectRatio(16f / 9f)` 正好铺满 16:9 的容器,画面被横着
    // 拉开,看不出这是个"还没量到"的状态。不知道就不摆比例 —— 那时还没有帧,看到的是黑底;
    // 量到之后按真实比例收进去,多出来的地方留黑边。
    var videoAspect by remember { mutableStateOf<Float?>(null) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
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
            override fun onEvents(source: Player, events: Player.Events) {
                videoAspect = source.videoSize.displayAspectOr(videoAspect)
            }
        }
        // 接上来时流可能已经在播了(页面重建、或从听视频切回来),那一次事件早就发过。
        videoAspect = player.videoSize.displayAspectOr(videoAspect)
        buffering = player.playbackState == Player.STATE_BUFFERING
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 常亮的条件是**此刻真的挂着画面**,不只是在出声,更不是"页面在前台"。有画面才有理由
    // 不让屏幕息:人正在看。
    //
    // [attached] 就是这个判据,不必另开一条通道:它已经决定了挂 PlayerSurface 还是画占位封面。
    // 直播的纯音频模式因此自然落在不常亮那一侧——画面位置是一张封面,而声音本来就该能息屏
    // 接着听。听视频不受影响:那条分支上这个壳根本不会被组合。
    val view = LocalView.current
    val keepAwake = isPlaying && attached
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }

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

    val context = LocalContext.current
    // 手势的反馈只有画面正中那个浮层,而横屏时手指往往正压在它上面。触感补的是"这一下认了"
    // 这件事:长按真的进了加速、锁真的合上了、拖到取消区了。
    val haptics = LocalHapticFeedback.current
    val brightness = rememberWindowBrightness()
    val volume = rememberMediaVolume(context)

    /** 正在进行的手势;横划与纵划在第一段位移里定死,见下面的 onDrag。 */
    var gesture by remember { mutableStateOf<PlayerGesture?>(null) }
    var dragStartX by remember { mutableFloatStateOf(0f) }

    /** 纵划时浮层要显示的百分比。 */
    var adjustValue by remember { mutableFloatStateOf(0f) }

    /** 双击 ±10 秒的短暂提示,非 null 时显示;正负决定文案。 */
    var seekNudgeMillis by remember { mutableStateOf<Long?>(null) }

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

    /** 定下这一次拖拽在做什么。返回 null 表示这次不做事(这一档手势被关掉)。 */
    val startGesture: (Boolean, Long) -> PlayerGesture? = start@{ horizontal, playerPosition ->
        if (horizontal) {
            return@start if (gestures.seek) PlayerGesture.Seek(playerPosition) else null
        }
        if (!gestures.brightnessAndVolume) return@start null
        val onLeftHalf = dragStartX < 0.5f
        if (onLeftHalf) {
            PlayerGesture.Adjust(VerticalAdjust.Brightness, brightness.current())
        } else {
            PlayerGesture.Adjust(VerticalAdjust.Volume, volume.current())
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

    LaunchedEffect(controlsVisible, isPlaying, dragPosition, menuOpen, interactionNonce) {
        // 暂停时控件常驻:此时用户多半正要点什么,把它藏掉只会逼人再点一次。
        if (controlsVisible && isPlaying && dragPosition == null && !menuOpen) {
            delay(CONTROLS_HIDE_DELAY_MILLIS)
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
        when {
            player.isPlaying -> player.pause()
            // 播完之后位置停在末尾,直接 play() 不会有反应,应有行为是重播。
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0)
                player.play()
            }

            else -> player.play()
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

    Box(modifier = modifier.background(Color.Black)) {
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
            PlayerSurface(
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
        if (buffering || externalLoading) {
            if (rememberLoadingVisible()) {
                LoadingIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(player, locked, gestures) {
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
                        // 拇指自然落到的位置。关掉 seek 的场景(直播)下四段退化成整屏播放/暂停。
                        onDoubleTap = { offset ->
                            val quarter = size.width / 4f
                            when {
                                gestures.seek && offset.x < quarter ->
                                    nudgeSeek(player, -DOUBLE_TAP_SEEK_MILLIS)
                                        .also { seekNudgeMillis = -DOUBLE_TAP_SEEK_MILLIS }

                                gestures.seek && offset.x > size.width - quarter ->
                                    nudgeSeek(player, DOUBLE_TAP_SEEK_MILLIS)
                                        .also { seekNudgeMillis = DOUBLE_TAP_SEEK_MILLIS }

                                else -> togglePlayPause()
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
                                    VerticalAdjust.Brightness -> brightness.set(value)
                                    VerticalAdjust.Volume -> volume.set(value)
                                }
                            }
                        }
                    }
                },
        )

        // 顶部渐变在 overlay **之前**,于是弹幕落在它上面而不是底下。托的是页面画在壳外面的
        // 那两个按钮(返回、分享),见 [MediaTopScrim]。
        if (topScrim) MediaTopScrim()

        // 内容层:压在画面与手势层之上、控制条之下——声明顺序即 z 序。没有 pointerInput,
        // 不拦截手势,底下的双击/拖拽照常命中。
        scope.overlay()

        // 手势反馈只有这一处。四种手势(长按加速、音量、亮度、进退)共用画面正中的同一个框,
        // 各自只换图标和那一行字 —— 以前它们分散在三个位置、三种字号,同一类操作要学三次。
        //
        // 优先级按"哪个正在发生"排:拖拽中的进退最要紧,它还要显示能不能取消。
        val hint: Pair<ImageVector, String>? = when {
            dragPosition != null -> {
                val forward = displayPosition >= position
                val icon = when {
                    seekCancelArmed == true -> Icons.Filled.Close
                    forward -> Icons.Filled.FastForward
                    else -> Icons.Filled.FastRewind
                }
                val text = if (seekCancelArmed == true) {
                    stringResource(R.string.player_seek_release_to_cancel)
                } else {
                    "${formatDurationMillis(displayPosition)} / ${formatDurationMillis(duration)}"
                }
                icon to text
            }

            (gesture as? PlayerGesture.Adjust)?.kind == VerticalAdjust.Brightness -> {
                val percent = (adjustValue * 100).roundToInt()
                val icon =
                    if (adjustValue >= 0.5f) Icons.Filled.BrightnessHigh else Icons.Filled.BrightnessLow
                icon to stringResource(R.string.player_brightness, percent)
            }

            (gesture as? PlayerGesture.Adjust)?.kind == VerticalAdjust.Volume -> {
                val percent = (adjustValue * 100).roundToInt()
                val icon = when {
                    adjustValue <= 0f -> Icons.AutoMirrored.Filled.VolumeOff
                    adjustValue < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                }
                icon to stringResource(R.string.player_volume, percent)
            }

            isFastForwarding -> Icons.Filled.FastForward to
                stringResource(R.string.player_fast_forwarding, formatSpeed(fastForwardSpeed))

            seekNudgeMillis != null -> {
                val delta = seekNudgeMillis ?: 0L
                val icon = if (delta >= 0) Icons.Filled.Forward10 else Icons.Filled.Replay10
                val label = stringResource(
                    if (delta >= 0) R.string.player_seek_forward else R.string.player_seek_backward,
                    abs(delta) / 1000,
                )
                icon to label
            }

            else -> null
        }
        // 退场的那几帧里 hint 已经是 null 了,而那时框还在屏上,总得有话可说 —— 留住最后一份
        // 非空的内容,和 [dev.bilby.ui.video.TripleToast] 里的 `shown` 是同一个写法。
        var shownHint by remember { mutableStateOf<Pair<ImageVector, String>?>(null) }
        LaunchedEffect(hint) { if (hint != null) shownHint = hint }

        // 淡入淡出而不是直接出现:四种手势的浮层在同一个位置上互相替换,硬切时看起来像画面上
        // 闪了一下。**只给透明度,不给位移或缩放** —— 它是"手势正在发生"的读数,位置固定在正中
        // 才不用每次重新找;effects 那一档也正是无回弹的那组。
        AnimatedVisibility(
            visible = hint != null,
            enter = fadeIn(effectsSpec),
            exit = fadeOut(effectsSpec),
            modifier = Modifier.align(Alignment.Center),
        ) {
            shownHint?.let { (icon, text) -> PlayerHintOverlay(icon = icon, text = text) }
        }

        // 全屏顶栏。全屏下没有别的东西说明"在看什么"和"怎么退出":系统栏是隐藏的,
        // 返回手势在锁屏态下也被吃掉了。竖屏不显示,那里标题就在播放器下面第一行。
        //
        // **进出方向由它贴着哪条边决定**,规范原文:"The direction a component enters is informed
        // by their location on screen, expanding away from the device edge. A menu at the top of
        // the screen expands downwards";Android 那一档还要求
        // "components expand and collapse along the x or y axis as they slide on and off screen"。
        // 所以顶栏是从上边缘展开,而不是原地淡入。
        AnimatedVisibility(
            visible = isFullscreen && controlsVisible && !locked,
            enter = expandVertically(spatialSpec, expandFrom = Alignment.Top) + fadeIn(effectsSpec),
            exit = shrinkVertically(spatialSpec, shrinkTowards = Alignment.Top) + fadeOut(effectsSpec),
            modifier = Modifier.align(Alignment.TopCenter),
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
                .padding(end = Spacing.Cozy),
        ) {
            val lockDescription =
                stringResource(if (locked) R.string.player_unlock else R.string.player_lock)
            PlayerTooltip(lockDescription) {
                IconButton(
                    onClick = {
                        // 锁上之后整块画面都不响应,而这件事在画面上只表现为"控件没了"——
                        // 和自动隐藏长得一模一样。两种触感分开:合上和打开是相反的动作。
                        haptics.performHapticFeedback(
                            if (locked) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                        )
                        onLockedChange(!locked)
                    },
                ) {
                    Icon(
                        imageVector = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = lockDescription,
                        tint = FixedColors.OnMedia,
                    )
                }
            }
        }

        // 控制条贴着下边缘,所以从下边缘展开。
        AnimatedVisibility(
            visible = controlsVisible && !locked,
            enter = expandVertically(spatialSpec, expandFrom = Alignment.Bottom) + fadeIn(effectsSpec),
            exit = shrinkVertically(spatialSpec, shrinkTowards = Alignment.Bottom) + fadeOut(effectsSpec),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            scope.controlBar()
        }
    }
}

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

/**
 * 全屏的两件事:Activity 转到画面朝向 + 隐藏系统栏。两者都是 Activity 级的全局状态,离开
 * 这个 composable 必须还原,否则退到列表页还卡在横屏。
 *
 * **朝向跟着画面走,不是一律横屏。** 竖屏视频转横屏之后画面只能缩到中间一条,两侧全是黑边,
 * 等于全屏把可视面积改小了;竖屏视频的全屏就该竖着占满。用 SENSOR_* 而不是 USER_*,
 * 是为了让人仍能把设备翻过来(倒持、左右手)。
 */
@Composable
private fun FullscreenEffect(
    isFullscreen: Boolean,
    isPortraitVideo: Boolean,
    /** 见 [PlayerShell] 的同名参数:画面铺到边缘但没全屏。 */
    fullBleed: Boolean,
    /** 见 [PlayerShell] 的同名参数:两栏下把状态栏整条收起来。 */
    hideStatusBar: Boolean,
) {
    val activity = LocalContext.current.findActivity() ?: return
    val window = activity.window
    val insets = remember(window) { WindowCompat.getInsetsController(window, window.decorView) }
    // 只记录普通页面的基线。effect 会因视频比例变化重新创建,不能把全屏时已经设成
    // false 的图标明暗再次当成"退出全屏后的正常值"保存下来。
    val normalLightStatusBars = remember(window) { insets.isAppearanceLightStatusBars }
    val normalLightNavigationBars = remember(window) { insets.isAppearanceLightNavigationBars }

    DisposableEffect(isFullscreen, isPortraitVideo, fullBleed, hideStatusBar) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 画面铺到边缘的那两种情形(全屏、横屏两栏的左栏)才允许它钻进刘海所在的短边,
        // 别的时候还原。**不在主题里全局开 shortEdges**:那样横屏下每一页的正文都可能压在
        // 挖孔底下,而 `Scaffold` 默认消费的是 systemBars,不含 displayCutout。
        val bleeding = isFullscreen || fullBleed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (bleeding) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
        val focusListener = if (isFullscreen) {
            ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                // 返回桌面/锁屏后系统可能重新显示 system bars;重新获得焦点时要把
                // 沉浸状态补回去,否则画面仍是横屏但底部突然多出导航栏。
                if (hasFocus) {
                    insets.isAppearanceLightStatusBars = false
                    insets.isAppearanceLightNavigationBars = false
                    insets.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    insets.hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        } else {
            null
        }
        focusListener?.let { window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(it) }

        if (isFullscreen) {
            activity.requestedOrientation =
                if (isPortraitVideo) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            // 控件浮在视频的黑底/渐变上,系统栏短暂被手势拉出来时也必须使用白色图标。
            insets.isAppearanceLightStatusBars = false
            insets.isAppearanceLightNavigationBars = false
            insets.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else if (fullBleed) {
            // 朝向不锁:全屏之外这一页始终跟着设备转,横过来才排得成两栏。
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // **导航栏一直留着**,简介和评论还要滚,手势条也得在。
            insets.show(WindowInsetsCompat.Type.navigationBars())
            if (hideStatusBar) {
                insets.hide(WindowInsetsCompat.Type.statusBars())
                insets.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insets.show(WindowInsetsCompat.Type.statusBars())
                insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
            // 状态栏(收起时是下拉唤出的那一瞬间)压在画面上,要白图标。
            insets.isAppearanceLightStatusBars = false
            insets.isAppearanceLightNavigationBars = normalLightNavigationBars
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insets.show(WindowInsetsCompat.Type.systemBars())
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            insets.isAppearanceLightStatusBars = normalLightStatusBars
            insets.isAppearanceLightNavigationBars = normalLightNavigationBars
        }
        onDispose {
            focusListener?.let { window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insets.show(WindowInsetsCompat.Type.systemBars())
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            insets.isAppearanceLightStatusBars = normalLightStatusBars
            insets.isAppearanceLightNavigationBars = normalLightNavigationBars
        }
    }
}

/** 双击 ±N 秒。夹在 [0, duration] 内:越界 seek 在部分实现上会直接把播放器打到 IDLE。 */
private fun nudgeSeek(player: Player, deltaMillis: Long) {
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
private fun VideoSize.displayAspectOr(fallback: Float?): Float? {
    if (width <= 0 || height <= 0) return fallback
    val par = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
    return width * par / height
}

private const val CONTROLS_HIDE_DELAY_MILLIS = 3_000L
private const val DOUBLE_TAP_SEEK_MILLIS = 10_000L
private const val HINT_VISIBLE_MILLIS = 700L

/** 控件在屏上、或者正在拖:这个读数是用户此刻盯着的东西。见取值处的说明。 */
private const val POSITION_POLL_ACTIVE_MILLIS = 200L

/** 控件收起(含息屏只出声):没人看,只要下次唤出时是对的就行。 */
private const val POSITION_POLL_IDLE_MILLIS = 500L

/** 控制条渐变的最暗端。壳的全屏顶栏与视频控制条共用同一个值,两处各写一份就会渐变对不上。 */
internal val ControlScrimBottom = Color(0xB3000000)

internal fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

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
