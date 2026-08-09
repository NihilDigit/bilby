package dev.bilby.danmaku

/**
 * 手写的最小 protobuf wire-format 读取器,只认 varint 与 length-delimited 两种 wire type ——
 * `DmSegMobileReply`/`DanmakuElem` 用到的字段全部落在这两类里,没有 fixed32/fixed64、没有
 * packed repeated 之外的复杂编码(notes/danmaku.md §6.3)。
 *
 * 不引 Wire 或 protobuf-javalite:两者都靠按名反射访问生成代码的字段来做序列化/反序列化,
 * 都需要额外的 R8 keep 规则,而这个代码库的前提是"从不按名字查类或成员"(CLAUDE.md,
 * `app/proguard-rules.pro` 短就是因为这个)。手写解析器不涉及类反射,天然不需要任何规则。
 *
 * **子消息不复制字节**:reader 持有 `bytes + pos + end` 三元组,[readMessage] 只是按长度前缀
 * 算出一个新的 `[pos, pos + length)` 区间,和父 reader 共享同一个底层数组。一段 6 分钟的热门
 * 视频有几千条弹幕,每条都 `copyOfRange` 一次就是几千次数组分配加拷贝,而这些副本除了被读一遍
 * 之外没有任何用途。字符串是唯一真正需要新对象的地方,在 [readString] 里按区间解码。
 *
 * 越界一律抛 [IllegalStateException]。调用方(`DanmakuRepository`)整段包在 runCatching 里,
 * 一份畸形或被中间人截断的响应只会让这一段弹幕缺失,不会把异常带进播放链路。
 */
internal class DanmakuProtoReader private constructor(
    private val bytes: ByteArray,
    private var pos: Int,
    private val end: Int,
) {

    constructor(bytes: ByteArray) : this(bytes, 0, bytes.size)

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
            // 逐字节检查边界,而不是先读完再判断:延续位是攻击者可控的,一串 0x80 能让循环
            // 一直往后读;这里读的又是整个响应体的数组,越过 end 会静静吃掉后一条弹幕的字节
            // (在子 reader 里 end 远小于 bytes.size,数组下标本身不会越界,不会有异常提醒)。
            check(pos < end) { "varint 越过消息末尾,protobuf 流已截断" }
            // 64 位装不下的 varint 只可能来自畸形数据。不拦的话 shl 的移位量在 JVM 上按 mod 64
            // 回绕,高位会覆盖已经读进 result 的低位,得到一个看似正常的数值。
            check(shift <= MAX_VARINT_SHIFT) { "varint 超过 10 字节,protobuf 流已损坏" }
            val b = bytes[pos].toInt() and 0xFF
            pos++
            result = result or ((b.toLong() and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    /**
     * length-delimited:读长度前缀,返回一个限定在这段区间内的子 reader,不复制底层字节。
     * 子 reader 的 `end` 就是这条子消息的末尾,所以子消息内部的越界读会在子 reader 里被
     * [readVarint] 的 `pos < end` 挡住,不会串到兄弟消息的字节上去。
     */
    fun readMessage(): DanmakuProtoReader {
        val length = readLength()
        val start = pos
        pos += length
        return DanmakuProtoReader(bytes, start, start + length)
    }

    /** 唯一真正产生新对象的地方:按 `[pos, pos + length)` 区间解码 UTF-8。 */
    fun readString(): String {
        val length = readLength()
        val s = String(bytes, pos, length, Charsets.UTF_8)
        pos += length
        return s
    }

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
                val length = readLength()
                pos += length
            }
            WIRE_FIXED64 -> skipFixed(8)
            WIRE_FIXED32 -> skipFixed(4)
            else -> error("不认识的 wire type $wireType,protobuf 流可能已损坏")
        }
    }

    /**
     * 长度前缀的三道校验合在一处:负数(varint 超过 Int 范围后 toInt 会翻负)、越过本消息
     * 末尾、以及由此产生的 `pos + length` 溢出。三者都必须在推进 pos 之前判掉。
     */
    private fun readLength(): Int {
        val raw = readVarint()
        check(raw in 0..(end - pos).toLong()) { "length-delimited 长度 $raw 越界,protobuf 流已损坏" }
        return raw.toInt()
    }

    private fun skipFixed(size: Int) {
        check(end - pos >= size) { "fixed$size 字段越过消息末尾,protobuf 流已截断" }
        pos += size
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LEN = 2
        const val WIRE_FIXED32 = 5

        /** 第 10 个字节的移位量。protobuf 的 varint 最长 10 字节(64 位 + 延续位)。 */
        private const val MAX_VARINT_SHIFT = 63
    }
}
