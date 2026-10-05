package dev.bilby.update

import com.github.luben.zstd.util.Native
import dev.bilby.BiliLog
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle

/**
 * 还原差分包里的 zstd 帧:以本机旧文件为前缀(zstd --patch-from 的对端),经 FFM 直调 libzstd 的 C API。
 *
 * 不用 zstd-jni 的 ZstdDecompressCtx:它的 win_aarch64 原生库只导出 ZSTD_* C 函数,一个 JNI 方法都没有。
 * zstd-jni 在 JDK 22 以上改走 jar 里 META-INF/versions/22 的 FFM 绑定,而那套绑定只覆盖了一部分类,
 * ZstdDecompressCtx 与 ZstdInputStreamNoFinalizer.setDict 仍调 JNI,在 ARM64 上抛 UnsatisfiedLinkError。
 * x64 的库两样都导出,两个架构因此走同一条路;zstd-jni 只负责把库加载进进程,
 * Native.load 认 ZstdNativePath,发行包里指向安装目录的那一份(见 DesktopAppUpdater.useBundledZstd)。
 *
 * 一次性解码(ZSTD_decompressDCtx)不受 windowLogMax 限制,CI 为大文件自动加大窗口压出的帧
 * 也不必另设参数,ARM64 上实测 160MB 的文件可以还原。
 */
internal object ZstdPatch {
    private class Functions(private val lookup: SymbolLookup) {
        private val linker = Linker.nativeLinker()

        private fun function(name: String, descriptor: FunctionDescriptor): MethodHandle =
            linker.downcallHandle(lookup.find(name).orElseThrow { NoSuchElementException(name) }, descriptor)

        val createDCtx = function("ZSTD_createDCtx", FunctionDescriptor.of(ADDRESS))
        val freeDCtx = function("ZSTD_freeDCtx", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
        val refPrefix = function("ZSTD_DCtx_refPrefix", FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG))
        val decompressDCtx = function(
            "ZSTD_decompressDCtx",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG),
        )
        val isError = function("ZSTD_isError", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
        val getErrorName = function("ZSTD_getErrorName", FunctionDescriptor.of(ADDRESS, JAVA_LONG))
    }

    // loaderLookup 看得到同一个类加载器经 System.load 载入的库,也就是 Native.load 载入的那一份。
    private val functions: Functions? by lazy {
        runCatching {
            Native.load()
            Functions(SymbolLookup.loaderLookup())
        }.onFailure { BiliLog.w("libzstd 加载失败,差分更新不可用,改下完整补丁包", it) }.getOrNull()
    }

    /** 加载失败是 UnsatisfiedLinkError 这类 Error,接不住;选更新方式之前先问这里。 */
    val isAvailable: Boolean get() = functions != null

    /** [base] 为 null 时 [frame] 是普通的 zstd 帧。还原结果不是 [size] 字节,或 zstd 报错时抛 [ZstdPatchException]。 */
    fun decode(base: ByteArray?, frame: ByteArray, size: Int): ByteArray {
        val f = checkNotNull(functions) { "libzstd 未加载" }
        Arena.ofConfined().use { arena ->
            val ctx = f.createDCtx.invokeWithArguments() as MemorySegment
            if (ctx == MemorySegment.NULL) throw OutOfMemoryError("ZSTD_createDCtx")
            try {
                if (base != null) {
                    // 前缀只被引用不被复制,解码结束前段内存要一直有效,所以随 arena 一起释放。
                    val prefix = arena.copyOf(base)
                    f.requireOk(f.refPrefix.invokeWithArguments(ctx, prefix, base.size.toLong()) as Long)
                }
                val source = arena.copyOf(frame)
                val target = arena.allocate(size.toLong().coerceAtLeast(1))
                val written = f.requireOk(
                    f.decompressDCtx.invokeWithArguments(ctx, target, size.toLong(), source, frame.size.toLong()) as Long,
                )
                if (written != size.toLong()) throw ZstdPatchException("还原出 $written 字节,应为 $size")
                return target.asSlice(0, written).toArray(JAVA_BYTE)
            } finally {
                f.freeDCtx.invokeWithArguments(ctx)
            }
        }
    }

    private fun Arena.copyOf(bytes: ByteArray): MemorySegment =
        allocate(bytes.size.toLong().coerceAtLeast(1)).also { MemorySegment.copy(bytes, 0, it, JAVA_BYTE, 0, bytes.size) }

    private fun Functions.requireOk(code: Long): Long {
        if (isError.invokeWithArguments(code) as Int == 0) return code
        val name = (getErrorName.invokeWithArguments(code) as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)
        throw ZstdPatchException(name)
    }
}

internal class ZstdPatchException(message: String) : Exception(message)
