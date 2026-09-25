package dev.bilby.ui.components

import android.content.ClipData
import android.os.Build
import androidx.compose.ui.platform.ClipEntry

actual fun plainTextClip(label: String, text: String): ClipEntry = ClipEntry(ClipData.newPlainText(label, text))

actual val NeedsCopyNotice: Boolean
    get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
