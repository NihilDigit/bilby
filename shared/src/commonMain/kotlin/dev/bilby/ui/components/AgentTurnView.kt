package dev.bilby.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.agent.AgentStep
import dev.bilby.agent.AgentTurnState
import dev.bilby.agent.StepKind
import dev.bilby.agent.TraceItem
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import org.jetbrains.compose.resources.StringResource
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/**
 * 助理一轮的完整界面:过程 + 答案 + 在跑/出错/没结果。
 *
 * 搜索页的一轮对话和播放页「找相关」显示的是同一个助理的同一种输出,**容器不同不构成两份
 * 实现的理由**:一个在对话流里,一个在 sheet 里,那是外面那层的事。分成两份的那一版里,
 * 播放页少了中间结果卡片和完成标记,而这件事从任何一份代码里都看不出来。
 *
 * 过程直播是信任的来源,也是等待体验本身(DESIGN 3.4):中间结果可点,助理翻到一半用户
 * 看中了可以直接点走。答案一出现过程自动折叠,但随时能点回来。答案本身见 [AgentAnswerView]。
 */
@Composable
fun AgentTurnView(
    turn: AgentTurnState,
    onVideoClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var processExpanded by remember { mutableStateOf(true) }
    val hasAnswer = turn.answer != null
    LaunchedEffect(hasAnswer) {
        if (hasAnswer) processExpanded = false
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (turn.steps.isNotEmpty()) {
            ProcessHeader(
                expanded = processExpanded,
                stepCount = turn.steps.size,
                onToggle = { processExpanded = !processExpanded },
            )
            // 答案一到就自动折叠,折叠是这一块自己发生的事,不是换页:沿竖轴展开收起,
            // 让"过程收到那一行标题里去了"能被看见。直接 if 掉的话几行过程会凭空消失,
            // 用户下一次点开也不知道点开的是刚才那块。
            AnimatedVisibility(
                visible = processExpanded,
                enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
                    fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
                    fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
            ) {
                Column {
                    turn.steps.forEach { step -> StepRow(step = step, onVideoClick = onVideoClick) }
                }
            }
        }

        turn.answer?.let { answer ->
            AgentAnswerView(
                answer = answer,
                onVideoClick = onVideoClick,
                modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
            )
        }

        when {
            // 出错这一格用行内样式,不用 FullScreenError:两个容器都不是"一屏",一个是对话流里
            // 的一轮,一个是半屏 sheet,居中占满只会把上下文顶开。
            turn.error != null -> Column(
                modifier = Modifier.padding(horizontal = Spacing.Comfortable),
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                Text(
                    text = turn.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
            }

            turn.running -> InlineProgress(
                text = stringResource(Res.string.agent_running),
                modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
            )

            !hasAnswer -> EmptyState(stringResource(Res.string.agent_no_result))
        }
    }
}

/** 用户问的那一句,靠右的气泡。搜索页的助理和找相关的追问共用。 */
@Composable
fun AgentQuestionBubble(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.large,
            // **上限按可用宽度的比例算,不写死 280dp。** 那个数是按 360dp 宽的手机定的,平板上
            // 一句长问句会在整屏宽度的中间断成好几行,右边留着一大片空;而窄屏上它比屏幕还宽,
            // 等于没有上限。留出的那两成是"这一侧是我说的话"这个形状本身 —— 气泡铺满整行就
            // 和下面助理的正文分不开了。
            modifier = Modifier.fillMaxWidth(BubbleWidthFraction).wrapContentWidth(Alignment.End),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
            )
        }
    }
}

/** 用户气泡最宽占多少。留出的两成让"谁在说话"从形状上就读得出来。 */
private const val BubbleWidthFraction = 0.8f

/**
 * 过程那一行。折叠时写一共几步:原先写最后一步的文字,答案出来之后那一步多半是一次热评,
 * 读不出这一轮查了多少。试过同时写"见过几个候选",那个数把每次搜索的整页结果都算进去,
 * 动辄三百多,说明不了什么。
 */
@Composable
private fun ProcessHeader(
    expanded: Boolean,
    stepCount: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        // 一行 labelLarge 加上下 12dp 是 44dp,差 4dp 够不到触摸下限。撑的是点击区,留白没动。
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Text(
            text = if (expanded) {
                stringResource(Res.string.agent_process)
            } else {
                stringResource(Res.string.agent_process_summary, stepCount)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = stringResource(
                if (expanded) Res.string.agent_process_collapse else Res.string.agent_process_expand,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepRow(step: AgentStep, onVideoClick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.Hair),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        // 行首是动作类型的图标,文字只写对象(见 StepKind);还在跑的那一步图标换成转圈。
        Row(
            modifier = Modifier.padding(horizontal = Spacing.Comfortable),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            if (step.finished) {
                Icon(
                    imageVector = step.kind.icon(),
                    contentDescription = stringResource(step.kind.labelRes()),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimens.IconInline),
                )
            } else {
                LoadingSpinner()
            }
            Text(
                text = step.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (step.items.isNotEmpty()) {
            // 中间结果可点:助理翻到一半用户看中了可以直接点走(DESIGN 3.4)。
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.Comfortable),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                items(step.items, key = { it.bvid }) { trace ->
                    TraceCard(item = trace, onClick = { onVideoClick(trace.bvid) })
                }
            }
        }
    }
}

/** 每类动作一个图标。选的都是全应用里同一件事已经在用的那个:放大镜是搜索,人像是 UP 主。 */
private fun StepKind.icon(): ImageVector = when (this) {
    StepKind.SearchVideos -> Icons.Outlined.Search
    StepKind.SearchUsers -> Icons.Outlined.PersonSearch
    StepKind.Up -> Icons.Outlined.Person
    StepKind.UpVideos -> Icons.Outlined.VideoLibrary
    StepKind.Video -> Icons.Outlined.PlayCircleOutline
    StepKind.Comments -> Icons.Outlined.ChatBubbleOutline
    StepKind.Collection -> Icons.AutoMirrored.Outlined.PlaylistPlay
    StepKind.Related -> Icons.Outlined.Explore
    StepKind.Other -> Icons.Outlined.MoreHoriz
}

/** 图标给读屏的名字:文字只有对象,动作只在图标上,读屏也得知道是哪一类。 */
private fun StepKind.labelRes(): StringResource = when (this) {
    StepKind.SearchVideos -> Res.string.agent_step_search_videos
    StepKind.SearchUsers -> Res.string.agent_step_search_users
    StepKind.Up -> Res.string.agent_step_up
    StepKind.UpVideos -> Res.string.agent_step_up_videos
    StepKind.Video -> Res.string.agent_step_video
    StepKind.Comments -> Res.string.agent_step_comments
    StepKind.Collection -> Res.string.agent_step_collection
    StepKind.Related -> Res.string.agent_step_related
    StepKind.Other -> Res.string.agent_process
}

@Composable
private fun TraceCard(item: TraceItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.width(Dimens.TraceCardWidth).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        ListCover(url = item.coverUrl, width = Dimens.TraceCardWidth)
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
