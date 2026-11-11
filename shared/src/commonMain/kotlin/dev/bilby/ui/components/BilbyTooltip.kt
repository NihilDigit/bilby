package dev.bilby.ui.components

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.bilby.ui.LocalWindowResizing
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val HoverShowDelayMillis = 500L
private const val ShortcutAlpha = 0.72f
private val TooltipGap = 6.dp
private val TooltipEdgeMargin = 8.dp

/**
 * 跨平台纯图标按钮提示。
 *
 * - **触屏(`isTouchLike`)**:委托给 M3 [TooltipBox],长按弹出,且绝不附带键盘快捷键后缀。
 * - **桌面鼠标**:绕开 CMP 1.12.1 `BasicTooltipBox` 在 `doMeasureAndLayout` 同帧通过
 *   `UNDISPATCHED` 协程同步销毁 `Popup` `RootNodeOwner` 的崩溃缺陷 —— 显隐状态只允许在
 *   [LaunchedEffect] 的帧调度器中写入,指针按下/滚轮通过非快照的 [Channel] 异步通知收起。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilbyTooltip(
    text: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    anchorAbove: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (text.isBlank()) {
        content()
        return
    }
    if (LocalPointerSource.current.isTouchLike) {
        val anchor = if (anchorAbove) TooltipAnchorPosition.Above else TooltipAnchorPosition.Below
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(anchor),
            tooltip = { PlainTooltip { Text(text) } },
            state = rememberTooltipState(),
            modifier = modifier,
            content = content,
        )
        return
    }

    val hoverSource = remember { MutableInteractionSource() }
    val hovered by hoverSource.collectIsHoveredAsState()
    val resizing = LocalWindowResizing.current
    val dismissSignals = remember { Channel<Unit>(Channel.CONFLATED) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(hovered, resizing) {
        if (!hovered || resizing) {
            visible = false
            return@LaunchedEffect
        }
        while (dismissSignals.tryReceive().isSuccess) {
            // 清掉悬停前残留的旧点击信号
        }
        val showJob = launch {
            delay(HoverShowDelayMillis)
            visible = true
        }
        dismissSignals.receive()
        showJob.cancel()
        visible = false
    }

    val density = LocalDensity.current
    val positionProvider = remember(density, anchorAbove) {
        DesktopTooltipPositionProvider(density, anchorAbove)
    }

    Box(
        modifier = modifier
            .hoverable(hoverSource)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) {
                            dismissSignals.trySend(Unit)
                        }
                    }
                }
            },
    ) {
        content()
        if (visible) {
            Popup(
                popupPositionProvider = positionProvider,
                properties = PopupProperties(focusable = false),
            ) {
                val containerColor = MaterialTheme.colorScheme.inverseSurface
                val contentColor = MaterialTheme.colorScheme.inverseOnSurface
                Surface(
                    color = containerColor,
                    contentColor = contentColor,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    val label = if (shortcut.isNullOrBlank()) {
                        buildAnnotatedString { append(text) }
                    } else {
                        buildAnnotatedString {
                            append(text)
                            append(MetaSeparator)
                            withStyle(SpanStyle(color = contentColor.copy(alpha = ShortcutAlpha))) {
                                append(shortcut)
                            }
                        }
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
                    )
                }
            }
        }
    }
}

private class DesktopTooltipPositionProvider(
    density: Density,
    private val anchorAbove: Boolean,
) : PopupPositionProvider {
    private val gapPx = with(density) { TooltipGap.roundToPx() }
    private val marginPx = with(density) { TooltipEdgeMargin.roundToPx() }

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(marginPx, maxX)

        val aboveY = anchorBounds.top - gapPx - popupContentSize.height
        val belowY = anchorBounds.bottom + gapPx
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)

        val rawY = if (anchorAbove) {
            if (aboveY >= marginPx) aboveY else belowY
        } else {
            if (belowY <= maxY) belowY else aboveY
        }
        return IntOffset(x, rawY.coerceIn(marginPx, maxY))
    }
}
