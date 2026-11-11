package dev.bilby.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import dev.bilby.BiliLog
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
actual fun plainTextClip(label: String, text: String): ClipEntry = ClipEntry(StringSelection(text))

actual suspend fun readClipboardText(clipboard: Clipboard): String? =
    runCatching {
        val sys = Toolkit.getDefaultToolkit().systemClipboard
        if (!sys.isDataFlavorAvailable(DataFlavor.stringFlavor)) null
        else sys.getData(DataFlavor.stringFlavor) as? String
    }.onFailure { BiliLog.w("读取剪贴板失败", it) }.getOrNull()

actual val NeedsCopyNotice: Boolean get() = true
