package dev.bilby.player

import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import kotlin.coroutines.CoroutineContext

/**
 * 桌面端的播放器:mediamp 的 mpv 后端,外加一条直达 mpv 的通道 [mpv]。
 *
 * mediamp 的公开 API 覆盖不到的都走 [mpv]:倍速、听视频时关掉视频轨、逐帧读位置。
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
     * 当前播放位置,每次调用现问 mpv。只给偶尔一次的精确读数用(拖动起点、换画质时的续播点)。
     *
     * **不能逐帧调。** 读属性要拿 mpv 的核心锁,mpv 正在解码或渲染时就得等;弹幕时钟曾经每帧
     * 调它,UI 线程每帧卡在这把锁上,弹幕一开帧率从一百七十掉到十几,而 JFR 里 JVM 几乎空闲
     * (线程停在 native 调用里,不在执行采样里)。逐帧的读数用 [observedPositionMillis]。
     */
    val positionMillis: Long
        get() = (mpv.getPropertyDouble("time-pos") * 1000).toLong().coerceAtLeast(0L)

    /**
     * mediamp 由 `time-pos` 属性事件更新的位置,读它不经过 JNI。事件间隔不固定,逐帧的连续
     * 由弹幕那层的 SmoothedDanmakuClock 从读数本身估出速率来补。
     */
    val observedPositionMillis: Long
        get() = mediamp.currentPositionMillis.value.coerceAtLeast(0L)

    val speed: Float get() = mpv.getPropertyDouble("speed").toFloat()

    override fun close() = mediamp.close()
}
