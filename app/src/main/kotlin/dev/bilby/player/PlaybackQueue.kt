package dev.bilby.player

import kotlin.random.Random

/**
 * 队列里的一条。取流要 bvid + cid 两个都有(VideoRepository.getPlayUrl 不替调用方挑 cid)。
 *
 * **cid 可能是 0**:空间投稿列表本身不返回 cid,来自那条路径的队列项只能占位。约定由播放
 * 服务在真正切到这一条时用 `VideoRepository.getVideoDetail(bvid)` 补上——**拿着 0 去
 * getPlayUrl 会被服务端当成无效 cid 拒绝**。
 */
data class QueueItem(
    val bvid: String,
    val cid: Long,
    val title: String,
    val upName: String,
    val coverUrl: String,
    val durationSeconds: Long,
)

/**
 * 听视频的播放队列。纯 Kotlin,不碰 Android 也不碰网络——队列逻辑是这块唯一值得写测试的
 * 地方(见 PlaybackQueueTest),服务那层剩下的都是播放器与系统交互。
 *
 * **播完即停,不回绕、不循环、不续接**(DESIGN 2.4b)。这是产品约束不是省事:允许连播的
 * 前提就是集合有限且由用户显式选定,一旦回绕或从推荐池续接,"播完"这个决策点就被永久取消,
 * 与被禁掉的自动连播没有区别。所以 [next] 在末尾返回 null,调用方据此停止播放。
 *
 * **随机用预先生成的乱序索引表**,不是每次 next 时随机抽一条:后者会重复播同一条,而且
 * "还剩多少"无从谈起——而随机播放本来就让人失去进度感,UI 靠 [currentIndex] / [size]
 * 显示 N / M 来补,这要求剩余条数是确定的。
 */
class PlaybackQueue(
    private val items: List<QueueItem>,
    startIndex: Int = 0,
    shuffled: Boolean = false,
    private val random: Random = Random.Default,
) {
    /** 播放顺序:元素是 [items] 的下标。顺序播时是 0..n-1,随机播时是一张乱序表。 */
    private var order: MutableList<Int> = MutableList(items.size) { it }

    /** 在**播放顺序**里的位置,不是在 [items] 里的位置。UI 显示 N / M 用的是它。 */
    var currentIndex: Int = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        private set

    var shuffled: Boolean = false
        private set

    val size: Int get() = items.size

    init {
        if (shuffled) setShuffled(true)
    }

    fun current(): QueueItem? = order.getOrNull(currentIndex)?.let(items::get)

    /** 到末尾返回 null 且不移动位置,调用方应当停止播放。 */
    fun next(): QueueItem? {
        if (currentIndex + 1 >= order.size) return null
        currentIndex++
        return current()
    }

    fun previous(): QueueItem? {
        if (currentIndex - 1 < 0) return null
        currentIndex--
        return current()
    }

    /** 跳到播放顺序里的第 [index] 条。越界返回 null 且不移动。 */
    fun seekTo(index: Int): QueueItem? {
        if (index !in order.indices) return null
        currentIndex = index
        return current()
    }

    /**
     * 切换随机。**当前正在播的这条不变**,只重排其余——切换随机是个瞬时动作,如果连带换掉
     * 正在响的声音,用户会以为自己误触了下一首。
     *
     * 打开随机时把当前这条放到表首(于是显示 1 / M,剩下 M-1 条是待播的);关闭时回到自然
     * 顺序,位置取当前这条在 [items] 里的真实下标。
     */
    fun setShuffled(shuffled: Boolean) {
        if (shuffled == this.shuffled) return
        val currentItemIndex = order.getOrNull(currentIndex)
        this.shuffled = shuffled
        if (currentItemIndex == null) return

        if (shuffled) {
            val rest = items.indices.filter { it != currentItemIndex }.shuffled(random)
            order = (listOf(currentItemIndex) + rest).toMutableList()
            currentIndex = 0
        } else {
            order = MutableList(items.size) { it }
            currentIndex = currentItemIndex
        }
    }
}
