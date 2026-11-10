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

    @Test
    fun `热度曲线识别出 ERB 片尾 Casting 时从 234 秒起算看完`() {
        // 实测样本 BV1Vb411m7oD(乔布斯 vs 比尔盖茨,duration = 260s,step_sec = 3):
        // - 03:54(234s,index 78)起进入无字幕的 Casting 报幕,热度从正片均值 ~5400 断崖跌到 59~221;
        // - 最后一个有效桶(258s,index 86)因"完结撒花"弹幕反弹到 246.39;
        // - 261s~279s(index 87..93)是服务端越界幽灵桶。
        val threshold = pbpFinishedThresholdMillis(
            durationMillis = 260_000,
            stepSeconds = 3,
            values = ERB_JOBS_VS_GATES_PBP,
        )
        assertEquals(26_000L, threshold)
        // 停在正片末尾(230 秒)照常续播;一进 Casting(234 秒、245 秒)就按看完归零。
        assertEquals(230_000L, resumePositionMillis(230_000, durationMillis = 260_000, thresholdMillis = threshold))
        assertEquals(0L, resumePositionMillis(234_000, durationMillis = 260_000, thresholdMillis = threshold))
        assertEquals(0L, resumePositionMillis(245_000, durationMillis = 260_000, thresholdMillis = threshold))
    }

    @Test
    fun `服务端越界幽灵桶不触发片尾误判`() {
        // 实测多条常规视频(二舅、Never Gonna Give You Up、罗翔)的 pbp 数组长度是真实时长的 2~4 倍,
        // 超出 duration 的部分全填着接近 0 的幽灵值。只看有效时长内的桶,阈值必须留在基础的 10 秒。
        val inBounds = List(71) { index -> if (index == 0) 0.0 else 5_000.0 }
        val phantomTail = List(140) { 10.0 }
        val threshold = pbpFinishedThresholdMillis(
            durationMillis = 213_000,
            stepSeconds = 3,
            values = inBounds + phantomTail,
        )
        assertEquals(10_000L, threshold)
    }

    @Test
    fun `长视频自然留存衰减不触发片尾误判`() {
        // 73 分钟公开课(BV1Xx41117tr P1):从开头的 1000 缓慢衰减到结尾的 450。末尾 10% 的桶按定义
        // 必然 <= P10,但仍高于全片中位数的 35%,不是无人观看的片尾。
        val gradualDecay = List(100) { index ->
            if (index == 0) 0.0 else 1_000.0 - index * 5.5
        }
        val threshold = pbpFinishedThresholdMillis(
            durationMillis = 4_400_000,
            stepSeconds = 45,
            values = gradualDecay,
        )
        assertEquals(10_000L, threshold)
    }

    @Test
    fun `只有落在片尾候选窗口且未进基础阈值的位置才查热度曲线`() {
        // 260 秒视频:片尾窗口起点是 max(80% = 208s, 260s - 90s = 170s) = 208s,基础阈值是 250s。
        assertFalse("正片中途不查", isInPbpOutroWindow(120_000, durationMillis = 260_000))
        assertFalse("刚过 170 秒但未满 80% 不查", isInPbpOutroWindow(200_000, durationMillis = 260_000))
        assertTrue("落在 208~250 秒候选窗口内要查", isInPbpOutroWindow(235_000, durationMillis = 260_000))
        assertFalse("已经进最后 10 秒基础阈值的不必再查", isInPbpOutroWindow(255_000, durationMillis = 260_000))
    }

    private fun ours(
        sentSeconds: Long,
        confirmedSeconds: Long?,
        completed: Boolean = false,
    ) = ReportedProgress(CID, sentSeconds, confirmedSeconds, completed)

    private companion object {
        const val CID = 555L
        const val OTHER_CID = 777L

        /** `BV1Vb411m7oD` (aid=44792638, cid=76694484) 实测抓包的 `events.default`。 */
        val ERB_JOBS_VS_GATES_PBP = listOf(
            0.0, 6226.15, 7612.82, 4172.17, 6789.84, 5773.32, 3440.96, 4050.71, 6103.52, 4939.77,
            4487.58, 3695.0, 2979.13, 2816.21, 5736.89, 5736.84, 4882.77, 4565.58, 3912.66, 2554.76,
            4065.34, 4712.71, 4660.25, 2954.08, 3812.14, 3495.5, 4208.16, 4131.69, 4301.29, 3600.89,
            2628.89, 1280.17, 2989.1, 3194.2, 4552.08, 6678.57, 7384.3, 6087.19, 3428.05, 4512.01,
            4064.5, 4590.89, 6048.62, 7456.83, 6506.03, 5266.05, 4090.71, 5987.3, 10079.96, 17912.91,
            15950.25, 11101.62, 6582.34, 5138.88, 6815.17, 7960.59, 6468.93, 6235.15, 4847.2, 5608.05,
            8065.28, 7094.86, 4796.18, 2500.28, 3190.47, 5074.66, 5652.0, 5247.37, 5986.26, 6053.03,
            3783.73, 5402.51, 5817.85, 5806.42, 4743.96, 4247.74, 3001.14, 2409.59, 221.39, 138.94,
            106.4, 151.31, 137.0, 131.26, 72.14, 59.35, 246.39, 92.81, 49.49, 17.7,
            18.4, 24.47, 20.47, 17.51,
        )
    }
}
