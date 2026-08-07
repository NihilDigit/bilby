package dev.danmaku.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * 渲染层的可调外观参数。库不带主题(不引 material3),字体、颜色兜底、描边、透明度全部由
 * 宿主 app 注入。
 *
 * @param baseTextStyle 字号/字重/字体族的基准样式;逐条弹幕的颜色与字号(若非 null)会覆盖
 *   这里的 color/fontSize,其余属性(fontFamily/fontWeight 等)原样沿用。
 * @param globalFontSizeSp 弹幕自身 [Danmaku.fontSize] 为 null 时使用的字号;两者都缺省时
 *   退回 [baseTextStyle] 自带的字号。
 * @param strokeWidthPx 描边宽度,<= 0 时跳过整条描边绘制(不多画那一遍)。
 * @param strokeColor 描边颜色。
 * @param trackHeightPx 每条轨道占用的行高,滚动与固定弹幕共用同一个值。
 * @param opacity 弹幕整体不透明度(用户设置项)。逐条传给 `drawText` 的 `alpha` 参数,不套
 *   `Modifier.alpha` —— 后者在不透明度 < 1 时会强制整个 Canvas 分配全尺寸离屏 buffer,
 *   每帧先画进去再整层混合,4K 下是上百万像素的额外填充率。副作用是重叠弹幕会互相透出,
 *   不是只显示最上层 —— 这是主流播放器的标准行为,不是这里引入的 bug。
 */
data class DanmakuRenderStyle(
    val baseTextStyle: TextStyle = TextStyle.Default,
    val globalFontSizeSp: Float? = null,
    val strokeWidthPx: Float = 3f,
    val strokeColor: Color = Color.Black,
    val trackHeightPx: Float = 48f,
    val opacity: Float = 1f,
)
