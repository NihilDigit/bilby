package dev.bilby.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 横滚区的右边沿渐隐:内容画完之后,用一道从不透明到透明的渐变按 `DstIn` 混合把最右边那几 dp
 * 擦掉。
 *
 * **为什么要它。** 滚动到边界的那个元素会被硬切一半 —— 切口本身是"右边还有"的信号,但硬切在
 * 一个圆形头像或一枚圆角芯片上读起来像被右边那个东西盖住了。渐隐把切口变成"没画完",那正是
 * 它的意思。**左边不淡**:那儿是这一排的开头,不是被截断的地方。
 *
 * **必须 `CompositingStrategy.Offscreen`**:混合模式作用在"已经画好的一层"上,不离屏合成的话
 * `DstIn` 会跟这一层底下的东西作用,把背景一起擦出一个透明洞。
 *
 * 动态页那排头像和关注页那排分组芯片共用这一份。两处是同一件事(一条横滚被右端一个钉住的
 * 控件截断),各写一份的话渐隐宽度会先漂,而那正是"同一个应用"最先露馅的地方。
 */
fun Modifier.fadingRightEdge(width: Dp = DefaultEdgeFadeWidth): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startX = size.width - width.toPx(),
                endX = size.width,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * 渐隐带的宽度。**这是一段渐变的长度,不是间距**,所以不取 `Spacing` 里的档 —— 它要压过被切
 * 的那个元素的一段圆弧,窄了看着像脏了一道边,宽了整格头像都半透明。
 */
private val DefaultEdgeFadeWidth = 24.dp
