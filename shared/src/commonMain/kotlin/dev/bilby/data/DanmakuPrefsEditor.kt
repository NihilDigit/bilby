package dev.bilby.data

import dev.nihildigit.danmaku.DanmakuDensity
import dev.nihildigit.danmaku.DanmakuFrameRateCap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 播放器里改弹幕设置的入口,视频页与直播间共用。
 *
 * **每项各自落盘,不整份写回。** 视频页和直播间各自拿着一份 [DanmakuPrefs],整份写回会把
 * 别处刚改过的项按自己手上的旧值覆盖掉:比如在一处关掉弹幕,另一处再调一下透明度,弹幕又被
 * 写成打开。
 */
interface DanmakuPrefsEditor {
    fun setEnabled(enabled: Boolean)
    fun setOpacity(value: Float)
    fun setScrollShowArea(value: Float)
    fun setDensity(value: DanmakuDensity)
    fun setFrameRate(value: DanmakuFrameRateCap)
    fun setInPip(value: Boolean)
    fun setFontScale(value: Float)
}

/**
 * 直接写进 [SettingsStore]。新值经 [SettingsStore.danmakuPrefs] 流回来,不另外维护一份本地状态。
 *
 * [scope] 须是 [dev.bilby.AppContainer.persistScope]:调完就退出页面时,页面的作用域随之取消,
 * 这一次写入不能跟着丢。
 */
class StoredDanmakuPrefsEditor(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : DanmakuPrefsEditor {
    override fun setEnabled(enabled: Boolean) = persist { settings.saveDanmakuEnabled(enabled) }
    override fun setOpacity(value: Float) = persist { settings.saveDanmakuOpacity(value) }
    override fun setScrollShowArea(value: Float) = persist { settings.saveDanmakuScrollShowArea(value) }
    override fun setDensity(value: DanmakuDensity) = persist { settings.saveDanmakuDensity(value) }
    override fun setFrameRate(value: DanmakuFrameRateCap) = persist { settings.saveDanmakuFrameRate(value) }
    override fun setInPip(value: Boolean) = persist { settings.saveDanmakuInPip(value) }
    override fun setFontScale(value: Float) = persist { settings.saveDanmakuFontScale(value) }

    private fun persist(write: suspend () -> Unit) {
        scope.launch { write() }
    }
}
