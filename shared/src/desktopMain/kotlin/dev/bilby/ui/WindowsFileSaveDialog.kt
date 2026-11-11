package dev.bilby.ui

import dev.bilby.BiliLog
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
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
import java.lang.foreign.ValueLayout.JAVA_SHORT_UNALIGNED
import java.lang.invoke.MethodHandle
import java.util.UUID
import java.util.concurrent.Executors

private const val COINIT_APARTMENTTHREADED = 0x2
private const val RPC_E_CHANGED_MODE = -0x7ffefef0 // 0x80010106
private const val CLSCTX_INPROC_SERVER = 0x1

private const val FOS_OVERWRITEPROMPT = 0x2
private const val FOS_FORCEFILESYSTEM = 0x40
private const val FOS_PATHMUSTEXIST = 0x800

private const val SIGDN_FILESYSPATH = -0x7ffaf000 // 0x80058000
private const val ERROR_CANCELLED_HRESULT = -0x7ff8fb39 // 0x800704C7: HRESULT_FROM_WIN32(ERROR_CANCELLED)
private const val MAX_PATH_BYTES = 65_536L

/**
 * Windows 原生 `IFileSaveDialog` 保存对话框(JDK FFM 直调 `ole32.dll` / `user32.dll` 与 COM vtable)。
 *
 * 不用 Swing `JFileChooser`(外观脱离系统且高分屏字号发虚),也不用 AWT `FileDialog`(无法精细指定扩展名过滤)。
 * `IFileSaveDialog` 要求在 STA 线程上创建并模态展示,这里固定在单条 STA 守护线程上运行。
 */
