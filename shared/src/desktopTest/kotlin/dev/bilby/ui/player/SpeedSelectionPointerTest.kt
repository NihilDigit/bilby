package dev.bilby.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
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
                    selector = rememberPlaybackSpeedController(player, "qa", fastForwardSpeed = 2.5f)
                    Box(Modifier.fillMaxSize()) {
                        PlayerShell(
                            player = player, attached = false, placeholderCoverUrl = "",
                            isFullscreen = false, onFullscreenChange = {}, locked = false, onLockedChange = {}, title = "",
                            speedController = selector,
                            modifier = Modifier.size(360.dp, 202.dp),
                            overlay = { controls = controlsVisible },
                        )
                    }
                }
            }
        }
        suspend fun frame() { repeat(3) { scene.render(System.nanoTime()).close(); delay(20) } }
        fun event(type: PointerEventType, x: Float, y: Float) = scene.sendPointerEvent(type, Offset(x,y), type = PointerType.Touch)
        try {
            frame()
            // 长按起手立即切到默认倍速 2.5x
            event(PointerEventType.Press, 240f, 110f)
            delay(650)
            frame()
            assertEquals("long press should immediately apply default fast-forward speed", 2.5f, player.playbackSpeed, 0f)

            // 往左滑 30dp(1 档)并停住,保持在 2.25x 不自动乱跑
            event(PointerEventType.Move, 210f, 110f)
            frame()
            assertEquals(2.25f, player.playbackSpeed, 0f)
            delay(600)
            frame()
            assertEquals("holding still must keep the chosen speed", 2.25f, player.playbackSpeed, 0f)

            // 继续左滑到底(0.25x),再往右回拨两档(+44dp),无需退回起手点立刻回到 0.75x
            event(PointerEventType.Move, 20f, 110f)
            frame()
            assertEquals(.25f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 64f, 110f)
            frame()
            assertEquals(.75f, player.playbackSpeed, 0f)
            event(PointerEventType.Release, 64f, 110f)
            frame()
            assertEquals("releasing without lock restores original speed", 1.5f, player.playbackSpeed, 0f)

            // 长按 2.5x -> 右滑到 2.75x -> 下滑锁定 -> 上滑取消锁定 -> 再下滑锁定并松手
            event(PointerEventType.Press, 90f, 110f)
            delay(650)
            frame()
            assertTrue("long press should open speed selection", selector.active)
            assertEquals(2.5f, player.playbackSpeed, 0f)
            assertFalse("normal controls should hide", controls)
            event(PointerEventType.Move, 120f, 110f)
            frame()
            assertEquals(2.75f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 120f, 150f)
            frame()
            assertTrue(selector.selection!!.locked)
            event(PointerEventType.Move, 120f, 110f)
            frame()
            assertFalse("returning upward should undo lock", selector.selection!!.locked)
            event(PointerEventType.Move, 120f, 150f)
            frame()
            assertTrue(selector.selection!!.locked)
            event(PointerEventType.Release, 120f, 150f)
            frame()
            assertFalse(selector.active)
            assertEquals(2.75f, player.playbackSpeed, 0f)
            assertFalse("releasing a long press must not fire a normal tap", controls)
            delay(1000)
            frame()
            assertNull(selector.lockedHint)
            assertEquals(0, player.playPauseCalls)
            assertEquals(0, player.seeks)

            // 直接在视频底部 Dial 上横向拖拽
            selector.open()
            frame()
            event(PointerEventType.Press, 180f, 186f)
            event(PointerEventType.Move, 198f, 186f)
            event(PointerEventType.Move, 230f, 186f)
            frame()
            assertTrue("dial host must handle horizontal drag", selector.active)
            assertEquals(3f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 230f, 200f)
            event(PointerEventType.Release, 230f, 200f)
            frame()
            assertFalse(selector.active)
            assertEquals(3f, player.playbackSpeed, 0f)

            // 卸载时恢复长按前的原倍速
            player.setPlaybackSpeed(1.5f)
            frame()
            event(PointerEventType.Press, 90f, 110f)
            delay(650)
            frame()
            assertEquals(2.5f, player.playbackSpeed, 0f)
            scene.setContent {}
            frame()
            assertFalse(selector.active)
            assertEquals("unmount must restore pre-hold speed", 1.5f, player.playbackSpeed, 0f)
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
                    selector = rememberPlaybackSpeedController(player, "landscape-qa", fastForwardSpeed = 2f)
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
        fun event(type: PointerEventType, x: Float, y: Float) = scene.sendPointerEvent(type, Offset(x,y), type = PointerType.Touch)
        try {
            frame()
            // 左半屏长按:右侧显示竖排轨道(横排 Chip),起手立即应用默认倍速 2x,上下滑纯跟手且反向即时响应
            event(PointerEventType.Press, 200f, 200f)
            delay(650)
            frame()
            assertTrue(selector.selection!!.dockEnd)
            assertEquals(2f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 170f)
            frame()
            assertEquals(1.75f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 30f)
            frame()
            assertEquals(.25f, player.playbackSpeed, 0f)
            event(PointerEventType.Move, 200f, 74f)
            frame()
            assertEquals(.75f, player.playbackSpeed, 0f)
            event(PointerEventType.Release, 200f, 74f)
            frame()
            assertEquals(1.5f, player.playbackSpeed, 0f)

            // 左半屏长按 + 右滑锁定
            event(PointerEventType.Press, 200f, 100f)
            delay(650)
            frame()
            assertTrue(selector.active)
            assertTrue(selector.selection!!.dockEnd)
            event(PointerEventType.Move, 200f, 130f)
            frame()
            assertEquals(2.25f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 240f, 138f)
            frame()
            assertTrue("sliding right from left-half press should lock", selector.selection!!.locked)
            event(PointerEventType.Move, 200f, 146f)
            frame()
            assertFalse("sliding back left should unlock", selector.selection!!.locked)
            event(PointerEventType.Move, 240f, 154f)
            frame()
            assertTrue(selector.selection!!.locked)
            event(PointerEventType.Release, 240f, 154f)
            frame()
            assertFalse(selector.active)
            assertEquals(2.25f, player.playbackSpeed, 0f)
            assertEquals(225, selector.lockedHint)

            // 右半屏长按:左侧显示倍速条,左滑锁定
            event(PointerEventType.Press, 450f, 100f)
            delay(650)
            frame()
            assertTrue(selector.active)
            assertFalse(selector.selection!!.dockEnd)
            event(PointerEventType.Move, 450f, 130f)
            frame()
            assertEquals(2.25f, selector.selection!!.speed, 0f)
            event(PointerEventType.Move, 410f, 138f)
            frame()
            assertTrue("sliding left from right-half press should lock", selector.selection!!.locked)
            event(PointerEventType.Release, 410f, 138f)
            frame()
            assertFalse(selector.active)
            assertEquals(2.25f, player.playbackSpeed, 0f)
            assertEquals(225, selector.lockedHint)
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
