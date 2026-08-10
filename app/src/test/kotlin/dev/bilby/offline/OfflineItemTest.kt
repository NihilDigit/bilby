package dev.bilby.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 两处算术,都在边界上出过或容易出错的那一类:段数少算一段只在视频最后几秒才看得出来,
 * 而失败分类错了会让下载要么无限重取地址、要么把一条只是放久了的缓存判成终态。
 */
class OfflineItemTest {

    @Test
    fun `弹幕段数向上取整`() {
        // 6 分钟整正好一段;多一秒就有第二段。少算一段的表现是最后那几秒没有弹幕。
        assertEquals(1, danmakuSegmentCount(360))
        assertEquals(2, danmakuSegmentCount(361))
        assertEquals(2, danmakuSegmentCount(720))
        assertEquals(3, danmakuSegmentCount(721))
    }

    @Test
    fun `时长未知也至少拉一段`() {
        assertEquals(1, danmakuSegmentCount(0))
        assertEquals(1, danmakuSegmentCount(-1))
    }

    @Test
    fun `403 是直链过期,要换地址重来`() {
        assertEquals(DownloadFailure.Expired, classifyHttpFailure(403))
        assertEquals(DownloadFailure.Expired, classifyHttpFailure(410))
    }

    @Test
    fun `404 是终态,不能当成过期`() {
        // 当成过期就会对着一条不存在的流反复重取 playurl —— 每次都是一份白花的风控额度。
        assertEquals(DownloadFailure.Fatal, classifyHttpFailure(404))
    }

    @Test
    fun `限流与服务端故障退避重试`() {
        assertEquals(DownloadFailure.Transient, classifyHttpFailure(429))
        assertEquals(DownloadFailure.Transient, classifyHttpFailure(500))
        assertEquals(DownloadFailure.Transient, classifyHttpFailure(503))
    }

    @Test
    fun `进程被杀时停在半路的那条读回来是可重试的失败态`() {
        // 不改的话列表里会留一条永远停在 37% 的假进度,而它什么都没在做 —— 那和"卡住了"
        // 在界面上一模一样,并且重试按钮不会出现,用户没有任何办法让它动起来。
        val running = OfflineItem(bvid = "BV1", cid = 1, title = "", status = OfflineStatus.Running)
        val queued = OfflineItem(bvid = "BV1", cid = 2, title = "", status = OfflineStatus.Queued)

        assertEquals(OfflineStatus.Failed, running.recoveredFromInterruption().status)
        assertEquals(OfflineStatus.Failed, queued.recoveredFromInterruption().status)
        assertEquals(OFFLINE_INTERRUPTED, running.recoveredFromInterruption().error)
    }

    @Test
    fun `已完成的不因为重启被改掉`() {
        val done = OfflineItem(bvid = "BV1", cid = 1, title = "", status = OfflineStatus.Completed)
        assertEquals(done, done.recoveredFromInterruption())
    }

    @Test
    fun `已失败的保留原来的原因`() {
        // "上次被打断"比"取流被拒"含糊,盖掉是丢信息 —— 而后者才是用户重试多少次都不会好的
        // 那一类。
        val failed = OfflineItem(
            bvid = "BV1",
            cid = 1,
            title = "",
            status = OfflineStatus.Failed,
            error = "取流失败",
        )
        assertEquals("取流失败", failed.recoveredFromInterruption().error)
    }

    private fun item(bvid: String, cid: Long, status: OfflineStatus = OfflineStatus.Queued) =
        OfflineItem(bvid = bvid, cid = cid, title = "", status = status, createdAtMillis = cid)

    @Test
    fun `指名要某一 P 时只认那一 P`() {
        // 缓存了 P3 而用户点的是 P7,给他 P3 比播不了更糟:画面在动、内容却是错的,
        // 而且没有任何提示。
        val list = listOf(item("BV1", cid = 3, status = OfflineStatus.Completed))

        assertNull(list.pickCompletedFor("BV1", preferredCid = 7))
        assertEquals(3L, list.pickCompletedFor("BV1", preferredCid = 3)?.cid)
    }

    @Test
    fun `指名的那一 P 没缓存时不拿别的 P 顶上`() {
        // 缓存列表点进来的每一行都是一个 (bvid, cid),会带着 cid 进导航(见 ui/Destinations.kt
        // 的 Video)。这条视频缓存了 P1 和 P2,而要的是 P3 —— 只能不命中,退回网络那条路。
        // 顶上一个别的 P 的话,画面在动、内容却不是他点的那一集,而且没有任何提示。
        val list = listOf(
            item("BV1", cid = 1, status = OfflineStatus.Completed),
            item("BV1", cid = 2, status = OfflineStatus.Completed),
        )

        assertNull(list.pickCompletedFor("BV1", preferredCid = 3))
        assertEquals(2L, list.pickCompletedFor("BV1", preferredCid = 2)?.cid)
    }

