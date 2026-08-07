package dev.bilby.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import dev.bilby.ui.theme.FixedColors

/**
 * B 站等级徽章。手绘 `Canvas`,不是图片也不是字体 —— 照 PiliPlus 的
 * `common/widgets/svg/level_icon.dart`(`LevelCanvas` + `RenderLevel`)逐坐标移植:
 * 圆角底框(右上角一个小凸起)+ 固定路径的「LV」两个字母 + 七段数码管画的等级数字。
 * 坐标常量原样照抄那份文件,没有重新推。
 *
 * 底色是 B 站的等级色,不跟主题 —— 见 [FixedColors.levelBackground]。
 *
 * @param senior 硬核会员。true 时右侧延长出一截并画一个闪电。
 *
 * **移植取舍,两处没有照搬原图**(渲染高度只有 11~24dp,这个尺度上差异不可见):
 * - 「LV」字形里 V 的两个圆角顶点,原图用 `Path.arcToPoint`(给定半径和终点算圆弧)画,
 *   Compose 的 `Path` 没有这个 API(只有按角度定义起止的 `arcTo`),这里用二次贝塞尔近似。
 * - 硬核会员的闪电,原图直接画 Material Icons 字体里的字形(`Icons.bolt_rounded`),
 *   这边没有同一套字体资源,换成 Compose 自己的 `Icons.Filled.Bolt` 矢量,按大致同样的
 *   位置和比例摆放,不是像素级对齐。
 */
@Composable
fun LevelBadge(level: Int, senior: Boolean = false, height: Dp, modifier: Modifier = Modifier) {
    val backgroundColor = FixedColors.levelBackground(level)
    val digitColor = Color.White
    val boltPainter = if (senior) rememberVectorPainter(Icons.Filled.Bolt) else null
    val aspect = (if (senior) LevelCanvas.ExtendR else LevelCanvas.TotalR) / LevelCanvas.TotalB
    // matchHeightConstraintsFirst = true:高度是外部定死的(height 参数),宽度要跟着算——
    // 默认反过来(先凑宽度)在这里没有宽度约束可凑,会退化成一个不可预期的尺寸。
    Canvas(modifier = modifier.height(height).aspectRatio(aspect, matchHeightConstraintsFirst = true)) {
        val factor = size.height / LevelCanvas.TotalB
        scale(factor, factor, pivot = Offset.Zero) {
            with(LevelCanvas) {
                drawBack(backgroundColor, senior, boltPainter)
                drawLv(digitColor)
                drawDigit(level, digitColor)
            }
        }
    }
}

/**
 * 坐标系照抄 `level_icon.dart` 的 `LevelCanvas`:总宽 930(硬核会员延长到 1250)、
 * 总高 466,原图上层的 `scale(size.height / totalB)` 换算成本地坐标 1:1 对应 dp。
 */
private object LevelCanvas {
    // ---- 外框 ----
    const val TotalR = 930f
    const val ExtendR = 1250f
    const val TotalB = 466f
    private const val BlockTop = 48f
    private val CornerR = CornerRadius(27f)

    fun DrawScope.drawBack(color: Color, bolt: Boolean, boltPainter: VectorPainter?) {
        val right = if (bolt) ExtendR else TotalR
        val body = Path().apply {
            addRoundRect(
                RoundRect(
                    Rect(0f, BlockTop, right, TotalB),
                    topLeft = CornerR,
                    topRight = CornerRadius.Zero,
                    bottomLeft = CornerR,
                    bottomRight = CornerR,
                ),
            )
        }
        val notch = Path().apply {
            addRoundRect(
                RoundRect(
                    Rect(576f, 0f, right, BlockTop + 1f),
                    topLeft = CornerR,
                    topRight = CornerR,
                    bottomLeft = CornerRadius.Zero,
                    bottomRight = CornerRadius.Zero,
                ),
            )
        }
        drawPath(body, color)
        drawPath(notch, color)

        // 闪电只在硬核会员时画,大致落在延长出来的那一截右侧,不是像素级对齐(见类头注释)。
        if (bolt && boltPainter != null) {
            translate(left = 850f, top = 20f) {
                with(boltPainter) { draw(size = Size(400f, 400f), colorFilter = ColorFilter.tint(Color.White)) }
            }
        }
    }

