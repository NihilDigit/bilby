package com.bilby.api

import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WbiKeys(val imgKey: String, val subKey: String)

/**
 * 部分接口(空间投稿、搜索、playurl 等)要求 w_rid/wts 签名,裸调返回 -400/-403。
 *
 * keyProvider 由 AppContainer 注入而不是这里直接调 /x/web-interface/nav,是为了断开
 * BiliClient ←→ WbiSigner 的循环依赖(客户端要签名,签名要发请求)。
 */
class WbiSigner(private val keyProvider: suspend () -> WbiKeys) {

    private val mutex = Mutex()
    private var cached: Pair<LocalDate, String>? = null

    /** 返回补齐了 wts 与 w_rid 的参数表。 */
    suspend fun sign(params: Map<String, String>): Map<String, String> =
        signWith(params, mixinKey(), System.currentTimeMillis() / 1000)

    private suspend fun mixinKey(): String = mutex.withLock {
        val today = LocalDate.now()
        cached?.takeIf { it.first == today }?.second ?: run {
            val keys = keyProvider()
            mixinKey(keys).also { cached = today to it }
        }
    }

    companion object {
        /**
         * 密钥混淆用的置换表,32 项。从 PiliPlus lib/utils/wbi_sign.dart:19-52 原样搬,
         * 顺序即语义,任何一项写错都只会表现为服务端 -403,本地无从自检。
         */
        private val MIXIN_KEY_ENC_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
        )

        /** 这五个字符 JS 的 encodeURIComponent 不转义,B 站按删除处理,所以签名前先剔掉。 */
        private val FILTERED_CHARS = charArrayOf('!', '\'', '(', ')', '*')

        fun mixinKey(keys: WbiKeys): String {
            val orig = keys.imgKey + keys.subKey
            return buildString(MIXIN_KEY_ENC_TAB.size) {
                for (i in MIXIN_KEY_ENC_TAB) append(orig[i])
            }
        }

        /** 纯函数,便于用固定 key 与固定 wts 做回归。 */
        fun signWith(params: Map<String, String>, mixinKey: String, wts: Long): Map<String, String> {
            val withWts = params + ("wts" to wts.toString())
            val query = withWts.keys.sorted().joinToString("&") { key ->
                "${encodeComponent(key)}=${encodeComponent(withWts.getValue(key).filterNot { it in FILTERED_CHARS })}"
            }
            return withWts + ("w_rid" to md5Hex(query + mixinKey))
        }

        /**
         * 复刻 JS encodeURIComponent:未保留字符集为 A-Za-z0-9-_.~ 加上 !'()*,
         * 而后五个已在上游被删掉,所以这里就是 RFC 3986 的 unreserved 集合。
         * 不能用 URLEncoder —— 它把空格编成 '+',且对 ~ 的处理与 JS 不同。
         */
        private fun encodeComponent(value: String): String = buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt().toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in "-_.~") append(c)
                else append('%').append("%02X".format(byte))
            }
        }

        private fun md5Hex(input: String): String =
            MessageDigest.getInstance("MD5")
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
