package dev.bilby.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.LocalAwtWindow
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import dev.bilby.BiliLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ContentChangeMode
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.jetbrains.skiko.SkiaLayer
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.mpv.nAckRetiredBuffersD3D11
import org.openani.mediamp.mpv.nCanShareFramesD3D11
import org.openani.mediamp.mpv.nCopyLatestFrameD3D11
import org.openani.mediamp.mpv.nGetBufferTextureD3D11
import org.openani.mediamp.mpv.nGetFrameStateD3D11
import org.openani.mediamp.mpv.nSetConsumerDeviceHintD3D11
import org.openani.mediamp.mpv.nSetReadbackSurfaceConfigD3D11
import org.openani.mediamp.mpv.nSetSurfaceConfigD3D11
import java.awt.Container
import java.awt.Window
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import kotlin.math.min

/**
 * 桌面端 mpv 视频表面,直接对接 Skiko 0.156.1 的 `FrameDriver` / `Direct3DRenderer` 与 mediamp 0.5.3
 * 的 `MPVHandleDesktop` D3D11/readback JNI,不再依赖 mediamp 内部对 Skiko 0.150 `Direct3DRedrawer` 的反射。
 *
 * - 当 Skiko 处于 `Direct3DRenderer` 且具备 `DirectContext` + `DirectXDevice` 时,从 Skiko 原生
 *   `DirectXDevice` 读取 `ID3D12Device*`(`+0x10`)与 `ID3D12CommandQueue*`(`+0x20`),构造 72 字节
 *   (`0x48`)的离堆 shim 结构(`+0x10/+0x30 = device`, `+0x18/+0x40 = queue`)满足 `mediampv.dll`
 *   的 `open_skia_d3d12_device` 布局校验,走 D3D11->D3D12 共享纹理三缓冲零拷贝渲染环。
 * - 当 Skiko 回落到 OpenGL / ANGLE / Software 或跨卡不可共享纹理时,自动回落到 D3D11 CPU readback
 *   (`nSetReadbackSurfaceConfigD3D11` / `nCopyLatestFrameD3D11`)。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun BilbyMpvVideoSurface(
    player: DesktopPlayer,
    modifier: Modifier = Modifier,
) {
    val window = LocalAwtWindow.current
    val layer = remember(window) { window?.let(::findSkiaLayer) }
    val controller = remember(player) { BilbySurfaceController(player) }

    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    var rendererGeneration by remember(layer) { mutableLongStateOf(0L) }
    val frameTick = remember { mutableLongStateOf(0L) }

    LaunchedEffect(layer) {
        if (layer == null) return@LaunchedEffect
        // 等待首个 FrameDriver/renderer 就绪后再订阅后续切换,避免首帧空窗时反复轮询
        var probeCount = 0
        while (SkikoAccess.currentRenderer(layer) == null && probeCount < MAX_INITIAL_PROBES) {
            delay(INITIAL_PROBE_DELAY_MS)
            probeCount++
        }
        rendererGeneration++
        SkikoAccess.rendererChanges(layer).collect {
            rendererGeneration++
        }
    }

    DisposableEffect(controller) {
        val listenerAttached = controller.attachRenderUpdateListener {
            frameTick.longValue += 1L
        }
        if (!listenerAttached) {
            BiliLog.w("挂载 mpv 帧通知监听失败")
        }
        onDispose {
            controller.release()
        }
    }

    LaunchedEffect(controller, layer, surfaceSize, rendererGeneration) {
        val width = surfaceSize.width
        val height = surfaceSize.height
        if (width <= 0 || height <= 0) return@LaunchedEffect

        // Direct3DRenderer 在首次 drawFrame 前 device/context 仍可能为空,短轮询等待其就绪
        repeat(MAX_SURFACE_CONFIG_ATTEMPTS) {
            val env = SkikoAccess.inspect(layer)
            if (controller.configureIfReady(width, height, env)) return@LaunchedEffect
            delay(SURFACE_CONFIG_RETRY_DELAY_MS)
        }
    }

    Canvas(
        modifier = modifier.onSizeChanged { newSize ->
            surfaceSize = newSize
        },
    ) {
        // 订阅 mpv 新帧通知以触发重绘
        frameTick.longValue
        if (controller.isTeardownStarted()) return@Canvas

        val width = size.width.toInt()
        val height = size.height.toInt()
        if (width <= 0 || height <= 0) return@Canvas

        val env = SkikoAccess.inspect(layer)
        val image = controller.acquireFrame(width, height, env) ?: return@Canvas

        val srcWidth = image.width.toFloat()
        val srcHeight = image.height.toFloat()
        if (srcWidth <= 0f || srcHeight <= 0f) return@Canvas

        val scale = min(size.width / srcWidth, size.height / srcHeight)
        val dstWidth = srcWidth * scale
        val dstHeight = srcHeight * scale
        val dstLeft = (size.width - dstWidth) * 0.5f
        val dstTop = (size.height - dstHeight) * 0.5f

        drawContext.canvas.skiaCanvas.drawImageRect(
            image,
            Rect.makeWH(srcWidth, srcHeight),
            Rect.makeXYWH(dstLeft, dstTop, dstWidth, dstHeight),
            SamplingMode.LINEAR,
            null,
            true,
        )
    }
}

private data class SkikoRenderEnvironment(
    val hasRenderer: Boolean,
    val isDirect3D: Boolean,
    val nativeDevicePtr: Long,
    val directContext: DirectContext?,
)

private object SkikoAccess {
    private const val FRAME_DRIVER_CLASS = "org.jetbrains.skiko.renderer.FrameDriver"
    private const val D3D_RENDERER_CLASS = "org.jetbrains.skiko.renderer.Direct3DRenderer"

    private val getFrameDriver: Method? by lazy {
        runCatching {
            SkiaLayer::class.java.getMethod("getFrameDriver\$org_jetbrains_skiko_skiko")
        }.onFailure { BiliLog.w("反射 SkiaLayer.getFrameDriver 失败", it) }.getOrNull()
    }

    private val getRenderer: Method? by lazy {
        runCatching {
            Class.forName(FRAME_DRIVER_CLASS).getDeclaredMethod("getRenderer").apply { isAccessible = true }
        }.onFailure { BiliLog.w("反射 FrameDriver.getRenderer 失败", it) }.getOrNull()
    }

    private val d3dDeviceField: Field? by lazy {
        runCatching {
            Class.forName(D3D_RENDERER_CLASS).getDeclaredField("device").apply { isAccessible = true }
        }.onFailure { BiliLog.w("反射 Direct3DRenderer.device 失败", it) }.getOrNull()
    }

    private val d3dContextField: Field? by lazy {
        runCatching {
            Class.forName(D3D_RENDERER_CLASS).getDeclaredField("context").apply { isAccessible = true }
        }.onFailure { BiliLog.w("反射 Direct3DRenderer.context 失败", it) }.getOrNull()
    }

    // 每个 SkiaLayer 最多注册一次 PropertyKind.Renderer 监听,只持 WeakReference 避免泄漏表面与 Arena
    private val rendererFlows = WeakHashMap<SkiaLayer, MutableStateFlow<Long>>()

    fun rendererChanges(layer: SkiaLayer): MutableStateFlow<Long> = synchronized(rendererFlows) {
        rendererFlows.getOrPut(layer) {
            val flow = MutableStateFlow(0L)
            val weakFlow = WeakReference(flow)
            layer.onStateChanged(SkiaLayer.PropertyKind.Renderer) {
                weakFlow.get()?.let { target -> target.value += 1L }
            }
            flow
        }
    }

    fun currentRenderer(layer: SkiaLayer?): Any? {
        if (layer == null) return null
        val driver = runCatching { getFrameDriver?.invoke(layer) }.getOrNull() ?: return null
        return runCatching { getRenderer?.invoke(driver) }.getOrNull()
    }

    fun inspect(layer: SkiaLayer?): SkikoRenderEnvironment {
        val renderer = currentRenderer(layer)
            ?: return SkikoRenderEnvironment(
                hasRenderer = false,
                isDirect3D = false,
                nativeDevicePtr = 0L,
                directContext = null,
            )
        if (renderer.javaClass.name != D3D_RENDERER_CLASS) {
            return SkikoRenderEnvironment(
                hasRenderer = true,
                isDirect3D = false,
                nativeDevicePtr = 0L,
                directContext = null,
            )
        }
        val devicePtr = runCatching { d3dDeviceField?.getLong(renderer) ?: 0L }.getOrDefault(0L)
        val directContext = runCatching { d3dContextField?.get(renderer) as? DirectContext }.getOrNull()
        return SkikoRenderEnvironment(
            hasRenderer = true,
            isDirect3D = true,
            nativeDevicePtr = devicePtr,
            directContext = directContext,
        )
    }
}

/**
 * 维护给 `mediampv.dll` 使用的 72 字节(`0x48`)离堆 D3D12 设备兼容结构。
 *
 * Skiko 0.156.1 的 `DirectXDevice` 在 `+0x10` 放 `ID3D12Device*`、`+0x20` 放 `ID3D12CommandQueue*`,
 * `+0x30` 已改为 `backbuffer[1]`;而 `mediampv.dll` 仍检查 `*(ptr+0x10) == *(ptr+0x30)` 与
 * `*(ptr+0x18) == *(ptr+0x40)`。每次原生设备指针或 COM 指针变化时分配新地址(驱动 `MpvSurfaceRing`
 * 重新配置),旧 `Arena` 延迟到新配置或 `release` 之后再释放。
 */
