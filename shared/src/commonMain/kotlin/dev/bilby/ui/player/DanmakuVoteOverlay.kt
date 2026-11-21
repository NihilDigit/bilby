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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import dev.bilby.danmaku.DanmakuVote
import dev.bilby.danmaku.DanmakuVoteOption
import dev.bilby.resources.Res
import dev.bilby.resources.player_vote_close
import dev.bilby.resources.player_vote_count
import dev.bilby.resources.player_vote_tag
import dev.bilby.stringResource
import dev.bilby.ui.components.formatCount
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular
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
 * 判断当前播放器容器是否属于紧凑内嵌画幅（如竖屏手机 16:9 内嵌约 360–412dp × 202–232dp，
 * 或窄双栏左栏）：高 `< 300dp` 或宽 `< 480dp` 时切入紧凑卡片形态，避免盖住大半个画面。
 * 首帧尚未量出实测尺寸时回退到 [fallbackCompact]。
 */
internal fun isCompactVoteContainer(
    containerWidth: Dp,
    containerHeight: Dp,
    fallbackCompact: Boolean = false,
): Boolean {
    val hasWidth = containerWidth.isSpecified && containerWidth > 0.dp
    val hasHeight = containerHeight.isSpecified && containerHeight > 0.dp
    if (!hasWidth && !hasHeight) return fallbackCompact
    return (hasHeight && containerHeight < CompactVoteHeightThreshold) ||
        (hasWidth && containerWidth < CompactVoteWidthThreshold)
}

