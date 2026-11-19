package dev.bilby.ui

import dev.bilby.api.BiliClient
import dev.bilby.api.toHttpsUrl
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.net.URI

/**
 * 原图下载与文件名推导的跨平台公共逻辑,供桌面与 Android 的 [SystemActions] 复用。
 */
internal object ImageSaveHelper {
    const val MAX_IMAGE_BYTES = 64 * 1024 * 1024

    /**
     * 规范化图片地址为 `https://`,并对 B 站图床(`.hdslb.com` / `.biliimg.com`)剥离 `@...` 裁切/转码后缀,
     * 以获取原图。
     */
    fun originalImageUrl(url: String): String {
        val https = url.trim().toHttpsUrl()
        if (!https.startsWith("https://", ignoreCase = true)) return https
        val hostEnd = https.indexOf('/', startIndex = 8)
        if (hostEnd <= 8) return https
        val host = https.substring(8, hostEnd)
        if (host.endsWith(".hdslb.com", ignoreCase = true) ||
            host.endsWith(".biliimg.com", ignoreCase = true) ||
            host.equals("hdslb.com", ignoreCase = true) ||
            host.equals("biliimg.com", ignoreCase = true)
        ) {
            return https.substringBefore('@')
        }
        return https
    }

    /**
     * 从图片 URL 推导安全的文件名与标准化扩展名(`jpg`/`png`/`webp`/`gif`,默认 `jpg`)。
     */
    fun suggestFileNameAndExtension(
        url: String,
        fallbackTimestampMillis: Long = System.currentTimeMillis(),
    ): Pair<String, String> {
        val rawPath = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val lastSegment = rawPath.substringAfterLast('/').substringBefore('@').substringBefore('?')
        val dot = lastSegment.lastIndexOf('.')
        val rawExt = if (dot > 0) lastSegment.substring(dot + 1).lowercase() else ""
        val ext = when (rawExt) {
            "png", "webp", "gif", "jpeg", "jpg" -> if (rawExt == "jpeg") "jpg" else rawExt
            else -> "jpg"
        }
        val rawStem = if (dot > 0) lastSegment.substring(0, dot) else lastSegment
        val safeStem = rawStem.replace(Regex("""[<>:"/\\|?*\x00-\x1F]"""), "_").trim()
            .ifEmpty { "bilby_$fallbackTimestampMillis" }
        return "$safeStem.$ext" to ext
    }

    fun mimeTypeForExtension(extension: String): String = when (extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "image/jpeg"
    }

    /** 经 [BiliClient.streamGet] 下载原图字节,受 [MAX_IMAGE_BYTES] 上限约束。 */
    suspend fun downloadOriginalBytes(client: BiliClient, url: String): ByteArray {
        val targetUrl = originalImageUrl(url)
        return client.streamGet(targetUrl) { response ->
            check(response.status.isSuccess()) { "HTTP ${response.status.value}" }
            val channel = response.bodyAsChannel()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = channel.readAvailable(buffer)
                if (read <= 0) break
                total += read
                check(total <= MAX_IMAGE_BYTES) { "image exceeds $MAX_IMAGE_BYTES bytes" }
                out.write(buffer, 0, read)
            }
            check(total > 0) { "empty image body" }
            out.toByteArray()
        }
    }
}