private class DirectXDeviceShim {
    private var activeArena: Arena? = null
    private val retiredArenas = mutableListOf<Arena>()

    private var lastNativeDevicePtr = 0L
    private var lastD3d12Device = 0L
    private var lastD3d12Queue = 0L
    var shimPtr: Long = 0L
        private set

    fun resolveShimPtr(nativeDevicePtr: Long): Long {
        if (nativeDevicePtr == 0L) return 0L
        val nativeSegment = MemorySegment.ofAddress(nativeDevicePtr).reinterpret(SKIKO_DEVICE_READ_BYTES)
        val d3d12Device = nativeSegment.get(JAVA_LONG, SKIKO_OFFSET_D3D12_DEVICE)
        val d3d12Queue = nativeSegment.get(JAVA_LONG, SKIKO_OFFSET_D3D12_QUEUE)
        if (d3d12Device == 0L || d3d12Queue == 0L) return 0L

        if (
            shimPtr != 0L &&
            nativeDevicePtr == lastNativeDevicePtr &&
            d3d12Device == lastD3d12Device &&
            d3d12Queue == lastD3d12Queue
        ) {
            return shimPtr
        }

        val nextArena = Arena.ofShared()
        val segment = nextArena.allocate(SHIM_STRUCT_BYTES)
        segment.set(JAVA_LONG, SHIM_OFFSET_FDEVICE, d3d12Device)
        segment.set(JAVA_LONG, SHIM_OFFSET_FQUEUE, d3d12Queue)
        segment.set(JAVA_LONG, SHIM_OFFSET_DEVICE, d3d12Device)
        segment.set(JAVA_LONG, SHIM_OFFSET_QUEUE, d3d12Queue)

        activeArena?.let { retiredArenas.add(it) }
        activeArena = nextArena
        lastNativeDevicePtr = nativeDevicePtr
        lastD3d12Device = d3d12Device
        lastD3d12Queue = d3d12Queue
        shimPtr = segment.address()
        return shimPtr
    }

