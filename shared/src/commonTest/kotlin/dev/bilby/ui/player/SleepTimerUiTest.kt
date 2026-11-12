package dev.bilby.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerUiTest {

    @Test
    fun `computeItemRemainingMillis scales remaining duration by playback speed`() {
        assertNull(computeItemRemainingMillis(positionMillis = 0L, durationMillis = 0L, speed = 1f))
        assertNull(computeItemRemainingMillis(positionMillis = 60_000L, durationMillis = 60_000L, speed = 1f))
        assertEquals(600_000L, computeItemRemainingMillis(positionMillis = 0L, durationMillis = 600_000L, speed = 1f))
        assertEquals(300_000L, computeItemRemainingMillis(positionMillis = 0L, durationMillis = 600_000L, speed = 2f))
    }

    @Test
    fun `formatSleepRemaining uses minute-second without splitting hours`() {
        assertEquals("0:00", formatSleepRemaining(0L))
        assertEquals("15:00", formatSleepRemaining(15 * 60_000L))
        assertEquals("120:00", formatSleepRemaining(120 * 60_000L))
        assertEquals("119:59", formatSleepRemaining(119 * 60_000L + 59_000L))
    }
}
