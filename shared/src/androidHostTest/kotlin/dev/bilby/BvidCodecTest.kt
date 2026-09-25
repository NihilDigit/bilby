package dev.bilby

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 两个方向都错了也不会报错:[BvidCodec.fromAid] 错了是安静地打开别人的视频,
 * [BvidCodec.toAid] 错了是把这次观看的进度记到别人的稿件上。
 */
class BvidCodecTest {

    @Test
    fun `av 号按 2023 版算法转 BV`() {
        // 这一对是公开可查的:两版算法对老号给出同一个结果,拿它当锚点。
        assertEquals("BV17x411w7KC", BvidCodec.fromAid(170_001L))
        assertEquals("BV1xx411c7mD", BvidCodec.fromAid(2L))
        // 超过旧算法上限(1 shl 27)的新号,旧算法根本表示不了。
        assertEquals("BV1uawaeCEPq", BvidCodec.fromAid(113_866_688_676_587L))
    }

    @Test
    fun `BV 号转回 av 号`() {
        assertEquals(170_001L, BvidCodec.toAid("BV17x411w7KC"))
        assertEquals(2L, BvidCodec.toAid("BV1xx411c7mD"))
        assertEquals(113_866_688_676_587L, BvidCodec.toAid("BV1uawaeCEPq"))
    }

    @Test
    fun `认不出来的串给 0`() {
        // 长度不对、前缀不对、字母表外的字符,三种都不能编一个号出来发请求。
        assertEquals(0L, BvidCodec.toAid("BV1uawaeCEP"))
        assertEquals(0L, BvidCodec.toAid("av170001"))
        assertEquals(0L, BvidCodec.toAid("BV1uawaeCEP0"))
        assertEquals(0L, BvidCodec.toAid(""))
    }
}
