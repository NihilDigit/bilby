package dev.bilby.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import dev.bilby.BiliLog
import dev.bilby.player.AudioPlaybackUiState
import dev.bilby.player.DesktopPlaybackHost
import kotlinx.coroutines.delay
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_CHAR
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities

/**
 * 将桌面端 [DesktopPlaybackHost] 接入 Windows 10/11 原生 `SystemMediaTransportControls`(SMTC):
 * 蓝牙耳机按键、键盘多媒体键及任务栏快捷设置媒体卡片直接控制播放/暂停与上一曲/下一曲,并实时同步当前标题与 UP 主名。
 *
 * 全程经 JDK FFM 调 `combase.dll` 与 COM vtable,零第三方 JNI 依赖。所有 WinRT 调用收敛在单条 MTA 守护线程上。
 */
@Composable
internal fun WindowsSmtcEffect(
    window: ComposeWindow,
    playback: DesktopPlaybackHost,
) {
    if (!isWindowsOs) return
    val bridge = rememberSmtcBridge(playback)
    LaunchedEffect(window, bridge) {
        while (window.windowHandle == 0L) delay(16)
        bridge.attach(window.windowHandle)
    }
    LaunchedEffect(playback, bridge) {
        playback.state.collect { state ->
            bridge.sync(state)
        }
    }
    DisposableEffect(bridge) {
        onDispose { bridge.close() }
    }
}

@Composable
private fun rememberSmtcBridge(playback: DesktopPlaybackHost): WindowsSmtcBridge =
    androidx.compose.runtime.remember(playback) { WindowsSmtcBridge(playback) }

