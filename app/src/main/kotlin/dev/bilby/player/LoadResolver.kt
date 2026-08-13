package dev.bilby.player

import dev.bilby.BiliLog
import dev.bilby.offline.OfflineItem

/**
 * 一条视频的分 P 清单。[defaultCid] 是详情给的主 cid,[cids] 是全部分 P 的 cid。
 *
 * 两者分开给而不是只给列表:主 cid 是服务端标出来的那一 P,取"列表第一项"是在替它做决定。
 */
data class VideoParts(val defaultCid: Long, val cids: List<Long>)

/** 这次装载放哪一 P、从哪儿起播。 */
sealed interface LoadPlan {

    /**
     * 盘上有能播的本地副本。哪一 P 由副本自己回答,位置来自它记着的本地进度。
     *
     * **这一支一次网络都不打。** 它存在的全部意义就是没有网络时也能起播。
     */
    data class LocalCopy(val item: OfflineItem, val startPositionMillis: Long) : LoadPlan

    /**
     * 走网络取流。这里只定哪一 P —— 位置随 playurl 一起回来(见
     * [dev.bilby.data.resumeAtMillisFor]),而 playurl 要有 cid 才发得出去。
     */
    data class Online(val cid: Long) : LoadPlan

    /** 连放哪一 P 都问不出来:详情没取到,调用方也没指定。 */
    data object Unresolved : LoadPlan
}

/**
 * 装载解析器:这次放哪一 P、从哪儿起播,**一次解析,只在装载时刻发生**。
 *
 * 优先级(设计文档「决定 4」):用户显式意图 > 离线副本的本地进度 > 云端记录。三者以前散在
 * 三处各自判断,于是"这一趟到底该听谁的"没有一个能读的答案,补丁只能往调用点加:一个记下
 * "续播换过 P,页面待会儿送来的默认 cid 要忽略",另一个记下"现在放的是本地副本,页面送来的
 * cid 一律忽略"。两道防御防的都是同一件事——页面在替播放层决定分 P。入口收成只有 bvid 之后
 * 它们没有对象可防,一并删掉。
 *
 * [requestedCid] 是**用户这一次显式指定的那一 P**:页内切 P、缓存列表点某行。它是一次性的
 * 命令参数,消费即弃,不进路由身份 —— 转屏重建之后它不该再生效,否则"上次看到第 7 P"永远被
 * 一个早就过去的选择压着。为 0 表示没有指定。
 *
 * 三个数据源都是挂起的 lambda:解析的正确性全在优先级与降级上,而那件事不需要网络才能验。
 */
class LoadResolver(
    /** 盘上这条视频的完整副本;[preferredCid] 为 0 表示哪一 P 都行。 */
    private val localCopy: suspend (bvid: String, preferredCid: Long) -> OfflineItem?,
    /** 这条视频有哪几 P。取不到详情时为 null。 */
    private val parts: suspend (bvid: String) -> VideoParts?,
    /**
     * 服务端记着这条视频停在哪一 P。0 表示没有记录**或这次没问到** —— 这条路上两者等价:
     * 问不到就播默认那一 P,续播接不上只是回到第一 P,不该让起播失败。
     */
    private val serverPart: suspend (bvid: String, cid: Long) -> Long,
) {

    suspend fun resolve(bvid: String, requestedCid: Long): LoadPlan {
        // 本地副本排在所有网络之前。**这一句的位置就是它的全部意义**:补 cid、问服务端各要一次
        // 往返,排在前面的话真离线时第一步就失败返回,盘上那份一步都走不到(真机上出过,表现
        // 是"缓存了却播不动")。
        localCopy(bvid, requestedCid)?.let { cached ->
            return LoadPlan.LocalCopy(
                item = cached,
                startPositionMillis = resumePositionMillis(
                    cached.watchedPositionMillis,
                    cached.durationSeconds * 1000,
                ),
            )
        }
        // 用户指名了哪一 P 就是哪一 P,不再问服务端。指名的场合(页内切 P、缓存列表点某行)
        // 都是当场的选择,而服务端记的是上一次的 —— 拿旧记录盖掉刚点下的那一下,画面在动而
        // 内容是错的。
        if (requestedCid != 0L) return LoadPlan.Online(requestedCid)

        val parts = parts(bvid) ?: return LoadPlan.Unresolved
        if (parts.defaultCid == 0L) return LoadPlan.Unresolved
        // 单 P 不问服务端:那一趟是一次额外的网络往返,而单 P 视频问了也只能得到当前这一 P。
        if (parts.cids.size <= 1) return LoadPlan.Online(parts.defaultCid)

        val server = serverPart(bvid, parts.defaultCid)
        if (server == 0L || server == parts.defaultCid) return LoadPlan.Online(parts.defaultCid)
        if (server !in parts.cids) {
            // 记录指向的分 P 已经被 UP 删掉了。照着它取流会得到 -404,而那个错误离原因很远。
            BiliLog.w("续播记录指向的分 P 已不存在 bvid=$bvid cid=$server")
            return LoadPlan.Online(parts.defaultCid)
        }
        return LoadPlan.Online(server)
    }
}
