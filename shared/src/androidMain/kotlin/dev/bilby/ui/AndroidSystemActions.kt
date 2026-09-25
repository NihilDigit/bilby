package dev.bilby.ui

import android.app.Activity

/** Android 上的 [SystemActions]。持有 Activity:切语言要重建它,拉起分享面板要一个前台界面。 */
class AndroidSystemActions(private val activity: Activity) : SystemActions {
    override fun shareVideo(bvid: String, title: String) = ShareLink.video(activity, bvid, title)
    override fun shareLiveRoom(roomId: Long, title: String) = ShareLink.liveRoom(activity, roomId, title)
    override fun shareSpace(mid: Long, name: String) = ShareLink.space(activity, mid, name)
    override fun openInBrowser(url: String) = ShareLink.openInBrowser(activity, url)

    override val supportsCalendar: Boolean get() = true

    override fun addCalendarEvent(title: String, startEpochSeconds: Long, description: String) =
        CalendarEvent.insert(activity, title, startEpochSeconds, description)

    override val supportsLanguageSwitch: Boolean get() = true
    override fun currentLanguage(): AppLanguage = AppLanguageStore.current(activity)
    override fun applyLanguage(language: AppLanguage) = AppLanguageStore.apply(activity, language)
}
