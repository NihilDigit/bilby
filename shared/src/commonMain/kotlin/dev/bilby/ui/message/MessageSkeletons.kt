package dev.bilby.ui.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import dev.bilby.ui.components.SkeletonLine
import dev.bilby.ui.components.SkeletonPulse
import dev.bilby.ui.components.skeleton
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/*
 * 消息页三种列表的首屏骨架。会话列表的行与 PersonRowSkeleton 同形,直接用那一份;这里是它
 * 盖不住的三种:通知行多一行动作和一块引用,系统通知没有头像,会话页是一串左右交替的气泡。
 * 每一种都按真实行的边距与尺寸画,换上内容时什么都不挪(风格指南 §6 骨架屏一条)。
 */

/** 回复、@、赞那一行的占位:头像、名字、动作、一块引用,同 NoticeRow。 */
@Composable
internal fun NoticeRowSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
    ) {
        Box(modifier = Modifier.size(Dimens.AvatarStack).skeleton(CircleShape))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            SkeletonLine(Modifier.fillMaxWidth(NameFraction))
            SkeletonLine(Modifier.fillMaxWidth(ActionFraction), height = MetaLineHeight)
            SkeletonLine(Modifier.fillMaxWidth())
            Box(
                modifier = Modifier
                    .fillMaxWidth(QuoteFraction)
                    .height(QuoteHeight)
                    .skeleton(MaterialTheme.shapes.small),
            )
        }
    }
}

/** 系统通知那一行的占位:标题一行、正文两行,没有头像,同 SysNoticeRow。 */
@Composable
internal fun SysNoticeRowSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        SkeletonLine(Modifier.fillMaxWidth(NameFraction))
        SkeletonLine(Modifier.fillMaxWidth())
        SkeletonLine(Modifier.fillMaxWidth(QuoteFraction))
    }
}

/**
 * 会话页首屏:一串左右交替、长短不一的气泡,贴着底边排 —— 会话读进来之后也是贴着底边的,
 * 骨架从顶上往下排的话,换上内容时整屏会往下掉一截。
 */
@Composable
internal fun ChatSkeleton(modifier: Modifier = Modifier) {
    SkeletonPulse {
        Column(
            modifier = modifier
                .fillMaxSize()
                .clipToBounds()
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight, Alignment.Bottom),
        ) {
            SkeletonBubbles.forEach { (mine, fraction) ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(BubbleSkeletonHeight)
                            .skeleton(MaterialTheme.shapes.largeIncreased),
                    )
                }
            }
        }
    }
}

/** 左右与长短。取一段普通对话的样子,不是承诺会有这么多条。 */
private val SkeletonBubbles = listOf(
    false to 0.55f,
    false to 0.35f,
    true to 0.6f,
    false to 0.7f,
    true to 0.4f,
    true to 0.5f,
    false to 0.45f,
)

/** 一行字的气泡高:bodyLarge 的 24sp 行高加上下各 8dp 内边距。 */
private val BubbleSkeletonHeight = 40.dp

private val MetaLineHeight = 10.dp
private val QuoteHeight = 32.dp
private const val NameFraction = 0.4f
private const val ActionFraction = 0.3f
private const val QuoteFraction = 0.7f
