package dev.bilby.ui.player

import androidx.compose.runtime.Composable

/** 纵划在调什么。左半屏亮度,右半屏音量。 */
internal enum class VerticalAdjust { Brightness, Volume }

/** 正在进行的手势。null 表示没有;横划与纵划在第一段位移里定下,之后不再改判。 */
internal sealed interface PlayerGesture {
    /** 横划改进度。[startPositionMillis] 是按下那一刻的位置,位移都从它算起。 */
    data class Seek(val startPositionMillis: Long) : PlayerGesture

    /** 纵划改亮度或音量。[startValue] 同理,是按下那一刻的值(0..1)。 */
    data class Adjust(val kind: VerticalAdjust, val startValue: Float) : PlayerGesture
}

/** 纵划调的那个量,0..1。 */
internal interface LevelControl {
    fun current(): Float
    fun set(fraction: Float)
}

/**
 * 播放页的亮度,跟着播放页的生命周期走:离开时把覆盖值交还系统。
 * 平台调不了时为 null,左半屏的纵划就不启用(桌面显示器的亮度不归应用管)。
 */
@Composable
internal expect fun rememberWindowBrightness(): LevelControl?

/** 系统媒体音量。平台调不了时为 null,右半屏的纵划就不启用。 */
@Composable
internal expect fun rememberMediaVolume(): LevelControl?
