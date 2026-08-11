package dev.bilby.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
