package dev.bilby.desktop.screenshot

import androidx.compose.runtime.tooling.ComposeToolingApi
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ClipMode
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Surface
import org.jetbrains.skiko.SkiaLayer
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.io.File
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

internal object ShotLogger {
    private val logFile: File? = System.getenv("BILBY_SHOT_LOG")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(::File)

    @Synchronized
    fun info(message: String) {
        println(message)
        logFile?.let { file ->
            file.parentFile?.mkdirs()
            file.appendText(message + System.lineSeparator(), Charsets.UTF_8)
        }
    }

    @Synchronized
    fun error(message: String, cause: Throwable? = null) {
        System.err.println(message)
        cause?.printStackTrace(System.err)
        logFile?.let { file ->
            file.parentFile?.mkdirs()
            file.appendText("ERROR: $message" + System.lineSeparator(), Charsets.UTF_8)
            if (cause != null) {
                file.appendText(cause.stackTraceToString() + System.lineSeparator(), Charsets.UTF_8)
            }
        }
    }
}

internal fun findSkiaLayer(container: Container): SkiaLayer? {
    for (child in container.components) {
        if (child is SkiaLayer) return child
        if (child is Container) findSkiaLayer(child)?.let { return it }
    }
    return null
}

internal suspend fun <T> onEdt(block: () -> T): T =
    if (SwingUtilities.isEventDispatchThread()) {
        block()
    } else {
        suspendCoroutine { cont ->
            SwingUtilities.invokeLater {
                cont.resumeWith(runCatching(block))
            }
        }
    }

internal const val REDACTED_AVATAR_URL = "bilby-shot://redacted-avatar"

internal fun createRedactedAvatarPngBytes(): ByteArray {
    val size = 160
    val surface = Surface.makeRasterN32Premul(size, size)
    try {
        val canvas = surface.canvas
        canvas.clear(0xFF4A5C92.toInt())
        val accentPaint = Paint().apply {
            isAntiAlias = true
            color = 0xFF6B7EB8.toInt()
        }
        canvas.drawCircle(size * 0.5f, size * 0.5f, size * 0.44f, accentPaint)
        val fgPaint = Paint().apply {
            isAntiAlias = true
            color = 0xFFF2F4FA.toInt()
        }
        // Neutral stylized person silhouette (no text/identity dependency)
        canvas.drawCircle(size * 0.5f, size * 0.39f, size * 0.16f, fgPaint)
        canvas.drawRRect(
            RRect.makeXYWH(size * 0.25f, size * 0.60f, size * 0.50f, size * 0.30f, size * 0.16f),
            fgPaint,
        )
        return surface.makeImageSnapshot()
            .encodeToData(EncodedImageFormat.PNG)
            ?.bytes
            ?: error("Failed to encode redacted avatar PNG")
    } finally {
        surface.close()
    }
}

internal class RedactedAvatarFetcherFactory(
    private val avatarBytes: ByteArray,
) : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        if (data.scheme != "bilby-shot") return null
        return Fetcher {
            SourceFetchResult(
                source = ImageSource(
                    source = Buffer().write(avatarBytes),
                    fileSystem = FileSystem.SYSTEM,
                ),
                mimeType = "image/png",
                dataSource = DataSource.MEMORY,
            )
        }
    }
}

