package dev.bilby.player

import dev.bilby.data.PlayEndMode
import dev.bilby.data.QueueOrder
import dev.bilby.data.QueueSource
import kotlin.random.Random

/**
 * 桌面的播放队列:条目、当前下标、随机顺序与分页边界状态。
 *
 * Android 的队列住在 ExoPlayer 的 playlist 里,上/下一条与随机由 Media3 回答;mpv 一次只装
 * 一条,没有 playlist 可借,这份状态只能自己存。设计文档「决定 2」给延迟 MediaSource 留的
 * 回退方案正是这个形状:单条装载,队列语义收进一个纯状态机。
 *
 * **语义照抄 Media3,不另起一套。** 列表按自然顺序摆,[index] 是列表下标;随机只改
 * [nextIndex]/[previousIndex] 走的顺序,规则见 [ShuffleOrder]。两端的界面读的是同一个
 * [QueueState],桌面若换一种随机法,同一个按钮在两边做的就不是同一件事。
 *
 * 一个 bvid 在队列里最多一条:队列面板拿 bvid 当 LazyColumn key,重复条目到那边是崩溃。
 * Android 在每个插入点各自去重,这里收在 [insert] 一处。
 */
internal class DesktopQueue(private val random: Random = Random.Default) {

    private val entries = mutableListOf<QueueItem>()
    private var shuffleOrder = ShuffleOrder.create(0, random)
    internal val pagination = QueuePagination()

    /** 当前这一条的列表下标,队列空时为 -1。 */
    var index: Int = -1
        private set

    var order: QueueOrder = QueueOrder.Forward

    val items: List<QueueItem> get() = entries.toList()
    val size: Int get() = entries.size
    val current: QueueItem? get() = entries.getOrNull(index)

    val offsetOfFirst: Int? get() = pagination.offsetOfFirst
    val sourceTotal: Int? get() = pagination.sourceTotal
    val hasBefore: Boolean get() = pagination.hasBefore
    val hasAfter: Boolean get() = pagination.hasAfter
    val loadingBefore: Boolean get() = pagination.loadingBefore
    val loadingAfter: Boolean get() = pagination.loadingAfter
    val errorBefore: Boolean get() = pagination.errorBefore
    val errorAfter: Boolean get() = pagination.errorAfter
    val loadingAll: Boolean get() = pagination.loadingAll
    val sourcePosition: Int? get() = pagination.sourcePositionFor(index, entries.size)

    operator fun get(at: Int): QueueItem = entries[at]

    /** 换一整份队列,随机顺序随之重排(同 `setMediaItems`)。 */
    fun set(items: List<QueueItem>, startIndex: Int) {
        val start = items.getOrNull(startIndex)
        val unique = items.distinctBy { it.bvid }
        entries.clear()
        entries.addAll(unique)
        shuffleOrder = ShuffleOrder.create(unique.size, random)
        index = if (start == null) -1 else unique.indexOfFirst { it.bvid == start.bvid }
        pagination.reset()
    }

    /**
     * 在列表下标 [at] 处插入,队列里已有的 bvid 跳过。插在当前条之前(含同一位置)时当前下标
     * 随之后移,当前这一条不变。新条目在随机顺序里落在随机位置(同 `addMediaItems`)。
     *
     * @return 实际插进去的条数。
     */
    fun insert(at: Int, items: List<QueueItem>): Int {
        val present = entries.mapTo(HashSet()) { it.bvid }
        val fresh = items.filter { present.add(it.bvid) }
        if (fresh.isEmpty()) return 0
        val position = at.coerceIn(0, entries.size)
        entries.addAll(position, fresh)
        shuffleOrder = shuffleOrder.cloneAndInsert(position, fresh.size, random)
        if (index >= position) index += fresh.size
        return fresh.size
    }

    /** 换掉某一条的展示信息。身份(bvid)不许变,变了就是另一条,那要走 [set]。 */
    fun update(at: Int, item: QueueItem) {
        require(entries[at].bvid == item.bvid) { "update must keep the bvid" }
        entries[at] = item
    }