    @Test
    fun `没指名哪一 P 时取最近缓存的那条`() {
        // 从缓存列表点进来就是这种:导航目的地只带 bvid(见 ui/Destinations.kt 的 Video),
        // cid 一路上没地方放。刚存的那条多半就是他现在想看的。
        val list = listOf(
            item("BV1", cid = 1, status = OfflineStatus.Completed).copy(createdAtMillis = 100),
            item("BV1", cid = 2, status = OfflineStatus.Completed).copy(createdAtMillis = 300),
        )

        assertEquals(2L, list.pickCompletedFor("BV1", preferredCid = 0)?.cid)
    }

    @Test
    fun `没下完的不算能播`() {
        // 下到一半的文件是残的,喂给播放器只会在某个位置解码失败,而那个失败点离原因很远。
        val list = listOf(
            item("BV1", cid = 1, status = OfflineStatus.Running),
            item("BV1", cid = 2, status = OfflineStatus.Failed),
        )

        assertNull(list.pickCompletedFor("BV1", preferredCid = 0))
        assertNull(list.pickCompletedFor("BV1", preferredCid = 1))
    }

    @Test
    fun `不会拿别的视频的缓存顶上`() {
        val list = listOf(item("BV2", cid = 1, status = OfflineStatus.Completed))
        assertNull(list.pickCompletedFor("BV1", preferredCid = 0))
    }

    @Test
    fun `真 cid 到手时撤掉同一条视频的占位格`() {
        // 真机上出过的那个幽灵条目:入队时 cid 还是 0,占位格按 bvid_0 落了盘;等真 cid 补出来,
        // 下载跑在 bvid_<cid> 下面 —— 列表里一条视频两行,用户点到的多半是那行 0 字节、
        // 永远 Queued、播不动的。
        val list = listOf(item("BV1", cid = 0))

        val merged = list.mergedWith(item("BV1", cid = 39932332578, status = OfflineStatus.Running))

        assertEquals(1, merged.size)
        assertEquals(39932332578L, merged.single().cid)
    }

    @Test
    fun `同一条视频的两个分 P 都留着`() {
        // 撤的只是没有身份的那格。两个真 cid 是两条独立的缓存,谁都不该顶掉谁。
        val list = listOf(item("BV1", cid = 111))

        val merged = list.mergedWith(item("BV1", cid = 222))

        assertEquals(listOf(222L, 111L), merged.map { it.cid })
    }

    @Test
    fun `同一格更新是替换不是追加`() {
        val list = listOf(item("BV1", cid = 111, status = OfflineStatus.Queued))

        val merged = list.mergedWith(item("BV1", cid = 111, status = OfflineStatus.Completed))

        assertEquals(1, merged.size)
        assertEquals(OfflineStatus.Completed, merged.single().status)
    }

    @Test
    fun `占位格不会顶掉别的视频`() {
        val list = listOf(item("BV2", cid = 0), item("BV1", cid = 111))

        val merged = list.mergedWith(item("BV1", cid = 222))

        // BV2 的占位格跟 BV1 无关,不该被这次合并带走。
        assertEquals(setOf("BV1", "BV2"), merged.map { it.bvid }.toSet())
        assertEquals(3, merged.size)
    }

    @Test
    fun `没有 cid 就没有身份`() {
        assertEquals(false, item("BV1", cid = 0).hasIdentity)
        assertEquals(true, item("BV1", cid = 1).hasIdentity)
    }

    @Test
    fun `总数未知时不报进度`() {
        // 报 0% 会画成一条一直停在起点的进度条,和"卡住了"读起来一样。
        val unknown = OfflineItem(bvid = "BV1", cid = 1, title = "", downloadedBytes = 100, totalBytes = 0)
        assertEquals(null, unknown.progress)
    }

    @Test
    fun `进度夹在 0 到 1 之间`() {
        // 服务端给的 Content-Length 偶尔比实际少(分块传输),超出 1 会让进度条画到框外面。
        val overshoot = OfflineItem(bvid = "BV1", cid = 1, title = "", downloadedBytes = 300, totalBytes = 200)
        assertEquals(1f, overshoot.progress)
    }
}
