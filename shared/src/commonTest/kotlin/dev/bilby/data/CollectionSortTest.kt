package dev.bilby.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionSortTest {

    private fun video(bvid: String, publishedAt: Long) = SpaceVideoItem(
        bvid = bvid,
        title = bvid,
        coverUrl = "",
        durationText = "1:00",
        publishedAtEpochSeconds = publishedAt,
        playCountText = "1",
        danmakuCountText = "0",
    )

    @Test
    fun `按默认、最新与最早发布时间排序`() {
        val raw = listOf(
            video("BV1", 200L),
            video("BV2", 500L),
            video("BV3", 100L),
        )
        assertEquals(listOf("BV1", "BV2", "BV3"), raw.sortedByCollectionSort(CollectionSort.Default).map { it.bvid })
        assertEquals(listOf("BV2", "BV1", "BV3"), raw.sortedByCollectionSort(CollectionSort.Newest).map { it.bvid })
        assertEquals(listOf("BV3", "BV1", "BV2"), raw.sortedByCollectionSort(CollectionSort.Oldest).map { it.bvid })
    }

    @Test
    fun `超大合集依据首屏时间戳走向推断反转方向`() {
        val ascSeason = listOf(video("BV1", 100L), video("BV2", 300L))
        assertTrue(inferSeasonReverse(ascSeason, CollectionSort.Newest))
        assertFalse(inferSeasonReverse(ascSeason, CollectionSort.Oldest))
        assertFalse(inferSeasonReverse(ascSeason, CollectionSort.Default))

        val descSeason = listOf(video("BV2", 300L), video("BV1", 100L))
        assertFalse(inferSeasonReverse(descSeason, CollectionSort.Newest))
        assertTrue(inferSeasonReverse(descSeason, CollectionSort.Oldest))
    }
}