    /** 将 [fromIndex] 移到自然顺序下标 [toIndex],保持当前播放项身份不变。 */
    fun move(fromIndex: Int, toIndex: Int): Boolean {
        if (fromIndex !in entries.indices) return false
        val target = toIndex.coerceIn(0, entries.lastIndex)
        if (fromIndex == target) return false
        val moved = entries.removeAt(fromIndex)
        entries.add(target, moved)
        index = when (index) {
            fromIndex -> target
            in (fromIndex + 1)..target -> index - 1
            in target until fromIndex -> index + 1
            else -> index
        }
        return true
    }

    /** 移除非当前播放项;若要移除当前项,调用方须先切到后继项。 */
    fun removeAt(at: Int): Boolean {
        if (at !in entries.indices || at == index) return false
        entries.removeAt(at)
        shuffleOrder = shuffleOrder.cloneAndRemove(at, at + 1)
        if (at < index) index -= 1
        return true
    }

    /** 清空除当前播放项以外的全部条目。 */
    fun clearExceptCurrent(): Boolean {
        if (index !in entries.indices || entries.size <= 1) return false
        val oldSize = entries.size
        if (index + 1 < oldSize) {
            entries.subList(index + 1, oldSize).clear()
            shuffleOrder = shuffleOrder.cloneAndRemove(index + 1, oldSize)
        }
        if (index > 0) {
            entries.subList(0, index).clear()
            shuffleOrder = shuffleOrder.cloneAndRemove(0, index)
            index = 0
        }
        return true
    }

    /** 插入或移动 [item] 至下一条或队尾,保证队列内 bvid 唯一。 */
    fun enqueue(item: QueueItem, playNext: Boolean): Boolean {
        if (item.bvid.isBlank()) return false
        if (entries.isEmpty()) {
            set(listOf(item), 0)
            return true
        }
        val cur = index
        if (cur !in entries.indices) {
            insert(entries.size, listOf(item))
            return true
        }
        val existing = indexOf(item.bvid)
        if (existing == cur) return false

        val targetIndex: Int
        if (existing >= 0) {
            entries[existing] = item
            targetIndex = if (playNext) {
                val curAfterRemove = if (existing < cur) cur - 1 else cur
                val insertPos = (if (order == QueueOrder.Reverse) curAfterRemove else curAfterRemove + 1)
                    .coerceIn(0, entries.lastIndex)
                move(existing, insertPos)
                this.index + if (order == QueueOrder.Reverse) -1 else 1
            } else {
                move(existing, entries.lastIndex)
                entries.lastIndex
            }
        } else {
            val insertPos = when {
                !playNext -> entries.size
                order == QueueOrder.Reverse -> cur
                else -> cur + 1
            }
            insert(insertPos, listOf(item))
            targetIndex = when {
                !playNext -> entries.lastIndex
                order == QueueOrder.Reverse -> this.index - 1
                else -> this.index + 1
            }
        }
        if (playNext && order == QueueOrder.Shuffle && targetIndex in entries.indices && targetIndex != this.index) {
            shuffleOrder = shuffleOrder.cloneWithNextAfter(this.index, targetIndex)
        }
        return true
    }

    fun indexOf(bvid: String): Int = entries.indexOfFirst { it.bvid == bvid }

    fun moveTo(target: Int): Boolean {
        if (target !in entries.indices) return false
        index = target
        return true
    }

    /** 下一条的列表下标,没有则 null。按 [order] 走正序、倒序或随机顺序。循环不存在。 */
    fun nextIndex(): Int? {
        if (index < 0) return null
        return when (order) {
            QueueOrder.Forward -> (index + 1).takeIf { it < entries.size }
            QueueOrder.Reverse -> (index - 1).takeIf { it >= 0 }
            QueueOrder.Shuffle -> shuffleOrder.next(index)
        }
    }

