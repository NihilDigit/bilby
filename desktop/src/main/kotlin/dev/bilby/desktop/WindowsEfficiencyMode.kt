package dev.bilby.desktop

import dev.bilby.BiliLog
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle

/**
 * Windows 进程级能效模式（EcoQoS）切换器。
 *
 * 通过 JDK 25 FFM 调用 `kernel32.dll` 的 `SetProcessInformation(ProcessPowerThrottling)` 与
 * `SetPriorityClass`：
 * - 开启时同时设置 `EXECUTION_SPEED | IGNORE_TIMER_RESOLUTION` 与 `IDLE_PRIORITY_CLASS`，
 *   使系统将线程调度到能效核并忽略高分辨率时钟请求，任务管理器同步显示绿叶能效模式标记；
 * - 关闭时立即清零 `StateMask` 并恢复 `NORMAL_PRIORITY_CLASS`。
 *
 * 非 Windows 平台或旧版系统 API 不可用时静默降级并通过 [BiliLog] 记录一次。
 */
internal object WindowsEfficiencyMode {
    private val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private const val PROCESS_POWER_THROTTLING = 4
    private const val PROCESS_POWER_THROTTLING_CURRENT_VERSION = 1
    private const val PROCESS_POWER_THROTTLING_EXECUTION_SPEED = 0x1
    private const val PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION = 0x4
    private const val STATE_STRUCT_BYTES = 12 // 3 * ULONG (Version, ControlMask, StateMask)

    private const val NORMAL_PRIORITY_CLASS = 0x00000020
    private const val IDLE_PRIORITY_CLASS = 0x00000040

    private class Kernel32Handles(
        val getCurrentProcess: MethodHandle,
        val setProcessInformation: MethodHandle,
        val setPriorityClass: MethodHandle,
    )

    private val handles: Kernel32Handles? by lazy {
        if (!isWindows) return@lazy null
        runCatching {
            val linker = Linker.nativeLinker()
            val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
            val getCurrentProcess = linker.downcallHandle(
                kernel32.find("GetCurrentProcess").orElseThrow(),
                FunctionDescriptor.of(ADDRESS),
            )
            val setProcessInformation = linker.downcallHandle(
                kernel32.find("SetProcessInformation").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT),
            )
            val setPriorityClass = linker.downcallHandle(
                kernel32.find("SetPriorityClass").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT),
            )
            Kernel32Handles(getCurrentProcess, setProcessInformation, setPriorityClass)
        }.onFailure {
            BiliLog.w("初始化 Windows 能效模式 FFM 句柄失败", it)
        }.getOrNull()
    }

    private var currentEnabled: Boolean? = null
    private var ignoreTimerResolutionSupported: Boolean = true

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (!isWindows) return
        if (currentEnabled == enabled) return
        val api = handles ?: return
        runCatching {
            val process = api.getCurrentProcess.invokeExact() as MemorySegment
            val throttlingOk = applyPowerThrottling(api, process, enabled)
            val priorityClass = if (enabled) IDLE_PRIORITY_CLASS else NORMAL_PRIORITY_CLASS
            val priorityOk = (api.setPriorityClass.invokeExact(process, priorityClass) as Int) != 0
            if (!throttlingOk || !priorityOk) {
                BiliLog.w(
                    "Windows 能效模式切换部分失败 enabled=$enabled throttlingOk=$throttlingOk priorityOk=$priorityOk",
                )
            }
            currentEnabled = enabled
        }.onFailure {
            BiliLog.w("切换 Windows 能效模式失败 enabled=$enabled", it)
        }
    }

    private fun applyPowerThrottling(
        api: Kernel32Handles,
        process: MemorySegment,
        enabled: Boolean,
    ): Boolean {
        val fullMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED or PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION
        if (ignoreTimerResolutionSupported) {
            if (invokeSetPowerThrottling(api, process, fullMask, if (enabled) fullMask else 0)) {
                return true
            }
            // 旧版 Windows 10 不支持 IGNORE_TIMER_RESOLUTION，回落到 EXECUTION_SPEED
            ignoreTimerResolutionSupported = false
        }
        val basicMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED
        return invokeSetPowerThrottling(api, process, basicMask, if (enabled) basicMask else 0)
    }

    private fun invokeSetPowerThrottling(
        api: Kernel32Handles,
        process: MemorySegment,
        controlMask: Int,
        stateMask: Int,
    ): Boolean {
        Arena.ofConfined().use { arena ->
            val state = arena.allocate(STATE_STRUCT_BYTES.toLong())
            state.set(JAVA_INT, 0L, PROCESS_POWER_THROTTLING_CURRENT_VERSION)
            state.set(JAVA_INT, 4L, controlMask)
            state.set(JAVA_INT, 8L, stateMask)
            val result = api.setProcessInformation.invokeExact(
                process,
                PROCESS_POWER_THROTTLING,
                state,
                STATE_STRUCT_BYTES,
            ) as Int
            return result != 0
        }
    }
}
