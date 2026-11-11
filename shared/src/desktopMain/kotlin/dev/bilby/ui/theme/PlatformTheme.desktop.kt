package dev.bilby.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont

actual val dynamicColorAvailable: Boolean = false

actual val NoFontPadding: PlatformTextStyle? = null

/**
 * 微软雅黑只有常规与粗体两档(另有细体),Medium 按字重匹配规则落到常规,SemiBold 落到粗体。
 * 只做 Windows,不为别的桌面系统准备字体名。
 */
@OptIn(ExperimentalTextApi::class)
actual val CjkFontFamily: FontFamily = FontFamily(
    SystemFont("Microsoft YaHei UI", FontWeight.Normal),
    SystemFont("Microsoft YaHei UI", FontWeight.Bold),
)

@Composable
internal actual fun dynamicColorSchemes(): PaletteSchemes = error("桌面没有按壁纸取色")

/** 读取并跟随 Windows 的「设置 → 辅助功能 → 视觉效果 → 动画效果」开关,见 [SystemReducedMotion]。 */
@Composable
actual fun rememberReducedMotion(): Boolean {
    SystemReducedMotion.ensureStarted()
    return SystemReducedMotion.reduced
}
