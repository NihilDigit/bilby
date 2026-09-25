package dev.bilby.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.util.lerp

/**
 * 「在放」的符号:队列里正在播的那一条、以及全应用的「正在直播」。**静止时就是 Material 的
 * `GraphicEq` 图标**,动起来时图标里的五根竖条以中线为轴各自伸缩。
 *
 * 按那枚图标的几何重画(24×24 视口,五根 2 宽的竖条,中线 y=12,高 4/12/20/12/4,对照
 * icons-extended 1.7.8 的 `GraphicEqKt` 反汇编核过)。直播曾有自己的一套三根竖条,两个符号
 * 说的是同一件事("这里在放"),长两个样子就得各认一遍;这一枚本身就长得像会动的东西,静止时
 * 还是那个认得的图标。
 *
 * 自己画而不是用 GIF(PiliPlus 直播用的是 `assets/images/live/live.gif`):颜色跟主题走,任意
 * 尺寸不糊。**`Canvas` 没有固有尺寸,尺寸必须由调用方在 [modifier] 上给**,不给就是 0×0 且不报错
 * (见 `Dimens.PlayingIndicatorInline`)。
 *
 * @param active 在放就跳,不在放就停回图标原样。直播恒为 true。停的时候平滑落回,不冻在随机的
 *   一帧;动与不动本身就是信息。系统关掉动画时 Compose 的无限动画直接停在终值,不另做判断。
 * @param contentDescription 为 null 时不进读屏:直播格旁边已经写着字,再念一遍是重复。
 */
@Composable
fun PlayingIndicator(
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    val transition = rememberInfiniteTransition(label = "playing")
    // 每根在图标原本的高度和另一个高度之间往返,**从原高起跳**:五根各自从同一个最矮值起跳的话,
    // 第一帧是一条扁线,看起来像卡了一下。
    val bouncing = IconBarHeights.indices.map { index ->
        transition.animateFloat(
            initialValue = IconBarHeights[index],
            targetValue = PartnerBarHeights[index],
            animationSpec = infiniteRepeatable(tween(BarPeriodsMillis[index]), RepeatMode.Reverse),
            label = "bar$index",
        )
    }
    // 在原高与跳动高度之间按活跃度插值,不让弹簧去追一个每帧都在变的目标 —— 那样起伏会被抹平。
    val activeness by animateFloatAsState(if (active) 1f else 0f, label = "playingActive")
    val semantics = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(modifier = modifier.then(semantics)) {
        val unit = size.width / IconViewport
        IconBarHeights.forEachIndexed { index, restHeight ->
            val height = lerp(restHeight, bouncing[index].value, activeness) * unit
            drawRect(
                color = color,
                topLeft = Offset(x = IconBarLefts[index] * unit, y = IconCenter * unit - height / 2),
                size = Size(IconBarWidth * unit, height),
            )
        }
    }
}

/** `Icons.Filled.GraphicEq` 的几何,单位是它 24×24 的视口。 */
private const val IconViewport = 24f
private const val IconCenter = 12f
private const val IconBarWidth = 2f
private val IconBarLefts = listOf(3f, 7f, 11f, 15f, 19f)
private val IconBarHeights = listOf(4f, 12f, 20f, 12f, 4f)

/** 每根往返的另一端。落在 4..20 之内,不超出图标本身的轮廓;高的变矮、矮的变高。 */
private val PartnerBarHeights = listOf(14f, 4f, 8f, 20f, 12f)

/** 五根的周期互不相同且不成整数比,合起来不会一眼看出在循环。 */
private val BarPeriodsMillis = listOf(410, 530, 370, 470, 590)
