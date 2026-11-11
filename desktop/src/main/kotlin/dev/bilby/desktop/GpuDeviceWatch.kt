package dev.bilby.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.bilby.BiliLog
import org.jetbrains.skiko.SkiaLayer
import java.awt.Container
import java.awt.Window
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 显卡设备失效(device removed / TDR)时主动重建窗口,避免 Skiko 在后续调整窗口尺寸时空指针崩溃。
 *
 * 驱动复位 GPU 引擎、热升级或休眠唤醒异常时,系统会将当前进程的 D3D12 设备标为失效。
 * Skiko 0.150 的 Direct3DRedrawer 不检查 HRESULT:失效后普通帧失败被吞掉,画面冻住;下一次调整窗口
 * 尺寸时 `makeDirectXSurface` 内 `GetBuffer` 失败留下空指针并直接解引用,导致整个 JVM 崩溃。
 *
 * 做法与 Piko 的同名类相同:从 `SkiaLayer.getRedrawer$skiko()` 取出 `Direct3DRedrawer.device`,
 * 读取第 6 个指针槽上的 `ID3D12Device`,经 FFM 调虚表第 37 项 `GetDeviceRemovedReason`。
 * 一旦返回非 0(非 `S_OK`),停止触碰该窗口画布并将 [generation] 加一,由 [BilbyWindow] 重建窗口与新设备。
 */
internal object GpuDeviceWatch {
    var generation by mutableIntStateOf(0)
        private set

    private var lastReportMillis = 0L
    private var attached = false
    private var disabled = !System.getProperty("os.name").orEmpty().startsWith("Windows")

    private val linker = Linker.nativeLinker()
    private val getRemovedReasonDesc = FunctionDescriptor.of(JAVA_INT, ADDRESS)

    private val getRedrawer: Method by lazy { SkiaLayer::class.java.getMethod("getRedrawer\$skiko") }
    private val deviceField: Field by lazy {
        Class.forName(D3D_REDRAWER).getDeclaredField("device").apply { isAccessible = true }
    }

    fun checkLost(window: Window?): Boolean {
        if (disabled || window == null) return false
        val hr = try {
            removedReason(window) ?: return false
        } catch (t: Throwable) {
            disabled = true
            BiliLog.w("Skiko D3D12 设备检查失败,关闭显卡复位自动重建", t)
            return false
        }
        if (!attached) {
            attached = true
            BiliLog.d("已挂载 Skiko D3D12 设备失效检测")
        }
        if (hr == 0) return false
        report(hr)
        return true
    }

    private fun report(hr: Int) {
        val now = System.currentTimeMillis()
        if (now - lastReportMillis < REPORT_DEBOUNCE_MS) return
        lastReportMillis = now
        BiliLog.w("D3D12 设备已失效(HRESULT 0x%08X),重建窗口".format(hr))
        generation++
    }

    private fun removedReason(window: Window): Int? {
        val layer = findSkiaLayer(window) ?: return null
        val redrawer = getRedrawer.invoke(layer) ?: return null
        if (redrawer.javaClass.name != D3D_REDRAWER) return null
        val devicePtr = deviceField.getLong(redrawer)
        if (devicePtr == 0L) return null
        val d3d12 = MemorySegment.ofAddress(devicePtr)
            .reinterpret(ADDRESS.byteSize() * (DEVICE_SLOT + 1))
            .getAtIndex(ADDRESS, DEVICE_SLOT.toLong())
        if (d3d12.address() == 0L) return null
        val vtable = d3d12.reinterpret(ADDRESS.byteSize())
            .get(ADDRESS, 0)
            .reinterpret(ADDRESS.byteSize() * (GET_DEVICE_REMOVED_REASON + 1))
        val fn = vtable.getAtIndex(ADDRESS, GET_DEVICE_REMOVED_REASON.toLong())
        return linker.downcallHandle(fn, getRemovedReasonDesc).invokeExact(d3d12) as Int
    }

    private fun findSkiaLayer(container: Container): SkiaLayer? {
        for (child in container.components) {
            if (child is SkiaLayer) return child
            if (child is Container) findSkiaLayer(child)?.let { return it }
        }
        return null
    }

    private const val D3D_REDRAWER = "org.jetbrains.skiko.redrawer.Direct3DRedrawer"
    private const val DEVICE_SLOT = 6
    private const val GET_DEVICE_REMOVED_REASON = 37
    private const val REPORT_DEBOUNCE_MS = 3_000L
}
