package dev.bilby.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.danmaku.DanmakuVote
import dev.bilby.danmaku.DanmakuVoteOption
import dev.bilby.resources.Res
import dev.bilby.resources.player_vote_close
import dev.bilby.resources.player_vote_count
import dev.bilby.resources.player_vote_tag
import dev.bilby.stringResource
import dev.bilby.ui.components.formatCount
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * 互动投票面板的会话态:记录当前分 P 内手动收起过的投票与刚投完后的驻留截止时刻。
 *
 * 提升到播放器壳层按 `cid` 持有,避免锁屏、进出小窗或临时开关弹幕导致组件重组出树后,
 * 用户已经点过关闭的投票再次弹出。
 */
@Stable
class DanmakuVoteUiState {
    internal val dismissedVoteIds = mutableStateMapOf<Long, Boolean>()
    internal val lingerUntilMillis = mutableStateMapOf<Long, Long>()
}

@Composable
fun rememberDanmakuVoteUiState(cid: Long): DanmakuVoteUiState =
    remember(cid) { DanmakuVoteUiState() }

/**
 * 视频内互动投票面板(`#VOTE#`,见 notes/danmaku.md §11)。
 *
 * 锚在画面右下角([Alignment.BottomEnd]),随控制条与字幕轨平滑抬升让位,不遮挡画面中央与字幕。
 * 未投票时展示紧凑可点选项;投票后(或打开视频时已投过)原地展开各项占比条与人数。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DanmakuVoteOverlay(
    votes: List<DanmakuVote>,
    positionMillis: Long,
    cid: Long,
    onVote: (voteId: Long, optionIdx: Int) -> Unit,
    uiState: DanmakuVoteUiState = rememberDanmakuVoteUiState(cid),
    modifier: Modifier = Modifier,
) {
    val activeVote = votes.lastOrNull { vote ->
        if (uiState.dismissedVoteIds[vote.voteId] == true) return@lastOrNull false
        val end = maxOf(vote.endMillis, uiState.lingerUntilMillis[vote.voteId] ?: 0L)
        positionMillis in vote.startMillis..end
    }

    // 退场动画期间保留最后一条非空投票,避免缩回途中内容突然变空。
    var cachedVote by remember(cid) { mutableStateOf<DanmakuVote?>(null) }
    if (activeVote != null) {
        cachedVote = activeVote
    }

    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntSize>()
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    AnimatedVisibility(
        visible = activeVote != null,
        enter = fadeIn(effectsSpec) + expandVertically(spatialSpec, expandFrom = Alignment.Bottom),
        exit = fadeOut(effectsSpec) + shrinkVertically(spatialSpec, shrinkTowards = Alignment.Bottom),
        modifier = modifier,
    ) {
        val vote = cachedVote ?: return@AnimatedVisibility
        DanmakuVoteCard(
            vote = vote,
            onSelectOption = { optionIdx ->
                // 临近窗口末尾点下投票时,再留一段结果展示时间,不让比例条刚画出来就立刻退场。
                uiState.lingerUntilMillis[vote.voteId] = positionMillis + VOTE_RESULT_LINGER_MILLIS
                onVote(vote.voteId, optionIdx)
            },
            onDismiss = { uiState.dismissedVoteIds[vote.voteId] = true },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DanmakuVoteCard(
    vote: DanmakuVote,
    onSelectOption: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val cardShape = MaterialTheme.shapes.medium
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        modifier = Modifier
            .widthIn(min = VoteCardMinWidth, max = VoteCardMaxWidth)
            .clip(cardShape)
            // 吞掉落在卡片空白处、标题栏或已投选项上的点按,防止穿透到底下 PlayerShell 触发暂停/显隐控件。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .background(FixedColors.ScrimOnMedia)
            .border(width = CardBorderWidth, color = CardBorderColor, shape = cardShape)
            .padding(top = Spacing.Hair, bottom = Spacing.Tight),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.Cozy, end = Spacing.Hair),
        ) {
            Text(
                text = stringResource(Res.string.player_vote_tag),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.88f))
                    .padding(horizontal = Spacing.Hair, vertical = VoteTagVerticalPad),
            )
            Spacer(Modifier.width(Spacing.Tight))
            Text(
                text = vote.question,
                style = MaterialTheme.typography.labelMedium,
                color = FixedColors.OnMedia,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (vote.hasVoted && vote.totalCount > 0) {
                Spacer(Modifier.width(Spacing.Hair))
                Text(
                    text = stringResource(Res.string.player_vote_count, formatCount(vote.totalCount.toLong())),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = FixedColors.OnMedia.copy(alpha = 0.62f),
                    maxLines = 1,
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(VoteCloseHitSize)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.player_vote_close),
                    tint = FixedColors.OnMedia.copy(alpha = 0.68f),
                    modifier = Modifier.size(VoteCloseIconSize),
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Cozy),
        ) {
            val total = vote.totalCount.coerceAtLeast(vote.options.sumOf { it.count }).coerceAtLeast(1)
            vote.options.forEach { option ->
                DanmakuVoteOptionRow(
                    option = option,
                    hasVoted = vote.hasVoted,
                    selected = vote.myVote == option.idx,
                    fraction = if (vote.hasVoted) (option.count.toFloat() / total).coerceIn(0f, 1f) else 0f,
                    onClick = { if (!vote.hasVoted) onSelectOption(option.idx) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DanmakuVoteOptionRow(
    option: DanmakuVoteOption,
    hasVoted: Boolean,
    selected: Boolean,
    fraction: Float,
    onClick: () -> Unit,
) {
    val animatedFraction by animateFloatAsState(
        targetValue = if (hasVoted) fraction.coerceIn(0f, 1f) else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "vote-option-bar",
    )
    val clampedFraction = animatedFraction.coerceIn(0f, 1f)
    val barColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.52f)
    } else {
        Color.White.copy(alpha = 0.18f)
    }
    val trackColor = if (hasVoted) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.13f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = VoteOptionMinHeight)
            .clip(MaterialTheme.shapes.small)
            .background(trackColor)
            .then(
                if (!hasVoted) Modifier.clickable(onClick = onClick) else Modifier,
            ),
    ) {
        if (hasVoted && clampedFraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(clampedFraction)
                    .background(barColor),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth()
                .padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = FixedColors.OnMedia,
                    modifier = Modifier.size(VoteCheckIconSize),
                )
                Spacer(Modifier.width(Spacing.Hair))
            }
            Text(
                text = option.desc,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = FixedColors.OnMedia.copy(alpha = if (!hasVoted || selected) 0.96f else 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hasVoted) {
                Spacer(Modifier.width(Spacing.Tight))
                val percent = (fraction * 100).roundToInt()
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = FixedColors.OnMedia.copy(alpha = if (selected) 0.96f else 0.72f),
                    maxLines = 1,
                )
            }
        }
    }
}

private val VoteCardMinWidth = 192.dp
private val VoteCardMaxWidth = 240.dp
private val VoteOptionMinHeight = 34.dp
private val VoteCloseHitSize = 28.dp
private val VoteCloseIconSize = 16.dp
private val VoteCheckIconSize = 14.dp
private val VoteTagVerticalPad = 1.dp
private val CardBorderWidth = 0.5.dp
private val CardBorderColor = Color.White.copy(alpha = 0.10f)
private const val VOTE_RESULT_LINGER_MILLIS = 3_500L
