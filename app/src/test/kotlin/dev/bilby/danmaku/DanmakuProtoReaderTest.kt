package dev.bilby.danmaku

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test
    fun `固定 fixture - 逐字段核对解析结果`() {
        // 这条 fixture 的用途是给"子消息不再复制字节"这次改造做基准:同一份字节,改造前后
        // 必须解析出同一组值。手写字节而不是抓一份真实响应,是为了让 tag 号、多字节 varint、
        // 未知字段的位置都能在测试里读出来。
        val reply = ByteArrayOutputStream().apply {
            writeVarintField(2, 1L) // state,已知但不用的字段
            writeBytesField(
                1,
                ByteArrayOutputStream().apply {
                    writeVarintField(1, 1_694_000_000_123L) // id,需要多字节 varint
                    writeVarintField(2, 359_999L)
                    writeVarintField(3, 5L)
                    writeVarintField(4, 25L)
                    writeVarintField(5, 0xFFFFFFL)
                    writeVarintField(6, 0x1A2B3C4DL) // midHash,跳过
                    writeStringField(7, "顶端弹幕 with ascii")
                    writeStringField(12, "1694000000123")
                }.toByteArray(),
            )
            writeBytesField(
                1,
                ByteArrayOutputStream().apply {
                    writeVarintField(2, 1L)
                    writeVarintField(3, 1L)
                    writeStringField(7, "")
                }.toByteArray(),
            )
        }.toByteArray()

        val elems = parseDmSegMobileReply(reply)

        assertEquals(2, elems.size)
        assertEquals(
            RawDanmakuElem(
                id = 1_694_000_000_123L,
                idStr = "1694000000123",
                progressMillis = 359_999,
                mode = 5,
                fontSize = 25,
                color = 0xFFFFFF,
                content = "顶端弹幕 with ascii",
            ),
            elems[0],
        )
        assertEquals(RawDanmakuElem(progressMillis = 1, mode = 1), elems[1])
    }

    @Test
    fun `空 reply 与空子消息`() {
        assertEquals(emptyList<RawDanmakuElem>(), parseDmSegMobileReply(ByteArray(0)))

        // 长度为 0 的子消息:所有字段取默认值,不能因为没有字节可读就抛。
        val reply = ByteArrayOutputStream().apply { writeBytesField(1, ByteArray(0)) }.toByteArray()
        assertEquals(listOf(RawDanmakuElem()), parseDmSegMobileReply(reply))
    }

    @Test
    fun `截断的 varint 不会读过消息末尾`() {
        // 0x80 是"后面还有下一组"的延续位,但后面什么都没有。旧写法在这里直接按下标取数组,
        // 靠 ArrayIndexOutOfBounds 兜底;子 reader 的 end 小于数组长度之后,越界读到的会是
        // 下一条弹幕的字节,根本不会抛——所以这条要盯的是"抛出来了",不是"抛了什么"。
        val truncated = byteArrayOf((1 shl 3).toByte(), 0x80.toByte())
        assertThrows(IllegalStateException::class.java) { parseDanmakuElem(truncated) }
    }

    @Test
    fun `声明长度越过消息末尾`() {
        // content 声称有 100 字节,实际只给了 3 个。
        val bytes = ByteArrayOutputStream().apply {
            writeTag(7, DanmakuProtoReader.WIRE_LEN)
            writeVarint(100L)
            write(byteArrayOf(1, 2, 3))
        }.toByteArray()
        assertThrows(IllegalStateException::class.java) { parseDanmakuElem(bytes) }
    }

    @Test
    fun `超出 Int 范围的长度不会翻成负数`() {
        // varint 能表达远超 Int 的值,toInt() 之后可能是负数;负长度加到 pos 上会让读取位置
        // 往回跳,构造出无限循环或读到已经解析过的字节。
        val bytes = ByteArrayOutputStream().apply {
            writeTag(7, DanmakuProtoReader.WIRE_LEN)
            writeVarint(0x1_0000_0001L)
            write(byteArrayOf(1, 2, 3))
        }.toByteArray()
        assertThrows(IllegalStateException::class.java) { parseDanmakuElem(bytes) }
    }

    @Test
    fun `子消息读不到兄弟消息的字节`() {
        // 第一条弹幕声称自己的 content 有 40 字节,而它自己的消息体只剩几个字节——如果 reader
        // 只按整份响应的长度做边界,这一读就会把第二条弹幕的字节当成第一条的正文吃掉,而且
        // 完全静默。共享底层数组的 bounded reader 正是在这里必须表现得和"切一份副本"一样。
        val elem = ByteArrayOutputStream().apply {
            writeTag(7, DanmakuProtoReader.WIRE_LEN)
            writeVarint(40L)
            write("短".toByteArray(Charsets.UTF_8))
        }.toByteArray()
        val reply = ByteArrayOutputStream().apply {
            writeBytesField(1, elem)
            writeBytesField(1, danmakuElemBytes(2, 2000, 1, "第二条".repeat(10)))
        }.toByteArray()

        assertThrows(IllegalStateException::class.java) { parseDmSegMobileReply(reply) }
    }

    @Test
    fun `外层声明的子消息长度越界`() {
        val reply = ByteArrayOutputStream().apply {
            writeTag(1, DanmakuProtoReader.WIRE_LEN)
            writeVarint(1000L)
            write(danmakuElemBytes(1, 1000, 1, "只有这么多"))
        }.toByteArray()

        assertThrows(IllegalStateException::class.java) { parseDmSegMobileReply(reply) }
    }

    @Test
    fun `十字节以上的 varint 被拒`() {
        // 全是延续位的一长串字节:不拦的话 shl 的移位量按 mod 64 回绕,高位会盖住已经读进去的
        // 低位,解出一个看着正常的数值。
        val bytes = ByteArrayOutputStream().apply {
            writeTag(1, DanmakuProtoReader.WIRE_VARINT)
            repeat(11) { write(0x80) }
            write(0x01)
        }.toByteArray()
        assertThrows(IllegalStateException::class.java) { parseDanmakuElem(bytes) }
    }

    @Test
    fun `大消息 - 五千条弹幕的边界不串位`() {
        // 一段 6 分钟的热门视频就是这个量级。条数对不上或者首尾内容错位,说明某处多吃或少吃了
        // 字节;中间任何一条串位都会一路传导到最后一条。
        val count = 5000
        val reply = ByteArrayOutputStream().apply {
            repeat(count) { i ->
                writeBytesField(1, danmakuElemBytes(i.toLong(), i * 70, 1, "弹幕$i"))
            }
        }.toByteArray()

        val elems = parseDmSegMobileReply(reply)

        assertEquals(count, elems.size)
        assertEquals("弹幕0", elems.first().content)
        assertEquals("弹幕${count - 1}", elems.last().content)
        assertEquals((count - 1) * 70, elems.last().progressMillis)
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