/**
 * 视频内互动投票面板(`#VOTE#`,见 notes/danmaku.md §11)。
 *
 * 锚在画面右下角([Alignment.BottomEnd]),随控制条与字幕轨平滑抬升让位,不遮挡画面中央与字幕。
 * 依据播放器容器实测尺寸([containerWidth] / [containerHeight])在紧凑内嵌卡片与全屏/桌面标准卡片间自适配;
 * 未投票时展示紧凑可点选项,投票后(或打开视频时已投过)原地展开各项占比条与人数。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DanmakuVoteOverlay(
    votes: List<DanmakuVote>,
    positionMillis: () -> Long,
    cid: Long,
    onVote: (voteId: Long, optionIdx: Int, positionMillis: Long) -> Unit,
    uiState: DanmakuVoteUiState = rememberDanmakuVoteUiState(cid),
    containerWidth: Dp = Dp.Unspecified,
    containerHeight: Dp = Dp.Unspecified,
    compactFallback: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val activeVote by remember(votes, uiState) {
        derivedStateOf {
            val pos = positionMillis()
            votes.lastOrNull { vote ->
                if (uiState.dismissedVoteIds[vote.voteId] == true) return@lastOrNull false
                val end = maxOf(vote.endMillis, uiState.lingerUntilMillis[vote.voteId] ?: 0L)
                pos in vote.startMillis..end
            }
        }
    }

    // 退场动画期间保留最后一条非空投票,避免缩回途中内容突然变空。
    var cachedVote by remember(cid) { mutableStateOf<DanmakuVote?>(null) }
    if (activeVote != null) {
        cachedVote = activeVote
    }

    val spatialSpec = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntSize>()
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val compact = isCompactVoteContainer(
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        fallbackCompact = compactFallback,
    )

    AnimatedVisibility(
        visible = activeVote != null,
        enter = fadeIn(effectsSpec) + expandVertically(spatialSpec, expandFrom = Alignment.Bottom),
        exit = fadeOut(effectsSpec) + shrinkVertically(spatialSpec, shrinkTowards = Alignment.Bottom),
        modifier = modifier,
    ) {
        val vote = cachedVote ?: return@AnimatedVisibility
        DanmakuVoteCard(
            vote = vote,
            compact = compact,
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            onSelectOption = { optionIdx ->
                // 临近窗口末尾点下投票时,再留一段结果展示时间,不让比例条刚画出来就立刻退场。
                val currentPos = positionMillis()
                uiState.lingerUntilMillis[vote.voteId] = currentPos + VOTE_RESULT_LINGER_MILLIS
                onVote(vote.voteId, optionIdx, currentPos)
            },
            onDismiss = { uiState.dismissedVoteIds[vote.voteId] = true },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DanmakuVoteCard(
    vote: DanmakuVote,
    compact: Boolean,
    containerWidth: Dp,
    containerHeight: Dp,
    onSelectOption: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val cardShape = if (compact) MaterialTheme.shapes.small else MaterialTheme.shapes.medium
    val maxCardWidth = if (compact) {
        if (containerWidth.isSpecified && containerWidth > 0.dp) {
            minOf(containerWidth * CompactVoteMaxWidthFraction, CompactVoteCardMaxWidth)
                .coerceAtLeast(CompactVoteCardHardMinWidth)
        } else {
            CompactVoteCardMaxWidth
        }
    } else {
        VoteCardMaxWidth
    }
    val minCardWidth = if (compact) {
        CompactVoteCardMinWidth.coerceAtMost(maxCardWidth)
    } else {
        VoteCardMinWidth.coerceAtMost(maxCardWidth)
    }
    val maxCardHeight = if (compact) {
        if (containerHeight.isSpecified && containerHeight > 0.dp) {
            (containerHeight * CompactVoteMaxHeightFraction).coerceAtLeast(CompactVoteMinHeightCap)
        } else {
            CompactVoteFallbackMaxHeight
        }
    } else {
        if (containerHeight.isSpecified && containerHeight > 0.dp) {
            (containerHeight * RegularVoteMaxHeightFraction).coerceAtLeast(RegularVoteMinHeightCap)
        } else {
            Dp.Unspecified
        }
    }
    val horizontalPad = if (compact) Spacing.Tight else Spacing.Cozy
    val bottomPad = if (compact) CompactCardBottomPad else Spacing.Tight
    val closeTargetSize = if (compact) CompactCloseTargetSize else Dimens.MinTouchTarget
    val closeIconSize = if (compact) CompactCloseIconSize else VoteCloseIconSize

    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        modifier = Modifier
            .widthIn(min = minCardWidth, max = maxCardWidth)
            .then(if (maxCardHeight.isSpecified) Modifier.heightIn(max = maxCardHeight) else Modifier)
            .clip(cardShape)
            // 吞掉落在卡片空白处、标题栏或已投选项上的点按,防止穿透到底下 PlayerShell 触发暂停/显隐控件。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .background(FixedColors.ScrimOnMedia)
            .border(width = HudSpecularBorderWidth, color = HudSpecularBorderColor, shape = cardShape)
            .padding(top = Spacing.Hair, bottom = bottomPad),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = horizontalPad, end = Spacing.Hair),
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
            Spacer(Modifier.width(if (compact) Spacing.Hair else Spacing.Tight))
            Text(
                text = vote.question,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.labelMedium,
                color = FixedColors.OnMedia,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (vote.hasVoted && vote.totalCount > 0) {
                Spacer(Modifier.width(Spacing.Hair))
                Text(
                    text = stringResource(Res.string.player_vote_count, formatCount(vote.totalCount.toLong())),
                    style = MaterialTheme.typography.labelSmall.tabular,
                    color = FixedColors.OnMedia.copy(alpha = 0.68f),
                    maxLines = 1,
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .sizeIn(minWidth = closeTargetSize, minHeight = closeTargetSize)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss)
                    .pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.player_vote_close),
                    tint = FixedColors.OnMedia.copy(alpha = 0.72f),
                    modifier = Modifier.size(closeIconSize),
                )
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPad),
        ) {
            val total = vote.totalCount.coerceAtLeast(vote.options.sumOf { it.count }).coerceAtLeast(1)
            if (compact) {
                vote.options.chunked(2).forEach { rowOptions ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        rowOptions.forEach { option ->
                            DanmakuVoteOptionRow(
                                option = option,
                                hasVoted = vote.hasVoted,
                                selected = vote.myVote == option.idx,
                                fraction = if (vote.hasVoted) (option.count.toFloat() / total).coerceIn(0f, 1f) else 0f,
                                compact = true,
                                onClick = { if (!vote.hasVoted) onSelectOption(option.idx) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            } else {
                vote.options.forEach { option ->
                    DanmakuVoteOptionRow(
                        option = option,
                        hasVoted = vote.hasVoted,
                        selected = vote.myVote == option.idx,
                        fraction = if (vote.hasVoted) (option.count.toFloat() / total).coerceIn(0f, 1f) else 0f,
                        compact = false,
                        onClick = { if (!vote.hasVoted) onSelectOption(option.idx) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
    compact: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animatedFractionState = animateFloatAsState(
        targetValue = if (hasVoted) fraction.coerceIn(0f, 1f) else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "vote-option-bar",
    )
    val barColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.52f)
    } else {
        Color.White.copy(alpha = 0.18f)
    }
    val trackColor = if (hasVoted) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.13f)
    val optionShape = if (compact) MaterialTheme.shapes.extraSmall else MaterialTheme.shapes.small
    val minHeight = if (compact) CompactVoteOptionMinHeight else VoteOptionMinHeight
    val horizontalPad = if (compact) CompactOptionHorizontalPad else Spacing.Tight
    val checkIconSize = if (compact) CompactCheckIconSize else VoteCheckIconSize
    val checkGap = if (compact) CompactCheckGap else Spacing.Hair
    val percentGap = if (compact) Spacing.Hair else Spacing.Tight

    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .heightIn(min = minHeight)
            .clip(optionShape)
            .background(trackColor)
            .drawBehind {
                val frac = animatedFractionState.value.coerceIn(0f, 1f)
                if (hasVoted && frac > 0f) {
                    drawRect(
                        color = barColor,
                        size = Size(size.width * frac, size.height),
                    )
                }
            }
            .then(
                if (!hasVoted) {
                    Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier
                },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPad, vertical = Spacing.Hair),
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = FixedColors.OnMedia,
                    modifier = Modifier.size(checkIconSize),
                )
                Spacer(Modifier.width(checkGap))
            }
            Text(
                text = option.desc,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = FixedColors.OnMedia.copy(alpha = if (!hasVoted || selected) 0.96f else 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (hasVoted) {
                Spacer(Modifier.width(percentGap))
                val percent = (fraction * 100).roundToInt()
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelSmall.tabular,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = FixedColors.OnMedia.copy(alpha = if (selected) 0.96f else 0.72f),
                    maxLines = 1,
                )
            }
        }
    }
}

private val CompactVoteHeightThreshold = 300.dp
private val CompactVoteWidthThreshold = 480.dp
private const val CompactVoteMaxWidthFraction = 0.55f
private const val CompactVoteMaxHeightFraction = 0.42f
private const val RegularVoteMaxHeightFraction = 0.55f

private val CompactVoteCardHardMinWidth = 120.dp
private val CompactVoteCardMinWidth = 148.dp
private val CompactVoteCardMaxWidth = 210.dp
private val CompactVoteMinHeightCap = 68.dp
private val CompactVoteFallbackMaxHeight = 96.dp
private val CompactCardBottomPad = 6.dp
private val CompactOptionHorizontalPad = 6.dp
private val CompactVoteOptionMinHeight = 32.dp
private val CompactCloseTargetSize = 28.dp
private val CompactCloseIconSize = 14.dp
private val CompactCheckIconSize = 12.dp
private val CompactCheckGap = 2.dp

private val VoteCardMinWidth = 192.dp
private val VoteCardMaxWidth = 240.dp
private val RegularVoteMinHeightCap = 160.dp
private val VoteOptionMinHeight = Dimens.MinTouchTarget
private val VoteCloseIconSize = 16.dp
private val VoteCheckIconSize = 14.dp
private val VoteTagVerticalPad = 1.dp
private const val VOTE_RESULT_LINGER_MILLIS = 3_500L
