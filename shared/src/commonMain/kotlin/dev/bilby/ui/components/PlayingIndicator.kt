package dev.bilby.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.util.lerp
import dev.bilby.elapsedRealtimeMillis
import dev.bilby.ui.LocalWindowVisible
import dev.bilby.ui.theme.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.delay

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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayingIndicator(
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    contentDescription: String? = null,
) {
    val reducedMotion = rememberReducedMotion()
    val windowVisible = LocalWindowVisible.current
    // 在原高与跳动高度之间按活跃度插值,不让弹簧去追一个每帧都在变的目标 —— 那样起伏会被抹平。
    // 以 ~30fps 节流推进单一计时状态并在 Canvas 绘制阶段直接计算五根竖条的余弦往返,
    // 零帧级重组,静止落稳、减弱动效开启或窗口不可见时立即停住,不在 120-240Hz 屏上每 vsync 空转。
    val activeness by animateFloatAsState(
        targetValue = if (active && !reducedMotion && windowVisible) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "playingActive",
    )
    val animating = !reducedMotion && windowVisible && (active || activeness > 0.001f)
    val elapsedMillis = remember { mutableLongStateOf(0L) }
    LaunchedEffect(animating) {
        if (!animating) return@LaunchedEffect
        var lastMillis = elapsedRealtimeMillis()
        while (true) {
            delay(IndicatorFrameDelayMillis)
            val now = elapsedRealtimeMillis()
            val delta = (now - lastMillis).coerceIn(0L, MaxIndicatorFrameDeltaMillis)
            lastMillis = now
            elapsedMillis.longValue += delta
        }
    }
    val semantics = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics { this.contentDescription = contentDescription }
    }
    Canvas(modifier = modifier.then(semantics)) {
        val unit = size.width / IconViewport
        val barWidth = IconBarWidth * unit
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        // 只在 Canvas 绘制阶段读取 elapsedMillis,避免每帧触发重组。
        val elapsed = if (animating) elapsedMillis.longValue else 0L
        IconBarHeights.forEachIndexed { index, restHeight ->
            val height = if (animating) {
                val period = BarPeriodsMillis[index]
                val cycle = period * 2L
                // 每根从原高起跳,按半周期余弦在 restHeight 与 PartnerBarHeights 之间平滑往返。
                val phase = (elapsed % cycle).toFloat() / period * PiFloat
                val fraction = 0.5f * (1f - cos(phase))
                val targetHeight = lerp(restHeight, PartnerBarHeights[index], fraction)
                lerp(restHeight, targetHeight, activeness) * unit
            } else {
                restHeight * unit
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(x = IconBarLefts[index] * unit, y = IconCenter * unit - height / 2),
                size = Size(barWidth, height),
                cornerRadius = radius,
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

/** 跳动节流间隔（~30fps）与单帧最大耗时钳制。 */
private const val IndicatorFrameDelayMillis = 33L
private const val MaxIndicatorFrameDeltaMillis = 100L
private val PiFloat = PI.toFloat()
