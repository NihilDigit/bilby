package dev.bilby.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder

/**
 * 列表倒序的 [ShuffleOrder]:自然顺序保持不动,播放次序从 `length - 1` 往 `0` 走。
 *
 * Media3 的 `ExoPlayerImpl.setMediaItems` 通过 `cloneAndSet` -> `cloneAndClear().cloneAndInsert(...)`
 * 派生新长度的实例,不会重置回 `DefaultShuffleOrder`,因此克隆方法返回同类型的不可变实例即可跨
 * `setMediaItems` / `addMediaItems` / `removeMediaItems` 保持倒序语义。
 */
@UnstableApi
internal class ReverseShuffleOrder(private val length: Int) : ShuffleOrder {

    override fun getLength(): Int = length

    override fun getNextIndex(index: Int): Int =
        if (index in 1 until length) index - 1 else C.INDEX_UNSET

    override fun getPreviousIndex(index: Int): Int =
        if (index >= 0 && index + 1 < length) index + 1 else C.INDEX_UNSET

    override fun getLastIndex(): Int = if (length > 0) 0 else C.INDEX_UNSET

    override fun getFirstIndex(): Int = if (length > 0) length - 1 else C.INDEX_UNSET

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder =
        ReverseShuffleOrder(length + insertionCount)

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder =
        ReverseShuffleOrder((length - (indexToExclusive - indexFrom)).coerceAtLeast(0))

    override fun cloneAndClear(): ShuffleOrder = ReverseShuffleOrder(0)
}

/**
 * 基于既有随机排列，把 [nextIndex] 紧排在 [currentIndex] 之后，其余条目相对顺序不变。
 */
@UnstableApi
internal fun shuffledOrderWithNextAfter(
    shuffledIndices: IntArray,
    currentIndex: Int,
    nextIndex: Int,
    seed: Long = 0L,
): ShuffleOrder.DefaultShuffleOrder {
    val reordered = shuffledIndices.toMutableList()
    if (currentIndex in reordered && nextIndex in reordered && currentIndex != nextIndex) {
        reordered.remove(nextIndex)
        val curPos = reordered.indexOf(currentIndex)
        reordered.add(curPos + 1, nextIndex)
    }
    return ShuffleOrder.DefaultShuffleOrder(reordered.toIntArray(), seed)
}

/**
 * 从 [Timeline] 提取当前随机序列，并将 [nextIndex] 紧排在 [currentIndex] 之后返回新的 [ShuffleOrder]。
 */
@UnstableApi
internal fun Timeline.shuffledOrderWithNextAfter(
    currentIndex: Int,
    nextIndex: Int,
    seed: Long = System.nanoTime(),
): ShuffleOrder? {
    val count = windowCount
    if (count <= 0 || currentIndex !in 0 until count || nextIndex !in 0 until count || currentIndex == nextIndex) {
        return null
    }
    val order = ArrayList<Int>(count)
    val seen = HashSet<Int>(count)
    var cursor = getFirstWindowIndex(/* shuffleModeEnabled = */ true)
    while (cursor != C.INDEX_UNSET && seen.size < count) {
        if (cursor in 0 until count && seen.add(cursor)) {
            order.add(cursor)
        }
        val next = getNextWindowIndex(cursor, Player.REPEAT_MODE_OFF, /* shuffleModeEnabled = */ true)
        cursor = if (next == cursor) C.INDEX_UNSET else next
    }
    for (i in 0 until count) {
        if (seen.add(i)) order.add(i)
    }
    return shuffledOrderWithNextAfter(order.toIntArray(), currentIndex, nextIndex, seed)
}
