package dev.bilby.ui.toview

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.bilby.resources.*
import dev.bilby.stringResource

/**
 * 清空前的确认。只说这一下做什么,不解释后果:移出稍后再看是什么意思,读者已经知道。
 *
 * 已看完那一种带条数(本地数得出来);已失效数不出来,标题就是全部。
 */
@Composable
fun ToViewClearDialog(
    kind: ToViewClear,
    finishedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (kind) {
                    ToViewClear.Finished -> stringResource(Res.string.toview_clear_finished_confirm, finishedCount)
                    ToViewClear.Invalid -> stringResource(Res.string.toview_clear_invalid_confirm)
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(Res.string.action_clear)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
