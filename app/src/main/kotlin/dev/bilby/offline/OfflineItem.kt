package dev.bilby.offline

import kotlinx.serialization.Serializable

/**
 * 一条缓存的状态。
 *
 * [Failed] 是**停下来等人决定**,不是"还会自己重试":重试的退避在下载器内部走完之后才落到
 * 这里,摆出来的时候已经不会再自己动了。和播放失败那条一样,跳过还是再试由用户决定。
 */
enum class OfflineStatus { Queued, Running, Completed, Failed }

/**
 * 缓存下来的计数。**是缓存那一刻的快照,不会更新。**
 *
 * 不给它加"截至某时"的角标:这个 app 里计数从来不是决策依据(DESIGN 的反算法立场),
 * 给一个永远滞后的数字加注解等于把它抬举成一个需要精确的东西。而且离线时赞/币/藏本来就是
 * 禁用的(互动状态查不到),用户拿它做不了任何决定。
 */
@Serializable
data class OfflineStat(
    val view: Long = 0,
    val danmaku: Long = 0,
    val reply: Long = 0,
    val favorite: Long = 0,
    val coin: Long = 0,
    val share: Long = 0,
    val like: Long = 0,
)

/**
 * 一条已缓存(或正在缓存)的分 P。
 *
 * **身份是 (bvid, cid),不是 bvid。** 多 P 视频的每一 P 各缓存各的,合集分集本来就是不同的
 * bvid。拿 bvid 当身份的话,缓存了第 3 P 再去缓存第 7 P 会被当成同一条而跳过。
 *
 * **元信息存的是"离线时把播放页撑起来"所需的一整份**,不只是列表要显示的那几样。判据是:
 * 少了它页面会**显示错的东西或点出错的行为**,而不是"信息少一点":
 * - [aid] 缺了,评论区会拿 `aid = 0` 去打接口(`state.detail?.aid` 在 0 时是非空的);
 * - [upMid] 缺了,点 UP 那一行会打开一个不存在的 `Space(0)`;
 * - [publishedAtEpochSeconds] 缺了,简介那行日期显示 1970-01-01 —— 那不是缺失,是错误。
 *
 * 评论不缓存(owner 定):它是随时在变的东西,存一份下来只会是一份过期的对话。
 */
