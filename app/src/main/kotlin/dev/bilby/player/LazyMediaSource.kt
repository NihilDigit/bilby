package dev.bilby.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 一条队列项,取流推迟到播放器真的要放它的那一刻。
 *
 * 队列住在 ExoPlayer 的 playlist 里,而 playurl 给的是带时效的 CDN 直链 —— 建队列时把整份
 * 队列的地址取好,排在后面的那些等轮到时早就过期了,表现为播到某条突然 403 而前面几条都正常。
 * 所以 playlist 里放的是这个壳:它只带 [mediaItem](身份是 bvid),真正的
 * [MergingMediaSource][androidx.media3.exoplayer.source.MergingMediaSource] 要等
 * [prepareSourceInternal] 被调用时才由 [resolve] 造出来。
 *
 * ExoPlayer 默认开着 lazy preparation,playlist 里的每一项都被包在 `MaskingMediaSource` 里,
 * 时间线还没到之前它用占位时间线和 `MaskingMediaPeriod` 顶着。所以"迟迟不发时间线"是
 * 播放器本来就支持的状态,不需要先给一份假的。
 *
 * **时间线要换掉里面的 MediaItem。** 内层源是拿 CDN 直链现造的,它的时间线窗口里挂的是那条
 * 直链的 MediaItem;不换的话 `player.currentMediaItem.mediaId` 拿到的是一串 URL,而队列的
 * 全部身份判断都靠 bvid。
 *
 * **解析失败必须落到 [maybeThrowSourceInfoRefreshError]**,那是 `MaskingMediaPeriod` 在还没有
 * 真周期时唯一会问的地方,由它一路抛到 `onPlayerError`。吞掉的话播放器会永远停在 BUFFERING,
 * 界面上是转圈转到天荒地老,日志里一个字都没有。
 */
@UnstableApi
class LazyMediaSource(
    mediaItem: MediaItem,
    private val scope: CoroutineScope,
    private val resolve: suspend (MediaItem) -> MediaSource,
) : CompositeMediaSource<Unit>() {

    @Volatile
    private var mediaItem: MediaItem = mediaItem

    private var child: MediaSource? = null
    private var resolveJob: Job? = null
    private var resolveError: IOException? = null

    /** 换标题时要照原样再发一次时间线,见 [updateMediaItem]。没解析完时为 null。 */
    private var childTimeline: Timeline? = null

    /**
     * 准备与释放会成对发生多次(playlist 增删、播放器 stop 后再 prepare),而解析的结果是
     * 异步回到播放线程的。这个计数让上一轮飞在半空的结果认得出自己已经过期 —— 否则它会把
     * 一个属于已释放那一轮的子源挂上来。
     */
    private var generation = 0

    override fun getMediaItem(): MediaItem = mediaItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        resolveError = null
        val handler = Util.createHandlerForCurrentLooper()
        val current = ++generation
        resolveJob = scope.launch {
            val result = runCatching { resolve(mediaItem) }
            handler.post { onResolved(current, result) }
        }
    }

    /** 回到播放线程之后才动状态:子源的准备与释放都要求在这条线程上。 */
    private fun onResolved(forGeneration: Int, result: Result<MediaSource>) {
        if (forGeneration != generation) return
        result.fold(
            onSuccess = { source ->
                child = source
                prepareChildSource(Unit, source)
            },
            onFailure = { cause ->
                resolveError = cause as? IOException ?: IOException(cause.message, cause)
            },
        )
    }

    override fun onChildSourceInfoRefreshed(
        id: Unit,
        mediaSource: MediaSource,
        newTimeline: Timeline,
    ) {
        childTimeline = newTimeline
        refreshSourceInfo(ItemTimeline(newTimeline))
    }

    /**
     * 只换展示信息,不换要取的流。
     *
     * 页面拿到 bvid 就发了打开命令,那时它还不知道这条视频叫什么;标题和封面随后才到。走这条路
     * 而不是把队列项整个换掉,是因为后者会重建这个源、重新取一次流 —— 表现是标题一到位画面就
     * 从头开始。
     *
     * 反过来,换 P、重试、切清晰度都必须重来,它们各自换掉一个装载参数(见
     * [cidHint]、[loadNonce]),于是自然落到这个判断的另一边。
     */
    override fun canUpdateMediaItem(newMediaItem: MediaItem): Boolean =
        newMediaItem.mediaId == mediaItem.mediaId &&
            newMediaItem.cidHint == mediaItem.cidHint &&
            newMediaItem.loadNonce == mediaItem.loadNonce

    override fun updateMediaItem(mediaItem: MediaItem) {
        this.mediaItem = mediaItem
        childTimeline?.let { refreshSourceInfo(ItemTimeline(it)) }
    }

    override fun maybeThrowSourceInfoRefreshError() {
        resolveError?.let { throw it }
        super.maybeThrowSourceInfoRefreshError()
    }

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long,
    ): MediaPeriod = checkNotNull(child).createPeriod(id, allocator, startPositionUs)

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        child?.releasePeriod(mediaPeriod)
    }

    override fun releaseSourceInternal() {
        generation++
        resolveJob?.cancel()
        resolveJob = null
        resolveError = null
        // super 会 releaseChildSource,子源自己的释放归它;这里只丢引用。
        super.releaseSourceInternal()
        child = null
        childTimeline = null
    }

    /**
     * 时间线原样透传,只把窗口里的 MediaItem 换成这一条的身份。周期、下标、uid 一概不动 ——
     * 那些是内层源和播放器之间的约定,改了就对不上了。
     */
    private inner class ItemTimeline(timeline: Timeline) : ForwardingTimeline(timeline) {
        override fun getWindow(
            windowIndex: Int,
            window: Timeline.Window,
            defaultPositionProjectionUs: Long,
        ): Timeline.Window {
            timeline.getWindow(windowIndex, window, defaultPositionProjectionUs)
            window.mediaItem = mediaItem
            return window
        }
    }
}
