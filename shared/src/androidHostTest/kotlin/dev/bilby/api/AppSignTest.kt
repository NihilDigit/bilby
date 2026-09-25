package dev.bilby.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * app 端签名的失败模式和 WBI 那套一模一样:错了不会抛异常,只会让点赞和投币一起返回
 * `-403 账号异常`,看上去与风控无从区分。所以同样对着独立算出的定值来测。
 *
 * 期望值不是从本实现跑出来的:按 `md5(排序后的 query + appsec)` 另算,其中值为空串的键
 * 只写键名、不写 `=`,值按 RFC 3986 编码。
 */
class AppSignTest {

    private val ts = 1_700_000_000L

    @Test
    fun `sign 与官方算法一致`() {
        val signed = AppSign.signWith(
            mapOf("aid" to "114514", "multiply" to "2", "select_like" to "0"),
            ts = ts,
        )
        assertEquals("4d569b0e878fcccd4d0dfec2858c3c41", signed["sign"])
        // appkey 和 ts 要原样跟着发,否则服务端算的不是我们签的那份 query。
        assertEquals(AppSign.APP_KEY, signed["appkey"])
        assertEquals("1700000000", signed["ts"])
    }

    @Test
    fun `值为空串的参数只写键名,不写等号`() {
        // 未登录时 access_key 是空串。写成 `access_key=` 签名就不过,而这条路上唯一的症状
        // 是接口报账号异常。
        assertEquals(
            "d1775a255a15a7f084ff8c5d65a39a63",
            AppSign.signWith(mapOf("access_key" to "", "aid" to "1"), ts = ts)["sign"],
        )
    }

    @Test
    fun `空格编成百分之二十而不是加号`() {
        // URLEncoder 会编成 '+'。同 WbiSignerTest 的那一条,顺手写错且只在带空格的值上暴露。
        assertEquals(
            "509ee658b9f44bef0302c073b2d2c9a3",
            AppSign.signWith(mapOf("keyword" to "a b 你好"), ts = ts)["sign"],
        )
    }

    @Test
    fun `传进来的 sign 不参与签名`() {
        // 重签一份已签过的参数表时,旧的 sign 混进 query 会让新签名必然错。
        val fresh = AppSign.signWith(mapOf("aid" to "1"), ts = ts)
        assertEquals(fresh["sign"], AppSign.signWith(fresh + ("sign" to "stale"), ts = ts)["sign"])
    }
}
