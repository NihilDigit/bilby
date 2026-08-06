package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.dto.ActionEnvelopeDto
import io.ktor.client.call.body

/**
 * 播放进度心跳上报,`POST x/click-interface/web/heartbeat`(notes/playurl.md §8.1)。
 * 定时/节流策略由调用方(ViewModel)负责,这里只管把一次心跳发出去。
 *
 * notes §8.1 记录的是 PiliPlus 实际发送的参数(bvid/aid/cid/epid/sid/type/sub_type/
 * played_time/csrf),没有覆盖 aid 现在函数签名要求的 real_time/start_ts/video_duration
 * 这三个字段——它们不在 PiliPlus 代码读证范围内,是 bilibili-API-collect 公开文档记录的
 * 心跳接口扩展字段,UNSURE: 具体语义文档本身也标注不确定(社区文档原话是"不知道有什么
 * 用"),这里原样带上,字段名采用文档里的 real_time/start_ts/video_duration。
 *
 * 这里只做 UGC(type=3,notes §7),不支持 PGC/PUGV——和 VideoRepository 现有范围一致,
 * 真要接番剧再加 epid/sid/type 参数。
 */
class HeartbeatReporter(private val client: BiliClient) {

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
        // notes §8.1 的前置条件之一是 progress != 0,这里原样搬过来当作发送前的判断,
        // 不是节流(节流由调用方做);progressSeconds 本身不作为字段发给服务端,笔记里
        // "played_time" 对应的是 playedTimeSeconds(见下)。
        if (progressSeconds == 0L) return
        runCatching {
            val form = mapOf(
                "aid" to aid.toString(),
                "cid" to cid.toString(),
                "type" to "3",
                // 完播时 played_time 特殊值 -1(notes §8.1),否则原样发送当前进度。
                "played_time" to if (isFinished) "-1" else playedTimeSeconds.toString(),
                "real_time" to realtimeSeconds.toString(),
                "start_ts" to startTs.toString(),
                "video_duration" to videoDurationSeconds.toString(),
            )
            val envelope = client.rawPostForm(HEARTBEAT_URL, form).body<ActionEnvelopeDto>()
            if (envelope.code != 0) {
                BiliLog.w("心跳上报失败(${envelope.code}): ${envelope.message}")
            }
        }.onFailure {
            // 心跳失败绝不能打断播放,吞掉,留一行日志方便排查。
            BiliLog.w("心跳上报异常", it)
        }
    }

    private companion object {
        const val HEARTBEAT_URL = "${BiliConstants.WEB_HOST}/x/click-interface/web/heartbeat"
    }
}
