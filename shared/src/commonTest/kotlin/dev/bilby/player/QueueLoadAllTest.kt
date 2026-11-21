package dev.bilby.player

import dev.bilby.data.FeedPage
import dev.bilby.data.PagedFeed
import dev.bilby.data.QueueFeed
import dev.bilby.data.QueueOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueLoadAllTest {

    private fun item(n: Int) = QueueItem(
        bvid = "BV$n",
        title = "Title $n",
        upName = "UP",
        coverUrl = "",
        durationSeconds = 60L,
    )

    @Test
    fun `loadAllQueuePages fetches after then before in Forward order and reconciles closed window`() = runTest {
        val allItems = (0 until 50).map(::item)
        val calls = mutableListOf<Int>()
        val feed = PagedFeed(anchorPage = 3, pageSize = 10) { page ->
            calls += page
            val from = (page - 1) * 10
            FeedPage(
                items = allItems.drop(from).take(10),
                hasMore = from + 10 < allItems.size,
                total = allItems.size,
            )
        }
        val playlist = feed.open("BV25")!!.toMutableList()
        val pagination = QueuePagination().apply {
            bindFeedMeta(
                feedOffsetOfFirst = feed.offsetOfFirst,
                feedTotal = feed.total,
                feedHasBefore = feed.hasBefore,
                feedHasAfter = feed.hasAfter,
                playlistSize = playlist.size,
            )
        }
        assertTrue(pagination.hasBefore)
        assertTrue(pagination.hasAfter)
        assertTrue(pagination.beginLoadAll())
        // 单头续取在全部加载期间被阻断，防止并发访问 QueueFeed
        assertFalse(pagination.beginExtend(before = true))
        assertFalse(pagination.beginExtend(before = false))

        calls.clear()
        loadAllQueuePages(
            feed = feed,
            pagination = pagination,
            order = QueueOrder.Forward,
            isStillValid = { true },
            applyItems = { before, loaded ->
                val present = playlist.mapTo(HashSet()) { it.bvid }
                val fresh = loaded.distinctBy { it.bvid }.filter { it.bvid !in present }
                if (before) playlist.addAll(0, fresh) else playlist.addAll(fresh)
                fresh.size
            },
            playlistSize = { playlist.size },
            onProgress = {},
            interPageDelayMs = 0L,
        )

        assertEquals(allItems.map { it.bvid }, playlist.map { it.bvid })
        assertFalse(pagination.loadingAll)
        assertFalse(pagination.hasBefore)
        assertFalse(pagination.hasAfter)
        assertFalse(pagination.errorBefore)
        assertFalse(pagination.errorAfter)
        assertEquals(0, pagination.offsetOfFirst)
        assertEquals(50, pagination.sourceTotal)
        // Forward 优先拉后续页(5)，再拉前面页(1)
        assertEquals(listOf(5, 1), calls)
    }

    @Test
    fun `loadAllQueuePages fetches before first in Reverse order`() = runTest {
        val allItems = (0 until 50).map(::item)
        val calls = mutableListOf<Int>()
        val feed = PagedFeed(anchorPage = 3, pageSize = 10) { page ->
            calls += page
            val from = (page - 1) * 10
            FeedPage(
                items = allItems.drop(from).take(10),
                hasMore = from + 10 < allItems.size,
                total = allItems.size,
            )
        }
        val playlist = feed.open("BV25")!!.toMutableList()
        val pagination = QueuePagination().apply {
            bindFeedMeta(
                feedOffsetOfFirst = feed.offsetOfFirst,
                feedTotal = feed.total,
                feedHasBefore = feed.hasBefore,
                feedHasAfter = feed.hasAfter,
                playlistSize = playlist.size,
            )
        }
        assertTrue(pagination.beginLoadAll())
        calls.clear()

        loadAllQueuePages(
            feed = feed,
            pagination = pagination,
            order = QueueOrder.Reverse,
            isStillValid = { true },
            applyItems = { before, loaded ->
                val present = playlist.mapTo(HashSet()) { it.bvid }
                val fresh = loaded.distinctBy { it.bvid }.filter { it.bvid !in present }
                if (before) playlist.addAll(0, fresh) else playlist.addAll(fresh)
                fresh.size
            },
            playlistSize = { playlist.size },
            onProgress = {},
            interPageDelayMs = 0L,
        )

        assertEquals(listOf(1, 5), calls)
        assertEquals(50, playlist.size)
        assertFalse(pagination.loadingAll)
    }

    @Test
    fun `error mid-flight latches directional error, stops immediately, and preserves fetched items`() = runTest {
        val allItems = (0 until 60).map(::item)
        var failPage5 = true
        val feed = PagedFeed(anchorPage = 1, pageSize = 10) { page ->
            if (page == 5 && failPage5) return@PagedFeed null
            val from = (page - 1) * 10
            FeedPage(
                items = allItems.drop(from).take(10),
                hasMore = from + 10 < allItems.size,
                total = allItems.size,
            )
        }
        val playlist = feed.open("BV0")!!.toMutableList() // pages 1, 2 loaded (20 items)
        val pagination = QueuePagination().apply {
            bindFeedMeta(
                feedOffsetOfFirst = feed.offsetOfFirst,
                feedTotal = feed.total,
                feedHasBefore = feed.hasBefore,
                feedHasAfter = feed.hasAfter,
                playlistSize = playlist.size,
            )
        }
        assertTrue(pagination.beginLoadAll())

        loadAllQueuePages(
            feed = feed,
            pagination = pagination,
            order = QueueOrder.Forward,
            isStillValid = { true },
            applyItems = { before, loaded ->
                val present = playlist.mapTo(HashSet()) { it.bvid }
                val fresh = loaded.distinctBy { it.bvid }.filter { it.bvid !in present }
                if (before) playlist.addAll(0, fresh) else playlist.addAll(fresh)
                fresh.size
            },
            playlistSize = { playlist.size },
            onProgress = {},
            interPageDelayMs = 0L,
        )

        // Pages 3 & 4 succeeded (40 items total), page 5 failed -> latched errorAfter & stopped
        assertEquals(40, playlist.size)
        assertFalse(pagination.loadingAll)
        assertTrue(pagination.hasAfter)
        assertTrue(pagination.errorAfter)
        assertFalse(pagination.loadingAfter)

        // Retrying LoadAll clears errorAfter and loads remaining pages 5 & 6
        failPage5 = false
        assertTrue(pagination.beginLoadAll())
        assertFalse(pagination.errorAfter)
        loadAllQueuePages(
            feed = feed,
            pagination = pagination,
            order = QueueOrder.Forward,
            isStillValid = { true },
            applyItems = { before, loaded ->
                val present = playlist.mapTo(HashSet()) { it.bvid }
                val fresh = loaded.distinctBy { it.bvid }.filter { it.bvid !in present }
                if (before) playlist.addAll(0, fresh) else playlist.addAll(fresh)
                fresh.size
            },
            playlistSize = { playlist.size },
            onProgress = {},
            interPageDelayMs = 0L,
        )
        assertEquals(60, playlist.size)
        assertFalse(pagination.hasAfter)
        assertFalse(pagination.errorAfter)
        assertFalse(pagination.loadingAll)
    }

    @Test
    fun `cancelling loadAll mid-flight preserves loaded items and clears loading flags`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val startedSecond = CompletableDeferred<Unit>()
        var afterCall = 0
        val feed = object : QueueFeed {
            override var hasBefore: Boolean = false
            override var hasAfter: Boolean = true
            override val offsetOfFirst: Int = 0
            override val total: Int = 30
            override suspend fun open(bvid: String): List<QueueItem> = (0 until 10).map(::item)
            override suspend fun loadBefore(): List<QueueItem> = emptyList()
            override suspend fun loadAfter(): List<QueueItem> {
                afterCall++
                return when (afterCall) {
                    1 -> (10 until 20).map(::item)
                    else -> {
                        startedSecond.complete(Unit)
                        gate.await()
                        hasAfter = false
                        (20 until 30).map(::item)
                    }
                }
            }
        }
        val playlist = feed.open("BV0")!!.toMutableList()
        val pagination = QueuePagination().apply {
            bindFeedMeta(0, 30, feedHasBefore = false, feedHasAfter = true, playlistSize = playlist.size)
        }
        assertTrue(pagination.beginLoadAll())

        val job = launch {
            loadAllQueuePages(
                feed = feed,
                pagination = pagination,
                order = QueueOrder.Forward,
                isStillValid = { true },
                applyItems = { before, loaded ->
                    val present = playlist.mapTo(HashSet()) { it.bvid }
                    val fresh = loaded.distinctBy { it.bvid }.filter { it.bvid !in present }
                    if (before) playlist.addAll(0, fresh) else playlist.addAll(fresh)
                    fresh.size
                },
                playlistSize = { playlist.size },
                onProgress = {},
                interPageDelayMs = 0L,
            )
        }
        startedSecond.await()
        job.cancel()
        job.join()

        assertEquals(20, playlist.size)
        assertFalse(pagination.loadingAll)
        assertFalse(pagination.loadingAfter)
        assertFalse(pagination.errorAfter)
        assertTrue(pagination.hasAfter)
    }

    @Test
    fun `empty response breaks loop even if feed reports hasAfter true`() = runTest {
        var calls = 0
        val feed = object : QueueFeed {
            override val hasBefore: Boolean = false
            override val hasAfter: Boolean = true
            override val offsetOfFirst: Int = 0
            override val total: Int? = null
            override suspend fun open(bvid: String): List<QueueItem> = listOf(item(0))
            override suspend fun loadBefore(): List<QueueItem> = emptyList()
            override suspend fun loadAfter(): List<QueueItem> {
                calls++
                return emptyList()
            }
        }
        val playlist = feed.open("BV0")!!.toMutableList()
        val pagination = QueuePagination().apply {
            bindFeedMeta(0, null, feedHasBefore = false, feedHasAfter = true, playlistSize = 1)
        }
        assertTrue(pagination.beginLoadAll())

        loadAllQueuePages(
            feed = feed,
            pagination = pagination,
            order = QueueOrder.Forward,
            isStillValid = { true },
            applyItems = { _, _ -> 0 },
            playlistSize = { playlist.size },
            onProgress = {},
            interPageDelayMs = 0L,
        )

        assertEquals(1, calls)
        assertFalse(pagination.loadingAll)
    }
}
