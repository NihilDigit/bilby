package dev.bilby.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.bilby.BiliLog
import java.awt.KeyboardFocusManager
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * Windows 的系统「动画效果」开关(`SPI_GETCLIENTAREAANIMATION`,即「设置 → 辅助功能 → 视觉效果 → 动画效果」)。
 *
 * Compose Desktop 不自己读取系统的减弱动效开关。这里在后台守护线程上查询,并在两处时机重读:
 * - 系统广播 `WM_SETTINGCHANGE`(由桌面窗口过程调用 [onSystemSettingChange]);
 * - 应用从后台重新获得活动窗口焦点。
 */
object SystemReducedMotion {
    private const val SPI_GETCLIENTAREAANIMATION = 0x1042

    var reduced: Boolean by mutableStateOf(false)
        private set

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")
    private val started = AtomicBoolean(false)
    private val reader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Bilby-ReducedMotion").apply { isDaemon = true }
    }

    private val systemParametersInfo by lazy {
        Linker.nativeLinker().downcallHandle(
            SymbolLookup.libraryLookup("user32", Arena.global()).find("SystemParametersInfoW").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT),
        )
    }

    internal fun ensureStarted() {
        if (!isWindows || !started.compareAndSet(false, true)) return
        refresh()
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addPropertyChangeListener("activeWindow") { event ->
            if (event.oldValue == null && event.newValue != null) refresh()
        }
    }

    fun onSystemSettingChange() {
        if (started.get()) refresh()
    }

    private fun refresh() {
        if (!isWindows) return
        reader.execute {
            val next = runCatching { !clientAreaAnimation() }
                .onFailure { BiliLog.w("读取系统动画效果开关失败", it) }
                .getOrNull() ?: return@execute
            SwingUtilities.invokeLater {
                if (reduced != next) reduced = next
            }
        }
    }

    private fun clientAreaAnimation(): Boolean = Arena.ofConfined().use { arena ->
        val enabled = arena.allocate(JAVA_INT)
        val ok = systemParametersInfo.invokeWithArguments(SPI_GETCLIENTAREAANIMATION, 0, enabled, 0) as Int
        check(ok != 0) { "SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION) failed" }
        enabled.get(JAVA_INT, 0) != 0
    }
}
