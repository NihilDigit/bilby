package dev.bilby.ui.player

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.awt.Cursor

/**
 * 小窗四边四角的拖动区。系统边框去掉之后(见桌面入口的 WindowsPip),改大小靠这里。
 *
 * 看不见,只在指针移上去时换成对应的缩放光标。四个角画在四条边后面,重叠处归角。
 */
@Composable
internal actual fun BoxScope.PipResizeHandles(window: PipWindow) {
    PipEdge.entries.sortedBy { it.horizontal != 0 && it.vertical != 0 }.forEach { edge ->
        Box(
            modifier = Modifier
                .align(alignmentOf(edge))
                .then(extentOf(edge))
                .pointerHoverIcon(cursorOf(edge))
                .pointerInput(window, edge) {
                    detectDragGestures(onDragStart = { window.startResize(edge) }) { change, _ ->
                        change.consume()
                        window.resize()
                    }
                },
        )
    }
}

private fun alignmentOf(edge: PipEdge): Alignment = when (edge) {
    PipEdge.Left -> Alignment.CenterStart
    PipEdge.Right -> Alignment.CenterEnd
    PipEdge.Top -> Alignment.TopCenter
    PipEdge.Bottom -> Alignment.BottomCenter
    PipEdge.TopLeft -> Alignment.TopStart
    PipEdge.TopRight -> Alignment.TopEnd
    PipEdge.BottomLeft -> Alignment.BottomStart
    PipEdge.BottomRight -> Alignment.BottomEnd
}

private fun extentOf(edge: PipEdge): Modifier = when {
    edge.horizontal != 0 && edge.vertical != 0 -> Modifier.size(CornerSize)
    edge.horizontal != 0 -> Modifier.fillMaxHeight().width(EdgeThickness)
    else -> Modifier.fillMaxWidth().height(EdgeThickness)
}

private fun cursorOf(edge: PipEdge): PointerIcon = EdgeCursors.getValue(edge)

private val EdgeCursors: Map<PipEdge, PointerIcon> = PipEdge.entries.associateWith { edge ->
    PointerIcon(
        Cursor(
            when (edge) {
                PipEdge.Left -> Cursor.W_RESIZE_CURSOR
                PipEdge.Right -> Cursor.E_RESIZE_CURSOR
                PipEdge.Top -> Cursor.N_RESIZE_CURSOR
                PipEdge.Bottom -> Cursor.S_RESIZE_CURSOR
                PipEdge.TopLeft -> Cursor.NW_RESIZE_CURSOR
                PipEdge.TopRight -> Cursor.NE_RESIZE_CURSOR
                PipEdge.BottomLeft -> Cursor.SW_RESIZE_CURSOR
                PipEdge.BottomRight -> Cursor.SE_RESIZE_CURSOR
            },
        ),
    )
}

/** 与系统可拖边框差不多宽:再窄鼠标很难对准,再宽就吃掉画面上的点击。 */
private val EdgeThickness = 6.dp
private val CornerSize = 12.dp
