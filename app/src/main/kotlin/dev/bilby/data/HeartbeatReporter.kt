package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.postAction
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
) {

    /**
     * 发一次心跳。**不是挂起函数**:调用点通常正在被销毁(见 [scope] 的说明),
     * 让它去持有一个协程正是那个坑。
     *
     * aid 保留不动:PiliPlus 的 UGC 心跳发的是 bvid,但接口本身 aid/bvid 二选一。播放服务
     * 手上只有 bvid,那边用 [dev.bilby.BvidCodec] 换算,一次都不必联网 —— 不值得为一个不
     * 构成行为差异的字段去改一条已经验证过的写请求。
     */
    fun report(
        aid: Long,
        cid: Long,
        playedTimeSeconds: Long,
        isFinished: Boolean,
        onReported: suspend (reportedMillis: Long) -> Unit = {},
    ) {
        if (playedTimeSeconds == 0L) return
        val form = mapOf(
            "aid" to aid.toString(),
            "cid" to cid.toString(),
            "type" to "3",
            "played_time" to if (isFinished) "-1" else playedTimeSeconds.toString(),
        )
        // 失败已由 postAction 记过一行日志(路径 + code + message),这里返回值丢弃即可:
        // 心跳失败绝不能打断播放。
        scope.launch {
            if (client.postAction(HEARTBEAT_URL, form) is BiliResult.Ok) {
                // 报成功了,服务端存的就是这个数。缓存内容据此推进 base ——
                // 不推的话下次打开会把我们自己刚报上去的进度当成"别处看过的",
                // 每次都弹一条其实来自本机的提示(见 dev.bilby.player.mergeCachedProgress)。
                //
                // 完播报的是 -1,服务端那边归零,所以这里也报 0。
                onReported(if (isFinished) 0L else playedTimeSeconds * 1000)
            }
        }
    }

    private companion object {
        const val HEARTBEAT_URL = "${BiliConstants.WEB_HOST}/x/click-interface/web/heartbeat"
    }
}
