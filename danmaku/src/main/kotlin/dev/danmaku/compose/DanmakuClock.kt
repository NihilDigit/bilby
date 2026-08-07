package dev.danmaku.compose

/**
 * host 认识的唯一时钟抽象。不认识 Media3、ExoPlayer、Mediamp,或任何其他具体播放器类型 ——
 * 接入哪个播放器是宿主 app 的事,这个接口是 host 与播放器之间唯一的耦合点。
 *
 * [positionMillis] 通常是粗粒度的来源(轮询/心跳更新;Bilby 现在是 500ms 一次),host 在两次
 * 更新之间按 [playbackSpeed] 自己插值到帧级,不需要、也不应该,调用方自己去做逐帧位置估算。
 *
 * 倍速跟着播放时钟走(2 倍速时弹幕也 2 倍速滚),排布本身跟倍速无关 —— 暂停/seek/变速的
 * 同步因此是免费的,host 不需要为这几种状态切换单独写分支。
 *
 * 三个属性都是轮询读取的普通值,不要求是 Compose `State`。host 的帧循环在没有可见弹幕时会
 * 挂起(见 [DanmakuHostState]),这期间如果 [isPlaying] 或播放位置发生了外部变化(恢复播放、
 * seek),调用方需要显式调 [DanmakuHostState.notifyChanged] 才能让 host 立刻醒来 —— 不调用
 * 也不会永远卡住,host 有一个兜底轮询间隔,只是响应会慢那一个间隔。
 */
interface DanmakuClock {
    val positionMillis: Long
    val isPlaying: Boolean
    val playbackSpeed: Float
}
