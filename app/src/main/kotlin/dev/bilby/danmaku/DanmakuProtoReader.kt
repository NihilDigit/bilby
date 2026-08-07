package dev.bilby.danmaku

/**
 * 手写的最小 protobuf wire-format 读取器,只认 varint 与 length-delimited 两种 wire type ——
 * `DmSegMobileReply`/`DanmakuElem` 用到的字段全部落在这两类里,没有 fixed32/fixed64、没有
 * packed repeated 之外的复杂编码(notes/danmaku.md §6.3)。
 *
 * 不引 Wire 或 protobuf-javalite:两者都靠按名反射访问生成代码的字段来做序列化/反序列化,
 * 都需要额外的 R8 keep 规则,而这个代码库的前提是"从不按名字查类或成员"(CLAUDE.md,
 * `app/proguard-rules.pro` 短就是因为这个)。手写解析器不涉及类反射,天然不需要任何规则。
 */
internal class DanmakuProtoReader(
    private val bytes: ByteArray,
    private var pos: Int = 0,
    private val end: Int = bytes.size,
) {

    fun hasNext(): Boolean = pos < end

    /** 读一个 tag,拆成 (字段号, wire type)。 */
    fun readTag(): Pair<Int, Int> {
        val tag = readVarint()
        return (tag ushr 3).toInt() to (tag and 0x7).toInt()
    }

    /** varint:7 位一组、小端序,每个字节最高位是"后面还有没有下一组"的延续位。 */
    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = bytes[pos].toInt() and 0xFF
            pos++
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    /** length-delimited:先读一个长度 varint,再原样切出那段字节,不做递归解析。 */
    fun readLengthDelimited(): ByteArray {
        val length = readVarint().toInt()
        val slice = bytes.copyOfRange(pos, pos + length)
        pos += length
        return slice
    }

    fun readString(): String = readLengthDelimited().toString(Charsets.UTF_8)

    /**
     * 跳过一个不认识的字段。B 站往协议里加字段是常态(notes/danmaku.md §2:protobuf 版本比
     * XML 多出一堆新字段),这里必须能正确跳过任意已知 wire type,否则服务端一加字段这个
     * 解析器就直接读飞、后面所有字段全部错位。
     */
    fun skip(wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> readVarint()
            // 长度必须先落进局部变量。写成 `pos += readVarint().toInt()` 会错:
            // `pos += e` 展开成 `pos = pos + e`,左边的 pos 在求值 e **之前**就被读走了,
            // 而 readVarint() 自己会推进 pos —— 读长度前缀走过的那几个字节于是被这次赋值
            // 覆盖掉,每跳过一个未知的 length 字段就少走一截,后面所有字段全部错位。
            WIRE_LEN -> {
                val length = readVarint().toInt()
                pos += length
            }
            WIRE_FIXED64 -> pos += 8
            WIRE_FIXED32 -> pos += 4
            else -> error("不认识的 wire type $wireType,protobuf 流可能已损坏")
        }
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LEN = 2
        const val WIRE_FIXED32 = 5
    }
}
