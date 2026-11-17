package dev.bilby.ui.video

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.components.AgentQuestionBubble
import dev.bilby.ui.components.AgentTurnView
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.KeepScrolledToBottom
import dev.bilby.ui.components.PillInputField
import dev.bilby.ui.components.rememberBottomFollow
import dev.bilby.ui.rememberCaptionSlot
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular
import dev.bilby.ui.windowDragArea

/** 找相关面板的动态状态标题,供常驻侧栏头与统一 [dev.bilby.ui.components.PaneSheet] 顶栏共用。 */
@Composable
internal fun relatedSheetTitle(related: RelatedState): String {
    val first = related.turns.firstOrNull()?.result
    val videoCount = first?.answer?.sources?.size ?: 0
    return when {
        first == null -> stringResource(Res.string.related_title)
        first.running -> stringResource(Res.string.related_title_running)
        first.error != null -> stringResource(Res.string.related_title_error)
        videoCount > 0 -> stringResource(Res.string.related_title_count, videoCount)
        else -> stringResource(Res.string.related_title)
    }
}

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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RelatedSheet(
    related: RelatedState,
    onVideoClick: (String) -> Unit,
    onAsk: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** 标题行右端的关闭。右栏常驻面板里给;模态 sheet 有统一标题行/把手,不重复画。 */
    onClose: (() -> Unit)? = null,
    /** 重新发起第一轮检索。空闲时可选提供。 */
    onReset: (() -> Unit)? = null,
    /** 右栏常驻模式下自己画带窗口控制按钮的标题行;模态 sheet 交由 [dev.bilby.ui.components.PaneSheet] 顶栏统一绘制。 */
    showHeader: Boolean = true,
    /** 追问输入栏已交由 [dev.bilby.ui.components.PaneSheet] 的 `footer` 承载时传 false,避免画两份。 */
    showFollowUpInBody: Boolean = true,
) {
    val first = related.turns.firstOrNull()?.result

    val scrollState = rememberScrollState()
    val follow = rememberBottomFollow(scrollState)
    KeepScrolledToBottom(scrollState, follow, enabled = related.running)
    // 第一轮落地时滚回顶部:过程已经自动折叠,整段回答要从第一句读起。追问的回答不滚:它接在
    // 刚才读到的地方下面,跟着走就是对的。
    LaunchedEffect(related.turns.size == 1 && !related.running) {
        if (related.turns.size == 1 && !related.running && follow.following) scrollState.animateScrollTo(0)
    }

    // 标题行和内容之间不画线。内容没滚动时两者本来连着读;滚上去之后标题行换高一档的底色,
    // 压在它下面走的内容才需要一条边界(M3 顶栏的 on-scroll 做法)。
    val scrolled by remember { derivedStateOf { scrollState.value > 0 } }
    val headerColor by animateColorAsState(
        targetValue = if (scrolled) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "related-header",
    )

    Column(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        if (showHeader) {
            // 这一行不跟着滚:它是这块内容的名字,报着第一轮在找还是找到了几条。右栏里右端是关闭,
            // 位置同侧栏(SidePanelLayout)的关闭:右栏里的找相关常驻,点画面不收起,关它只走这里。
            val captionSlot = if (onClose != null) rememberCaptionSlot() else null
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .then(captionSlot?.modifier ?: Modifier)
                    .fillMaxWidth()
                    .background(headerColor)
                    .heightIn(min = Dimens.MinTouchTarget + Spacing.Tight)
                    .padding(start = Spacing.Comfortable, end = if (onClose != null) Spacing.Hair else Spacing.Comfortable),
            ) {
                BoxTitleText(
                    text = relatedSheetTitle(related),
                    dragAtTop = captionSlot?.atTop == true,
                    modifier = Modifier.weight(1f),
                )
                if (onReset != null && !related.running) {
                    val refreshLabel = stringResource(Res.string.action_refresh)
                    BilbyTooltip(text = refreshLabel) {
                        IconButton(onClick = onReset) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = refreshLabel,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                onClose?.let { close ->
                    val closeLabel = stringResource(Res.string.action_close)
                    BilbyTooltip(text = closeLabel, shortcut = "Esc") {
                        IconButton(onClick = close) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = closeLabel,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                captionSlot?.buttons?.invoke()
            }
        }

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
        if (showFollowUpInBody && first != null && !first.running) {
            RelatedFollowUpBar(
                running = related.running,
                onAsk = onAsk,
                modifier = Modifier.imePadding().padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoxTitleText(
    text: String,
    dragAtTop: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = Dimens.MinTouchTarget)
            .windowDragArea(dragAtTop),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMediumEmphasized.tabular,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 追问的输入栏,贴在面板底部(优先交由 [dev.bilby.ui.components.PaneSheet] 的粘性 `footer` 承载)。
 * 上一轮还在跑时不能发,不排队:同一段会话里两个循环并行会拼出一段谁也没说过的对话。
 */
@Composable
internal fun RelatedFollowUpBar(
    running: Boolean,
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val send = {
        if (text.isNotBlank() && !running) {
            onAsk(text)
            text = ""
            focusManager.clearFocus()
        }
    }
    // 和私信、搜索页助理同一个胶囊。在跑的时候发送键那一格是转圈:这一轮还没答完。
    PillInputField(
        value = text,
        onValueChange = { text = it },
        placeholder = stringResource(Res.string.related_follow_up),
        canSend = text.isNotBlank() && !running,
        sending = running,
        onSend = send,
        modifier = modifier.fillMaxWidth(),
    )
}
