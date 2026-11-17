package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.BvidCodec
import dev.bilby.api.BiliResult
import dev.bilby.parseDurationSeconds
import dev.bilby.player.QueueItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 队列背后的那份来源。**只读当前视频附近一段,两头按需续取**(docs/queue-redesign.md 决定 4):
 * 一个发过三千条的 UP 不该为了放一条视频把三千条全拉下来,而人往哪头走,就往哪头续。
 *
 * 实现都不是线程安全的,调用方(播放服务)在主线程上串行调用。
 */
interface QueueFeed {
    /** 这条视频前后各至少 [QUEUE_WINDOW] 条(来源有那么多的话)。找不到它或请求失败返回 null。 */
    suspend fun open(bvid: String): List<QueueItem>?

    /** 已读部分之前的一段。到头了返回空表,请求失败返回 null(下次再试)。 */
    suspend fun loadBefore(): List<QueueItem>?

    /** 已读部分之后的一段。约定同 [loadBefore]。 */
    suspend fun loadAfter(): List<QueueItem>?

    val hasBefore: Boolean
    val hasAfter: Boolean

    /**
     * 已读部分的第一条在整份来源里是第几条(0 起)与来源总条数,给「N / M」用。任一不知道时
     * 为 null,界面就不显示 —— 显示已读部分的条数会让一个三千条的 UP 读成"共 40 条"。
     */
    val offsetOfFirst: Int? get() = null
    val total: Int? get() = null
}

/** 打开时当前视频前后各读多少条。 */
const val QUEUE_WINDOW = 10

/** 分页接口的一页。[total] 不知道时为 null。 */
data class FeedPage(val items: List<QueueItem>, val hasMore: Boolean, val total: Int? = null)

/**
 * 按页号翻的来源:UP 投稿栏、合集/系列目录、收藏夹。
 *
 * 从 [anchorPage] 开始找这条视频。**找不到时看相邻两页**:列表页拿到这条视频之后,UP 可能又发了
 * 新投稿,旧的整体往后挪,那条视频就落到了下一页;删稿则反过来。
 */
class PagedFeed(
    private val anchorPage: Int,
    private val pageSize: Int,
    private val loadPage: suspend (page: Int) -> FeedPage?,
) : QueueFeed {
    private var firstPage = 0
    private var lastPage = 0
    private var lastHasMore = false
    private var knownTotal: Int? = null
    private var currentOffset = 0

    override val hasBefore: Boolean get() = firstPage > 1
    override val hasAfter: Boolean get() = lastHasMore
    override val offsetOfFirst: Int? get() = if (firstPage > 0) currentOffset else null
    override val total: Int? get() = knownTotal

    override suspend fun open(bvid: String): List<QueueItem>? {
        val anchor = anchorPage.coerceAtLeast(1)
        var found: List<QueueItem>? = null
        for (page in listOf(anchor, anchor + 1, anchor - 1)) {
            if (page < 1) continue
            val loaded = loadPage(page) ?: return null
            if (loaded.items.none { it.bvid == bvid }) continue
            firstPage = page
            lastPage = page
            lastHasMore = loaded.hasMore && loaded.items.isNotEmpty()
            currentOffset = if (page == 1) 0 else (page - 1) * pageSize
            knownTotal = if (page == 1 && !lastHasMore) loaded.items.size else loaded.total
            found = loaded.items
            break
        }
        val opened = found?.let { fillWindow(this, it, bvid) } ?: return null
        if (!hasBefore && !hasAfter) {
            currentOffset = 0
            knownTotal = opened.size
        }
        return opened
    }

    override suspend fun loadBefore(): List<QueueItem>? {
        if (!hasBefore) return emptyList()
        val loaded = loadPage(firstPage - 1) ?: return null
        firstPage--
        currentOffset = if (firstPage == 1) 0 else (currentOffset - loaded.items.size).coerceAtLeast(0)
        loaded.total?.let { knownTotal = it }
        return loaded.items
    }

    override suspend fun loadAfter(): List<QueueItem>? {
        if (!hasAfter) return emptyList()
        val loaded = loadPage(lastPage + 1) ?: return null
        lastPage++
        lastHasMore = loaded.hasMore && loaded.items.isNotEmpty()
        loaded.total?.let { knownTotal = it }
        return loaded.items
    }
}

/** 一次就给全的来源:合集详情里的分集、稍后再看、缓存列表。 */
class StaticFeed(private val items: List<QueueItem>) : QueueFeed {
    override val hasBefore: Boolean get() = false
    override val hasAfter: Boolean get() = false
    override val offsetOfFirst: Int get() = 0
    override val total: Int get() = items.size

