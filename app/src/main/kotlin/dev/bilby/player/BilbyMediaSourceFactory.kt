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
private const val EXTRA_ITEM_START_MS = "startMs"
private const val EXTRA_ITEM_LOAD = "load"
private const val EXTRA_ITEM_ROOM_ID = "roomId"
private const val EXTRA_ITEM_LIVE_QN = "liveQn"

/** 直播项的 mediaId。带前缀是为了和 bvid 分得开:两种条目住在同一份 playlist 里。 */
fun liveMediaId(roomId: Long): String = "live:$roomId"

/**
 * 这一次装载指名的分 P,0 表示"没指名,由解析层决定"(见 [LoadResolver])。
 *
 * **这是一次性的意图,不是这一条的状态。** 正在播的是哪一 P 由服务的装载状态回答
 * ([AudioPlaybackService.loadedCid]),解析出来的 cid 不回写到这里 —— 回写就等于把播放层的
 * 内部状态挂到了队列的身份上,而队列的身份只有 bvid。**队列项本身不带 cid**:普通队列项
 * (自动连播、点队列里的一条)这个值恒为 0,每次装载都重新解析一遍该放哪一 P。
 */
val MediaItem.cidHint: Long
    get() = requestMetadata.extras?.getLong(EXTRA_ITEM_CID) ?: 0L

/** 指定的起播位置(毫秒),null 表示按续播记录起播。切清晰度要停在原地,走的是这一条。 */
val MediaItem.startPositionHint: Long?
    get() = requestMetadata.extras?.getLong(EXTRA_ITEM_START_MS, -1L)?.takeIf { it >= 0 }

/**
 * 第几次装载这一条。**"要不要重新取流"就是靠它表达的。**
 *
 * 重试和切清晰度都要连取流一起重来:直链过期(403)是最常见的那种失败,拿同一条地址再
 * prepare 一次必然还是同样的错。而 [LazyMediaSource.canUpdateMediaItem] 认为条目没变时,
 * 播放器只换条目、不重建源 —— 那正是补标题要的行为,却让重试变成空转。两者的区别在这个数上。
 */
val MediaItem.loadNonce: Int
    get() = requestMetadata.extras?.getInt(EXTRA_ITEM_LOAD) ?: 0

/** 直播间的房间号,0 表示这不是直播项。取流按它走直播接口,见 [isLive]。 */
val MediaItem.liveRoomId: Long
    get() = requestMetadata.extras?.getLong(EXTRA_ITEM_ROOM_ID) ?: 0L

/** 直播要的档位,0 表示按默认档要。断流重取沿用它,否则画质会在用户没动过的情况下自己跳。 */
val MediaItem.liveQn: Int
    get() = requestMetadata.extras?.getInt(EXTRA_ITEM_LIVE_QN) ?: 0

/**
 * 这一条是直播。
 *
 * **判据是房间号在不在,不是 mediaId 长什么样**:前者是取流真正要用的东西,后者只是身份。
 * 工厂按它分派(见 [BilbyMediaSourceFactory]),于是"直播是队列里的一条"这件事在别处不必
 * 再有第二处分支。
 */
val MediaItem.isLive: Boolean
    get() = liveRoomId != 0L

/**
 * 队列项转成播放器认识的条目。
 *
 * 装载参数放 `requestMetadata` 而不是 `mediaId` 里:身份只有 bvid(队列去重、页面判断
 * "播的是不是我这一条"都按它),而它们是这一次装载怎么取内容的指示,解析层要在自己那条线程上
 * 读到 —— 那时发起装载的那次调用早就返回了。**也不用 `MediaItem.tag`**:tag 不进
 * `toBundle`,过一次 session binder 就没了。
 *
 * 标题、UP 名、封面进 `mediaMetadata`,通知栏和锁屏直接读得到 —— 元数据不必再由服务覆写
 * `getMediaMetadata` 喂给 MediaSession。
 */
fun QueueItem.toMediaItem(
    requestedCid: Long = 0,
    startPositionMillis: Long? = null,
    loadNonce: Int = 0,
): MediaItem = MediaItem.Builder()
    .setMediaId(bvid)
    .setRequestMetadata(
        loadParams(
            requestedCid = requestedCid,
            startPositionMillis = startPositionMillis,
            loadNonce = loadNonce,
        )
    )
    .setMediaMetadata(displayMetadata())
    .build()

