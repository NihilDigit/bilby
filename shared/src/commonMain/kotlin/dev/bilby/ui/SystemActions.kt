package dev.bilby.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 界面向系统要的动作:分享、在浏览器打开、记日历、装更新、切界面语言。
 *
 * 由各平台的入口在组合树根部经 [LocalSystemActions] 提供。某个平台做不到的动作,对应的
 * `supports*` 为 false,界面据此不给入口,而不是给一个按下去没反应的按钮。
 */
interface SystemActions {
    fun shareVideo(bvid: String, title: String)
    fun shareLiveRoom(roomId: Long, title: String)
    fun shareSpace(mid: Long, name: String)

    /**
     * 分享其实是把链接放进剪贴板(没有系统分享面板可拉起的平台)。为 true 时根部在每次分享后
     * 报一句"已复制",见 [withShareNotice]:否则按下去界面上什么也没发生,读起来就是按钮坏了。
     */
    val shareCopiesLink: Boolean get() = false
    fun openInBrowser(url: String)

    val supportsCalendar: Boolean

    /** 见 Android 实现 CalendarEvent 的说明:打开新建事件界面,由用户保存。 */
    fun addCalendarEvent(title: String, startEpochSeconds: Long, description: String)

    val supportsLanguageSwitch: Boolean

    /** 现在生效的界面语言。 */
    fun currentLanguage(): AppLanguage

    fun applyLanguage(language: AppLanguage)
}

/**
 * 分享之后报一句。只包分享那三个动作,其余原样转给 [this]。放在根部一次包好,
 * 三个分享入口(播放页、直播间、空间页)不用各自接提示。
 */
fun SystemActions.withShareNotice(notify: () -> Unit): SystemActions {
    if (!shareCopiesLink) return this
    val base = this
    return object : SystemActions by base {
        override fun shareVideo(bvid: String, title: String) = base.shareVideo(bvid, title).also { notify() }
        override fun shareLiveRoom(roomId: Long, title: String) = base.shareLiveRoom(roomId, title).also { notify() }
        override fun shareSpace(mid: Long, name: String) = base.shareSpace(mid, name).also { notify() }
    }
}

val LocalSystemActions = staticCompositionLocalOf<SystemActions> {
    error("SystemActions 未提供:平台入口要在组合树根部提供 LocalSystemActions")
}
