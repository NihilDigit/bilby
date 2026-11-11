package dev.bilby.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.unit.dp
import dev.bilby.BiliLog
import dev.bilby.dropLinkHintMessage
import dev.bilby.ui.BilbyLink
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.SystemReducedMotion
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DropTargetDragEvent

/**
 * 从命令行参数或拖入的文本中提取可由 `BilbyRoot.incomingLink` 消费的站内链接。
 */
internal fun extractIncomingLink(raw: String): String? = BilbyLink.extractIncomingLink(raw)

/**
 * 窗口级链接拖放区:从浏览器地址栏、网页正文或聊天窗口拖入站内链接或 BV / av 号即可直接跳转。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun LinkDropTarget(
    onLinkDropped: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val currentOnLinkDropped by rememberUpdatedState(onLinkDropped)
    var hovering by remember { mutableStateOf(false) }
    val reducedMotion = SystemReducedMotion.reduced
    val fadeMs = if (reducedMotion) 0 else 120

    val target = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                hovering = true
            }

            override fun onExited(event: DragAndDropEvent) {
                hovering = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                hovering = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                hovering = false
                val text = readDroppedText(event) ?: return false
                val link = extractIncomingLink(text) ?: return false
                currentOnLinkDropped(link)
                return true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    val dragEvent = event.nativeEvent as? DropTargetDragEvent
                    val hasTextFlavor = dragEvent?.isDataFlavorSupported(DataFlavor.stringFlavor)
                        ?: runCatching { event.dragData() is DragData.Text }.getOrDefault(false)
                    if (!hasTextFlavor) {
                        false
                    } else {
                        runCatching {
                            val preview = (event.dragData() as? DragData.Text)?.readText()
                            preview == null || extractIncomingLink(preview) != null
                        }.getOrDefault(true)
                    }
                },
                target = target,
            ),
    ) {
        content()
        AnimatedVisibility(
            visible = hovering,
            enter = fadeIn(tween(fadeMs)),
            exit = fadeOut(tween(fadeMs)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.86f))
                    .padding(Spacing.Comfortable)
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.extraLarge,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = Spacing.Loose, vertical = Spacing.Comfortable),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                    ) {
                        Icon(Icons.Outlined.Link, contentDescription = null)
                        Text(
                            text = dropLinkHintMessage(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun readDroppedText(event: DragAndDropEvent): String? = runCatching {
    (event.dragData() as? DragData.Text)?.readText()
        ?: (event.awtTransferable.takeIf { it.isDataFlavorSupported(DataFlavor.stringFlavor) }
            ?.getTransferData(DataFlavor.stringFlavor) as? String)
}.onFailure { BiliLog.w("读取拖放文本失败", it) }.getOrNull()
