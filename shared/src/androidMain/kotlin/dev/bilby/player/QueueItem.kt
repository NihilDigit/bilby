package dev.bilby.player

/**
 * 队列里的一条。
 *
 * **队列本身住在 ExoPlayer 的 playlist 里**,这个类型只是它的读写形态:进去时由
 * [toMediaItem] 变成条目,出来时由 [toQueueItem] 变回来。服务不另存一份列表 —— 两份列表意味着
 * "队列现在是什么"有两个答案,而它们只在没人动过队列时相等。
 *
 * **身份只有 bvid,这里没有 cid**(设计文档「决定 1」)。放到哪一 P 是播放层在装载时解析出来的
 * 内部状态(见 [LoadResolver]),写进队列项就成了第二份真相:队列在收到命令的那一刻就指向新的
 * 一条,而装载还要几百毫秒,两者在这段窗口里说的不是同一件事——切 P 时发出去的心跳因此带过
 * "新 cid 配旧位置"。多 P 也不是队列项:分 P 是这条视频内部的结构,切 P 不动队列位置。
 */
data class QueueItem(
    val bvid: String,
    val title: String,
    val upName: String,
    val coverUrl: String,
    val durationSeconds: Long,
)
