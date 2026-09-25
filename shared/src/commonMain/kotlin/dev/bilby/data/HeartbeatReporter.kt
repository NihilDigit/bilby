package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.postAction
import dev.bilby.offline.OfflineStore
import dev.bilby.offline.PendingHeartbeat
import dev.bilby.offline.PendingHeartbeatStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 播放进度心跳上报,`POST x/click-interface/web/heartbeat`。
 * 触发与节流归 [dev.bilby.player.ProgressSession],这里只管把一次心跳发出去。
 *
 * 请求体对齐 PiliPlus `lib/http/video.dart:679-704`,它只发五个字段:
 * 稿件 id、`cid`、`type`、`played_time`、`csrf`。
 *
 * 原先额外带的 `real_time` / `start_ts` / `video_duration` 已经去掉。那三个字段来自
 * bilibili-API-collect,而社区文档对它们的原话就是"不知道有什么用";PiliPlus 这个接口
 * 是有实现的,按 DESIGN 8 节的规矩,有实现的地方以它为准、文档只在它没实现时补充。
 * 其中 `start_ts` 尤其不该猜着发:我们填的是 ViewModel 实例创建时刻,一个含义不明、
 * 取值还是我们自己编的字段,发错比不发更糟。
 *
 * 完播上报 `played_time = -1` 与 PiliPlus 一致(`controller.dart:958` 直接传 -1);
 * `progress == 0` 时整条不发,同样照抄(`controller.dart:1476`)。
 *
 * 这里只做 UGC(type=3),不支持 PGC/PUGV——和 VideoRepository 现有范围一致,
 * 真要接番剧再加 epid/sid/sub_type 参数。
 *
 * **没发出去的记下来,联网后补发。** 断网看缓存正是这个 app 离线功能的用途,而那段时间的每一次
 * 心跳都会失败;不记下来的话,服务端只知道下载那一刻的进度,别的设备上续播落回那里。补发用的
 * 是同一个请求体,PiliPlus 没有这一层(它断网时的心跳就丢了),接口上的事实见 notes/playurl.md
 * §8.1.3。
 */
