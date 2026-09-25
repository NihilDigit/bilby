package dev.bilby.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.bilby.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 全应用唯一一份时间文案。**动态流、动态卡片、评论、专栏页共用它** —— 以前每一页各写一份,
 * 于是首页写"4分钟前"而同一个人的同一条动态在空间页写"2026-08-09",同一件事两种说法。
 *
 * 分档到"7天前"为止,再往前换成日期:"38天前"这种说法读者还得自己换算回哪一天。
 *
 * `@Composable` 只为了取字符串资源 —— 相对时间的字面是本地化内容,不能写死在这里。
 *
 * @param nowEpochSeconds 参数化只为让测试能给定"现在"。默认取系统时间。
 */
@Composable
fun formatRelativeTime(epochSeconds: Long, nowEpochSeconds: Long = Instant.now().epochSecond): String {
    if (epochSeconds <= 0L) return ""
    val diff = nowEpochSeconds - epochSeconds
    return when {
        diff < 60 -> stringResource(R.string.time_just_now)
        diff < 3600 -> stringResource(R.string.time_minutes_ago, diff / 60)
        diff < 24 * 3600 -> stringResource(R.string.time_hours_ago, diff / 3600)
        diff < 2 * 24 * 3600 -> stringResource(R.string.time_yesterday)
        diff < 7 * 24 * 3600 -> stringResource(R.string.time_days_ago, diff / (24 * 3600))
        else -> Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(AbsoluteDateFormatter)
    }
}

private val AbsoluteDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