    fun closeRetired() {
        if (retiredArenas.isEmpty()) return
        val iterator = retiredArenas.iterator()
        while (iterator.hasNext()) {
            runCatching { iterator.next().close() }
                .onFailure { BiliLog.w("释放旧 D3D12 shim Arena 失败", it) }
            iterator.remove()
        }
    }

    fun closeAll() {
        closeRetired()
        activeArena?.let { arena ->
            runCatching { arena.close() }
                .onFailure { BiliLog.w("释放 D3D12 shim Arena 失败", it) }
        }
        activeArena = null
        shimPtr = 0L
        lastNativeDevicePtr = 0L
        lastD3d12Device = 0L
        lastD3d12Queue = 0L
    }

    private companion object {
        const val SKIKO_OFFSET_D3D12_DEVICE = 0x10L
        const val SKIKO_OFFSET_D3D12_QUEUE = 0x20L
        const val SKIKO_DEVICE_READ_BYTES = 0x28L

        const val SHIM_STRUCT_BYTES = 0x48L // 72 字节
        const val SHIM_OFFSET_FDEVICE = 0x10L
        const val SHIM_OFFSET_FQUEUE = 0x18L
        const val SHIM_OFFSET_DEVICE = 0x30L
        const val SHIM_OFFSET_QUEUE = 0x40L
    }
}

@OptIn(InternalMediampApi::class)
private class BilbySurfaceController(private val player: DesktopPlayer) {
    private val handlePtr: Long by lazy {
        MediampAccess.getHandlePtr(player.mpv)
    }

    private val shim = DirectXDeviceShim()
    private val ringConsumer by lazy { D3D11RingConsumer(handlePtr) }
    private val readbackConsumer by lazy { D3D11ReadbackConsumer(handlePtr) }

