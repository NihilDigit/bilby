package dev.bilby.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 探针:确认 material3 1.5.0-alpha25 上这些 API 真的存在、可见性够、需要哪些 opt-in。
 *
 * **放在 test source set,不放 main。** 探针的用途只是"这个 API 在不在",不需要进产物;
 * 放在 main 里意味着任何一个中间态的编译错误都会让同一棵树上的其他人停工 —— 已经发生过一次。
 * 用 `./gradlew :app:compileDebugUnitTestKotlin` 单独编译它。
 *
 * 结论记在 notes/ui-style-guide.md,升级依赖时重跑这个文件。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Probe() {
    // 1. expressive 主题入口:1.4.0 上是 internal,这一版公开了
    MaterialExpressiveTheme(
        colorScheme = expressiveLightColorScheme(),
        motionScheme = MotionScheme.expressive(),
        shapes = Shapes(),
        typography = Typography(),
    ) {}

    // 2. motion scheme 毕业:全局动效应该走它而不是各处手写 tween/spring
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    // 3. 十档形状刻度补齐(1.4.0 只有五档)
    val s = MaterialTheme.shapes
    listOf(s.extraSmall, s.small, s.medium, s.large, s.largeIncreased, s.extraLarge, s.extraLargeIncreased, s.extraExtraLarge)

    // 4. Typography 的默认 FontFamily 构造器
    Typography(FontFamily.Default)

    // 5. 一组并列操作
    ButtonGroup(overflowIndicator = {}) {
        clickableItem(onClick = {}, label = "x")
    }
    ButtonGroupDefaults.ConnectedSpaceBetween

    // 6. 状态型按钮:选中态配色由组件维护,不用自己 if/else 调 tint
    ToggleButton(checked = false, onCheckedChange = {}) {}
    FilledTonalToggleButton(checked = true, onCheckedChange = {}) {}
    ToggleButtonDefaults.toggleButtonColors()

    // 7. 加载指示器(1.4.0 上类不存在)
    LoadingIndicator()
}
