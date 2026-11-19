package dev.bilby.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/**
 * 当前页面页内搜索的栈式注册表，供桌面端 `Ctrl+F` 优先唤起当前页面的页内搜索。
 *
 * 与 [PageRefreshRegistry] 对称：栈尾始终是最近进入组合的那一层；若当前页未注册页内搜索，
 * 全局 `Ctrl+F` 回退至主搜索页。
 */
class PageSearchRegistry {
    private class Entry(
        var enabled: Boolean,
        var onTrigger: () -> Unit,
    )

    private val entries = ArrayList<Entry>()

    @Composable
    fun Register(enabled: Boolean, onTrigger: () -> Unit) {
        val entry = remember { Entry(enabled, onTrigger) }
        SideEffect {
            entry.enabled = enabled
            entry.onTrigger = onTrigger
        }
        DisposableEffect(this, entry) {
            entries.add(entry)
            onDispose { entries.remove(entry) }
        }
    }

    fun trigger(): Boolean {
        val target = entries.lastOrNull() ?: return false
        if (!target.enabled) return false
        target.onTrigger()
        return true
    }
}

val LocalPageSearchRegistry = compositionLocalOf { PageSearchRegistry() }

@Composable
fun RegisterPageSearch(enabled: Boolean = true, onTrigger: () -> Unit) {
    LocalPageSearchRegistry.current.Register(enabled, onTrigger)
}

private val WhitespaceRegex = Regex("\\s+")

/**
 * 媒体库本地搜索匹配：空/全空白词全部放行；非空词按空白拆分为多段，
 * 全部词段都在任一字段中大小写不敏感命中时返回 true。
 */
internal fun matchesLibraryQuery(query: String, vararg fields: String): Boolean {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return true
    val terms = trimmed.split(WhitespaceRegex)
    return terms.all { term ->
        fields.any { field -> field.contains(term, ignoreCase = true) }
    }
}
