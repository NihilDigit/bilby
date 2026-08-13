package dev.bilby.player

/**
 * 队列里的一条。
 *
 * **队列本身住在 ExoPlayer 的 playlist 里**,这个类型只是它的读写形态:进去时由
 * [toMediaItem] 变成条目,出来时由 [toQueueItem] 变回来。服务不另存一份列表 —— 两份列表意味着
 * "队列现在是什么"有两个答案,而它们只在没人动过队列时相等。
 *
 * **cid 可能是 0**:空间投稿列表本身不返回 cid,来自那条路径的队列项只能占位。约定由
 * [AudioPlaybackService.resolveStream] 在真正切到这一条时用 `getVideoDetail(bvid)` 补上 ——
 * **拿着 0 去 getPlayUrl 会被服务端当成无效 cid 拒绝**。
 */
data class QueueItem(
    val bvid: String,
    val cid: Long,
    val title: String,
    val upName: String,
    val coverUrl: String,
    val durationSeconds: Long,
)
