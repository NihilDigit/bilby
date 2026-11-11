package dev.bilby.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowState
import dev.bilby.BiliLog
import dev.bilby.windowFaultMessage
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * 桌面端主窗口:包裹异常自愈([DesktopWindowFaultCoordinator])与显卡复位自愈([GpuDeviceWatch])。
 *
 * Compose Desktop 1.12 在同帧测量与卸载弹层时可能触发 `"RootNodeOwner is already disposed"` 并终止出帧协程;
 * 驱动复位 D3D12 设备时画面也会停止更新。识别这两类可自愈故障后,通过切换 [key] 原地重建窗口恢复渲染,
 * 而不让整个进程退出或留下卡死的空窗。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BilbyWindow(
    onCloseRequest: () -> Unit,
    state: WindowState,
    title: String,
    icon: Painter?,
    onFatalClose: () -> Unit = onCloseRequest,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    val currentFatalClose by rememberUpdatedState(onFatalClose)
    val windowScope = remember { desktopWindowFaultCoordinator.newWindowScope() }
    DisposableEffect(windowScope) {
        onDispose { windowScope.markClosed() }
    }
    val handlerFactory = remember(windowScope) {
        BilbyWindowExceptionHandlerFactory(
            scope = windowScope,
            onFatalClose = { currentFatalClose() },
        )
    }
    CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides handlerFactory) {
        key(GpuDeviceWatch.generation, desktopWindowFaultCoordinator.generation, windowScope.generation) {
            Window(
                onCloseRequest = onCloseRequest,
                title = title,
                icon = icon,
                state = state,
            ) {
                LaunchedEffect(window) {
                    while (true) {
                        delay(DEVICE_CHECK_INTERVAL_MS)
                        if (GpuDeviceWatch.checkLost(window)) break
                    }
                }
                content()
            }
        }
    }
}

private const val DEVICE_CHECK_INTERVAL_MS = 1_000L

internal class WindowFaultScope internal constructor(
    private val coordinator: DesktopWindowFaultCoordinator,
) {
    private enum class Phase { ACTIVE, CLOSING, CLOSED }

    var generation by mutableIntStateOf(0)
        private set
    private var phase = Phase.ACTIVE
    private var queuedGeneration = -1
    private val recoveries = ArrayDeque<Long>()

    fun markClosed() = coordinator.withLock {
        phase = Phase.CLOSED
        queuedGeneration = -1
    }

    internal fun handleLocked(
        throwable: Throwable,
        now: Long,
        onFatalClose: () -> Unit,
    ) {
        if (phase != Phase.ACTIVE) return
        while (recoveries.isNotEmpty() && now - recoveries.first() >= 15_000L) recoveries.removeFirst()
        val unrecoverable = generateSequence(throwable) { it.cause }.take(32)
            .any { it is VirtualMachineError || it is LinkageError }
        val currentGen = generation
        if (!unrecoverable && throwable.isDisposedLayerRace()) {
            coordinator.log(throwable)
            if (queuedGeneration == currentGen) return
            queuedGeneration = currentGen
            coordinator.dispatch {
                coordinator.withLock {
                    if (phase == Phase.ACTIVE && generation == currentGen) {
                        queuedGeneration = -1
                        generation++
                    }
                }
            }
        } else if (!unrecoverable && throwable is RuntimeException && (queuedGeneration == currentGen || recoveries.size < 2)) {
            coordinator.log(throwable)
            if (queuedGeneration == currentGen) return
            recoveries.addLast(now)
            queuedGeneration = currentGen
            coordinator.dispatch {
                coordinator.withLock {
                    if (phase == Phase.ACTIVE && generation == currentGen) {
                        queuedGeneration = -1
                        generation++
                    }
                }
            }
        } else {
            phase = Phase.CLOSING
            queuedGeneration = -1
            BiliLog.e("不可恢复的界面异常,关闭窗口", throwable)
            coordinator.dispatch {
                val shouldRun = coordinator.withLock {
                    if (phase == Phase.CLOSED) false
                    else {
                        phase = Phase.CLOSED
                        true
                    }
                }
                if (shouldRun) onFatalClose()
            }
        }
    }
}

internal class DesktopWindowFaultCoordinator(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    internal val dispatch: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
    internal val log: (Throwable) -> Unit = {
        BiliLog.e("界面渲染或事件异常,重建窗口恢复出帧", it)
    },
) {
    private val handled = ArrayDeque<WeakReference<Throwable>>()
    private val globalScope = WindowFaultScope(this)

    val generation: Int get() = globalScope.generation

    fun newWindowScope(): WindowFaultScope = WindowFaultScope(this)

    @Synchronized
    internal fun <T> withLock(block: () -> T): T = block()

    @Synchronized
    fun report(
        throwable: Throwable,
        isUiThread: Boolean,
        onFatalClose: () -> Unit,
        scope: WindowFaultScope = globalScope,
    ): Boolean {
        if (!isUiThread && !throwable.isDisposedLayerRace()) return false
        if (handled.any { it.get() === throwable }) return true
        handled.addLast(WeakReference(throwable))
        while (handled.size > 64) handled.removeFirst()
        scope.handleLocked(throwable, nowMillis(), onFatalClose)
        return true
    }
}

internal val desktopWindowFaultCoordinator = DesktopWindowFaultCoordinator()

@OptIn(ExperimentalComposeUiApi::class)
private class BilbyWindowExceptionHandlerFactory(
    private val scope: WindowFaultScope,
    private val onFatalClose: () -> Unit,
) : WindowExceptionHandlerFactory {
    override fun exceptionHandler(window: java.awt.Window) = WindowExceptionHandler { throwable ->
        desktopWindowFaultCoordinator.report(
            throwable,
            isUiThread = true,
            scope = scope,
            onFatalClose = { showWindowFault(window, onFatalClose) },
        )
    }
}

private fun showWindowFault(window: java.awt.Window?, onFatalClose: () -> Unit) {
    try {
        val message = runCatching { windowFaultMessage() }
            .onFailure { BiliLog.w("读取窗口异常提示文案失败", it) }
            .getOrDefault("界面发生错误，窗口即将关闭。")
        JOptionPane.showMessageDialog(
            window?.takeIf { it.isDisplayable },
            message,
            "Bilby",
            JOptionPane.ERROR_MESSAGE,
        )
    } finally {
        try {
            window?.dispose()
        } finally {
            onFatalClose()
        }
    }
}

private fun Throwable.isDisposedLayerRace(): Boolean =
    this is IllegalArgumentException && message == "RootNodeOwner is already disposed"
