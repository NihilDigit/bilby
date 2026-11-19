package dev.bilby.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.Serializable

const val DEFAULT_NIGHT_START_MINUTE: Int = 23 * 60 + 30
const val DEFAULT_NIGHT_END_MINUTE: Int = 6 * 60
const val DEFAULT_SCHEDULE_RULE_ID: String = "default"
const val DEFAULT_LEGACY_RULE_NAME: String = "夜间"
internal const val MINUTES_PER_DAY: Int = 24 * 60
private const val ONE_DAY_MILLIS: Long = 24L * 60L * 60L * 1000L

/** 定时白名单下允许访问的一位 UP 主。 */
@Serializable
data class WhitelistedUp(
    val mid: Long,
    val name: String,
    val faceUrl: String = "",
)

/**
 * 单条定时白名单时段规则。
 *
 * [daysOfWeek] 使用 ISO 星期编号 `1..7`（周一为 1，周日为 7）；空集合表示每天生效。
 * 跨午夜时段（[startMinuteOfDay] > [endMinuteOfDay]）的星期归属锚定于起始日。
 */
@Serializable
data class ScheduleRule(
    val id: String,
    val name: String = "",
    val enabled: Boolean = true,
    val startMinuteOfDay: Int = DEFAULT_NIGHT_START_MINUTE,
    val endMinuteOfDay: Int = DEFAULT_NIGHT_END_MINUTE,
    val daysOfWeek: Set<Int> = emptySet(),
    val allowedUps: List<WhitelistedUp> = emptyList(),
) {
    val allowedMids: Set<Long>
        get() = allowedUps.mapTo(HashSet()) { it.mid }

    fun normalized(): ScheduleRule = copy(
        name = name.trim(),
        startMinuteOfDay = startMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1),
        endMinuteOfDay = endMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1),
        daysOfWeek = daysOfWeek.filterTo(LinkedHashSet()) { it in 1..7 }.let { if (it.size == 7) emptySet() else it },
        allowedUps = allowedUps.distinctBy { it.mid },
    )
}

/**
 * 定时白名单配置、本轮临时退出截止时间与手动进入截止时间。
 *
 * [overrideUntilEpochMillis] 记的是那一次合并生效区间的结束时间戳，而不是一个"今夜暂停"布尔：
 * 下一轮窗口到来时，`overrideUntilEpochMillis <= newWindow.startEpochMillis`，旧退出自动失效。
 * [manualRuleId] 与 [manualUntilEpochMillis] 记的是手动立即进入的目标规则及其持续截止时间戳。
 */
data class NightModePrefs(
    val enabled: Boolean = false,
    val rules: List<ScheduleRule> = emptyList(),
    val overrideUntilEpochMillis: Long = 0L,
    val manualRuleId: String? = null,
    val manualUntilEpochMillis: Long = 0L,
) {
    fun ruleById(id: String?): ScheduleRule? =
        id?.let { target -> rules.firstOrNull { it.id == target } }
}