/**
 * 一个直播间在队列里的样子。**和视频项是同一种东西**,差别只在装载参数:身份是房间号,
 * 取流走直播接口,没有分 P 也没有起播位置。
 *
 * [display] 的 `bvid` 必须是 [liveMediaId] —— 队列面板和页面身份都按它,而房间号本身
 * 已经在装载参数里了。
 */
fun liveMediaItem(
    display: QueueItem,
    roomId: Long,
    qn: Int,
    loadNonce: Int = 0,
): MediaItem = MediaItem.Builder()
    .setMediaId(display.bvid)
    .setRequestMetadata(loadParams(roomId = roomId, liveQn = qn, loadNonce = loadNonce))
    .setMediaMetadata(display.displayMetadata())
    .build()

/**
 * 同一条内容,换一组装载参数:换 P、切清晰度、重试、直播换档或切纯音频都走这里。
 *
 * **换掉的是整份参数,不是往里补一个字段。** 每个参数都在签名里给出默认值,直播的档位默认
 * 沿用当前值 —— 不沿用的话断一次流重来,画质自己跳一档,而那件事用户没做过。
 *
 * 身份与展示信息原样留着:重来一遍不是换了一条内容。
 */
fun MediaItem.withLoadParams(
    loadNonce: Int,
    requestedCid: Long = 0,
    startPositionMillis: Long? = null,
    liveQn: Int = this.liveQn,
): MediaItem = buildUpon()
    .setRequestMetadata(
        loadParams(
            requestedCid = requestedCid,
            startPositionMillis = startPositionMillis,
            loadNonce = loadNonce,
            roomId = liveRoomId,
            liveQn = liveQn,
        )
    )
    .build()

private fun loadParams(
    requestedCid: Long = 0,
    startPositionMillis: Long? = null,
    loadNonce: Int = 0,
    roomId: Long = 0,
    liveQn: Int = 0,
): MediaItem.RequestMetadata = MediaItem.RequestMetadata.Builder()
    .setExtras(
        Bundle().apply {
            putLong(EXTRA_ITEM_CID, requestedCid)
            putLong(EXTRA_ITEM_START_MS, startPositionMillis ?: -1L)
            putInt(EXTRA_ITEM_LOAD, loadNonce)
            putLong(EXTRA_ITEM_ROOM_ID, roomId)
            putInt(EXTRA_ITEM_LIVE_QN, liveQn)
        }
    )
    .build()

/**
 * 只换展示信息的那一份条目。原来的 `requestMetadata` 原样留着 —— 装载参数一个字节都不能动,
 * 动了就是另一次装载,播放器会重建源、画面从头开始。
 */
fun MediaItem.withDisplay(item: QueueItem): MediaItem =
    buildUpon().setMediaMetadata(item.displayMetadata()).build()

private fun QueueItem.displayMetadata(): MediaMetadata = MediaMetadata.Builder()
    .setTitle(title)
    .setArtist(upName)
    .setArtworkUri(coverUrl.takeIf { it.isNotEmpty() }?.toUri())
    // 时长为 0 是"还不知道",不是"零秒"。填进去的话通知栏会画一条已经走到头的进度条。
    .setDurationMs(durationSeconds.takeIf { it > 0 }?.times(1000))
    .setIsBrowsable(false)
    .setIsPlayable(true)
    .build()

/**
 * 播放器里的条目回到队列项。**队列就是 playlist**,面板要显示的东西全从这里读,服务不另存
 * 一份列表 —— 两份列表意味着"队列现在是什么"有两个答案,而它们只在没人动过队列时相等。
 */
fun MediaItem.toQueueItem(): QueueItem = QueueItem(
    bvid = mediaId,
    title = mediaMetadata.title?.toString().orEmpty(),
    upName = mediaMetadata.artist?.toString().orEmpty(),
    coverUrl = mediaMetadata.artworkUri?.toString().orEmpty(),
    durationSeconds = (mediaMetadata.durationMs ?: 0L) / 1000,
)
