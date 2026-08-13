package dev.bilby.player

import dev.bilby.data.LastPlayed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续播位置的判据。这块的错法都是静默的:阈值取小了表现成"每次打开都跳下一个",合并方向反了
 * 表现成"网页上重看之后手机还停在旧位置",两种都不会报错,只能靠断言挡住。
 */
class ResumePositionTest {

    @Test
    fun `停在片尾按看完处理,不从倒数几秒续播`() {
        // 真机上的那条:600 秒的视频,完播上报被中断,服务端停在 597 秒。旧的 2000 毫秒容差
        // 放行了它,于是 seek 到 597 秒、播 3 秒、队列进下一条。阈值是 min(10s, 30s) = 10s,
        // 剩 3 秒落在里面。
        assertEquals(0, resumePositionMillis(597_000, durationMillis = 600_000))
    }

    @Test
    fun `短视频的阈值按比例收窄`() {
        // 30 秒的视频若也留 10 秒,三分之一的内容会被当成片尾。5% = 1.5 秒。
        assertEquals(1_500, finishedThresholdMillis(30_000))
        assertEquals(27_000, resumePositionMillis(27_000, durationMillis = 30_000))
        assertEquals(0, resumePositionMillis(29_000, durationMillis = 30_000))
    }

    @Test
    fun `长视频的阈值封顶在 10 秒`() {
        // 3 小时的片子,5% 是 9 分钟 —— 那不是片尾,是还剩一整段没看。
        assertEquals(10_000, finishedThresholdMillis(3 * 3600 * 1000L))
        assertEquals(10_500_000, resumePositionMillis(10_500_000, durationMillis = 10_800_000))
    }

    @Test
    fun `时长未知时不判看完,照原位置续播`() {
        // 没有分母就没有"还剩多少"。从头开始是可感知的损失,多播一段片尾不是。
        assertFalse(isWatchedToEnd(597_000, durationMillis = 0))
        assertEquals(597_000, resumePositionMillis(597_000, durationMillis = 0))
    }

    @Test
    fun `服务端动过就丢掉本地那份`() {
        // 断网看缓存看到 400 秒(写下时服务端是 300 秒),之后在网页上看到了 500 秒。
        assertEquals(500_000, mergeCachedProgress(localMillis = 400_000, base = 300_000, serverMillis = 500_000))
    }

    @Test
    fun `服务端没动过就用本地那份`() {
        // 同样的断网观看,但期间别处没看过。服务端仍停在 300 秒,本机那 400 秒是唯一动过的。
        assertEquals(400_000, mergeCachedProgress(localMillis = 400_000, base = 300_000, serverMillis = 300_000))
    }

    @Test
    fun `网页上从头重看时服务端赢,不取较大者`() {
        // 服务端退回 0。取较大者会把人摁回本地那个旧位置,而"重看"恰恰是意图最明确的场景。
        assertEquals(0, mergeCachedProgress(localMillis = 400_000, base = 300_000, serverMillis = 0))
    }

    @Test
    fun `从没联网看过时本地那份直接生效`() {
        // base 与 server 都是 0(服务端没有这条视频的记录),相等,本地说了算。
        assertEquals(120_000, mergeCachedProgress(localMillis = 120_000, base = 0, serverMillis = 0))
    }

    @Test
    fun `看完的判定与起播位置互不推翻`() {
        // 看完了要能在列表里显示成看完,而这次仍然从 0 起播。两件事分开问。
        assertTrue(isWatchedToEnd(600_000, durationMillis = 600_000))
        assertEquals(0, resumePositionMillis(600_000, durationMillis = 600_000))
    }

    @Test
    fun `云端等于自己报的就不是别处`() {
        // 页面重开时最常见的那一趟:离开页面时的 flush 报了 300 秒,云端回的正是它。
        assertNull(
            cloudProgressWrittenElsewhere(
                server = LastPlayed(cid = CID, positionMillis = 300_000),
                ours = ours(sentSeconds = 300, confirmedSeconds = 300),
            )
        )
    }

