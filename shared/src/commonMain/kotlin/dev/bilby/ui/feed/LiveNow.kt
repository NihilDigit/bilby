package dev.bilby.ui.feed

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bilby.data.LiveUpBrief
import dev.bilby.resources.Res
import dev.bilby.resources.feed_live_count_badge
import dev.bilby.resources.feed_live_now_title
import dev.bilby.stringResource
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.PaneSheet
import dev.bilby.ui.components.PlayingIndicator
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.interactiveCard
import dev.bilby.ui.theme.tabular

/**
 * 「最常访问 / 特别关注」那一排最左端的前置格:关注的人里此刻正在直播的入口。
 *
 * 没人在播时整格不画、不留占位。有人在播时采用 2dp `primary` 环形的头像胶囊叠放至多三张等大头像,
 * 底部跨压一枚 `primaryContainer` 胶囊(`PlayingIndicator` + 等宽 `N 直播`),与右侧单人空间格在形态上明确区分。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LiveNowSlot(
    liveUps: List<LiveUpBrief>,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (liveUps.isEmpty()) return
    val faces = liveUps.take(MaxStackedFaces)
    val totalLiveCount = count.coerceAtLeast(liveUps.size)
    val colors = MaterialTheme.colorScheme

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .interactiveCard(
                shape = MaterialTheme.shapes.medium,
                baseColor = Color.Transparent,
                hoverColor = colors.surfaceContainerLow,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.Hair, horizontal = LiveSlotInset)
            .width(liveSlotWidth(faces.size)),
    ) {
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxWidth()
                .height(FrontFaceSize + LiveBadgeOverlapDrop),
        ) {
            LiveRingCapsule(faces = faces)

            Surface(
                shape = CircleShape,
                color = colors.primaryContainer,
                contentColor = colors.onPrimaryContainer,
                border = BorderStroke(BadgeHaloBorder, colors.surface),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    modifier = Modifier.padding(horizontal = LiveBadgeHorizontalPadding, vertical = LiveBadgeVerticalPadding),
                ) {
                    PlayingIndicator(
                        active = true,
                        color = colors.onPrimaryContainer,
                        modifier = Modifier.size(LiveSlotPulseSize),
                    )
                    Text(
                        text = stringResource(Res.string.feed_live_count_badge, totalLiveCount),
                        style = MaterialTheme.typography.labelSmallEmphasized.tabular,
                        color = colors.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 2dp `primary` 描边的胶囊头像框:外高与右侧 [Dimens.AvatarStack](48dp)严格齐平,
 * 内部至多叠三张等尺寸圆头像,前一张压后一张并以底色描边切开相邻边缘。
 */
@Composable
private fun LiveRingCapsule(faces: List<LiveUpBrief>) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .size(width = capsuleWidth(faces.size), height = FrontFaceSize)
            .clip(CircleShape)
            .background(colors.surfaceContainerLow)
            .border(LiveRingStrokeWidth, colors.primary, CircleShape)
            .padding(LiveRingInnerInset),
    ) {
        for (index in faces.lastIndex downTo 0) {
            val startOffset = AvatarOverlapStep * index
            val hasBackNeighbor = index < faces.lastIndex
            if (hasBackNeighbor) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .offset(x = startOffset - FaceSeparatorWidth)
                        .size(InnerAvatarSize + FaceSeparatorWidth * 2)
                        .clip(CircleShape)
                        .background(colors.surface),
                ) {
                    Avatar(url = faces[index].faceUrl, size = InnerAvatarSize)
                }
            } else {
                Avatar(
                    url = faces[index].faceUrl,
                    size = InnerAvatarSize,
                    modifier = Modifier.offset(x = startOffset),
                )
            }
        }
    }
}

/**
 * 正在直播的名单面板,接入统一 [PaneSheet] 标题栏。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LiveNowSheet(
    liveUps: List<LiveUpBrief>,
    onLiveClick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    PaneSheet(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.feed_live_now_title),
        skipPartiallyExpanded = true,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            contentPadding = PaddingValues(vertical = Spacing.Hair),
        ) {
            items(liveUps, key = { it.mid }) { up ->
                ListItem(
                    onClick = { onLiveClick(up.roomId) },
                    supportingContent = up.roomTitle.takeIf { it.isNotEmpty() }?.let { title ->
                        { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    },
                    leadingContent = { Avatar(url = up.faceUrl, size = Dimens.AvatarRow) },
                    trailingContent = {
                        PlayingIndicator(
                            active = true,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(LivePulseSizeInList),
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = up.name,
                        style = MaterialTheme.typography.bodyLargeEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val MaxStackedFaces = 3

private val FrontFaceSize = Dimens.AvatarStack
private val LiveRingStrokeWidth = 2.dp
private val LiveRingInnerInset = 3.dp
private val InnerAvatarSize = FrontFaceSize - LiveRingInnerInset * 2
private val AvatarOverlapStep = 22.dp
private val FaceSeparatorWidth = 1.5.dp

private val BadgeHaloBorder = 1.5.dp
private val LiveBadgeOverlapDrop = 16.dp
private val LiveBadgeHorizontalPadding = 7.dp
private val LiveBadgeVerticalPadding = 1.5.dp
private val LiveSlotPulseSize = 11.dp
private val LivePulseSizeInList = 14.dp

private val MinLiveSlotWidth = 68.dp
private val LiveSlotSidePadding = 4.dp
private val LiveSlotInset = 2.dp

private fun capsuleWidth(faceCount: Int): Dp =
    FrontFaceSize + AvatarOverlapStep * (faceCount.coerceIn(1, MaxStackedFaces) - 1)

private fun liveSlotWidth(faceCount: Int): Dp =
    (capsuleWidth(faceCount) + LiveSlotSidePadding * 2).coerceAtLeast(MinLiveSlotWidth)

/**
 * 这一格左沿到环形头像胶囊左沿的距离,供首页门户条对齐 16dp 页边线。
 */
internal fun liveNowSlotFaceInset(faceCount: Int): Dp =
    LiveSlotInset + (liveSlotWidth(faceCount) - capsuleWidth(faceCount)) / 2

/** 这一格连内边距的总宽,动态页算整排能否并成一行时用。 */
internal fun liveNowSlotOuterWidth(faceCount: Int): Dp =
    liveSlotWidth(faceCount) + LiveSlotInset * 2
