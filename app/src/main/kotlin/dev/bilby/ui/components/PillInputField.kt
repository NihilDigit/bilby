package dev.bilby.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.ui.theme.Spacing

/**
 * 填充式胶囊输入框,发送键装在里面。私信、直播间发弹幕、写评论的面板([ComposerPanel])
 * 共用这一份。
 *
 * **不用 `OutlinedTextField`。** 页面底部常驻一条输入栏时,一圈描边加 56dp 的表单字段比它上面
 * 的内容还重,外面再套一层底条,底部就横着两层框。填充胶囊和搜索框(SearchField)是同一套语言
 * (风格指南 §2.4b):聚焦只升一档容器色,不长描边。
 *
 * 框随字数长高到 [maxLines] 行,再多在框里滚;发送键贴底,多行时仍在拇指够得到的右下角。
 *
 * 草稿归调用方([value]/[onValueChange]):什么时候清空两处不一样,私信等发送成功才清,
 * 直播间按下就清。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PillInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    /** 此刻能不能发。为 false 时发送键收起,输入法的发送键也不响应。 */
    canSend: Boolean,
    sending: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = DefaultMaxLines,
    /** 起步就占几行。评论是一段话,起步四行(见 ComposerPanel);大于 1 时占位贴顶,不居中。 */
    minLines: Int = 1,
    /** 回车发送还是换行。多行的段落输入(评论)回车是换行,发送只走发送键。 */
    imeSend: Boolean = true,
    /** 挂在输入框本身上的修饰:焦点请求、读屏标签这类只对输入框有意义的东西。 */
    fieldModifier: Modifier = Modifier,
    /**
     * 胶囊左端的一格,和发送键同高(直播间的弹幕开关)。给了它,左侧就不再留文字的内边距,
     * 由这一格自己占位。
     */
    leading: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val fieldColor by animateColorAsState(
        targetValue = if (focused) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "pill-field",
    )
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinHeight)
            .background(fieldColor, MaterialTheme.shapes.largeIncreased)
            .padding(
                start = if (leading != null) Spacing.Hair else Spacing.Comfortable,
                end = Spacing.Hair,
                top = Spacing.Hair,
                bottom = Spacing.Hair,
            ),
    ) {
        leading?.invoke()
        Box(
            contentAlignment = if (minLines > 1) Alignment.TopStart else Alignment.CenterStart,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = InnerMinHeight)
                .padding(vertical = Spacing.Tight),
        ) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = maxLines == 1,
                minLines = minLines,
                maxLines = maxLines,
                keyboardOptions = KeyboardOptions(imeAction = if (imeSend) ImeAction.Send else ImeAction.Default),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                interactionSource = interactionSource,
                modifier = Modifier.fillMaxWidth().then(fieldModifier),
            )
        }
        // **没字时不画发送键,不是画一个灰的。** 一个按不动的键常驻在框里,占着 48dp 宽度,
        // 还让人以为要先做点别的才能发。有字了缩放着出来,发送中换成转圈。
        AnimatedVisibility(
            visible = canSend || sending,
            enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeIn(),
            exit = scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()) + fadeOut(),
        ) {
            FilledIconButton(
                onClick = onSend,
                enabled = canSend,
                shapes = IconButtonDefaults.shapes(),
            ) {
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

/** 胶囊的最低高度:单行字加上下各一截,与 48dp 的发送键加 4dp 边等高。 */
private val MinHeight = 56.dp

/** 文字那一格的最低高度,单行时和发送键中线对齐。 */
private val InnerMinHeight = 48.dp

private const val DefaultMaxLines = 5
