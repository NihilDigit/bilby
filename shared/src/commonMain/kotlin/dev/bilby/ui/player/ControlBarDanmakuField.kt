package dev.bilby.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.bilby.resources.Res
import dev.bilby.resources.action_close
import dev.bilby.resources.action_send
import dev.bilby.resources.danmaku_input_hint
import dev.bilby.stringResource
import dev.bilby.ui.components.LoadingSpinner
import dev.bilby.ui.components.ReleaseFocusWhenKeyboardHides
import dev.bilby.ui.components.trackEditableFocus
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/** 控制条能同时容纳弹幕输入框和设置按钮的最小宽度。 */
internal val InlineDanmakuBarMinWidth = 720.dp

/** 控制条里发弹幕要的全部状态,见 [ControlBarDanmakuField]。 */
class ControlBarDanmaku(
    val draft: String,
    val onDraftChange: (String) -> Unit,
    val maxLength: Int,
    val sending: Boolean,
    val error: String?,
    val onSend: () -> Unit,
    /** 开始写与写完(拿到、放掉焦点)。点播借它取弹幕时间点、开关输入状态,直播用不着。 */
    val onComposingChange: (Boolean) -> Unit = {},
)

/**
 * 宽排法控制条里常驻的弹幕输入框,点播与直播共用。弹幕写在画面上,输入口就放在画面上。
 *
 * 调用方经 [onFocusChange] 知道开始写与写完:点播同窄屏的弹幕胶囊,在开始写的那一刻取弹幕
 * 时间点。控制条在它有焦点时不自动收起,也由调用方接住。
 *
 * **发出去之后放掉焦点。** 草稿被清空就是发出去了(失败时调用方会留着草稿),这时退出输入;
 * 留着焦点的话,下一句会沿用上一句的时间点,控制条也一直不收起。
 *
 * 草稿、发送状态和字数上限都归调用方:点播的上限是 100,直播是 20。
 *
 * @param error 上一次发送失败的原因。草稿留在框里,框尾一个警示图标,悬停读原因,改一个字再发就是重试。
 * @param autoFocus 一出现就要焦点。窄一点的控制条里它是点了「发弹幕」才展开的,那时人就是要写。
 */
@Composable
internal fun ControlBarDanmakuField(
    draft: String,
    onDraftChange: (String) -> Unit,
    maxLength: Int,
    sending: Boolean,
    error: String?,
    onSend: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    focusRequestNonce: Int = 0,
) {
    val hint = stringResource(Res.string.danmaku_input_hint)
    val canSend = draft.isNotBlank() && !sending
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var wasFocused by remember { mutableStateOf(false) }
    var sendPending by remember { mutableStateOf(false) }

    if (autoFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(focusRequestNonce) {
        if (focusRequestNonce > 0) {
            runCatching { focusRequester.requestFocus() }
            keyboard?.show()
        }
    }
    ReleaseFocusWhenKeyboardHides(focused)

    val submit: () -> Unit = {
        if (canSend) {
            sendPending = true
            onSend()
        }
    }
    LaunchedEffect(sendPending, sending, draft, error) {
        if (!sendPending) return@LaunchedEffect
        if (!sending && draft.isEmpty()) {
            sendPending = false
            keyboard?.hide()
            focusManager.clearFocus()
        } else if (!sending && error != null) {
            sendPending = false
        }
    }

    // 外层撑到触摸下限,画出来的胶囊与旁边的 chip 同高。
    Box(modifier = modifier.sizeIn(minHeight = Dimens.MinTouchTarget), contentAlignment = Alignment.Center) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(ControlChipHeight)
                .clip(CircleShape)
                .background(mediaControlContainer())
                .padding(start = Spacing.Cozy),
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { if (it.length <= maxLength) onDraftChange(it) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .trackEditableFocus()
                        // 刚进组合的那一帧焦点还没到,初始 false 不能当成"放掉焦点",否则点「发弹幕」
                        // 展开的那一帧就会立刻把 composing 重置回 false。
                        .onFocusChanged { state ->
                            focused = state.isFocused
                            if (state.isFocused) {
                                wasFocused = true
                                onFocusChange(true)
                            } else if (wasFocused) {
                                onFocusChange(false)
                            }
                        }
                        .semantics { contentDescription = hint },
                )
            }
            if (error != null && !sending) {
                PlayerTooltip(error) {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = error,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = Spacing.Hair).size(Dimens.IconInline),
                    )
                }
            }
            if (autoFocus && draft.isEmpty() && !sending) {
                IconButton(
                    onClick = {
                        keyboard?.hide()
                        focusManager.clearFocus()
                        onFocusChange(false)
                    },
                    modifier = Modifier.size(ControlChipHeight),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.action_close),
                        modifier = Modifier.size(Dimens.IconInline),
                    )
                }
            } else {
                IconButton(onClick = submit, enabled = canSend, modifier = Modifier.size(ControlChipHeight)) {
                    if (sending) {
                        LoadingSpinner()
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(Res.string.action_send),
                            modifier = Modifier.size(Dimens.IconInline),
                        )
                    }
                }
            }
        }
    }
}
