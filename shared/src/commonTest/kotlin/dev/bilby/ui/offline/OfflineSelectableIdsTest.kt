package dev.bilby.ui.offline

import dev.bilby.offline.OfflineItem
import dev.bilby.offline.OfflineStatus
import dev.bilby.offline.groupByUp
import dev.bilby.offline.partitionByCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线页多选作用域测试：验证「全选」与多选入口严格限定于当前标签/子视图实际渲染的可选条目行，
 * 绝不跨合集/UP 主子视图或折叠卡片误选全库条目。
 */
class OfflineSelectableIdsTest {

    private val seasonA1 = OfflineItem(
        bvid = "BV1a1",
        cid = 1L,
        title = "高数第一讲",
        upMid = 100L,
        upName = "老师甲",
        status = OfflineStatus.Completed,
        seasonId = 10L,
        seasonTitle = "高数合集",
        createdAtMillis = 100L,
    )
    private val seasonA2 = OfflineItem(
        bvid = "BV1a2",
        cid = 2L,
        title = "高数第二讲",
        upMid = 100L,
        upName = "老师甲",
        status = OfflineStatus.Completed,
        seasonId = 10L,
        seasonTitle = "高数合集",
        createdAtMillis = 200L,
    )
    private val seasonB1 = OfflineItem(
        bvid = "BV1b1",
        cid = 3L,
        title = "线代第一讲",
        upMid = 200L,
        upName = "老师乙",
        status = OfflineStatus.Completed,
        seasonId = 20L,
        seasonTitle = "线代合集",
        createdAtMillis = 300L,
    )
    private val solo = OfflineItem(
        bvid = "BV1solo",
        cid = 4L,
        title = "独立视频",
        upMid = 200L,
        upName = "老师乙",
        status = OfflineStatus.Completed,
        seasonId = 0L,
        createdAtMillis = 400L,
    )
    private val queued = OfflineItem(
        bvid = "BV1q",
        cid = 5L,
        title = "排队条目",
        upMid = 300L,
        upName = "老师丙",
        status = OfflineStatus.Queued,
        createdAtMillis = 500L,
    )

    private val allItems = listOf(seasonA1, seasonA2, seasonB1, solo, queued)
    private val completedOnly = listOf(seasonA1, seasonA2, seasonB1, solo)

    @Test
    fun `All 标签包含全部可见条目`() {
        val partition = allItems.partitionByCollection()
        val ids = computeOfflineSelectableIds(
            items = allItems,
            visibleItems = allItems,
            activeTab = OfflineViewTab.All,
            activeCollection = null,
            activeUp = null,
            trimmedQuery = "",
            collectionPartition = partition,
        )
        assertEquals(allItems.map { it.id }.toSet(), ids)
    }

    @Test
    fun `Collections 根标签仅包含在途条目与平铺未归组合集单集,不含折叠合集内条目`() {
        val partition = allItems.partitionByCollection()
        val ids = computeOfflineSelectableIds(
            items = allItems,
            visibleItems = allItems,
            activeTab = OfflineViewTab.Collections,
            activeCollection = null,
            activeUp = null,
            trimmedQuery = "",
            collectionPartition = partition,
        )
        assertEquals(setOf(queued.id, solo.id), ids)
    }

    @Test
    fun `Creators 根标签仅包含在途条目,无在途条目时为空集`() {
        val partitionWithInFlight = allItems.partitionByCollection()
        val withInFlight = computeOfflineSelectableIds(
            items = allItems,
            visibleItems = allItems,
            activeTab = OfflineViewTab.Creators,
            activeCollection = null,
            activeUp = null,
            trimmedQuery = "",
            collectionPartition = partitionWithInFlight,
        )
        assertEquals(setOf(queued.id), withInFlight)

        val partitionCompletedOnly = completedOnly.partitionByCollection()
        val withoutInFlight = computeOfflineSelectableIds(
            items = completedOnly,
            visibleItems = completedOnly,
            activeTab = OfflineViewTab.Creators,
            activeCollection = null,
            activeUp = null,
            trimmedQuery = "",
            collectionPartition = partitionCompletedOnly,
        )
        assertTrue(withoutInFlight.isEmpty())
    }

    @Test
    fun `打开合集子视图时严格限定为该合集条目并遵守搜索词`() {
        val partition = allItems.partitionByCollection()
        val seasonA = partition.collections.first { it.seasonId == 10L }

        val fullSubView = computeOfflineSelectableIds(
            items = allItems,
            visibleItems = allItems,
            activeTab = OfflineViewTab.Collections,
            activeCollection = seasonA,
            activeUp = null,
            trimmedQuery = "",
            collectionPartition = partition,
        )
        assertEquals(setOf(seasonA1.id, seasonA2.id), fullSubView)

        val filteredSubView = computeOfflineSelectableIds(
            items = allItems,
            visibleItems = listOf(seasonA2),
            activeTab = OfflineViewTab.Collections,
            activeCollection = seasonA,
            activeUp = null,
            trimmedQuery = "第二讲",
            collectionPartition = partition,
        )
        assertEquals(setOf(seasonA2.id), filteredSubView)
    }

    @Test
    fun `打开 UP 主子视图时严格限定为该 UP 主条目并遵守搜索词`() {
        val partition = allItems.partitionByCollection()
        val upB = allItems.groupByUp().first { it.upMid == 200L }

        val fullUpView = computeOfflineSelectableIds(
            items = allItems,
            visibleItems = allItems,
            activeTab = OfflineViewTab.Creators,
            activeCollection = null,
            activeUp = upB,
            trimmedQuery = "",
            collectionPartition = partition,
        )
        assertEquals(setOf(seasonB1.id, solo.id), fullUpView)

        val filteredUpView = computeOfflineSelectableIds(
            items = allItems,
            visibleItems = listOf(solo),
            activeTab = OfflineViewTab.Creators,
            activeCollection = null,
            activeUp = upB,
            trimmedQuery = "独立",
            collectionPartition = partition,
        )
        assertEquals(setOf(solo.id), filteredUpView)
    }
}
