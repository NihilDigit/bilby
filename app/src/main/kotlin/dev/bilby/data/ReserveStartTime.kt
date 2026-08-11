package dev.bilby.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 从预约卡片的说明文字里解出开播/发布时刻。
 *
 * **接口没有时间戳字段。** 网页端 `additional.reserve` 只有 `title`、`rid`、`state`、
 * `reserve_total`、`desc1`/`desc2`/`desc3`、`button`;app 端的 protobuf `AdditionUP`
 * (PiliPlus `grpc/bilibili/app/dynamic/v2.pb.dart:1205-1283`)同样只有 `desc_text1` 这类
 * 展示串。带 `live_plan_start_time` 的是 `x/new-reserve/up/reserve/info`,那是 UP 主管理
 * 自己那场预约的接口("up/"),看别人的预约取不到。所以 `desc1` 这句话是唯一的来源。
 *
 * 因此这里只认能确定到分钟的几种写法,认不出就返回 null —— 调用方据此不给"加入日历"入口。
 * 猜一个时间比不给入口更糟:用户会以为已经记下了。
 *
 * **按东八区解析。** 拉动态时我们传的是 `timezone_offset=-480`,服务端就是按东八区把时刻
 * 渲染成这句话的;设备在别的时区时,按本地时区解会整体偏掉几个小时。
 */
private val ChinaZone: ZoneId = ZoneId.of("Asia/Shanghai")

/** `2026-08-23 19:00`,年份明写。 */
private val FullDate = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})\s*(\d{1,2}):(\d{2})""")

/** `08-23 19:00 直播`,没有年份。 */
private val MonthDay = Regex("""(?<!\d)(\d{1,2})-(\d{1,2})\s*(\d{1,2}):(\d{2})""")

/** `预计今天 18:00发布` / `预计明天 18:00发布`。 */
private val RelativeDay = Regex("""(今天|明天)\s*(\d{1,2}):(\d{2})""")

/**
 * @param now 用来推断年份与判断"今天/明天"。参数化只为让测试能给定"现在"。
 * @return 秒级时间戳,解不出返回 null。
 *
 * **已经过去的时刻照样返回。** 界面要用它区分"还没开始"和"已经开始了":前者给"加入日历",
 * 后者给一个点进直播间或视频的入口。判断放在渲染时按当前时间现算 —— 解析时定死的话,
 * 一页开着几小时之后那个答案就过期了。
 */
fun parseReserveStartTime(text: String, now: ZonedDateTime = ZonedDateTime.now(ChinaZone)): Long? {
    val china = now.withZoneSameInstant(ChinaZone)
    val parsed = parseFullDate(text)
        ?: parseRelativeDay(text, china)
        ?: parseMonthDay(text, china)
        ?: return null
    return parsed.toEpochSecond()
}

private fun parseFullDate(text: String): ZonedDateTime? = FullDate.find(text)?.let { m ->
    val (year, month, day, hour, minute) = m.destructured
    zoned(year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt())
}

private fun parseRelativeDay(text: String, now: ZonedDateTime): ZonedDateTime? = RelativeDay.find(text)?.let { m ->
    val (word, hour, minute) = m.destructured
    val date = if (word == "明天") now.toLocalDate().plusDays(1) else now.toLocalDate()
    zoned(date, hour.toInt(), minute.toInt())
}

/**
 * 没有年份的 `08-23 19:00`。**年份取离现在最近的那一个**,前后年都算进候选。
 *
 * 曾经写成"取还没到的那一年",真机上立刻错了:一场 `08-09 20:00` 的直播在 08-11 这天仍然
 * 挂在动态流里(播完了动态还在),那条规则把它推到了明年,于是"加入日历"会建出一条一年后的
 * 日程。预约的跨度是几天到几周,不是一年 —— 离现在最近的那个年份才是作者写的那个,
 * 而它落在过去时说明这场已经过去了,由 [parseReserveStartTime] 一并判掉。
 */
private fun parseMonthDay(text: String, now: ZonedDateTime): ZonedDateTime? = MonthDay.find(text)?.let { m ->
    val (month, day, hour, minute) = m.destructured
    (-1..1)
        .mapNotNull { offset -> zoned(now.year + offset, month.toInt(), day.toInt(), hour.toInt(), minute.toInt()) }
        .minByOrNull { kotlin.math.abs(it.toEpochSecond() - now.toEpochSecond()) }
}

/** 月/日/时越界(`13-45`、`25:00`)时返回 null,而不是让 java.time 抛出来。 */
private fun zoned(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime? =
    runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { zoned(it, hour, minute) }

private fun zoned(date: LocalDate, hour: Int, minute: Int): ZonedDateTime? =
    runCatching { LocalTime.of(hour, minute) }.getOrNull()?.let { LocalDateTime.of(date, it).atZone(ChinaZone) }