class HeartbeatReporter(
    private val client: BiliClient,
    /**
     * **应用级 scope,不是调用方的。**
     *
     * 最要紧的那一次心跳发生在这次观看结束的瞬间(会话 close 的定格上报):它带的是最终位置,
     * 而云端那份是续播的唯一来源。原先它跑在 `viewModelScope` 上 —— 页面出栈时 NavEntry 的
     * ViewModelStore 正在清,scope 当场取消,请求还没发出去就没了,日志里只留一行
     * `JobCancellationException`。真机上抓到过。
     *
     * 驱动搬进播放服务之后这条仍然成立:服务停止时它自己的 scope 也在被取消,而定格上报
     * 恰恰发生在那一刻。
     *
     * 归到这里而不是让调用方换个 scope:调用方有几个、将来还会有几个,而"这个请求必须活过
     * 发起它的那个页面"是心跳自己的性质。
     */
    private val scope: CoroutineScope,
    /** 报上去的进度要落到对应的缓存条目上,见 [report]。条目不在盘上时它什么都不做。 */
    private val offlineStore: OfflineStore,
    private val pending: PendingHeartbeatStore,
    /** 排一次补发。实现是一个要求联网的 WorkManager 任务,见 [dev.bilby.offline.HeartbeatFlushWorker]。 */
    private val scheduleFlush: () -> Unit,
) {

    init {
        // 上次进程里记下、还没补发完的那些。WorkManager 自己会把排过的任务带过进程重启,这一句
        // 兜的是"记下了、还没来得及排"那一瞬被杀的情形。
        scope.launch { if (pending.snapshot().isNotEmpty()) scheduleFlush() }
    }

    /**
     * 发一次心跳。**不是挂起函数**:调用点通常正在被销毁(见 [scope] 的说明),
     * 让它去持有一个协程正是那个坑。
     *
     * aid 保留不动:PiliPlus 的 UGC 心跳发的是 bvid,但接口本身 aid/bvid 二选一。播放服务
     * 手上只有 bvid,那边用 [dev.bilby.BvidCodec] 换算,一次都不必联网 —— 不值得为一个不
     * 构成行为差异的字段去改一条已经验证过的写请求。[bvid] 不进请求体,只用来找缓存条目。
     *
     * 三种结果三种去向:
     * - 成功:服务端存的就是这个数,缓存条目的本地进度与 base 一起推到这里(见
     *   [OfflineStore.recordReported]),同稿件更早的待补发记录作废。
     * - 没发出去(断网、超时):记进待补发表,排一次补发;本地进度先写上,进程随后被杀也不回退。
     * - 服务端拒绝(未登录之类):不记。同一个请求补发多少次都是同一个回答。
     */
    fun report(
        aid: Long,
        cid: Long,
        bvid: String,
        playedTimeSeconds: Long,
        isFinished: Boolean,
        onConfirmed: suspend () -> Unit = {},
    ) {
        if (playedTimeSeconds == 0L) return
        val sentAtMillis = System.currentTimeMillis()
        // 完播报的是 -1,服务端那边归零,所以本地也记 0。
        val reportedMillis = if (isFinished) 0L else playedTimeSeconds * 1000
        scope.launch {
            // 失败已由 postAction 记过一行日志(路径 + code + message)。心跳失败绝不能打断播放。
            when (client.postAction(HEARTBEAT_URL, formOf(aid, cid, playedTimeSeconds, isFinished))) {
                is BiliResult.Ok -> {
                    pending.removeOlder(aid, sentAtMillis)
                    // 不推 base 的话,下次打开会把我们自己刚报上去的进度当成"别处看过的",
                    // 每次都弹一条其实来自本机的提示(见 dev.bilby.player.mergeCachedProgress)。
                    offlineStore.recordReported(bvid, cid, reportedMillis)
                    onConfirmed()
                }
                is BiliResult.Failure -> {
                    pending.put(
                        PendingHeartbeat(aid, cid, bvid, playedTimeSeconds, isFinished, sentAtMillis),
                    )
                    // 只写本地进度、不动 base:服务端还不知道这个数。补发成功时两者再一起推。
                    offlineStore.recordProgress(bvid, cid, reportedMillis)
                    scheduleFlush()
                }
                is BiliResult.ApiError -> Unit
            }
        }
    }

    /**
     * 把待补发的心跳按记下的先后发出去。返回 false 表示还有没发成的,调用方(WorkManager)
     * 据此退避重试。
     *
     * 先后要按记录时刻排:服务端整个稿件只存一对 (cid, 秒数),同一稿件两个分 P 各有一条待补发
     * 时,最后发的那条就是最后留下的那条,它应当是用户最后看的那一 P。
     *
     * 服务端的 view_at 会是补发这一刻而不是观看那一刻:接口没有可以指定时刻的字段,owner 接受
     * 这个偏差。
     */
    suspend fun flushPending(): Boolean {
        for (entry in pending.snapshot().sortedBy { it.queuedAtMillis }) {
            val form = formOf(entry.aid, entry.cid, entry.playedTimeSeconds, entry.finished)
            when (val result = client.postAction(HEARTBEAT_URL, form)) {
                is BiliResult.Ok -> {
                    pending.remove(entry)
                    offlineStore.recordReported(
                        entry.bvid,
                        entry.cid,
                        if (entry.finished) 0L else entry.playedTimeSeconds * 1000,
                    )
                }
                // 被拒绝的补发多少次都一样(最常见的是期间退出了登录),丢掉,不然这张表永远清不空。
                is BiliResult.ApiError -> {
                    BiliLog.w("补发心跳被拒,丢弃 aid=${entry.aid} cid=${entry.cid} code=${result.code}")
                    pending.remove(entry)
                }
                is BiliResult.Failure -> return false
            }
        }
        // 补发途中又记下的新条目(还在断断续续的网络上看)留给下一轮。
        return pending.snapshot().isEmpty()
    }

    private fun formOf(aid: Long, cid: Long, playedTimeSeconds: Long, isFinished: Boolean) = mapOf(
        "aid" to aid.toString(),
        "cid" to cid.toString(),
        "type" to "3",
        "played_time" to if (isFinished) "-1" else playedTimeSeconds.toString(),
    )

    private companion object {
        const val HEARTBEAT_URL = "${BiliConstants.WEB_HOST}/x/click-interface/web/heartbeat"
    }
}
