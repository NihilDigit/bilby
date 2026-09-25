package dev.bilby.ui.components

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

/**
 * 行内表情的绘制高度。评论、动态、专栏三处共用这一处判断 —— 它们在读者眼里是同一个东西,
 * 而这三处的正文字号分别是 14、16、16sp,各自按字号折算会让同一个表情在三页里三个大小。
 *
 * **上限是这一行的行高。** 装扮表情(`emoji.size == 2`)按 [InlineEmoteSize] 翻倍是 44sp,
 * 而动态正文的行高只有 26sp:Compose 的 `lineHeight` 是硬定的行距,占位符比它高时不会把行
 * 撑开,而是压到上下两行的字上 —— 表现是大表情糊在前后行的文字上,自己也被裁掉一截。
 * PiliPlus 那边不会撞上这条:Flutter 的 `WidgetSpan` 会把行撑高。
 *
 * @param scale 接口给的 `emoji.size`,1 是跟着文字走的小表情,2 是装扮表情。
 */
internal fun inlineEmoteSize(style: TextStyle, scale: Float = 1f): TextUnit {
    val wanted = InlineEmoteSize * scale
    val lineHeight = style.lineHeight
    if (!lineHeight.isSpecified || !lineHeight.isSp) return wanted
    return minOf(wanted.value, lineHeight.value).sp
}

/**
 * 小表情的基准高度。取值对着 PiliPlus 的 `20.0 * emoji.size` 略大一档:那个数是配 14sp 正文
 * 的,这里三处里有两处正文是 16sp。
 */
private val InlineEmoteSize = 22.sp