internal fun roundBitmapToPngBytes(bitmap: Bitmap, dark: Boolean, cornerRadiusPx: Float = 24f): ByteArray {
    val width = bitmap.width
    val height = bitmap.height
    val surface = Surface.makeRasterN32Premul(width, height)
    try {
        val canvas: Canvas = surface.canvas
        canvas.clear(0x00000000)
        val outerRRect = RRect.makeXYWH(0f, 0f, width.toFloat(), height.toFloat(), cornerRadiusPx)
        canvas.save()
        canvas.clipRRect(outerRRect, ClipMode.INTERSECT, true)
        Image.makeFromBitmap(bitmap).use { img ->
            canvas.drawImage(img, 0f, 0f)
        }
        canvas.restore()

        val borderPaint = Paint().apply {
            isAntiAlias = true
            mode = PaintMode.STROKE
            strokeWidth = 2f
            color = if (dark) 0x29FFFFFF else 0x1F000000
        }
        val borderRRect = RRect.makeXYWH(
            1f,
            1f,
            (width - 2).toFloat(),
            (height - 2).toFloat(),
            (cornerRadiusPx - 1f).coerceAtLeast(0f),
        )
        canvas.drawRRect(borderRRect, borderPaint)

        return surface.makeImageSnapshot()
            .encodeToData(EncodedImageFormat.PNG)
            ?.bytes
            ?: error("Failed to encode screenshot PNG")
    } finally {
        surface.close()
    }
}

internal suspend fun writeScreenshot(
    pngBytes: ByteArray,
    outDir: File,
    baseName: String,
    format: OutputFormat,
) {
    withContext(Dispatchers.IO) {
        outDir.mkdirs()
        val pngOut = File(outDir, "$baseName.png")
        val avifOut = File(outDir, "$baseName.avif")
        when (format) {
            OutputFormat.PNG -> {
                pngOut.writeBytes(pngBytes)
                ShotLogger.info("Saved ${pngOut.absolutePath} (${pngBytes.size / 1024} KB)")
            }
            OutputFormat.AVIF -> {
                val tempPng = Files.createTempFile("bilby-shot-", ".png").toFile()
                try {
                    tempPng.writeBytes(pngBytes)
                    encodeAvifViaFfmpeg(tempPng, avifOut)
                    ShotLogger.info("Saved ${avifOut.absolutePath} (${avifOut.length() / 1024} KB)")
                } finally {
                    tempPng.delete()
                }
            }
            OutputFormat.BOTH -> {
                pngOut.writeBytes(pngBytes)
                encodeAvifViaFfmpeg(pngOut, avifOut)
                ShotLogger.info(
                    "Saved ${pngOut.absolutePath} (${pngBytes.size / 1024} KB) and " +
                        "${avifOut.absolutePath} (${avifOut.length() / 1024} KB)",
                )
            }
        }
    }
}

private fun encodeAvifViaFfmpeg(inputPng: File, outputAvif: File) {
    val command = listOf(
        "ffmpeg",
        "-y",
        "-loglevel",
        "error",
        "-i",
        inputPng.absolutePath,
        "-filter_complex",
        "[0:v]split=2[rgb][a];" +
            "[rgb]format=yuv444p,setparams=colorspace=bt709:color_primaries=bt709:color_trc=bt709[v];" +
            "[a]alphaextract,format=gray,setparams=colorspace=bt709:color_primaries=bt709:color_trc=bt709[alpha]",
        "-map",
        "[v]",
        "-map",
        "[alpha]",
        "-c:v",
        "libaom-av1",
        "-still-picture",
        "1",
        "-crf",
        "20",
        "-b:v",
        "0",
        "-cpu-used",
        "4",
        outputAvif.absolutePath,
    )
    val process = ProcessBuilder(command)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    check(exit == 0) {
        "ffmpeg AVIF encoding failed (exit=$exit). Ensure ffmpeg with libaom-av1 is on PATH, or pass --format=png.\n$output"
    }
}

internal class SemanticsDriver(private val window: ComposeWindow) {

