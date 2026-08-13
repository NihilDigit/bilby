package dev.bilby.player

import dev.bilby.offline.OfflineItem
import dev.bilby.offline.OfflineStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 装载解析器测的是**优先级与降级**:哪一档说了算,以及某一档答不上来时退到哪里。这两件事
 * 每一条都对应过一个真实症状 —— 缓存了却播不动(本地那档排在网络后面)、多 P 续播落回第一 P
 * (云端那档被页面送来的默认 cid 顶掉)、进度串到别的分 P(记录指向的 P 已被删)。
 *
 * "不等网络"是判据的一部分,所以本地副本那几条同时断言两个网络源一次都没被问过:把它写成
 * "返回值对就行"的话,把本地检查挪到网络后面照样能过。
 */
class LoadResolverTest {

    @Test
    fun `用户指名的那一 P 压过服务端记录`() = runTest {
        val resolver = resolver(parts = VideoParts(1, listOf(1, 2, 3)), serverCid = 3)

        assertEquals(LoadPlan.Online(2), resolver.resolve(BVID, requestedCid = 2))
    }

    @Test
    fun `没有指名时按服务端记着的那一 P`() = runTest {
        val resolver = resolver(parts = VideoParts(1, listOf(1, 2, 3)), serverCid = 3)

        assertEquals(LoadPlan.Online(3), resolver.resolve(BVID, requestedCid = 0))
    }

    @Test
    fun `服务端没有记录时按默认那一 P`() = runTest {
        val resolver = resolver(parts = VideoParts(1, listOf(1, 2, 3)), serverCid = 0)

        assertEquals(LoadPlan.Online(1), resolver.resolve(BVID, requestedCid = 0))
    }

    @Test
    fun `服务端记录指向已不存在的分 P 时退回默认那一 P`() = runTest {
        val resolver = resolver(parts = VideoParts(1, listOf(1, 2, 3)), serverCid = 99)

        assertEquals(LoadPlan.Online(1), resolver.resolve(BVID, requestedCid = 0))
    }

    /** 单 P 视频问服务端只能得到当前这一 P,那一次网络往返省掉。 */
    @Test
    fun `单 P 视频不问服务端`() = runTest {
        var asked = false
        val resolver = LoadResolver(
            localCopy = { _, _ -> null },
            parts = { VideoParts(7, listOf(7)) },
            serverPart = { _, _ -> asked = true; 9 },
        )

        assertEquals(LoadPlan.Online(7), resolver.resolve(BVID, requestedCid = 0))
        assertEquals(false, asked)
    }

    @Test
    fun `取不到详情且没有指名时解析不出分 P`() = runTest {
        val resolver = resolver(parts = null, serverCid = 0)

        assertEquals(LoadPlan.Unresolved, resolver.resolve(BVID, requestedCid = 0))
    }

    /** 详情要联网,而指名的那一 P 本身就是答案 —— 离线里点缓存列表走的正是这一条。 */
    @Test
    fun `取不到详情但指名了分 P 时照样能播`() = runTest {
        val resolver = resolver(parts = null, serverCid = 0)

        assertEquals(LoadPlan.Online(5), resolver.resolve(BVID, requestedCid = 5))
    }

    @Test
    fun `本地副本压过云端,而且一次网络都不打`() = runTest {
        var asked = false
        val resolver = LoadResolver(
            localCopy = { _, _ -> cached(cid = 2, watchedMillis = 30_000) },
            parts = { asked = true; VideoParts(1, listOf(1, 2)) },
            serverPart = { _, _ -> asked = true; 1 },
        )

        val plan = resolver.resolve(BVID, requestedCid = 0)

        assertEquals(LoadPlan.LocalCopy(cached(cid = 2, watchedMillis = 30_000), 30_000), plan)
        assertEquals(false, asked)
    }

    /** 副本看完了就从头播,和网络流是同一条规则(见 [resumePositionMillis])。 */
    @Test
    fun `本地副本看完之后从头起播`() = runTest {
        val resolver = LoadResolver(
            localCopy = { _, _ -> cached(cid = 2, watchedMillis = 599_000) },
            parts = { null },
            serverPart = { _, _ -> 0 },
        )

        val plan = resolver.resolve(BVID, requestedCid = 0)

        assertEquals(0L, (plan as LoadPlan.LocalCopy).startPositionMillis)
    }

    /**
     * 指名的那一 P 盘上没有时不能拿别的 P 顶上:用户点的是 P7 就得是 P7,画面在动而内容是
     * 错的比播不了更糟。这个判据在 [dev.bilby.offline.pickCompletedFor] 里,解析器只是把
     * 指名原样传下去 —— 传丢了的表现正是"点 P7 播出 P3"。
     */
    @Test
    fun `指名的分 P 原样传给本地查找`() = runTest {
        var seen = -1L
        val resolver = LoadResolver(
            localCopy = { _, preferred -> seen = preferred; null },
            parts = { VideoParts(1, listOf(1)) },
            serverPart = { _, _ -> 0 },
        )

        resolver.resolve(BVID, requestedCid = 7)

        assertEquals(7L, seen)
    }

    private fun resolver(parts: VideoParts?, serverCid: Long) = LoadResolver(
        localCopy = { _, _ -> null },
        parts = { parts },
        serverPart = { _, _ -> serverCid },
    )

    private fun cached(cid: Long, watchedMillis: Long) = OfflineItem(
        bvid = BVID,
        cid = cid,
        title = "标题",
        durationSeconds = 600,
        status = OfflineStatus.Completed,
        watchedPositionMillis = watchedMillis,
    )

    private companion object {
        const val BVID = "BV1xx411c7mD"
    }
}
