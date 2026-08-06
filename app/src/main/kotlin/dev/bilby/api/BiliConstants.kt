package dev.bilby.api

/**
 * 请求约定。PiliPlus 走的是 app 端那套(UA 写死 'Dart/3.6 (dart:io)' 加 app-key header),
 * 对我们要用的网页端接口不适用,所以这里按网页端自己定:桌面浏览器 UA + 站内 Referer。
 * 取流(playurl 拿到的 baseUrl)有防盗链,同样要带这两个头。
 */
object BiliConstants {
    const val WEB_HOST = "https://api.bilibili.com"
    const val PASSPORT_HOST = "https://passport.bilibili.com"
    const val MAIN_HOST = "https://www.bilibili.com"
    const val SPACE_HOST = "https://space.bilibili.com"
    const val APP_HOST = "https://app.bilibili.com"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"
    /** app 路线用的 UA,原样抄自 PiliPlus 的 Constants.userAgent(android_hd)。 */
    const val APP_USER_AGENT =
        "Mozilla/5.0 BiliDroid/2.0.1 (bbcallen@gmail.com) os/android model/android_hd " +
            "mobi_app/android_hd build/2001100 channel/master innerVer/2001100 osVer/15 network/2"

    const val REFERER = "https://www.bilibili.com"

    /**
     * 只给取流用(playurl 返回的 CDN 直链有防盗链,要 Referer + Origin + UA)。
     *
     * **接口请求不要带它**:浏览器对同源 GET 根本不发 Origin,无条件发反而是个破绽,
     * PiliPlus 也没有全局 Origin(见 BiliClient.applyCommonHeaders 的说明)。
     */
    const val ORIGIN = "https://www.bilibili.com"

    /**
     * PiliPlus 给**每一个**请求都加这三条,web 路线与 app 路线一视同仁
     * (`lib/common/constants.dart:29-34` 的 `baseHeaders`,由
     * `lib/utils/accounts/account_manager/account_mgr.dart:66-68` 无条件 addAll)。
     *
     * 含义无从考证(`app-key: android64` 与我们签名用的 android_hd 并不一致,PiliPlus
     * 照样这么发),但它是一个长期在线上跑的客户端的既定事实,缺了只会让请求特征与真实
     * 客户端不同、更容易被风控挑出来。DESIGN 8 节:与 B 站交互的决定以 PiliPlus 为准。
     */
    /**
     * 动态接口的 features 位。原样抄自 PiliPlus `lib/common/constants.dart:43`,
     * 首页动态与空间动态两条都要带。
     */
    const val DYN_FEATURES = "itemOpusStyle,listOnlyfans,onlyfansQaCard"

    val BASE_HEADERS = mapOf(
        "env" to "prod",
        "app-key" to "android64",
        "x-bili-aurora-zone" to "sh001",
    )
}