@Serializable
data class OfflineItem(
    val bvid: String,
    val cid: Long,
    val title: String,
    /**
     * 多 P 时这一 P 的名字;单 P 为空。缓存列表里同一条视频的两个分 P 靠它区分 ——
     * 两行同名条目谁是谁,只有这个字段答得上来。
     */
    val partTitle: String = "",
    val upName: String = "",
    val coverUrl: String = "",
    val durationSeconds: Long = 0,
    /** 评论以它作 oid。为 0 表示这条是旧索引,调用方要据此不去建评论。 */
    val aid: Long = 0,
    val upMid: Long = 0,
    val upFaceUrl: String = "",
    val description: String = "",
    val publishedAtEpochSeconds: Long = 0,
    val stat: OfflineStat = OfflineStat(),
    /** 实际下到的清晰度,可能低于用户选的档(选流会降级),显示的是这个。 */
    val qualityId: Int = 0,
    val qualityLabel: String = "",
    val codec: String = "",
    val status: OfflineStatus = OfflineStatus.Queued,
    /** 音视频两条流合计。总数未知(服务端没给 Content-Length)时为 0。 */
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val hasDanmaku: Boolean = false,
    /** 失败原因,给界面看的一句话。成功时为 null。 */
    val error: String? = null,
    val createdAtMillis: Long = 0,
) {
    val id: String get() = offlineId(bvid, cid)

    /** 总数未知时返回 null —— 那种情况下进度条该画不确定态,不该画成 0%。 */
    val progress: Float?
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/**
 * 缓存目录名。bvid 是字母数字、cid 是数字,拼出来天然是合法文件名 —— **不要把标题拼进去**,
 * 标题里有 `/`、emoji,还会被 UP 改,改一次就再也对不上原来那个目录。
 */
fun offlineId(bvid: String, cid: Long): String = "${bvid}_$cid"

/**
 * 这一条有没有身份。**cid 为 0 表示还不知道要缓存哪一 P**(空间投稿来源的队列项不带 cid,
 * 要等一次视频详情才补得出来),那种条目只能在内存里当占位,不能落盘 —— 落了就会在盘上留下
 * 一个 `<bvid>_0` 目录,里面只有一份 349 字节的 meta.json、没有任何流,而列表里这条视频
 * 因此有两行:一行是真的,一行是 0 字节、永远 Queued、点开播不动的。真机上出过。
 */
val OfflineItem.hasIdentity: Boolean get() = cid != 0L

/**
 * 这条视频有没有能直接播的本地副本。
 *
 * **[preferredCid] 为 0 和非 0 是两种问法,不能混:**
 * - **非 0** = "我要的就是这一 P"(队列项带着 cid 进来)。只认精确匹配 —— 缓存了 P3 而用户
 *   点的是 P7 时给他 P3,比播不了更糟:画面在动,内容却是错的,而且没有任何提示。
 * - **0** = "这条视频随便哪一 P 都行"。从缓存列表点进来就是这种:导航目的地只带 bvid
 *   (见 `ui/Destinations.kt` 的 `Video`),cid 一路上没地方放。此时取最近缓存的那一条 ——
 *   用户刚存的多半就是他现在想看的。
 *
 * 只认 [OfflineStatus.Completed]:下到一半的那份文件是残的,喂给播放器只会在某个位置解码
 * 失败,而那个失败点离原因很远。
 */
fun List<OfflineItem>.pickCompletedFor(bvid: String, preferredCid: Long): OfflineItem? {
    val candidates = filter { it.bvid == bvid && it.status == OfflineStatus.Completed }
    if (preferredCid != 0L) return candidates.firstOrNull { it.cid == preferredCid }
    return candidates.maxByOrNull { it.createdAtMillis }
}

/**
 * 把 [item] 并进这份列表。
 *
 * 两件事,**第二件是那个幽灵条目的根治处**:
 * 1. 同一 (bvid, cid) 是同一条,替换而不是追加。
 * 2. **真 cid 到手时,撤掉这条视频的 cid=0 占位格。** 占位格的身份是假的,而下载真正跑起来
 *    之后用的是真 cid —— 不撤就是一条视频两行,用户点到的多半是那行假的。
 *
 * 只撤同一个 bvid 的占位格:同一条视频的两个分 P(两个真 cid)是两条独立的缓存,都要留着。
 */
fun List<OfflineItem>.mergedWith(item: OfflineItem): List<OfflineItem> {
    val kept = filterNot { existing ->
        val sameEntry = existing.bvid == item.bvid && existing.cid == item.cid
        val supersededPlaceholder = item.hasIdentity && existing.bvid == item.bvid && !existing.hasIdentity
        sameEntry || supersededPlaceholder
    }
    return (listOf(item) + kept).sortedByDescending { it.createdAtMillis }
}

/** 弹幕分段长度,和 [dev.bilby.danmaku.DanmakuRepository] 是同一个约定:6 分钟一段。 */
private const val DANMAKU_SEGMENT_SECONDS = 6L * 60

/**
 * 一条 [durationSeconds] 秒的视频有几段弹幕(1-based 段号的上界)。
 *
 * 边界要向上取整:360 秒整的视频只有第 1 段,361 秒就有第 2 段。少算一段的表现是视频最后
 * 那几秒离线时没有弹幕,而这条视频别处都正常 —— 查起来毫无头绪,所以这一算术单独可测。
 * 时长为 0(详情没给)时至少拉一段,总比一条都不存强。
 */
fun danmakuSegmentCount(durationSeconds: Long): Int =
    if (durationSeconds <= 0) 1
    else ((durationSeconds + DANMAKU_SEGMENT_SECONDS - 1) / DANMAKU_SEGMENT_SECONDS).toInt()

/**
 * 上次没下完的那条摆在界面上的说法。**是"接着下"不是"重来"** —— 续传按文件已有的字节数发
 * Range,点一下重试不会把已经下好的几百 MB 再走一遍。
 */
const val OFFLINE_INTERRUPTED = "上次没下完,点一下继续"

/**
 * 进程被杀时停在半路的那条,读回来时该是什么状态。
 *
 * **进程死掉不会有人来改状态**,盘上留下的就是一条 `Running`。照原样读回来的话,列表里会有
 * 一条永远停在 37% 的假进度,而它其实什么都没在做 —— 那和"卡住了"在界面上完全一样,而且
 * 用户没有任何办法让它动起来。改成失败态,重试按钮才出现。
 *
 * 已完成和已失败的原样返回:前者不该被动,后者的原因(取流被拒、片源没了)比"上次被打断"
 * 更具体,盖掉是丢信息。
 */
fun OfflineItem.recoveredFromInterruption(): OfflineItem =
    if (status == OfflineStatus.Running || status == OfflineStatus.Queued) {
        copy(status = OfflineStatus.Failed, error = OFFLINE_INTERRUPTED)
    } else {
        this
    }

/** 一次 HTTP 失败该怎么理解。见 [classifyHttpFailure]。 */
enum class DownloadFailure {
    /**
     * 直链过期了。playurl 给的是带签名、有时效的 CDN 地址,放几个小时再续传必然撞这个。
     * **处理方式和别的失败不同**:要重新走一次 playurl 拿新地址,而不是拿着同一个地址退避。
     * 这也正是这个功能没有用 Media3 `DownloadManager` 的第一条理由(见 OfflineDownloader)。
     */
    Expired,

    /** 瞬时,退避之后同一个地址还能用。 */
    Transient,

    /** 这条流本身有问题(404、参数错),重试多少次都一样。 */
    Fatal,
}

/**
 * HTTP 状态码 → 该怎么办。
 *
 * 403 与 404 必须分开:前者是签名过期(换个地址就好),后者是资源没了(换地址也没用)。
 * 混在一起的两种错法都很难查 —— 都当过期就对着 404 无限重取 playurl,都当终态就让一条
 * 只是放久了的缓存永远失败。
 */
fun classifyHttpFailure(status: Int): DownloadFailure = when (status) {
    403, 410 -> DownloadFailure.Expired
    408, 429 -> DownloadFailure.Transient
    in 500..599 -> DownloadFailure.Transient
    else -> DownloadFailure.Fatal
}
