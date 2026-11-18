package dev.bilby.ui.feed

import androidx.compose.runtime.Immutable
import dev.bilby.data.model.FeedEntry
import kotlin.math.abs

internal const val BURST_MIN_COUNT = 3
internal const val BURST_MAX_INDEX_GAP = 6
internal const val BURST_MAX_TIME_GAP_SECONDS = 6L * 3600L

@Immutable
internal sealed interface FeedDisplayNode {
    val anchorId: String

    @Immutable
    data class Single(val entry: FeedEntry) : FeedDisplayNode {
        override val anchorId: String get() = entry.id
    }

    @Immutable
    data class Burst(
        val lead: FeedEntry.Video,
        val rest: List<FeedEntry.Video>,
    ) : FeedDisplayNode {
        override val anchorId: String get() = lead.id
        val all: List<FeedEntry.Video> = listOf(lead) + rest
        val totalCount: Int get() = 1 + rest.size
    }
}

/**
 * 该节点在网格里发射的 item 数量。
 *
 * 收起态统一为 1 格（单条或叠卡）；展开态统一直接平铺 [FeedDisplayNode.Burst.all]
 * 并追加 1 格尾部收起卡/行，宽屏与窄屏共用同一套计数。
 */
internal fun FeedDisplayNode.emittedGridItemCount(expanded: Boolean): Int = when (this) {
    is FeedDisplayNode.Single -> 1
    is FeedDisplayNode.Burst -> if (expanded) totalCount + 1 else 1
}

/**
 * 将订阅流中同一 UP 主在邻近窗口内的连发或多人轮换连发合并为折叠组。
 *
 * 必须按已读位置分界线先切段再分别调用，确保不跨分界线合并；各条目间保持
 * 首次出现（最新一条）的时间顺序。
 */
internal fun groupFeedBursts(entries: List<FeedEntry>): List<FeedDisplayNode> {
    if (entries.isEmpty()) return emptyList()
    val consumed = BooleanArray(entries.size)
    val result = ArrayList<FeedDisplayNode>(entries.size)
    for (i in entries.indices) {
        if (consumed[i]) continue
        val head = entries[i]
        if (head !is FeedEntry.Video || head.upMid == 0L) {
            consumed[i] = true
            result += FeedDisplayNode.Single(head)
            continue
        }
        val chain = mutableListOf(i)
        for (j in (i + 1) until entries.size) {
            val prevIdx = chain.last()
            if (j - prevIdx > BURST_MAX_INDEX_GAP) break
            if (consumed[j]) continue
            val cand = entries[j]
            if (cand !is FeedEntry.Video || cand.upMid != head.upMid) continue
            val prev = entries[prevIdx] as FeedEntry.Video
            if (abs(prev.publishedAtEpochSeconds - cand.publishedAtEpochSeconds) > BURST_MAX_TIME_GAP_SECONDS) {
                break
            }
            chain += j
        }
        if (chain.size >= BURST_MIN_COUNT) {
            for (idx in chain) consumed[idx] = true
            result += FeedDisplayNode.Burst(
                lead = head,
                rest = chain.drop(1).map { entries[it] as FeedEntry.Video },
            )
        } else {
            consumed[i] = true
            result += FeedDisplayNode.Single(head)
        }
    }
    return result
}
