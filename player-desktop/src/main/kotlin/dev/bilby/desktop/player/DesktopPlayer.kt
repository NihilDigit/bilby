package dev.bilby.desktop.player

import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import kotlin.coroutines.CoroutineContext

/**
 * 桌面端的播放器:mediamp 的 mpv 后端,外加一条直达 mpv 的通道 [mpv]。
 *
 * mediamp 的公开 API 覆盖不到的都走 [mpv]:缓冲参数、听视频时关掉视频解码、逐帧读位置。
 * 它的 `handle` 是 internal,但构造参数 `configureOptions` 会在初始化前同步交出同一个
 * [MPVHandle] 实例,在那里留下引用即可,不用反射。
 */
class DesktopPlayer(parentCoroutineContext: CoroutineContext) : AutoCloseable {
    val mpv: MPVHandle

    val mediamp: MpvMediampPlayer

    init {
        var captured: MPVHandle? = null
        // 桌面端的 context 参数不被使用(Android 端才是 Context)
        mediamp = MpvMediampPlayer(
            context = Unit,
            parentCoroutineContext = parentCoroutineContext,
            configureOptions = { captured = it },
        )
        mpv = checkNotNull(captured) { "mediamp did not hand out its MPVHandle during construction" }
    }

    suspend fun open(source: StreamSource, playWhenReady: Boolean = true, startPositionMillis: Long = 0L) {
        mediamp.setMediaData(source.toMediaData(), playWhenReady, startPositionMillis)
    }

    /**
     * 当前播放位置,每次调用现问 mpv,不经过 mediamp 的状态流。那条流由 `time-pos` 属性事件
     * 驱动,更新间隔不固定;弹幕的横坐标是位置的直接函数,要的是逐帧连续的读数。
     */
    val positionMillis: Long
        get() = (mpv.getPropertyDouble("time-pos") * 1000).toLong().coerceAtLeast(0L)

    val speed: Float get() = mpv.getPropertyDouble("speed").toFloat()

    override fun close() = mediamp.close()
}