    override suspend fun open(bvid: String): List<QueueItem>? = items.takeIf { list -> list.any { it.bvid == bvid } }
    override suspend fun loadBefore(): List<QueueItem> = emptyList()
    override suspend fun loadAfter(): List<QueueItem> = emptyList()
}

/**
 * UP 投稿里这条视频前后的邻居,走 aid 游标(notes/space-and-search.md 1.4.3)。
 *
 * **不用 web 投稿列表翻页定位**:那个接口的深 `pn` 会被服务端夹到一个可达上限,再往后永远
 * 返回同一页。索尼音乐中国(mid 486906719,25 万条)身上 2020 年的一条投稿七次探测全部白发。
 * 游标接口按 aid 定位,一次命中。
 *
 * 更新的那一段是往前续:列表按发布时间倒序,更新的排在前面。
 */
class ArchiveCursorFeed(
    private val load: suspend (aid: Long, newer: Boolean, includeCursor: Boolean) -> CursorArchivePage?,
) : QueueFeed {
    private var newestAid = 0L
    private var oldestAid = 0L
    private var newerLeft = false
    private var olderLeft = false
    private var upstreamTotal: Int? = null
    private var loadedSize = 0
    private val seenAids = HashSet<Long>()

    override val hasBefore: Boolean get() = newerLeft
    override val hasAfter: Boolean get() = olderLeft
    override val total: Int?
        get() = if (!newerLeft && !olderLeft && loadedSize > 0) loadedSize else upstreamTotal
    override val offsetOfFirst: Int?
        get() = when {
            loadedSize == 0 -> null
            !newerLeft -> 0
            !olderLeft && upstreamTotal != null -> (upstreamTotal!! - loadedSize).coerceAtLeast(0)
            else -> null
        }

    override suspend fun open(bvid: String): List<QueueItem>? {
        val aid = BvidCodec.toAid(bvid)
        if (aid == 0L) return null
        // 游标自己带在这一页的第一条,返回里有它就说明它确实是这位 UP 的投稿。
        val older = load(aid, false, true) ?: return null
        if (older.items.firstOrNull()?.bvid != bvid) return null
        val newer = load(aid, true, false)
        upstreamTotal = older.total ?: newer?.total
        seenAids.clear()
        val items = (newer?.items.orEmpty() + older.items).filter { seenAids.add(it.aid) }
        loadedSize = items.size
        newestAid = items.first().aid
        oldestAid = items.last().aid
        // 取更新那一段失败时仍标记为"前面还有",下次滚到头再试。
        newerLeft = if (newer == null) true else newer.hasNewer && newer.items.isNotEmpty()
        olderLeft = older.hasOlder && older.items.isNotEmpty()
        return items.map { it.toQueueItem() }
    }

    override suspend fun loadBefore(): List<QueueItem>? {
        if (!newerLeft) return emptyList()
        val page = load(newestAid, true, false) ?: return null
        page.total?.let { upstreamTotal = it }
        newerLeft = page.hasNewer && page.items.isNotEmpty()
        page.items.firstOrNull()?.let { newestAid = it.aid }
        val fresh = page.items.filter { seenAids.add(it.aid) }
        loadedSize += fresh.size
        return fresh.map { it.toQueueItem() }
    }

    override suspend fun loadAfter(): List<QueueItem>? {
        if (!olderLeft) return emptyList()
        val page = load(oldestAid, false, false) ?: return null
        page.total?.let { upstreamTotal = it }
        olderLeft = page.hasOlder && page.items.isNotEmpty()
        page.items.lastOrNull()?.let { oldestAid = it.aid }
        val fresh = page.items.filter { seenAids.add(it.aid) }
        loadedSize += fresh.size
        return fresh.map { it.toQueueItem() }
    }

    private fun CursorArchiveItem.toQueueItem() = QueueItem(
        bvid = bvid,
        title = title,
        upName = upName,
        coverUrl = coverUrl,
        durationSeconds = durationSeconds,
    )
}

/** 动态接口的一页里带视频的那些条目。[nextOffset] 为 null 或 [hasMore] 为 false 即到底。 */
data class DynamicVideoPage(val items: List<QueueItem>, val nextOffset: String?, val hasMore: Boolean)

/**
 * UP 空间动态里的视频,沿动态的游标往后翻(notes/space-and-search.md 的「用户动态」一节)。
 *
 * 为动态视频而设:它不在投稿列表里,只在动态里(1.4.3 的三类视频表)。队列里是这一页起所有
 * 带视频的动态,动态视频与普通投稿都在,按动态的时间顺序 —— 这就是空间页动态栏里的那份顺序。
 *
 * **只能往后翻。** 游标只朝更旧的方向走,没有"往前一页"。从最新一页找起时前面本来就没有东西;
 * 从中间某页找起时,比它新的那些进不了队列。
 *
 * **找与续都有页数上限 [maxPages]。** 这个接口最容易被风控(-412),为一条视频从头翻到底不值,
 * 超出上限还没找到就放弃,留在单条队列。整页都是转发、图文时一条视频也没有,续取时跳过这种页,
 * 同样受上限约束。未登录时接口只给第一页(notes 同一节),那时自然在第一页就到底。
 */