    // ---- 「LV」字形,固定路径,原图预先录制成 Picture,这里等价地建一次性 Path ----
    private const val VLeft = 296f
    private const val LvTop = 106f
    private const val Llr = 123f
    private const val VtB = 282f
    private const val BotY = 347f
    private const val BotYB = 415f
    private val R20 = CornerRadius(20f)

    private val lvPath: Path by lazy {
        Path().apply {
            // 注意:Compose 的 RoundRect(rect, topLeft, topRight, bottomRight, bottomLeft) 是
            // "顺时针"参数序,不是读起来顺手的 (tl,tr,bl,br) —— 这里全用具名参数,不留位置错位的空子。
            // L
            addRoundRect(
                RoundRect(Rect(56f, LvTop, Llr, BotYB), topLeft = R20, topRight = R20, bottomRight = CornerRadius.Zero, bottomLeft = R20),
            )
            addRoundRect(
                RoundRect(Rect(Llr - 1f, BotY, 256f, BotYB), topLeft = CornerRadius.Zero, topRight = R20, bottomRight = R20, bottomLeft = CornerRadius.Zero),
            )
            // V 的两条竖笔
            addRoundRect(
                RoundRect(Rect(VLeft, LvTop, 363f, VtB + 1f), topLeft = R20, topRight = R20, bottomRight = CornerRadius.Zero, bottomLeft = CornerRadius.Zero),
            )
            addRoundRect(
                RoundRect(Rect(476f, LvTop, 543f, VtB + 1f), topLeft = R20, topRight = R20, bottomRight = CornerRadius.Zero, bottomLeft = CornerRadius.Zero),
            )
            // V 底部的尖角:原图用 arcToPoint(radius: 50) 画四段圆弧,这里用二次贝塞尔近似
            // (类头注释里说明过为什么)。
            moveTo(VLeft, VtB)
            lineTo(VLeft, 292f)
            quadraticTo(VLeft, 313f, 300f, 313f)
            lineTo(395f, 408f)
            quadraticTo(419.5f, 408f, 444f, 408f)
            lineTo(539f, 313f)
            quadraticTo(543f, 313f, 543f, 292f)
            lineTo(543f, VtB)
            lineTo(476f, VtB)
            lineTo(419.5f, 340f)
            lineTo(363f, VtB)
            close()
        }
    }

    fun DrawScope.drawLv(color: Color) = drawPath(lvPath, color)

    // ---- 七段数码管 ----
    private const val Left = 629f
    private const val Right = 877f
    private const val ColW = 68f
    private val LColR = Left + ColW // 697
    private val RColL = Right - ColW // 809

    private const val RowH = 68f
    private const val RowSp = 146f
    private const val TopY = 55f
    private val TopYB = TopY + RowH // 123
    private val MidY = TopY + RowSp // 201
    private val MidYB = MidY + RowH // 269
    private val BotY2 = MidY + RowSp // 347,跟上面 LV 的 BotY 同值,分开命名避免混淆
    private val BotYB2 = BotY2 + RowH // 415
    private val MidMid = (MidY + MidYB) / 2 // 235

    /** 数字 1 是两根竖条,不走七段拼接 —— 跟原图 `_draw1` 一致,单独画。 */
    private fun draw1(): Path = Path().apply {
        addRoundRect(RoundRect(Rect(673f, BotY2, 833f, BotYB2), CornerRadius(20f)))
        addRoundRect(
            RoundRect(Rect(673f, TopY, 787f, TopYB), topLeft = R20, topRight = R20, bottomRight = CornerRadius.Zero, bottomLeft = R20),
        )
        addRect(Rect(719f, TopYB, 787f, BotY2))
    }

