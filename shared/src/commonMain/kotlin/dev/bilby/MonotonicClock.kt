package dev.bilby

/**
 * 单调时钟,毫秒,只用来做差。不会被对时和用户改时间挪动。
 *
 * Android 上是 `SystemClock.elapsedRealtime`,它把休眠的时间也算进去:定时关闭设的 30 分钟
 * 要按墙上经过的时间算。桌面用 `System.nanoTime`。播放位置的锚点([dev.bilby.player.PositionTick])
 * 也在这个时基上,读写两端必须用同一个函数。
 */
expect fun elapsedRealtimeMillis(): Long
