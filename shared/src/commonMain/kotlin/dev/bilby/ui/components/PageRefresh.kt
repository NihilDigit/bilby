package dev.bilby.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 当前页面刷新的栈式注册表,供键盘快捷键 `F5` / `Ctrl+R` 读取状态并调用最上层页面的刷新动作。
 *
 * 栈尾始终是最近进入组合的那一层(二级面板盖在页面上时先刷面板,收起后回到页面本身)。
 * 各可刷新顶栏上的刷新按钮由各自页面直接经 `TopBarRefreshButton` 渲染,不读取此全局注册表,
 * 以免后台返回栈里的页面把刷新按钮泄露到不可刷新页面(如设置/离线)。
 */
@Stable
class PageRefreshRegistry {
    private class Entry(
        refreshing: Boolean,
        var onRefresh: () -> Unit,
    ) {
        var refreshing: Boolean by mutableStateOf(refreshing)
    }

    private val entries = mutableStateListOf<Entry>()

    val canRefresh: Boolean get() = entries.isNotEmpty()
    val refreshing: Boolean get() = entries.lastOrNull()?.refreshing == true

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

private val DefaultPageRefreshRegistry = PageRefreshRegistry()

val LocalPageRefreshRegistry = compositionLocalOf { DefaultPageRefreshRegistry }

/**
 * 若外层宿主(如桌面入口 `Main.kt`)已提供 [LocalPageRefreshRegistry] 则直接复用，使窗口级快捷键
 * 与页面内容共享同一份注册表；否则为当前根节点新建一份独立实例。
 */
@Composable
fun rememberPageRefreshRegistry(): PageRefreshRegistry {
    val current = LocalPageRefreshRegistry.current
    return remember(current) {
        if (current !== DefaultPageRefreshRegistry) current else PageRefreshRegistry()
    }
}

@Composable
fun RegisterPageRefresh(refreshing: Boolean = false, onRefresh: () -> Unit) {
    LocalPageRefreshRegistry.current.Register(refreshing, onRefresh)
}