    @OptIn(ComposeToolingApi::class)
    suspend fun allNodes(): List<SemanticsNode> = onEdt {
        val result = ArrayList<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            result += node
            for (child in node.children) {
                walk(child)
            }
        }
        for (owner in window.semanticsOwners) {
            walk(owner.rootSemanticsNode)
        }
        result
    }

    suspend fun findFirst(predicate: (SemanticsNode) -> Boolean): SemanticsNode? =
        allNodes().firstOrNull(predicate)

    suspend fun hasNode(predicate: (SemanticsNode) -> Boolean): Boolean =
        findFirst(predicate) != null

    suspend fun waitUntil(
        timeoutMs: Long = 20_000L,
        pollMs: Long = 150L,
        description: String,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            delay(pollMs)
        }
        throw IllegalStateException("Timed out after ${timeoutMs}ms waiting for: $description")
    }

    suspend fun clickNode(
        description: String,
        timeoutMs: Long = 15_000L,
        predicate: (SemanticsNode) -> Boolean,
    ) {
        var target: SemanticsNode? = null
        waitUntil(timeoutMs = timeoutMs, description = "clickable node '$description'") {
            target = findFirst { node ->
                predicate(node) && node.config.getOrNull(SemanticsActions.OnClick) != null
            }
            target != null
        }
        val node = checkNotNull(target)
        onEdt {
            val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
                ?: error("Node '$description' disappeared or lost OnClick")
            action.invoke()
        }
    }

    suspend fun setTextAndSubmit(
        description: String,
        text: String,
        timeoutMs: Long = 15_000L,
        predicate: (SemanticsNode) -> Boolean,
    ) {
        var target: SemanticsNode? = null
        waitUntil(timeoutMs = timeoutMs, description = "editable node '$description'") {
            target = findFirst { node ->
                predicate(node) && node.config.getOrNull(SemanticsActions.SetText) != null
            }
            target != null
        }
        val node = checkNotNull(target)
        onEdt {
            val setText = node.config.getOrNull(SemanticsActions.SetText)?.action
                ?: error("Node '$description' lost SetText")
            setText.invoke(AnnotatedString(text))
        }
        delay(120L)
        val refreshed = findFirst { predicate(it) && it.config.getOrNull(SemanticsActions.OnImeAction) != null }
        val performed = if (refreshed != null) {
            onEdt {
                refreshed.config.getOrNull(SemanticsActions.OnImeAction)?.action?.invoke() == true
            }
        } else {
            false
        }
        if (!performed) {
            sendKey(KeyEvent.VK_ENTER, '\n')
        }
    }

    suspend fun sendKey(keyCode: Int, keyChar: Char = KeyEvent.CHAR_UNDEFINED, modifiers: Int = 0) {
        onEdt {
            val layer = findSkiaLayer(window)
            val target: Component = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                ?.takeIf { SwingUtilities.isDescendingFrom(it, window) }
                ?: layer
                ?: window
            layer?.requestFocusInWindow()
            val now = System.currentTimeMillis()
            target.dispatchEvent(
                KeyEvent(target, KeyEvent.KEY_PRESSED, now, modifiers, keyCode, keyChar),
            )
            if (keyChar != KeyEvent.CHAR_UNDEFINED) {
                target.dispatchEvent(
                    KeyEvent(target, KeyEvent.KEY_TYPED, now, modifiers, KeyEvent.VK_UNDEFINED, keyChar),
                )
            }
            target.dispatchEvent(
                KeyEvent(target, KeyEvent.KEY_RELEASED, now, modifiers, keyCode, keyChar),
            )
        }
    }

    companion object {
        fun SemanticsNode.texts(): List<String> =
            config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()

        fun SemanticsNode.descriptions(): List<String> =
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

        fun SemanticsNode.hasExactLabel(label: String): Boolean =
            label in descriptions() || label in texts()

        fun SemanticsNode.hasAnyLabel(vararg labels: String): Boolean =
            labels.any { hasExactLabel(it) }

        fun SemanticsNode.containsLabel(substring: String): Boolean =
            descriptions().any { substring in it } || texts().any { substring in it }

        fun SemanticsNode.containsAnyLabel(vararg substrings: String): Boolean =
            substrings.any { containsLabel(it) }

        fun SemanticsNode.isEditable(): Boolean =
            config.getOrNull(SemanticsProperties.EditableText) != null ||
                config.getOrNull(SemanticsActions.SetText) != null
    }
}
