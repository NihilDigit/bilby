package dev.bilby.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import dev.bilby.api.BiliConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 播放链路上"实际用了什么"的一手信息:解码器名、倍速算法。设置页显示这一节,出问题时它是
 * 第一手材料——"卡/烫"看解码器,"倍速音质不对"看算法和回退原因。
 */
data class PlaybackTechInfo(
    /** 如 `c2.qti.avc.decoder`(硬解)或 `c2.android.avc.decoder`(软解)。 */
    val videoDecoder: String? = null,
    val audioDecoder: String? = null,
    /** 此刻真正在做时间伸缩的算法,不是配置值——回退发生后这里会变成 Sonic。 */
    val speedAlgorithm: SpeedAlgorithm = SpeedAlgorithm.Wsola,
    /** 非 null 表示倍速回退过,内容是具体原因。UI 可以只在非 null 时显示这一行。 */
    val speedFallbackReason: String? = null,
) {
    /**
     * 硬解判定按名字前缀:`c2.android.*` 与 `OMX.google.*` 是 AOSP 的软解实现,其余是厂商的。
     * 这只用于展示——真正的硬解保证来自选流阶段(见 [DeviceCodecs]),不是这里的字符串判断。
     */
    val videoIsHardware: Boolean?
        get() = videoDecoder?.let {
            !it.startsWith("c2.android.") && !it.startsWith("OMX.google.")
        }
}

/**
 * B 站的 playurl 返回的不是标准 MPD,而是自家 JSON 里两条独立的直链(video 一条、audio 一条)。
 * PiliPlus 用 libmpv 的 `edl://` 伪协议把两者拼起来,Media3 没有对应机制,所以这里各建一条
 * ProgressiveMediaSource 再用 MergingMediaSource 合并 —— 播放器把它们当作同一条时间线的
 * 两个轨道对待,seek 与播放控制自动同步。
 *
 * 请求头挂在 DataSource 工厂上而不是逐条流设置:防盗链对两条流一视同仁,漏掉音频那条会
 * 表现为"有画面没声音",而不是明显的报错。
 */
@UnstableApi
object PlayerFactory {

    private val _techInfo = MutableStateFlow(PlaybackTechInfo())

    /** UI 观察这个。解码器名在开始解码后才有值,倍速算法在音频格式确定后才准。 */
    val techInfo: StateFlow<PlaybackTechInfo> = _techInfo.asStateFlow()

    /**
     * **只有 [AudioPlaybackService] 该调它**。全 app 一个播放器(DESIGN 2.4b),UI 要播放器就
     * 连 MediaController;再调一次这里就又有了两个会同时发声的播放器。
     */
    fun createPlayer(context: Context): ExoPlayer {
        DeviceCodecs.logOnce()
        val forced = SpeedAlgorithmSelector.forcedAlgorithm(context)
        if (forced != null) PlayerLog.d("倍速算法被强制为 $forced(debug 开关)")
        return ExoPlayer.Builder(context, renderersFactory(context, forced))
            .build()
            .apply { addAnalyticsListener(DecoderLogger()) }
    }

