package dev.bilby.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

const val DEFAULT_NIGHT_START_MINUTE: Int = 23 * 60 + 30
const val DEFAULT_NIGHT_END_MINUTE: Int = 6 * 60
internal const val MINUTES_PER_DAY: Int = 24 * 60

/** 夜间模式下允许访问的一位 UP 主。 */
data class WhitelistedUp(
    val mid: Long,
    val name: String,
    val faceUrl: String = "",
)

/**
 * 夜间模式配置与当夜临时退出截止时间。
 *
 * [overrideUntilEpochMillis] 记的是那一次生效窗口的结束时间戳，而不是一个"今夜暂停"布尔：
 * 下一轮窗口到来时，`overrideUntilEpochMillis <= newWindow.startEpochMillis`，旧退出自动失效。
 */
data class NightModePrefs(
    val enabled: Boolean = false,
    val startMinuteOfDay: Int = DEFAULT_NIGHT_START_MINUTE,
    val endMinuteOfDay: Int = DEFAULT_NIGHT_END_MINUTE,
    val allowedUps: List<WhitelistedUp> = emptyList(),
    val overrideUntilEpochMillis: Long = 0L,
) {
    val allowedMids: Set<Long>
        get() = allowedUps.mapTo(HashSet()) { it.mid }
}

/** 一次正在进行（或被临时退出暂停）的夜间窗口，左闭右开 `[startEpochMillis, endEpochMillis)`。 */
data class ActiveNightWindow(
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

sealed interface NightWindowStatus {
    data object Inactive : NightWindowStatus
    data class Overridden(val window: ActiveNightWindow) : NightWindowStatus
    data class Active(val window: ActiveNightWindow) : NightWindowStatus
}

/**
 * 按 [zoneId] 的墙上时间判定当前是否落在夜间窗口内，以及是否处于本轮临时退出状态。
 */
fun evaluateNightMode(
    prefs: NightModePrefs,
    nowEpochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): NightWindowStatus {
    if (!prefs.enabled) return NightWindowStatus.Inactive
    val startMinute = prefs.startMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    val endMinute = prefs.endMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    val today = Instant.ofEpochMilli(nowEpochMillis).atZone(zoneId).toLocalDate()

    val window: ActiveNightWindow = when {
        startMinute == endMinute -> {
            val todayBoundary = toEpochMillis(today, startMinute, zoneId)
            if (nowEpochMillis >= todayBoundary) {
                ActiveNightWindow(todayBoundary, toEpochMillis(today.plusDays(1), endMinute, zoneId))
            } else {
                ActiveNightWindow(toEpochMillis(today.minusDays(1), startMinute, zoneId), todayBoundary)
            }
        }

        startMinute < endMinute -> {
            val start = toEpochMillis(today, startMinute, zoneId)
            val end = toEpochMillis(today, endMinute, zoneId)
            if (nowEpochMillis in start until end) ActiveNightWindow(start, end) else return NightWindowStatus.Inactive
        }

        else -> {
            val tonightStart = toEpochMillis(today, startMinute, zoneId)
            val tomorrowEnd = toEpochMillis(today.plusDays(1), endMinute, zoneId)
            if (nowEpochMillis in tonightStart until tomorrowEnd) {
                ActiveNightWindow(tonightStart, tomorrowEnd)
            } else {
                val yesterdayStart = toEpochMillis(today.minusDays(1), startMinute, zoneId)
                val todayEnd = toEpochMillis(today, endMinute, zoneId)
                if (nowEpochMillis in yesterdayStart until todayEnd) {
                    ActiveNightWindow(yesterdayStart, todayEnd)
                } else {
                    return NightWindowStatus.Inactive
                }
            }
        }
    }

    // 仅当覆盖截止时刻落在当前窗口起点之后时，它才属于本轮（或跨过本轮）：
    // 昨日窗口结束时的时间戳不会大于今日窗口起点，从而自动让今晚恢复生效。
    val overridden = prefs.overrideUntilEpochMillis > window.startEpochMillis &&
        (prefs.overrideUntilEpochMillis >= window.endEpochMillis || nowEpochMillis < prefs.overrideUntilEpochMillis)
    return if (overridden) {
        NightWindowStatus.Overridden(window)
    } else {
        NightWindowStatus.Active(window)
    }
}

fun formatMinuteOfDay(minuteOfDay: Int): String {
    val clamped = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    return "%02d:%02d".format(Locale.US, clamped / 60, clamped % 60)
}

fun formatNightRange(startMinute: Int, endMinute: Int): String =
    "${formatMinuteOfDay(startMinute)}\u2013${formatMinuteOfDay(endMinute)}"

private fun toEpochMillis(date: LocalDate, minuteOfDay: Int, zoneId: ZoneId): Long {
    val clamped = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    val time = LocalTime.of(clamped / 60, clamped % 60)
    return date.atTime(time).atZone(zoneId).toInstant().toEpochMilli()
}
