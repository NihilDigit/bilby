package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.postAction

/**
 * 播放进度心跳上报,`POST x/click-interface/web/heartbeat`。
 * 定时/节流策略由调用方(ViewModel)负责,这里只管把一次心跳发出去。
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
class HeartbeatReporter(private val client: BiliClient) {

    /**
     * [realtimeSeconds]、[startTs]、[videoDurationSeconds] 已不再进请求体,签名暂时保留:
     * 唯一的调用方 `ui/video/VideoViewModel` 属于另一位负责人,本轮不动 ui/。那边清理完
     * 之后,这三个参数连同 [progressSeconds](它与 [playedTimeSeconds] 一直是同一个值)
     * 应当一并删掉,只留 aid/cid/playedTime/isFinished。
     *
     * aid 保留不动:PiliPlus 的 UGC 心跳发的是 bvid,但接口本身 aid/bvid 二选一,这一处
     * 不构成行为差异,不值得为它改调用方签名。
     */
    suspend fun report(
        aid: Long,
        cid: Long,
        progressSeconds: Long,
        playedTimeSeconds: Long,
        realtimeSeconds: Long,
        startTs: Long,
        videoDurationSeconds: Long,
        isFinished: Boolean,
    ) {
        if (progressSeconds == 0L) return
        val form = mapOf(
            "aid" to aid.toString(),
            "cid" to cid.toString(),
            "type" to "3",
            "played_time" to if (isFinished) "-1" else playedTimeSeconds.toString(),
        )
        // 失败已由 postAction 记过一行日志(路径 + code + message),这里返回值丢弃即可:
        // 心跳失败绝不能打断播放。
        client.postAction(HEARTBEAT_URL, form)
    }

    private companion object {
        const val HEARTBEAT_URL = "${BiliConstants.WEB_HOST}/x/click-interface/web/heartbeat"
    }
}