    /** bit 表:a..g 七段,和原图 `bits` 常量按位对应,`0x40` 是 a 段、`0x01` 是 g 段。 */
    private fun bitsOf(digit: Int): Int = when (digit) {
        0 -> 0x7E
        2 -> 0x6D
        3 -> 0x79
        4 -> 0x33
        5 -> 0x5B
        6 -> 0x5F
        7 -> 0x70
        8 -> 0x7F
        9 -> 0x7B
        else -> 0x4F // 无法识别的等级按 'E' 显示,原图同样的兜底
    }

    private fun segments(a: Boolean, b: Boolean, c: Boolean, d: Boolean, e: Boolean, f: Boolean, g: Boolean): Path =
        Path().apply {
            // 横段
            if (a) addRR(Left, TopY, Right, TopYB, R20, R20, cr(f), cr(b))
            if (g) addRR(Left, MidY, Right, MidYB, cr(f), cr(b), cr(e), cr(c))
            if (d) addRR(Left, BotY2, Right, BotYB2, cr(e), cr(c), R20, R20)

            // 竖段。上下端点与圆角取决于相邻横段是否画出——跟横段拼接处不留缝、
            // 不重叠圆角,是原图 `_drawSegments` 里最容易出错的部分,逐条抄的。
            if (f) {
                val top = (if (a) TopYB else TopY) - 1f
                val bottom = (if (g) MidY else if (e) MidMid else MidYB) + 1f
                val rTop = if (a) CornerRadius.Zero else R20
                val rBot = if (g || e) CornerRadius.Zero else R20
                addRR(Left, top, LColR, bottom, rTop, rTop, rBot, rBot)
            }
            if (b) {
                val top = (if (a) TopYB else TopY) - 1f
                val bottom = (if (g) MidY else if (c) MidMid else MidYB) + 1f
                val rTop = if (a) CornerRadius.Zero else R20
                val rBot = if (g || c) CornerRadius.Zero else R20
                addRR(RColL, top, Right, bottom, rTop, rTop, rBot, rBot)
            }
            if (e) {
                val top = (if (g) MidYB else if (f) MidMid else MidY) - 1f
                val bottom = (if (d) BotY2 else BotYB2) + 1f
                val rTop = if (g || f) CornerRadius.Zero else R20
                val rBot = if (d) CornerRadius.Zero else R20
                addRR(Left, top, LColR, bottom, rTop, rTop, rBot, rBot)
            }
            if (c) {
                val top = (if (g) MidYB else if (b) MidMid else MidY) - 1f
                val bottom = (if (d) BotY2 else BotYB2) + 1f
                val rTop = if (g || b) CornerRadius.Zero else R20
                val rBot = if (d) CornerRadius.Zero else R20
                addRR(RColL, top, Right, bottom, rTop, rTop, rBot, rBot)
            }
        }

    private fun cr(zero: Boolean) = if (zero) CornerRadius.Zero else R20

    private fun Path.addRR(l: Float, t: Float, r: Float, b: Float, tl: CornerRadius, tr: CornerRadius, bl: CornerRadius, br: CornerRadius) {
        addRoundRect(RoundRect(Rect(l, t, r, b), tl, tr, br, bl))
    }

    fun DrawScope.drawDigit(digit: Int, color: Color) {
        val path = if (digit == 1) {
            draw1()
        } else {
            val bits = bitsOf(digit)
            segments(
                a = bits and 0x40 != 0,
                b = bits and 0x20 != 0,
                c = bits and 0x10 != 0,
                d = bits and 0x08 != 0,
                e = bits and 0x04 != 0,
                f = bits and 0x02 != 0,
                g = bits and 0x01 != 0,
            )
        }
        drawPath(path, color)
    }
}
