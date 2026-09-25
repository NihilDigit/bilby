package dev.bilby.ui

import dev.bilby.resources.*
import org.jetbrains.compose.resources.StringResource
import java.util.Locale

/**
 * 应用语言。**跟随系统、简体中文、English 三项**,就是现有的两套文案(`values`、`values-en`)。
 * 选择存在哪里、怎么生效由平台决定,见 [SystemActions.applyLanguage]。
 */
enum class AppLanguage(
    /** BCP 47 标签;跟随系统为 null。 */
    val tag: String?,
    val label: StringResource,
) {
    System(null, Res.string.settings_language_system),

    // 简体中文的资源在默认的 `values` 里,没有 values-zh:任何中文 Locale 都落到它。
    SimplifiedChinese("zh-CN", Res.string.settings_language_zh_hans),
    English("en", Res.string.settings_language_en),
    ;

    companion object {
        /** 按语言匹配,不按完整标签:系统给的可能是 zh-Hans-CN 之类更长的写法。 */
        fun fromTag(tag: String?): AppLanguage {
            if (tag == null) return System
            val language = Locale.forLanguageTag(tag).language
            return entries.firstOrNull { it.tag != null && Locale.forLanguageTag(it.tag).language == language }
                ?: System
        }
    }
}
