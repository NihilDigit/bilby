package dev.bilby.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.bilby.BiliLog
import dev.bilby.api.BiliClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Color
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

data class DesktopToastEvent(
    val id: Long,
    val message: String,
)

/**
 * 桌面上的 [SystemActions]。
 *
 * 分享没有系统面板可拉起(Windows 的分享面板要走 WinRT),改成把"标题 链接"放进剪贴板,
 * 用户粘到哪里由他决定。记日历尚未实现,对应入口不出现。
 */
class DesktopSystemActions(
    private val languageStore: DesktopLanguageStore,
    private val biliClient: BiliClient? = null,
) : SystemActions {
    private val toastSeq = AtomicLong(0L)
    private val _toastState = MutableStateFlow<DesktopToastEvent?>(null)
    val toastState: StateFlow<DesktopToastEvent?> = _toastState.asStateFlow()

    override fun showToast(message: String) {
        if (message.isBlank()) return
        _toastState.value = DesktopToastEvent(
            id = toastSeq.incrementAndGet(),
            message = message,
        )
    }

    fun dismissToast(id: Long) {
        _toastState.update { current -> if (current?.id == id) null else current }
    }
    override fun shareVideo(bvid: String, title: String) = copy(title, "https://www.bilibili.com/video/$bvid")
    override fun shareLiveRoom(roomId: Long, title: String) = copy(title, "https://live.bilibili.com/$roomId")
    override fun shareSpace(mid: Long, name: String) = copy(name, "https://space.bilibili.com/$mid")
    override val shareCopiesLink: Boolean get() = true

    override fun openInBrowser(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
            .onFailure { BiliLog.w("打开浏览器失败", it) }
    }

    override val supportsCalendar: Boolean get() = false
    override fun addCalendarEvent(title: String, startEpochSeconds: Long, description: String) = Unit

    override val supportsLanguageSwitch: Boolean get() = true
    override fun currentLanguage(): AppLanguage = languageStore.language
    override fun applyLanguage(language: AppLanguage) = languageStore.set(language)

    override val supportsCopyImage: Boolean get() = biliClient != null
    override val supportsSaveImage: Boolean get() = biliClient != null

    override suspend fun copyImage(url: String): Boolean {
        val client = biliClient ?: return false
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ImageSaveHelper.downloadOriginalBytes(client, url)
                val rgbImage = SkiaImage.makeFromEncoded(bytes).use { skia ->
                    val awt = skia.toComposeImageBitmap().toAwtImage()
                    if (awt.type == BufferedImage.TYPE_INT_RGB) {
                        awt
                    } else {
                        // Windows AWT 剪贴板的 CF_DIB 序列化器在遇到带 alpha 通道的 BufferedImage 时会失败,
                        // 统一转为 24 位 TYPE_INT_RGB 以兼容系统画图、聊天软件与浏览器粘贴。
                        BufferedImage(awt.width, awt.height, BufferedImage.TYPE_INT_RGB).also { dst ->
                            val g = dst.createGraphics()
                            try {
                                g.drawImage(awt, 0, 0, Color.WHITE, null)
                            } finally {
                                g.dispose()
                            }
                        }
                    }
                }
                Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageTransferable(rgbImage), null)
                true
            }.onFailure { BiliLog.w("复制图片到剪贴板失败", it) }.getOrDefault(false)
        }
    }

    override suspend fun saveImage(url: String): Boolean? {
        val client = biliClient ?: return false
        val normalized = ImageSaveHelper.originalImageUrl(url)
        val (suggestedName, ext) = ImageSaveHelper.suggestFileNameAndExtension(normalized)
        val filterLabel = "${ext.uppercase()} (*.$ext)"
        val target = WindowsFileSaveDialog.pickSavePath(
            suggestedName = suggestedName,
            extension = ext,
            filterLabel = filterLabel,
        ) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ImageSaveHelper.downloadOriginalBytes(client, normalized)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                true
            }.onFailure { BiliLog.w("保存图片失败 file=${target.name}", it) }.getOrDefault(false)
        }
    }

    private fun copy(title: String, url: String) {
        val text = if (title.isBlank()) url else "$title $url"
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
            .onFailure { BiliLog.w("写剪贴板失败", it) }
    }
}

private class ImageTransferable(private val image: BufferedImage) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = DataFlavor.imageFlavor.equals(flavor)
    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return image
    }
}