    @Test
    fun `确认还没回来时也算自己报的`() {
        // flush 刚发出去、响应还没落地,用户已经重新进了这一页。只认确认过的那个值的话,
        // 这里会把自己刚写上去的 300 秒当成别处写的,弹一条指着当前位置的提示。
        assertNull(
            cloudProgressWrittenElsewhere(
                server = LastPlayed(cid = CID, positionMillis = 300_000),
                ours = ours(sentSeconds = 300, confirmedSeconds = 120),
            )
        )
    }

    @Test
    fun `上报失败时云端仍停在确认过的那个值上`() {
        // 断网时那条心跳没发出去。云端还是上次确认的 120 秒——它不是别处写的。
        assertNull(
            cloudProgressWrittenElsewhere(
                server = LastPlayed(cid = CID, positionMillis = 120_000),
                ours = ours(sentSeconds = 300, confirmedSeconds = 120),
            )
        )
    }

    @Test
    fun `云端和自己报的对不上就是别处写的`() {
        // 我们停在 300 秒,云端却是 900 秒:这期间有人在网页或官方 app 上看过。
        assertEquals(
            900_000L,
            cloudProgressWrittenElsewhere(
                server = LastPlayed(cid = CID, positionMillis = 900_000),
                ours = ours(sentSeconds = 300, confirmedSeconds = 300),
            )
        )
    }

    @Test
    fun `别处换了一 P 也是别处写的`() {
        // 服务端整条视频只存一对 (cid, 秒数),换 P 意味着这一对整个属于另一 P。
        assertEquals(
            60_000L,
            cloudProgressWrittenElsewhere(
                server = LastPlayed(cid = OTHER_CID, positionMillis = 60_000),
                ours = ours(sentSeconds = 300, confirmedSeconds = 300),
            )
        )
    }

    @Test
    fun `自己看完之后别处又看了一段,提示`() {
        // 我们写上去的是哨兵 -1,所以云端这个正的秒数一定是别处写的。实测:报 -1 之后 v2 回
        // last_play_time=-1000(notes/playurl.md §8.1.2),不会变成时长,也不会归零。
        assertEquals(
            300_000L,
            cloudProgressWrittenElsewhere(
                server = LastPlayed(cid = CID, positionMillis = 300_000),
                ours = ours(sentSeconds = 600, confirmedSeconds = 600, completed = true),
            )
        )
    }

    @Test
    fun `云端记着的就是自己那次完播时不提示`() {
        // v2 回的 -1000 在 SubtitleRepository.lastPlayed 里已经被夹到 0,和"服务端没有记录"
        // 落在同一支上——两者要的行为一样:没有可跳的地方。
        assertNull(
            cloudProgressWrittenElsewhere(
                server = LastPlayed(cid = CID, positionMillis = 0),
                ours = ours(sentSeconds = 600, confirmedSeconds = 600, completed = true),
            )
        )
    }

    @Test
    fun `服务端说没有记录时不提示`() {
        // cid 为 0 是"这条视频服务端没有记录"。
        assertNull(
            cloudProgressWrittenElsewhere(
                server = LastPlayed(cid = 0, positionMillis = 0),
                ours = ours(sentSeconds = 300, confirmedSeconds = 300),
            )
        )
        assertNull(
            cloudProgressWrittenElsewhere(
                server = LastPlayed(cid = CID, positionMillis = 0),
                ours = ours(sentSeconds = 300, confirmedSeconds = 300),
            )
        )
    }

    @Test
    fun `没问到和没有记录是两回事`() {
        // 离线、限流、接口出错都给 null。当成"服务端说 0"的话,下次联网必然弹一条用户
        // 根本没做过的"别处已看到"。
        assertNull(
            cloudProgressWrittenElsewhere(
                server = null,
                ours = ours(sentSeconds = 300, confirmedSeconds = 300),
            )
        )
    }

    @Test
    fun `这次一次都没报过时没有可比的基线`() {
        // 云端那一对多半就是装载时用来起播的那个,拿它弹提示等于把用户已经在的位置再提议一遍。
        assertNull(
            cloudProgressWrittenElsewhere(
                server = LastPlayed(cid = CID, positionMillis = 300_000),
                ours = null,
            )
        )
    }

    private fun ours(
        sentSeconds: Long,
        confirmedSeconds: Long?,
        completed: Boolean = false,
    ) = ReportedProgress(CID, sentSeconds, confirmedSeconds, completed)

    private companion object {
        const val CID = 555L
        const val OTHER_CID = 777L
    }
}
