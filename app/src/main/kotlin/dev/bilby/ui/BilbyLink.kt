package dev.bilby.ui

import androidx.navigation3.runtime.NavKey
import dev.bilby.BvidCodec
import dev.bilby.data.VIDEO_COMMENT_TYPE

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
        commentThreadOf(url)?.let { return it }
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
        // 动态的网页地址。私信里的动态分享与部分通知给的就是这一种;不认的话会落到下面的
        // 视频分支,一串纯数字不是 BV 号,于是整条交给浏览器。
        if (host == "t.bilibili.com") {
            return segments.firstOrNull()
                ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
                ?.let { DynamicDetail(it) }
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

    /**
     * 指向某一条评论的链接,落到评论详情页([CommentThread])。认不出评论定位的返回 null。
     *
     * 认的几种写法照 PiliPlus 的 `utils/app_scheme.dart`(见 notes/private-message.md §8):
     *
     * - `bilibili://comment/detail/{type}/{oid}/{root}/?anchor={rpid}`,`msg_fold` 同形;
     * - `bilibili://video/{aid}?comment_root_id=…&comment_secondary_id=…`,评论区类型为 1;
     * - `bilibili://following/detail/{动态 id}?comment_root_id=…`,`opus/detail` 同形;
     * - `https://www.bilibili.com/video/{BV|av}?comment_root_id=…`。
     *
     * 动态那两种只给了动态 id,而评论区的 oid 与类型不一定就是它(图文动态的评论区挂在另一个
     * id 上):消息中心的条目另给了 `subject_id`、`business_id`,调用方传进来时优先用那两个;
     * 不给时照 PiliPlus 退到动态 id 与类型 17。
     *
     * `comment_secondary_id` 或 `anchor` 缺省时,要定位的就是根评论本身。
     */
    fun commentThreadOf(url: String, subjectId: Long = 0, businessId: Int = 0): CommentThread? {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        val query = queryOf(uri.rawQuery)
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")

        fun thread(oid: Long?, type: Int, root: Long?, target: Long?): CommentThread? {
            if (oid == null || oid <= 0 || root == null || root <= 0) return null
            return CommentThread(oid = oid, type = type, rootRpid = root, targetRpid = target ?: root)
        }
        val root = query["comment_root_id"]?.toLongOrNull()
        val secondary = query["comment_secondary_id"]?.toLongOrNull()

        if (scheme == "bilibili") {
            return when (host) {
                "comment" -> if (segments.firstOrNull() == "detail" || segments.firstOrNull() == "msg_fold") {
                    val rootId = segments.getOrNull(3)?.toLongOrNull()
                    thread(
                        oid = segments.getOrNull(2)?.toLongOrNull(),
                        type = segments.getOrNull(1)?.toIntOrNull() ?: return null,
                        root = rootId,
                        target = query["anchor"]?.toLongOrNull() ?: rootId,
                    )
                } else {
                    null
                }

                "video" -> thread(segments.firstOrNull()?.toLongOrNull(), VIDEO_COMMENT_TYPE, root, secondary)

                "following", "opus" -> if (segments.firstOrNull() == "detail") {
                    val dynamicId = segments.getOrNull(1)?.toLongOrNull()
                    thread(
                        oid = subjectId.takeIf { it > 0 } ?: dynamicId,
                        type = businessId.takeIf { it > 0 } ?: DYNAMIC_COMMENT_TYPE,
                        root = root,
                        target = secondary,
                    )
                } else {
                    null
                }

                else -> null
            }
        }
        if (host != "bilibili.com" || root == null) return null
        val videoIndex = segments.indexOf("video")
        val id = segments.getOrNull(videoIndex + 1)?.takeIf { videoIndex >= 0 } ?: return null
        val aid = when {
            id.startsWith("BV") -> runCatching { BvidCodec.toAid(id) }.getOrNull()
            id.startsWith("av", ignoreCase = true) -> id.drop(2).toLongOrNull()
            else -> null
        }
        return thread(aid, VIDEO_COMMENT_TYPE, root, secondary)
    }

    private fun queryOf(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            pair.substring(0, eq) to runCatching {
                java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }.getOrDefault(pair.substring(eq + 1))
        }.toMap()

    /** 动态的评论区类型。PiliPlus 在链接只给了动态 id 时同样按 17 处理(`app_scheme.dart` 的 following)。 */
    private const val DYNAMIC_COMMENT_TYPE = 17

    private fun hostOf(url: String): String? = runCatching {
        java.net.URI(url.trim()).host?.lowercase()?.removePrefix("www.")
            ?.let { if (it.startsWith("m.bilibili")) it.removePrefix("m.") else it }
    }.getOrNull()

    private fun pathOf(url: String): String =
        runCatching { java.net.URI(url.trim()).path.orEmpty() }.getOrDefault("")

    /** 链接里合法的字符按 RFC 3986 取,末尾的中文标点不吃进来。 */
    private val URL_PATTERN = Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""")
}