    fun previousIndex(): Int? {
        if (index < 0) return null
        return when (order) {
            QueueOrder.Forward -> (index - 1).takeIf { it >= 0 }
            QueueOrder.Reverse -> (index + 1).takeIf { it < entries.size }
            QueueOrder.Shuffle -> shuffleOrder.previous(index)
        }
    }

    fun bindFeedMeta(
        offsetOfFirst: Int?,
        total: Int?,
        hasBefore: Boolean,
        hasAfter: Boolean,
    ) {
        pagination.bindFeedMeta(offsetOfFirst, total, hasBefore, hasAfter, entries.size)
    }

    fun paginationSnapshot(): QueuePaginationSnapshot = pagination.snapshot()

    fun restorePagination(snapshot: QueuePaginationSnapshot) {
        pagination.restore(snapshot)
    }

    fun beginExtend(before: Boolean): Boolean = pagination.beginExtend(before)

    fun beginLoadAll(): Boolean = pagination.beginLoadAll()

    fun finishLoadAll() = pagination.finishLoadAll()

    fun cancelExtend(before: Boolean? = null) = pagination.cancelExtend(before)

    fun failExtend(before: Boolean) = pagination.failExtend(before)

    fun prepend(
        items: List<QueueItem>,
        feedHasBefore: Boolean = hasBefore,
        feedHasAfter: Boolean = hasAfter,
        feedTotal: Int? = sourceTotal,
    ): Int {
        val added = insert(0, items)
        pagination.completeExtend(
            before = true,
            addedCount = added,
            feedHasBefore = feedHasBefore,
            feedHasAfter = feedHasAfter,
            feedTotal = feedTotal,
            newPlaylistSize = entries.size,
        )
        return added
    }

    fun append(
        items: List<QueueItem>,
        feedHasBefore: Boolean = hasBefore,
        feedHasAfter: Boolean = hasAfter,
        feedTotal: Int? = sourceTotal,
    ): Int {
        val added = insert(entries.size, items)
        pagination.completeExtend(
            before = false,
            addedCount = added,
            feedHasBefore = feedHasBefore,
            feedHasAfter = feedHasAfter,
            feedTotal = feedTotal,
            newPlaylistSize = entries.size,
        )
        return added
    }

    fun completeExtend(
        before: Boolean,
        freshItems: List<QueueItem>,
        feedHasBefore: Boolean,
        feedHasAfter: Boolean,
        feedTotal: Int?,
    ): Int = if (before) {
        prepend(freshItems, feedHasBefore, feedHasAfter, feedTotal)
    } else {
        append(freshItems, feedHasBefore, feedHasAfter, feedTotal)
    }

    fun detachToCustomQueue() {
        pagination.detachToCustom(entries.size)
    }

    fun shouldAutoExtendBefore(detached: Boolean = false, isCustomContext: Boolean = false): Boolean =
        !detached &&
            !isCustomContext &&
            !loadingAll &&
            order == QueueOrder.Reverse &&
            entries.isNotEmpty() &&
            index == 0 &&
            hasBefore &&
            !errorBefore &&
            !loadingBefore

    fun shouldAutoExtendAfter(detached: Boolean = false, isCustomContext: Boolean = false): Boolean =
        !detached &&
            !isCustomContext &&
            !loadingAll &&
            order == QueueOrder.Forward &&
            entries.isNotEmpty() &&
            index == entries.lastIndex &&
            hasAfter &&
            !errorAfter &&
            !loadingAfter

