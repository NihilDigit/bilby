package dev.bilby.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 这段异或+base64 算错了没有任何本地征兆:服务端不会因为 `x-bili-aurora-eid` 不对而报错,
 * 只会在风控侧留下一个对不上号的账号标识。所以拿一组独立算出来的向量钉住它。
 *
 * 期望值不是从这份 Kotlin 实现跑出来的,是按 PiliPlus `lib/utils/id_utils.dart:75-90` 的
 * 描述另写一份脚本算的,两边对上才算移植成功。
 */
class AuroraEidTest {

    @Test
    fun `按 PiliPlus 的算法生成 aurora eid`() {
        assertEquals("UA", AuroraEid.of("1"))
        assertEquals("UFYCQlQ", AuroraEid.of("12345"))
        assertEquals("U10CQVgHAlIC", AuroraEid.of("293793435"))
        // 12 位 key 循环一整轮之后仍然对得上,才说明取模没写错。
        assertEquals("UFwCQVgEBlEHXA", AuroraEid.of("1837900000"))
    }

    @Test
    fun `未登录时不产出 eid`() {
        assertEquals("", AuroraEid.of(""))
        assertEquals("", AuroraEid.of("0"))
    }
}
