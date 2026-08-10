package dev.bilby.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 头像右下角挂一个小圆标。**联合投稿的关注、和最常访问的直播中共用这一份** —— 两处是同一个
 * 形状(一张脸,角上一个可点的小圆),各画一份的话尺寸和偏移迟早对不上,而它们在同一个 app 里
 * 长得应该一样。
 *
 * 小圆**比它看起来还小一点**:它紧挨着头像,而头像本身是可点的(进空间)。触摸区做到 M3 那条
 * 48dp 的下限会把整个头像右下角连同边上的空隙一起吃掉,想点进空间的手指有一半落在它身上。
 * 这里让触摸区就等于视觉尺寸 —— 破例的理由是**误触的代价不对称**:点错关注是替用户做了个
 * 社交动作,点错直播是把人从这一页扔进直播间,而"点小了"只是再点一次。
 */
@Composable
fun BadgedAvatar(
    url: String,
    size: Dp,
    modifier: Modifier = Modifier,
    badge: AvatarBadge? = null,
) {
    Box(modifier = modifier) {
        Avatar(url = url, size = size)
        badge?.let {
            Icon(
                imageVector = it.icon,
                contentDescription = it.contentDescription,
                tint = it.contentColor ?: MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // 往外挪出头像一角:完全压在头像里会盖住脸,而且圆叠圆的边缘挨得太近,
                    // 小尺寸下看不出是两个东西。
                    .offset(x = BadgeOffset, y = BadgeOffset)
                    .size(BadgeSize)
                    .clip(CircleShape)
                    .background(it.containerColor ?: MaterialTheme.colorScheme.primary)
                    .clickable(onClick = it.onClick)
                    .padding(BadgeIconInset),
            )
        }
    }
}

/** 角标要画什么、点了做什么。颜色留 null 就取 primary / onPrimary。 */
data class AvatarBadge(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    val containerColor: Color? = null,
    val contentColor: Color? = null,
)

/** 比原来的 18dp 再收一档:那个尺寸下点头像常常点到角标上。 */
private val BadgeSize = 14.dp

/** 露出头像轮廓之外的那一点。跟着尺寸一起收,否则小圆会整个跑到头像外面。 */
private val BadgeOffset = 3.dp

/** 图标与圆边之间留一丝,不然图形顶到边缘看着像被裁了。 */
private val BadgeIconInset = 1.dp