/** 一次正在进行（或被临时退出暂停）的生效窗口，左闭右开 `[startEpochMillis, endEpochMillis)`。 */
data class ActiveNightWindow(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

sealed interface NightWindowStatus {
    data object Inactive : NightWindowStatus
    data class Overridden(
        val window: ActiveNightWindow,
        val rules: List<ScheduleRule> = emptyList(),
    ) : NightWindowStatus

    data class Active(
        val window: ActiveNightWindow,
        val activeRules: List<ScheduleRule> = emptyList(),
        val allowedUps: List<WhitelistedUp> = emptyList(),
        val manual: Boolean = false,
    ) : NightWindowStatus {
        val allowedMids: Set<Long>
            get() = allowedUps.mapTo(HashSet()) { it.mid }
    }
}

/** 合并多条生效规则的白名单，按首次出现顺序以 `mid` 去重。 */
fun mergeAllowedUps(rules: List<ScheduleRule>): List<WhitelistedUp> {
    if (rules.isEmpty()) return emptyList()
    val seen = HashSet<Long>()
    val result = ArrayList<WhitelistedUp>()
    for (rule in rules) {
        for (up in rule.allowedUps) {
            if (seen.add(up.mid)) {
                result.add(up)
            }
        }
    }
    return result
}

/**
 * 计算从 [nowEpochMillis] 起下一个 [endMinuteOfDay] 时刻的时间戳（按 [zoneId] 墙上时间）。
 */
fun computeNextNightEndEpochMillis(
    endMinuteOfDay: Int,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long {
    val endMinute = endMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
    val todayEndMillis = toEpochMillis(today, endMinute, zoneId)
    return if (nowEpochMillis < todayEndMillis) {
        todayEndMillis
    } else {
        toEpochMillis(today.plusDays(1), endMinute, zoneId)
    }
}

private fun ScheduleRule.matchesAnchorDate(date: LocalDate): Boolean {
    val days = daysOfWeek
    if (days.isEmpty()) return true
    return date.dayOfWeek.value in days
}

private fun buildWindowForAnchorDate(
    rule: ScheduleRule,
    anchorDate: LocalDate,
    zoneId: ZoneId,
): ActiveNightWindow? {
    if (!rule.matchesAnchorDate(anchorDate)) return null
    val startMinute = rule.startMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    val endMinute = rule.endMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    val start = toEpochMillis(anchorDate, startMinute, zoneId)
    val endDate = if (startMinute < endMinute) anchorDate else anchorDate.plusDays(1)
    val end = toEpochMillis(endDate, endMinute, zoneId)
    return if (end > start) ActiveNightWindow(start, end) else null
}

internal fun findCoveringRuleWindow(
    rule: ScheduleRule,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): ActiveNightWindow? {
    val startMinute = rule.startMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    val endMinute = rule.endMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()

    return when {
        startMinute < endMinute -> {
            val w = buildWindowForAnchorDate(rule, today, zoneId) ?: return null
            if (nowEpochMillis in w.startEpochMillis until w.endEpochMillis) w else null
        }

        else -> {
            val tonight = buildWindowForAnchorDate(rule, today, zoneId)
            if (tonight != null && nowEpochMillis in tonight.startEpochMillis until tonight.endEpochMillis) {
                tonight
            } else {
                val yesterday = buildWindowForAnchorDate(rule, today.minusDays(1), zoneId)
                if (yesterday != null && nowEpochMillis in yesterday.startEpochMillis until yesterday.endEpochMillis) {
                    yesterday
                } else {
                    null
                }
            }
        }
    }
}

/**
 * 计算从 [nowEpochMillis] 所在生效窗口出发，所有严格重叠相连的启用规则合并后的结束时间戳。
 * 用于「临时退出」覆盖至整个合并生效区间结束，避免退出后立即落入另一条重叠规则。
 */
fun computeTempExitUntilEpochMillis(
    prefs: NightModePrefs,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long? {
    if (!prefs.enabled) return null
    val enabledRules = prefs.rules.filter { it.enabled }
    if (enabledRules.isEmpty()) return null

    val covering = enabledRules.mapNotNull { findCoveringRuleWindow(it, nowEpochMillis, zoneId) }
    if (covering.isEmpty()) return null

    val spanStart = covering.minOf { it.startEpochMillis }
    var spanEnd = covering.maxOf { it.endEpochMillis }

    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()
    val candidates = ArrayList<ActiveNightWindow>(enabledRules.size * 3)
    for (rule in enabledRules) {
        buildWindowForAnchorDate(rule, today.minusDays(1), zoneId)?.let(candidates::add)
        buildWindowForAnchorDate(rule, today, zoneId)?.let(candidates::add)
        buildWindowForAnchorDate(rule, today.plusDays(1), zoneId)?.let(candidates::add)
    }
    candidates.sortBy { it.startEpochMillis }

    val maxAnchorStart = spanStart + ONE_DAY_MILLIS
    var changed = true
    while (changed) {
        changed = false
        for (w in candidates) {
            if (w.startEpochMillis < spanEnd &&
                w.startEpochMillis < maxAnchorStart &&
                w.endEpochMillis > spanEnd
            ) {
                spanEnd = w.endEpochMillis
                changed = true
            }
        }
    }
    return spanEnd
}

/**
 * 按 [zoneId] 的墙上时间判定当前是否落在定时白名单窗口内，以及是否处于手动开启或本轮临时退出状态。
 *
 * 多条启用规则同时命中当前时刻时，取白名单并集（按规则顺序保序以 `mid` 去重）。
 */
fun evaluateNightMode(
    prefs: NightModePrefs,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): NightWindowStatus {
    val scheduledHits = if (prefs.enabled) {
        prefs.rules.mapNotNull { rule ->
            if (!rule.enabled) null
            else findCoveringRuleWindow(rule, nowEpochMillis, zoneId)?.let { rule to it }
        }
    } else {
        emptyList()
    }
    val scheduledRules = scheduledHits.map { it.first }
    val scheduledWindow = if (scheduledHits.isNotEmpty()) {
        ActiveNightWindow(
            startEpochMillis = scheduledHits.minOf { it.second.startEpochMillis },
            endEpochMillis = scheduledHits.maxOf { it.second.endEpochMillis },
        )
    } else {
        null
    }

    if (prefs.manualUntilEpochMillis > nowEpochMillis) {
        val manualRule = prefs.ruleById(prefs.manualRuleId) ?: prefs.rules.firstOrNull()
        val combinedRules = buildList {
            addAll(scheduledRules)
            if (manualRule != null && none { it.id == manualRule.id }) {
                add(manualRule)
            }
        }
        val effectiveEnd = maxOf(prefs.manualUntilEpochMillis, scheduledWindow?.endEpochMillis ?: 0L)
        return NightWindowStatus.Active(
            window = ActiveNightWindow(
                startEpochMillis = scheduledWindow?.startEpochMillis ?: nowEpochMillis,
                endEpochMillis = effectiveEnd,
            ),
            activeRules = combinedRules,
            allowedUps = mergeAllowedUps(combinedRules),
            manual = scheduledWindow == null || !prefs.enabled,
        )
    }

    val window = scheduledWindow ?: return NightWindowStatus.Inactive

    // 仅当覆盖截止时刻落在当前窗口起点之后时，它才属于本轮（或跨过本轮）：
    // 昨日窗口结束时的时间戳不会大于今日窗口起点，从而自动让今日恢复生效。
    val overridden = prefs.overrideUntilEpochMillis > window.startEpochMillis &&
        (prefs.overrideUntilEpochMillis >= window.endEpochMillis || nowEpochMillis < prefs.overrideUntilEpochMillis)
    return if (overridden) {
        NightWindowStatus.Overridden(window = window, rules = scheduledRules)
    } else {
        NightWindowStatus.Active(
            window = window,
            activeRules = scheduledRules,
            allowedUps = mergeAllowedUps(scheduledRules),
            manual = false,
        )
    }
}

/**
 * 判定从 [current] 变更为 [next] 是否会放宽当前正在生效的限制：
 * - 当前未生效时，任何修改均无需 30 秒长按守卫；
 * - 当前生效中若变更后不再生效、生效结束时间被提前、或在非空白名单基础上新增了原本不允许的 UP 主，则需要守卫。
 */
fun relaxesActiveRestriction(
    current: NightModePrefs,
    next: NightModePrefs,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Boolean {
    val curStatus = evaluateNightMode(current, nowEpochMillis, zoneId) as? NightWindowStatus.Active ?: return false
    val nextStatus = evaluateNightMode(next, nowEpochMillis, zoneId)
    if (nextStatus !is NightWindowStatus.Active) return true
    if (nextStatus.window.endEpochMillis < curStatus.window.endEpochMillis) return true
    if (curStatus.allowedUps.isNotEmpty() && nextStatus.allowedUps.any { it.mid !in curStatus.allowedMids }) {
        return true
    }
    return false
}

fun formatMinuteOfDay(minuteOfDay: Int): String {
    val clamped = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    return "%02d:%02d".format(Locale.US, clamped / 60, clamped % 60)
}

fun formatNightRange(startMinute: Int, endMinute: Int): String =
    "${formatMinuteOfDay(startMinute)}\u2013${formatMinuteOfDay(endMinute)}"

fun formatEpochTime(epochMillis: Long, zoneId: ZoneId = ZoneId.systemDefault()): String {
    val time = Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalTime()
    return "%02d:%02d".format(Locale.US, time.hour, time.minute)
}

private fun toEpochMillis(date: LocalDate, minuteOfDay: Int, zoneId: ZoneId): Long {
    val clamped = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    val time = LocalTime.of(clamped / 60, clamped % 60)
    return date.atTime(time).atZone(zoneId).toInstant().toEpochMilli()
}
