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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
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
    /** 这一次发送失败的原因,报在输入框上方一行。见 [SendErrorRow]。 */
    sendError: String?,
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
                SendErrorRow(error = sendError, sending = sending, onRetry = onSend)
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
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { if (!sending && text.isNotBlank()) onSend() },
                        ),
                        supportingText = draftCounter(text.length),
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

/**
 * 发送失败那一行:原因 + 一个重试。摆在输入框**上方**,不是 snackbar 也不是列表页脚 ——
 * 人此刻看着的是这条输入栏,而失败之后要做的两件事(改一个字再发、直接重试)都在这一带。
 * 形状照 `ui/video/DanmakuInput.kt` 那一条,它已经在真机上验过。
 *
 * 重试就是再发一次同一份草稿,所以 [onRetry] 和发送键是同一个 lambda。正在发的时候不画上
 * 一次的失败:那句话此刻说的不是眼前这件事。
 */
@Composable
internal fun SendErrorRow(error: String?, sending: Boolean, onRetry: () -> Unit) {
    if (error == null || sending) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.Comfortable, end = Spacing.Hair),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.comment_send_failed, error),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

/**
 * 输入框右下角的字数计数,**过了 [CounterFrom] 才出现**,而且不拦输入。
 *
 * 和弹幕那条计数器不是一件事:弹幕有 100 字的硬上限(PiliPlus 的 `danmaku.dart` 注明),
 * 超出的按键必须被拦住并且要有计数器解释为什么。评论这一侧 **PiliPlus 没有任何客户端长度
 * 限制**(`pages/video/reply_new/view.dart` 里没有 `maxLength`,也没有 LengthLimiting 的
 * formatter),所以这里不敢写一个上限去拦:写错了就是一条合法评论发不出去,而且只在长评论上
 * 才犯。1000 是站内 web 版编辑器的那个数,拿来当"快到头了"的刻度,真正的判决交给服务端 ——
 * 被拒之后草稿留在框里,原因在上面那一行,这正是这一轮修好的那条路。
 *
 * 返回 null 而不是返回一个空的 supportingText:那个槽位一存在就占掉一行高度。
 */
@Composable
internal fun draftCounter(length: Int): (@Composable () -> Unit)? {
    if (length < CounterFrom) return null
    val label = stringResource(R.string.input_length_counter, length, SoftLimit)
    return {
        Text(text = label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
    }
}

/** 站内 web 版评论编辑器的字数刻度。**不是本地上限**,理由见 [draftCounter]。 */
private const val SoftLimit = 1000

/** 到这个长度才把计数器画出来。写两句话的人不需要被提醒还剩多少。 */
private const val CounterFrom = 800
