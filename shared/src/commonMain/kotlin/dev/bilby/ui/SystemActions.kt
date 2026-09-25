package dev.bilby.ui

import androidx.compose.runtime.staticCompositionLocalOf
import java.io.File

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
    fun openInBrowser(url: String)

    val supportsCalendar: Boolean

    /** 见 Android 实现 CalendarEvent 的说明:打开新建事件界面,由用户保存。 */
    fun addCalendarEvent(title: String, startEpochSeconds: Long, description: String)

    /** 能不能下载并安装新版本。不能时设置页不出现检查更新,启动时也不检查。 */
    val supportsSelfUpdate: Boolean

    /** 安装包的下载目录。只算路径,不建目录。 */
    val updateDownloadDir: File

    fun installUpdate(file: File)

    val supportsLanguageSwitch: Boolean

    /** 现在生效的界面语言。 */
    fun currentLanguage(): AppLanguage

    fun applyLanguage(language: AppLanguage)
}

val LocalSystemActions = staticCompositionLocalOf<SystemActions> {
    error("SystemActions 未提供:平台入口要在组合树根部提供 LocalSystemActions")
}
