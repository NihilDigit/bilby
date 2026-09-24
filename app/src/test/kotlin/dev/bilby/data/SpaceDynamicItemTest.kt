package dev.bilby.data

import dev.bilby.api.dto.ArchiveDto
import dev.bilby.api.dto.ArchiveStatDto
import dev.bilby.api.dto.DynamicDescDto
import dev.bilby.api.dto.DynamicItemDto
import dev.bilby.api.dto.DynamicLiveDto
import dev.bilby.api.dto.DynamicModulesDto
import dev.bilby.api.dto.MajorDto
import dev.bilby.api.dto.ModuleAuthorDto
import dev.bilby.api.dto.ModuleDynamicDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空间动态 tab 做两处判断:**哪些条目能进播放队列**,以及**哪些视频也在投稿栏里**。
 *
 * 它值得测是因为错了不会报错:把转发来的视频判成投稿,「听这位 UP 的投稿」会开始放别人的
 * 稿件;把动态视频判成在投稿栏里,它在空间页两栏都看不到。类型分发本身在 DynamicCardMapperTest 里测。
 */
class SpaceDynamicItemTest {

    private val archive = ArchiveDto(
        bvid = "BV1xx",
        title = "标题",
        cover = "//c.jpg",
        durationText = "12:34",
        stat = ArchiveStatDto(play = "1.2万", danmaku = "88"),
    )

    private fun item(
        type: String,
        major: MajorDto?,
        desc: String? = null,
        orig: DynamicItemDto? = null,
        pubAction: String = "投稿了视频",
    ) = DynamicItemDto(
        idStr = "555",
        type = type,
        orig = orig,
        modules = DynamicModulesDto(
            moduleAuthor = ModuleAuthorDto(mid = 7L, name = "某人", pubTs = 1_700_000_000L, pubAction = pubAction),
            moduleDynamic = ModuleDynamicDto(major = major, desc = desc?.let { DynamicDescDto(text = it) }),
        ),
    )

    @Test
    fun `自己的投稿变成能进队列的视频行,发布时间取动态的时间`() {
        val result = item("DYNAMIC_TYPE_AV", MajorDto(archive = archive)).toSpaceDynamicItem()

        val video = requireNotNull(result?.video)
        assertEquals("BV1xx", video.bvid)
        // 发布时间在作者模块上,不在 major.archive 里 —— 取错的话列表上每条都是 1970。
        assertEquals(1_700_000_000L, video.publishedAtEpochSeconds)
        assertEquals("1.2万", video.playCountText)
        assertTrue(result.listedInArchive)
    }

    @Test
    fun `动态视频能进队列,但不算在投稿栏里`() {
        val result = item("DYNAMIC_TYPE_AV", MajorDto(archive = archive), pubAction = "发布了动态视频")
            .toSpaceDynamicItem()

        assertNotNull(result?.video)
        assertFalse(result!!.listedInArchive)
    }

    @Test
    fun `没见过的作者动作按不在投稿栏算`() {
        val result = item("DYNAMIC_TYPE_AV", MajorDto(archive = archive), pubAction = "发布了新形态视频")
            .toSpaceDynamicItem()

        assertFalse(result!!.listedInArchive)
    }

    @Test
    fun `合集更新也算他自己的投稿,不看作者动作`() {
        val result = item("DYNAMIC_TYPE_UGC_SEASON", MajorDto(ugcSeason = archive), pubAction = "")
            .toSpaceDynamicItem()

        assertNotNull(result?.video)
        assertTrue(result!!.listedInArchive)
    }

    @Test
    fun `转发别人的视频不进队列`() {
        val result = item(
            "DYNAMIC_TYPE_FORWARD",
            major = null,
            desc = "推荐一下",
            orig = item("DYNAMIC_TYPE_AV", MajorDto(archive = archive)),
            pubAction = "",
        ).toSpaceDynamicItem()

        assertNull(result?.video)
        assertFalse(result!!.listedInArchive)
    }

    @Test
    fun `非视频类型照样保留,不再被丢掉`() {
        val live = item("DYNAMIC_TYPE_LIVE", MajorDto(live = DynamicLiveDto(id = 1, title = "开播了", liveState = 1)))
        val word = item("DYNAMIC_TYPE_WORD", major = null, desc = "随便说两句")

        assertNotNull(live.toSpaceDynamicItem())
        assertNotNull(word.toSpaceDynamicItem())
    }
}
