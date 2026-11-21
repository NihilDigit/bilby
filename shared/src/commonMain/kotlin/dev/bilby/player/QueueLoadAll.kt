package dev.bilby.player

import dev.bilby.BiliLog
import dev.bilby.data.QueueFeed
import dev.bilby.data.QueueOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal const val QUEUE_LOAD_ALL_PAGE_DELAY_MS: Long = 80L

/**
 * 顺序拉取队列来源尚未读取的全部前后页，直到两端见底、任务取消或任一页失败（锁存对应方向
 * 错误态并立即停手，避免 -412 紧循环）。不设人为页数上限。
 *
 * 方向优先级与当前播放顺序一致：倒序先拉前面，其余先拉后面。
 */
internal suspend fun loadAllQueuePages(
    feed: QueueFeed,
    pagination: QueuePagination,
    order: QueueOrder,
    isStillValid: () -> Boolean,
    applyItems: (before: Boolean, items: List<QueueItem>) -> Int,
    playlistSize: () -> Int,
    onProgress: () -> Unit,
    interPageDelayMs: Long = QUEUE_LOAD_ALL_PAGE_DELAY_MS,
) {
    val directions = if (order == QueueOrder.Reverse) {
        listOf(true, false)
    } else {
        listOf(false, true)
    }
    var fetchedAnyPage = false
    try {
        for (before in directions) {
            while (true) {
                if (!isStillValid()) return
                val hasMore = if (before) pagination.hasBefore else pagination.hasAfter
                if (!hasMore) break
                if (!pagination.beginExtend(before, fromLoadAll = true)) break
                onProgress()

                if (fetchedAnyPage && interPageDelayMs > 0L) {
                    delay(interPageDelayMs)
                    if (!isStillValid()) {
                        pagination.cancelExtend(before)
                        return
                    }
                }

                val loaded = try {
                    if (before) feed.loadBefore() else feed.loadAfter()
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        pagination.cancelExtend(before)
                        throw t
                    }
                    BiliLog.w("队列全部加载异常 before=$before", t)
                    null
                }
                fetchedAnyPage = true

                if (!isStillValid()) {
                    pagination.cancelExtend(before)
                    return
                }
                if (loaded == null) {
                    BiliLog.w("队列全部加载失败 before=$before,停止继续拉取并锁存错误态")
                    pagination.failExtend(before)
                    return
                }

                val added = applyItems(before, loaded)
                pagination.completeExtend(
                    before = before,
                    addedCount = added,
                    feedHasBefore = feed.hasBefore,
                    feedHasAfter = feed.hasAfter,
                    feedTotal = feed.total,
                    newPlaylistSize = playlistSize(),
                )
                onProgress()
                // 来源约定到头时返回空表；即便 feed.hasBefore/hasAfter 未翻假也不死循环。
                if (loaded.isEmpty()) break
            }
        }
    } finally {
        pagination.finishLoadAll()
        if (isStillValid()) {
            onProgress()
        }
    }
}
