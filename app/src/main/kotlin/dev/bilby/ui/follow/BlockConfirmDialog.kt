package dev.bilby.ui.follow

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.bilby.R

/**
 * 拉黑二次确认。**关注列表、空间页、播放页共用这一份**,与 [dev.bilby.ui.components.FollowButton]
 * 里那个取关确认是同一条理由:三处各写一份,判据一改就会出现三种说法。
 *
 * **这里写出了动作的副作用,是 CLAUDE.md「确认框陈述动作即止」的一处例外。** 那条规矩的理由
 * 是不要把读者已经知道的事再讲一遍(取关框只有「取消关注」四个字就是这么来的),而拉黑连带
 * 做掉的三件事——解除关注、取消合集订阅、对方从此不能与你互动或看你的空间——是服务端替你做的,
 * 按钮上那两个字里读不出来。
 *
 * @param name 被拉黑的人。写进标题而不是正文:确认框最先被读到的是标题,而"拉黑谁"是这里
 *   唯一有可能点错的东西。
 */
@Composable
fun BlockConfirmDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blacklist_block_confirm_title, name)) },
        text = { Text(stringResource(R.string.blacklist_block_confirm_message)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(stringResource(R.string.blacklist_block)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
