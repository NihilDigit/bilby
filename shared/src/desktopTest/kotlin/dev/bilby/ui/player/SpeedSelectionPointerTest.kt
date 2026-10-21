package dev.bilby.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.bilby.player.*
import dev.bilby.ui.LocalPlaybackHost
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
        val player = PointerTestPlayer().apply { setPlaybackSpeed(1.5f) }
        val host = PointerTestPlaybackHost(player)
        val navigation = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        lateinit var selector: PlaybackSpeedController
        var controls = true
        val scene = ImageComposeScene(360, 640, coroutineContext = coroutineContext) {
            BilbyTheme {
                CompositionLocalProvider(LocalPlaybackHost provides host, LocalNavigationEventDispatcherOwner provides navigation) {
                    selector = rememberPlaybackSpeedController(player, "qa")
                    Box(Modifier.fillMaxSize()) {
                        PlayerShell(
                            player = player, attached = false, placeholderCoverUrl = "",
                            isFullscreen = false, onFullscreenChange = {}, locked = false, onLockedChange = {}, title = "",
                            speedController = selector,
                            modifier = Modifier.size(360.dp, 202.dp),
                            overlay = { controls = controlsVisible },
                        )
                        SpeedSelectionOverlay(selector, Rect(0f,0f,360f,202f), dimDetails = true)
                    }
                }
            }
        }
        suspend fun frame() { repeat(3) { scene.render(System.nanoTime()).close(); delay(20) } }
        fun event(type: PointerEventType, x: Float, y: Float) = scene.sendPointerEvent(type, Offset(x,y), type = PointerType.Touch)
        try {
            frame()
            event(PointerEventType.Press, 90f, 110f)
            delay(650)
            frame()
            delay(450)
            assertEquals("holding without sliding must not start coarse repeat", 1.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 140f, 110f)
            frame()
            assertEquals(1.75f, player.playbackSpeed, 0f)
            delay(800)
            frame()
            assertEquals("holding in mid-screen must not auto-repeat", 1.75f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 340f, 110f)
            frame()
            delay(1500)
            frame()
            assertEquals("sliding to the right edge must reach the max preset", 3f, player.playbackSpeed, 0f)
            event(PointerEventType.Release, 340f, 110f)
            frame()
            assertEquals(1.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Press, 90f, 110f)
            delay(650)
            frame()
            assertTrue("long press should open speed selection", selector.active)
            assertEquals(1.5f, player.playbackSpeed, 0f)
            assertFalse("normal controls should hide", controls)
            event(PointerEventType.Move, 140f, 110f)
            frame()
            assertEquals(1.75f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 140f, 150f)
            frame()
            assertTrue(selector.selection!!.locked)
            event(PointerEventType.Move, 140f, 110f)
            frame()
            assertFalse("returning upward should undo portrait lock", selector.selection!!.locked)
            event(PointerEventType.Move, 140f, 150f)
            frame()
            assertTrue(selector.selection!!.locked)
            event(PointerEventType.Release, 140f, 150f)
            frame()
            assertFalse(selector.active)
            assertEquals(1.75f, player.playbackSpeed, 0f)
            assertFalse("releasing a long press must not fire a normal tap", controls)
            delay(1000)
            frame()
            assertNull(selector.lockedHint)
            assertEquals(0, player.playPauseCalls)
            assertEquals(0, player.seeks)
            selector.open()
            frame()
            event(PointerEventType.Press, 180f, 596f)
            event(PointerEventType.Move, 198f, 596f)
            event(PointerEventType.Move, 240f, 596f)
            frame()
            assertTrue("dial host must handle horizontal drag", selector.active)
            assertEquals(2f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 240f, 636f)
            event(PointerEventType.Release, 240f, 636f)
            frame()
            assertFalse(selector.active)
            assertEquals(2f, player.playbackSpeed, 0f)
            event(PointerEventType.Press, 90f, 110f)
            delay(650)
            frame()
            assertEquals(2f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 140f, 110f)
            frame()
            assertEquals(2.25f, selector.selection!!.speed, 0f)
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
        val player = PointerTestPlayer().apply { setPlaybackSpeed(1.5f) }
        val host = PointerTestPlaybackHost(player)
        val navigation = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        lateinit var selector: PlaybackSpeedController
        val scene = ImageComposeScene(660, 360, coroutineContext = coroutineContext) {
            BilbyTheme {
                CompositionLocalProvider(LocalPlaybackHost provides host, LocalNavigationEventDispatcherOwner provides navigation) {
                    selector = rememberPlaybackSpeedController(player, "landscape-qa")
                    Box(Modifier.fillMaxSize()) {
                        PlayerShell(
                            player = player, attached = false, placeholderCoverUrl = "",
                            isFullscreen = false, onFullscreenChange = {}, locked = false, onLockedChange = {}, title = "",
                            speedController = selector, modifier = Modifier.fillMaxSize(),
                        )
                        SpeedSelectionOverlay(selector)
                    }
                }
            }
        }
        suspend fun frame() { repeat(3) { scene.render(System.nanoTime()).close(); delay(20) } }
        fun event(type: PointerEventType, x: Float, y: Float) = scene.sendPointerEvent(type, Offset(x,y), type = PointerType.Touch)
        try {
            frame()
            // 左半屏长按:右侧显示倍速条,右滑锁定
            event(PointerEventType.Press, 200f, 100f)
            delay(650)
            frame()
            assertTrue(selector.selection!!.dockEnd)
            delay(450)
            assertEquals(1.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 50f)
            frame()
            assertEquals(1.25f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 20f)
            frame()
            delay(2200)
            frame()
            assertEquals("upward edge hold must keep stepping to min", .25f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 100f)
            frame()
            delay(450)
            assertEquals(.25f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 340f)
            frame()
            delay(3400)
            frame()
            assertEquals("downward edge hold should reach the opposite endpoint", 3f, player.playbackSpeed, 0f)
            event(PointerEventType.Release, 200f, 340f)
            frame()
            assertEquals(1.5f, player.playbackSpeed, 0f)

            // 左半屏长按 + 右滑锁定
            event(PointerEventType.Press, 200f, 100f)
            delay(650)
            frame()
            assertTrue(selector.active)
            assertTrue(selector.selection!!.dockEnd)
            event(PointerEventType.Move, 200f, 150f)
            frame()
            assertEquals(1.75f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 240f, 158f)
            frame()
            assertTrue("sliding right from left-half press should lock", selector.selection!!.locked)
            event(PointerEventType.Move, 200f, 166f)
            frame()
            assertFalse("sliding back left should unlock", selector.selection!!.locked)
            event(PointerEventType.Move, 240f, 174f)
            frame()
            assertTrue(selector.selection!!.locked)
            event(PointerEventType.Release, 240f, 174f)
            frame()
            assertFalse(selector.active)
            assertEquals(1.75f, player.playbackSpeed, 0f)
            assertEquals(175, selector.lockedHint)

            // 右半屏长按:左侧显示倍速条,左滑锁定
            event(PointerEventType.Press, 450f, 100f)
            delay(650)
            frame()
            assertTrue(selector.active)
            assertFalse(selector.selection!!.dockEnd)
            event(PointerEventType.Move, 450f, 150f)
            frame()
            assertEquals(2f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 410f, 158f)
            frame()
            assertTrue("sliding left from right-half press should lock", selector.selection!!.locked)
            event(PointerEventType.Release, 410f, 158f)
            frame()
            assertFalse(selector.active)
            assertEquals(2f, player.playbackSpeed, 0f)
            assertEquals(200, selector.lockedHint)
            assertEquals(0, player.seeks)
            assertEquals(0, player.playPauseCalls)
        } finally { scene.close() }
    }
}

private class PointerTestPlayer : PlayerHandle {
    private var actualSpeed = 1f
    private val listeners = mutableSetOf<PlayerListener>()
    var playPauseCalls = 0
    var seeks = 0
    override val isPlaying = true
    override val playWhenReady = true
    override val playbackState = PlayerPhase.READY
    override val currentPosition = 10000L
    override val bufferedPosition = 80000L
    override val duration = 120000L
    override val playbackSpeed get() = actualSpeed
    override val videoSize = VideoDimensions(1920,1080)
    override var shuffleModeEnabled = false
    override fun play() { playPauseCalls++ }
    override fun pause() { playPauseCalls++ }
    override fun seekTo(positionMillis: Long) { seeks++ }
    override fun seekTo(index: Int, positionMillis: Long) { seeks++ }
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