internal object WindowsFileSaveDialog {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    private val staDispatcher by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "bilby-file-save-dialog").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    }

    /**
     * 弹出系统另存为对话框。用户取消时返回 `null`,选择路径后返回对应的 [File]。
     */
    suspend fun pickSavePath(
        suggestedName: String,
        extension: String,
        filterLabel: String,
    ): File? {
        if (!isWindows) return null
        val native = SaveDialogNative ?: return null
        return withContext(staDispatcher) {
            runCatching {
                showOnSta(native, suggestedName, extension, filterLabel)
            }.onFailure { BiliLog.w("拉起系统保存对话框失败", it) }.getOrNull()
        }
    }

    private fun showOnSta(
        native: SaveDialogBindings,
        suggestedName: String,
        extension: String,
        filterLabel: String,
    ): File? {
        val hrInit = native.coInitializeEx.invokeExact(MemorySegment.NULL, COINIT_APARTMENTTHREADED) as Int
        if (hrInit < 0 && hrInit != RPC_E_CHANGED_MODE) {
            BiliLog.w("CoInitializeEx(STA) 失败 hr=0x${hrInit.toUInt().toString(16)}")
            return null
        }

        Arena.ofConfined().use { arena ->
            val clsid = allocateGuid(arena, "c0b4e2f3-ba21-4773-8dba-335ec946eb8b") // CLSID_FileSaveDialog
            val iid = allocateGuid(arena, "84bccd23-5fde-4cdb-aea4-af64b83d78ab") // IID_IFileSaveDialog
            val outDialog = arena.allocate(ADDRESS)
            val hrCreate = native.coCreateInstance.invokeExact(
                clsid,
                MemorySegment.NULL,
                CLSCTX_INPROC_SERVER,
                iid,
                outDialog,
            ) as Int
            if (hrCreate < 0) {
                BiliLog.w("CoCreateInstance(FileSaveDialog) 失败 hr=0x${hrCreate.toUInt().toString(16)}")
                return null
            }
            val dialog = outDialog.get(ADDRESS, 0)
            if (dialog == MemorySegment.NULL) return null

            try {
                // 10: GetOptions, 9: SetOptions
                val outFos = arena.allocate(JAVA_INT)
                if ((native.callGetInt.invokeExact(vtableSlot(dialog, 10), dialog, outFos) as Int) >= 0) {
                    val fos = outFos.get(JAVA_INT, 0) or FOS_OVERWRITEPROMPT or FOS_FORCEFILESYSTEM or FOS_PATHMUSTEXIST
                    ignoreHr(native.callSetInt.invokeExact(vtableSlot(dialog, 9), dialog, fos) as Int)
                }

                val cleanExt = extension.trimStart('.').ifEmpty { "jpg" }
                // COMDLG_FILTERSPEC { LPCWSTR pszName; LPCWSTR pszSpec; }
                val filterSpec = arena.allocate(ADDRESS.byteSize() * 2, ADDRESS.byteAlignment())
                filterSpec.set(ADDRESS, 0, allocateUtf16(arena, filterLabel))
                filterSpec.set(ADDRESS, ADDRESS.byteSize(), allocateUtf16(arena, "*.$cleanExt"))
                // 4: SetFileTypes, 5: SetFileTypeIndex, 22: SetDefaultExtension, 15: SetFileName
                ignoreHr(native.callSetIntPtr.invokeExact(vtableSlot(dialog, 4), dialog, 1, filterSpec) as Int)
                ignoreHr(native.callSetInt.invokeExact(vtableSlot(dialog, 5), dialog, 1) as Int)
                ignoreHr(native.callSetPtr.invokeExact(vtableSlot(dialog, 22), dialog, allocateUtf16(arena, cleanExt)) as Int)
                ignoreHr(native.callSetPtr.invokeExact(vtableSlot(dialog, 15), dialog, allocateUtf16(arena, suggestedName)) as Int)

                val ownerHwnd = native.getActiveWindow.invokeExact() as MemorySegment
                // 3: IModalWindow::Show
                val hrShow = native.callSetPtr.invokeExact(vtableSlot(dialog, 3), dialog, ownerHwnd) as Int
                if (hrShow == ERROR_CANCELLED_HRESULT) return null
                if (hrShow < 0) {
                    BiliLog.w("FileSaveDialog.Show 失败 hr=0x${hrShow.toUInt().toString(16)}")
                    return null
                }

                // 20: GetResult -> IShellItem*
                val outItem = arena.allocate(ADDRESS)
                val hrResult = native.callGetPtr.invokeExact(vtableSlot(dialog, 20), dialog, outItem) as Int
                if (hrResult < 0) {
                    BiliLog.w("FileSaveDialog.GetResult 失败 hr=0x${hrResult.toUInt().toString(16)}")
                    return null
                }
                val item = outItem.get(ADDRESS, 0)
                if (item == MemorySegment.NULL) return null

                try {
                    // 5: IShellItem::GetDisplayName(SIGDN_FILESYSPATH, &pszName)
                    val outPathPtr = arena.allocate(ADDRESS)
                    val hrName = native.callSetIntPtr.invokeExact(
                        vtableSlot(item, 5),
                        item,
                        SIGDN_FILESYSPATH,
                        outPathPtr,
                    ) as Int
                    if (hrName < 0) {
                        BiliLog.w("IShellItem.GetDisplayName 失败 hr=0x${hrName.toUInt().toString(16)}")
                        return null
                    }
                    val pathPtr = outPathPtr.get(ADDRESS, 0)
                    if (pathPtr == MemorySegment.NULL) return null
                    try {
                        val path = readUtf16Z(pathPtr)
                        return path.takeIf { it.isNotBlank() }?.let(::File)
                    } finally {
                        native.coTaskMemFree.invokeExact(pathPtr)
                    }
                } finally {
                    releaseCom(native, item)
                }
            } finally {
                releaseCom(native, dialog)
            }
        }
    }

    private fun releaseCom(native: SaveDialogBindings, ptr: MemorySegment) {
        if (ptr == MemorySegment.NULL) return
        ignoreHr(native.release.invokeExact(vtableSlot(ptr, 2), ptr) as Int)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun ignoreHr(hr: Int) = Unit

    private fun vtableSlot(comObject: MemorySegment, index: Int): MemorySegment {
        val obj = comObject.reinterpret(ADDRESS.byteSize())
        val vtable = obj.get(ADDRESS, 0).reinterpret((index + 1L) * ADDRESS.byteSize())
        return vtable.get(ADDRESS, index * ADDRESS.byteSize())
    }

    private fun allocateUtf16(arena: Arena, text: String): MemorySegment {
        val seg = arena.allocate(JAVA_CHAR, (text.length + 1).toLong())
        for (i in text.indices) {
            seg.setAtIndex(JAVA_CHAR, i.toLong(), text[i])
        }
        seg.setAtIndex(JAVA_CHAR, text.length.toLong(), '\u0000')
        return seg
    }

    private fun readUtf16Z(ptr: MemorySegment): String {
        val seg = ptr.reinterpret(MAX_PATH_BYTES)
        val sb = StringBuilder()
        var i = 0L
        val maxChars = MAX_PATH_BYTES / JAVA_CHAR.byteSize()
        while (i < maxChars) {
            val ch = seg.get(JAVA_CHAR, i * JAVA_CHAR.byteSize())
            if (ch == '\u0000') break
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    private fun allocateGuid(arena: Arena, uuidText: String): MemorySegment {
        val uuid = UUID.fromString(uuidText)
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val seg = arena.allocate(16, 4)
        seg.set(JAVA_INT_UNALIGNED, 0, (msb ushr 32).toInt())
        seg.set(JAVA_SHORT_UNALIGNED, 4, ((msb ushr 16) and 0xFFFF).toShort())
        seg.set(JAVA_SHORT_UNALIGNED, 6, (msb and 0xFFFF).toShort())
        for (i in 0 until 8) {
            val shift = (7 - i) * 8
            seg.set(JAVA_BYTE, (8 + i).toLong(), ((lsb ushr shift) and 0xFF).toByte())
        }
        return seg
    }
}

private class SaveDialogBindings(
    val coInitializeEx: MethodHandle,
    val coCreateInstance: MethodHandle,
    val coTaskMemFree: MethodHandle,
    val getActiveWindow: MethodHandle,
    val release: MethodHandle,
    val callSetInt: MethodHandle,
    val callGetInt: MethodHandle,
    val callSetPtr: MethodHandle,
    val callGetPtr: MethodHandle,
    val callSetIntPtr: MethodHandle,
)

private val SaveDialogNative: SaveDialogBindings? by lazy {
    runCatching {
        val linker = Linker.nativeLinker()
        val ole32 = SymbolLookup.libraryLookup("ole32.dll", Arena.global())
        val user32 = SymbolLookup.libraryLookup("user32.dll", Arena.global())
        SaveDialogBindings(
            coInitializeEx = linker.downcallHandle(
                ole32.findOrThrow("CoInitializeEx"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT),
            ),
            coCreateInstance = linker.downcallHandle(
                ole32.findOrThrow("CoCreateInstance"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS),
            ),
            coTaskMemFree = linker.downcallHandle(
                ole32.findOrThrow("CoTaskMemFree"),
                FunctionDescriptor.ofVoid(ADDRESS),
            ),
            getActiveWindow = linker.downcallHandle(
                user32.findOrThrow("GetActiveWindow"),
                FunctionDescriptor.of(ADDRESS),
            ),
            release = linker.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS)),
            callSetInt = linker.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)),
            callGetInt = linker.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
            callSetPtr = linker.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
            callGetPtr = linker.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
            callSetIntPtr = linker.downcallHandle(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)),
        )
    }.onFailure { BiliLog.w("加载 ole32/user32 保存对话框符号失败", it) }.getOrNull()
}