    /**
     * 显式建 [DefaultRenderersFactory] 而不是用 `ExoPlayer.Builder(context)` 的隐式默认值。
     * 三项设置里只有一项改变了行为,另外两项是把默认值写出来当契约——不写的话,以后有人
     * 顺手改成 `PREFER_SOFTWARE` 排查问题、忘了改回来,是查不出来的耗电回归。
     *
     * - **[MediaCodecSelector.DEFAULT]**:等于 `MediaCodecUtil.getDecoderInfos`,返回的是
     *   `MediaCodecList` 的原始顺序,而框架保证"好的解码器在前",Android 10 起厂商硬解
     *   (`c2.qti.*`)排在 AOSP 软解(`c2.android.*`)之前。renderer 之后只按"格式是否被支持"
     *   再稳定排一次,不改变硬/软的相对次序。**所以硬解优先是 Media3 的既有行为,不需要打开
     *   什么开关**;需要防的是被人换成 `PREFER_SOFTWARE`。
     * - **[DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF]**:默认值。扩展 renderer
     *   (libav1/ffmpeg/opus)是纯软解,而且我们的依赖里根本没有这些 aar,它们是靠反射
     *   `Class.forName` 加载的,写成 OFF 是防止将来引入某个 aar 后无声地多出一条软解路径。
     * - **`enableDecoderFallback = true`**:这一项**改变了默认行为**(默认 false)。硬解器
     *   偶尔会在 configure 阶段抛异常(厂商实现对某些分辨率/profile 的边界情况),默认行为是
     *   直接抛 `DecoderInitializationException`,在我们这儿会被 `onPlayerError` 当成取流失败
     *   跳到下一条——一条本可以软解播出来的视频被静默跳过。打开后先试下一个解码器,实在不行才报错。
     */
    private fun renderersFactory(context: Context, forced: SpeedAlgorithm?): RenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val chain = SpeedAudioProcessorChain(forced)
                chain.speedProcessor.onStateChanged = { state ->
                    // 音频线程回调。只在算法真的变化时到达,不是每个缓冲区。
                    _techInfo.value = _techInfo.value.copy(
                        speedAlgorithm = state.algorithm,
                        speedFallbackReason = state.fallbackReason,
                    )
                }
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    // 保持 false(默认):true 会把倍速交给平台 AudioTrack 的
                    // PlaybackParams,那条路的时间伸缩由 AudioFlinger 实现,厂商之间音质
                    // 差异很大且无法审计,还会绕过整条 AudioProcessor 链(连同我们的回退)。
                    .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                    .setAudioProcessorChain(chain)
                    .build()
            }
        }
            .setMediaCodecSelector(MediaCodecSelector.DEFAULT)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)

    fun createMediaSource(videoUrl: String, audioUrl: String?): MediaSource =
        mergedSource(ProgressiveMediaSource.Factory(httpDataSourceFactory()), videoUrl, audioUrl)

    /**
     * 离线缓存的本地文件。**结构和网络那条完全一样** —— 同样是两条分离的流靠
     * [MergingMediaSource] 合成一条时间线,只是数据源换成了 [FileDataSource]。
     *
     * 这正是这个功能没有用 Media3 `DownloadManager` + `CacheDataSource` 的收益所在
     * (理由见 `offline/OfflineDownloader`):落成普通文件之后,离线播放不需要缓存层,
     * 也不需要一份"这条流的 cache key 是什么"的约定。
     */
    fun createLocalMediaSource(videoPath: String, audioPath: String?): MediaSource =
        mergedSource(
            ProgressiveMediaSource.Factory(FileDataSource.Factory()),
            Uri.fromFile(java.io.File(videoPath)).toString(),
            audioPath?.let { Uri.fromFile(java.io.File(it)).toString() },
        )

    private fun mergedSource(
        factory: ProgressiveMediaSource.Factory,
        videoUri: String,
        audioUri: String?,
    ): MediaSource {
        val video = factory.createMediaSource(MediaItem.fromUri(videoUri))
        val audio = audioUri?.let { factory.createMediaSource(MediaItem.fromUri(it)) }
        return if (audio == null) video else MergingMediaSource(video, audio)
    }

    /**
     * 直播流。HLS 走 [HlsMediaSource],其余(FLV)退回 progressive —— Media3 内建的
     * `FlvExtractor` 能把它当无限流播。**判据是 URL 而不是接口给的 protocol 名**:
     * 拼出来的地址才是真正要喂给播放器的东西,而 `LiveRepository` 挑流时已经把两者对上了。
     *
     * Referer 指到直播站点而不是主站:直播 CDN 的防盗链认的是前者。
     */
    fun createLiveMediaSource(url: String): MediaSource {
        val factory = liveHttpDataSourceFactory()
        val item = MediaItem.fromUri(url)
        return if (url.substringBefore('?').endsWith(".m3u8")) {
            HlsMediaSource.Factory(factory).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(factory).createMediaSource(item)
        }
    }

    private fun liveHttpDataSourceFactory() = DefaultHttpDataSource.Factory()
        .setUserAgent(BiliConstants.USER_AGENT)
        .setDefaultRequestProperties(mapOf("Referer" to BiliConstants.LIVE_REFERER))
        .setAllowCrossProtocolRedirects(true)

    private fun httpDataSourceFactory() = DefaultHttpDataSource.Factory()
        .setUserAgent(BiliConstants.USER_AGENT)
        // CDN 取流只认 Referer 和 UA。这里曾经还发 Origin,和其余请求一起去掉了:
        // 真实浏览器的同源请求不带 Origin,带了反而是个可观测的伪装破绽。
        // 已真机验证去掉后取流正常(state=PLAYING),CDN 不校验它。
        .setDefaultRequestProperties(mapOf("Referer" to BiliConstants.REFERER))
        .setAllowCrossProtocolRedirects(true)

    /**
     * 记下实际选中的解码器。这是"到底走没走硬解"唯一可靠的证据来源:选流阶段能保证
     * "这个编码本机有硬解器",但真正用了哪一个只有 MediaCodec 知道。
     */
    private class DecoderLogger : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            PlayerLog.d("视频解码器: $decoderName")
            _techInfo.value = _techInfo.value.copy(videoDecoder = decoderName)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            PlayerLog.d("音频解码器: $decoderName")
            _techInfo.value = _techInfo.value.copy(audioDecoder = decoderName)
        }
    }
}
