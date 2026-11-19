package dev.bilby.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.bilby.player.*
import dev.bilby.ui.LocalPlaybackHost
import dev.bilby.ui.components.SeekBar
import dev.bilby.ui.theme.BilbyTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalComposeUiApi::class)
class SpeedSelectionPointerTest {
    @Test(timeout = 30000)
    fun realPlayerShellLongPressAndDrag() = runBlocking(Dispatchers.Swing) {
        val player = PointerTestPlayer().apply { setPlaybackSpeed(1.25f) }
        val host = PointerTestPlaybackHost(player)
        val navigation = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        lateinit var selector: PlaybackSpeedController
        var controls = true
        val scene = ImageComposeScene(390, 844, coroutineContext = coroutineContext) {
            BilbyTheme {
                CompositionLocalProvider(LocalPlaybackHost provides host, LocalNavigationEventDispatcherOwner provides navigation) {
                    selector = rememberPlaybackSpeedController(player, "qa", defaultHoldSpeed = 2.5f)
                    Box(Modifier.fillMaxSize()) {
                        PlayerShell(
                            player = player, attached = false, placeholderCoverUrl = "",
                            isFullscreen = false, onFullscreenChange = {}, locked = false, onLockedChange = {}, title = "",
                            speedController = selector,
                            modifier = Modifier.size(390.dp, 220.dp),
                            overlay = { controls = controlsVisible },
                        )
                    }
                }
            }
        }
        suspend fun frame() { repeat(3) { scene.render(System.nanoTime()).close(); delay(20) } }
        fun event(type: PointerEventType, x: Float, y: Float) = scene.sendPointerEvent(type, Offset(x, y), type = PointerType.Touch)
        try {
            frame()
            // 1. 偏下长按(y = 150 >= 110):选档条放画面上沿(dockTop = true),起手跳默认 2.5x,单滑直达 0.5x 并即时反向回拨
            event(PointerEventType.Press, 240f, 150f)
            delay(650)
            frame()
            assertTrue(selector.active)
            assertTrue("lower-half press must dock dial to top", selector.selection!!.dockTop)
            assertEquals("first hold must jump to default hold speed", 2.5f, player.playbackSpeed, 0f)
            assertFalse("normal controls should hide", controls)
            event(PointerEventType.Move, 198f, 150f)
            frame()
            assertEquals(2f, player.playbackSpeed, 0f)
            delay(600)
            frame()
            assertEquals("holding still must keep the chosen speed", 2f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 30f, 150f)
            frame()
            assertEquals("single swipe must directly reach 0.5x", .5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 142f, 150f)
            frame()
            assertEquals("reversing right responds immediately to 1.5x", 1.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Release, 142f, 150f)
            frame()
            assertEquals("plain release restores base speed", 1.25f, player.playbackSpeed, 0f)

            // 2. 偏上长按(y = 60 < 110):选档条放画面下沿(dockTop = false),下滑锁定到 2.0x
            event(PointerEventType.Press, 200f, 60f)
            delay(650)
            frame()
            assertTrue(selector.active)
            assertFalse("upper-half press must dock dial at bottom", selector.selection!!.dockTop)
            assertEquals(2.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 158f, 60f)
            frame()
            assertEquals(2f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 158f, 100f)
            frame()
            assertTrue("downward slide locks when docked at bottom", selector.selection!!.locked)
            event(PointerEventType.Move, 158f, 60f)
            frame()
            assertFalse("returning upward undoes bottom-docked lock", selector.selection!!.locked)
            event(PointerEventType.Move, 158f, 100f)
            frame()
            assertTrue(selector.selection!!.locked)
            event(PointerEventType.Release, 158f, 100f)
            frame()
            assertFalse(selector.active)
            assertEquals(2f, player.playbackSpeed, 0f)
            assertTrue(selector.lockedByHold)
            assertFalse("releasing a long press must not fire a normal tap", controls)
            delay(1000)
            frame()
            assertNull(selector.lockedHint)

            // 3. 已锁在 2.0x 时再次偏下长按(y = 160 >= 110):从已锁定的 2.0x 开始,上滑锁定再下滑解锁,松手回 1.25x
            event(PointerEventType.Press, 180f, 160f)
            delay(650)
            frame()
            assertTrue(selector.selection!!.dockTop)
            assertEquals("subsequent hold starts from locked speed", 2f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 280f, 160f)
            frame()
            assertEquals(3f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 280f, 120f)
            frame()
            assertTrue("upward slide locks when docked at top", selector.selection!!.locked)
            event(PointerEventType.Move, 280f, 160f)
            frame()
            assertFalse("returning downward undoes top-docked lock", selector.selection!!.locked)
            event(PointerEventType.Release, 280f, 160f)
            frame()
            assertEquals(1.25f, player.playbackSpeed, 0f)
            assertFalse(selector.lockedByHold)

            // 4. 在画面底沿选档条本体(y = 166..220 -> y = 190)直接横向拖拽并下滑锁定 2.0x
            selector.applySpeed(1.5f)
            selector.open(dockTop = false)
            frame()
            event(PointerEventType.Press, 180f, 190f)
            event(PointerEventType.Move, 198f, 190f)
            event(PointerEventType.Move, 240f, 190f)
            frame()
            assertTrue("dial host must handle horizontal drag", selector.active)
            assertEquals(2f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 240f, 230f)
            event(PointerEventType.Release, 240f, 230f)
            frame()
            assertFalse(selector.active)
            assertEquals(2f, player.playbackSpeed, 0f)

            event(PointerEventType.Press, 180f, 160f)
            delay(650)
            frame()
            assertEquals(2.5f, player.playbackSpeed, 0f)
            scene.setContent {}
            frame()
            assertFalse(selector.active)
            assertEquals("unmount must restore pre-hold speed", 2f, player.playbackSpeed, 0f)
            assertEquals(0, player.playPauseCalls)
            assertEquals(0, player.seeks)
        } finally { scene.close() }
    }

