package dev.bilby.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import dev.bilby.data.AppearancePrefs
import dev.bilby.data.ThemeMode

/**
 * 评论正文里 @提及 和链接的颜色。它不在 [androidx.compose.material3.ColorScheme] 的角色表里
 * (那张表没有"链接"这个角色),但又必须随深浅色换值,所以单独走一个 CompositionLocal,
 * 而不是让每个用到的地方自己 `if (isSystemInDarkTheme())`。取值理由见 [FixedColors]。
 */
val LocalMentionColor = staticCompositionLocalOf { FixedColors.MentionLight }

/**
 * 主题入口走 [MaterialExpressiveTheme]。
 *
 * 这一条随依赖变过一次,值得记下来:在 material3 **1.4.0**(BOM 2026.06.01)上
 * `MaterialExpressiveTheme`、`MotionScheme`、`expressiveLightColorScheme` 全是 `internal`,
 * ButtonGroup / ToggleButton / MaterialShapes 这些类根本不存在,所以当时只能用 `MaterialTheme`
 * 自己拼四件套。换到 **1.5.0-alpha25**(compose-bom-alpha 2026.07.01)之后它们都公开了,
 * 探针在 `app/src/test/kotlin/dev/bilby/ui/M3ApiProbe.kt`,升级依赖时重跑那个文件。
 *
 * 用 expressive 入口而不是 `MaterialTheme` 的实际收益有两条,都不是"更好看":
 *
 * 1. 它会置上 `LocalUsingExpressiveTheme`,组件据此选 expressive 形态的默认值
 *    (按钮的形变、导航项的指示器),不用每个调用点自己传一堆 `shapes = ...`。
 * 2. 它要求给出 [MotionScheme] 并挂进 CompositionLocal,动效因此有了唯一的来源 ——
 *    以前是各处手写 `tween(300)` / `spring()`,同一个展开动作在两个页面上快慢不一样。
 *
 * 配色仍然是自己的那几套(见 [ThemePalette]),不用 `expressiveLightColorScheme()`:
 * 后者是另一套更高彩度的基线,和应用图标、启动窗口对不上(见 Color.kt 里种子的来历)。
 *
 * @param appearance 设置页「外观」那几项,见 [AppearancePrefs]。配色默认按壁纸取色
 *   (Android 12+):单用户自用的应用,系统色就是用户已经选过的审美。系统不支持时退回
 *   [ThemePalette.Default]。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BilbyTheme(
    appearance: AppearancePrefs = AppearancePrefs(),
    content: @Composable () -> Unit,
) {
    val darkTheme = when (appearance.mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val schemes = resolveSchemes(appearance)
    val colorScheme = if (darkTheme) schemes.dark else schemes.light

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        // expressive 而不是 standard:两者的区别是弹性和时长,expressive 的 spatial spec
        // 回弹更明显。Bilby 的动效只出现在展开/折叠和选中态上,回弹是"这一下生效了"的反馈。
        motionScheme = MotionScheme.expressive(),
        typography = BilbyTypography,
        shapes = BilbyShapes,
    ) {
        CompositionLocalProvider(
            LocalMentionColor provides if (darkTheme) FixedColors.MentionDark else FixedColors.MentionLight,
            LocalPlayerColorScheme provides schemes.dark,
            LocalIsDarkTheme provides darkTheme,
        ) {
            // MaterialTheme 只给配色方案,不设 LocalContentColor —— 它的默认值是纯黑,
            // 真正把内容色设成 onSurface 的是 Surface。没有这层的话,任何没被 Scaffold
            // 包住的界面都会在深色背景上写黑字。这个坑踩过一次。
            Surface(color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}

/**
 * 这一次该用的一对配色:按壁纸取色(可用且选了它时),否则是选中的内置配色;深色那一套
 * 按需压成纯黑。
 */
@Composable
private fun resolveSchemes(appearance: AppearancePrefs): PaletteSchemes {
    val context = LocalContext.current
    val base = if (appearance.palette == AppearancePrefs.DYNAMIC && dynamicColorAvailable) {
        PaletteSchemes(dynamicLightColorScheme(context), dynamicDarkColorScheme(context))
    } else {
        // 存的名字对不上(包括系统不支持时存着的 dynamic)一律退回默认那一套。
        (ThemePalette.entries.firstOrNull { it.name == appearance.palette } ?: ThemePalette.Default).schemes
    }
    return if (appearance.pureBlack) PaletteSchemes(base.light, base.dark.toPureBlack()) else base
}

/** 系统能不能按壁纸取色。设置页据此决定给不给那一格。 */
val dynamicColorAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 纯黑:页面底色压到 #000,几档容器色跟着各降一档,最高那一档空出来。
 *
 * **降档而不是只改底色。** 只把 background/surface 改成纯黑的话,surfaceContainerLow 那一档
 * (列表、卡片常用)和黑底之间的差一下子拉大,卡片浮成一块块灰补丁;整体下移一档,层次的
 * 间距还是原来那样,只是整块沉下去。
 */
private fun ColorScheme.toPureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = surfaceContainerLowest,
    surfaceContainer = surfaceContainerLow,
    surfaceContainerHigh = surfaceContainer,
    surfaceContainerHighest = surfaceContainerHigh,
)

/** 外层主题的深色那一套,[PlayerTheme] 用它:取色来源、纯黑与否都跟外层一致。 */
private val LocalPlayerColorScheme = staticCompositionLocalOf { BilbyDarkColors }

/**
 * 当前是不是深色。**用这个,不用 `isSystemInDarkTheme()`**:用户可以在设置里把明暗定死,
 * 系统是深色而应用是浅色时,后者给的是错的答案(状态栏图标的明暗就吃过这一亏)。
 */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * 播放器那一块的主题:**深浅固定取深色,取色来源跟外层一致。**
 *
 * 播放器底下永远是画面或黑底,而浅色主题的配色是给白底定的:primary 是一档深色,压在黑底上
 * 对比不够;菜单、tooltip、进度条的未播段都成了白底或浅灰实线,浮在视频上格外扎眼。这一层
 * 让它们全部按深色主题取值,主题色仍是用户那一套(动态取色时同一张壁纸的深色版)。
 *
 * 换配色与动效(standard,见下),字体、形状沿用外层。内容色设成 onSurface:MaterialTheme 自己不设
 * LocalContentColor(见 [BilbyTheme] 里那段),不设的话继承外层浅色主题的深色字。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerTheme(content: @Composable () -> Unit) {
    val colorScheme = LocalPlayerColorScheme.current
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        // **动效取 standard,不取外层的 expressive。** expressive 的位移弹簧会过冲回弹,放在列表
        // 展开、选中这类一次性的反馈上是"这一下生效了";放在播放器上,每点一下画面控件就弹一次,
        // 播放键变形也在晃 —— 浮在画面上的东西不该抢画面。
        motionScheme = MotionScheme.standard(),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
    ) {
        CompositionLocalProvider(
            LocalMentionColor provides FixedColors.MentionDark,
            LocalContentColor provides colorScheme.onSurface,
            content = content,
        )
    }
}
