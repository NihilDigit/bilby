package dev.bilby.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 「正在直播」的那几根跳动的竖条。
 *
 * **自己画,不是从 PiliPlus 抄的。** 它那边这个符号是一张 GIF(`assets/images/live/live.gif`),
 * 全仓库只有输入框和竖排 tab 两处 `CustomPainter`,没有可移植的画法。自己画换来三样 GIF 给不了
 * 的东西:颜色跟着主题走(GIF 的颜色烧死在像素里,深色模式下会是一块亮斑)、任意尺寸不糊、
 * 以及不用为一个 20dp 的符号引一套 GIF 解码。
 *
 * 三根条依次起落,靠的是把同一条 0→1 的时间线按 [PhaseLag] 逐根往回挪 —— 三个独立的
 * `animateFloat` 会各自从组合的那一刻起算,首帧它们的高度是一样的,那一下看起来像卡了一帧。
 *
 * 高度用三角波(`abs` 折返)而不是正弦:两端的停顿更短,读起来更像声音在跳,而不是在呼吸。
 */
@Composable
fun LivePulse(
    color: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "live")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CycleMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Canvas(modifier = modifier) {
        val barWidth = size.width / (BarCount * 2 - 1)
        repeat(BarCount) { index ->
            // 每根比左边那根**晚** [PhaseLag],于是波峰从左往右走。加 1 再取余是因为
            // Kotlin 的 % 对负数返回负值,直接取余会让右边两根跳到波形的另一端去。
            val t = (phase - index * PhaseLag + 1f) % 1f
            // 三角波:小数部分 0→0.5→1 映成 0→1→0。
            val wave = 1f - abs(t * 2f - 1f)
            val barHeight = size.height * (MinBarFraction + (1f - MinBarFraction) * wave)
            val x = index * barWidth * 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(x, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

private const val BarCount = 3

/**
 * 一个来回多久。**慢一点更像"在播"** —— 快到一定程度那三根条读起来是"正在加载",而这个
 * 符号要说的是有人在那儿一直播着。
 */
private const val CycleMillis = 1200

/**
 * 每根条比左边那根晚多少个周期。
 *
 * **不能取 1/3。** 三根等距铺满一个周期,配上左右对称的三角波,正好是那个方向不可判的退化
 * 情形:第三根的高度恒等于"第一根往前挪 1/3",和"往后挪 1/3"给出同一组数字。眼睛读不出波
 * 往哪边走,只好把它当成三根各跳各的 —— 看起来就是没规律。
 *
 * 挪成一个小滞后之后,三根在任一时刻构成一道明显的斜坡,波峰从左向右扫过去,方向是确定的。
 */
private const val PhaseLag = 0.18f

/** 最矮时也留这么高。归零的话那根条会整个消失,读起来像少画了一根。 */
private const val MinBarFraction = 0.25f

/** 默认尺寸。小到能当角标,大到三根条还分得开。 */
val LivePulseSize = 12.dp
