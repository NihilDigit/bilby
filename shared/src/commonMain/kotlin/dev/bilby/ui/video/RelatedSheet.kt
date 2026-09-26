package dev.bilby.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.components.AgentQuestionBubble
import dev.bilby.ui.components.AgentTurnView
import dev.bilby.ui.components.KeepScrolledToBottom
import dev.bilby.ui.components.SearchField
import dev.bilby.ui.components.rememberBottomFollow
import dev.bilby.ui.theme.Spacing

/**
 * 找相关的面板,装在 [dev.bilby.ui.components.PaneSheet] 里(宽屏在右栏,窄屏是 sheet,见 VideoScreen)。
 *
 * 点「找相关」就跑第一轮;结果出来之后底部可以接着追问(「评论区怎么评价」「有没有更入门的」)。
 * 这一段会话以这条视频为上下文,离开这一页就丢掉,见 [VideoViewModel.askRelated]。
 *
 * **高度填满面板给的上限**,不跟着内容长:面板一动就要求用户重新找视线落点。高度不变之后,
 * 内容增长只表现为面板内部滚动 —— 于是检索过程要自己跟着往下走(见 [KeepScrolledToBottom]),
 * 否则用户只看得见最初一两步。
 */
@Composable
fun RelatedSheet(
    related: RelatedState,
    onVideoClick: (String) -> Unit,
    onAsk: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val first = related.turns.firstOrNull()?.result
    val videoCount = first?.answer?.sources?.size ?: 0

    val scrollState = rememberScrollState()
    val follow = rememberBottomFollow(scrollState)
    KeepScrolledToBottom(scrollState, follow, enabled = related.running)
    // 第一轮落地时滚回顶部:过程已经自动折叠,整段回答要从第一句读起。追问的回答不滚:它接在
    // 刚才读到的地方下面,跟着走就是对的。
    LaunchedEffect(related.turns.size == 1 && !related.running) {
        if (related.turns.size == 1 && !related.running && follow.following) scrollState.animateScrollTo(0)
    }

    Column(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        // 这一行不跟着滚:它是这块内容的名字,报着第一轮在找还是找到了几条。
        Text(
            text = when {
                first == null -> stringResource(Res.string.related_title)
                first.running -> stringResource(Res.string.related_title_running)
                first.error != null -> stringResource(Res.string.related_title_error)
                videoCount > 0 -> stringResource(Res.string.related_title_count, videoCount)
                else -> stringResource(Res.string.related_title)
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        )
        HorizontalDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .nestedScroll(follow.connection)
                .verticalScroll(scrollState)
                .padding(top = Spacing.Cozy, bottom = Spacing.Loose),
            verticalArrangement = Arrangement.spacedBy(Spacing.Loose),
        ) {
            related.turns.forEach { turn ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Cozy)) {
                    turn.question?.let {
                        AgentQuestionBubble(text = it, modifier = Modifier.padding(horizontal = Spacing.Comfortable))
                    }
                    // 与搜索页共用同一个部件:同一个助理的同一种输出,两处必须长得一样。
                    AgentTurnView(turn = turn.result, onVideoClick = onVideoClick, onRetry = onRetry)
                }
            }
        }

        // 第一轮跑完才露出来:追问接着一个回答问,在那之前没有东西可追。
        if (first != null && !first.running) {
            FollowUpBar(running = related.running, onAsk = onAsk)
        }
    }
}

/**
 * 追问的输入栏,贴在面板底部。样式同搜索页助理的输入栏:surfaceContainer 底色,右边一个实心的
 * 发送键。上一轮还在跑时发送键禁用,不排队:同一段会话里两个循环并行会拼出一段谁也没说过的对话。
 */
@Composable
private fun FollowUpBar(running: Boolean, onAsk: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val send = {
        if (text.isNotBlank() && !running) {
            onAsk(text)
            text = ""
            focusManager.clearFocus()
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().imePadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier.padding(Spacing.Tight),
        ) {
            SearchField(
                value = text,
                onValueChange = { text = it },
                placeholder = stringResource(Res.string.related_follow_up),
                onSearch = send,
                releaseFocusWithKeyboard = true,
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(onClick = send, enabled = text.isNotBlank() && !running) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(Res.string.action_send))
            }
        }
    }
}
