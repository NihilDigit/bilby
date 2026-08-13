package dev.bilby.player

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlinx.coroutines.CoroutineScope

/**
 * 播放器认识的唯一一种条目:身份是 bvid,流地址等到要播它的那一刻再取。
 *
 * 注册在 [androidx.media3.exoplayer.ExoPlayer] 上,于是 `setMediaItems`、`addMediaItems`、
 * `replaceMediaItem` 这些标准 playlist 命令都能用 —— 队列不再需要一套平行的自定义命令去表达
 * "下一条是什么"。
 *
 * DRM 与加载错误策略两个 setter 原样返回:这条链路上的流没有 DRM,重试由播放器的默认策略加
 * 服务自己的退避共同决定,不从这里配。[getSupportedTypes] 报 [C.CONTENT_TYPE_OTHER],因为
 * 条目根本没有 URI —— 容器类型是解析之后才知道的事。
 */
@UnstableApi
class BilbyMediaSourceFactory(
    private val scope: CoroutineScope,
    /** 把一个队列项解析成真正能播的源:离线副本走本地文件,否则取一次 playurl。失败抛异常。 */
    private val resolve: suspend (MediaItem) -> MediaSource,
) : MediaSource.Factory {

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory = this

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory = this

    override fun getSupportedTypes(): IntArray = intArrayOf(C.CONTENT_TYPE_OTHER)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        LazyMediaSource(mediaItem, scope, resolve)
}

private const val EXTRA_ITEM_CID = "cid"
private const val EXTRA_ITEM_RESUME_PART = "resumePart"
private const val EXTRA_ITEM_START_MS = "startMs"

/** 队列项里带的分 P 提示,0 表示"没指定,由解析层决定"。 */
val MediaItem.cidHint: Long
    get() = requestMetadata.extras?.getLong(EXTRA_ITEM_CID) ?: 0L

/** 这一趟要不要顺带把"上次看到第几 P"问出来。见 [AudioPlaybackService.lastPlayedPart]。 */
val MediaItem.resumePartHint: Boolean
    get() = requestMetadata.extras?.getBoolean(EXTRA_ITEM_RESUME_PART) == true

/** 指定的起播位置(毫秒),null 表示按续播记录起播。切清晰度要停在原地,走的是这一条。 */
val MediaItem.startPositionHint: Long?
    get() = requestMetadata.extras?.getLong(EXTRA_ITEM_START_MS, -1L)?.takeIf { it >= 0 }

/**
 * 队列项转成播放器认识的条目。
 *
 * cid 放在 `requestMetadata` 而不是 `mediaId` 里:身份只有 bvid(队列去重、页面判断"播的是不是
 * 我这一条"都按它),而 cid 是"这一条从哪一 P 取流",属于怎么拿到内容的那一半。
 * [resumePart] 与 [startPositionMillis] 同理 —— 它们是这一次装载的参数,解析层要在自己那条
 * 线程上读到,而那时发起装载的那次调用早就返回了。
 *
 * 标题、UP 名、封面进 `mediaMetadata`,通知栏和锁屏直接读得到 —— 元数据不必再由服务覆写
 * `getMediaMetadata` 喂给 MediaSession。
 */
fun QueueItem.toMediaItem(
    resumePart: Boolean = false,
    startPositionMillis: Long? = null,
): MediaItem = MediaItem.Builder()
    .setMediaId(bvid)
    .setRequestMetadata(
        MediaItem.RequestMetadata.Builder()
            .setExtras(
                Bundle().apply {
                    putLong(EXTRA_ITEM_CID, cid)
                    putBoolean(EXTRA_ITEM_RESUME_PART, resumePart)
                    putLong(EXTRA_ITEM_START_MS, startPositionMillis ?: -1L)
                }
            )
            .build()
    )
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(upName)
            .setArtworkUri(coverUrl.takeIf { it.isNotEmpty() }?.toUri())
            // 时长为 0 是"还不知道",不是"零秒"。填进去的话通知栏会画一条已经走到头的进度条。
            .setDurationMs(durationSeconds.takeIf { it > 0 }?.times(1000))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
    )
    .build()
