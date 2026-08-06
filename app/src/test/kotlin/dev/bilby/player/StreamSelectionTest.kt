package dev.bilby.player

import dev.bilby.api.dto.DashDto
import dev.bilby.api.dto.DashStreamDto
import dev.bilby.api.dto.DolbyDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 只覆盖三类"服务端给的东西和偏好对不上"的分支——这是选流真正会出错的地方。
 * 偏好正好存在时选中它这种事不写断言(那是把实现抄一遍)。
 */
class StreamSelectionTest {

    @Test
    fun `偏好画质不存在时降到不超过它的最高档`() {
        val dash = dashOf(
            video(id = 120, codecid = VideoCodecId.AVC, url = "4k"),
            video(id = 64, codecid = VideoCodecId.AVC, url = "720p"),
            video(id = 16, codecid = VideoCodecId.AVC, url = "360p"),
        )

        val selected = selectStreams(dash, preferredQuality = 80)!!

        assertEquals("720p", selected.videoUrl)
        assertEquals(64, selected.qualityId)
    }

    @Test
    fun `偏好画质低于所有可用档时退到最高档而不是空`() {
        val dash = dashOf(video(id = 120, codecid = VideoCodecId.AVC, url = "4k"))

        val selected = selectStreams(dash, preferredQuality = 16)!!

        assertEquals("4k", selected.videoUrl)
    }

    @Test
    fun `偏好编码一个都没有时回退到该画质下的第一条流`() {
        // 偏好是默认的 [AVC, AV1],服务端这一档只给了 HEVC。
        val dash = dashOf(
            video(id = 80, codecid = VideoCodecId.HEVC, url = "hevc", codecs = "hev1.1.6.L120.90"),
        )

        val selected = selectStreams(dash, preferredQuality = 80)!!

        assertEquals("hevc", selected.videoUrl)
        assertEquals("HEVC", selected.codec)
    }

    @Test
    fun `三路音轨都为空时 audioUrl 为 null 而不是抛异常`() {
        val dash = DashDto(
            video = listOf(video(id = 80, codecid = VideoCodecId.AVC, url = "v")),
            audio = null,
            // dolby 节点存在但 audio 是空的,是服务端真实会出现的形状。
            dolby = DolbyDto(type = 1, audio = null),
            flac = null,
        )

        val selected = selectStreams(dash, preferredQuality = 80)!!

        assertEquals("v", selected.videoUrl)
        assertNull(selected.audioUrl)
    }

    private fun dashOf(vararg videos: DashStreamDto) = DashDto(
        video = videos.toList(),
        audio = listOf(audio(id = 30280, url = "a192k")),
    )

    private fun video(id: Int, codecid: Int, url: String, codecs: String = "avc1.640032") =
        DashStreamDto(id = id, baseUrlCamel = url, codecid = codecid, codecs = codecs)

    private fun audio(id: Int, url: String) = DashStreamDto(id = id, baseUrlCamel = url)
}
