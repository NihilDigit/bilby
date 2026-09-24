package dev.bilby.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 硬币:一个圆,中间一个 B。视频页动作栏的投币格和「我的」页的硬币余额共用这一个字形,
 * 同一样东西在两处长一个样子。
 *
 * **B 交给字体画,不自己描点。** 先手写过一版路径(一竖加两个碗,实心态再靠 evenOdd 挖出
 * 两个字腔),在 20dp 上是糊的 —— 字腔只剩一两个物理像素,而字体厂商为这个尺寸做了 hinting,
 * 手写坐标做不到。`Text` 还顺带解决了粗细和光学重心。
 *
 * **字号按 dp 折算,不跟系统字号缩放。** 圆是 dp 定死的,B 若跟着系统字号长大就会顶破它;
 * 这一个字是图标的一部分,不是可读的正文。圈环粗细和字号都按 [size] 等比取。
 *
 * 实心是实心圆挖出字,空心是圈环加同色的字 —— 两个明显不同的字形,不只靠颜色区分
 * (风格指南 §2.6)。挖出来那个字取 [cutout],也就是字形周围真正的底色。
 */
@Composable
fun CoinGlyph(
    tint: Color,
    filled: Boolean,
    cutout: Color,
    modifier: Modifier = Modifier,
    size: Dp = DefaultCoinSize,
) {
    val density = LocalDensity.current
    val letterSize = remember(density, size) { with(density) { (size * LetterRatio).toSp() } }
    Box(
        modifier = modifier
            .size(size)
            .then(
                if (filled) {
                    Modifier.background(tint, CircleShape)
                } else {
                    Modifier.border(size * RingRatio, tint, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "B",
            color = if (filled) cutout else tint,
            style = TextStyle(
                fontSize = letterSize,
                // 行高等于字号、并关掉字体自带的上下留白,字才落在圆心上 —— 默认那两样都会
                // 把这一个字往下推,在 20dp 的圆里看得出来。
                lineHeight = letterSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
        )
    }
}

private val DefaultCoinSize = 20.dp

/** 20dp 的圆配 1.6dp 的环、12dp 的 B:字占圆的六成左右,再大就贴边。别的尺寸等比。 */
private const val RingRatio = 0.08f
private const val LetterRatio = 0.6f