    private var activeMode: SurfaceMode? = null
    private var hintedShimPtr = 0L
    private var released = false

    private enum class SurfaceMode { RING, READBACK }

    fun attachRenderUpdateListener(onUpdate: () -> Unit): Boolean =
        MediampAccess.setRenderUpdateListener(player.mediamp, onUpdate)

    fun isTeardownStarted(): Boolean =
        released || MediampAccess.isSurfaceTeardownStarted(player.mediamp)

    fun configureIfReady(width: Int, height: Int, env: SkikoRenderEnvironment): Boolean {
        if (isTeardownStarted() || width <= 0 || height <= 0) return false
        if (!env.hasRenderer) return false

        if (env.isDirect3D) {
            if (env.nativeDevicePtr == 0L || env.directContext == null) return false
            val shimPtr = shim.resolveShimPtr(env.nativeDevicePtr)
            if (shimPtr == 0L) return false
            if (hintedShimPtr != shimPtr) {
                nSetConsumerDeviceHintD3D11(handlePtr, shimPtr)
                hintedShimPtr = shimPtr
            }
            val useRing = nCanShareFramesD3D11(handlePtr, shimPtr)
            val configured = if (useRing) {
                switchTo(SurfaceMode.RING)
                ringConsumer.requestSurface(width, height, shimPtr)
            } else {
                switchTo(SurfaceMode.READBACK)
                readbackConsumer.requestSurface(width, height)
            }
            if (configured) shim.closeRetired()
            return configured
        }

        // OpenGL / ANGLE / Software 等非 Direct3D 渲染器统一走 readback
        switchTo(SurfaceMode.READBACK)
        val configured = readbackConsumer.requestSurface(width, height)
        if (configured) shim.closeRetired()
        return configured
    }

    fun acquireFrame(width: Int, height: Int, env: SkikoRenderEnvironment): Image? {
        if (isTeardownStarted()) return null
        configureIfReady(width, height, env)
        return when (activeMode) {
            SurfaceMode.RING -> ringConsumer.currentFrameImage(env.directContext)
            SurfaceMode.READBACK -> readbackConsumer.currentFrameImage()
            null -> null
        }
    }

    private fun switchTo(mode: SurfaceMode) {
        if (activeMode == mode) return
        when (activeMode) {
            SurfaceMode.RING -> ringConsumer.release()
            SurfaceMode.READBACK -> readbackConsumer.release()
            null -> Unit
        }
        activeMode = mode
    }

    fun release() {
        released = true
        MediampAccess.setRenderUpdateListener(player.mediamp, null)
        when (activeMode) {
            SurfaceMode.RING -> ringConsumer.release()
            SurfaceMode.READBACK -> readbackConsumer.release()
            null -> Unit
        }
        activeMode = null
        hintedShimPtr = 0L
        shim.closeAll()
    }
}

private object MediampAccess {
    private val getPtrMethod: Method by lazy {
        MPVHandle::class.java.getMethod("getPtr\$mediamp_mpv")
    }

    private val renderUpdateListenerClass: Class<*>? by lazy {
        runCatching {
            Class.forName("org.openani.mediamp.mpv.RenderUpdateListener")
        }.onFailure { BiliLog.w("加载 RenderUpdateListener 失败", it) }.getOrNull()
    }

    private val setRenderUpdateListenerMethod: Method? by lazy {
        val listenerClass = renderUpdateListenerClass ?: return@lazy null
        runCatching {
            MpvMediampPlayer::class.java.getMethod("setRenderUpdateListener\$mediamp_mpv", listenerClass)
        }.onFailure { BiliLog.w("反射 setRenderUpdateListener 失败", it) }.getOrNull()
    }

    private val isSurfaceTeardownStartedMethod: Method? by lazy {
        runCatching {
            MpvMediampPlayer::class.java.getMethod("isSurfaceTeardownStarted\$mediamp_mpv")
        }.onFailure { BiliLog.w("反射 isSurfaceTeardownStarted 失败", it) }.getOrNull()
    }

    fun getHandlePtr(mpv: MPVHandle): Long = getPtrMethod.invoke(mpv) as Long

