package dev.bilby.ui.components

import coil3.BitmapImage
import coil3.decode.ImageSource
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.FileSystem
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ImageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkiaAnimatedImageDecoderTest {

    @Test
    fun `detects GIF and animated WebP headers and ignores static formats`() {
        val gif89a = Buffer().write(TWO_FRAME_GIF_BYTES)
        assertTrue(SkiaAnimatedImageDecoder.isAnimatedCandidate(gif89a))

        val gif87a = Buffer().writeUtf8("GIF87a").write(ByteArray(16))
        assertTrue(SkiaAnimatedImageDecoder.isAnimatedCandidate(gif87a))

        // RIFF (4) + size (4) + WEBP (4) + VP8X (4) + chunkSize (4) + flags (1)
        val animatedWebP = Buffer()
            .writeUtf8("RIFF")
            .writeIntLe(30)
            .writeUtf8("WEBPVP8X")
            .writeIntLe(10)
            .writeByte(0x02) // ANIMATION_FLAG
            .write(ByteArray(9))
        assertTrue(SkiaAnimatedImageDecoder.isAnimatedCandidate(animatedWebP))

        val staticWebP = Buffer()
            .writeUtf8("RIFF")
            .writeIntLe(30)
            .writeUtf8("WEBPVP8X")
            .writeIntLe(10)
            .writeByte(0x00) // 无 ANIMATION_FLAG
            .write(ByteArray(9))
        assertFalse(SkiaAnimatedImageDecoder.isAnimatedCandidate(staticWebP))

        val png = Buffer().write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        assertFalse(SkiaAnimatedImageDecoder.isAnimatedCandidate(png))
    }

    @Test
    fun `decodes multi-frame GIF as AnimatedSkiaImage and advances frames`() = runTest {
        val source = ImageSource(
            source = Buffer().write(TWO_FRAME_GIF_BYTES),
            fileSystem = FileSystem.SYSTEM,
        )
        val result = SkiaAnimatedImageDecoder(source).decode()
        assertTrue(result.image is AnimatedSkiaImage)
        val animated = result.image as AnimatedSkiaImage
        assertEquals(1, animated.width)
        assertEquals(1, animated.height)
        // 无 NETSCAPE 循环扩展 -> 有限播放一次,不可跨处共享
        assertFalse(animated.shareable)

        val target = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(1, 1)) }
        val canvas = Canvas(target)
        animated.draw(canvas)
        // 第 0 帧是纯红 (#FF0000)
        assertEquals(0xFFFF0000.toInt(), target.getColor(0, 0))

        // 推进到第 1 帧:纯蓝 (#0000FF)
        assertTrue(animated.stepFrameForTest())
        animated.draw(canvas)
        assertEquals(0xFF0000FF.toInt(), target.getColor(0, 0))

        // 有限循环(repetitionCount == 0)播完一轮后停止推进
        assertFalse(animated.stepFrameForTest())
    }

    @Test
    fun `infinite-loop GIF is shareable and handles RESTORE_PREVIOUS disposal`() = runTest {
        val source = ImageSource(
            source = Buffer().write(THREE_FRAME_RESTORE_PREVIOUS_LOOP_GIF_BYTES),
            fileSystem = FileSystem.SYSTEM,
        )
        val result = SkiaAnimatedImageDecoder(source).decode()
        assertTrue(result.image is AnimatedSkiaImage)
        val animated = result.image as AnimatedSkiaImage
        assertTrue(animated.shareable)

        val target = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(2, 1)) }
        val canvas = Canvas(target)

        // 第 0 帧(KEEP):整行 2x1 填红 (#FF0000)
        animated.draw(canvas)
        assertEquals(0xFFFF0000.toInt(), target.getColor(0, 0))
        assertEquals(0xFFFF0000.toInt(), target.getColor(1, 0))

        // 第 1 帧(RESTORE_PREVIOUS):左半格 (0,0) 改画蓝 (#0000FF),右半格保留第 0 帧的红
        assertTrue(animated.stepFrameForTest())
        animated.draw(canvas)
        assertEquals(0xFF0000FF.toInt(), target.getColor(0, 0))
        assertEquals(0xFFFF0000.toInt(), target.getColor(1, 0))

        // 第 2 帧(KEEP):只改画右半格 (1,0) 为蓝;左半格必须从第 1 帧前的状态恢复回红!
        assertTrue(animated.stepFrameForTest())
        animated.draw(canvas)
        assertEquals(0xFFFF0000.toInt(), target.getColor(0, 0))
        assertEquals(0xFF0000FF.toInt(), target.getColor(1, 0))

        // 无限循环:继续推进回到第 0 帧
        assertTrue(animated.stepFrameForTest())
        animated.draw(canvas)
        assertEquals(0xFFFF0000.toInt(), target.getColor(0, 0))
        assertEquals(0xFFFF0000.toInt(), target.getColor(1, 0))
    }

    @Test
    fun `decodes single-frame GIF as static BitmapImage`() = runTest {
        val source = ImageSource(
            source = Buffer().write(SINGLE_FRAME_GIF_BYTES),
            fileSystem = FileSystem.SYSTEM,
        )
        val result = SkiaAnimatedImageDecoder(source).decode()
        assertTrue(result.image is BitmapImage)
        assertEquals(1, result.image.width)
        assertEquals(1, result.image.height)
    }

    private companion object {
        // 1x1 两帧 GIF89a:第 0 帧 #FF0000,第 1 帧 #0000FF,每帧 80ms
        val TWO_FRAME_GIF_BYTES = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // GIF89a
            0x01, 0x00, 0x01, 0x00,             // 1x1
            0x80.toByte(), 0x00, 0x00,          // GCT flag (2 colors)
            0xFF.toByte(), 0x00, 0x00,          // Color 0: Red
            0x00, 0x00, 0xFF.toByte(),          // Color 1: Blue
            // Frame 0 GCE (delay = 8 = 80ms)
            0x21, 0xF9.toByte(), 0x04, 0x00, 0x08, 0x00, 0x00, 0x00,
            // Frame 0 Image Descriptor + LZW (color index 0)
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x44, 0x01, 0x00,
            // Frame 1 GCE (delay = 8 = 80ms)
            0x21, 0xF9.toByte(), 0x04, 0x00, 0x08, 0x00, 0x00, 0x00,
            // Frame 1 Image Descriptor + LZW (color index 1)
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x4C, 0x01, 0x00,
            0x3B,                               // Trailer
        )

        // 2x1 三帧无限循环 GIF89a,验证 NETSCAPE2.0 无限循环与 RESTORE_PREVIOUS 帧处置:
        // - Frame 0 (2x1 at 0,0, disposal = KEEP): 全红 [Red, Red]
        // - Frame 1 (1x1 at 0,0, disposal = RESTORE_PREVIOUS): 左格盖蓝 [Blue, Red]
        // - Frame 2 (1x1 at 1,0, disposal = KEEP): 右格盖蓝,左格恢复回 Frame 0 的红 [Red, Blue]
        val THREE_FRAME_RESTORE_PREVIOUS_LOOP_GIF_BYTES = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // GIF89a
            0x02, 0x00, 0x01, 0x00,             // 2x1
            0x80.toByte(), 0x00, 0x00,          // GCT flag (2 colors)
            0xFF.toByte(), 0x00, 0x00,          // Color 0: Red
            0x00, 0x00, 0xFF.toByte(),          // Color 1: Blue
            // NETSCAPE2.0 Application Extension (infinite loop: count = 0)
            0x21, 0xFF.toByte(), 0x0B,
            0x4E, 0x45, 0x54, 0x53, 0x43, 0x41, 0x50, 0x45, 0x32, 0x2E, 0x30,
            0x03, 0x01, 0x00, 0x00, 0x00,
            // Frame 0 GCE: disposal = 1 (KEEP = 0x04), delay = 8 (80ms)
            0x21, 0xF9.toByte(), 0x04, 0x04, 0x08, 0x00, 0x00, 0x00,
            // Frame 0 Image Descriptor: (0,0) 2x1, LZW two pixels of color index 0
            // Clear=4(100), 0(000), 0(000), EOI=5(101) -> bits LSB-first: 100(4), 000(0), 000(0), 101(5)
            // = 0b00_000_100 (0x04), 0b0000_101_0 (0x0A)
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x04, 0x0A, 0x00,
            // Frame 1 GCE: disposal = 3 (RESTORE_PREVIOUS = 0x0C), delay = 8 (80ms)
            0x21, 0xF9.toByte(), 0x04, 0x0C, 0x08, 0x00, 0x00, 0x00,
            // Frame 1 Image Descriptor: (0,0) 1x1, LZW single pixel of color index 1
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x4C, 0x01, 0x00,
            // Frame 2 GCE: disposal = 1 (KEEP = 0x04), delay = 8 (80ms)
            0x21, 0xF9.toByte(), 0x04, 0x04, 0x08, 0x00, 0x00, 0x00,
            // Frame 2 Image Descriptor: (1,0) 1x1, LZW single pixel of color index 1
            0x2C, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x4C, 0x01, 0x00,
            0x3B,
        )

        // 1x1 单帧 GIF89a
        val SINGLE_FRAME_GIF_BYTES = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
            0x01, 0x00, 0x01, 0x00,
            0x80.toByte(), 0x00, 0x00,
            0xFF.toByte(), 0x00, 0x00,
            0x00, 0x00, 0xFF.toByte(),
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x44, 0x01, 0x00,
            0x3B,
        )
    }
}