private class WindowsSmtcBridge(private val playback: DesktopPlaybackHost) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bilby-smtc").apply { isDaemon = true }
    }

    private var smtc: MemorySegment = MemorySegment.NULL
    private var updater: MemorySegment = MemorySegment.NULL
    private var eventToken: Long = 0L
    private var hasEventToken = false
    private var callbackArena: Arena? = null
    private var initialized = false
    private var closed = false

    private var lastEnabled: Boolean? = null
    private var lastStatus: Int? = null
    private var lastCanPrev: Boolean? = null
    private var lastCanNext: Boolean? = null
    private var lastTitle: String? = null
    private var lastArtist: String? = null
    private var pendingState: AudioPlaybackUiState? = null

    fun attach(hwnd: Long) {
        if (hwnd == 0L) return
        executor.execute {
            if (closed || initialized) return@execute
            runCatching { initOnWorker(hwnd) }
                .onFailure { BiliLog.w("SMTC 初始化失败", it) }
            pendingState?.let { latest ->
                runCatching { applyStateOnWorker(latest) }
                    .onFailure { BiliLog.w("SMTC 初始状态同步失败", it) }
            }
        }
    }

    fun sync(state: AudioPlaybackUiState) {
        executor.execute {
            if (closed) return@execute
            pendingState = state
            if (!initialized || smtc == MemorySegment.NULL) return@execute
            runCatching { applyStateOnWorker(state) }
                .onFailure { BiliLog.w("SMTC 状态同步失败", it) }
        }
    }

    fun close() {
        executor.execute {
            if (closed) return@execute
            closed = true
            runCatching { releaseOnWorker() }
                .onFailure { BiliLog.w("SMTC 释放失败", it) }
        }
        executor.shutdown()
    }

    private fun initOnWorker(hwnd: Long) {
        val native = SmtcNative ?: return
        val hrInit = native.roInitialize.invokeExact(RO_INIT_MULTITHREADED) as Int
        if (hrInit < 0 && hrInit != RPC_E_CHANGED_MODE) return

        Arena.ofConfined().use { local ->
            val classHstr = native.createHString(local, RUNTIME_CLASS_SMTC) ?: return
            try {
                val iidInterop = local.guid(IID_SMTC_INTEROP)
                val outFactory = local.allocate(ADDRESS)
                val hrFactory = native.roGetActivationFactory.invokeExact(classHstr, iidInterop, outFactory) as Int
                if (hrFactory < 0) return
                val factory = outFactory.get(ADDRESS, 0L)
                if (factory == MemorySegment.NULL) return
                try {
                    val iidSmtc = local.guid(IID_SMTC)
                    val outSmtc = local.allocate(ADDRESS)
                    val getForWindow = vtableCall(
                        factory,
                        SLOT_INTEROP_GET_FOR_WINDOW,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS),
                    )
                    val hrGet = getForWindow.invokeExact(
                        factory,
                        MemorySegment.ofAddress(hwnd),
                        iidSmtc,
                        outSmtc,
                    ) as Int
                    if (hrGet < 0) return
                    smtc = outSmtc.get(ADDRESS, 0L)
                } finally {
                    releaseCom(factory)
                }
            } finally {
                native.windowsDeleteString.invokeExact(classHstr) as Int
            }

            if (smtc == MemorySegment.NULL) return

            val outUpdater = local.allocate(ADDRESS)
            val getUpdater = vtableCall(
                smtc,
                SLOT_SMTC_GET_DISPLAY_UPDATER,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
            )
            if ((getUpdater.invokeExact(smtc, outUpdater) as Int) >= 0) {
                updater = outUpdater.get(ADDRESS, 0L)
            }

            val cbArena = Arena.ofShared()
            val handlerObj = ButtonPressedComCallback(playback, cbArena).segment
            val outToken = local.allocate(JAVA_LONG)
            val addPressed = vtableCall(
                smtc,
                SLOT_SMTC_ADD_BUTTON_PRESSED,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            )
            val hrAdd = addPressed.invokeExact(smtc, handlerObj, outToken) as Int
            if (hrAdd >= 0) {
                callbackArena = cbArena
                eventToken = outToken.get(JAVA_LONG, 0L)
                hasEventToken = true
            } else {
                cbArena.close()
            }
            initialized = true
        }
    }

    private fun applyStateOnWorker(state: AudioPlaybackUiState) {
        val native = SmtcNative ?: return
        val activeNow = state.nowPlaying
        val enabled = activeNow != null
        if (lastEnabled != enabled) {
            putBool(smtc, SLOT_SMTC_PUT_IS_ENABLED, enabled)
            putBool(smtc, SLOT_SMTC_PUT_IS_PLAY_ENABLED, enabled)
            putBool(smtc, SLOT_SMTC_PUT_IS_PAUSE_ENABLED, enabled)
            putBool(smtc, SLOT_SMTC_PUT_IS_STOP_ENABLED, enabled)
            lastEnabled = enabled
        }
        if (!enabled) {
            if (lastStatus != MEDIA_STATUS_CLOSED) {
                putInt(smtc, SLOT_SMTC_PUT_PLAYBACK_STATUS, MEDIA_STATUS_CLOSED)
                lastStatus = MEDIA_STATUS_CLOSED
            }
            if (updater != MemorySegment.NULL && (lastTitle != null || lastArtist != null)) {
                val clearAll = vtableCall(updater, SLOT_UPDATER_CLEAR_ALL, FunctionDescriptor.of(JAVA_INT, ADDRESS))
                clearAll.invokeExact(updater) as Int
                val update = vtableCall(updater, SLOT_UPDATER_UPDATE, FunctionDescriptor.of(JAVA_INT, ADDRESS))
                update.invokeExact(updater) as Int
                lastTitle = null
                lastArtist = null
            }
            return
        }

        val canPrev = state.queue?.canPrevious == true
        val canNext = state.queue?.canNext == true
        if (lastCanPrev != canPrev) {
            putBool(smtc, SLOT_SMTC_PUT_IS_PREVIOUS_ENABLED, canPrev)
            lastCanPrev = canPrev
        }
        if (lastCanNext != canNext) {
            putBool(smtc, SLOT_SMTC_PUT_IS_NEXT_ENABLED, canNext)
            lastCanNext = canNext
        }

        val status = when {
            state.isPlaying -> MEDIA_STATUS_PLAYING
            state.loading -> MEDIA_STATUS_CHANGING
            else -> MEDIA_STATUS_PAUSED
        }
        if (lastStatus != status) {
            putInt(smtc, SLOT_SMTC_PUT_PLAYBACK_STATUS, status)
            lastStatus = status
        }

        val title = activeNow.title
        val artist = activeNow.subtitle
        if (updater != MemorySegment.NULL && (title != lastTitle || artist != lastArtist)) {
            Arena.ofConfined().use { local ->
                putInt(updater, SLOT_UPDATER_PUT_TYPE, MEDIA_TYPE_MUSIC)
                val outMusic = local.allocate(ADDRESS)
                val getMusic = vtableCall(
                    updater,
                    SLOT_UPDATER_GET_MUSIC_PROPS,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                )
                if ((getMusic.invokeExact(updater, outMusic) as Int) >= 0) {
                    val music = outMusic.get(ADDRESS, 0L)
                    if (music != MemorySegment.NULL) {
                        try {
                            putHString(native, local, music, SLOT_MUSIC_PUT_TITLE, title)
                            putHString(native, local, music, SLOT_MUSIC_PUT_ARTIST, artist)
                        } finally {
                            releaseCom(music)
                        }
                    }
                }
                val update = vtableCall(updater, SLOT_UPDATER_UPDATE, FunctionDescriptor.of(JAVA_INT, ADDRESS))
                update.invokeExact(updater) as Int
                lastTitle = title
                lastArtist = artist
            }
        }
    }

    private fun releaseOnWorker() {
        if (smtc != MemorySegment.NULL && hasEventToken) {
            val removePressed = vtableCall(
                smtc,
                SLOT_SMTC_REMOVE_BUTTON_PRESSED,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG),
            )
            removePressed.invokeExact(smtc, eventToken) as Int
            hasEventToken = false
        }
        if (updater != MemorySegment.NULL) {
            releaseCom(updater)
            updater = MemorySegment.NULL
        }
        if (smtc != MemorySegment.NULL) {
            putBool(smtc, SLOT_SMTC_PUT_IS_ENABLED, false)
            releaseCom(smtc)
            smtc = MemorySegment.NULL
        }
        callbackArena?.close()
        callbackArena = null
    }
}

