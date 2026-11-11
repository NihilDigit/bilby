package dev.bilby.ui.components

import android.content.ClipData
import android.os.Build
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import dev.bilby.BiliLog

actual fun plainTextClip(label: String, text: String): ClipEntry = ClipEntry(ClipData.newPlainText(label, text))

actual suspend fun readClipboardText(clipboard: Clipboard): String? =
    runCatching {
        val data = clipboard.getClipEntry()?.clipData ?: return@runCatching null
        if (data.itemCount <= 0) null else data.getItemAt(0)?.text?.toString()
    }.onFailure { BiliLog.w("读取剪贴板失败", it) }.getOrNull()

actual val NeedsCopyNotice: Boolean
    get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
