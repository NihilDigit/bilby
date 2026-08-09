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
    items: List<QueueItem>,
    startIndex: Int = 0,
    shuffled: Boolean = false,
    private val random: Random = Random.Default,
) {
    /**
     * 可变的两个理由:[updateCurrentCid] 换当前这一格记的分 P,以及 [replaceKeeping] 把起播时
     * 那份只有一条的临时队列整份换成真正的来源。**别拿它当"可以往队列里加东西"的口子**:
     * 队列有限且由用户显式选定,能追加就等于恢复了被禁的自动续接(DESIGN 1.3)。
     */
    private val items: MutableList<QueueItem> = items.toMutableList()
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
     * 给 UI 列表用的**自然顺序**。随机只改播放顺序(order),不改列表怎么摆——
     * 列表跟着重排会让人找不到刚才看的那条在哪。
     */
    fun itemsNatural(): List<QueueItem> = items

    /**
     * 把当前这一格记的 cid 换成正在播的那一 P。
     *
     * 队列装的是**视频**,cid 只是"从哪一 P 起播"的默认值(通常是 P1)。在播放页或听视频页
     * 换过 P 之后,这一格若还记着默认值,走开再回来就落回 P1 —— 而用户离开时明明在 P3。
     */
    fun updateCurrentCid(cid: Long) {
        val index = order.getOrNull(currentIndex) ?: return
        items[index] = items[index].copy(cid = cid)
    }

    /**
     * 补当前这一格的展示信息(标题、UP 名、封面)。
     *
     * 起播时装的临时队列只有 bvid 和 cid —— 页面在拿到视频详情**之前**就把打开命令发出来了,
     * 那时它自己也还不知道这条视频叫什么。这三样随后才到,通知栏和队列面板要的正是它们。
     *
     * **非空的新值胜出,空值一律不覆盖。** 从队列面板点进来的那条本来就带着完整信息,一次
     * 空参数不该把它擦掉;而"这一格已经有值了就不再更新"也不对 —— 空间投稿来源的条目 upName
     * 恒为空,永远等不到被补上。
     *
     * 返回是否真的改了什么,调用方据此决定要不要重发状态。
     */
    fun fillCurrentMetadata(title: String, upName: String, coverUrl: String): Boolean {
        val index = order.getOrNull(currentIndex) ?: return false
        val existing = items[index]
        val updated = existing.copy(
            title = title.ifEmpty { existing.title },
            upName = upName.ifEmpty { existing.upName },
            coverUrl = coverUrl.ifEmpty { existing.coverUrl },
        )
        if (updated == existing) return false
        items[index] = updated
        return true
    }

    /**
     * 用补全好的完整来源换掉现有内容,当前这条按 [bvid] 重新定位。
     *
     * **这不是"往队列里追加"。** 换进来的仍是同一次打开所选定的那个有限集合(合集,或 UP
     * 投稿里当前视频前后各 25 条),只是起播时先装了一份只有当前视频的临时队列,真正的来源
     * 随后才建好。播到末尾再续一批仍然是被禁的。
     *
     * **按 bvid 定位而不是按下标。** 补全期间投稿列表可能已经变了(UP 发了新稿,整体后移
     * 一位),照下标换会把当前格换成邻居,而 cid 还记着原来那条 —— bvid 与 cid 分属两条视频,
     * playurl 回 -404「啥都木有」,队列界面高亮的又是第三条。这条线上出过。
     *
     * 新队列里没有 [bvid] 时返回 false 且保持原样,由调用方留在临时队列上。
     */
    fun replaceKeeping(bvid: String, newItems: List<QueueItem>): Boolean {
        val index = newItems.indexOfFirst { it.bvid == bvid }
        if (index < 0) return false
        // 已经补到的 cid 跟着走。来源列表给的是这条视频的默认 P1,直接采用会让"正在播第 3 P"
        // 在队列里被记成 P1,走开再回来就落回 P1。
        val keptCid = current()?.takeIf { it.bvid == bvid }?.cid ?: 0L
        val wasShuffled = shuffled

        items.clear()
        items.addAll(newItems)
        if (keptCid != 0L) items[index] = items[index].copy(cid = keptCid)
        order = MutableList(items.size) { it }
        currentIndex = index

        // 随机表是按旧内容生成的,整份作废。重新打开时 setShuffled 会把当前这条放到表首,
        // 于是正在响的这条不受影响 —— 与用户手动切随机时的行为是同一条路径。
        shuffled = false
        if (wasShuffled) setShuffled(true)
        return true
    }

    /** 跳到指定 bvid,不在队列里返回 null。 */
    fun seekToBvid(bvid: String): QueueItem? {
        val position = order.indexOfFirst { items[it].bvid == bvid }
        return if (position < 0) null else seekTo(position)
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
