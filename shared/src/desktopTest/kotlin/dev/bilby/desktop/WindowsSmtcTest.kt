package dev.bilby.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED
import java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Windows SMTC FFM 契约回归测试：
 * 1. 核对 `WindowsSmtc.kt` 修正后的 4 个 COM vtable 槽位常量（25/27/32/33）与非对齐 GUID 读取、关闭清理逻辑；
 * 2. 验证 Windows 4 字节对齐 COM GUID 必须使用 `JAVA_LONG_UNALIGNED`（`JAVA_LONG` 会抛对齐异常）；
 * 3. 通过 JDK FFM upcall/downcall 构造 `ITypedEventHandler` 与模拟 `ISystemMediaTransportControlsButtonPressedEventArgs`
 *    （槽位 6 = `get_Button`），验证 4 字节对齐 `QueryInterface` 与 5 个多媒体键派发。
 */
class WindowsSmtcTest {

    @Test
    fun `WindowsSmtc 声明正确的 SDK vtable 槽位、非对齐 GUID 比较与安全关闭`() {
        val candidates = listOf(
            File("../desktop/src/main/kotlin/dev/bilby/desktop/WindowsSmtc.kt"),
            File("desktop/src/main/kotlin/dev/bilby/desktop/WindowsSmtc.kt"),
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
            ?: error("未找到 WindowsSmtc.kt，当前目录=${File(".").absolutePath}")
        val source = sourceFile.readText(Charsets.UTF_8)

        assertTrue(source.contains("SLOT_SMTC_PUT_IS_PREVIOUS_ENABLED = 25"))
        assertTrue(source.contains("SLOT_SMTC_PUT_IS_NEXT_ENABLED = 27"))
        assertTrue(source.contains("SLOT_SMTC_ADD_BUTTON_PRESSED = 32"))
        assertTrue(source.contains("SLOT_SMTC_REMOVE_BUTTON_PRESSED = 33"))
        assertTrue(source.contains("JAVA_LONG_UNALIGNED"))
        assertTrue(source.contains("CountDownLatch"))
        assertTrue(source.contains("SLOT_UPDATER_CLEAR_ALL"))
        assertTrue(source.contains("private var callback: ButtonPressedComCallback? = null"))
    }

    @Test
    fun `4 字节对齐 COM GUID 比较使用 JAVA_LONG_UNALIGNED 不抛对齐异常`() {
        Arena.ofConfined().use { arena ->
            val rawA = arena.allocate(24L, 8L)
            val unalignedA = rawA.asSlice(4L, 16L) // 4 字节对齐，非 8 字节对齐
            writeGuid(unalignedA, IID_BUTTON_PRESSED_HANDLER)

            val alignedB = arena.allocate(16L, 8L)
            writeGuid(alignedB, IID_BUTTON_PRESSED_HANDLER)

            val other = arena.allocate(16L, 8L)
            writeGuid(other, IID_IUNKNOWN)

            try {
                unalignedA.get(JAVA_LONG, 0L)
                fail("4 字节对齐切片按 JAVA_LONG 读取应抛出对齐 IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }

            assertTrue(unalignedGuidEquals(unalignedA, alignedB))
            assertFalse(unalignedGuidEquals(unalignedA, other))
        }
    }

    @Test
    fun `COM 回调对象处理非对齐 QueryInterface 并通过槽位 6 派发全部 5 个媒体按键`() {
        val dispatched = mutableListOf<String>()
        Arena.ofConfined().use { arena ->
            val callback = TestButtonPressedCallback(arena) { button ->
                dispatched += when (button) {
                    BUTTON_PLAY -> "play"
                    BUTTON_PAUSE -> "pause"
                    BUTTON_STOP -> "stop"
                    BUTTON_NEXT -> "next"
                    BUTTON_PREVIOUS -> "previous"
                    else -> "unknown($button)"
                }
            }
            val linker = Linker.nativeLinker()
            val vtbl = callback.segment.get(ADDRESS, 0L).reinterpret(ADDRESS.byteSize() * 4L)
            val qiDowncall = linker.downcallHandle(
                vtbl.getAtIndex(ADDRESS, 0L),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            )
            val releaseDowncall = linker.downcallHandle(
                vtbl.getAtIndex(ADDRESS, 2L),
                FunctionDescriptor.of(JAVA_INT, ADDRESS),
            )
            val invokeDowncall = linker.downcallHandle(
                vtbl.getAtIndex(ADDRESS, 3L),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
            )

            // 1. 4 字节对齐 REFIID 的 QueryInterface 命中 Handler / IUnknown / IAgileObject，拒绝未知 IID
            val unalignedBacking = arena.allocate(24L, 8L)
            val unalignedIid = unalignedBacking.asSlice(4L, 16L)
            val outObj = arena.allocate(ADDRESS)
            for (iid in listOf(IID_BUTTON_PRESSED_HANDLER, IID_IUNKNOWN, IID_IAGILE_OBJECT)) {
                writeGuid(unalignedIid, iid)
                val hr = qiDowncall.invokeExact(callback.segment, unalignedIid, outObj) as Int
                assertEquals(S_OK, hr)
                assertEquals(callback.segment.address(), outObj.get(ADDRESS, 0L).address())
                releaseDowncall.invokeExact(callback.segment) as Int
            }

            writeGuid(unalignedIid, "11111111-2222-3333-4444-555555555555")
            val hrUnknown = qiDowncall.invokeExact(callback.segment, unalignedIid, outObj) as Int
            assertEquals(E_NOINTERFACE, hrUnknown)
            assertEquals(0L, outObj.get(ADDRESS, 0L).address())

            // 2. 通过模拟 EventArgs（槽位 6 = get_Button）验证 5 个多媒体键回调派发
            val fakeArgs = FakeButtonPressedEventArgs(arena)
            for (button in listOf(BUTTON_PLAY, BUTTON_PAUSE, BUTTON_STOP, BUTTON_NEXT, BUTTON_PREVIOUS)) {
                fakeArgs.nextButton = button
                val hr = invokeDowncall.invokeExact(callback.segment, MemorySegment.NULL, fakeArgs.segment) as Int
                assertEquals(S_OK, hr)
            }
            assertEquals(listOf("play", "pause", "stop", "next", "previous"), dispatched)
        }
    }

    private class TestButtonPressedCallback(
        arena: Arena,
        private val onButton: (Int) -> Unit,
    ) {
        private val refCount = AtomicInteger(1)
        private val iidUnknown = arena.allocate(16L).also { writeGuid(it, IID_IUNKNOWN) }
        private val iidAgile = arena.allocate(16L).also { writeGuid(it, IID_IAGILE_OBJECT) }
        private val iidHandler = arena.allocate(16L).also { writeGuid(it, IID_BUTTON_PRESSED_HANDLER) }

        val segment: MemorySegment

        init {
            val linker = Linker.nativeLinker()
            val lookup = MethodHandles.lookup()
            val qiStub = linker.upcallStub(
                lookup.findVirtual(
                    TestButtonPressedCallback::class.java,
                    "queryInterface",
                    MethodType.methodType(
                        Int::class.javaPrimitiveType,
                        MemorySegment::class.java,
                        MemorySegment::class.java,
                        MemorySegment::class.java,
                    ),
                ).bindTo(this),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                arena,
            )
            val addRefStub = linker.upcallStub(
                lookup.findVirtual(
                    TestButtonPressedCallback::class.java,
                    "addRef",
                    MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java),
                ).bindTo(this),
                FunctionDescriptor.of(JAVA_INT, ADDRESS),
                arena,
            )
            val releaseStub = linker.upcallStub(
                lookup.findVirtual(
                    TestButtonPressedCallback::class.java,
                    "release",
                    MethodType.methodType(Int::class.javaPrimitiveType, MemorySegment::class.java),
                ).bindTo(this),
                FunctionDescriptor.of(JAVA_INT, ADDRESS),
                arena,
            )
            val invokeStub = linker.upcallStub(
                lookup.findVirtual(
                    TestButtonPressedCallback::class.java,
                    "invoke",
                    MethodType.methodType(
                        Int::class.javaPrimitiveType,
                        MemorySegment::class.java,
                        MemorySegment::class.java,
                        MemorySegment::class.java,
                    ),
                ).bindTo(this),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS),
                arena,
            )
            val vtbl = arena.allocate(ADDRESS.byteSize() * 4L)
            vtbl.setAtIndex(ADDRESS, 0L, qiStub)
            vtbl.setAtIndex(ADDRESS, 1L, addRefStub)
            vtbl.setAtIndex(ADDRESS, 2L, releaseStub)
            vtbl.setAtIndex(ADDRESS, 3L, invokeStub)
            segment = arena.allocate(ADDRESS).apply { set(ADDRESS, 0L, vtbl) }
        }

        @Suppress("unused")
        fun queryInterface(thisPtr: MemorySegment, riidPtr: MemorySegment, ppvObject: MemorySegment): Int {
            val out = ppvObject.reinterpret(ADDRESS.byteSize())
            val riid = riidPtr.reinterpret(16L)
            val matches = unalignedGuidEquals(riid, iidHandler) ||
                unalignedGuidEquals(riid, iidUnknown) ||
                unalignedGuidEquals(riid, iidAgile)
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
        fun addRef(@Suppress("UNUSED_PARAMETER") thisPtr: MemorySegment): Int = refCount.incrementAndGet()

        @Suppress("unused")
        fun release(@Suppress("UNUSED_PARAMETER") thisPtr: MemorySegment): Int =
            refCount.decrementAndGet().coerceAtLeast(0)

        @Suppress("unused")
        fun invoke(
            @Suppress("UNUSED_PARAMETER") thisPtr: MemorySegment,
            @Suppress("UNUSED_PARAMETER") senderPtr: MemorySegment,
            argsPtr: MemorySegment,
        ): Int {
            Arena.ofConfined().use { local ->
                val outButton = local.allocate(JAVA_INT)
                val argsObj = argsPtr.reinterpret(ADDRESS.byteSize())
                val argsVtbl = argsObj.get(ADDRESS, 0L).reinterpret(ADDRESS.byteSize() * (SLOT_ARGS_GET_BUTTON + 1L))
                val getButton = Linker.nativeLinker().downcallHandle(
                    argsVtbl.getAtIndex(ADDRESS, SLOT_ARGS_GET_BUTTON.toLong()),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                )
                val hr = getButton.invokeExact(argsPtr, outButton) as Int
                if (hr >= 0) onButton(outButton.get(JAVA_INT, 0L))
            }
            return S_OK
        }
    }

    private class FakeButtonPressedEventArgs(arena: Arena) {
        var nextButton: Int = BUTTON_PAUSE
        val segment: MemorySegment

        init {
            val linker = Linker.nativeLinker()
            val getButtonStub = linker.upcallStub(
                MethodHandles.lookup().findVirtual(
                    FakeButtonPressedEventArgs::class.java,
                    "getButton",
                    MethodType.methodType(
                        Int::class.javaPrimitiveType,
                        MemorySegment::class.java,
                        MemorySegment::class.java,
                    ),
                ).bindTo(this),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
                arena,
            )
            val vtbl = arena.allocate(ADDRESS.byteSize() * (SLOT_ARGS_GET_BUTTON + 1L))
            vtbl.setAtIndex(ADDRESS, SLOT_ARGS_GET_BUTTON.toLong(), getButtonStub)
            segment = arena.allocate(ADDRESS).apply { set(ADDRESS, 0L, vtbl) }
        }

        @Suppress("unused")
        fun getButton(@Suppress("UNUSED_PARAMETER") thisPtr: MemorySegment, outButton: MemorySegment): Int {
            outButton.reinterpret(JAVA_INT.byteSize()).set(JAVA_INT, 0L, nextButton)
            return S_OK
        }
    }

    private companion object {
        const val IID_IUNKNOWN = "00000000-0000-0000-c000-000000000046"
        const val IID_IAGILE_OBJECT = "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"
        const val IID_BUTTON_PRESSED_HANDLER = "0557e996-7b23-5bae-aa81-ea0d671143a4"
        const val S_OK = 0
        const val E_NOINTERFACE = -0x7fffbffe
        const val SLOT_ARGS_GET_BUTTON = 6
        const val BUTTON_PLAY = 0
        const val BUTTON_PAUSE = 1
        const val BUTTON_STOP = 2
        const val BUTTON_NEXT = 6
        const val BUTTON_PREVIOUS = 7

        fun writeGuid(seg: MemorySegment, spec: String) {
            val uuid = UUID.fromString(spec)
            val msb = uuid.mostSignificantBits
            val lsb = uuid.leastSignificantBits
            seg.set(JAVA_INT_UNALIGNED, 0L, (msb ushr 32).toInt())
            seg.set(JAVA_SHORT_UNALIGNED, 4L, ((msb ushr 16) and 0xFFFF).toShort())
            seg.set(JAVA_SHORT_UNALIGNED, 6L, (msb and 0xFFFF).toShort())
            for (i in 0 until 8) {
                seg.set(JAVA_BYTE, 8L + i, ((lsb ushr (56 - i * 8)) and 0xFF).toByte())
            }
        }

        fun unalignedGuidEquals(a: MemorySegment, b: MemorySegment): Boolean =
            a.get(JAVA_LONG_UNALIGNED, 0L) == b.get(JAVA_LONG_UNALIGNED, 0L) &&
                a.get(JAVA_LONG_UNALIGNED, 8L) == b.get(JAVA_LONG_UNALIGNED, 8L)
    }
}
