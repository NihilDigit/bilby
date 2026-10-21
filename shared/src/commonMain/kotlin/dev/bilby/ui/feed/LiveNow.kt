package dev.bilby.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import dev.bilby.ui.components.PaneSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import dev.bilby.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bilby.resources.*
import dev.bilby.data.LiveUpBrief
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.PlayingIndicator
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/**
 * 「最常访问」那一排最左边的一格:关注的人里此刻有谁在播。
 *
 * **它不是那一排的一员,是它前面的一格。** 那一排每个头像点进去是空间(一个人攒下来的一切),
 * 而这一格点开是此刻正在发生的事 —— 两者的时间性不同,合在一起就得替用户猜他想去哪。原先的
 * 做法是在头像角上挂一个直播小圆点,那个做法有个够不着的边界:live_users 里的人不一定在
 * "最常访问"那一排里,不在的那些一个都露不出来。
 *
 * 没人在播时整格不画。**不留占位、不显示"当前无人直播"** —— 那句话每天要占着这个位置说
 * 二十三个小时的废话。
 *
 * 头像至多叠前三个,从左到右每次缩小 10%。
 */
@Composable
fun LiveNowSlot(
    liveUps: List<LiveUpBrief>,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (liveUps.isEmpty()) return
    val faces = liveUps.take(MaxStackedFaces)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp, horizontal = LiveSlotInset)
            .width(liveSlotWidth(faces.size)),
    ) {
        StackedFaces(faces)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            PlayingIndicator(
                active = true,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(LiveSlotPulseSize),
            )
            Text(
                text = stringResource(Res.string.feed_live_now, count.coerceAtLeast(liveUps.size)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 至多三张叠着的头像。
 *
 * **从左到右依次缩小 10%,左前压右后。** 两张一样大的叠在一起读起来是"并列的人",而这一格要
 * 说的是"有人在播,还不止一个" —— 后面的头像依次小一圈,顺带定下叠放顺序:大的压在小的上面。
 *
 * 第一张和「最常访问」那排头像**一样大**:这一格站在那一排的最前面,矮一圈的话整行的
 * 头像上沿就不齐了。叠在前面的头像外侧描一圈背景色的边,把相邻两个圆的边缘切开。
 */
@Composable
private fun StackedFaces(faces: List<LiveUpBrief>) {
    val ring = MaterialTheme.colorScheme.surface
    Box(modifier = Modifier.size(width = stackWidth(faces.size), height = FrontFaceSize)) {
        // 从右后往左前画:Box 里后画的在上,左前的大头像压住右后的小头像。
        for (index in faces.lastIndex downTo 0) {
            val size = faceSize(index)
            val startOffset = faceRightEdge(index) - size
            val hasBackNeighbor = index < faces.lastIndex
            if (hasBackNeighbor) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = startOffset - FaceRing)
                        .size(size + FaceRing * 2)
                        .clip(CircleShape)
                        .background(ring)
                        .padding(FaceRing),
                ) {
                    Avatar(url = faces[index].faceUrl, size = size)
                }
            } else {
                Avatar(
                    url = faces[index].faceUrl,
                    size = size,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = startOffset),
                )
            }
        }
    }
}

/**
 * 正在直播的列表。用 sheet 不用整页:它是一份此刻有效、看一眼就走的短名单,推一整页进
 * backstack 之后回来还得按一次返回。
 *
 * 每行的副标题是**直播间标题**,不是签名也不是分区 —— "现在在播什么"正是决定点不点进去的
 * 那一句,而人名在上面一行已经有了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveNowSheet(
    liveUps: List<LiveUpBrief>,
    onLiveClick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    PaneSheet(onDismissRequest = onDismiss, skipPartiallyExpanded = false) {
        Text(
            text = stringResource(Res.string.feed_live_now_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        )
        LazyColumn {
            items(liveUps, key = { it.mid }) { up ->
                ListItem(
                    headlineContent = { Text(up.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                    // sheet 自己就是容器,行底色跟着它走(风格指南 §2.3c)。
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onLiveClick(up.roomId) },
                )
            }
        }
    }
}

private const val MaxStackedFaces = 3

/**
 * 左边第一张,压在最上面。**取的就是那一排的尺寸** —— 这一格排在首页那排关注的最前面,
 * 头像不一样大的话整行的上沿就参差了。
 */
private val FrontFaceSize = Dimens.AvatarStack

/** 往右每多叠一张,直径缩小 10%。 */
private const val FaceScaleStep = 0.9f

/** 后面每张头像往右露出的比例。 */
private const val BackFaceReveal = 0.36f

private fun faceSize(index: Int): Dp = when (index) {
    0 -> FrontFaceSize
    1 -> FrontFaceSize * FaceScaleStep
    else -> FrontFaceSize * (FaceScaleStep * FaceScaleStep)
}

private fun faceRightEdge(index: Int): Dp = when (index) {
    0 -> FrontFaceSize
    else -> faceRightEdge(index - 1) + faceSize(index) * BackFaceReveal
}

private fun stackWidth(faceCount: Int): Dp =
    faceRightEdge(faceCount.coerceIn(1, MaxStackedFaces) - 1)

/** 前面头像外侧那圈底色,把叠在一起的两个圆切开。 */
private val FaceRing = 1.5.dp

private val LiveSlotPulseSize = 12.dp

private val LivePulseSizeInList = 14.dp

private val MinLiveSlotWidth = 64.dp
private val LiveSlotSidePadding = 4.dp
private val LiveSlotInset = 2.dp

private fun liveSlotWidth(faceCount: Int): Dp =
    (stackWidth(faceCount) + LiveSlotSidePadding * 2).coerceAtLeast(MinLiveSlotWidth)

/**
 * 这一格左沿到前面那张大头像左沿的距离:格子的左右内边距,加上头像叠在格里居中让出的一半。
 */
internal fun liveNowSlotFaceInset(faceCount: Int): Dp =
    LiveSlotInset + (liveSlotWidth(faceCount) - stackWidth(faceCount)) / 2

/** 这一格连内边距的总宽。动态页算那一排放不放得下时用。 */
internal fun liveNowSlotOuterWidth(faceCount: Int): Dp =
    liveSlotWidth(faceCount) + LiveSlotInset * 2
