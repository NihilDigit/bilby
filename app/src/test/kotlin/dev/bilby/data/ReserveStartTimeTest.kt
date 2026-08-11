package dev.bilby.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 预约卡片的开播时间只能从一句展示文案里读(接口没有时间戳字段,见 ReserveStartTime 的说明)。
 * 这里钉住的是那条判据本身:**认得出就得认对,认不出就必须是 null** —— 猜出来的时间会变成
 * 用户日历里一条错的日程,那比没有这个入口更糟。
 */
class ReserveStartTimeTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 8, 11, 19, 0, 0, 0, zone)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toEpochSecond()

    @Test
    fun `直播预约那句话解成东八区的时刻`() {
        assertEquals(at(2026, 8, 23, 19, 0), parseReserveStartTime("08-23 19:00 直播", now))
    }

    /** 设备在别的时区不影响结果:那句话本来就是服务端按东八区渲染的(我们传的 timezone_offset=-480)。 */
    @Test
    fun `解析不跟设备时区走`() {
        val inLondon = now.withZoneSameInstant(ZoneId.of("Europe/London"))
        assertEquals(at(2026, 8, 23, 19, 0), parseReserveStartTime("08-23 19:00 直播", inLondon))
    }

    /** 没有年份时取离现在最近的那一年。12 月看到 01-05,近的是明年。 */
    @Test
    fun `跨年时补的是明年`() {
        val december = ZonedDateTime.of(2026, 12, 20, 10, 0, 0, 0, zone)
        assertEquals(at(2027, 1, 5, 20, 0), parseReserveStartTime("01-05 20:00 直播", december))
    }

    /**
     * 真机上抓到的那条:一场 08-09 的直播播完了,动态还挂在流里,而今天是 08-11。
     * 按"取还没到的那一年"会推到明年,建出一条一年后的日程 —— 取最近的那一年才对,
     * 哪怕它落在过去。
     */
    @Test
    fun `刚过去的场次不被推到明年`() {
        assertEquals(at(2026, 8, 9, 20, 0), parseReserveStartTime("08-09 20:00 直播", now))
    }

    @Test
    fun `今天与明天按当天算`() {
        assertEquals(at(2026, 8, 11, 23, 30), parseReserveStartTime("预计今天 23:30发布", now))
        assertEquals(at(2026, 8, 12, 18, 0), parseReserveStartTime("预计明天 18:00发布", now))
    }

    /**
     * 已经过去的时刻照样解出来。这里曾经返回 null,理由是"日历提醒不可能在过去响";
     * 而界面还要用这个时刻区分"还没开始"和"已经开始了"(开始了的那些给一个点进去的入口),
     * 在解析时就判掉等于把那个区分也一起丢了。该不该给日历入口由界面按当前时间现算。
     */
    @Test
    fun `已经过去的时刻照样解出来`() {
        assertEquals(at(2026, 8, 11, 18, 0), parseReserveStartTime("预计今天 18:00发布", now))
    }

    @Test
    fun `年份明写时直接用`() {
        assertEquals(at(2027, 3, 1, 9, 5), parseReserveStartTime("2027-03-01 09:05 直播", now))
    }

    @Test
    fun `认不出来的写法返回 null,不猜`() {
        assertNull(parseReserveStartTime("3.8万人预约", now))
        assertNull(parseReserveStartTime("即将开播", now))
        assertNull(parseReserveStartTime("", now))
        // 月/日/时越界的脏数据不能让 java.time 抛出来。
        assertNull(parseReserveStartTime("13-45 25:00 直播", now))
    }
}
