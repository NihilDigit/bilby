package dev.bilby.player

import dev.bilby.data.LastPlayed
import kotlin.math.min

/**
 * 这次从哪儿接着播。
 *
 * 这里只有算术,没有 IO 也没有 Android —— 续播是这块最容易出静默错误的地方(位置差一个数量级
 * 会表现成"视频播不动",单位错会表现成"每次都从头开始"),所以判据集中在一处并单独可测。
 *
 * 进度有两个来源,**由媒体的来源决定用哪一个**:放网络流用服务端的 `last_play_time`,放本地
 * 副本用 [dev.bilby.offline.OfflineItem] 里记的那份。两者不混,PiliPlus 也是这个分工
 * (`pages/video/controller.dart` 里本地那份只在 `isFileSource` 时读写)。唯一相遇的地方是
 * [mergeCachedProgress],见那里的说明。
 */

/**
 * 剩下多少就算看完了。
 *
 * `min(10 秒, 5% 时长)`:长视频取 10 秒的绝对值,短视频按比例收窄 —— 一条 30 秒的视频若也留
 * 10 秒,那是三分之一的内容被判成片尾。200 秒是两者的交点。
 *
 * **这是注意力尺度,不是数据精度尺度。** 曾经这里是一个 2000 毫秒的常数,名字叫"进度条判定为
 * 满的容差",本意是吸收服务端整秒取值与两个时长来源之间的一秒误差。但它实际挡的是另一件事,
 * 而按精度取的值对那件事太小了:服务端如实记着 600 秒视频的第 597 秒(完播上报被中断一次就会
 * 停在这种位置),597000 >= 598000 不成立,于是闸放行、seek 到 597 秒、播 3 秒、队列进下一条。
 * 表现是"每次打开都直接跳下一个",而它其实把这条剩下的 3 秒老老实实播完了。
 */
fun finishedThresholdMillis(durationMillis: Long): Long =
    min(FINISHED_THRESHOLD_CAP_MILLIS, durationMillis * FINISHED_THRESHOLD_PERCENT / 100)

/**
 * [positionMillis] 这个位置算不算已经看完。
 *
 * 时长未知(两个来源都缺)时一律不算 —— 没有分母就没有"还剩多少"这个问题,那时宁可续播:
 * 从头开始是可感知的损失,而多播一段片尾不是。
 */
fun isWatchedToEnd(positionMillis: Long, durationMillis: Long): Boolean =
    durationMillis > 0 && positionMillis >= durationMillis - finishedThresholdMillis(durationMillis)

/**
 * 起播位置。看完了就从 0 开始,否则就是记下的那个位置。
 *
 * **"从 0 起播"和"已看完"是两件事,不互相推翻。** 一条视频完全可以在列表里显示已看完、下次
 * 又从头播:前者说的是它被看过,后者说的是这次从哪儿开始。把两者绑在一起会逼出一个"看完之后
 * 要不要清掉记录"的问题,而那个问题没有答案。
 */
fun resumePositionMillis(positionMillis: Long, durationMillis: Long): Long =
    if (positionMillis <= 0 || isWatchedToEnd(positionMillis, durationMillis)) 0 else positionMillis

/**
 * 本地那份进度和服务端那份冲突时听谁的。
 *
 * 只有放本地副本时才会问到这里。放网络流时服务端是唯一来源,本地那份根本不参与。
 *
 * [base] 是写下 [localMillis] 那一刻服务端的值。于是:
 * - `server != base` —— 服务端在这期间自己动过,也就是这条视频在别处(网页、官方 app)被看过。
 *   那边比本机新,丢掉本地这份。
 * - `server == base` —— 服务端没动过,本机这份是唯一动过的,它说了算。
 *
 * 不取两者较大者:在网页上从头重看会让服务端退回 0,取较大者会把人摁回本地那个旧位置,而且
 * 越是"重看"这个意图明确的场景越错。
 *
 * 联网时心跳照常上报,上报成功之后 [base] 跟着推进到刚报上去的值(见 [dev.bilby.data.HeartbeatReporter]),
 * 本地这份就不再有话语权。所以两者真正分叉的窗口只有"断网看了缓存"到"下次上报成功"之间。
 */
fun mergeCachedProgress(localMillis: Long, base: Long, serverMillis: Long): Long =
    if (serverMillis != base) serverMillis else localMillis

/**
 * 云端记着的那一对是不是**别处**写的。返回别处那个位置,不是的话为 null。
 *
 * 用在「页面重开」那条路径上:播放器常驻,同一条视频再打开一次不触发装载,一次解析都不发生,
 * 于是别处(网页、官方 app)在这期间写下的进度问不到。这个函数回答的就是"刚问回来的这一对,
 * 是我们自己写的,还是别人写的"。
 *
 * **基线是我们自己报上去的那一对,绝不能拿播放器当前位置去比。** 心跳按位置节流(≥5 秒),
 * 当前位置几乎总是比云端新几秒——拿它比对,每次重开页面都会把自己的节流延迟认成"别处看过"。
 * 离开播放页时的 [ProgressSession.flush] 把云端钉在了一个我们知道的值上,这个等值判断因此
 * 立得住;那一条补发是这里的前提,删掉它就得换判据。
 *
 * 正在播放时云端本来就是自己刚报的,这个函数自然返回 null,不需要为"在播/没在播"写分支。
 *
 * 四种为 null 的情形各自的理由:
 * - [server] 为 null:这次没问到(离线、限流、接口出错)。和"服务端说没有记录"不是一回事,
 *   见 [dev.bilby.data.SubtitleRepository.lastPlayed]。
 * - 服务端没有记录(cid 为 0 或秒数不是正数):没有可跳的地方。**完播之后落在这一支**——
 *   服务端对看完的稿件记的是 -1(历史列表里就是这个值,见 [dev.bilby.data.HistoryItem.isFinished]),
 *   而 `last_play_time` 的负数在解析时已经被夹到 0。
 * - [ours] 为 null:这次会话一次都没报过,没有可比的基线。此时云端那一对多半就是装载时用来
 *   起播的那个,拿它弹一条提示等于把用户已经在的位置再提议一遍。
 * - [ours] 的最后一次是完播:服务端此时存的是什么没有实测过(notes/playurl.md §8.1.2),
 *   拿一个没验证的取值去判"别处",错的方向是凭空弹提示。宁可这一支不提示。
 */
fun cloudProgressWrittenElsewhere(server: LastPlayed?, ours: ReportedProgress?): Long? {
    if (server == null) return null
    if (server.cid == 0L || server.positionMillis <= 0) return null
    if (ours == null || ours.completed) return null
    if (server.cid != ours.cid) return server.positionMillis
    val serverSeconds = server.positionMillis / 1000
    if (serverSeconds == ours.sentSeconds || serverSeconds == ours.confirmedSeconds) return null
    return server.positionMillis
}

/** 见 [finishedThresholdMillis]。 */
private const val FINISHED_THRESHOLD_CAP_MILLIS = 10_000L
private const val FINISHED_THRESHOLD_PERCENT = 5L
