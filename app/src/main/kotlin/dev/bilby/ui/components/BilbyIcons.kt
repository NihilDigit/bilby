package dev.bilby.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 这个应用自己画的图标。
 *
 * 目前只有弹幕一个,原因是 Material Symbols 里能读成"弹幕"的符号在播放页**已经各有主人**:
 * 气泡是评论(标签行左边就写着"评论"),字幕框是字幕(控制条上那一个)。借用任何一个都会让
 * 同一页里一个符号说两件事。PiliPlus 遇到的是同一个问题,它的解法也是自带一对字形
 * (`CustomIcons.dm_on` / `dm_off`,`pages/video/view.dart:1424-1427`)。
 *
 * 开与关是**两个字形**而不是一个字形换颜色:只靠颜色的话色觉障碍用户读不出当前是开还是关,
 * 风格指南 §2.6 对导航栏写的是同一条。
 */
object BilbyIcons {

    /**
     * 弹幕(开)。一块屏 + 三条错开飞过的字。
     *
     * **三条线错开而不是左对齐**,是为了和字幕那个图标区分开 —— 后者的线贴着框底并且对齐,
     * 表达的是"画面底下的一行字";弹幕要表达的是"从右往左穿过整块画面",错开的起点就是它。
     */
    val Danmaku: ImageVector by lazy { danmakuIcon("Danmaku", slashed = false) }

    /** 弹幕(关)。同一块屏加一道斜杠,照 Material 自己 `*Off` 那一族的写法。 */
    val DanmakuOff: ImageVector by lazy { danmakuIcon("DanmakuOff", slashed = true) }
}

/**
 * 两个字形共用的骨架:圆角屏框 + 三条飞过的字,[slashed] 再加一道斜杠。
 *
 * 全部用描边而不是填充。Material 那一套图标是填充路径,但这一个的内容是"框里有几条细线",
 * 填充版要把每条线画成一个细长矩形,坐标翻倍且改一次要动六个点。描边一样吃 `Icon` 的
 * tint —— 那是一层 ColorFilter,填充和描边都过。
 */
private fun danmakuIcon(name: String, slashed: Boolean): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = FrameStroke,
            strokeLineJoin = StrokeJoin.Round,
            strokeLineCap = StrokeCap.Round,
        ) {
            // 屏框。上下留 5,左右留 3:弹幕是横着飞的,框比正方形扁一点才像一块画面。
            moveTo(5.5f, 5f)
            lineTo(18.5f, 5f)
            arcToRelative(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.5f, 2.5f)
            lineTo(21f, 16.5f)
            arcToRelative(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.5f, 2.5f)
            lineTo(5.5f, 19f)
            arcToRelative(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, -2.5f, -2.5f)
            lineTo(3f, 7.5f)
            arcToRelative(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 2.5f, -2.5f)
            close()
        }
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = TextStroke,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(6.5f, 9f)
            lineTo(11.5f, 9f)
            moveTo(12.5f, 12f)
            lineTo(17.5f, 12f)
            moveTo(6.5f, 15f)
            lineTo(10.5f, 15f)
            if (slashed) {
                moveTo(4.5f, 4.5f)
                lineTo(19.5f, 19.5f)
            }
        }
    }.build()

/**
 * 框比字粗一档。等粗的话三条线会和框糊成一片 —— 这个图标最小要在 20dp 上读得出来
 * ([dev.bilby.ui.theme.Dimens.IconInline]),那时 1.6dp 的笔画只有两三个物理像素。
 */
private const val FrameStroke = 1.7f
private const val TextStroke = 1.5f
