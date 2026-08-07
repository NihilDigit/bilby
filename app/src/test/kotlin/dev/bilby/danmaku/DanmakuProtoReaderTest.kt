package dev.bilby.danmaku

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 手写 protobuf 读取器的性质测试:varint 的多字节边界、length-delimited 的嵌套、
 * 未知字段能不能正确跳过——这三条是这类手写解析器真正会出错的地方。不测"读一个已知
 * 字段能不能读出已知值"这种复述,那种错误编译期就会被类型系统挡住大半。
 */
class DanmakuProtoReaderTest {

    @Test
    fun `varint 跨字节边界`() {
        // 127 是单字节 varint 的上限,128 是最小的双字节 varint —— 编码逻辑真出错最容易
        // 在这条边界上翻车(比如漏判延续位、shift 算错)。
        assertEquals(0L, roundTripVarint(0))
        assertEquals(127L, roundTripVarint(127))
        assertEquals(128L, roundTripVarint(128))
        assertEquals(300L, roundTripVarint(300))
        assertEquals(Int.MAX_VALUE.toLong(), roundTripVarint(Int.MAX_VALUE.toLong()))
    }

    @Test
    fun `length-delimited 嵌套 - 两条子消息各自正确切分`() {
        // 模拟 DmSegMobileReply:两条 DanmakuElem(tag 1,LEN)背靠背放在一起,读第一条
        // 不能多吃或少吃字节,影响第二条的边界。
        val first = danmakuElemBytes(id = 1, progressMillis = 1000, mode = 1, content = "第一条")
        val second = danmakuElemBytes(id = 2, progressMillis = 2000, mode = 4, content = "第二条")
        val reply = ByteArrayOutputStream().apply {
            writeBytesField(1, first)
            writeBytesField(1, second)
        }.toByteArray()

        val elems = parseDmSegMobileReply(reply)

        assertEquals(2, elems.size)
        assertEquals("第一条", elems[0].content)
        assertEquals(1000, elems[0].progressMillis)
        assertEquals("第二条", elems[1].content)
        assertEquals(2000, elems[1].progressMillis)
    }

    @Test
    fun `未知字段按各自 wire type 正确跳过,不影响已知字段`() {
        // 已知字段(1/2/3/7)之间穿插四种 wire type 的未知字段(99=varint,98=length-delimited
        // 垃圾字节,97=fixed32,96=fixed64),且未知字段既出现在已知字段之前也出现在之后——
        // 这是"B 站以后加字段"的真实场景,不是构造出来凑数的分支。
        val bytes = ByteArrayOutputStream().apply {
            writeVarintField(99, 123456789L) // 未知 varint,读之前
            writeVarintField(1, 42L) // id
            writeBytesField(98, byteArrayOf(1, 2, 3, 4, 5)) // 未知 length-delimited,任意垃圾字节
            writeVarintField(2, 5000L) // progress
            writeFixed32Field(97, 0xDEADBEEF.toInt()) // 未知 fixed32
            writeVarintField(3, 1L) // mode
            writeFixed64Field(96, 0x1122334455667788L) // 未知 fixed64
            writeStringField(7, "带垃圾字段的弹幕") // content
        }.toByteArray()

        val elem = parseDanmakuElem(bytes)

        assertEquals(42L, elem.id)
        assertEquals(5000, elem.progressMillis)
        assertEquals(1, elem.mode)
        assertEquals("带垃圾字段的弹幕", elem.content)
    }

    @Test
    fun `idStr 优先于数字 id`() {
        val bytes = ByteArrayOutputStream().apply {
            writeVarintField(1, 42L)
            writeStringField(12, "42-str")
        }.toByteArray()

        assertEquals("42-str", parseDanmakuElem(bytes).idStr)
    }

    private fun roundTripVarint(value: Long): Long {
        val bytes = ByteArrayOutputStream().apply { writeVarint(value) }.toByteArray()
        return DanmakuProtoReader(bytes).readVarint()
    }
}

// ---- 测试专用的最小 protobuf 写入辅助:只写这几个测试要用到的字段类型,不追求通用性 ----

private fun ByteArrayOutputStream.writeVarint(value: Long) {
    var v = value
    while (true) {
        val b = (v and 0x7F).toInt()
        v = v ushr 7
        if (v == 0L) {
            write(b)
            return
        }
        write(b or 0x80)
    }
}

private fun ByteArrayOutputStream.writeTag(field: Int, wireType: Int) {
    writeVarint(((field.toLong() shl 3) or wireType.toLong()))
}

private fun ByteArrayOutputStream.writeVarintField(field: Int, value: Long) {
    writeTag(field, DanmakuProtoReader.WIRE_VARINT)
    writeVarint(value)
}

private fun ByteArrayOutputStream.writeStringField(field: Int, value: String) {
    writeBytesField(field, value.toByteArray(Charsets.UTF_8))
}

private fun ByteArrayOutputStream.writeBytesField(field: Int, bytes: ByteArray) {
    writeTag(field, DanmakuProtoReader.WIRE_LEN)
    writeVarint(bytes.size.toLong())
    write(bytes)
}

private fun ByteArrayOutputStream.writeFixed32Field(field: Int, value: Int) {
    writeTag(field, DanmakuProtoReader.WIRE_FIXED32)
    write(value and 0xFF)
    write((value ushr 8) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 24) and 0xFF)
}

private fun ByteArrayOutputStream.writeFixed64Field(field: Int, value: Long) {
    writeTag(field, DanmakuProtoReader.WIRE_FIXED64)
    for (i in 0 until 8) write(((value ushr (i * 8)) and 0xFF).toInt())
}

private fun danmakuElemBytes(id: Long, progressMillis: Int, mode: Int, content: String): ByteArray =
    ByteArrayOutputStream().apply {
        writeVarintField(1, id)
        writeVarintField(2, progressMillis.toLong())
        writeVarintField(3, mode.toLong())
        writeStringField(7, content)
    }.toByteArray()
