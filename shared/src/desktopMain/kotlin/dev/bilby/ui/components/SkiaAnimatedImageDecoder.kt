package dev.bilby.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import coil3.Canvas
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import dev.bilby.BiliLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import org.jetbrains.skia.AnimationDisposalMode
import org.jetbrains.skia.AnimationFrameInfo
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image as SkiaImage

/**
 * 桌面端 (Skiko) 的 GIF 与动态 WebP 解码器。
 *
 * Coil 3 在非 Android 平台默认的 `SkiaImageDecoder` 走 `SkiaImage.makeFromEncoded`,只解第 0 帧静态位图;
 * 这里用 Skia 的 [Codec] 识别多帧动图并交由 [AnimatedSkiaImage] 按帧时长推进绘制。单帧图片仍退回静态
 * [Bitmap],非 GIF / 动态 WebP 在 [Factory.create] 阶段直接通过文件头放行给默认解码器,零额外开销。
 */
class SkiaAnimatedImageDecoder(
    private val source: ImageSource,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val data = Data.makeFromBytes(bytes)
        val codec = try {
            Codec.makeFromData(data)
        } catch (t: Throwable) {
            data.close()
            throw t
        }

        if (codec.frameCount <= 1) {
            codec.close()
            data.close()
            val bitmap = SkiaImage.makeFromEncoded(bytes).use { image ->
                Bitmap.makeFromImage(image).apply { setImmutable() }
            }
            return DecodeResult(
                image = bitmap.asImage(),
                isSampled = false,
            )
        }

        val animated = try {
            AnimatedSkiaImage(data = data, codec = codec)
        } catch (t: Throwable) {
            codec.close()
            data.close()
            throw t
        }
        return DecodeResult(
            image = animated,
            isSampled = false,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!isAnimatedCandidate(result.source.source())) return null
            return SkiaAnimatedImageDecoder(result.source)
        }
    }

    internal companion object {
        private val GIF_HEADER_87A = "GIF87a".encodeUtf8()
        private val GIF_HEADER_89A = "GIF89a".encodeUtf8()
        private val WEBP_RIFF_HEADER = "RIFF".encodeUtf8()
        private val WEBP_VP8X_HEADER = "WEBPVP8X".encodeUtf8()
        private const val WEBP_ANIMATION_FLAG = 0x02

        internal fun isAnimatedCandidate(source: BufferedSource): Boolean {
            return isGif(source) || isAnimatedWebP(source)
        }

        private fun isGif(source: BufferedSource): Boolean {
            return source.rangeEquals(0, GIF_HEADER_89A) ||
                source.rangeEquals(0, GIF_HEADER_87A)
        }

        private fun isAnimatedWebP(source: BufferedSource): Boolean {
            if (!source.request(21)) return false
            if (!source.rangeEquals(0, WEBP_RIFF_HEADER)) return false
            if (!source.rangeEquals(8, WEBP_VP8X_HEADER)) return false
            val peek = source.peek()
            peek.skip(20)
            val flags = peek.readByte().toInt() and 0xFF
            return (flags and WEBP_ANIMATION_FLAG) != 0
        }
    }
}

/**
 * 基于 Skia [Codec] 的多帧动图 [Image]。
 *
 * `coil-compose-core` 对非 `BitmapImage` 的 [Image] 统一包进 `ImagePainter`,在 `DrawScope.onDraw()`
 * 内直接调 [draw]。这里在 [draw] 中读取快照状态 [frameTick],由后台协程按每帧时长解码下一帧并更新
 * [frameTick],从而精确按 GIF 帧率触发 Compose 的绘制层重绘;当组件离开屏幕不再调用 [draw] 时,
 * 后台循环在静默超时后自动挂起,滚回屏幕时再无缝续播。
 */
