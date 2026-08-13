package dev.bilby.ui

import androidx.navigation3.runtime.NavKey
import dev.bilby.BvidCodec

/**
 * 一条 bilibili 链接指向应用里的哪一页。
 *
 * **只认 UGC**:视频、直播间、UP 主空间、专栏。番剧、影视、课堂这些**不是"还没做",是
 * Non-Goal** —— 它们是有版权方的商业内容,这个应用不碰。解析直接失败,不给一个跳过去发现
 * 是空壳的入口。
 *
 * 解析是纯函数,不碰网络。短链(b23.tv)例外:它必须先展开一次才知道指向哪儿,
 * 那一步由 `BiliClient.resolveRedirect` 做(不带 Cookie),展开之后再送回这里。
 */
object BilbyLink {

    /** b23.tv 这类短链,要先跟一次跳转才知道目的地。 */
    fun isShortLink(url: String): Boolean {
        val host = hostOf(url) ?: return false
        return host == "b23.tv" || host == "bili2233.cn"
    }

    /**
     * 从一段文本里挑出第一个链接。
     *
     * 分享给出来的从来不是裸链接,B 站客户端复制的那段长这样:
     * `【标题】 https://b23.tv/xxxx 复制这段内容打开哔哩哔哩...`。
     */
    fun extractUrl(text: String): String? = URL_PATTERN.find(text)?.value

    /**
     * 链接指向哪一页。认不出来返回 null。
     *
     * 分 P 参数(`?p=2`)**故意不解析**:换 P 走的是播放页内部的重组,而 [Video] 这个
     * NavKey 只带 bvid;更要紧的是打开一条多 P 视频时我们会接着上次那一 P 播
     * (见 `AudioPlaybackService`),链接里带的 p 和那条规则会互相打架。等真有人需要
     * "分享到第几 P"再一起想,而不是现在留一个两条规则谁赢不确定的入口。
     */
    fun destinationOf(url: String): NavKey? {
        val host = hostOf(url) ?: return null
        val path = pathOf(url)
        val segments = path.split('/').filter { it.isNotEmpty() }

        if (host == "live.bilibili.com") {
            val roomId = segments.firstOrNull()?.toLongOrNull() ?: return null
            return LiveRoom(roomId)
        }
        if (host == "space.bilibili.com") {
            val mid = segments.firstOrNull()?.toLongOrNull() ?: return null
            return Space(mid)
        }
        // `endsWith("bilibili.com")` 会放行 `evilbilibili.com` —— 少一个点就是另一个域名。
        if (host != "bilibili.com" && !host.endsWith(".bilibili.com")) return null

        // 专栏。**两套编号,不是同一个东西的两种写法**:`/opus/<id>` 是新版,`/read/cv<id>`
        // 是旧版,取的接口不同(notes/article.md 第 0 节),所以要连"是哪一套"一起带走。
        segments.indexOf("opus").takeIf { it >= 0 }?.let { index ->
            return segments.getOrNull(index + 1)
                ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.let { ArticlePage(id = it, isRead = false) }
        }
        segments.indexOf("read").takeIf { it >= 0 }?.let { index ->
            return segments.getOrNull(index + 1)
                ?.removePrefix("cv")
                ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.let { ArticlePage(id = it, isRead = true) }
        }

        val videoIndex = segments.indexOf("video")
        if (videoIndex >= 0) {
            val id = segments.getOrNull(videoIndex + 1) ?: return null
            return videoDestination(id)
        }
        // 手机端的 /BVxxxx 直链(m.bilibili.com 有时给这种)。
        return segments.firstOrNull()?.let(::videoDestination)
    }

    /**
     * BV 号直接用,av 号转成 BV 号。
     *
     * **不接受 av 号原样传下去**:全应用的视频身份是 bvid,一条 av 路径塞进 [Video] 会在
     * 取详情那一步才失败,而那时已经压了一页。
     */
    private fun videoDestination(id: String): NavKey? = when {
        id.startsWith("BV", ignoreCase = false) && id.length >= 3 -> Video(id)
        id.startsWith("av", ignoreCase = true) ->
            id.drop(2).toLongOrNull()?.takeIf { it > 0 }?.let { Video(BvidCodec.fromAid(it)) }

        else -> null
    }

    private fun hostOf(url: String): String? = runCatching {
        java.net.URI(url.trim()).host?.lowercase()?.removePrefix("www.")
            ?.let { if (it.startsWith("m.bilibili")) it.removePrefix("m.") else it }
    }.getOrNull()

    private fun pathOf(url: String): String =
        runCatching { java.net.URI(url.trim()).path.orEmpty() }.getOrDefault("")

    /** 链接里合法的字符按 RFC 3986 取,末尾的中文标点不吃进来。 */
    private val URL_PATTERN = Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""")
}

