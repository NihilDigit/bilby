package dev.bilby.api

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WBI 签名坏掉的方式只有一种:所有读接口一起返回 -403,而且看起来像风控。置换表错一位、
 * 过滤字符漏一个、编码用了 `URLEncoder` —— 本地都自检不出来,只有对着独立算出的定值才能发现。
 *
 * 这里的期望值不是从本实现跑出来的,是照官方算法另算的:`(imgKey+subKey)` 按 32 项置换表
 * 取字符得 mixinKey,参数按 key 排序、值删掉 `!'()*` 后按 RFC 3986 编码拼成 query,
 * `md5(query + mixinKey)` 即 w_rid。
 */
class WbiSignerTest {

    private val keys = WbiKeys(
        imgKey = "7cd084941338484aae1ad9425b84077c",
        subKey = "4932caff0ff746eab6f01bf08b70ac45",
    )

    @Test
    fun `mixinKey 按置换表从 imgKey+subKey 取 32 个字符`() {
        assertEquals("ea1db124af3c7062474693fa704f4ff8", WbiSigner.mixinKey(keys))
    }

    @Test
    fun `w_rid 与官方算法一致`() {
        val signed = WbiSigner.signWith(
            params = mapOf("mid" to "1", "keyword" to "a b(c)!'*d", "order" to "pubdate"),
            mixinKey = WbiSigner.mixinKey(keys),
            wts = 1_700_000_000L,
        )
        assertEquals("e1b04dc7293035c14c3e3a8bccc05b60", signed["w_rid"])
        // wts 要原样留在参数里一起发,不然服务端算出来的 query 和我们签的不是同一份。
        assertEquals("1700000000", signed["wts"])
    }

    @Test
    fun `空格编成百分之二十而不是加号`() {
        // URLEncoder 会编成 '+',这是最容易顺手写错、且只在带空格的搜索词上暴露的一处。
        val signed = WbiSigner.signWith(mapOf("keyword" to "a b"), WbiSigner.mixinKey(keys), 1L)
        val other = WbiSigner.signWith(mapOf("keyword" to "a+b"), WbiSigner.mixinKey(keys), 1L)
        assertEquals(false, signed["w_rid"] == other["w_rid"])
    }
}
