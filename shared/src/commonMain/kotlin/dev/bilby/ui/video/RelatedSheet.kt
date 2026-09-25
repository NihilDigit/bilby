package dev.bilby.ui.video

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.bilby.agent.AnswerBlock
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.components.AgentTurnView
import dev.bilby.ui.components.KeepScrolledToBottom
import dev.bilby.ui.components.rememberBottomFollow
import dev.bilby.ui.theme.Spacing

/**
 * 找相关的结果面板,装在 modal sheet 里(见 VideoScreen)。
 *
 * **高度填满 sheet 给的上限**,不跟着内容长:sheet 一动就要求用户重新找视线落点。高度不变之后,
 * 内容增长只表现为面板内部滚动 —— 于是检索过程要自己跟着往下走(见 [KeepScrolledToBottom]),
 * 否则用户只看得见最初一两步。
 *
 * 答案落地时反过来滚回顶部:那时过程已经自动折叠,整段回答要从第一句读起。
 */
@Composable
fun RelatedSheet(
    related: RelatedState,
    onVideoClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val turn = related.turn
    val videoCount = turn.blocks.count { it is AnswerBlock.Video }

    val scrollState = rememberScrollState()
    val follow = rememberBottomFollow(scrollState)
    KeepScrolledToBottom(scrollState, follow, enabled = turn.running)
    LaunchedEffect(turn.running) {
        if (!turn.running && follow.following) scrollState.animateScrollTo(0)
    }

    Column(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        // 这一行不跟着滚:它是这块内容的名字,报着在找还是找到了几条。
        Text(
            text = when {
                turn.running -> stringResource(Res.string.related_title_running)
                turn.error != null -> stringResource(Res.string.related_title_error)
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
                // 展开后最后一块不要贴着屏幕下缘,手势区就在那里。
                .padding(top = Spacing.Cozy, bottom = Spacing.Loose),
        ) {
            // 与搜索页共用同一个部件:同一个助理的同一种输出,两处必须长得一样。
            AgentTurnView(turn = turn, onVideoClick = onVideoClick, onRetry = onRetry)
        }
    }
}
