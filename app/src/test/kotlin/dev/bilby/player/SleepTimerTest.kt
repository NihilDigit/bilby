package dev.bilby.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只覆盖状态机真会错的地方:时长与"播完当前"是两个独立的旗子,不是三选一
 * (旧版 `EndOfItem` 是这两者的一个特例);[SleepTimer.onItemFinished] 只认
 * `finishCurrentItem`,不管它是怎么被置真的。到点后的行为(delay 真实流逝)要走
 * `SystemClock`,这里不测——不设时长的分支完全不碰它,已经够覆盖两个旗子的组合关系。
 */
class SleepTimerTest {

    private fun timer(onFire: () -> Unit = {}) = SleepTimer(CoroutineScope(Job()), onFire)

    @Test
    fun `只勾播完当前,不设时长,等价于旧版 EndOfItem`() {
        var fired = false
        val sleepTimer = timer { fired = true }

        sleepTimer.start(minutes = null, finishCurrentItem = true)

        assertEquals(SleepTimerMode.Off, sleepTimer.state.value.mode)
        assertTrue(sleepTimer.onItemFinished())
        assertTrue(fired)
        // 触发之后状态机整个复位,不是只清一个字段。
        assertEquals(SleepTimerState(), sleepTimer.state.value)
    }

    @Test
    fun `没勾播完当前时,播完不触发`() {
        var fired = false
        val sleepTimer = timer { fired = true }

        sleepTimer.start(minutes = 30, finishCurrentItem = false)

        assertFalse(sleepTimer.onItemFinished())
        assertFalse(fired)
        // 播完这一条没有意义,时长部分原样留着继续倒计时。
        assertEquals(SleepTimerMode.After(30), sleepTimer.state.value.mode)
    }

    @Test
    fun `时长和播完当前互不覆盖`() {
        val sleepTimer = timer()

        sleepTimer.start(minutes = 45, finishCurrentItem = true)

        val state = sleepTimer.state.value
        assertEquals(SleepTimerMode.After(45), state.mode)
        assertTrue(state.finishCurrentItem)
    }

    @Test
    fun `cancel 把播完当前的旗子也清掉`() {
        val sleepTimer = timer()
        sleepTimer.start(minutes = null, finishCurrentItem = true)

        sleepTimer.cancel()

        assertEquals(SleepTimerState(), sleepTimer.state.value)
        assertFalse(sleepTimer.onItemFinished())
    }
}
