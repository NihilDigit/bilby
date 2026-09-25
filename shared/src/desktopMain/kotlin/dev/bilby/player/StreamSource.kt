package dev.bilby.player

import dev.bilby.api.BiliConstants
import org.openani.mediamp.source.UriMediaData

/**
 * 一次播放要打开的流。点播 DASH 的视频与音频是两条独立 URL,直播与本地文件只有 [video]。
 * [referer] 按来源给:点播用主站,直播用直播站,指错会被防盗链拒绝。
 */
data class StreamSource(
    val video: String,
    val audio: String? = null,
    val referer: String = BiliConstants.REFERER,
)

fun StreamSource.toMediaData(): UriMediaData {
    val uri = if (audio == null) video else dashEdl(video, audio)
    val isNetwork = video.startsWith("http://") || video.startsWith("https://")
    // 取流有防盗链,要 Referer + Origin + UA,两条流一视同仁(见 BiliConstants.ORIGIN)。
    val headers = mapOf(
        "User-Agent" to BiliConstants.USER_AGENT,
        "Referer" to referer,
        "Origin" to BiliConstants.ORIGIN,
    )
    return UriMediaData(uri, headers = if (isNetwork) headers else emptyMap())
}

/**
 * 把分离的视频轨与音频轨拼成一个 mpv 虚拟文件,写法同 PiliPlus(`pages/video/controller.dart`)。
 *
 * `%n%` 前缀里的 n 是 UTF-8 字节数。PiliPlus 对 URL 用字符数、对本地文件用字节数,只因为
 * URL 都是 ASCII 两者才相等;统一按字节数算,含中文的本地路径也不会截错。
 */
internal fun dashEdl(video: String, audio: String): String =
    "edl://!no_chapters;${edlEntry(video)};!new_stream;!no_chapters;${edlEntry(audio)}"

private fun edlEntry(value: String): String = "%${value.encodeToByteArray().size}%$value"
