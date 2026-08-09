package dev.bilby.ui.video

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.agent.AnswerBlock
import dev.bilby.ui.components.AgentTurnView
import dev.bilby.ui.components.KeepScrolledToBottom
import dev.bilby.ui.components.rememberBottomFollow
import dev.bilby.ui.theme.Spacing

/** 收起时露出的把手高度。够放一行标题与状态,不占画面。 */
val SheetHandleHeight = 96.dp

/** 还没量到投币行位置时的退路,只在首帧用得上。 */
val DefaultSheetHeight = 560.dp

/**
 * 找相关的结果面板。收起时只露出顶部一行(把手),展开是完整的回答。
 *
 * **高度由调用方定死**(锚在投币那一行的上沿,见 VideoScreen),而不是跟着内容长:sheet 一动
 * 就要求用户重新找视线落点,而它盖住的正是用户此刻还想看的画面与标题。高度固定之后,内容
 * 增长只表现为面板内部滚动 —— 于是检索过程要自己跟着往下走(见 [KeepScrolledToBottom]),
 * 否则用户只看得见最初一两步。
 *
 * 答案落地时反过来滚回顶部:那时过程已经自动折叠,整段回答要从第一句读起。
 */
@Composable
fun RelatedSheet(
    related: RelatedState,
    height: Dp,
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

    Column(modifier = modifier.fillMaxWidth().height(height)) {
        // 把手上一眼能认出它是什么、找到了几条 —— 翻了两屏评论之后回来时靠的就是这行。
        // 它不跟着滚:面板高度固定,这一行就是这块内容的名字,滚走了把手上什么都不剩。
        Text(
            text = when {
                turn.running -> stringResource(R.string.related_title_running)
                turn.error != null -> stringResource(R.string.related_title_error)
                videoCount > 0 -> stringResource(R.string.related_title_count, videoCount)
                else -> stringResource(R.string.related_title)
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