class DynamicVideoFeed(
    private val startOffset: String?,
    private val maxPages: Int,
    private val load: suspend (offset: String?) -> DynamicVideoPage?,
) : QueueFeed {
    private val fromTop = startOffset.isNullOrEmpty()
    private var nextOffset: String? = null
    private var more = false
    private var matched = false
    private var loadedCount = 0

    /** 同一条视频可能出现在相邻两页(翻页期间 UP 又发了动态),队列里同一个 bvid 只能有一条。 */
    private val seen = HashSet<String>()

    override val hasBefore: Boolean get() = false
    override val hasAfter: Boolean get() = more
    override val offsetOfFirst: Int? get() = if (matched && fromTop) 0 else null
    override val total: Int? get() = if (matched && fromTop && !more) loadedCount else null

    override suspend fun open(bvid: String): List<QueueItem>? {
        var offset = startOffset
        val read = mutableListOf<QueueItem>()
        repeat(maxPages) {
            val page = load(offset) ?: return null
            read += page.items.fresh()
            advance(page)
            if (read.any { it.bvid == bvid }) {
                matched = true
                loadedCount = read.size
                val window = fillWindow(this, read, bvid)
                loadedCount = window.size
                return window
            }
            if (!more) return null
            offset = nextOffset
        }
        return null
    }

    override suspend fun loadBefore(): List<QueueItem> = emptyList()

    override suspend fun loadAfter(): List<QueueItem>? {
        repeat(maxPages) {
            if (!more) return emptyList()
            val page = load(nextOffset) ?: return null
            advance(page)
            val items = page.items.fresh()
            if (items.isNotEmpty()) {
                loadedCount += items.size
                return items
            }
        }
        return emptyList()
    }

    private fun advance(page: DynamicVideoPage) {
        nextOffset = page.nextOffset
        more = page.hasMore && page.nextOffset != null
    }

    private fun List<QueueItem>.fresh(): List<QueueItem> = filter { seen.add(it.bvid) }
}

/**
 * 夜间模式「全部」视图的跨 UP 归并队列:按 `publishedAtEpochSeconds` 全局倒序归并多位白名单 UP 主的投稿。
 */
