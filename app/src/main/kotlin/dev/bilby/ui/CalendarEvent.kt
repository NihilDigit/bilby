package dev.bilby.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import dev.bilby.BiliLog

/**
 * 把一场预约记进用户自己的日历。
 *
 * **打开日历的新建事件界面,由用户按下保存,不直接写库。** 三条理由:
 *
 * 1. 直接 `insert` 到 `CalendarContract.Events` 要 `WRITE_CALENDAR` 权限。为一个可选功能
 *    去要日历读写权限不划算 —— 装完应用可能一次都不用到它,而权限是一直在那儿的。
 * 2. 写进去的是用户自己的日历,该由他确认。即使有权限,静默往别人日历里塞条目也不该做。
 * 3. 提醒的可靠性交给日历。`AlarmManager` 在国产 ROM 的省电策略下会被杀,日历应用不会,
 *    它本来就是干这个的。
 *
 * 这条路径不发任何通知,应用里也没有通知渠道 —— 提醒完全由用户的日历应用发出。
 */
object CalendarEvent {

    /**
     * @param startEpochSeconds 开始时刻。**调用方必须确保它是真的**:解不出时间时不要给入口,
     *   建出一条时间错的日程比没有更糟(见 `data/ReserveStartTime.kt`)。
     * @param description 事件说明,放直播间或动态的地址,用户从日历点得回来。
     */
    fun insert(context: Context, title: String, startEpochSeconds: Long, description: String) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startEpochSeconds * 1000)
            // 只给开始时刻,不猜时长:一场直播播多久没人知道,写死一小时是编的。
            // 日历自己会按它的默认时长补上结束时间。
            .putExtra(CalendarContract.Events.DESCRIPTION, description)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                // 设备上没有任何日历应用时走这里。不弹提示:这一步是用户主动点的,
                // 界面上按下去没反应本身就说明了这台机器上没有能接的应用。
                if (it is ActivityNotFoundException) {
                    BiliLog.w("这台设备没有能接 ACTION_INSERT 的日历应用")
                } else {
                    BiliLog.w("打开日历失败", it)
                }
            }
    }
}
