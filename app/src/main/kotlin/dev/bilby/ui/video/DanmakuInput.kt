package dev.bilby.ui.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/**
 * 发弹幕的输入层。**贴着键盘的一条输入栏,不是 sheet**:要的就是这一条,而
 * `ModalBottomSheet` 会把 window insets 一并接管,IME 的表现随 material3 的 alpha 版本变。
 * 评论区那条输入栏(`CommentInputBar`)已经用 [imePadding] 走通过同一件事。
 *
 * **躲让是 `imePadding().navigationBarsPadding()` 这个顺序**,不是两者相加:前者消费掉
 * IME 那份 inset,后者拿到的手势条高度已经被扣掉了。反过来写就是键盘和输入栏之间空一条
 * 手势条的高度。
 *
 * 上面那层不压暗。人此刻要看的正是画面上这一刻的内容(弹幕是发给这一帧的),盖一层遮罩
 * 等于把参照物拿走;它只负责接住点击,点在别处就是关掉。
 *
 * @param progressMillis 打开面板那一刻的播放进度。由调用方在打开时取值并一直传同一个数,
 *   不在这里读播放器——这条弹幕属于人按下"发弹幕"时看到的那一帧。
 */
@Composable
fun DanmakuInputLayer(
    text: String,
    onTextChange: (String) -> Unit,
    state: DanmakuSend,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // 面板一出现就聚焦并把键盘叫出来。**键盘要显式 show 一次**:requestFocus 在多数情况下
    // 会带出键盘,但这一层是刚进组合的兄弟节点,焦点落定与窗口 inset 更新之间有一帧的窗口,
    // 真机上偶尔只聚焦不弹键盘。
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // 返回键归这一层。不拦的话它穿到导航层,整个播放页被弹掉——人只是想收起输入栏。
    BackHandler(onBack = onDismiss)

    Box(modifier = modifier.fillMaxSize()) {
        // 点空白处关闭。不要涟漪也不要指示:这是一块吞点击的区域,不是按钮。
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
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Column {
                // 失败原因摆在输入框上面,不做 toast:人正看着这个面板,而失败之后要做的事
                // (改一个字再发)也在这里。发送中不显示上一次的失败。
                (state as? DanmakuSend.Failed)?.let { failed ->
                    Text(
                        text = stringResource(R.string.danmaku_send_failed, failed.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(
                            start = Spacing.Comfortable,
                            end = Spacing.Comfortable,
                            top = Spacing.Tight,
                        ),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.Tight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                ) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { if (it.length <= MaxLength) onTextChange(it) },
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        placeholder = { Text(stringResource(R.string.danmaku_input_hint)) },
                        // 弹幕是一行字,不是段落:回车即发送,不换行。
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { if (canSend(text, state)) onSend() },
                        ),
                        textStyle = TextStyle(fontSize = MaterialTheme.typography.bodyLarge.fontSize),
                        shape = MaterialTheme.shapes.large,
                    )
                    FilledIconButton(onClick = onSend, enabled = canSend(text, state)) {
                        if (state is DanmakuSend.Sending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(Dimens.IconInline),
                                strokeWidth = 2.dp,
                            )
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

private fun canSend(text: String, state: DanmakuSend): Boolean =
    text.isNotBlank() && state !is DanmakuSend.Sending

/** 服务端对弹幕正文的长度上限(PiliPlus `danmaku.dart:12` 注明的 100 字符)。 */
private const val MaxLength = 100
