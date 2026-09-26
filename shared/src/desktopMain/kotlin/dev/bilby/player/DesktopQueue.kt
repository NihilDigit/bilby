package dev.bilby.player

import kotlin.random.Random

/**
 * 桌面的播放队列:条目、当前下标与随机顺序。
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
    private var order = ShuffleOrder.create(0, random)

    /** 当前这一条的列表下标,队列空时为 -1。 */
    var index: Int = -1
        private set

    var shuffled: Boolean = false

    val items: List<QueueItem> get() = entries.toList()
    val size: Int get() = entries.size
    val current: QueueItem? get() = entries.getOrNull(index)

    operator fun get(at: Int): QueueItem = entries[at]

    /** 换一整份队列,随机顺序随之重排(同 `setMediaItems`)。 */
    fun set(items: List<QueueItem>, startIndex: Int) {
        val start = items.getOrNull(startIndex)
        val unique = items.distinctBy { it.bvid }
        entries.clear()
        entries.addAll(unique)
        order = ShuffleOrder.create(unique.size, random)
        index = if (start == null) -1 else unique.indexOfFirst { it.bvid == start.bvid }
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
        order = order.cloneAndInsert(position, fresh.size, random)
        if (index >= position) index += fresh.size
        return fresh.size
    }

    /** 换掉某一条的展示信息。身份(bvid)不许变,变了就是另一条,那要走 [set]。 */
    fun update(at: Int, item: QueueItem) {
        require(entries[at].bvid == item.bvid) { "update must keep the bvid" }
        entries[at] = item
    }

    fun indexOf(bvid: String): Int = entries.indexOfFirst { it.bvid == bvid }

    fun moveTo(target: Int): Boolean {
        if (target !in entries.indices) return false
        index = target
        return true
    }

    /** 下一条的列表下标,没有则 null。随机时走随机顺序,否则走列表顺序。循环不存在。 */
    fun nextIndex(): Int? {
        if (index < 0) return null
        return if (shuffled) order.next(index) else (index + 1).takeIf { it < entries.size }
    }

    fun previousIndex(): Int? {
        if (index < 0) return null
        return if (shuffled) order.previous(index) else (index - 1).takeIf { it >= 0 }
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
