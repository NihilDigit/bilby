package dev.bilby.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
actual fun plainTextClip(label: String, text: String): ClipEntry = ClipEntry(StringSelection(text))

actual val NeedsCopyNotice: Boolean get() = true
