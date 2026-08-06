package dev.bilby.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
 * 配色仍然是自己的那套(见 [BilbyLightColors]),不用 `expressiveLightColorScheme()`:
 * 后者是另一套更高彩度的基线,和应用图标、启动窗口对不上(见 Color.kt 里种子的来历)。
 *
 * @param dynamicColor Android 12+ 上按壁纸取色。默认开:单用户自用的应用,系统色就是用户
 *   已经选过的审美,没有品牌一致性要压过它。关掉时退回 [BilbyLightColors] / [BilbyDarkColors]。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BilbyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> BilbyDarkColors
        else -> BilbyLightColors
    }

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
        ) {
            // MaterialTheme 只给配色方案,不设 LocalContentColor —— 它的默认值是纯黑,
            // 真正把内容色设成 onSurface 的是 Surface。没有这层的话,任何没被 Scaffold
            // 包住的界面都会在深色背景上写黑字。这个坑踩过一次。
            Surface(color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
