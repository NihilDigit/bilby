package dev.bilby.ui.comment

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import dev.bilby.R
import dev.bilby.ui.components.LoadingSpinner
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/**
 * 在楼中楼面板之上回复某一条。
 *
 * **它是浮层,不是面板底部多出来的一行。** 上一版是选好回复对象就把整个面板收掉,交给评论区
 * 底部那条常驻输入栏 —— 而人是在这一组回复里读到某条才想回它的,面板一关,他刚读的那几条和
 * 滚到的位置全没了,发完还得自己找回来。浮层让面板留在原地:底下那个 `LazyColumn` 不重组,
 * 位置和滚动偏移就还是原来那个。
 *
 * 形态照 `ui/video/DanmakuInput.kt` 的 `DanmakuInputLayer`,那一条已经在真机上验过:
 *
 * - **上面那层不压暗。** 人此刻要看的正是自己要回复的那条,盖一层遮罩等于把它拿走。
 *   这一层只负责接住点击,点在别处就是取消。
 * - **返回键归这一层。** 不拦的话它穿到 `ModalBottomSheet` 自己那个 handler,一按就把整个
 *   面板弹掉 —— 而人只是想收起输入。这一层组合得比 sheet 晚,`OnBackPressedDispatcher` 后进先出,
 *   所以拿得到。
 *
 * **躲让只写 `imePadding()`,没有 `navigationBarsPadding()`。** 这一点和 `DanmakuInputLayer`
 * 不同,因为容器不同:那一层直接铺在活动窗口上,手势条要自己躲;这一层长在 `ModalBottomSheet`
 * 的窗口里,而 sheet 的 `contentWindowInsets` 默认已经把导航栏那份消费掉了,再加一次就是键盘
 * 和输入框之间空一条手势条的高度。
 */
@Composable
fun ReplyInputLayer(
    /** 回复对象的名字。取不到就只显示通用提示 —— 那条回复可能已经被刷掉了。 */
    targetName: String?,
    text: String,
    onTextChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 一出现就聚焦并把键盘叫出来。**键盘要显式 show 一次**:requestFocus 多数情况下会带出
    // 键盘,但这一层是刚进组合的兄弟节点,焦点落定与窗口 inset 更新之间有一帧的空档,
    // 真机上偶尔只聚焦不弹键盘(DanmakuInputLayer 踩过)。
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    BackHandler(onBack = onDismiss)

    Box(modifier = modifier.fillMaxSize()) {
        // 点空白处取消。不要涟漪也不要指示:这是一块吞点击的区域,不是按钮。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .imePadding(),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.Comfortable, end = Spacing.Hair)
                        .heightIn(min = Dimens.MinTouchTarget),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (targetName != null) {
                            stringResource(R.string.comment_replying_to, targetName)
                        } else {
                            stringResource(R.string.comment_reply)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.Tight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = onTextChange,
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        placeholder = {
                            Text(
                                if (targetName != null) {
                                    stringResource(R.string.comment_input_hint_reply, targetName)
                                } else {
                                    stringResource(R.string.comment_input_hint)
                                },
                            )
                        },
                        maxLines = 4,
                        shape = MaterialTheme.shapes.large,
                    )
                    FilledIconButton(onClick = onSend, enabled = !sending && text.isNotBlank()) {
                        if (sending) {
                            LoadingSpinner()
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.action_send),
                            )
                        }
                    }
                }
            }
        }
    }
}
