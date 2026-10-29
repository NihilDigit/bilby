package dev.bilby.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 用真实 session/controller 核实尺寸快照与事件去重,不依赖解码器或设备。 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ControllerVideoSizeTest {
    @Test
    fun `重连可同步读尺寸且同尺寸换条不要求重发尺寸事件`() {
        val player = SizePlayer()
        val context = RuntimeEnvironment.getApplication()
        val session = MediaSession.Builder(context, player).build()
        val future = MediaController.Builder(context, session.token).buildAsync()
        try {
            idle()
            assertTrue("controller 应完成连接", future.isDone)
            val controller = future.get()
            val handle = MediaControllerHandle(controller)
            assertEquals(VideoDimensions(1080, 1920), handle.videoSize)
            var sizeEvents = 0
            controller.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) { sizeEvents++ }
            })
            val snapshots = mutableListOf<VideoDimensions>()
            handle.addListener(object : PlayerListener {
                override fun onEvents() { snapshots += handle.videoSize }
            })

            player.show(1, VideoSize(1080, 1920))
            idle()
            assertEquals("B", controller.currentMediaItem?.mediaId)
            assertEquals(0, sizeEvents)
            assertEquals(VideoDimensions(1080, 1920), handle.videoSize)
            assertTrue(snapshots.contains(handle.videoSize))

            for (size in listOf(VideoSize(720, 1280), VideoSize(1920, 1080), VideoSize(1080, 1920))) {
                player.show(1, size)
                idle()
                assertEquals(VideoDimensions(size.width, size.height), handle.videoSize)
                assertEquals(handle.videoSize, snapshots.last())
            }
            assertEquals(3, sizeEvents)
        } finally {
            MediaController.releaseFuture(future)
            session.release()
            player.release()
            idle()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class SizePlayer : SimpleBasePlayer(Looper.getMainLooper()) {
        private var snapshot = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(listOf("A", "B").map { id ->
                MediaItemData.Builder(id).setMediaItem(MediaItem.Builder().setMediaId(id).build()).build()
            })
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setVideoSize(VideoSize(1080, 1920))
            .build()

        override fun getState(): State = snapshot
        override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

        fun show(index: Int, size: VideoSize) {
            snapshot = snapshot.buildUpon().setCurrentMediaItemIndex(index).setVideoSize(size).build()
            invalidateState()
        }
    }
}
