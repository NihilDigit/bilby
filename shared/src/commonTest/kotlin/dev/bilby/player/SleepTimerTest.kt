package dev.bilby.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只覆盖状态机真会错的地方:三个模式互斥,而 [SleepTimer.onItemFinished] 只该拦
 * [SleepTimerMode.EndOfItem] —— 它曾经拦的是一个与时长正交的旗子,于是"定时 30 分钟"也会
 * 在当前这条播完时当场停,那 30 分钟从未生效。
 *
 * 到点后的行为(delay 真实流逝)要走 `SystemClock`,这里不测:EndOfItem 分支完全不碰它。
 */
class SleepTimerTest {

    private fun timer(onFire: () -> Unit = {}) = SleepTimer(CoroutineScope(Job()), onFire)

    @Test
    fun `播完当前，这条播完就停`() {
        var fired = false
        val sleepTimer = timer { fired = true }

        sleepTimer.start(SleepTimerMode.EndOfItem)

        assertEquals(SleepTimerMode.EndOfItem, sleepTimer.state.value.mode)
        assertTrue(sleepTimer.onItemFinished())
        assertTrue(fired)
        // 触发之后状态机整个复位,不是只清一个字段。
        assertEquals(SleepTimerState(), sleepTimer.state.value)
    }

    @Test
    fun `定时模式下这条播完不停，继续倒计时`() {
        var fired = false
        val sleepTimer = timer { fired = true }

        sleepTimer.start(SleepTimerMode.After(30))

        assertFalse(sleepTimer.onItemFinished())
        assertFalse(fired)
        assertEquals(SleepTimerMode.After(30), sleepTimer.state.value.mode)
    }

    @Test
    fun `换模式是替换,不是叠加`() {
        val sleepTimer = timer()

        sleepTimer.start(SleepTimerMode.After(45))
        sleepTimer.start(SleepTimerMode.EndOfItem)

        // 剩余时间要跟着一起清:留着的话标签会拿上一个模式的数字倒计时。
        assertEquals(SleepTimerState(SleepTimerMode.EndOfItem), sleepTimer.state.value)
    }

    @Test
    fun `cancel 之后播完不再触发`() {
        val sleepTimer = timer()
        sleepTimer.start(SleepTimerMode.EndOfItem)

        sleepTimer.cancel()

        assertEquals(SleepTimerState(), sleepTimer.state.value)
        assertFalse(sleepTimer.onItemFinished())
    }
}
