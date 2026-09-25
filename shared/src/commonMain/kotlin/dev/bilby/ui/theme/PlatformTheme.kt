package dev.bilby.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily

/** 系统能不能按壁纸取色。设置页据此决定给不给那一格。只有 Android 12+ 能。 */
expect val dynamicColorAvailable: Boolean

/** 按壁纸取的那一对配色。[dynamicColorAvailable] 为 false 时不会被调用。 */
@Composable
internal expect fun dynamicColorSchemes(): PaletteSchemes

/**
 * 关掉字体自带的上下 padding。只有 Android 的文字排版有这一项(`includeFontPadding`,
 * 按拉丁文的 ascent/descent 算,中文字形在其中偏上);桌面的 Skia 排版没有,给 null。
 */
expect val NoFontPadding: PlatformTextStyle?

/**
 * 正文字族。Android 是系统默认,与迁移前一致。
 *
 * 桌面上不能交给回退:英文系统下 Skia 为汉字挑的第一回退字体不含简体专用字,「计」「讲」
 * 「隐」再落到雅黑常规体,加粗的一行里混着几个细字。给文字加中文语言标签试过,Skia 挑回退
 * 字体时不看它,没有用。于是直接点名一套字体。
 */
expect val CjkFontFamily: FontFamily

/**
 * 系统的"减弱动效"开着没有。
 *
 * 规范把它列为"好的转场"第一条:"Most platforms have a reduced animation setting... If that setting
 * is on, transitions should **use subtle fades instead of intense sliding or scaling animations**
 * and disable decorative effects"。各平台的读法见 actual。
 */
@Composable
expect fun rememberReducedMotion(): Boolean
