package dev.bilby.data

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeTest {

    private val utc = ZoneId.of("UTC")

    private fun epoch(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0, millis: Int = 0, zone: ZoneId = utc): Long =
        LocalDateTime.of(year, month, day, hour, minute, second, millis * 1_000_000)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `disabled prefs always return Inactive`() {
        val prefs = NightModePrefs(
            enabled = false,
            startMinuteOfDay = 23 * 60 + 30,
            endMinuteOfDay = 6 * 60,
        )
        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(prefs, epoch(2026, 11, 16, 23, 45), utc))
        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(prefs, epoch(2026, 11, 17, 2, 0), utc))
    }

    @Test
    fun `cross-midnight schedule evaluates half-open window accurately`() {
        val prefs = NightModePrefs(enabled = true, startMinuteOfDay = 23 * 60 + 30, endMinuteOfDay = 6 * 60)
        val expectedWindow = ActiveNightWindow(
            startEpochMillis = epoch(2026, 11, 16, 23, 30),
            endEpochMillis = epoch(2026, 11, 17, 6, 0),
        )

        // 1ms before start -> Inactive
        assertEquals(
            NightWindowStatus.Inactive,
            evaluateNightMode(prefs, epoch(2026, 11, 16, 23, 29, 59, 999), utc),
        )
        // Exact start -> Active
        assertEquals(
            NightWindowStatus.Active(expectedWindow),
            evaluateNightMode(prefs, epoch(2026, 11, 16, 23, 30, 0, 0), utc),
        )
        // After midnight -> Active with same window
        assertEquals(
            NightWindowStatus.Active(expectedWindow),
            evaluateNightMode(prefs, epoch(2026, 11, 17, 2, 15), utc),
        )
        // Exact end -> Inactive (half-open)
        assertEquals(
            NightWindowStatus.Inactive,
            evaluateNightMode(prefs, epoch(2026, 11, 17, 6, 0, 0, 0), utc),
        )
    }

    @Test
    fun `same-day schedule evaluates half-open window accurately`() {
        val prefs = NightModePrefs(enabled = true, startMinuteOfDay = 9 * 60, endMinuteOfDay = 17 * 60)
        val expectedWindow = ActiveNightWindow(
            startEpochMillis = epoch(2026, 11, 16, 9, 0),
            endEpochMillis = epoch(2026, 11, 16, 17, 0),
        )

        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(prefs, epoch(2026, 11, 16, 8, 59, 59, 999), utc))
        assertEquals(NightWindowStatus.Active(expectedWindow), evaluateNightMode(prefs, epoch(2026, 11, 16, 9, 0), utc))
        assertEquals(NightWindowStatus.Active(expectedWindow), evaluateNightMode(prefs, epoch(2026, 11, 16, 13, 0), utc))
        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(prefs, epoch(2026, 11, 16, 17, 0), utc))
    }

    @Test
    fun `full-day schedule when start equals end covers 24h cycle and resets override at boundary`() {
        val prefs = NightModePrefs(enabled = true, startMinuteOfDay = 23 * 60 + 30, endMinuteOfDay = 23 * 60 + 30)
        val cycle1 = ActiveNightWindow(
            startEpochMillis = epoch(2026, 11, 15, 23, 30),
            endEpochMillis = epoch(2026, 11, 16, 23, 30),
        )
        val cycle2 = ActiveNightWindow(
            startEpochMillis = epoch(2026, 11, 16, 23, 30),
            endEpochMillis = epoch(2026, 11, 17, 23, 30),
        )

        assertEquals(NightWindowStatus.Active(cycle1), evaluateNightMode(prefs, epoch(2026, 11, 16, 20, 0), utc))
        assertEquals(NightWindowStatus.Active(cycle2), evaluateNightMode(prefs, epoch(2026, 11, 16, 23, 30), utc))

        val overridden = prefs.copy(overrideUntilEpochMillis = cycle1.endEpochMillis)
        assertEquals(NightWindowStatus.Overridden(cycle1), evaluateNightMode(overridden, epoch(2026, 11, 16, 20, 0), utc))
        assertEquals(NightWindowStatus.Active(cycle2), evaluateNightMode(overridden, epoch(2026, 11, 16, 23, 30), utc))
    }

    @Test
    fun `yesterday override does not override tonight window`() {
        val yesterdayWindow = ActiveNightWindow(
            startEpochMillis = epoch(2026, 11, 15, 23, 30),
            endEpochMillis = epoch(2026, 11, 16, 6, 0),
        )
        val tonightWindow = ActiveNightWindow(
            startEpochMillis = epoch(2026, 11, 16, 23, 30),
            endEpochMillis = epoch(2026, 11, 17, 6, 0),
        )
        val prefs = NightModePrefs(
            enabled = true,
            startMinuteOfDay = 23 * 60 + 30,
            endMinuteOfDay = 6 * 60,
            overrideUntilEpochMillis = yesterdayWindow.endEpochMillis,
        )

        // During yesterday's window -> Overridden
        assertEquals(
            NightWindowStatus.Overridden(yesterdayWindow),
            evaluateNightMode(prefs, epoch(2026, 11, 16, 1, 0), utc),
        )
        // Between windows -> Inactive
        assertEquals(
            NightWindowStatus.Inactive,
            evaluateNightMode(prefs, epoch(2026, 11, 16, 12, 0), utc),
        )
        // During tonight's window -> Active (expired override does not carry over)
        assertEquals(
            NightWindowStatus.Active(tonightWindow),
            evaluateNightMode(prefs, epoch(2026, 11, 16, 23, 30), utc),
        )
        assertEquals(
            NightWindowStatus.Active(tonightWindow),
            evaluateNightMode(prefs, epoch(2026, 11, 17, 3, 0), utc),
        )
    }

    @Test
    fun `override stays active until overrideUntil even if window end is extended`() {
        val originalEnd = epoch(2026, 11, 17, 6, 0)
        val extendedWindow = ActiveNightWindow(
            startEpochMillis = epoch(2026, 11, 16, 23, 30),
            endEpochMillis = epoch(2026, 11, 17, 8, 0),
        )
        val prefs = NightModePrefs(
            enabled = true,
            startMinuteOfDay = 23 * 60 + 30,
            endMinuteOfDay = 8 * 60,
            overrideUntilEpochMillis = originalEnd,
        )

        assertEquals(
            NightWindowStatus.Overridden(extendedWindow),
            evaluateNightMode(prefs, epoch(2026, 11, 17, 5, 0), utc),
        )
        assertEquals(
            NightWindowStatus.Active(extendedWindow),
            evaluateNightMode(prefs, epoch(2026, 11, 17, 6, 0), utc),
        )
    }

    @Test
    fun `dst transitions compute exact zone wall-clock boundaries`() {
        val ny = ZoneId.of("America/New_York")
        val prefs = NightModePrefs(enabled = true, startMinuteOfDay = 23 * 60 + 30, endMinuteOfDay = 6 * 60)

        // Spring forward on 2026-03-08: 23:30 to 06:00 is 5h30m
        val springStatus = evaluateNightMode(prefs, epoch(2026, 3, 8, 3, 30, zone = ny), ny)
        assertTrue(springStatus is NightWindowStatus.Active)
        val springWindow = (springStatus as NightWindowStatus.Active).window
        assertEquals(5 * 60 + 30L, (springWindow.endEpochMillis - springWindow.startEpochMillis) / 60_000L)

        // Fall back on 2026-11-01: 23:30 to 06:00 is 7h30m
        val fallStatus = evaluateNightMode(prefs, epoch(2026, 11, 1, 3, 30, zone = ny), ny)
        assertTrue(fallStatus is NightWindowStatus.Active)
        val fallWindow = (fallStatus as NightWindowStatus.Active).window
        assertEquals(7 * 60 + 30L, (fallWindow.endEpochMillis - fallWindow.startEpochMillis) / 60_000L)
    }

    @Test
    fun `allowedMids reflects allowedUps`() {
        val prefs = NightModePrefs(
            allowedUps = listOf(
                WhitelistedUp(101L, "Alpha"),
                WhitelistedUp(202L, "Beta"),
            ),
        )
        assertEquals(setOf(101L, 202L), prefs.allowedMids)
    }

    @Test
    fun `formatMinuteOfDay and formatNightRange clamp bounds and use en-dash`() {
        assertEquals("00:00", formatMinuteOfDay(-5))
        assertEquals("00:00", formatMinuteOfDay(0))
        assertEquals("23:30", formatMinuteOfDay(23 * 60 + 30))
        assertEquals("23:59", formatMinuteOfDay(1439))
        assertEquals("23:59", formatMinuteOfDay(2000))

        val range = formatNightRange(23 * 60 + 30, 6 * 60)
        assertEquals("23:30\u201306:00", range)
        assertFalse(range.contains('\u00B7'))
        assertFalse(range.contains('\u2022'))
    }
}