internal class AnimatedSkiaImage(
    @Suppress("unused")
    private val data: Data,
    private val codec: Codec,
) : Image {

    private val lock = Any()
    private val frameCount: Int = codec.frameCount.coerceAtLeast(1)
    private val repetitionCount: Int = codec.repetitionCount
    private val framesInfo: Array<AnimationFrameInfo> = codec.framesInfo
    private val frameDurationsMs: LongArray = LongArray(frameCount) { index ->
        val raw = framesInfo.getOrNull(index)?.duration ?: DEFAULT_FRAME_DURATION_MS.toInt()
        // 浏览器与 Skia 惯例:<= 10ms 的帧时长视为未妥善填写的旧式 GIF,按 100ms 兜底。
        if (raw <= 10) DEFAULT_FRAME_DURATION_MS else raw.toLong()
    }

    override val width: Int = codec.width
    override val height: Int = codec.height
    override val size: Long = codec.imageInfo.computeMinByteSize().toLong().coerceAtLeast(1L)
    // 无限循环动图(repetitionCount < 0)可在内存缓存中安全共享,避免评论/弹幕同屏出现十几个相同表情时
    // 重复解码与开启多条协程;有限循环动图保持不共享,以免前一次展示耗尽循环次数后新挂载处直接停在末帧。
    override val shareable: Boolean = repetitionCount < 0

    private val workBitmap: Bitmap = Bitmap().apply {
        allocPixels(codec.imageInfo)
    }
    private var scratchBitmap: Bitmap? = null
    // 当前保留在 workBitmap 中、且 disposalMethod 不是 RESTORE_PREVIOUS 的帧序号,供后续帧增量合成。
    private var validPriorFrame: Int = -1
    private var decodedFrameIndex: Int = -1
    private var currentSkiaImage: SkiaImage? = null
    private var completedLoops: Int = 0
    private var frameTick by mutableIntStateOf(0)

    private var lastDrawNanoTime: Long = 0L
    private var tickerJob: Job? = null

    init {
        try {
            synchronized(lock) {
                decodeFrameLocked(0)
            }
        } catch (t: Throwable) {
            scratchBitmap?.close()
            workBitmap.close()
            throw t
        }
    }

    override fun draw(canvas: Canvas) {
        // 读取快照状态:订阅当前 DrawScope,只在帧切换时重绘。
        frameTick
        synchronized(lock) {
            lastDrawNanoTime = System.nanoTime()
            ensureTickerLocked()
            val imageToDraw = currentSkiaImage ?: return
            // 在同一把锁内完成 drawImage 录制:SkCanvas 会立即对底层 SkImage 增加 C++ 引用计数,
            // 后台切帧随后在同一把锁内 close 上一帧的 JVM 包装对象时不会与正在录制的指针竞态,
            // 也能在渲染回放结束后立即回收 native 像素缓冲而不必等 JVM GC。
            canvas.drawImage(imageToDraw, 0f, 0f)
        }
    }

    internal fun currentFrameDurationMs(): Long = synchronized(lock) {
        frameDurationsMs[decodedFrameIndex.coerceIn(0, frameCount - 1)]
    }

    internal fun stepFrameForTest(): Boolean = synchronized(lock) {
        advanceNextFrameLocked()
    }

    internal fun copyCurrentBitmap(): Bitmap = synchronized(lock) {
        val current = currentSkiaImage
        if (current != null) {
            Bitmap.makeFromImage(current).apply { setImmutable() }
        } else {
            val copy = Bitmap()
            copy.allocPixels(workBitmap.imageInfo)
            copy.setImmutable()
            copy
        }
    }

    private fun ensureTickerLocked() {
        if (frameCount <= 1) return
        if (repetitionCount >= 0 && completedLoops > repetitionCount) return
        if (tickerJob?.isActive == true) return
        tickerJob = animationScope.launch {
            while (isActive) {
                val delayMs = synchronized(lock) {
                    frameDurationsMs[decodedFrameIndex.coerceIn(0, frameCount - 1)]
                }
                delay(delayMs)
                val advanced = synchronized(lock) {
                    val idleNs = System.nanoTime() - lastDrawNanoTime
                    if (idleNs > delayMs * 1_000_000L + IDLE_PAUSE_THRESHOLD_NS) {
                        tickerJob = null
                        false
                    } else {
                        val ok = runCatching { advanceNextFrameLocked() }
                            .onFailure { BiliLog.w("动图帧解码失败,停止动画推进", it) }
                            .getOrDefault(false)
                        if (!ok) {
                            tickerJob = null
                        }
                        ok
                    }
                }
                if (!advanced) break
                Snapshot.withMutableSnapshot {
                    frameTick++
                }
            }
        }
    }

    private fun advanceNextFrameLocked(): Boolean {
        val nextFrame = if (decodedFrameIndex + 1 < frameCount) {
            decodedFrameIndex + 1
        } else {
            completedLoops++
            if (repetitionCount >= 0 && completedLoops > repetitionCount) {
                return false
            }
            0
        }
        decodeFrameLocked(nextFrame)
        return true
    }

    private fun decodeFrameLocked(targetFrame: Int) {
        val info = framesInfo.getOrNull(targetFrame)
        val required = info?.requiredFrame ?: -1
        val isRestorePrevious = info?.disposalMethod == AnimationDisposalMode.RESTORE_PREVIOUS

        val nextImage = if (isRestorePrevious) {
            // SkCodec 不允许把 disposalMethod == RESTORE_PREVIOUS 的帧作为后续帧的 priorFrame。
            // 这里在独立临时位图上合成当前帧,保持 workBitmap 与 validPriorFrame 原封不动。
            val target = scratchBitmap ?: Bitmap().apply {
                allocPixels(codec.imageInfo)
            }.also { scratchBitmap = it }
            if (targetFrame > 0 && required >= 0 && validPriorFrame in required until targetFrame) {
                val pixels = workBitmap.readPixels(workBitmap.imageInfo, workBitmap.rowBytes, 0, 0)
                if (pixels != null) {
                    target.installPixels(workBitmap.imageInfo, pixels, workBitmap.rowBytes)
                }
                codec.readPixels(target, targetFrame, validPriorFrame)
            } else {
                codec.readPixels(target, targetFrame)
            }
            target.notifyPixelsChanged()
            SkiaImage.makeFromBitmap(target)
        } else {
            if (targetFrame == 0 || required < 0 || validPriorFrame !in required until targetFrame) {
                codec.readPixels(workBitmap, targetFrame)
            } else {
                codec.readPixels(workBitmap, targetFrame, validPriorFrame)
            }
            validPriorFrame = targetFrame
            workBitmap.notifyPixelsChanged()
            SkiaImage.makeFromBitmap(workBitmap)
        }

        decodedFrameIndex = targetFrame
        val previous = currentSkiaImage
        currentSkiaImage = nextImage
        previous?.close()
    }

    private companion object {
        const val DEFAULT_FRAME_DURATION_MS = 100L
        const val IDLE_PAUSE_THRESHOLD_NS = 500_000_000L
        val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
