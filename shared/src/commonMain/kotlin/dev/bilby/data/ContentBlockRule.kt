package dev.bilby.data

import kotlinx.serialization.Serializable

/**
 * 本地内容屏蔽规则：按 UP 主 mid 或标题/UP 名关键词（忽略大小写）在动态流与搜索结果中实时过滤。
 */
@Serializable
data class ContentBlockRule(
    val kind: Kind,
    val value: String,
    val label: String,
) {
    @Serializable
    enum class Kind { Up, Keyword }
}

/**
 * 由 [ContentBlockRule] 列表编译出的不可变匹配器。
 */
data class ContentBlockMatcher(
    val blockedUpMids: Set<Long> = emptySet(),
    val blockedKeywords: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = blockedUpMids.isEmpty() && blockedKeywords.isEmpty()

    fun matchesVideo(
        upMid: Long = 0L,
        upName: String = "",
        title: String = "",
    ): Boolean {
        if (upMid != 0L && upMid in blockedUpMids) return true
        if (blockedKeywords.isNotEmpty()) {
            for (kw in blockedKeywords) {
                if (title.contains(kw, ignoreCase = true) || upName.contains(kw, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    fun matchesUser(mid: Long, name: String): Boolean {
        if (mid != 0L && mid in blockedUpMids) return true
        if (blockedKeywords.isNotEmpty()) {
            for (kw in blockedKeywords) {
                if (name.contains(kw, ignoreCase = true)) return true
            }
        }
        return false
    }

    companion object {
        val EMPTY = ContentBlockMatcher()

        fun fromRules(rules: List<ContentBlockRule>): ContentBlockMatcher {
            if (rules.isEmpty()) return EMPTY
            val mids = mutableSetOf<Long>()
            val keywords = mutableListOf<String>()
            for (rule in rules) {
                when (rule.kind) {
                    ContentBlockRule.Kind.Up -> rule.value.toLongOrNull()?.takeIf { it > 0L }?.let { mids += it }
                    ContentBlockRule.Kind.Keyword -> rule.value.trim().takeIf { it.isNotEmpty() }?.let { keywords += it }
                }
            }
            return ContentBlockMatcher(blockedUpMids = mids, blockedKeywords = keywords)
        }
    }
}
