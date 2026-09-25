package dev.bilby.ui

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.bilby.BiliLog
import dev.bilby.R

/**
 * 把一条内容分享出去。**给的是标准的 web 地址,不是短链** —— 短链要请求一次 B 站的接口才
 * 换得到,而收链接的那一方(可能是记事本,可能是另一台设备)拿到的应该是一条自己就能看懂、
 * 十年后也还能解析的地址。
 *
 * 标题和链接之间只放一个空格,不加"复制这段内容打开…"那种话术:那句话是给一个需要抢跳转的
 * 客户端用的,而我们不抢。
 */
object ShareLink {

    fun video(context: Context, bvid: String, title: String) =
        share(context, title, "https://www.bilibili.com/video/$bvid")

    fun liveRoom(context: Context, roomId: Long, title: String) =
        share(context, title, "https://live.bilibili.com/$roomId")

    fun space(context: Context, mid: Long, name: String) =
        share(context, name, "https://space.bilibili.com/$mid")

    private fun share(context: Context, title: String, url: String) {
        val text = if (title.isBlank()) url else "$title $url"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            // 有些接收方(邮件、笔记)会拿它当标题,只给正文的话那边是一封无主题的信。
            putExtra(Intent.EXTRA_SUBJECT, title.ifBlank { url })
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.action_share))
        runCatching { context.startActivity(chooser) }
            .onFailure { BiliLog.w("拉起分享失败", it) }
    }

    /** 在浏览器里打开。分享面板里没有合适的目标时,用户至少还有这条路。 */
    fun openInBrowser(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            .onFailure { BiliLog.w("打开浏览器失败", it) }
    }
}
