package dev.bilby.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Cursor

private val HorizontalResize = PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR))

actual fun Modifier.horizontalResizeCursor(): Modifier = pointerHoverIcon(HorizontalResize)