/**
 * 手写 COM 事件回调对象(`ITypedEventHandler<SystemMediaTransportControls, SystemMediaTransportControlsButtonPressedEventArgs>`),
 * 同时实现 `IAgileObject` 以免 WinRT 在 MTA 中走跨套间代理封送。
 */
private class ButtonPressedComCallback(
    private val playback: DesktopPlaybackHost,
    arena: Arena,
) {
    private val refCount = AtomicInteger(1)
    private val iidUnknown = arena.guid(IID_IUNKNOWN)
    private val iidAgile = arena.guid(IID_IAGILE_OBJECT)
    private val iidHandler = arena.guid(IID_BUTTON_PRESSED_HANDLER)

    val segment: MemorySegment

    init {
        val linker = Linker.nativeLinker()
        val lookup = MethodHandles.lookup()
        val qiStub = linker.upcallStub(
            lookup.findVirtual(
                ButtonPressedComCallback::class.java,
                "queryInterface",
                MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java),
            ).bindTo(this),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            arena,
        )
        val addRefStub = linker.upcallStub(
            lookup.findVirtual(
                ButtonPressedComCallback::class.java,
                "addRef",
                MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java),
            ).bindTo(this),
            FunctionDescriptor.of(JAVA_INT, ADDRESS),
            arena,
        )
        val releaseStub = linker.upcallStub(
            lookup.findVirtual(
                ButtonPressedComCallback::class.java,
                "release",
                MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java),
            ).bindTo(this),
            FunctionDescriptor.of(JAVA_INT, ADDRESS),
            arena,
        )
        val invokeStub = linker.upcallStub(
            lookup.findVirtual(
                ButtonPressedComCallback::class.java,
                "invoke",
                MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java, MemorySegment::class.java, MemorySegment::class.java),
            ).bindTo(this),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            arena,
        )

        val vtbl = arena.allocate(ADDRESS.byteSize() * 4)
        vtbl.setAtIndex(ADDRESS, 0L, qiStub)
        vtbl.setAtIndex(ADDRESS, 1L, addRefStub)
        vtbl.setAtIndex(ADDRESS, 2L, releaseStub)
        vtbl.setAtIndex(ADDRESS, 3L, invokeStub)

        val obj = arena.allocate(ADDRESS)
        obj.set(ADDRESS, 0L, vtbl)
        segment = obj
    }

    @Suppress("unused")
    fun queryInterface(thisPtr: MemorySegment, riidPtr: MemorySegment, ppvObject: MemorySegment): Int {
        if (ppvObject == MemorySegment.NULL) return E_POINTER
        val out = ppvObject.reinterpret(ADDRESS.byteSize())
        if (riidPtr == MemorySegment.NULL) {
            out.set(ADDRESS, 0L, MemorySegment.NULL)
            return E_NOINTERFACE
        }
        val riid = riidPtr.reinterpret(16L)
        val matches = guidEquals(riid, iidHandler) || guidEquals(riid, iidUnknown) || guidEquals(riid, iidAgile)
        return if (matches) {
            refCount.incrementAndGet()
            out.set(ADDRESS, 0L, thisPtr)
            S_OK
        } else {
            out.set(ADDRESS, 0L, MemorySegment.NULL)
            E_NOINTERFACE
        }
    }

    @Suppress("unused")
    fun addRef(@Suppress("UNUSED_PARAMETER") thisPtr: MemorySegment): Int =
        refCount.incrementAndGet()

    @Suppress("unused")
    fun release(@Suppress("UNUSED_PARAMETER") thisPtr: MemorySegment): Int =
        refCount.decrementAndGet().coerceAtLeast(0)

    @Suppress("unused")
    fun invoke(
        @Suppress("UNUSED_PARAMETER") thisPtr: MemorySegment,
        @Suppress("UNUSED_PARAMETER") senderPtr: MemorySegment,
        argsPtr: MemorySegment,
    ): Int {
        if (argsPtr == MemorySegment.NULL) return S_OK
        runCatching {
            Arena.ofConfined().use { local ->
                val outButton = local.allocate(JAVA_INT)
                val getButton = vtableCall(
                    argsPtr,
                    SLOT_ARGS_GET_BUTTON,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                )
                val hr = getButton.invokeExact(argsPtr, outButton) as Int
                if (hr >= 0) {
                    val button = outButton.get(JAVA_INT, 0L)
                    SwingUtilities.invokeLater {
                        when (button) {
                            BUTTON_PLAY -> playback.play()
                            BUTTON_PAUSE, BUTTON_STOP -> playback.pause()
                            BUTTON_NEXT -> playback.seekToNext()
                            BUTTON_PREVIOUS -> playback.seekToPrevious()
                        }
                    }
                }
            }
        }.onFailure { BiliLog.w("处理 SMTC 按键回调失败", it) }
        return S_OK
    }
}

