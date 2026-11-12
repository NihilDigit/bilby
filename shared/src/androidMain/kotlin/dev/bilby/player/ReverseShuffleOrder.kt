package dev.bilby.player

import androidx.media3.common.C
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
