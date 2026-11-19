package dev.bilby.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeTest {

    private val utc = ZoneId.of("UTC")

    private fun epoch(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int = 0,
        millis: Int = 0,
        zone: ZoneId = utc,
    ): Long = LocalDateTime.of(year, month, day, hour, minute, second, millis * 1_000_000)
        .atZone(zone)
        .toInstant()
        .toEpochMilli()

    private fun singleRulePrefs(
        enabled: Boolean = true,
        startMinuteOfDay: Int = 23 * 60 + 30,
        endMinuteOfDay: Int = 6 * 60,
        daysOfWeek: Set<Int> = emptySet(),
        allowedUps: List<WhitelistedUp> = emptyList(),
        overrideUntilEpochMillis: Long = 0L,
        manualRuleId: String? = null,
        manualUntilEpochMillis: Long = 0L,
        ruleId: String = DEFAULT_SCHEDULE_RULE_ID,
        ruleName: String = DEFAULT_LEGACY_RULE_NAME,
    ): NightModePrefs = NightModePrefs(
        enabled = enabled,
        rules = listOf(
            ScheduleRule(
                id = ruleId,
                name = ruleName,
                enabled = true,
                startMinuteOfDay = startMinuteOfDay,
                endMinuteOfDay = endMinuteOfDay,
                daysOfWeek = daysOfWeek,
                allowedUps = allowedUps,
            ),
        ),
        overrideUntilEpochMillis = overrideUntilEpochMillis,
        manualRuleId = manualRuleId,
        manualUntilEpochMillis = manualUntilEpochMillis,
    )

    @Test
    fun `disabled prefs always return Inactive`() {
        val prefs = singleRulePrefs(
            enabled = false,
            startMinuteOfDay = 23 * 60 + 30,
            endMinuteOfDay = 6 * 60,
        )
        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(prefs, epoch(2026, 11, 16, 23, 45), utc))
        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(prefs, epoch(2026, 11, 17, 2, 0), utc))
    }

    @Test
    fun `cross-midnight schedule evaluates half-open window accurately`() {
        val prefs = singleRulePrefs(enabled = true, startMinuteOfDay = 23 * 60 + 30, endMinuteOfDay = 6 * 60)
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
        val atStart = evaluateNightMode(prefs, epoch(2026, 11, 16, 23, 30, 0, 0), utc) as NightWindowStatus.Active
        assertEquals(expectedWindow, atStart.window)
        assertFalse(atStart.manual)
        // After midnight -> Active with same window
        val afterMidnight = evaluateNightMode(prefs, epoch(2026, 11, 17, 2, 15), utc) as NightWindowStatus.Active
        assertEquals(expectedWindow, afterMidnight.window)
        // Exact end -> Inactive (half-open)
        assertEquals(
            NightWindowStatus.Inactive,
            evaluateNightMode(prefs, epoch(2026, 11, 17, 6, 0, 0, 0), utc),
        )
    }

    @Test
    fun `same-day schedule evaluates half-open window accurately`() {
        val prefs = singleRulePrefs(enabled = true, startMinuteOfDay = 9 * 60, endMinuteOfDay = 17 * 60)
        val expectedWindow = ActiveNightWindow(
            startEpochMillis = epoch(2026, 11, 16, 9, 0),
            endEpochMillis = epoch(2026, 11, 16, 17, 0),
        )

        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(prefs, epoch(2026, 11, 16, 8, 59, 59, 999), utc))
        assertEquals(expectedWindow, (evaluateNightMode(prefs, epoch(2026, 11, 16, 9, 0), utc) as NightWindowStatus.Active).window)
        assertEquals(expectedWindow, (evaluateNightMode(prefs, epoch(2026, 11, 16, 13, 0), utc) as NightWindowStatus.Active).window)
        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(prefs, epoch(2026, 11, 16, 17, 0), utc))
    }

    @Test
    fun `overnight weekday rule anchors to start day`() {
        // 2026-11-20 is Friday (ISO day 5), 2026-11-21 is Saturday (ISO day 6)
        val fridayOnly = singleRulePrefs(
            enabled = true,
            startMinuteOfDay = 23 * 60,
            endMinuteOfDay = 7 * 60,
            daysOfWeek = setOf(5),
        )
        val expectedWindow = ActiveNightWindow(
            startEpochMillis = epoch(2026, 11, 20, 23, 0),
            endEpochMillis = epoch(2026, 11, 21, 7, 0),
        )

        // Friday 23:30 -> Active
        assertEquals(
            expectedWindow,
            (evaluateNightMode(fridayOnly, epoch(2026, 11, 20, 23, 30), utc) as NightWindowStatus.Active).window,
        )
        // Saturday 02:00 (still anchored to Friday) -> Active
        assertEquals(
            expectedWindow,
            (evaluateNightMode(fridayOnly, epoch(2026, 11, 21, 2, 0), utc) as NightWindowStatus.Active).window,
        )
        // Saturday 07:00 -> Inactive
        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(fridayOnly, epoch(2026, 11, 21, 7, 0), utc))
        // Saturday 23:30 -> Inactive (Saturday is not in daysOfWeek)
        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(fridayOnly, epoch(2026, 11, 21, 23, 30), utc))
        // Sunday 02:00 -> Inactive (anchored to Saturday)
        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(fridayOnly, epoch(2026, 11, 22, 2, 0), utc))
    }

    @Test
    fun `same-day weekday rule filters by ISO day of week`() {
        // 2026-11-16 is Monday (1), 2026-11-17 is Tuesday (2), 2026-11-18 is Wednesday (3)
        val mwf = singleRulePrefs(
            enabled = true,
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60,
            daysOfWeek = setOf(1, 3, 5),
        )
        assertTrue(evaluateNightMode(mwf, epoch(2026, 11, 16, 12, 0), utc) is NightWindowStatus.Active)
        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(mwf, epoch(2026, 11, 17, 12, 0), utc))
        assertTrue(evaluateNightMode(mwf, epoch(2026, 11, 18, 12, 0), utc) is NightWindowStatus.Active)
    }

    @Test
    fun `overlapping rules resolve as ordered union deduplicated by mid`() {
        val upA = WhitelistedUp(101L, "Alpha")
        val upB = WhitelistedUp(202L, "Beta")
        val upBDup = WhitelistedUp(202L, "Beta-2")
        val upC = WhitelistedUp(303L, "Gamma")
        val upDisabled = WhitelistedUp(404L, "Disabled")

        val rule1 = ScheduleRule(
            id = "r1",
            name = "夜间",
            enabled = true,
            startMinuteOfDay = 22 * 60,
            endMinuteOfDay = 6 * 60,
            allowedUps = listOf(upA, upB),
        )
        val rule2 = ScheduleRule(
            id = "r2",
            name = "深夜",
            enabled = true,
            startMinuteOfDay = 23 * 60,
            endMinuteOfDay = 7 * 60,
            allowedUps = listOf(upBDup, upC),
        )
        val rule3Disabled = ScheduleRule(
            id = "r3",
            name = "停用",
            enabled = false,
            startMinuteOfDay = 22 * 60,
            endMinuteOfDay = 8 * 60,
            allowedUps = listOf(upDisabled),
        )
        val prefs = NightModePrefs(
            enabled = true,
            rules = listOf(rule1, rule2, rule3Disabled),
        )

        // At 22:30 only rule1 is active
        val onlyR1 = evaluateNightMode(prefs, epoch(2026, 11, 16, 22, 30), utc) as NightWindowStatus.Active
        assertEquals(listOf("r1"), onlyR1.activeRules.map { it.id })
        assertEquals(listOf(upA, upB), onlyR1.allowedUps)

        // At 23:30 both rule1 and rule2 are active -> union [A, B, C], window [22:00, 07:00)
        val both = evaluateNightMode(prefs, epoch(2026, 11, 16, 23, 30), utc) as NightWindowStatus.Active
        assertEquals(listOf("r1", "r2"), both.activeRules.map { it.id })
        assertEquals(listOf(upA, upB, upC), both.allowedUps)
        assertEquals(
            ActiveNightWindow(epoch(2026, 11, 16, 22, 0), epoch(2026, 11, 17, 7, 0)),
            both.window,
        )

        // At 06:30 only rule2 is active
        val onlyR2 = evaluateNightMode(prefs, epoch(2026, 11, 17, 6, 30), utc) as NightWindowStatus.Active
        assertEquals(listOf("r2"), onlyR2.activeRules.map { it.id })
        assertEquals(listOf(upBDup, upC), onlyR2.allowedUps)
    }

    @Test
    fun `temporary exit covers combined overlapping span until last overlapping rule ends`() {
        val ruleA = ScheduleRule(
            id = "a",
            name = "晚间",
            enabled = true,
            startMinuteOfDay = 22 * 60,
            endMinuteOfDay = 2 * 60,
        )
        val ruleB = ScheduleRule(
            id = "b",
            name = "凌晨",
            enabled = true,
            startMinuteOfDay = 1 * 60,
            endMinuteOfDay = 7 * 60,
        )
        val prefs = NightModePrefs(enabled = true, rules = listOf(ruleA, ruleB))

        // Exit at 23:00 (only ruleA covers now, but ruleB overlaps [01:00, 02:00)) -> span ends at 07:00
        val exitUntil = computeTempExitUntilEpochMillis(prefs, epoch(2026, 11, 16, 23, 0), utc)
        assertEquals(epoch(2026, 11, 17, 7, 0), exitUntil)

        val overridden = prefs.copy(overrideUntilEpochMillis = exitUntil!!)
        assertTrue(evaluateNightMode(overridden, epoch(2026, 11, 16, 23, 30), utc) is NightWindowStatus.Overridden)
        assertTrue(evaluateNightMode(overridden, epoch(2026, 11, 17, 1, 30), utc) is NightWindowStatus.Overridden)
        assertTrue(evaluateNightMode(overridden, epoch(2026, 11, 17, 5, 0), utc) is NightWindowStatus.Overridden)
        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(overridden, epoch(2026, 11, 17, 7, 0), utc))
        // Next night at 22:00 -> Active again
        assertTrue(evaluateNightMode(overridden, epoch(2026, 11, 17, 22, 0), utc) is NightWindowStatus.Active)
    }

    @Test
    fun `full-day schedule when start equals end covers 24h cycle and resets override at boundary`() {
        val prefs = singleRulePrefs(enabled = true, startMinuteOfDay = 23 * 60 + 30, endMinuteOfDay = 23 * 60 + 30)
        val cycle1 = ActiveNightWindow(
            startEpochMillis = epoch(2026, 11, 15, 23, 30),
            endEpochMillis = epoch(2026, 11, 16, 23, 30),
        )
        val cycle2 = ActiveNightWindow(
            startEpochMillis = epoch(2026, 11, 16, 23, 30),
            endEpochMillis = epoch(2026, 11, 17, 23, 30),
        )

        assertEquals(cycle1, (evaluateNightMode(prefs, epoch(2026, 11, 16, 20, 0), utc) as NightWindowStatus.Active).window)
        assertEquals(cycle2, (evaluateNightMode(prefs, epoch(2026, 11, 16, 23, 30), utc) as NightWindowStatus.Active).window)

        val exitUntil = computeTempExitUntilEpochMillis(prefs, epoch(2026, 11, 16, 20, 0), utc)
        assertEquals(cycle1.endEpochMillis, exitUntil)

        val overridden = prefs.copy(overrideUntilEpochMillis = cycle1.endEpochMillis)
        assertEquals(cycle1, (evaluateNightMode(overridden, epoch(2026, 11, 16, 20, 0), utc) as NightWindowStatus.Overridden).window)
        assertEquals(cycle2, (evaluateNightMode(overridden, epoch(2026, 11, 16, 23, 30), utc) as NightWindowStatus.Active).window)
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
        val prefs = singleRulePrefs(
            enabled = true,
            startMinuteOfDay = 23 * 60 + 30,
            endMinuteOfDay = 6 * 60,
            overrideUntilEpochMillis = yesterdayWindow.endEpochMillis,
        )

        assertEquals(yesterdayWindow, (evaluateNightMode(prefs, epoch(2026, 11, 16, 1, 0), utc) as NightWindowStatus.Overridden).window)
        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(prefs, epoch(2026, 11, 16, 12, 0), utc))
        assertEquals(tonightWindow, (evaluateNightMode(prefs, epoch(2026, 11, 16, 23, 30), utc) as NightWindowStatus.Active).window)
        assertEquals(tonightWindow, (evaluateNightMode(prefs, epoch(2026, 11, 17, 3, 0), utc) as NightWindowStatus.Active).window)
    }

    @Test
    fun `override stays active until overrideUntil even if window end is extended`() {
        val originalEnd = epoch(2026, 11, 17, 6, 0)
        val extendedWindow = ActiveNightWindow(
            startEpochMillis = epoch(2026, 11, 16, 23, 30),
            endEpochMillis = epoch(2026, 11, 17, 8, 0),
        )
        val prefs = singleRulePrefs(
            enabled = true,
            startMinuteOfDay = 23 * 60 + 30,
            endMinuteOfDay = 8 * 60,
            overrideUntilEpochMillis = originalEnd,
        )

        assertEquals(extendedWindow, (evaluateNightMode(prefs, epoch(2026, 11, 17, 5, 0), utc) as NightWindowStatus.Overridden).window)
        assertEquals(extendedWindow, (evaluateNightMode(prefs, epoch(2026, 11, 17, 6, 0), utc) as NightWindowStatus.Active).window)
    }

    @Test
    fun `dst transitions compute exact zone wall-clock boundaries`() {
        val ny = ZoneId.of("America/New_York")
        val prefs = singleRulePrefs(enabled = true, startMinuteOfDay = 23 * 60 + 30, endMinuteOfDay = 6 * 60)

        val springStatus = evaluateNightMode(prefs, epoch(2026, 3, 8, 3, 30, zone = ny), ny) as NightWindowStatus.Active
        assertEquals(5 * 60 + 30L, (springStatus.window.endEpochMillis - springStatus.window.startEpochMillis) / 60_000L)

        val fallStatus = evaluateNightMode(prefs, epoch(2026, 11, 1, 3, 30, zone = ny), ny) as NightWindowStatus.Active
        assertEquals(7 * 60 + 30L, (fallStatus.window.endEpochMillis - fallStatus.window.startEpochMillis) / 60_000L)
    }

    @Test
    fun `computeNextNightEndEpochMillis resolves today before endMinute and tomorrow at or after endMinute`() {
        val endMinute = 6 * 60
        assertEquals(
            epoch(2026, 11, 17, 6, 0),
            computeNextNightEndEpochMillis(endMinute, epoch(2026, 11, 17, 2, 15), utc),
        )
        assertEquals(
            epoch(2026, 11, 18, 6, 0),
            computeNextNightEndEpochMillis(endMinute, epoch(2026, 11, 17, 6, 0), utc),
        )
        assertEquals(
            epoch(2026, 11, 17, 6, 0),
            computeNextNightEndEpochMillis(endMinute, epoch(2026, 11, 16, 22, 45), utc),
        )
    }

    @Test
    fun `manual engagement activates specific rule and auto-expires at endMinute`() {
        val engagedAt = epoch(2026, 11, 16, 22, 45)
        val nextEnd = computeNextNightEndEpochMillis(6 * 60, engagedAt, utc)
        val up1 = WhitelistedUp(1L, "One")
        val up2 = WhitelistedUp(2L, "Two")
        val prefs = NightModePrefs(
            enabled = false,
            rules = listOf(
                ScheduleRule(id = "work", name = "工作", startMinuteOfDay = 9 * 60, endMinuteOfDay = 18 * 60, allowedUps = listOf(up1)),
                ScheduleRule(id = "night", name = "夜间", startMinuteOfDay = 23 * 60 + 30, endMinuteOfDay = 6 * 60, allowedUps = listOf(up2)),
            ),
            manualRuleId = "night",
            manualUntilEpochMillis = nextEnd,
        )

        val activeBeforeMidnight = evaluateNightMode(prefs, engagedAt, utc) as NightWindowStatus.Active
        assertTrue(activeBeforeMidnight.manual)
        assertEquals(ActiveNightWindow(engagedAt, nextEnd), activeBeforeMidnight.window)
        assertEquals(listOf("night"), activeBeforeMidnight.activeRules.map { it.id })
        assertEquals(listOf(up2), activeBeforeMidnight.allowedUps)

        val activeAfterMidnight = evaluateNightMode(prefs, epoch(2026, 11, 17, 1, 30), utc) as NightWindowStatus.Active
        assertTrue(activeAfterMidnight.manual)

        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(prefs, nextEnd, utc))
    }

    @Test
    fun `manual engagement before scheduled window merges into scheduled window without gap`() {
        val engagedAt = epoch(2026, 11, 16, 22, 45)
        val endMillis = epoch(2026, 11, 17, 6, 0)
        val prefs = singleRulePrefs(
            enabled = true,
            startMinuteOfDay = 23 * 60 + 30,
            endMinuteOfDay = 6 * 60,
            manualRuleId = DEFAULT_SCHEDULE_RULE_ID,
            manualUntilEpochMillis = endMillis,
        )

        val earlyStatus = evaluateNightMode(prefs, engagedAt, utc) as NightWindowStatus.Active
        assertTrue(earlyStatus.manual)
        assertEquals(endMillis, earlyStatus.window.endEpochMillis)

        val scheduledStatus = evaluateNightMode(prefs, epoch(2026, 11, 16, 23, 45), utc) as NightWindowStatus.Active
        assertFalse(scheduledStatus.manual)
        assertEquals(epoch(2026, 11, 16, 23, 30), scheduledStatus.window.startEpochMillis)
        assertEquals(endMillis, scheduledStatus.window.endEpochMillis)
    }

    @Test
    fun `relaxesActiveRestriction gates only changes that relax currently active restriction`() {
        val up1 = WhitelistedUp(1L, "A")
        val up2 = WhitelistedUp(2L, "B")
        val activeRule = ScheduleRule(
            id = "night",
            name = "夜间",
            enabled = true,
            startMinuteOfDay = 23 * 60,
            endMinuteOfDay = 7 * 60,
            allowedUps = listOf(up1),
        )
        val inactiveRule = ScheduleRule(
            id = "work",
            name = "工作",
            enabled = true,
            startMinuteOfDay = 9 * 60,
            endMinuteOfDay = 17 * 60,
            allowedUps = listOf(up2),
        )
        val prefs = NightModePrefs(enabled = true, rules = listOf(activeRule, inactiveRule))
        val nowInside = epoch(2026, 11, 17, 1, 0)
        val nowOutside = epoch(2026, 11, 17, 19, 0)

        // Outside active window -> never gated
        assertFalse(relaxesActiveRestriction(prefs, prefs.copy(enabled = false), nowOutside, utc))

        // Inside active window:
        // Disabling master -> gated
        assertTrue(relaxesActiveRestriction(prefs, prefs.copy(enabled = false), nowInside, utc))
        // Disabling active rule -> gated
        assertTrue(relaxesActiveRestriction(prefs, prefs.copy(rules = listOf(activeRule.copy(enabled = false), inactiveRule)), nowInside, utc))
        // Deleting active rule -> gated
        assertTrue(relaxesActiveRestriction(prefs, prefs.copy(rules = listOf(inactiveRule)), nowInside, utc))
        // Shortening active rule -> gated
        assertTrue(relaxesActiveRestriction(prefs, prefs.copy(rules = listOf(activeRule.copy(endMinuteOfDay = 5 * 60), inactiveRule)), nowInside, utc))
        // Adding UP to non-empty active rule -> gated
        assertTrue(relaxesActiveRestriction(prefs, prefs.copy(rules = listOf(activeRule.copy(allowedUps = listOf(up1, up2)), inactiveRule)), nowInside, utc))

        // Editing inactive rule -> NOT gated
        assertFalse(relaxesActiveRestriction(prefs, prefs.copy(rules = listOf(activeRule, inactiveRule.copy(enabled = false))), nowInside, utc))
        // Adding a new rule -> NOT gated
        val brandNew = ScheduleRule(id = "noon", startMinuteOfDay = 12 * 60, endMinuteOfDay = 13 * 60)
        assertFalse(relaxesActiveRestriction(prefs, prefs.copy(rules = prefs.rules + brandNew), nowInside, utc))
        // Extending active rule -> NOT gated
        assertFalse(relaxesActiveRestriction(prefs, prefs.copy(rules = listOf(activeRule.copy(endMinuteOfDay = 8 * 60), inactiveRule)), nowInside, utc))
        // Removing UP from active rule -> NOT gated
        assertFalse(relaxesActiveRestriction(prefs, prefs.copy(rules = listOf(activeRule.copy(allowedUps = emptyList()), inactiveRule)), nowInside, utc))
    }

    @Test
    fun `legacy night_mode keys migrate losslessly into default rule and stay deleted once removed`() = runTest {
        val legacyData = mutablePreferencesOf(
            booleanPreferencesKey("night_mode_enabled") to true,
            intPreferencesKey("night_mode_start_minute") to (22 * 60 + 30),
            intPreferencesKey("night_mode_end_minute") to (6 * 60 + 30),
            stringPreferencesKey("night_mode_allowed_ups") to """[{"mid":101,"name":"Alpha","faceUrl":"http://i0.hdslb.com/a.jpg"}]""",
            longPreferencesKey("night_mode_override_until") to 123456L,
            longPreferencesKey("night_mode_manual_until_epoch_ms") to 789012L,
        )
        val settings = SettingsStore(inMemoryPreferencesStore(legacyData))
        val migrated = settings.nightModePrefs.first()

        assertTrue(migrated.enabled)
        assertEquals(1, migrated.rules.size)
        val rule = migrated.rules.first()
        assertEquals(DEFAULT_SCHEDULE_RULE_ID, rule.id)
        assertEquals(DEFAULT_LEGACY_RULE_NAME, rule.name)
        assertTrue(rule.enabled)
        assertEquals(22 * 60 + 30, rule.startMinuteOfDay)
        assertEquals(6 * 60 + 30, rule.endMinuteOfDay)
        assertTrue(rule.daysOfWeek.isEmpty())
        assertEquals(listOf(WhitelistedUp(101L, "Alpha", "https://i0.hdslb.com/a.jpg")), rule.allowedUps)
        assertEquals(123456L, migrated.overrideUntilEpochMillis)
        assertEquals(789012L, migrated.manualUntilEpochMillis)
        assertEquals(DEFAULT_SCHEDULE_RULE_ID, migrated.manualRuleId)

        // Deleting the migrated rule persists empty schedule_mode_rules and does not resurrect legacy data
        settings.deleteScheduleRule(DEFAULT_SCHEDULE_RULE_ID)
        val afterDelete = settings.nightModePrefs.first()
        assertTrue(afterDelete.rules.isEmpty())
        assertNull(afterDelete.manualRuleId)
        assertEquals(0L, afterDelete.manualUntilEpochMillis)
    }

    @Test
    fun `fresh store without legacy keys starts with empty rules`() = runTest {
        val settings = SettingsStore(inMemoryPreferencesStore())
        val prefs = settings.nightModePrefs.first()
        assertFalse(prefs.enabled)
        assertTrue(prefs.rules.isEmpty())
    }

    @Test
    fun `SettingsStore multi-rule upsert, manual engage, temporary exit, and LLM normalization`() = runTest {
        val zone = ZoneId.systemDefault()
        val settings = SettingsStore(inMemoryPreferencesStore())

        val nightRule = ScheduleRule(
            id = "night",
            name = "夜间",
            enabled = true,
            startMinuteOfDay = 23 * 60 + 30,
            endMinuteOfDay = 6 * 60,
            allowedUps = listOf(WhitelistedUp(101L, "Alpha")),
        )
        settings.upsertScheduleRule(nightRule)

        // 1) Manual engage with auto-schedule disabled -> Active(manual=true) -> temp exit -> Inactive
        val nowOutside = epoch(2026, 11, 16, 22, 0, zone = zone)
        settings.engageNightModeManually("night", nowOutside)
        val manualPrefs = settings.nightModePrefs.first()
        val manualStatus = evaluateNightMode(manualPrefs, nowOutside, zone)
        assertTrue(manualStatus is NightWindowStatus.Active && manualStatus.manual)

        // Updating rule endMinute while manual session is active updates manualUntilEpochMillis
        settings.upsertScheduleRule(nightRule.copy(endMinuteOfDay = 7 * 60), nowOutside)
        val updatedPrefs = settings.nightModePrefs.first()
        assertEquals(
            computeNextNightEndEpochMillis(7 * 60, nowOutside, zone),
            updatedPrefs.manualUntilEpochMillis,
        )

        settings.exitNightModeTemporarily(nowOutside)
        assertEquals(NightWindowStatus.Inactive, evaluateNightMode(settings.nightModePrefs.first(), nowOutside, zone))

        // 2) Scheduled window active -> temp exit -> Overridden -> manual engage -> Active again
        settings.setNightModeEnabled(true)
        val nowInside = epoch(2026, 11, 17, 1, 0, zone = zone)
        assertTrue(evaluateNightMode(settings.nightModePrefs.first(), nowInside, zone) is NightWindowStatus.Active)

        settings.exitNightModeTemporarily(nowInside)
        assertTrue(evaluateNightMode(settings.nightModePrefs.first(), nowInside, zone) is NightWindowStatus.Overridden)

        settings.engageNightModeManually("night", nowInside)
        val reEngaged = evaluateNightMode(settings.nightModePrefs.first(), nowInside, zone)
        assertTrue(reEngaged is NightWindowStatus.Active)

        // 3) LLM baseUrl normalization & model non-blank requirement
        settings.saveLlmConfig(
            LlmConfig(
                baseUrl = "generativelanguage.googleapis.com/v1beta/openai/chat/completions/",
                apiKey = " test-key ",
                model = "",
            ),
        )
        val unconfiguredModel = settings.llmConfig.first()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", unconfiguredModel.baseUrl)
        assertFalse(unconfiguredModel.isConfigured)

        settings.saveLlmConfig(unconfiguredModel.copy(model = "gemini-3-flash-preview"))
        val configured = settings.llmConfig.first()
        assertTrue(configured.isConfigured)
    }

    @Test
    fun `QueueContext NightMode decodes legacy JSON without ruleName and round-trips ruleName`() {
        val legacyJson = """{"type":"dev.bilby.data.QueueContext.NightMode","ups":[{"mid":101,"name":"Alpha"}],"selectedMid":101,"anchorPage":2}"""
        val decodedLegacy = decodeQueueContext(legacyJson) as QueueContext.NightMode
        assertEquals("", decodedLegacy.ruleName)
        assertEquals(101L, decodedLegacy.selectedMid)
        assertEquals(2, decodedLegacy.anchorPage)

        val named = QueueContext.NightMode(
            ups = listOf(WhitelistedUp(101L, "Alpha")),
            selectedMid = null,
            anchorPage = 1,
            ruleName = "专注",
        )
        val roundTripped = decodeQueueContext(encodeQueueContext(named)) as QueueContext.NightMode
        assertEquals("专注", roundTripped.ruleName)
    }

    @Test
    fun `formatMinuteOfDay, formatNightRange, and formatEpochTime clamp bounds and avoid middle dots`() {
        assertEquals("00:00", formatMinuteOfDay(-5))
        assertEquals("00:00", formatMinuteOfDay(0))
        assertEquals("23:30", formatMinuteOfDay(23 * 60 + 30))
        assertEquals("23:59", formatMinuteOfDay(1439))
        assertEquals("23:59", formatMinuteOfDay(2000))

        val range = formatNightRange(23 * 60 + 30, 6 * 60)
        assertEquals("23:30\u201306:00", range)
        assertFalse(range.contains('\u00B7'))
        assertFalse(range.contains('\u2022'))

        assertEquals("07:05", formatEpochTime(epoch(2026, 11, 17, 7, 5), utc))
    }

    private fun inMemoryPreferencesStore(initial: Preferences = emptyPreferences()): DataStore<Preferences> =
        object : DataStore<Preferences> {
            private val mutex = Mutex()
            private val state = MutableStateFlow(initial)
            override val data: Flow<Preferences> = state
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                mutex.withLock {
                    val updated = transform(state.value)
                    state.value = updated
                    updated
                }
        }
}