private class SmtcCombase(
    val roInitialize: MethodHandle,
    val roGetActivationFactory: MethodHandle,
    val windowsCreateString: MethodHandle,
    val windowsDeleteString: MethodHandle,
) {
    fun createHString(arena: Arena, text: String): MemorySegment? {
        val chars = arena.allocate(JAVA_CHAR, (text.length + 1).toLong())
        for (i in text.indices) {
            chars.setAtIndex(JAVA_CHAR, i.toLong(), text[i])
        }
        chars.setAtIndex(JAVA_CHAR, text.length.toLong(), '\u0000')
        val out = arena.allocate(ADDRESS)
        val hr = windowsCreateString.invokeExact(chars, text.length, out) as Int
        if (hr < 0) return null
        return out.get(ADDRESS, 0L)
    }
}

private val SmtcNative: SmtcCombase? by lazy {
    runCatching {
        val linker = Linker.nativeLinker()
        val combase = SymbolLookup.libraryLookup("combase", Arena.global())
        SmtcCombase(
            roInitialize = linker.downcallHandle(
                combase.find("RoInitialize").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT),
            ),
            roGetActivationFactory = linker.downcallHandle(
                combase.find("RoGetActivationFactory").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            ),
            windowsCreateString = linker.downcallHandle(
                combase.find("WindowsCreateString").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
            ),
            windowsDeleteString = linker.downcallHandle(
                combase.find("WindowsDeleteString").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS),
            ),
        )
    }.onFailure { BiliLog.w("加载 combase.dll 失败,SMTC 已停用", it) }.getOrNull()
}

private fun vtableCall(instance: MemorySegment, slot: Int, descriptor: FunctionDescriptor): MethodHandle {
    val obj = instance.reinterpret(ADDRESS.byteSize())
    val vtbl = obj.get(ADDRESS, 0L).reinterpret(ADDRESS.byteSize() * (slot + 1).toLong())
    val fnPtr = vtbl.getAtIndex(ADDRESS, slot.toLong())
    return Linker.nativeLinker().downcallHandle(fnPtr, descriptor)
}

private fun releaseCom(instance: MemorySegment) {
    if (instance == MemorySegment.NULL) return
    val release = vtableCall(instance, SLOT_IUNKNOWN_RELEASE, FunctionDescriptor.of(JAVA_INT, ADDRESS))
    release.invokeExact(instance) as Int
}

private fun putBool(instance: MemorySegment, slot: Int, value: Boolean) {
    val fn = vtableCall(instance, slot, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_BYTE))
    fn.invokeExact(instance, if (value) 1.toByte() else 0.toByte()) as Int
}

