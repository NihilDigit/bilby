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
            event(PointerEventType.Move, 114f, 110f)
            frame()
            assertEquals(2f, player.playbackSpeed, 0f)
            delay(1400)
            frame()
            assertEquals("horizontal repeat must continue without more pointer events", 3f, player.playbackSpeed, 0f)
            event(PointerEventType.Release, 114f, 110f)
            frame()
            assertEquals(1.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Press, 90f, 110f)
            delay(650)
            frame()
            assertTrue("long press should open speed selection", selector.active)
            assertEquals(1.5f, player.playbackSpeed, 0f)
            assertFalse("normal controls should hide", controls)
            event(PointerEventType.Move, 90f, 70f)
            frame()
            assertTrue(selector.selection!!.fine)
            event(PointerEventType.Move, 114f, 70f)
            frame()
            val firstFine = selector.selection!!.value
            assertTrue(firstFine >= 151)
            event(PointerEventType.Move, 114f, 110f)
            frame()
            assertFalse(selector.selection!!.fine)
            assertFalse(selector.selection!!.locked)
            assertTrue(firstFine in selector.selection!!.choices)
            event(PointerEventType.Move, 114f, 150f)
            frame()
            assertTrue(selector.selection!!.locked)
            event(PointerEventType.Move, 114f, 110f)
            frame()
            assertFalse("returning upward should undo portrait lock", selector.selection!!.locked)
            event(PointerEventType.Move, 114f, 150f)
            frame()
            assertTrue(selector.selection!!.locked)
            event(PointerEventType.Release, 114f, 150f)
            frame()
            assertFalse(selector.active)
            assertEquals(firstFine / 100f, player.playbackSpeed, 0f)
            assertFalse("releasing a long press must not fire a normal tap", controls)
            delay(1000)
            frame()
            assertNull(selector.lockedHint)
            assertEquals(0, player.playPauseCalls)
            assertEquals(0, player.seeks)
            selector.open()
            frame()
            event(PointerEventType.Press, 180f, 596f)
            event(PointerEventType.Move, 180f, 556f)
            event(PointerEventType.Move, 180f, 516f)
            frame()
            assertTrue("dial host must survive coarse to fine switch", selector.active)
            assertTrue(selector.selection!!.fine)
            event(PointerEventType.Move, 204f, 516f)
            frame()
            val secondFine = selector.selection!!.value
            assertTrue(secondFine > firstFine)
            event(PointerEventType.Move, 204f, 596f)
            frame()
            assertTrue(selector.active)
            assertFalse(selector.selection!!.fine)
            assertTrue(secondFine in selector.selection!!.choices)
            event(PointerEventType.Move, 204f, 636f)
            event(PointerEventType.Release, 204f, 636f)
            frame()
            assertFalse(selector.active)
            assertEquals(secondFine / 100f, player.playbackSpeed, 0f)
            event(PointerEventType.Press, 90f, 110f)
            delay(650)
            frame()
            assertEquals(secondFine / 100f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 114f, 110f)
            frame()
            assertEquals(2f, selector.selection!!.speed, 0f)
            scene.setContent {}
            frame()
            assertFalse(selector.active)
            assertEquals("unmount must restore pre-hold speed", secondFine / 100f, player.playbackSpeed, 0f)
            assertEquals(0, player.playPauseCalls)
            assertEquals(0, player.seeks)
        } finally { scene.close() }
    }

    @Test(timeout = 30000)
    fun landscapeUsesVerticalAxisAndLeftLock() = runBlocking(Dispatchers.Swing) {
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
            event(PointerEventType.Press, 250f, 100f)
            delay(650)
            frame()
            delay(450)
            assertEquals(1.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 250f, 76f)
            frame()
            assertEquals(1.25f, player.playbackSpeed, 0f)
            delay(2400)
            frame()
            assertEquals("upward intent must keep stepping with a stationary finger", .5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 250f, 100f)
            frame()
            delay(450)
            assertEquals(.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 250f, 124f)
            frame()
            assertEquals(.75f, player.playbackSpeed, 0f)
            delay(4100)
            frame()
            assertEquals("one hold should reach the opposite endpoint", 3f, player.playbackSpeed, 0f)
            event(PointerEventType.Release, 250f, 124f)
            frame()
            assertEquals(1.5f, player.playbackSpeed, 0f)
            event(PointerEventType.Press, 250f, 100f)
            delay(650)
            frame()
            assertTrue(selector.active)
            event(PointerEventType.Move, 290f, 100f)
            frame()
            assertTrue(selector.selection!!.fine)
            event(PointerEventType.Move, 296f, 124f)
            frame()
            assertTrue(selector.selection!!.value >= 151)
            delay(500)
            frame()
            val fineValue = selector.selection!!.value
            assertTrue("fine ruler must keep moving without more pointer events", fineValue > 151)
            event(PointerEventType.Move, 278f, 130f)
            frame()
            delay(300)
            assertEquals("starting a curved cross gesture must pause fine repeat", fineValue, selector.selection!!.value)
            event(PointerEventType.Move, 250f, 136f)
            frame()
            assertFalse(selector.selection!!.fine)
            assertEquals(fineValue, selector.selection!!.value)
            assertTrue(fineValue in selector.selection!!.choices)
            event(PointerEventType.Move, 210f, 144f)
            frame()
            assertTrue(selector.selection!!.locked)
            event(PointerEventType.Move, 250f, 152f)
            frame()
            assertFalse(selector.selection!!.locked)
            event(PointerEventType.Move, 290f, 160f)
            frame()
            assertTrue(selector.selection!!.fine)
            event(PointerEventType.Move, 296f, 184f)
            frame()
            val releasedFine = selector.selection!!.value
            assertTrue(releasedFine > fineValue)
            event(PointerEventType.Release, 296f, 184f)
            frame()
            assertFalse(selector.active)
            assertEquals("fine release should commit without a separate left lock", releasedFine / 100f, player.playbackSpeed, 0f)
            assertEquals(releasedFine, selector.lockedHint)
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
