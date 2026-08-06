package dev.bilby.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Bilby 的基线配色。种子色 `#4A5C92` 不是新挑的 —— `res/values/colors.xml` 里的
 * `ic_launcher_background` 本来就是它,`values-night` 的启动背景 `#121318` 正是这个种子在
 * 2021 版规范下算出的 dark surface。这里只是把当时只落了两个值的那套色板补全成完整的 role 表,
 * 让启动窗口、应用图标和 Compose 主题真的是同一套色,而不是三处各写各的。
 *
 * 表由 material-color-utilities 按 tonal spot 变体、对比度 0、**2021 版规范**生成。
 * 规范版本不能选 2025:Compose material3 1.4.0 实现的是 2021 版,
 * 用 2025 版的值会和同一台机器上的 `dynamicLightColorScheme()` 观感对不上。
 *
 * 动态取色开着时这套表用不到(见 [BilbyTheme]),它是 Android 12 以下和用户关掉动态取色时的兜底。
 */
internal val BilbyLightColors = lightColorScheme(
    primary = Color(0xFF4A5C92),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBE1FF),
    onPrimaryContainer = Color(0xFF324478),
    inversePrimary = Color(0xFFB4C5FF),
    secondary = Color(0xFF585E72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDDE1F9),
    onSecondaryContainer = Color(0xFF414659),
    tertiary = Color(0xFF745470),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD6F8),
    onTertiaryContainer = Color(0xFF5A3D58),
    background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFAF8FF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE2E2EC),
    onSurfaceVariant = Color(0xFF45464F),
    surfaceTint = Color(0xFF4A5C92),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF757680),
    outlineVariant = Color(0xFFC5C6D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFAF8FF),
    surfaceDim = Color(0xFFDAD9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F3FA),
    surfaceContainer = Color(0xFFEEEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE3E2E9),
)

internal val BilbyDarkColors = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF1A2E60),
    primaryContainer = Color(0xFF324478),
    onPrimaryContainer = Color(0xFFDBE1FF),
    inversePrimary = Color(0xFF4A5C92),
    secondary = Color(0xFFC1C6DD),
    onSecondary = Color(0xFF2A3042),
    secondaryContainer = Color(0xFF414659),
    onSecondaryContainer = Color(0xFFDDE1F9),
    tertiary = Color(0xFFE2BBDC),
    onTertiary = Color(0xFF422741),
    tertiaryContainer = Color(0xFF5A3D58),
    onTertiaryContainer = Color(0xFFFFD6F8),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    surfaceTint = Color(0xFFB4C5FF),
    inverseSurface = Color(0xFFE3E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8F909A),
    outlineVariant = Color(0xFF45464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF38393F),
    surfaceDim = Color(0xFF121318),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF33343A),
)

/**
 * 不跟随主题、也不跟随动态取色的固定色。
 *
 * 判据只有一条:**这个颜色是画给机器看的,还是压在一张我们控制不了的图片上的**。
 * 两种情况下"随主题反色"都会直接坏掉功能,而不只是难看一点。
 * 二维码是最硬的一例,单独放在 `login/QrCodeImage.kt` 里,那边有完整说明。
 */
object FixedColors {
    /**
     * 压在封面上的时长角标、进度条底槽用的黑色遮罩。封面是 UP 主上传的任意图片,
     * 亮的暗的都有,只有固定的深色遮罩 + 纯白文字能保证在所有封面上都读得出来。
     * 用 surface/onSurface 的话浅色主题下就是白底白字压在白封面上。
     *
     * 0.55 是把"够读"和"别把封面糊掉"折中出来的:再低白字在雪景、白墙类封面上会散,
     * 再高遮罩本身变成一块显眼的黑斑。
     */
    val ScrimOnMedia = Color(0x8C000000)

    /** 压在封面/播放画面上的文字与图标。跟着主题走会在深色下变成深灰,压在黑边上看不见。 */
    val OnMedia = Color(0xFFFFFFFF)

    /** 播放器控件那种"半透明底 + 白字"的底色。全屏时画面本身就是背景,不存在 surface 可言。 */
    val PlayerControlScrim = Color(0x73000000)

    /**
     * 登录二维码的底色。扫码器普遍只认"深码块 + 浅底"这一种极性,深色主题下取 surface
     * 会得到深灰底,B 站客户端直接报"未成功解析到二维码"。整个应用最硬的一条固定色。
     * 码块本身的黑与 4 模块静区在 `login/QrCodeImage.kt`。
     */
    val QrBackground = Color(0xFFFFFFFF)

    /**
     * 评论正文里 @提及 与链接的颜色,B 站站内蓝。它标的是"这段是可点的",要跨动态取色保持
     * 稳定 —— 跟着 primary 走的话,换张壁纸同一段文字的含义就得重新学一遍。
     *
     * 但**不跟动态取色 ≠ 不跟深浅色**。同一个蓝在两个主题下不可能都达标:实测原色
     * `#00A1D6` 在浅色 surface 上只有 2.82:1,而任何在浅色下达标的深蓝到了深色底就掉到 2 以下。
     * 所以这里是一对值,沿 HCT 的 tone 轴取两档,色相(233)和彩度(51)保持不动 ——
     * 携带语义的是色相,承担可读性的是明度。M3 对 error 也是这么处理的:动态取色下保持静态,
     * 但仍然分深浅色两套。
     *
     * tone 45 / 65,对本主题 surface 的实测对比度 5.09:1 与 6.89:1。
     */
    val MentionLight = Color(0xFF00739A)
    val MentionDark = Color(0xFF1FA9DF)
}
