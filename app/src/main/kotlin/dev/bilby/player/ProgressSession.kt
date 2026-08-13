package dev.bilby.player

/**
 * 一次装载的全部进度上报(设计文档「决定 3」)。
 *
 * 身份 `(aid, cid)` 在创建时冻结,之后不可变。这是它存在的第一个理由:上报的 cid 曾经是每次
 * 现取的,而"现在放的是哪一 P"在切 P 的那几百毫秒里有两个答案,发出去的心跳因此带过"新 cid
 * 配旧位置"——服务端整条视频只存一对 `(cid, 秒数)`(notes/playurl.md §8.2.1),写错一次就是把
 * 用户上一 P 的进度抹了。会话把身份和位置绑在同一个对象上之后,这种组合没有地方产生。
 *
 * 第二个理由是收尾。任何退出路径(装载新内容、连播切条、服务停止、出错清理)只管调
 * [close],不必判断自己是不是第一个到的,也不必知道别人有没有调过——[close] 幂等,第一次
 * 发定格上报然后置死,再调是空操作。
 *
 * **位置由调用方给,会话不去问播放器。** 连播切条时播放器的 `currentPosition` 已经属于下一条,
 * 那一刻现取到的是新条目的 0 秒。权威的最终位置在 `onPositionDiscontinuity` 的 `oldPosition`
 * 里;拿不到时用 [lastObservedMillis] 兜底,它由每一次触发顺手更新。
 *
 * 节流基准是**位置**不是墙上时钟(PiliPlus `pl_player/controller.dart:1495` 的
 * `progress - _heartDuration >= 5` 同此)。倍速播放下按位置算才是"进度前进了多少",而 seek
 * 之后把基准重置到落点,紧跟的周期心跳就不会把同一个数再报一遍。
 *
 * 不依赖 Android,上报出口是一个 lambda:协议正确性(幂等、冻结、各触发的节流)不需要网络
 * 也不需要播放器才能验。
 */
