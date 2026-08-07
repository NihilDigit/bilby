package dev.danmaku.compose

/**
 * host 认识的唯一时钟抽象。不认识 Media3、ExoPlayer、Mediamp,或任何其他具体播放器类型 ——
 * 接入哪个播放器是宿主 app 的事,这个接口是 host 与播放器之间唯一的耦合点。
 *
 * [positionMillis] 每次调用都会被 host 的帧循环重新读取(见 [DanmakuHostState.run]),host
 * 自己不做任何插值/外推——**调用方要负责这个读数在播放中是逐帧连续、不是只在心跳/轮询间隔
 * 才更新的粗粒度值**。这不是可以随意选的实现细节:host 曾经内置过一层"锚点位置 + 经过时间 ×
 * 倍速"的外推,理由是"实现方可能只做粗粒度轮询";后来发现 Media3 的 `MediaController
 * .getCurrentPosition()` 本身就是这个模型(每次调用现算,连续),再叠一层等于两个各自外推的
 * 估计器同时存在——倍速变化时两边对"新倍速几时生效"的认知有短暂分歧,表现为位置抖一下。
 * 结论是**外推只能有一层**,而且必须放在离真实播放状态最近的那一层——也就是 [positionMillis]
 * 的实现本身,不是 host。如果接入的播放器只能提供真正粗粒度的读数(比如确实是定时轮询、
 * 调用之间返回同一个值不变),外推要在这个属性的实现里做,不要指望 host replay 一遍。
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
