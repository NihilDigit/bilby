package dev.bilby.data

import dev.bilby.api.dto.ArchiveCursorResponseDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App 端空间投稿游标接口（`x/v2/space/archive/cursor`，notes/space-archive-cursor.md）的 DTO 解析、
 * UGC 过滤（丢弃 `is_pgc`、`is_pugv` 与空 `bvid`）、充电专属角标识别与原始末条游标推进测试。
 */
class SpaceArchiveCursorTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `解析完整游标响应并正确映射时长、计数、HTTPS 封面与充电专属角标`() {
        val raw = """
            {
              "count": 128,
              "has_prev": false,
              "has_next": true,
              "item": [
                {
                  "param": "170001",
                  "bvid": "BV17x411w7KC",
                  "title": "普通投稿",
                  "cover": "http://i0.hdslb.com/bfs/archive/a.jpg",
                  "duration": 125,
                  "author": "Lu小鱼",
                  "play": 125000,
                  "danmaku": 456,
                  "ctime": 1700000000,
                  "is_pgc": false,
                  "is_pugv": false,
                  "badges": [{"text": "合作"}]
                },
                {
                  "param": "170002",
                  "bvid": "BV1xx411c7mD",
                  "title": "充电视频",
                  "cover": "//i1.hdslb.com/bfs/archive/b.jpg",
                  "duration": 3661,
                  "author": "Lu小鱼",
                  "play": 980,
                  "danmaku": 12,
                  "ctime": 1700000500,
                  "badges": [{"text": "充电专属"}]
                },
                {
                  "param": "170003",
                  "bvid": "BV1ab411c7mE",
                  "title": "空角标字段",
                  "cover": "https://i2.hdslb.com/bfs/archive/c.jpg",
                  "duration": 59,
                  "author": "Lu小鱼",
                  "play": 150000000,
                  "danmaku": 25000,
                  "ctime": 1700001000,
                  "badges": null
                }
              ]
            }
        """.trimIndent()

        val dto = json.decodeFromString(ArchiveCursorResponseDto.serializer(), raw)
        val page = dto.toSpaceArchiveCursorPage()

        assertEquals(128, page.total)
        assertTrue(page.hasMore)
        assertEquals(170003L, page.nextCursorAid)
        assertEquals(3, page.items.size)

        val first = page.items[0]
        assertEquals("BV17x411w7KC", first.bvid)
        assertEquals("https://i0.hdslb.com/bfs/archive/a.jpg", first.coverUrl)
        assertEquals("02:05", first.durationText)
        assertEquals("12.5万", first.playCountText)
        assertEquals("456", first.danmakuCountText)
        assertEquals(1700000000L, first.publishedAtEpochSeconds)
        assertFalse(first.chargingOnly)
        assertEquals("Lu小鱼", first.upName)

        val charging = page.items[1]
        assertEquals("https://i1.hdslb.com/bfs/archive/b.jpg", charging.coverUrl)
        assertEquals("01:01:01", charging.durationText)
        assertTrue(charging.chargingOnly)

        val nullBadges = page.items[2]
        assertEquals("1.5亿", nullBadges.playCountText)
        assertEquals("2.5万", nullBadges.danmakuCountText)
        assertFalse(nullBadges.chargingOnly)
    }

    @Test
    fun `过滤 PGC、PUGV 与空 bvid 但游标仍按原始末条 param 推进`() {
        val raw = """
            {
              "count": 40,
              "has_prev": true,
              "has_next": true,
              "item": [
                {
                  "param": "5001",
                  "bvid": "BV1xx411c7mA",
                  "title": "番剧项",
                  "is_pgc": true
                },
                {
                  "param": "5002",
                  "bvid": "BV1xx411c7mB",
                  "title": "正常 UGC",
                  "cover": "http://i0.hdslb.com/u.jpg",
                  "duration": 90,
                  "play": 42,
                  "danmaku": 3,
                  "ctime": 1700000100
                },
                {
                  "param": "5003",
                  "bvid": "",
                  "title": "无 bvid"
                },
                {
                  "param": "5004",
                  "bvid": "BV1xx411c7mC",
                  "title": "课程项",
                  "is_pugv": true
                }
              ]
            }
        """.trimIndent()

        val dto = json.decodeFromString(ArchiveCursorResponseDto.serializer(), raw)
        val browsePage = dto.toSpaceArchiveCursorPage(previousCursorAid = 6000L)

        assertEquals(listOf("BV1xx411c7mB"), browsePage.items.map { it.bvid })
        assertTrue(browsePage.hasMore)
        // 末条 5004 是被过滤的 PUGV，但下一页游标仍取原始末条 5004 防止重复拉取或停滞
        assertEquals(5004L, browsePage.nextCursorAid)

        val cursorPage = dto.toCursorArchivePage()
        assertEquals(listOf(5002L), cursorPage.items.map { it.aid })
        assertEquals(5001L, cursorPage.firstRawAid)
        assertEquals(5004L, cursorPage.lastRawAid)
        assertTrue(cursorPage.hasNewer)
        assertTrue(cursorPage.hasOlder)
    }

    @Test
    fun `has_next 为 false、空页或游标未前进时停止分页`() {
        val lastPageDto = json.decodeFromString(
            ArchiveCursorResponseDto.serializer(),
            """{"count": 2, "has_next": false, "item": [{"param": "9001", "bvid": "BV1xx411c7mZ"}]}""",
        )
        val lastPage = lastPageDto.toSpaceArchiveCursorPage(previousCursorAid = 9999L)
        assertFalse(lastPage.hasMore)
        assertNull(lastPage.nextCursorAid)
        assertEquals(1, lastPage.items.size)

        val emptyDto = json.decodeFromString(
            ArchiveCursorResponseDto.serializer(),
            """{"count": 2, "has_next": true, "item": []}""",
        )
        val emptyPage = emptyDto.toSpaceArchiveCursorPage(previousCursorAid = 9001L)
        assertFalse(emptyPage.hasMore)
        assertNull(emptyPage.nextCursorAid)

        val stalledDto = json.decodeFromString(
            ArchiveCursorResponseDto.serializer(),
            """{"count": 2, "has_next": true, "item": [{"param": "9001", "bvid": "BV1xx411c7mZ"}]}""",
        )
        val stalledPage = stalledDto.toSpaceArchiveCursorPage(previousCursorAid = 9001L)
        assertFalse(stalledPage.hasMore)
        assertNull(stalledPage.nextCursorAid)
    }
}