    fun setRenderUpdateListener(mediamp: MpvMediampPlayer, onUpdate: (() -> Unit)?): Boolean {
        val listenerClass = renderUpdateListenerClass ?: return false
        val method = setRenderUpdateListenerMethod ?: return false
        val proxy = onUpdate?.let { callback ->
            Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, called, _ ->
                if (called.name == "onRenderUpdate") callback()
                null
            }
        }
        return runCatching {
            method.invoke(mediamp, proxy) as Boolean
        }.onFailure { BiliLog.w("设置 mpv RenderUpdateListener 失败", it) }.getOrDefault(false)
    }

    fun isSurfaceTeardownStarted(mediamp: MpvMediampPlayer): Boolean {
        val method = isSurfaceTeardownStartedMethod ?: return false
        return runCatching { method.invoke(mediamp) as Boolean }.getOrDefault(false)
    }
}

@OptIn(InternalMediampApi::class)
private class D3D11RingConsumer(private val handlePtr: Long) {
    private val wrappedSurfaces = arrayOfNulls<Surface>(RING_BUFFER_COUNT)
    private val renderTargets = arrayOfNulls<BackendRenderTarget>(RING_BUFFER_COUNT)
    private var blitSurface: Surface? = null
    private var wrappedGeneration = -1
    private var surfaceContext: DirectContext? = null
    private var cachedFrame: Image? = null
    private var cachedState = 0L

    private var requestedWidth = 0
    private var requestedHeight = 0
    private var requestedDevicePtr = 0L
    private val loggedErrors = mutableSetOf<String>()

    fun requestSurface(width: Int, height: Int, devicePtr: Long): Boolean {
        if (width == requestedWidth && height == requestedHeight && devicePtr == requestedDevicePtr) {
            return true
        }
        if (!nSetSurfaceConfigD3D11(handlePtr, width, height, devicePtr)) {
            return false
        }
        requestedWidth = width
        requestedHeight = height
        requestedDevicePtr = devicePtr
        return true
    }

    fun currentFrameImage(directContext: DirectContext?): Image? {
        if (directContext == null) return null
        val state = nGetFrameStateD3D11(handlePtr)
        if (state == cachedState && surfaceContext === directContext) {
            cachedFrame?.let { return it }
        }
        val generation = ((state ushr 48) and 0xFFFF).toInt()
        val index = ((state ushr 44) and 0xF).toInt()
        val width = ((state ushr 30) and 0x3FFF).toInt()
        val height = ((state ushr 16) and 0x3FFF).toInt()
        if (index == 0xF || width <= 0 || height <= 0) return cachedFrame

        if (generation != wrappedGeneration || surfaceContext !== directContext || blitSurface == null) {
            if (!rewrapBuffers(generation, width, height, directContext)) return cachedFrame
        }
        val source = wrappedSurfaces.getOrNull(index) ?: return cachedFrame
        val blit = blitSurface ?: return cachedFrame
        blit.notifyContentWillChange(ContentChangeMode.DISCARD)
        source.draw(blit.canvas, 0, 0, null)
        cachedFrame?.close()
        cachedFrame = blit.makeImageSnapshot()
        cachedState = state
        return cachedFrame
    }

    private fun rewrapBuffers(
        generation: Int,
        width: Int,
        height: Int,
        directContext: DirectContext,
    ): Boolean {
        if (surfaceContext !== null && surfaceContext !== directContext) {
            cachedFrame?.close()
            cachedFrame = null
            cachedState = 0L
        }
        closeWraps()
        nAckRetiredBuffersD3D11(handlePtr)

        for (i in 0 until RING_BUFFER_COUNT) {
            val texture = nGetBufferTextureD3D11(handlePtr, i)
            if (texture == 0L) {
                logOnce("buffer $i has no D3D12 shared texture")
                closeWraps()
                return false
            }
            val target = BackendRenderTarget.makeDirect3D(
                width = width,
                height = height,
                texturePtr = texture,
                format = DXGI_FORMAT_R8G8B8A8_UNORM,
                sampleCnt = 1,
                levelCnt = 1,
            )
            val surface = runCatching {
                Surface.makeFromBackendRenderTarget(
                    context = directContext,
                    rt = target,
                    origin = SurfaceOrigin.TOP_LEFT,
                    colorFormat = SurfaceColorFormat.RGBA_8888,
                    colorSpace = ColorSpace.sRGB,
                )
            }.onFailure { logOnce("wrapping buffer $i as Skia D3D12 surface failed", it) }.getOrNull()
            if (surface == null) {
                logOnce("Surface.makeFromBackendRenderTarget returned null")
                target.close()
                closeWraps()
                return false
            }
            renderTargets[i] = target
            wrappedSurfaces[i] = surface
        }

        blitSurface = runCatching {
            Surface.makeRenderTarget(directContext, false, ImageInfo.makeN32Premul(width, height))
        }.onFailure { logOnce("creating D3D12 blit surface failed", it) }.getOrNull()
        if (blitSurface == null) {
            closeWraps()
            return false
        }

        val currentGeneration = ((nGetFrameStateD3D11(handlePtr) ushr 48) and 0xFFFF).toInt()
        if (currentGeneration != generation) {
            closeWraps()
            return false
        }

        wrappedGeneration = generation
        surfaceContext = directContext
        return true
    }

