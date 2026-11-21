package dev.bilby.offline

import dev.bilby.data.VideoDetail
import dev.bilby.data.VideoPart
import dev.bilby.data.VideoStat
import dev.bilby.data.VideoUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `Content-Range 读出起点和总长`() {
        val range = parseContentRange("bytes 500-1233/1234")
        assertEquals(500L, range?.start)
        assertEquals(1234L, range?.total)
    }

    @Test
    fun `Content-Range 总长未知时只有起点`() {
        val range = parseContentRange("bytes 500-1233/*")
        assertEquals(500L, range?.start)
        assertNull(range?.total)
    }

    @Test
    fun `Content-Range 缺失或不认识按起点未知处理`() {
        // 调用方对起点未知的 206 走 Mismatch 推倒重来 —— 一个不报起点的 206 无法安全追加。
        assertNull(parseContentRange(null))
        assertNull(parseContentRange("pages 1-2/3"))
    }

    @Test
    fun `416 那种只有总长的 Content-Range 也读得出总长`() {
        assertEquals(1234L, contentRangeTotal("bytes */1234"))
        assertEquals(1234L, contentRangeTotal("bytes 0-99/1234"))
        assertNull(contentRangeTotal("bytes 0-99/*"))
    }

    @Test
    fun `写完的长度短了是续传,长了是推倒重来`() {
        // 短了 = 服务端提前收流,按已有长度接着要就行;长了 = 盘上这份字节和远端对不上,
        // 拿着它续传只会把坏文件越接越长。
        assertNull(classifyDownloadedLength(actual = 1234, expected = 1234))
        assertEquals(DownloadFailure.Transient, classifyDownloadedLength(actual = 1000, expected = 1234))
        assertEquals(DownloadFailure.Mismatch, classifyDownloadedLength(actual = 2000, expected = 1234))
    }

    @Test
    fun `预期总长未知时读到头就算成功`() {
        // 没有依据不下判断:分块传输拿不到 Content-Length,不能把这种服务端判成失败。
        assertNull(classifyDownloadedLength(actual = 1000, expected = 0))
    }

    @Test
    fun `进度夹在 0 到 1 之间`() {
        // 服务端给的 Content-Length 偶尔比实际少(分块传输),超出 1 会让进度条画到框外面。
        val overshoot = OfflineItem(bvid = "BV1", cid = 1, title = "", downloadedBytes = 300, totalBytes = 200)
        assertEquals(1f, overshoot.progress)
    }

    @Test
    fun `已暂停条目重启恢复时保持 Paused 不被改写为失败`() {
        val paused = OfflineItem(
            bvid = "BV1",
            cid = 1,
            title = "暂停条目",
            status = OfflineStatus.Paused,
            downloadedBytes = 1024,
            totalBytes = 4096,
        )
        val recovered = paused.recoveredFromInterruption()
        assertEquals(OfflineStatus.Paused, recovered.status)
        assertNull(recovered.error)
        assertEquals(1024L, recovered.downloadedBytes)
    }

    @Test
    fun `CachedIndex 正确区分 Paused 与进行中并按优先级合并同 bvid`() {
        val index = CachedIndex.of(
            listOf(
                item("BV1", cid = 1, status = OfflineStatus.Paused),
                item("BV1", cid = 2, status = OfflineStatus.Failed),
                item("BV2", cid = 10, status = OfflineStatus.Queued),
                item("BV2", cid = 20, status = OfflineStatus.Paused),
                item("BV3", cid = 30, status = OfflineStatus.Completed),
                item("BV3", cid = 31, status = OfflineStatus.Paused),
            ),
        )

        // 精确 (bvid, cid) 查询
        assertTrue(index.isPaused("BV1" to 1L))
        assertFalse(index.isInProgress("BV1" to 1L))
        assertFalse(("BV1" to 1L) in index)
        assertTrue(index.isFailed("BV1" to 2L))

        // 按 bvid 合并(cid == 0): Completed < Running < Queued < Paused < Failed
        assertTrue(index.isPaused("BV1" to 0L))
        assertTrue(index.isInProgress("BV2" to 0L))
        assertFalse(index.isPaused("BV2" to 0L))
        assertTrue(("BV3" to 0L) in index)
    }

    @Test
    fun `Paused 条目不被当作可离线播放的已完成副本`() {
        val list = listOf(item("BV1", cid = 1, status = OfflineStatus.Paused))
        assertNull(list.pickCompletedFor("BV1", preferredCid = 0))
        assertNull(list.pickCompletedFor("BV1", preferredCid = 1))
    }

    @Test
    fun `queueSummary 分别统计下载中、已暂停与失败并排除已完成`() {
        val summary = listOf(
            item("BV1", 1, OfflineStatus.Running),
            item("BV2", 2, OfflineStatus.Queued),
            item("BV3", 3, OfflineStatus.Paused),
            item("BV4", 4, OfflineStatus.Paused),
            item("BV5", 5, OfflineStatus.Failed),
            item("BV6", 6, OfflineStatus.Completed),
        ).queueSummary()

        assertEquals(2, summary.activeCount)
        assertEquals(2, summary.pausedCount)
        assertEquals(1, summary.failedCount)
        assertEquals(5, summary.totalCount)
        assertTrue(summary.hasActive)
        assertTrue(summary.hasPaused)
        assertTrue(summary.hasFailed)
    }

    @Test
    fun `applyVideoDetailMetadata 回填合集元数据并在 seasonMid 为 0 时退回 upMid`() {
        val detail = VideoDetail(
            bvid = "BV1season",
            aid = 999L,
            cid = 101L,
            title = "第 1 集",
            description = "desc",
            descriptionSpans = emptyList(),
            coverUrl = "https://example.com/c.jpg",
            durationSeconds = 300L,
            publishedAtEpochSeconds = 1_700_000_000L,
            up = VideoUp(mid = 42L, name = "作者", faceUrl = "https://example.com/f.jpg"),
            staff = emptyList(),
            stat = VideoStat(1, 2, 3, 4, 5, 6, 7),
            maxCoins = 2,
            pages = listOf(VideoPart(cid = 101L, index = 1, title = "P1", durationSeconds = 300L)),
            seasonTitle = "系列合集",
            seasonId = 888L,
            seasonMid = 0L,
            seasonEpisodes = emptyList(),
        )

        val enriched = OfflineItem(bvid = "BV1season", cid = 101L, title = "旧标题")
            .applyVideoDetailMetadata(detail, cid = 101L)

        assertEquals(888L, enriched.seasonId)
        assertEquals("系列合集", enriched.seasonTitle)
        assertEquals(42L, enriched.seasonMid)
    }

    @Test
    fun `partitionByCollection 将合集条目归组并将无合集条目直接平铺`() {
        val items = listOf(
            OfflineItem(
                bvid = "BV1s1a", cid = 1, title = "合集A-1",
                status = OfflineStatus.Completed, downloadedBytes = 100, createdAtMillis = 100,
                seasonId = 10, seasonTitle = "合集A", seasonMid = 50, coverUrl = "https://a1",
            ),
            OfflineItem(
                bvid = "BV1s1b", cid = 2, title = "合集A-2",
                status = OfflineStatus.Completed, downloadedBytes = 250, createdAtMillis = 300,
                seasonId = 10, seasonTitle = "合集A", seasonMid = 50, coverUrl = "https://a2",
            ),
            OfflineItem(
                bvid = "BV1s2", cid = 3, title = "合集B-1",
                status = OfflineStatus.Completed, downloadedBytes = 400, createdAtMillis = 200,
                seasonId = 20, seasonTitle = "合集B", seasonMid = 60, coverUrl = "https://b1",
            ),
            OfflineItem(
                bvid = "BV1solo1", cid = 4, title = "单集1",
                status = OfflineStatus.Completed, downloadedBytes = 50, createdAtMillis = 150,
                seasonId = 0,
            ),
            OfflineItem(
                bvid = "BV1solo2", cid = 5, title = "单集2",
                status = OfflineStatus.Completed, downloadedBytes = 75, createdAtMillis = 400,
                seasonId = 0,
            ),
            // 未完成条目不进入已完成分组
            OfflineItem(
                bvid = "BV1paused", cid = 6, title = "暂停中",
                status = OfflineStatus.Paused, downloadedBytes = 30, createdAtMillis = 500,
                seasonId = 10, seasonTitle = "合集A",
            ),
        )

        val partition = items.partitionByCollection()

        // 合集按最新缓存时间倒序: 合集A(300) 在 合集B(200) 之前
        assertEquals(listOf(10L, 20L), partition.collections.map { it.seasonId })
        assertEquals("合集A", partition.collections[0].seasonTitle)
        assertEquals(2, partition.collections[0].items.size)
        assertEquals(350L, partition.collections[0].totalBytes)
        assertEquals("https://a2", partition.collections[0].coverUrl)

        // seasonId == 0 的单集直接平铺在 ungrouped,不包成额外合集分组
        assertEquals(listOf("BV1solo2", "BV1solo1"), partition.ungrouped.map { it.bvid })
    }

    @Test
    fun `groupByUp 优先按 upMid 分组并在旧索引 upMid 为 0 时退回 upName`() {
        val items = listOf(
            OfflineItem(
                bvid = "BV1a", cid = 1, title = "A1",
                upMid = 100L, upName = "甲", upFaceUrl = "https://face/a",
                status = OfflineStatus.Completed, downloadedBytes = 200, createdAtMillis = 100,
            ),
            OfflineItem(
                bvid = "BV1b", cid = 2, title = "A2",
                upMid = 100L, upName = "甲(改名后)", upFaceUrl = "",
                status = OfflineStatus.Completed, downloadedBytes = 300, createdAtMillis = 400,
            ),
            OfflineItem(
                bvid = "BV1legacy1", cid = 3, title = "旧1",
                upMid = 0L, upName = "旧作者",
                status = OfflineStatus.Completed, downloadedBytes = 120, createdAtMillis = 250,
            ),
            OfflineItem(
                bvid = "BV1legacy2", cid = 4, title = "旧2",
                upMid = 0L, upName = "旧作者",
                status = OfflineStatus.Completed, downloadedBytes = 80, createdAtMillis = 200,
            ),
            OfflineItem(
                bvid = "BV1running", cid = 5, title = "下载中",
                upMid = 999L, upName = "未完成",
                status = OfflineStatus.Running, downloadedBytes = 10, createdAtMillis = 999,
            ),
        )

        val groups = items.groupByUp()

        assertEquals(2, groups.size)
        assertEquals(OfflineUpKey.ByMid(100L), groups[0].key)
        assertEquals(2, groups[0].items.size)
        assertEquals(500L, groups[0].totalBytes)
        assertEquals("https://face/a", groups[0].upFaceUrl)

        assertEquals(OfflineUpKey.ByName("旧作者"), groups[1].key)
        assertEquals(2, groups[1].items.size)
        assertEquals(200L, groups[1].totalBytes)
    }
}
