package dev.danmaku.compose

/**
 * 帧循环的更新上限。跳帧按时间阈值判断("攒够 1/fps 才推进"),不按"隔 N 帧" —— 后者的
 * 输出会随面板刷新率漂移(120Hz 屏上限出 60 会变成隔 1 帧,144Hz 上同样的隔 1 帧却变成
 * 出 72)。[UNLIMITED] 是阈值 0,跟 30/60 走同一条判断代码路径,不是特例分支。
 */
enum class DanmakuFrameRateCap(internal val minFrameIntervalNanos: Long) {
    FPS_30(1_000_000_000L / 30),
    FPS_60(1_000_000_000L / 60),
    UNLIMITED(0L),
}
