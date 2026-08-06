package dev.bilby.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 形状。取值直接用 M3 的圆角刻度,没有自作主张的数。
 *
 * **十档在 material3 1.5.0-alpha25 上补齐了** —— 1.4.0 的 [Shapes] 只有五个槽,
 * `largeIncreased` / `extraLargeIncreased` / `extraExtraLarge` 是随 Expressive 一起进来的。
 * 以前需要 20dp 那种中间档只能在组件处写死,现在收回主题。
 *
 * 用哪一档由**信息密度**决定,不是由组件大小决定(M3 从 M2 换掉的判据):
 * Bilby 到处是信息密集的列表,所以主力仍是 small/medium,大圆角只留给弹窗和听视频的大封面。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
internal val BilbyShapes = Shapes(
    // 角标、小标签。
    extraSmall = RoundedCornerShape(4.dp),
    // 列表里的封面缩略图、队列条目。
    small = RoundedCornerShape(8.dp),
    // 卡片、输入框、二维码占位。
    medium = RoundedCornerShape(12.dp),
    // 弹窗、底部 sheet。
    large = RoundedCornerShape(16.dp),
    // 输入框:比 large 再圆一档,让"这里能打字"更明显。以前没有这一档,只能写死 20dp。
    largeIncreased = RoundedCornerShape(20.dp),
    // 大面积容器。
    extraLarge = RoundedCornerShape(28.dp),
    extraLargeIncreased = RoundedCornerShape(32.dp),
    // 听视频页那张大封面,整屏里唯一的"主角",允许圆得明显一点。
    extraExtraLarge = RoundedCornerShape(48.dp),
)
