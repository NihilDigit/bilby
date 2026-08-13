package dev.bilby.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.upstream.Allocator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * [LazyMediaSource] 是这次改动里唯一的自定义 Media3 组件,而它接的三根线都是"不出声就查不出"
 * 的那种:时间线透传错了表现为队列身份错乱,释放漏了表现为几小时后内存涨,解析失败被吞掉表现为
 * 永远转圈且日志为空。所以测的是这三根线,不是它内部怎么记状态。
 *
 * 要 Robolectric 是因为 Media3 的准备与释放都跑在一个 Looper 上,普通 JVM 单测里没有。
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
// compileSdk 走在 Robolectric 支持的最高档前面,不钉住的话它按 targetSdk 找镜像然后报
// "Unsupported SDK"。这里只用到 Looper,哪一档都一样。
@Config(sdk = [35])
class LazyMediaSourceTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val item = MediaItem.Builder().setMediaId("BV1xx411c7mD").build()

    @Test
    fun `发布的时间线带的是自己的身份,不是内层源的`() {
        val child = FakeMediaSource(MediaItem.fromUri("https://cdn.example/video.m4s"))
        val source = LazyMediaSource(item, scope) { child }
        val caller = RecordingCaller()

        source.prepareSource(caller, null, PlayerId.UNSET)
        idle()

        val window = caller.timeline!!.getWindow(0, Timeline.Window())
        assertEquals("BV1xx411c7mD", window.mediaItem.mediaId)
        // 时长这类窗口内容照旧由内层源说了算,只有身份被换掉。
        assertEquals(FakeMediaSource.DURATION_US, window.durationUs)
    }

    @Test
    fun `解析失败从 maybeThrowSourceInfoRefreshError 抛出去`() {
        val source = LazyMediaSource(item, scope) { throw IOException("取流失败") }
        val caller = RecordingCaller()

        source.prepareSource(caller, null, PlayerId.UNSET)
        idle()

        // 播放器在还没有真周期时只问这一处,吞掉的话它会永远停在 BUFFERING。
        val thrown = runCatching { source.maybeThrowSourceInfoRefreshError() }.exceptionOrNull()
        assertEquals("取流失败", thrown?.message)
        assertNull("失败时不该发时间线", caller.timeline)
    }

    @Test
    fun `非 IO 异常也包成 IOException 抛出`() {
        val source = LazyMediaSource(item, scope) { error("详情拿不到") }

        source.prepareSource(RecordingCaller(), null, PlayerId.UNSET)
        idle()

        val thrown = runCatching { source.maybeThrowSourceInfoRefreshError() }.exceptionOrNull()
        assertTrue(thrown is IOException)
        assertEquals("详情拿不到", thrown?.message)
    }

    @Test
    fun `释放会连内层源一起释放`() {
        val child = FakeMediaSource(MediaItem.fromUri("https://cdn.example/video.m4s"))
        val source = LazyMediaSource(item, scope) { child }
        val caller = RecordingCaller()

        source.prepareSource(caller, null, PlayerId.UNSET)
        idle()
        source.releaseSource(caller)
        idle()

        assertTrue("内层源没被释放,这条流的连接和缓冲会一直留着", child.released)
    }

    @Test
    fun `解析还没回来就被释放,迟到的结果不会挂上去`() {
        val gate = CompletableDeferred<MediaSource>()
        val child = FakeMediaSource(MediaItem.fromUri("https://cdn.example/video.m4s"))
        val source = LazyMediaSource(item, scope) { gate.await() }
        val caller = RecordingCaller()

        source.prepareSource(caller, null, PlayerId.UNSET)
        source.releaseSource(caller)
        gate.complete(child)
        idle()

        assertNull("释放之后不该再发时间线", caller.timeline)
        assertTrue("迟到的子源没被准备,自然也不该被准备", !child.prepared)
    }

    /** Handler 投递回播放线程的那一步靠它跑起来,见 [LazyMediaSource.prepareSourceInternal]。 */
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private class RecordingCaller : MediaSource.MediaSourceCaller {
        var timeline: Timeline? = null

        override fun onSourceInfoRefreshed(source: MediaSource, timeline: Timeline) {
            this.timeline = timeline
        }
    }

    /** 只做一件事:准备时发一条单窗口时间线,释放时记一笔。 */
    private class FakeMediaSource(private val item: MediaItem) : BaseMediaSource() {
        var prepared = false
        var released = false

        override fun getMediaItem(): MediaItem = item

        override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
            prepared = true
            refreshSourceInfo(
                SinglePeriodTimeline(
                    DURATION_US,
                    /* isSeekable = */ true,
                    /* isDynamic = */ false,
                    /* useLiveConfiguration = */ false,
                    /* manifest = */ null,
                    item,
                )
            )
        }

        override fun releaseSourceInternal() {
            released = true
        }

        override fun maybeThrowSourceInfoRefreshError() = Unit

        override fun createPeriod(
            id: MediaSource.MediaPeriodId,
            allocator: Allocator,
            startPositionUs: Long,
        ): MediaPeriod = throw UnsupportedOperationException()

        override fun releasePeriod(mediaPeriod: MediaPeriod) = Unit

        companion object {
            const val DURATION_US = 12_000_000L
        }
    }
}
