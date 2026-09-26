package dev.bilby.ui.comment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.components.PillInputField
import dev.bilby.ui.components.rememberKeyboardFollowingInset
import dev.bilby.ui.theme.Spacing

/**
 * 写评论时的字数计数,**过了 [CounterFrom] 才出现**,而且不拦输入。
 *
 * 和弹幕那条计数器不是一件事:弹幕有 100 字的硬上限(PiliPlus 的 `danmaku.dart` 注明),
 * 超出的按键必须被拦住并且要有计数器解释为什么。评论这一侧 **PiliPlus 没有任何客户端长度
 * 限制**(`pages/video/reply_new/view.dart` 里没有 `maxLength`,也没有 LengthLimiting 的
 * formatter),所以这里不敢写一个上限去拦:写错了就是一条合法评论发不出去,而且只在长评论上
 * 才犯。1000 是站内 web 版编辑器的那个数,拿来当"快到头了"的刻度,真正的判决交给服务端 ——
 * 被拒之后草稿留在框里,原因在发送键上方那一行。
 *
 * 返回 null 即不显示。
 */
@Composable
internal fun commentDraftCounter(length: Int): String? {
    if (length < CounterFrom) return null
    return stringResource(Res.string.input_length_counter, length, SoftLimit)
}

/** 站内 web 版评论编辑器的字数刻度。**不是本地上限**,理由见 [commentDraftCounter]。 */
private const val SoftLimit = 1000

/** 到这个长度才把计数器画出来。写两句话的人不需要被提醒还剩多少。 */
private const val CounterFrom = 800

/**
 * 评论列表底部常驻的输入栏。主列表、楼中楼面板、评论详情页三处共用,胶囊与私信、直播间同一个
 * ([PillInputField])。
 *
 * **键盘与导航栏取并集,在这里让。** 列表铺到屏幕底边,外层都不替它让;这一栏和列表在同一个
 * 窗口里,IME inset 随键盘动画逐帧走,栏就是被键盘托上来的。外面已经让过的(楼中楼的底部
 * sheet 自带一层)从并集里扣掉,不会垫两次。
 *
 * 平时一行,随字数往上长,[MaxLines] 行之后在框里滚。回车是换行,评论是一段话;发送只走发送键。
 *
 * @param replyingTo 正在回复的那个人的名字。null 即写给这一处的默认对象,不画那一行。
 * @param label 读屏念的"此刻写给谁"。占位只是画在框里的一段字,输入框本身没有标签。
 * @param error 这一次发送失败的原因,整句。报在输入框上方,草稿留在框里,旁边一个重试。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CommentInputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    replyingTo: String?,
    onCancelReply: () -> Unit,
    placeholder: String,
    label: String,
    sending: Boolean,
    error: String?,
    onSend: () -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    // 不直接 windowInsetsPadding:HyperOS 第二次弹键盘时动画目标虚高,照原样跟就是冲上去再退回,
    // 见 rememberKeyboardFollowingInset。
    val bottomInset = rememberKeyboardFollowingInset()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onConsumedWindowInsetsChanged { bottomInset.consumed = it }
            .padding(bottom = bottomInset.value)
            .padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        // 回复谁写在框外上方一行,不塞进占位:框里有草稿时占位就不见了,而那正是最容易忘了
        // 自己在回复谁的时候。✕ 回到这一处的默认对象,草稿按对象另存,不会丢。
        if (replyingTo != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.Tight),
            ) {
                Text(
                    text = stringResource(Res.string.comment_replying_to, replyingTo),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCancelReply) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.comment_reply_cancel),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (error != null && !sending) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.Tight),
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSend) { Text(stringResource(Res.string.action_retry)) }
            }
        }
        PillInputField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = placeholder,
            canSend = !sending && draft.isNotBlank(),
            sending = sending,
            onSend = onSend,
            maxLines = MaxLines,
            imeSend = false,
            fieldModifier = Modifier
                .focusRequester(focusRequester)
                .semantics { contentDescription = label },
        )
        commentDraftCounter(draft.length)?.let { counter ->
            Text(
                text = counter,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(end = Spacing.Comfortable),
            )
        }
    }
}

/** 长到这么多行就不再长,改在框里滚:常驻栏长过半屏,上面的评论就被它顶没了。 */
private const val MaxLines = 6
