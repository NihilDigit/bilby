package dev.bilby.desktop.player

import org.openani.mediamp.source.UriMediaData

/**
 * 一次播放要打开的流。点播 DASH 的视频与音频是两条独立 URL,直播与本地文件只有 [video]。
 */
data class StreamSource(
    val video: String,
    val audio: String? = null,
)

/**
 * 取流请求头,与 Android 端 `BiliConstants` 的 USER_AGENT、REFERER、ORIGIN 相同。
 * :app 迁到 KMP 后改为直接引用那份常量。
 */
private val STREAM_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/131.0.0.0 Safari/537.36",
    "Referer" to "https://www.bilibili.com",
    "Origin" to "https://www.bilibili.com",
)

fun StreamSource.toMediaData(): UriMediaData {
    val uri = if (audio == null) video else dashEdl(video, audio)
    val isNetwork = video.startsWith("http://") || video.startsWith("https://")
    return UriMediaData(uri, headers = if (isNetwork) STREAM_HEADERS else emptyMap())
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
