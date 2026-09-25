package dev.bilby.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import dev.bilby.resources.*
import dev.bilby.stringResource
import kotlinx.coroutines.launch

/**
 * 自由复制:把一段正文摊在 `SelectionContainer` 里,想复制哪一截就选哪一截,系统的选择工具条
 * 负责复制、全选和分享。
 *
 * **不在列表里就地开选择区。** `SelectionContainer` 会接管长按,而评论区的正文上已经压着
 * 三样东西:整行点击(楼中楼预览层)、链接与 @ 的子节点点击、以及打开这个面板的长按 ——
 * 四者会互抢同一个手势。更硬的一条是选择范围跨过 `LazyColumn` 回收掉的行时会断,而评论区
 * 恰恰是一条一条往下回收的。PiliPlus 的解法也是弹一个带 `SelectionArea` 的对话框
 * (`common/widgets/context_menu/reply_menu_helper.dart` 的 `showReplyCopyDialog`)。
 *
 * **展示的是原文,不是渲染出来的样子。** 表情按服务端存的 `[doge]` 给,链接按正文里那串字
 * (`av170001`、`https://…`)而不是渲染出来的标题:粘出去的 `av170001` 对方能打开,
 * 粘出去一行视频标题什么都不是。调用方传 [dev.bilby.data.model.plainText] 的结果。
 *
 * 底部留一个「复制全文」,因为整条复制是最常见的那一次 —— 让它退化成"全选再复制"两步不划算。
 */
@Composable
fun SelectableTextDialog(
    text: String,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(Res.string.comment_copy_clip_label)
    val copiedNotice = stringResource(Res.string.comment_copied)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.text_select_title)) },
        text = {
            // 长评论要能滚。**高度设上限而不是让对话框自己长**:一条几百字的评论会把
            // 确认按钮顶出屏幕,而那两个按钮是这个面板仅有的出口。
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .heightIn(max = MaxBodyHeight)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(text = text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(plainTextClip(clipLabel, text))
                        if (NeedsCopyNotice) snackbar.showSnackbar(copiedNotice)
                    }
                    onDismiss()
                },
            ) { Text(stringResource(Res.string.text_select_copy_all)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

private val MaxBodyHeight = 320.dp
