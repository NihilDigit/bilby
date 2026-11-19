package dev.bilby.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSaveHelperTest {

    @Test
    fun `B 站图床剥离 @ 后缀并升级到 https`() {
        assertEquals(
            "https://i0.hdslb.com/bfs/new_dyn/abc123.jpg",
            ImageSaveHelper.originalImageUrl("http://i0.hdslb.com/bfs/new_dyn/abc123.jpg@1052w_!web-dynamic.avif"),
        )
        assertEquals(
            "https://i1.hdslb.com/bfs/archive/cover.png",
            ImageSaveHelper.originalImageUrl("//i1.hdslb.com/bfs/archive/cover.png@400w_400h.webp"),
        )
        assertEquals(
            "https://i0.biliimg.com/bfs/face/avatar.webp",
            ImageSaveHelper.originalImageUrl("https://i0.biliimg.com/bfs/face/avatar.webp@96w_96h.avif"),
        )
    }

    @Test
    fun `非 B 站图床保留 @ 后缀但规范化协议`() {
        assertEquals(
            "https://example.com/images/photo@2x.png",
            ImageSaveHelper.originalImageUrl("http://example.com/images/photo@2x.png"),
        )
        assertEquals(
            "https://cdn.example.org/a@b.jpg",
            ImageSaveHelper.originalImageUrl("//cdn.example.org/a@b.jpg"),
        )
    }

    @Test
    fun `文件名与扩展名推导规范化并兜底`() {
        assertEquals(
            "abc123.jpg" to "jpg",
            ImageSaveHelper.suggestFileNameAndExtension("https://i0.hdslb.com/bfs/new_dyn/abc123.jpeg"),
        )
        assertEquals(
            "cover.png" to "png",
            ImageSaveHelper.suggestFileNameAndExtension("https://i0.hdslb.com/bfs/archive/cover.png@400w.webp"),
        )
        assertEquals(
            "anim.gif" to "gif",
            ImageSaveHelper.suggestFileNameAndExtension("https://i0.hdslb.com/bfs/emote/anim.GIF"),
        )
        assertEquals(
            "photo.webp" to "webp",
            ImageSaveHelper.suggestFileNameAndExtension("https://i0.hdslb.com/bfs/new_dyn/photo.webp"),
        )
        assertEquals(
            "noext.jpg" to "jpg",
            ImageSaveHelper.suggestFileNameAndExtension("https://i0.hdslb.com/bfs/new_dyn/noext"),
        )
        assertEquals(
            "bilby_1700000000000.jpg" to "jpg",
            ImageSaveHelper.suggestFileNameAndExtension("https://i0.hdslb.com/", fallbackTimestampMillis = 1700000000000L),
        )
    }

    @Test
    fun `扩展名映射到正确的 MIME 类型`() {
        assertEquals("image/jpeg", ImageSaveHelper.mimeTypeForExtension("jpg"))
        assertEquals("image/jpeg", ImageSaveHelper.mimeTypeForExtension("JPEG"))
        assertEquals("image/png", ImageSaveHelper.mimeTypeForExtension("png"))
        assertEquals("image/webp", ImageSaveHelper.mimeTypeForExtension("webp"))
        assertEquals("image/gif", ImageSaveHelper.mimeTypeForExtension("gif"))
        assertEquals("image/jpeg", ImageSaveHelper.mimeTypeForExtension("unknown"))
    }
}
