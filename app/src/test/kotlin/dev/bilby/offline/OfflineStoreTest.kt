package dev.bilby.offline

import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 目录布局与索引。这块值得测,因为它的错法都是**看不见的**:目录名撞了会让两条视频互相
 * 覆盖(播放时才发现内容不对),删除漏掉弹幕会让几十 MB 永远留在盘上而列表里查无此人。
 */
class OfflineStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun store(): OfflineStore = OfflineStore(temp.newFolder("offline"), json)

    private fun item(bvid: String, cid: Long, createdAt: Long = 0) = OfflineItem(
        bvid = bvid,
        cid = cid,
        title = "标题 / 带斜杠 🎬",
        durationSeconds = 100,
        status = OfflineStatus.Completed,
        createdAtMillis = createdAt,
    )

    @Test
    fun `同一条视频的不同分 P 各占一个目录`() {
        val store = store()
        val p1 = store.dirFor("BV1xx", 111)
        val p2 = store.dirFor("BV1xx", 222)
        assertTrue("分 P 不能共用目录,共用就会互相覆盖", p1.path != p2.path)
    }

    @Test
    fun `目录名不带标题,标题里的斜杠不会造出子目录`() {
        val store = store()
        val dir = store.dirFor("BV1xx", 111)
        assertEquals("BV1xx_111", dir.name)
    }

    @Test
    fun `写进去读得回来,并按创建时间倒序`() = runTest {
        val store = store()
        store.write(item("BV1aa", 1, createdAt = 100))
        store.write(item("BV1bb", 2, createdAt = 300))
        store.write(item("BV1cc", 3, createdAt = 200))

        assertEquals(listOf("BV1bb", "BV1cc", "BV1aa"), store.list().map { it.bvid })
        assertEquals(1L, store.read("BV1aa", 1)?.cid)
    }

    @Test
    fun `同一条写两遍只留一条`() = runTest {
        val store = store()
        store.write(item("BV1aa", 1).copy(qualityLabel = "720P"))
        store.write(item("BV1aa", 1).copy(qualityLabel = "1080P"))

        val all = store.list()
        assertEquals(1, all.size)
        assertEquals("1080P", all.single().qualityLabel)
    }

    @Test
    fun `没下完的不算已缓存`() = runTest {
        val store = store()
        store.write(item("BV1aa", 1).copy(status = OfflineStatus.Running))
        assertNull(store.completed("BV1aa", 1))
    }

    @Test
    fun `索引说下完了但文件没了也不算已缓存`() = runTest {
        val store = store()
        store.write(item("BV1aa", 1))
        // 索引在、文件不在:用户去文件管理器里删过,或者上一次写盘被打断。
        // 照索引播的话播放器会在打开文件时才失败,而那个错误离原因很远。
        assertNull(store.completed("BV1aa", 1))
    }

    @Test
    fun `完整的一条既有索引也有文件`() = runTest {
        val store = store()
        store.write(item("BV1aa", 1))
        store.videoFile("BV1aa", 1).writeBytes(byteArrayOf(1, 2, 3))
        assertEquals("BV1aa", store.completed("BV1aa", 1)?.bvid)
    }

    @Test
    fun `弹幕按 cid 存取`() = runTest {
        val store = store()
        store.writeDanmaku(cid = 42, segmentIndex = 2, bytes = byteArrayOf(7, 7))
        assertEquals(listOf<Byte>(7, 7), store.readDanmaku(42, 2)?.toList())
        assertNull("没存过的段不该编出一份空数据", store.readDanmaku(42, 1))
    }

    @Test
    fun `删除把视频和弹幕一起带走`() = runTest {
        val store = store()
        store.write(item("BV1aa", 1))
        store.videoFile("BV1aa", 1).writeBytes(byteArrayOf(1))
        store.audioFile("BV1aa", 1).writeBytes(byteArrayOf(1))
        store.writeDanmaku(1, 1, byteArrayOf(1))

        store.delete("BV1aa", 1)

        assertTrue(store.list().isEmpty())
        assertFalse(store.videoFile("BV1aa", 1).exists())
        // 漏掉弹幕的话它会一直躺在盘上,而列表里已经没有任何条目指向它。
        assertNull(store.readDanmaku(1, 1))
    }

    @Test
    fun `删一条不影响另一条的弹幕`() = runTest {
        val store = store()
        store.writeDanmaku(1, 1, byteArrayOf(1))
        store.writeDanmaku(2, 1, byteArrayOf(2))
        store.delete("BV1aa", 1)
        assertEquals(listOf<Byte>(2), store.readDanmaku(2, 1)?.toList())
    }

    @Test
    fun `按 bvid 就能查到本地副本,不需要先有 cid`() = runTest {
        // 这条查询要排在补 cid 之前 —— 补 cid 本身要联网,判在它后面等于离线时永远走不到
        // 本地那份。真机上"缓存了却播不动"就是这么来的。
        val store = store()
        store.write(item("BV1aa", 12345))
        store.videoFile("BV1aa", 12345).writeBytes(byteArrayOf(1))

        assertEquals(12345L, store.completedFor("BV1aa")?.cid)
    }

    @Test
    fun `索引说下完了但文件没了就不算能播`() = runTest {
        val store = store()
        store.write(item("BV1aa", 1))
        assertNull(store.completedFor("BV1aa"))
    }

    @Test
    fun `前缀相同的另一个 bvid 不会被误命中`() = runTest {
        // 目录名是 `<bvid>_<cid>`,只按前缀过滤的话 BV1aa 会把 BV1aabb 的目录也扫进来。
        val store = store()
        store.write(item("BV1aabb", 9))
        store.videoFile("BV1aabb", 9).writeBytes(byteArrayOf(1))

        assertNull(store.completedFor("BV1aa"))
        assertEquals(9L, store.completedFor("BV1aabb")?.cid)
    }

    @Test
    fun `没有 cid 的条目不落盘`() = runTest {
        val store = store()
        // 真机上出过:入队时 cid 还是 0,这一份写下去就在盘上留了个 `<bvid>_0` 目录,
        // 里面只有 349 字节的 meta.json、没有任何流,而列表里这条视频因此有两行。
        store.write(item("BV1nocid", cid = 0))

        assertTrue(store.list().isEmpty())
        assertFalse(store.dirFor("BV1nocid", 0).exists())
    }

    @Test
    fun `盘上已有的幽灵条目读不出来,并被当成无主目录扫掉`() = runTest {
        val store = store()
        // 旧版本留下的那种:目录和索引都在,但 cid 是 0。判成无效之后它不进列表,
        // sweepOrphans 就会把它当无主的清掉 —— 不用单写一段一次性的清理代码。
        val ghost = store.dirFor("BV1ghost", 0).apply { mkdirs() }
        File(ghost, "meta.json").writeText(
            """{"bvid":"BV1ghost","cid":0,"title":"","status":"Queued"}""",
        )

        assertTrue(store.list().isEmpty())
        store.sweepOrphans()
        assertFalse(ghost.exists())
    }

    @Test
    fun `扫掉没有索引的目录`() = runTest {
        val store = store()
        // `delete` 的顺序是先删索引后删文件,中间被打断就会留下这个:列表里查无此人、
        // 点不到也删不掉,却照样占着盘,而 usedBytes 会把它算进去。
        val orphan = store.dirFor("BV1gone", 9).apply { mkdirs() }
        File(orphan, "video.m4s").writeBytes(ByteArray(1024))

        assertEquals(1024L, store.sweepOrphans())
        assertFalse(orphan.exists())
    }

    @Test
    fun `下到一半的分片不算无主,不能扫掉`() = runTest {
        val store = store()
        // 进程被杀留下的断点:索引还在,只是状态是 Running。扫掉等于把用户已经下好的
        // 几百 MB 白白丢了,而续传本来只要接着写就行。
        store.write(item("BV1half", 5).copy(status = OfflineStatus.Running))
        store.videoFile("BV1half", 5).writeBytes(ByteArray(2048))

        assertEquals(0L, store.sweepOrphans())
        assertEquals(2048L, store.videoFile("BV1half", 5).length())
    }

    @Test
    fun `扫掉没人引用的弹幕目录,留下有人引用的`() = runTest {
        val store = store()
        store.write(item("BV1keep", 7))
        store.writeDanmaku(cid = 7, segmentIndex = 1, bytes = ByteArray(16))
        store.writeDanmaku(cid = 999, segmentIndex = 1, bytes = ByteArray(32))

        assertEquals(32L, store.sweepOrphans())
        assertEquals(16, store.readDanmaku(7, 1)?.size)
        assertNull(store.readDanmaku(999, 1))
    }

    @Test
    fun `索引读不出来的目录也算无主`() = runTest {
        val store = store()
        val broken = store.dirFor("BV1bad", 3).apply { mkdirs() }
        File(broken, "meta.json").writeText("{ 这不是 JSON")
        File(broken, "video.m4s").writeBytes(ByteArray(512))

        // JSON 解析失败不是瞬时故障,那份索引不会自己好起来 —— 留着只是把一块永远回收不了的
        // 空间留在盘上,而缓存本来就是可以重下的。
        assertEquals(true, store.sweepOrphans() > 0)
        assertFalse(broken.exists())
    }

    @Test
    fun `没有残留时扫描什么都不做`() = runTest {
        val store = store()
        store.write(item("BV1aa", 1))
        store.videoFile("BV1aa", 1).writeBytes(ByteArray(64))
        store.writeDanmaku(1, 1, ByteArray(8))

        assertEquals(0L, store.sweepOrphans())
        assertEquals(1, store.list().size)
    }

    @Test
    fun `写坏的索引只跳过它自己`() = runTest {
        val store = store()
        store.write(item("BV1good", 1, createdAt = 10))
        val broken = File(store.dirFor("BV1bad", 2).apply { mkdirs() }, "meta.json")
        broken.writeText("{ 这不是 JSON")

        assertEquals(listOf("BV1good"), store.list().map { it.bvid })
    }
}
