package com.bilby.api

/**
 * 请求约定。PiliPlus 走的是 app 端那套(UA 写死 'Dart/3.6 (dart:io)' 加 app-key header),
 * 对我们要用的网页端接口不适用,所以这里按网页端自己定:桌面浏览器 UA + 站内 Referer。
 * 取流(playurl 拿到的 baseUrl)有防盗链,同样要带这两个头。
 */
object BiliConstants {
    const val WEB_HOST = "https://api.bilibili.com"
    const val PASSPORT_HOST = "https://passport.bilibili.com"
    const val MAIN_HOST = "https://www.bilibili.com"
    const val APP_HOST = "https://app.bilibili.com"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"
    /** app 路线用的 UA,原样抄自 PiliPlus 的 Constants.userAgent(android_hd)。 */
    const val APP_USER_AGENT =
        "Mozilla/5.0 BiliDroid/2.0.1 (bbcallen@gmail.com) os/android model/android_hd " +
            "mobi_app/android_hd build/2001100 channel/master innerVer/2001100 osVer/15 network/2"

    const val REFERER = "https://www.bilibili.com"
    const val ORIGIN = "https://www.bilibili.com"
}