private fun putInt(instance: MemorySegment, slot: Int, value: Int) {
    val fn = vtableCall(instance, slot, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
    fn.invokeExact(instance, value) as Int
}

private fun putHString(native: SmtcCombase, arena: Arena, instance: MemorySegment, slot: Int, value: String) {
    val hstr = native.createHString(arena, value) ?: MemorySegment.NULL
    try {
        val fn = vtableCall(instance, slot, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
        fn.invokeExact(instance, hstr) as Int
    } finally {
        if (hstr != MemorySegment.NULL) {
            native.windowsDeleteString.invokeExact(hstr) as Int
        }
    }
}

private fun Arena.guid(spec: String): MemorySegment {
    val uuid = UUID.fromString(spec)
    val msb = uuid.mostSignificantBits
    val lsb = uuid.leastSignificantBits
    val seg = allocate(16L)
    seg.set(JAVA_INT_UNALIGNED, 0L, (msb ushr 32).toInt())
    seg.set(JAVA_SHORT_UNALIGNED, 4L, ((msb ushr 16) and 0xFFFF).toShort())
    seg.set(JAVA_SHORT_UNALIGNED, 6L, (msb and 0xFFFF).toShort())
    for (i in 0 until 8) {
        seg.set(JAVA_BYTE, 8L + i, ((lsb ushr (56 - i * 8)) and 0xFF).toByte())
    }
    return seg
}

private fun guidEquals(a: MemorySegment, b: MemorySegment): Boolean =
    a.get(JAVA_LONG, 0L) == b.get(JAVA_LONG, 0L) && a.get(JAVA_LONG, 8L) == b.get(JAVA_LONG, 8L)

private val isWindowsOs: Boolean =
    System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)

private const val RUNTIME_CLASS_SMTC = "Windows.Media.SystemMediaTransportControls"
private const val IID_IUNKNOWN = "00000000-0000-0000-c000-000000000046"
private const val IID_IAGILE_OBJECT = "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"
private const val IID_SMTC_INTEROP = "ddb0472d-c911-4a1f-86d9-dc3d71a95f5a"
private const val IID_SMTC = "99fa3ff4-1742-42a6-902e-087d41f965ec"
private const val IID_BUTTON_PRESSED_HANDLER = "0557e996-7b23-5bae-aa81-ea0d671143a4"

private const val S_OK = 0
private const val E_NOINTERFACE = -0x7fffbffe // 0x80004002
private const val E_POINTER = -0x7fffbffd // 0x80004003
private const val RPC_E_CHANGED_MODE = -0x7ffefefA // 0x80010106
private const val RO_INIT_MULTITHREADED = 1

private const val SLOT_IUNKNOWN_RELEASE = 2
private const val SLOT_INTEROP_GET_FOR_WINDOW = 6

private const val SLOT_SMTC_PUT_PLAYBACK_STATUS = 7
private const val SLOT_SMTC_GET_DISPLAY_UPDATER = 8
private const val SLOT_SMTC_PUT_IS_ENABLED = 11
private const val SLOT_SMTC_PUT_IS_PLAY_ENABLED = 13
private const val SLOT_SMTC_PUT_IS_STOP_ENABLED = 15
private const val SLOT_SMTC_PUT_IS_PAUSE_ENABLED = 17
private const val SLOT_SMTC_PUT_IS_PREVIOUS_ENABLED = 23
private const val SLOT_SMTC_PUT_IS_NEXT_ENABLED = 25
private const val SLOT_SMTC_ADD_BUTTON_PRESSED = 30
private const val SLOT_SMTC_REMOVE_BUTTON_PRESSED = 31

private const val SLOT_UPDATER_PUT_TYPE = 7
private const val SLOT_UPDATER_GET_MUSIC_PROPS = 12
private const val SLOT_UPDATER_CLEAR_ALL = 16
private const val SLOT_UPDATER_UPDATE = 17

private const val SLOT_MUSIC_PUT_TITLE = 7
private const val SLOT_MUSIC_PUT_ARTIST = 11

private const val SLOT_ARGS_GET_BUTTON = 6

private const val MEDIA_STATUS_CLOSED = 0
private const val MEDIA_STATUS_CHANGING = 1
private const val MEDIA_STATUS_PLAYING = 3
private const val MEDIA_STATUS_PAUSED = 4
private const val MEDIA_TYPE_MUSIC = 1

private const val BUTTON_PLAY = 0
private const val BUTTON_PAUSE = 1
private const val BUTTON_STOP = 2
private const val BUTTON_NEXT = 6
private const val BUTTON_PREVIOUS = 7
