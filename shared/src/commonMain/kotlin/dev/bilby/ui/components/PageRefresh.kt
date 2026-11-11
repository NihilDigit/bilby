package dev.bilby.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/**
 * 当前页面刷新的栈式注册表,供桌面端 `F5` / `Ctrl+R` 调用最上层页面的刷新动作。
 *
 * 栈尾始终是最近进入组合的那一层(二级面板盖在页面上时先刷面板,收起后回到页面本身)。
 */
class PageRefreshRegistry {
    private class Entry(
        var refreshing: Boolean,
        var onRefresh: () -> Unit,
    )

    private val entries = ArrayList<Entry>()

    @Composable
    fun Register(refreshing: Boolean, onRefresh: () -> Unit) {
        val entry = remember { Entry(refreshing, onRefresh) }
        SideEffect {
            entry.refreshing = refreshing
            entry.onRefresh = onRefresh
        }
        DisposableEffect(this, entry) {
            entries.add(entry)
            onDispose { entries.remove(entry) }
        }
    }

    fun trigger(): Boolean {
        val target = entries.lastOrNull() ?: return false
        if (!target.refreshing) target.onRefresh()
        return true
    }
}

val LocalPageRefreshRegistry = compositionLocalOf { PageRefreshRegistry() }

@Composable
fun RegisterPageRefresh(refreshing: Boolean = false, onRefresh: () -> Unit) {
    LocalPageRefreshRegistry.current.Register(refreshing, onRefresh)
}
