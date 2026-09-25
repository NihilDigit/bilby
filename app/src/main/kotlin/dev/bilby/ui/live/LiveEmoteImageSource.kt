package dev.bilby.ui.live

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.imageLoader
import coil3.request.allowHardware
import coil3.toBitmap
import dev.bilby.BiliLog
import dev.bilby.ui.components.biliImageRequestBuilder
import dev.nihildigit.danmaku.DanmakuImageSource

/**
 * 屏上弹幕里的表情图。键是图的地址。
 *
 * 弹幕库每录一条带图的弹幕就来问一次;没有就在这时发请求、先返回 null,库会留出空位,等图到了
 * 自己重录那一条。所以这里不需要预取,也不需要通知谁。
 *
 * 只在主线程上用:库在绘制时来问,Coil 的回调也在主线程。
 */
class LiveEmoteImageSource(private val context: Context) : DanmakuImageSource {

    private val images = LinkedHashMap<String, ImageBitmap>()
    private val loading = HashSet<String>()

    /** 取不到的不再重试。库对没到齐的条目每帧都会再问,失败的不记下来就是每帧一个请求。 */
    private val failed = HashSet<String>()

    override fun imageOrNull(key: String): ImageBitmap? {
        images.remove(key)?.let {
            // 重新放回尾部,让淘汰从最久没用的开始。
            images[key] = it
            return it
        }
        if (key !in loading && key !in failed) load(key)
        return null
    }

    private fun load(url: String) {
        loading += url
        val request = biliImageRequestBuilder(context, url)
            .size(DECODE_SIZE_PX)
            // 弹幕库在 CPU 上把图画进自己的位图图集,硬件位图画不进软件画布。
            .allowHardware(false)
            .target(
                onSuccess = { image ->
                    loading -= url
                    if (images.size >= MAX_IMAGES) images.remove(images.keys.first())
                    images[url] = image.toBitmap().asImageBitmap()
                },
                onError = {
                    loading -= url
                    failed += url
                    BiliLog.w("弹幕表情加载失败 path=${url.substringBefore('?')}")
                },
            )
            .build()
        context.imageLoader.enqueue(request)
    }

    private companion object {
        /** 屏上表情最大也就一行多高,全屏 18sp 在高密度屏上不到 100px,解码到这个边长足够。 */
        const val DECODE_SIZE_PX = 160

        /** 一个直播间常用的表情就几十种,留一些余量给房间表情。 */
        const val MAX_IMAGES = 256
    }
}
