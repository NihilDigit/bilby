package dev.bilby.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.data.LiveUpBrief
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.LivePulse
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
 * 头像只堆前两个:第三个开始就只是一串糊在一起的圆,而"有几个"那个数字已经由旁边的文字说了。
 */
@Composable
fun LiveNowSlot(
    liveUps: List<LiveUpBrief>,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (liveUps.isEmpty()) return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp)
            .width(LiveSlotWidth),
    ) {
        StackedFaces(liveUps)
        Text(
            text = stringResource(R.string.feed_live_now, count.coerceAtLeast(liveUps.size)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 两张叠着的头像,右下角压一枚跳动的符号。
 *
 * **一大一小,大的在左。** 两张一样大的叠在一起读起来是"两个并列的人",而这一格要说的是
 * "有人在播,还不止一个" —— 第二张是那个"不止",不是并列的另一位。大小差还顺带定了叠放顺序:
 * 大的压在小的上面,不必再靠边框去区分谁在前。
 *
 * 大的那张和「最常访问」那排头像**一样大**:这一格站在那一排的最前面,矮一圈的话整行的
 * 头像上沿就不齐了,而那正是一眼看得出的毛病。
 *
 * 小的那张要描一圈背景色的边:两个圆的边缘不分开的话,叠合处糊成一块,看着像一张被啃掉
 * 一角的头像。
 */
@Composable
private fun StackedFaces(liveUps: List<LiveUpBrief>) {
    val ring = MaterialTheme.colorScheme.surface
    Box(modifier = Modifier.size(width = StackWidth, height = FrontFaceSize)) {
        // 先画小的(右后),再画大的(左前)。Box 里后画的在上,顺序就是层级。
        liveUps.getOrNull(1)?.let { back ->
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(BackFaceSize + FaceRing * 2)
                    .clip(CircleShape)
                    .background(ring)
                    .padding(FaceRing),
            ) {
                Avatar(url = back.faceUrl, size = BackFaceSize)
            }
        }
        Avatar(
            url = liveUps[0].faceUrl,
            size = FrontFaceSize,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        // 直接画在头像上,不衬一个圆底。衬底是给"图标"用的 —— 一个有轮廓的形状压在另一个
        // 形状上才需要底把它托起来;而这三根条本身就是稀疏的笔画,加个实心圆反倒把它围成了
        // 一枚按钮。
        LivePulse(
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.BottomEnd).size(PulseSize),
        )
    }
}

/**
 * 宽屏次区里的那一行。和 [LiveNowSlot] 是同一件事的两种形状:横排那格是方格(头像在上、
 * 字在下),这一栏是 `ListItem`(头像在左、字在右),两边各按所在容器的形状走。
 *
 * 头像仍用同一组叠放,不退化成一个图标 —— 「有谁在播」这件事里,那两张脸就是内容本身。
 */
@Composable
fun LiveNowListRow(
    liveUps: List<LiveUpBrief>,
    count: Int,
    onClick: () -> Unit,
) {
    if (liveUps.isEmpty()) return
    ListItem(
        headlineContent = {
            Text(
                text = stringResource(R.string.feed_live_now, count.coerceAtLeast(liveUps.size)),
                color = MaterialTheme.colorScheme.primary,
            )
        },
        leadingContent = { StackedFaces(liveUps) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    )
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.feed_live_now_title),
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
                        LivePulse(
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

/**
 * 左边那张,压在上面。**取的就是那一排的尺寸** —— 这一格排在首页那排关注的最前面,
 * 头像不一样大的话整行的上沿就参差了。以前这里是一个同值的本地常量,配一句"改那边记得
 * 改这里"。
 */
private val FrontFaceSize = Dimens.AvatarStack

/**
 * 右边那张,压在下面。**按前一张的比例定,不写死 dp** —— 这两个数之间的差是有意义的
 * (它表示"还不止一个",不是并列的另一位),写成两个独立的常量之后,改大的那张时这层关系
 * 就悄悄变了。
 */
private val BackFaceSize = FrontFaceSize * 0.75f

/** 小的那张露出来多少。露出约六成,再少就只剩一道边,看不出是张脸。 */
private const val BackFaceReveal = 0.61f

private val StackWidth = FrontFaceSize + BackFaceSize * BackFaceReveal

/** 小头像外那圈底色,把两个圆的边缘分开。 */
private val FaceRing = 1.5.dp

private val PulseSize = 16.dp

private val LivePulseSizeInList = 14.dp

/** 比两张头像叠起来再宽一点,让"N 正在直播"那行字有地方站。 */
/** 这一格**比头像格宽**:它装的是两张叠着的脸,不是一张。 */
private val LiveSlotWidth = 72.dp