    @Test(timeout = 30000)
    fun landscapeUsesVerticalAxisAndSideDependentLock() = runBlocking(Dispatchers.Swing) {
        val player = PointerTestPlayer().apply { setPlaybackSpeed(1f) }
        val host = PointerTestPlaybackHost(player)
        val navigation = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        lateinit var selector: PlaybackSpeedController
        val scene = ImageComposeScene(660, 360, coroutineContext = coroutineContext) {
            BilbyTheme {
                CompositionLocalProvider(LocalPlaybackHost provides host, LocalNavigationEventDispatcherOwner provides navigation) {
                    selector = rememberPlaybackSpeedController(player, "landscape-qa", defaultHoldSpeed = 2.5f)
                    Box(Modifier.fillMaxSize()) {
                        PlayerShell(
                            player = player, attached = false, placeholderCoverUrl = "",
                            isFullscreen = true, onFullscreenChange = {}, locked = false, onLockedChange = {}, title = "",
                            speedController = selector, modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        suspend fun frame() { repeat(3) { scene.render(System.nanoTime()).close(); delay(20) } }
        fun event(type: PointerEventType, x: Float, y: Float) = scene.sendPointerEvent(type, Offset(x, y), type = PointerType.Touch)
        try {
            frame()
            event(PointerEventType.Press, 200f, 200f)
            delay(650)
            frame()
            assertTrue(selector.selection!!.dockEnd)
            assertEquals(2.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 162f)
            frame()
            assertEquals(2f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 40f)
            frame()
            assertEquals("upward swipe reaches 0.5x directly", .5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 112f)
            frame()
            assertEquals(1.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 250f)
            frame()
            assertEquals("downward swipe reaches 3.5x directly", 3.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Release, 200f, 250f)
            frame()
            assertEquals(1f, player.playbackSpeed, 0f)

            // 左半屏长按 + 右滑锁定在 2.0x
            event(PointerEventType.Press, 200f, 100f)
            delay(650)
            frame()
            assertTrue(selector.active)
            assertTrue(selector.selection!!.dockEnd)
            assertEquals(2.5f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 200f, 62f)
            frame()
            assertEquals(2f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 240f, 68f)
            frame()
            assertTrue("sliding right from left-half press should lock", selector.selection!!.locked)
            event(PointerEventType.Move, 200f, 74f)
            frame()
            assertFalse("sliding back left should unlock", selector.selection!!.locked)
            event(PointerEventType.Move, 240f, 80f)
            frame()
            assertTrue(selector.selection!!.locked)
            event(PointerEventType.Release, 240f, 80f)
            frame()
            assertFalse(selector.active)
            assertEquals(2f, player.playbackSpeed, 0f)
            assertEquals(200, selector.lockedHint)

            // 右半屏长按:左侧显示倍速条,从已锁定的 2.0x 开始,下滑升到 2.5x 并左滑锁定
            event(PointerEventType.Press, 450f, 100f)
            delay(650)
            frame()
            assertTrue(selector.active)
            assertFalse(selector.selection!!.dockEnd)
            assertEquals(2f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 450f, 138f)
            frame()
            assertEquals(2.5f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 410f, 144f)
            frame()
            assertTrue("sliding left from right-half press should lock", selector.selection!!.locked)
            event(PointerEventType.Release, 410f, 144f)
            frame()
            assertFalse(selector.active)
            assertEquals(2.5f, player.playbackSpeed, 0f)
            assertEquals(250, selector.lockedHint)
            assertEquals(0, player.seeks)
            assertEquals(0, player.playPauseCalls)
        } finally { scene.close() }
    }

    @Test(timeout = 30000)
    fun longPressThenDragNeverTriggersSeek() = runBlocking(Dispatchers.Swing) {
        val player = PointerTestPlayer().apply { setPlaybackSpeed(1f) }
        val host = PointerTestPlaybackHost(player)
        val navigation = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        lateinit var selector: PlaybackSpeedController
        var controls = true
        val scene = ImageComposeScene(390, 844, coroutineContext = coroutineContext) {
            BilbyTheme {
                CompositionLocalProvider(LocalPlaybackHost provides host, LocalNavigationEventDispatcherOwner provides navigation) {
                    selector = rememberPlaybackSpeedController(player, "conflict-lp", defaultHoldSpeed = 2.5f)
                    Box(Modifier.fillMaxSize()) {
                        PlayerShell(
                            player = player, attached = false, placeholderCoverUrl = "",
                            isFullscreen = false, onFullscreenChange = {}, locked = false, onLockedChange = {}, title = "",
                            speedController = selector,
                            showSpeedOverlay = false,
                            modifier = Modifier.size(390.dp, 220.dp),
                            overlay = { controls = controlsVisible },
                        )
                    }
                }
            }
        }
        suspend fun frame() { repeat(3) { scene.render(System.nanoTime()).close(); delay(20) } }
        fun event(type: PointerEventType, x: Float, y: Float) = scene.sendPointerEvent(type, Offset(x, y), type = PointerType.Touch)
        try {
            frame()
            // 长按后大幅横滑 + 纵滑,绝不能触发 seek (避开正中 195x110 的播放键)
            event(PointerEventType.Press, 240f, 150f)
            delay(650)
            frame()
            assertTrue(selector.active)
            event(PointerEventType.Move, 140f, 150f)
            event(PointerEventType.Move, 320f, 150f)
            event(PointerEventType.Move, 320f, 190f)
            frame()
            assertTrue(selector.active)
            event(PointerEventType.Release, 320f, 190f)
            frame()
            assertFalse(selector.active)
            assertEquals("long-press drag must never trigger seek", 0, player.seeks)
            assertEquals(0, player.playPauseCalls)

            // 竖屏内嵌 showSpeedOverlay = false 时,倍速选档处于激活态期间画面拖拽与点按必须被屏蔽
            selector.open(dockTop = false)
            frame()
            assertTrue(selector.active)
            assertFalse(controls)
            event(PointerEventType.Press, 80f, 150f)
            event(PointerEventType.Move, 130f, 150f)
            event(PointerEventType.Move, 260f, 150f)
            event(PointerEventType.Release, 260f, 150f)
            frame()
            assertEquals("surface drag while speedController is active must not seek", 0, player.seeks)
            event(PointerEventType.Press, 240f, 150f)
            event(PointerEventType.Release, 240f, 150f)
            delay(350)
            frame()
            assertFalse("surface tap while speedController is active must not show controls", controls)
            selector.cancel()
        } finally { scene.close() }
    }

    @Test(timeout = 30000)
    fun immediateHorizontalDragThenPauseNeverOpensSpeedDial() = runBlocking(Dispatchers.Swing) {
        val player = PointerTestPlayer().apply { setPlaybackSpeed(1f) }
        val host = PointerTestPlaybackHost(player)
        val navigation = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        lateinit var selector: PlaybackSpeedController
        val scene = ImageComposeScene(390, 844, coroutineContext = coroutineContext) {
            BilbyTheme {
                CompositionLocalProvider(LocalPlaybackHost provides host, LocalNavigationEventDispatcherOwner provides navigation) {
                    selector = rememberPlaybackSpeedController(player, "conflict-drag", defaultHoldSpeed = 2.5f)
                    Box(Modifier.fillMaxSize()) {
                        PlayerShell(
                            player = player, attached = false, placeholderCoverUrl = "",
                            isFullscreen = false, onFullscreenChange = {}, locked = false, onLockedChange = {}, title = "",
                            speedController = selector,
                            modifier = Modifier.size(390.dp, 220.dp),
                        )
                    }
                }
            }
        }
        suspend fun frame() { repeat(3) { scene.render(System.nanoTime()).close(); delay(20) } }
        fun event(type: PointerEventType, x: Float, y: Float) = scene.sendPointerEvent(type, Offset(x, y), type = PointerType.Touch)
        try {
            frame()
            // 按下后立即横向拖动超过 touchSlop,然后按住停顿超过 longPressTimeoutMillis
            event(PointerEventType.Press, 100f, 120f)
            event(PointerEventType.Move, 140f, 120f)
            event(PointerEventType.Move, 220f, 120f)
            frame()
            delay(650)
            frame()
            assertFalse("drag mode must never open speed dial after pausing", selector.active)
            assertEquals(1f, player.playbackSpeed, 0f)
            event(PointerEventType.Release, 220f, 120f)
            frame()
            assertFalse(selector.active)
            assertEquals("horizontal drag must commit seek on release", 1, player.seeks)
        } finally { scene.close() }
    }

    @Test(timeout = 30000)
    fun shortVideoSwipeSeekUsesFullDurationSpan() = runBlocking(Dispatchers.Swing) {
        val player = PointerTestPlayer(initialPosition = 0L, initialDuration = 30_000L)
        val host = PointerTestPlaybackHost(player)
        val navigation = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        val scene = ImageComposeScene(390, 844, coroutineContext = coroutineContext) {
            BilbyTheme {
                CompositionLocalProvider(LocalPlaybackHost provides host, LocalNavigationEventDispatcherOwner provides navigation) {
                    Box(Modifier.fillMaxSize()) {
                        PlayerShell(
                            player = player, attached = false, placeholderCoverUrl = "",
                            isFullscreen = false, onFullscreenChange = {}, locked = false, onLockedChange = {}, title = "",
                            modifier = Modifier.size(390.dp, 220.dp),
                        )
                    }
                }
            }
        }
        suspend fun frame() { repeat(3) { scene.render(System.nanoTime()).close(); delay(20) } }
        fun event(type: PointerEventType, x: Float, y: Float) = scene.sendPointerEvent(type, Offset(x, y), type = PointerType.Touch)
        try {
            frame()
            // 30s 短视频划过半屏(195px / 390px = 50%),目标应约 15s 而不是顶到 30s 结尾
            event(PointerEventType.Press, 100f, 120f)
            event(PointerEventType.Move, 130f, 120f)
            event(PointerEventType.Move, 295f, 120f)
            event(PointerEventType.Release, 295f, 120f)
            frame()
            assertEquals(1, player.seeks)
            val target = player.seekTargets.last()
            assertTrue("expected ~15000ms for half-width swipe on 30s video, got $target", target in 14_000L..16_000L)
        } finally { scene.close() }
    }

    @Test(timeout = 30000)
    fun rapidDoubleTapNudgeAccumulatesAndPreventsSnapBack() = runBlocking(Dispatchers.Swing) {
        val player = PointerTestPlayer(initialPosition = 10_000L, initialDuration = 120_000L)
        val host = PointerTestPlaybackHost(player)
        val navigation = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        lateinit var capturedProgress: PlayerProgressState
        val scene = ImageComposeScene(390, 844, coroutineContext = coroutineContext) {
            BilbyTheme {
                CompositionLocalProvider(LocalPlaybackHost provides host, LocalNavigationEventDispatcherOwner provides navigation) {
                    Box(Modifier.fillMaxSize()) {
                        PlayerShell(
                            player = player, attached = false, placeholderCoverUrl = "",
                            isFullscreen = false, onFullscreenChange = {}, locked = false, onLockedChange = {}, title = "",
                            modifier = Modifier.size(390.dp, 220.dp),
                            overlay = { capturedProgress = progressState },
                        )
                    }
                }
            }
        }
        suspend fun frame() { repeat(3) { scene.render(System.nanoTime()).close(); delay(20) } }
        fun event(type: PointerEventType, x: Float, y: Float) = scene.sendPointerEvent(type, Offset(x, y), type = PointerType.Touch)
        suspend fun doubleTapRight() {
            event(PointerEventType.Press, 350f, 110f)
            event(PointerEventType.Release, 350f, 110f)
            delay(60)
            event(PointerEventType.Press, 350f, 110f)
            event(PointerEventType.Release, 350f, 110f)
            frame()
        }
        try {
            frame()
            doubleTapRight()
            delay(60)
            doubleTapRight()
            delay(60)
            doubleTapRight()
            assertEquals(listOf(20_000L, 30_000L, 40_000L), player.seekTargets)
            assertEquals(40_000L, capturedProgress.positionMillis)
            // 等待超过一次 200ms 轮询,确认不会被尚未更新的 player.currentPosition(10s) 回弹覆盖
            delay(260)
            frame()
            assertEquals("pending seek must prevent snap-back before player catches up", 40_000L, capturedProgress.positionMillis)
            // 播放器回报 seek discontinuity 后解除锁定
            player.notifySeekDiscontinuity(10_000L, 40_000L)
            assertNull(capturedProgress.pendingSeekTargetMillis)
        } finally { scene.close() }
    }

    @Test(timeout = 30000)
    fun seekBarTapDoesNotPauseOrResume() = runBlocking(Dispatchers.Swing) {
        val player = PointerTestPlayer(initialPosition = 10_000L, initialDuration = 120_000L)
        val host = PointerTestPlaybackHost(player)
        val navigation = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        val scene = ImageComposeScene(390, 220, coroutineContext = coroutineContext) {
            BilbyTheme {
                CompositionLocalProvider(LocalPlaybackHost provides host, LocalNavigationEventDispatcherOwner provides navigation) {
                    PlayerShell(
                        player = player, attached = false, placeholderCoverUrl = "",
                        isFullscreen = false, onFullscreenChange = {}, locked = false, onLockedChange = {}, title = "",
                        modifier = Modifier.fillMaxSize(),
                        controlBar = {
                            SeekBar(
                                position = positionMillis,
                                duration = durationMillis,
                                onSeekStart = { onSeekStart() },
                                onSeekTo = { onSeekTo(it) },
                                onSeekFinished = { onSeekFinished() },
                                onTapSeek = { onTapSeek(it) },
                                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                            )
                        },
                    )
                }
            }
        }
        suspend fun frame() { repeat(3) { scene.render(System.nanoTime()).close(); delay(20) } }
        fun event(type: PointerEventType, x: Float, y: Float) = scene.sendPointerEvent(type, Offset(x, y), type = PointerType.Touch)
        try {
            frame()
            // 点击底部进度条中点
            event(PointerEventType.Press, 195f, 196f)
            event(PointerEventType.Release, 195f, 196f)
            frame()
            assertEquals(1, player.seeks)
            assertEquals("tapping SeekBar must not trigger pause/play cycle", 0, player.playPauseCalls)
            assertTrue("expected seek near 60000ms, got ${player.seekTargets.last()}", player.seekTargets.last() in 55_000L..65_000L)
        } finally { scene.close() }
    }
}

private class PointerTestPlayer(
    initialPosition: Long = 10000L,
    initialDuration: Long = 120000L,
) : PlayerHandle {
    private var actualSpeed = 1f
    private val listeners = mutableSetOf<PlayerListener>()
    var playPauseCalls = 0
    var seeks = 0
    val seekTargets = mutableListOf<Long>()
    override val isPlaying = true
    override val playWhenReady = true
    override val playbackState = PlayerPhase.READY
    override val currentPosition = initialPosition
    override val bufferedPosition = 80000L
    override val duration = initialDuration
    override val playbackSpeed get() = actualSpeed
    override val videoSize = VideoDimensions(1920, 1080)
    override fun play() { playPauseCalls++ }
    override fun pause() { playPauseCalls++ }
    override fun seekTo(positionMillis: Long) { seeks++; seekTargets += positionMillis }
    override fun seekTo(index: Int, positionMillis: Long) { seeks++; seekTargets += positionMillis }
    fun notifySeekDiscontinuity(oldPositionMillis: Long, newPositionMillis: Long) {
        listeners.toList().forEach { it.onPositionDiscontinuity(oldPositionMillis, newPositionMillis, isSeek = true) }
    }
    override fun seekToNext() = Unit
    override fun seekToPrevious() = Unit
    override fun setPlaybackSpeed(speed: Float) { actualSpeed = speed; listeners.toList().forEach { it.onPlaybackSpeedChanged(speed) } }
    override fun setVideoDisabled(disabled: Boolean) = Unit
    override fun addListener(listener: PlayerListener) { listeners += listener }
    override fun removeListener(listener: PlayerListener) { listeners -= listener }
    override fun send(command: PlaybackCommand) = Unit
}

private class PointerTestPlaybackHost(private val player: PointerTestPlayer) : PlaybackHost {
    override val state = MutableStateFlow(AudioPlaybackUiState())
    override val positionTicks = MutableStateFlow(PositionTick())
    override val sleepTimerState = MutableStateFlow(SleepTimerState())
    override fun currentPositionMillis() = player.currentPosition
    override fun setBackgroundPlaybackAllowed(allowed: Boolean) = Unit
    override fun pauseForAppBackground() = Unit
    override fun retainFrames(ids: Set<String>) = Unit
    override fun stop() = Unit
    override fun connect(onConnected: (PlayerHandle) -> Unit): PlayerConnection {
        onConnected(player)
        return object : PlayerConnection { override fun release() = Unit }
    }
}
