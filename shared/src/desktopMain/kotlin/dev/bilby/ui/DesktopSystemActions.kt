package dev.bilby.ui

import dev.bilby.BiliLog
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

/**
 * 桌面上的 [SystemActions]。
 *
 * 分享没有系统面板可拉起(Windows 的分享面板要走 WinRT),改成把"标题 链接"放进剪贴板,
 * 用户粘到哪里由他决定。记日历、应用内更新、切界面语言尚未实现,对应入口不出现。
 */
class DesktopSystemActions : SystemActions {
    override fun shareVideo(bvid: String, title: String) = copy(title, "https://www.bilibili.com/video/$bvid")
    override fun shareLiveRoom(roomId: Long, title: String) = copy(title, "https://live.bilibili.com/$roomId")
    override fun shareSpace(mid: Long, name: String) = copy(name, "https://space.bilibili.com/$mid")

    override fun openInBrowser(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
            .onFailure { BiliLog.w("打开浏览器失败", it) }
    }

    override val supportsCalendar: Boolean get() = false
    override fun addCalendarEvent(title: String, startEpochSeconds: Long, description: String) = Unit

    override val supportsSelfUpdate: Boolean get() = false
    override val updateDownloadDir: File get() = File(System.getProperty("java.io.tmpdir"), "bilby-updates")
    override fun installUpdate(file: File) = Unit

    override val supportsLanguageSwitch: Boolean get() = false
    override fun currentLanguage(): AppLanguage = AppLanguage.System
    override fun applyLanguage(language: AppLanguage) = Unit

    private fun copy(title: String, url: String) {
        val text = if (title.isBlank()) url else "$title $url"
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
            .onFailure { BiliLog.w("写剪贴板失败", it) }
    }
}
