package dev.bilby.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测的是**协议**:幂等的 close、冻结的身份、各触发的节流。这三件事都属于"错了也不报错"的
 * 那一类——多发一条心跳没有任何迹象,少发一条要等下次打开视频落在错的位置上才发现,而两者
 * 都只在真机上连播、切 P、退出的组合里才走得到。
 *
 * 会话不依赖 Android 也不问播放器,所以这里是纯 JVM 单测,位置由测试自己给。
 */
class ProgressSessionTest {

    private val sent = mutableListOf<Pair<Long, Boolean>>()

    private fun session(aid: Long = 170_001L, cid: Long = 999L) =
        ProgressSession(aid, cid) { seconds, finished -> sent += seconds to finished }

    @Test
    fun `位置前进不足 5 秒不发`() {
        val session = session()

        session.onPosition(4_000, DURATION)
        assertTrue("刚起播不到 5 秒就不该有心跳", sent.isEmpty())

        session.onPosition(5_000, DURATION)
        assertEquals(listOf(5L to false), sent)

        // 基准跟着刚发出去的那个位置走,所以下一条要等到第 10 秒。
        session.onPosition(9_500, DURATION)
        assertEquals(1, sent.size)
        session.onPosition(10_000, DURATION)
        assertEquals(listOf(5L to false, 10L to false), sent)
    }

    @Test
    fun `暂停恢复按 2 秒补发`() {
        val session = session()
        session.onPosition(5_000, DURATION)
        sent.clear()

        // 恢复播放时位置几乎没动,常规心跳的 5 秒够不着,这条阈值更低的够得着。
        session.onResumed(7_000, DURATION)
        assertEquals(listOf(7L to false), sent)

        sent.clear()
        session.onResumed(8_000, DURATION)
        assertTrue("距上次不足 2 秒,恢复也不发", sent.isEmpty())
    }

    @Test
    fun `seek 立即发并把基准挪到落点`() {
        val session = session()
        session.onPosition(5_000, DURATION)
        sent.clear()

        // 往回拖:位置比上次上报小,而这一条照样要发出去——服务端得立刻知道新位置。
        session.onSeeked(3_000, DURATION)
        assertEquals(listOf(3L to false), sent)

        sent.clear()
        session.onPosition(7_000, DURATION)
        assertTrue("基准已经挪到 3 秒,距它不足 5 秒不发", sent.isEmpty())
        session.onPosition(8_000, DURATION)
        assertEquals(listOf(8L to false), sent)
    }

    @Test
    fun `距结尾一秒以内算完播`() {
        val session = session()

        session.onPosition(DURATION - 10_000, DURATION)
        assertEquals(listOf((DURATION - 10_000) / 1000 to false), sent)

        sent.clear()
        session.onPosition(DURATION - 500, DURATION)
        assertEquals("最后一秒里的心跳报的就是完播", listOf((DURATION - 500) / 1000 to true), sent)
    }

    @Test
    fun `时长未知时不算完播`() {
        val session = session()
        // 时间线还没到,只有位置。没有分母就没有"还剩多少"这个问题。
        session.onPosition(5_000, 0)
        assertEquals(listOf(5L to false), sent)
    }

    @Test
    fun `close 定格补发最终位置`() {
        val session = session()
        session.onPosition(5_000, DURATION)
        sent.clear()

        // 距上次只有 2 秒,常规心跳发不出去,而这一次观看到此为止——定格那一条不受节流约束。
        session.onPosition(7_000, DURATION)
        session.close()
        assertEquals(listOf(7L to false), sent)
    }

    @Test
    fun `flush 立即补发并重置基准,会话不关`() {
        val session = session()
        session.onPosition(5_000, DURATION)
        sent.clear()

        // 距上次只有 2 秒,常规心跳发不出去,而离开播放页要的就是这两秒。
        session.onPosition(7_000, DURATION)
        session.flush()
        assertEquals(listOf(7L to false), sent)

        sent.clear()
        // 基准挪到了 7 秒,紧跟的周期心跳不该把同一个数再报一遍。
        session.onPosition(11_000, DURATION)
        assertTrue(sent.isEmpty())
        // 但会话还活着:回到这一页接着看,心跳照常。
        session.onPosition(12_000, DURATION)
        assertEquals(listOf(12L to false), sent)
    }

    @Test
    fun `close 之后 flush 是空操作`() {
        val session = session()
        session.onPosition(20_000, DURATION)
        session.close(30_000)
        sent.clear()

        session.flush()
        assertTrue("会话死了,离开页面也不该再写服务端", sent.isEmpty())
    }

    @Test
    fun `close 幂等,第二次是空操作`() {
        val session = session()
        session.onPosition(20_000, DURATION)
        sent.clear()

        session.close(30_000)
        session.close(40_000)
        session.close()
        assertEquals("任何退出路径都只管调,重复的那些什么都不做", listOf(30L to false), sent)
    }

    @Test
    fun `置死之后的触发一律不发`() {
        val session = session()
        session.close(30_000)
        sent.clear()

        session.onPosition(35_000, DURATION)
        session.onResumed(40_000, DURATION)
        session.onSeeked(45_000, DURATION)
        session.onCompleted()
        session.flush()
        assertTrue("会话死了就是死了,晚到的事件不该再写服务端", sent.isEmpty())
    }

    @Test
    fun `连播切条的 close 报完播`() {
        val session = session()
        session.onPosition(20_000, DURATION)
        sent.clear()

        // 播放器自己走到了下一条。oldPosition 未必正好等于时长(这里差了 3 秒),
        // 但"它自己走过去了"这件事本身就是完播。
        session.close(DURATION - 3_000, completed = true)
        assertEquals(listOf((DURATION - 3_000) / 1000 to true), sent)
    }

    @Test
    fun `close 没给位置时用最后观察到的那个`() {
        val session = session()
        // 服务停止、出错清理这些路径上没有 transition 事件可以给出权威位置。
        session.onPosition(12_000, DURATION)
        sent.clear()
        session.onPosition(14_000, DURATION)

        session.close()
        assertEquals(listOf(14L to false), sent)
    }

    @Test
    fun `身份创建时冻结`() {
        val session = session(aid = 42L, cid = 7L)
        assertEquals(42L, session.aid)
        assertEquals(7L, session.cid)
        // 改不了:两个 val。这条断言的价值在于它是编译期就成立的那一半的说明——真正防住的是
        // "上报时现取 cid",而现取的那一份在切 P 的窗口里和位置不是同一条内容的。
    }

    @Test
    fun `位置 0 不上报`() {
        val session = session()
        // 起播那一刻的 0 报上去会把服务端记着的位置抹掉,而这个 0 只表示"还没开始"。
        session.onSeeked(0, DURATION)
        session.close(0)
        assertTrue(sent.isEmpty())
    }

    private companion object {
        const val DURATION = 600_000L
    }
}
