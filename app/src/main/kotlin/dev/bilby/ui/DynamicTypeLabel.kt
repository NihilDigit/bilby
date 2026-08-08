package dev.bilby.ui

import androidx.annotation.StringRes
import dev.bilby.R

/**
 * 非视频动态的类型名。动态页和个人空间的动态 tab 共用这一份 —— 两处以前各写了一份中文
 * 字面量在 Repository 里,除了本地化拿不到之外,分支还悄悄分叉过(一处认直播,另一处不认)。
 */
@StringRes
fun dynamicTypeLabel(type: String): Int = when (type) {
    "DYNAMIC_TYPE_DRAW" -> R.string.dynamic_type_draw
    "DYNAMIC_TYPE_ARTICLE" -> R.string.dynamic_type_article
    "DYNAMIC_TYPE_WORD" -> R.string.dynamic_type_word
    "DYNAMIC_TYPE_FORWARD" -> R.string.dynamic_type_forward
    "DYNAMIC_TYPE_LIVE", "DYNAMIC_TYPE_LIVE_RCMD" -> R.string.dynamic_type_live
    else -> R.string.dynamic_type_other
}
