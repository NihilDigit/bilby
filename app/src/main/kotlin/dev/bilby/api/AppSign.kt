package dev.bilby.api

import java.security.MessageDigest

/**
 * app 端接口的签名。与 WBI 是两套东西:WBI 用于网页端接口,这套用于 `app.bilibili.com`。
 *
 * 用它的原因不是偏好:网页端的写接口(点赞、投币)对第三方客户端风控严重,返回
 * `-403 账号异常`;PiliPlus 早已把这两个接口换成 app 端(见 notes/auth-model.md)。
 */
object AppSign {

    /** HD 版(android_hd)。PiliPlus 全仓库只用这一对,不同接口不换 key。 */
    const val APP_KEY = "dfca71928277209b"
    private const val APP_SEC = "b5475a8825547a4fc26c7d518eaaa02e"

    /** 返回补齐了 appkey/ts/sign 的参数表。 */
    fun sign(params: Map<String, String>): Map<String, String> =
        signWith(params, ts = System.currentTimeMillis() / 1000)

    /** 纯函数,便于用固定 ts 做回归。理由同 [WbiSigner.signWith]。 */
    fun signWith(params: Map<String, String>, ts: Long): Map<String, String> {
        val withMeta = params - "sign" + mapOf(
            "appkey" to APP_KEY,
            "ts" to ts.toString(),
        )
        val query = withMeta.keys.sorted().joinToString("&") { key ->
            val value = withMeta.getValue(key)
            // value 为空串时只写 key、不写 '='。这一点与常见实现不同,写错就是签名不过。
            if (value.isEmpty()) encode(key) else "${encode(key)}=${encode(value)}"
        }
        return withMeta + ("sign" to md5Hex(query + APP_SEC))
    }

    /** 与 JS encodeURIComponent 同语义,和 WbiSigner 里那份保持一致的未保留字符集。 */
    private fun encode(value: String): String = buildString {
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
