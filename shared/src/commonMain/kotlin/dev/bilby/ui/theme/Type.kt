package dev.bilby.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * M3 字号表,按中文正文调过行高与字距。
 *
 * 只动行高和字距,**不动字号**:字号是组件排版的输入(按钮高度、列表行高、TabRow 的最小宽度
 * 都由它推),改了会让一堆 material3 组件重新流一遍布局。M3 的排版页也是这么建议的。
 *
 * 两处偏离基线,都是中文特有的:
 *
 * - **字距归零。** 基线表给正文留了 +0.25 ~ +0.5sp 的字距,那是给 Roboto 的小写拉丁字母调的。
 *   汉字本来就是等宽满格的方块,再撑开就散成一个个孤立的字,读起来要一个字一个字地拼。
 *   label 档保留一点正字距 —— 那一档实际承载的多是数字和短英文(时长、倍速、"1080P")。
 *
 * - **小字号行高加高。** 14sp/20sp 在拉丁文下够,汉字的实际墨迹几乎占满 em 框,
 *   同样的行距看上去挤得多。密度最高的 body/title small 各加 2sp。
 *
 * 另外全表统一关掉 `includeFontPadding` 并把行高按字形居中:字体自带的上下 padding 是按拉丁文
 * 的 ascent/descent 算的,中文字形在其中偏上,不处理的话每个 Text 的视觉中心都比容器中心高一点,
 * 图标配文字那种一行里最明显。
 *
 * **emphasized 那 15 档在 material3 1.5.0-alpha25 上才有**(1.4.0 只有 15 档基线)。
 * 它们和基线同字号、同行高,只加字重 —— M3 的强调靠字重和字宽,不靠放大,
 * 放大会把行高一起改掉,列表行就跳了。同一套 CJK 调整必须应用到这 15 档上,
 * 否则一强调就退回 Roboto 的字距,中文会突然散开。
 */
private val CjkPlatform = NoFontPadding

private val CjkLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun cjk(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = CjkFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    platformStyle = CjkPlatform,
    lineHeightStyle = CjkLineHeight,
)

/**
 * 等宽数字(`tnum`):时长、计数、`X / N` 队列位置与倒计时跳字时整行宽度保持稳定。
 */
val TextStyle.tabular: TextStyle
    get() = copy(fontFeatureSettings = "tnum")

/**
 * 强调档:同字号同行高,按中文笔画密度拉清层级且避免合成粗体糊笔:
 * display / headline / title / label 取 SemiBold(600),body 取 Medium(500)。
 */
private fun cjkEmphasized(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.SemiBold,
    letterSpacing: Double = 0.0,
) = cjk(size, lineHeight, weight, letterSpacing)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val BilbyTypography = Typography(
    // 大标题与主视觉锚点(个人页、空间头图、专栏标题、听视频曲目名等按需取对应档或其 Emphasized)。
    displayLarge = cjk(57, 64, letterSpacing = -0.25),
    displayMedium = cjk(45, 52),
    displaySmall = cjk(36, 44),
    headlineLarge = cjk(32, 40),
    headlineMedium = cjk(28, 36),
    headlineSmall = cjk(24, 32),

    // 页面标题(顶栏、播放页主标题)。
    titleLarge = cjk(22, 28),
    // 区块标题、卡片组标题。
    titleMedium = cjk(16, 24, FontWeight.Medium),
    titleSmall = cjk(14, 22, FontWeight.Medium),

    // 列表与网格条目主标题(强调档 bodyLargeEmphasized 抬至 Medium,压住次级元信息)。
    bodyLarge = cjk(16, 24),
    bodyMedium = cjk(14, 22),
    // UP 主名、辅助说明等次级正文。
    bodySmall = cjk(12, 18),

    // 按钮文字与小节标题。
    labelLarge = cjk(14, 20, FontWeight.Medium, letterSpacing = 0.1),
    labelMedium = cjk(12, 16, FontWeight.Medium, letterSpacing = 0.5),
    // 封面上的时长角标、倍速、清晰度——几乎全是数字和短英文,保留基线字距。
    labelSmall = cjk(11, 16, FontWeight.Medium, letterSpacing = 0.5),

    displayLargeEmphasized = cjkEmphasized(57, 64, FontWeight.SemiBold, letterSpacing = -0.25),
    displayMediumEmphasized = cjkEmphasized(45, 52, FontWeight.SemiBold),
    displaySmallEmphasized = cjkEmphasized(36, 44, FontWeight.SemiBold),
    headlineLargeEmphasized = cjkEmphasized(32, 40, FontWeight.SemiBold),
    headlineMediumEmphasized = cjkEmphasized(28, 36, FontWeight.SemiBold),
    headlineSmallEmphasized = cjkEmphasized(24, 32, FontWeight.SemiBold),
    titleLargeEmphasized = cjkEmphasized(22, 28, FontWeight.SemiBold),
    titleMediumEmphasized = cjkEmphasized(16, 24, FontWeight.SemiBold),
    titleSmallEmphasized = cjkEmphasized(14, 22, FontWeight.SemiBold),
    bodyLargeEmphasized = cjkEmphasized(16, 24, FontWeight.Medium),
    bodyMediumEmphasized = cjkEmphasized(14, 22, FontWeight.Medium),
    bodySmallEmphasized = cjkEmphasized(12, 18, FontWeight.Medium),
    labelLargeEmphasized = cjkEmphasized(14, 20, FontWeight.SemiBold, letterSpacing = 0.1),
    labelMediumEmphasized = cjkEmphasized(12, 16, FontWeight.SemiBold, letterSpacing = 0.5),
    labelSmallEmphasized = cjkEmphasized(11, 16, FontWeight.SemiBold, letterSpacing = 0.5),
)