    fun toQueueState(
        endMode: PlayEndMode = PlayEndMode.Continue,
        canPrevious: Boolean = previousIndex() != null,
        canNext: Boolean = nextIndex() != null,
        frameId: String? = null,
        sourceLabel: String = "",
        source: QueueSource? = null,
        enriching: Boolean = false,
        incomplete: Boolean = false,
        detached: Boolean = false,
    ): QueueState {
        val effectivePos = if (detached) {
            (index + 1).takeIf { entries.isNotEmpty() && index in entries.indices }
        } else {
            sourcePosition
        }
        val effectiveTotal = if (detached) {
            entries.size.takeIf { it > 0 }
        } else {
            sourceTotal
        }
        return QueueState(
            current = current,
            items = items,
            size = size,
            order = order,
            endMode = endMode,
            canPrevious = canPrevious,
            canNext = canNext,
            frameId = frameId,
            sourceLabel = sourceLabel,
            source = source,
            enriching = enriching,
            incomplete = incomplete,
            detached = detached,
            canExtendBefore = !detached && hasBefore,
            canExtendAfter = !detached && hasAfter,
            extendingBefore = !detached && loadingBefore,
            extendingAfter = !detached && loadingAfter,
            errorBefore = !detached && errorBefore,
            errorAfter = !detached && errorAfter,
            loadingAll = !detached && loadingAll,
            sourcePosition = effectivePos,
            sourceTotal = effectiveTotal,
        )
    }
}

/**
 * Media3 `ShuffleOrder.DefaultShuffleOrder` 的移植,只留队列用得到的建表与插入。
 *
 * 行为要点(与 Media3 一致):整份队列是一个随机排列,**当前条不被提到最前**,所以随机播放下
 * 当前条之前的那些要按上一条才走得到,走到排列末尾就停;插入的新条目各自落在排列里的随机
 * 位置,已有条目之间的先后不变。
 */
internal class ShuffleOrder private constructor(private val shuffled: IntArray) {

    private val positionOf = IntArray(shuffled.size).also { positions ->
        shuffled.forEachIndexed { position, value -> positions[value] = position }
    }

    fun next(index: Int): Int? = shuffled.getOrNull(positionOf[index] + 1)

    fun previous(index: Int): Int? = shuffled.getOrNull(positionOf[index] - 1)

    fun cloneAndInsert(insertionIndex: Int, insertionCount: Int, random: Random): ShuffleOrder {
        val insertionPoints = IntArray(insertionCount)
        val insertionValues = IntArray(insertionCount)
        for (i in 0 until insertionCount) {
            insertionPoints[i] = random.nextInt(shuffled.size + 1)
            val swapIndex = random.nextInt(i + 1)
            insertionValues[i] = insertionValues[swapIndex]
            insertionValues[swapIndex] = i + insertionIndex
        }
        insertionPoints.sort()
        val result = IntArray(shuffled.size + insertionCount)
        var fromOld = 0
        var fromInserted = 0
        for (i in result.indices) {
            if (fromInserted < insertionCount && fromOld == insertionPoints[fromInserted]) {
                result[i] = insertionValues[fromInserted++]
            } else {
                val value = shuffled[fromOld++]
                result[i] = if (value >= insertionIndex) value + insertionCount else value
            }
        }
        return ShuffleOrder(result)
    }

    fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int): ShuffleOrder {
        val numberOfElementsToRemove = indexToExclusive - indexFrom
        val newShuffled = IntArray(shuffled.size - numberOfElementsToRemove)
        var foundElementsCount = 0
        for (i in shuffled.indices) {
            val v = shuffled[i]
            if (v >= indexFrom && v < indexToExclusive) {
                foundElementsCount++
            } else {
                newShuffled[i - foundElementsCount] =
                    if (v >= indexFrom) v - numberOfElementsToRemove else v
            }
        }
        return ShuffleOrder(newShuffled)
    }

    fun cloneWithNextAfter(currentIndex: Int, nextIndex: Int): ShuffleOrder {
        if (currentIndex == nextIndex || currentIndex !in shuffled.indices || nextIndex !in shuffled.indices) {
            return this
        }
        val list = shuffled.toMutableList()
        list.remove(nextIndex)
        val curPos = list.indexOf(currentIndex)
        list.add(curPos + 1, nextIndex)
        return ShuffleOrder(list.toIntArray())
    }

    companion object {
        fun create(length: Int, random: Random): ShuffleOrder {
            val shuffled = IntArray(length)
            for (i in 0 until length) {
                val swapIndex = random.nextInt(i + 1)
                shuffled[i] = shuffled[swapIndex]
                shuffled[swapIndex] = i
            }
            return ShuffleOrder(shuffled)
        }
    }
}