class ProgressSession(
    val aid: Long,
    val cid: Long,
    /** [finished] 为真时发 `played_time=-1`,见 [dev.bilby.data.HeartbeatReporter]。 */
    private val report: (playedTimeSeconds: Long, finished: Boolean) -> Unit,
) {

    /** 最后一次看到的位置。[close] 拿不到定格位置时用它。 */
    private var lastObservedMillis = 0L

    /** 最后一次看到的时长。完播判定要它,而时长要等时间线到了才知道。 */
    private var durationMillis = 0L

    /** 上一次真的发出去的那个位置(秒)。节流基准。 */
    private var reportedSeconds = 0L

    private var closed = false

    /**
     * 位置刻度。位置比上次上报前进了 [HEARTBEAT_INTERVAL_SECONDS] 秒才发。
     *
     * 刻度本身比这密得多(服务每半秒发一次),因为同一条刻度还喂着弹幕时钟。
     */
    fun onPosition(positionMillis: Long, durationMillis: Long) {
        if (closed) return
        observe(positionMillis, durationMillis)
        sendIfAdvanced(positionMillis, HEARTBEAT_INTERVAL_SECONDS)
    }

    /**
     * 暂停之后又放起来了。阈值降到 [RESUME_INTERVAL_SECONDS]:恢复播放时补一次,让服务端
     * 知道人还在这条上。
     *
     * **暂停本身不发。** 暂停发一条的话,"看了半分钟然后停在这里"和"看到这里就走了"在服务端
     * 是同一份记录,而后者恰恰是要靠 [close] 定格的那一次。
     */
    fun onResumed(positionMillis: Long, durationMillis: Long) {
        if (closed) return
        observe(positionMillis, durationMillis)
        sendIfAdvanced(positionMillis, RESUME_INTERVAL_SECONDS)
    }

    /**
     * seek 的落点已经确认。**立即发,不节流**,并把基准重置到落点。
     *
     * 这一条和 PiliPlus 相反(它 seek 不发,还把随后 5 秒的心跳一起压住):用户拖完进度条
     * 退出去,下次进来要落在拖到的地方,而不是拖之前那个。基准照样重置,免得紧跟的周期心跳
     * 把同一个数再报一遍。
     */
    fun onSeeked(positionMillis: Long, durationMillis: Long) {
        if (closed) return
        observe(positionMillis, durationMillis)
        send(positionMillis, finished = false)
    }

    /**
     * 补一条当前位置,**会话不关**。位置取自缓存的最后观察值,基准跟着重置到那里。
     *
     * 给"这次观看多半到此为止了"的那些时刻用,现在的调用点是离开播放页。常规心跳带着 5 秒
     * 节流,而暂停本身不发——"暂停,然后退出页面"这条最常见的路径上,云端停在最多 5 秒之前。
     * 那几秒平时无所谓,但离开页面正是下一次续播要对准的地方。
     *
     * **上报的所有权仍然在会话这边**,页面提供的只是时机:它给不出位置(播放器归服务),
     * 也覆盖不了听视频、切后台、进程被杀这三种它根本不在场的情形。这一点和 seek 落点那一条
     * 同构——外面说"该报了",报什么由会话决定。
     */
    fun flush() {
        if (closed) return
        send(lastObservedMillis, finished = false)
    }

    /** 这一条播完了(播放器停在片尾,或被"播完这条就停"拦住)。发 `played_time=-1`。 */
    fun onCompleted() {
        if (closed) return
        send(lastObservedMillis, finished = true)
    }

    /**
     * 定格补发最终位置,然后置死。
     *
     * 同稿件切 P 的这一条很快会被新 P 的心跳覆盖,无害;换稿件时它是旧稿件留下记录的唯一机会。
     * 两种情况同一条路径,不分支。
     *
     * @param atMillis 定格位置。为 null 时用最后观察到的位置——服务停止、出错清理这些路径上
     *   没有 transition 事件可以给出权威位置。
     * @param completed 连播切条(`DISCONTINUITY_REASON_AUTO_TRANSITION`)即完播。那一刻
     *   `oldPosition` 未必正好等于时长,而"播放器自己走到了下一条"这件事本身就是判据。
     */
    fun close(atMillis: Long? = null, completed: Boolean = false) {
        if (closed) return
        closed = true
        val position = atMillis ?: lastObservedMillis
        lastObservedMillis = position
        send(position, finished = completed)
    }

    private fun observe(positionMillis: Long, durationMillis: Long) {
        lastObservedMillis = positionMillis.coerceAtLeast(0)
        if (durationMillis > 0) this.durationMillis = durationMillis
    }

    private fun sendIfAdvanced(positionMillis: Long, thresholdSeconds: Long) {
        if (positionMillis / 1000 - reportedSeconds < thresholdSeconds) return
        send(positionMillis, finished = false)
    }

    /**
     * 位置 0 一律不发,和 PiliPlus 的 `progress != 0` 前置条件一致
     * (`pl_player/controller.dart:1476`)。起播那一刻的 0 报上去会把服务端记的位置抹掉,
     * 而这个 0 只表示"还没开始"。
     */
    private fun send(positionMillis: Long, finished: Boolean) {
        val seconds = positionMillis.coerceAtLeast(0) / 1000
        if (seconds == 0L) return
        reportedSeconds = seconds
        report(seconds, finished || isPlayedToEnd(positionMillis, durationMillis))
    }

    companion object {
        /** 常规心跳的间隔,按位置算。PiliPlus 同值。 */
        const val HEARTBEAT_INTERVAL_SECONDS = 5L

        /** 暂停恢复时的补发阈值,比常规心跳短。PiliPlus 同值。 */
        const val RESUME_INTERVAL_SECONDS = 2L

        /**
         * 距结尾多近算播完。
         *
         * **这是数据精度尺度,不是注意力尺度**,所以和 [finishedThresholdMillis] 的 10 秒/5%
         * 不是一回事,也不该合并:那一个回答"这条还用不用续播",一秒只够吸收整秒取值和两个
         * 时长来源之间的误差。PiliPlus 也是 1000ms(`pl_player/controller.dart:1504-1509`)。
         */
        const val COMPLETED_TOLERANCE_MILLIS = 1_000L
    }
}

/** 这个位置算不算把这一条播完了,见 [ProgressSession.COMPLETED_TOLERANCE_MILLIS]。 */
fun isPlayedToEnd(positionMillis: Long, durationMillis: Long): Boolean =
    durationMillis > 0 &&
        positionMillis >= durationMillis - ProgressSession.COMPLETED_TOLERANCE_MILLIS
