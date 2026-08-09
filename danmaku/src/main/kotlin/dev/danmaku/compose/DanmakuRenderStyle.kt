package dev.danmaku.compose

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * 渲染层的可调外观参数。库不带主题(不引 material3),字体、颜色兜底、描边、透明度全部由
 * 宿主 app 注入。
 *
 * 行高不在这里:它同时决定轨道数和轨道 y,是排布输入,只能有一份,放在
 * [DanmakuLayoutConfig.trackHeightPx]。
 *
 * @param baseTextStyle 字号/字重/字体族的基准样式;逐条弹幕的颜色与字号(若非 null)会覆盖
 *   这里的 color/fontSize,其余属性(fontFamily/fontWeight 等)原样沿用。
 * @param globalFontSizeSp 弹幕自身 [Danmaku.fontSize] 为 null 时使用的字号;两者都缺省时
 *   退回 [baseTextStyle] 自带的字号。
 * @param strokeWidthPx 描边宽度,<= 0 时跳过整条描边绘制(不多画那一遍)。
 * @param strokeColor 描边颜色。
 * @param opacity 弹幕整体不透明度(用户设置项)。它被烤进每条弹幕录制 display list 时的
 *   `drawText` `alpha` 参数,**既不套 `Modifier.alpha`,也不设 `GraphicsLayer.alpha`**:
 *   前者在不透明度 < 1 时强制整个 Canvas 分配全尺寸离屏 buffer;后者在默认
 *   `CompositingStrategy.Auto` 下会把每一条弹幕各自提升成一张离屏缓冲 —— 而这个值默认就
 *   < 1,踩上去等于给同屏几百条弹幕各开一张离屏。详见 [DanmakuRenderCache] 的类注释。
 *   副作用是重叠弹幕会互相透出,不是只显示最上层 —— 这是主流播放器的标准行为,不是这里
 *   引入的 bug。
 */
data class DanmakuRenderStyle(
    val baseTextStyle: TextStyle = TextStyle.Default,
    val globalFontSizeSp: Float? = null,
    val strokeWidthPx: Float = 3f,
    val strokeColor: Color = Color.Black,
    val opacity: Float = 1f,
)
