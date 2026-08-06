package com.bilby.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.bilby.api.BiliConstants

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

    fun createPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context).build()

    fun createMediaSource(videoUrl: String, audioUrl: String?): MediaSource {
        val factory = ProgressiveMediaSource.Factory(httpDataSourceFactory())
        val video = factory.createMediaSource(MediaItem.fromUri(videoUrl))
        val audio = audioUrl?.let { factory.createMediaSource(MediaItem.fromUri(it)) }
        return if (audio == null) video else MergingMediaSource(video, audio)
    }

    private fun httpDataSourceFactory() = DefaultHttpDataSource.Factory()
        .setUserAgent(BiliConstants.USER_AGENT)
        .setDefaultRequestProperties(
            mapOf(
                "Referer" to BiliConstants.REFERER,
                "Origin" to BiliConstants.ORIGIN,
            )
        )
        .setAllowCrossProtocolRedirects(true)
}
