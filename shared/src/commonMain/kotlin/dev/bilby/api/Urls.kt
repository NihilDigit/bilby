package dev.bilby.api

/**
 * B 站返回的图片地址有三种形态:`https://`、`http://`、以及省略协议的 `//i0.hdslb.com/...`。
 * 后两种在 Android 上都拿不到图:明文 HTTP 被网络安全策略默认拒绝(报
 * UnknownServiceException),无协议的则根本不是合法 URL。
 *
 * 统一升到 https 而不是放开 cleartext —— 为一个字段把全 app 的传输安全降级不划算,
 * 且 hdslb 全站支持 https。
 */
fun String.toHttpsUrl(): String = when {
    isEmpty() -> this
    startsWith("//") -> "https:$this"
    startsWith("http://") -> "https://" + substring("http://".length)
    else -> this
}
