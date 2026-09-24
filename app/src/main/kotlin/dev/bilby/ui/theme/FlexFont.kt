package dev.bilby.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.bilby.R
import kotlin.math.roundToInt

/**
 * Roboto Flex,可变字体。随包分发(`res/font/roboto_flex.ttf`,SIL OFL 1.1,许可证原文在
 * `assets/licenses/roboto_flex_OFL.txt`)。
 *
 * **为什么要一个可变字体。** 系统字体在不同机器上不是同一个(Pixel 上是 Roboto,HyperOS 上是
 * MiSans),而静态字重只有几档,字重从 300 变到 700 只能硬切。可变字体的字重、字宽是连续的轴,
 * 数字可以随手势平滑地变粗变窄。
 *
 * 取一次形态就是一个字体实例;动画中逐帧取会把每一帧的轴值都实例化一遍。所以轴值先量化
 * ([WeightStep]、[WidthStep]),同一档复用同一个实例。
 *
 * [figureHeight] 是 Roboto Flex 的 `YTFI` 轴(数字高度,560–788,默认 738)。压低它数字变矮,
 * 字号和行高不动;配合宽的字宽轴就是"矮胖"的数字。汉字和字母不受影响。
 *
 * [ink] 在 0..1 之间,把四条参数轴一起推:竖笔(`XOPQ` 96→175)、横笔(`YOPQ` 79→135)加粗到
 * 上限,字腔(`XTRA` 468→323)收到下限,字级(`GRAD` 0→150)推满。字重轴到 1000 之后字形就
 * 不再变黑了,再往下黑只能靠这几条;Google Fonts 上 Roboto Flex 的那个 "Flex" 字样就是这么
 * 调出来的。四条轴各自单调,合成一个量方便做动画。
 */
@OptIn(ExperimentalTextApi::class)
@Composable
fun rememberFlexFont(
    weight: Float,
    width: Float,
    figureHeight: Float = DefaultFigureHeight,
    ink: Float = 0f,
): FontFamily {
    val w = (weight / WeightStep).roundToInt() * WeightStep
    val wd = ((width / WidthStep).roundToInt() * WidthStep).toFloat()
    val fh = ((figureHeight / WidthStep).roundToInt() * WidthStep).toFloat()
    val k = (ink.coerceIn(0f, 1f) * InkSteps).roundToInt() / InkSteps.toFloat()
    return remember(w, wd, fh, k) {
        FontFamily(
            Font(
                R.font.roboto_flex,
                weight = FontWeight(w.coerceIn(100, 1000)),
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(w.coerceIn(100, 1000)),
                    FontVariation.width(wd.coerceIn(25f, 151f)),
                    FontVariation.Setting("YTFI", fh.coerceIn(560f, 788f)),
                    FontVariation.Setting("XOPQ", lerp(96f, 175f, k)),
                    FontVariation.Setting("YOPQ", lerp(79f, 135f, k)),
                    FontVariation.Setting("XTRA", lerp(468f, 323f, k)),
                    FontVariation.Setting("GRAD", lerp(0f, 150f, k)),
                ),
            ),
        )
    }
}

private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction

/** [ink] 量化成这么多档,理由同 [WeightStep]。 */
private const val InkSteps = 10

/**
 * 听视频那块大数字的轴值:"Flex" 字样那种黑体块 —— 字重轴顶满、字宽轴拉满,
 * 笔画与字腔推到 [Ink] 那一档。按住时只换颜色,字形不变。
 */
object ReadoutAxes {
    /** 字重轴的上限。 */
    const val Weight = 1000f
    const val Ink = 0.3f
    const val Width = 151f
    /** `h:mm:ss` 比 `mm:ss` 长,收回一些字宽,而不是缩字号。 */
    const val WidthLong = 125f
    /**
     * `YTFI` 比默认的 738 矮一些,不压到下限。压到 560 时配上 0.6 的 ink,0、5、8 的字腔整个
     * 被填死,真机上读不出是几;矮和黑两头各让一步。
     */
    const val FigureHeight = 650f
}

/**
 * 瘦高的时间读数:定时关闭的倒计时、横划进退时画面中间的读数、拖进度条时的气泡。
 * 后两处叠在画面上,窄而高的数字大一号也不多挡画面;三处同一个 token,一个界面里不混用
 * 几种处理(M3 typography.md 的 editorial treatments)。
 *
 * 字宽轴压到下限,数字高度轴拉到上限;字重取粗,叠在画面上细字会被背景吃掉。
 */
object ClockAxes {
    const val Weight = 700f
    /** 字宽轴的下限。 */
    const val Width = 25f
    /** `YTFI` 轴的上限。 */
    const val FigureHeight = 788f
}

/** 瘦高读数的字形,见 [ClockAxes]。轴值固定,全应用只有这一个实例。 */
@Composable
fun rememberClockFont(): FontFamily =
    rememberFlexFont(ClockAxes.Weight, ClockAxes.Width, ClockAxes.FigureHeight)

private const val WeightStep = 20
private const val WidthStep = 5
private const val DefaultFigureHeight = 738f
