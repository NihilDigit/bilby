package dev.bilby.api

import dev.bilby.BuildConfig

/**
 * 请求约定。web 接口用桌面浏览器 UA([USER_AGENT])加站内 Referer;app 端接口报 B 站官方
 * 客户端([APP_USER_AGENT],签名里带的是 TV/HD 的 appkey,UA 必须跟它对得上)。
 * 只有字幕轨那一个接口例外,见 [NON_BROWSER_USER_AGENT]。
 *
 * 取流(playurl 拿到的 baseUrl)有防盗链,要 Referer + UA。
 */
object BiliConstants {
    const val WEB_HOST = "https://api.bilibili.com"
    const val PASSPORT_HOST = "https://passport.bilibili.com"
    const val MAIN_HOST = "https://www.bilibili.com"
    const val SPACE_HOST = "https://space.bilibili.com"

    /** 一条动态的网页地址是 `t.bilibili.com/<id_str>`,不在 `www` 下面。 */
    const val DYNAMIC_HOST = "https://t.bilibili.com"
    const val APP_HOST = "https://app.bilibili.com"

    /** 直播接口自成一个域,和 `api.bilibili.com` 不通用。 */
    const val LIVE_HOST = "https://api.live.bilibili.com"

    /** 直播页的 Referer。取流同样有防盗链,指到站点首页会被拒。 */
    const val LIVE_REFERER = "https://live.bilibili.com"

    /** web 接口的默认 UA。整条默认路线上二十来个接口都靠它,长期工作正常,不要动。 */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    /**
     * **只给 `x/player/wbi/v2`(字幕轨)用的 UA,别扩大到别的接口。**
     *
     * 那个接口拿浏览器 UA 长期 `-412 request was banned`,字幕从来加载不出来。三轮单变量对照
     * (同一账号、同一网络、其余 header 与参数全不变):
     *
     * | UA | 结果 |
     * |---|---|
     * | [USER_AGENT](桌面 Chrome) | -412 |
     * | `Dart/3.6 (dart:io)`(PiliPlus 的 Dio 默认值) | 通 |
     * | 本串 | 通 |
     *
     * 两个互不相同的非浏览器 UA 都能过,所以判据是"这个接口不接受浏览器 UA",而不是"必须是
     * 某个特定字符串" —— 于是不必抄 PiliPlus 的值,如实报自己是谁即可。
     *
     * **曾经把它设成全局默认,那是错的。** 理由当时是"自称 Chrome 凑不出一个真实客户端:
     * 同一个请求还带着 app-key、x-bili-aurora-eid 和 TV 扫码换来的 cookie"。推理本身没错,
     * 但它推不出"所以别的接口也会拒绝浏览器 UA" —— 实际结果是 `x/web-interface/card`
     * 当场 -352、搜索直接没结果。一个接口的实证只覆盖那一个接口(CLAUDE.md:风控是按动作算的)。
     */
    val NON_BROWSER_USER_AGENT = "Bilby/${BuildConfig.VERSION_NAME} (+https://github.com/NihilDigit/bilby)"
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