    private fun closeWraps() {
        for (i in 0 until RING_BUFFER_COUNT) {
            wrappedSurfaces[i]?.close()
            wrappedSurfaces[i] = null
            renderTargets[i]?.close()
            renderTargets[i] = null
        }
        blitSurface?.close()
        blitSurface = null
        wrappedGeneration = -1
    }

    private fun logOnce(message: String, throwable: Throwable? = null) {
        if (loggedErrors.add(message)) {
            if (throwable != null) BiliLog.w("BilbyMpvVideoSurface: $message", throwable)
            else BiliLog.w("BilbyMpvVideoSurface: $message")
        }
    }

    fun release() {
        cachedFrame?.close()
        cachedFrame = null
        cachedState = 0L
        closeWraps()
        surfaceContext = null
        nSetSurfaceConfigD3D11(handlePtr, 0, 0, 0L)
        requestedWidth = 0
        requestedHeight = 0
        requestedDevicePtr = 0L
    }

    private companion object {
        const val RING_BUFFER_COUNT = 3
        const val DXGI_FORMAT_R8G8B8A8_UNORM = 28
    }
}

@OptIn(InternalMediampApi::class)
private class D3D11ReadbackConsumer(private val handlePtr: Long) {
    private var bitmap: Bitmap? = null
    private var bitmapPixels: Pixmap? = null
    private var cachedFrame: Image? = null
    private var cachedState = 0L

    private var requestedWidth = 0
    private var requestedHeight = 0
    private var configured = false

    fun requestSurface(width: Int, height: Int): Boolean {
        if (configured && width == requestedWidth && height == requestedHeight) return true
        if (!nSetReadbackSurfaceConfigD3D11(handlePtr, width, height)) return false
        requestedWidth = width
        requestedHeight = height
        configured = true
        return true
    }

    fun currentFrameImage(): Image? {
        val state = nGetFrameStateD3D11(handlePtr)
        if (state == cachedState) {
            cachedFrame?.let { return it }
        }
        val index = ((state ushr 44) and 0xF).toInt()
        val width = ((state ushr 30) and 0x3FFF).toInt()
        val height = ((state ushr 16) and 0x3FFF).toInt()
        if (index == 0xF || width <= 0 || height <= 0) return cachedFrame

        val newBitmap = Bitmap()
        if (!newBitmap.allocPixels(ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE))) {
            newBitmap.close()
            return cachedFrame
        }
        val newPixels = newBitmap.peekPixels()
        if (newPixels == null) {
            newBitmap.close()
            return cachedFrame
        }
        val copiedState = nCopyLatestFrameD3D11(handlePtr, newPixels.addr, width, height)
        if (copiedState == 0L) {
            newPixels.close()
            newBitmap.close()
            return cachedFrame
        }
        newBitmap.setImmutable()
        val newFrame = Image.makeFromBitmap(newBitmap)

        cachedFrame?.close()
        bitmapPixels?.close()
        bitmap?.close()
        cachedFrame = newFrame
        bitmap = newBitmap
        bitmapPixels = newPixels
        cachedState = copiedState
        return newFrame
    }

    fun release() {
        nSetReadbackSurfaceConfigD3D11(handlePtr, 0, 0)
        cachedFrame?.close()
        cachedFrame = null
        cachedState = 0L
        bitmapPixels?.close()
        bitmapPixels = null
        bitmap?.close()
        bitmap = null
        requestedWidth = 0
        requestedHeight = 0
        configured = false
    }
}

private fun findSkiaLayer(container: Container): SkiaLayer? {
    for (child in container.components) {
        if (child is SkiaLayer) return child
        if (child is Container) findSkiaLayer(child)?.let { return it }
    }
    return null
}

private const val INITIAL_PROBE_DELAY_MS = 50L
private const val MAX_INITIAL_PROBES = 40
private const val SURFACE_CONFIG_RETRY_DELAY_MS = 50L
private const val MAX_SURFACE_CONFIG_ATTEMPTS = 20