class NightMergeFeed(
    ups: List<WhitelistedUp>,
    private val batchSize: Int = SpaceRepository.ARCHIVE_PAGE_SIZE,
    private val maxOpenBatches: Int = DEFAULT_MAX_OPEN_BATCHES,
    private val loadPage: suspend (mid: Long, page: Int) -> SpaceArchivePage?,
) : QueueFeed {

    constructor(
        spaceRepository: SpaceRepository,
        ups: List<WhitelistedUp>,
    ) : this(
        ups = ups,
        loadPage = { mid, page ->
            when (val r = spaceRepository.loadArchives(mid, page, SpaceArchiveOrder.Pubdate)) {
                is BiliResult.Ok -> r.value
                else -> null.also { BiliLog.w("队列:夜间归并拉取投稿第 $page 页失败 mid=$mid") }
            }
        },
    )

    private class UpCursor(
        val up: WhitelistedUp,
        var nextPage: Int = 1,
        var hasMore: Boolean = true,
        var fetchedCount: Int = 0,
        var total: Int = 0,
        val buffer: ArrayDeque<SpaceVideoItem> = ArrayDeque(),
    )

    private val cursors = ups.distinctBy { it.mid }.map { UpCursor(it) }
    private val seenBvids = HashSet<String>()
    private var openedFromTop = false
    private var cappedFallback = false
    private var loadedCount = 0

    override val hasBefore: Boolean get() = false
    override val hasAfter: Boolean
        get() = !cappedFallback && cursors.any { it.buffer.isNotEmpty() || it.hasMore }
    override val offsetOfFirst: Int? get() = if (openedFromTop) 0 else null
    override val total: Int?
        get() = if (openedFromTop && !hasAfter) {
            loadedCount.takeIf { it > 0 }
        } else {
            cursors.sumOf { it.total }.takeIf { it > 0 }
        }

    override suspend fun open(bvid: String): List<QueueItem>? {
        if (cursors.isEmpty()) return null
        val collected = ArrayList<QueueItem>()
        var batches = 0
        while (batches < maxOpenBatches) {
            batches += 1
            val batch = drain(batchSize)
            if (batch == null) {
                if (collected.isEmpty()) return null
                break
            }
            if (batch.isEmpty()) break
            collected.addAll(batch)
            val idx = collected.indexOfFirst { it.bvid == bvid }
            if (idx >= 0) {
                while (collected.size - 1 - idx < QUEUE_WINDOW && hasAfter) {
                    val extra = drain(QUEUE_WINDOW) ?: break
                    if (extra.isEmpty()) break
                    collected.addAll(extra)
                }
                openedFromTop = true
                loadedCount = collected.size
                return collected
            }
            if (!hasAfter) break
        }
        if (cursors.any { it.buffer.isNotEmpty() || it.hasMore }) {
            cappedFallback = true
            openedFromTop = false
            return listOf(QueueItem(bvid = bvid, title = "", upName = "", coverUrl = "", durationSeconds = 0L))
        }
        return null
    }

    override suspend fun loadBefore(): List<QueueItem> = emptyList()

    override suspend fun loadAfter(): List<QueueItem>? {
        if (!hasAfter) return emptyList()
        val batch = drain(batchSize) ?: return null
        loadedCount += batch.size
        return batch
    }

    private suspend fun drain(targetCount: Int): List<QueueItem>? {
        val batch = ArrayList<QueueItem>(targetCount)
        var refetchRounds = 0

        while (batch.size < targetCount) {
            val needFetch = cursors.filter { it.buffer.isEmpty() && it.hasMore }
            var fetchFailed = false
            if (needFetch.isNotEmpty()) {
                if (refetchRounds >= MAX_REFETCH_ROUNDS) break
                refetchRounds += 1
                val responses = coroutineScope {
                    needFetch.map { cursor ->
                        val requestedPage = cursor.nextPage
                        async {
                            Triple(
                                cursor,
                                requestedPage,
                                loadPage(cursor.up.mid, requestedPage),
                            )
                        }
                    }.awaitAll()
                }
                for ((cursor, requestedPage, page) in responses) {
                    if (page != null) {
                        val pageItems = page.items
                        cursor.total = page.total
                        cursor.fetchedCount += pageItems.size
                        pageItems.forEach { video -> cursor.buffer.addLast(video) }
                        if (pageItems.isNotEmpty()) {
                            cursor.nextPage = requestedPage + 1
                        }
                        cursor.hasMore = pageItems.isNotEmpty() && cursor.fetchedCount < page.total
                    } else {
                        fetchFailed = true
                    }
                }
            }

            if (fetchFailed) {
                return if (batch.isEmpty()) null else batch
            }
            if (cursors.any { it.buffer.isEmpty() && it.hasMore }) continue

            val bestCursor = cursors
                .filter { it.buffer.isNotEmpty() }
                .maxByOrNull { it.buffer.first().publishedAtEpochSeconds }
                ?: break

            val video = bestCursor.buffer.removeFirst()
            if (seenBvids.add(video.bvid)) {
                batch.add(
                    QueueItem(
                        bvid = video.bvid,
                        title = video.title,
                        upName = video.upName.ifBlank { bestCursor.up.name },
                        coverUrl = video.coverUrl,
                        durationSeconds = parseDurationSeconds(video.durationText),
                    ),
                )
            }
        }
        return batch
    }

    private companion object {
        const val DEFAULT_MAX_OPEN_BATCHES = 10
        const val MAX_REFETCH_ROUNDS = 4
    }
}

/**
 * 从已读的一段出发,往两头补到 [bvid] 前后各 [QUEUE_WINDOW] 条。某一头请求失败就停在那一头,
 * 不影响另一头 —— 少几条邻居不是打不开这条视频的理由。
 */
internal suspend fun fillWindow(feed: QueueFeed, initial: List<QueueItem>, bvid: String): List<QueueItem> {
    var items = initial.distinctBy { it.bvid }
    var here = items.indexOfFirst { it.bvid == bvid }
    if (here < 0) return items
    while (here < QUEUE_WINDOW && feed.hasBefore) {
        val before = feed.loadBefore() ?: break
        if (before.isEmpty()) break
        val existing = items.mapTo(HashSet()) { it.bvid }
        val fresh = before.distinctBy { it.bvid }.filter { it.bvid !in existing }
        items = fresh + items
        here += fresh.size
    }
    while (items.size - 1 - here < QUEUE_WINDOW && feed.hasAfter) {
        val after = feed.loadAfter() ?: break
        if (after.isEmpty()) break
        val existing = items.mapTo(HashSet()) { it.bvid }
        val fresh = after.distinctBy { it.bvid }.filter { it.bvid !in existing }
        items = items + fresh
    }
    return items
}
