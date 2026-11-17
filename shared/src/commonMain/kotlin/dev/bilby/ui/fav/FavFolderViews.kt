package dev.bilby.ui.fav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.data.FavFolderDetail
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.CoverAspectRatio
import dev.bilby.ui.components.CoverCornerRadius
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.interactiveCard
import dev.bilby.ui.theme.tabular

/**
 * 收藏夹叠层封面:顶缘阶梯式 `surfaceContainerHigh` 文件夹唇边 + 16:10 封面 + 右下角私密锁与条数胶囊。
 *
 * **没有封面时画一个文件夹图标,不留一块空白。** 空收藏夹没有封面可取,而空白的 16:10 色块
 * 读起来像图还没加载完。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FavFolderCover(
    url: String,
    modifier: Modifier = Modifier,
    private: Boolean = false,
    count: Int? = null,
    showLip: Boolean = true,
) {
    val colorScheme = MaterialTheme.colorScheme
    Column(modifier = modifier) {
        if (showLip) {
            Box(
                modifier = Modifier
                    .padding(horizontal = Spacing.Cozy)
                    .fillMaxWidth()
                    .height(FolderLipHeight)
                    .clip(RoundedCornerShape(topStart = FolderLipCorner, topEnd = FolderLipCorner))
                    .background(colorScheme.surfaceContainerHigh),
            )
            Spacer(modifier = Modifier.height(FolderLipGap))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CoverAspectRatio)
                .clip(RoundedCornerShape(CoverCornerRadius))
                .background(colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (url.isNotEmpty()) {
                // 封面是装饰:旁边就是收藏夹的名字。
                BiliAsyncImage(url = url, contentDescription = null, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                )
            }
            // 私密锁与条数胶囊同居封面右下角,用固定遮罩色衬底,在任意亮暗封面上都清晰可读。
            if (private || count != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(BadgeInset)
                        .background(FixedColors.ScrimOnMedia, CircleShape)
                        .padding(
                            horizontal = if (count != null) BadgeHorizontalPadding else LockPadding + 2.dp,
                            vertical = BadgeVerticalPadding,
                        ),
                ) {
                    if (private) {
                        Icon(
                            Icons.Outlined.Lock,
                            // 这把锁没有旁边的文字替它说,读屏要念出来。
                            contentDescription = stringResource(Res.string.fav_folder_private),
                            tint = FixedColors.OnMedia,
                            modifier = Modifier.size(LockIconSize),
                        )
                    }
                    if (count != null) {
                        Text(
                            text = stringResource(Res.string.fav_folder_count, count),
                            style = MaterialTheme.typography.labelSmallEmphasized.tabular,
                            color = FixedColors.OnMedia,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** "12 个视频  私密"。公开性和条数排在一行,两者都是这个收藏夹的属性。 */
@Composable
fun favFolderMeta(folder: FavFolderDetail): String =
    stringResource(Res.string.fav_folder_count, folder.count) + MetaSeparator +
        stringResource(if (folder.isPublic) Res.string.fav_folder_public else Res.string.fav_folder_private)

/**
 * 收藏夹单列行:叠层封面、名字、一行简介、条数与公开性。
 *
 * 版式照视频行([dev.bilby.ui.components.VideoRow]):同一宽度的封面、同一个页边距,
 * 收藏夹页和视频列表挨着出现时封面是一条竖线对齐下来的。
 *
 * @param trailing 行尾的操作(管理菜单)。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FavFolderRow(
    folder: FavFolderDetail,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 宽屏的右键菜单挂在长按上,见 `ContextMenuBox`。 */
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .interactiveCard(
                shape = MaterialTheme.shapes.large,
                baseColor = Color.Transparent,
                hoverColor = MaterialTheme.colorScheme.surfaceContainerLow,
                enableScale = false,
            )
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FavFolderCover(
            url = folder.coverUrl,
            private = !folder.isPublic,
            count = folder.count,
            modifier = Modifier.width(Dimens.ListCoverWidth),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            Text(
                text = folder.title,
                style = MaterialTheme.typography.bodyLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (folder.intro.isNotBlank()) {
                Text(
                    text = folder.intro,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = favFolderMeta(folder),
                style = MaterialTheme.typography.labelSmall.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        trailing?.invoke(this)
    }
}

/**
 * 宽屏多列网格里的收藏夹卡片:阶梯文件夹唇边 + 16:10 封面计数胶囊 + 强调标题 + 悬停抬升与管理入口。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FavFolderGridCard(
    folder: FavFolderDetail,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    overflow: (@Composable () -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val subtitle = if (folder.intro.isNotBlank()) {
        favFolderMeta(folder) + MetaSeparator + folder.intro
    } else {
        favFolderMeta(folder)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .interactiveCard(
                shape = MaterialTheme.shapes.large,
                baseColor = colorScheme.surfaceContainer,
                hoverColor = lerp(colorScheme.surfaceContainer, colorScheme.surfaceContainerHigh, 0.6f),
            )
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(Spacing.Tight),
    ) {
        FavFolderCover(
            url = folder.coverUrl,
            modifier = Modifier.fillMaxWidth(),
            private = !folder.isPublic,
            count = folder.count,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.Tight, start = 2.dp, end = 2.dp, bottom = 2.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = folder.title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.tabular,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            overflow?.invoke()
        }
    }
}

/**
 * 「我的」页横排的收藏夹卡片:叠层封面、名字、条数与悬停容器色阶反馈。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FavFolderCard(folder: FavFolderDetail, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .width(Dimens.ListCoverWidth + Spacing.Tight * 2)
            .interactiveCard(
                shape = MaterialTheme.shapes.large,
                baseColor = colorScheme.surfaceContainer,
                hoverColor = lerp(colorScheme.surfaceContainer, colorScheme.surfaceContainerHigh, 0.65f),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Spacing.Tight),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        FavFolderCover(
            url = folder.coverUrl,
            modifier = Modifier.fillMaxWidth(),
            private = !folder.isPublic,
            count = folder.count,
        )
        Text(
            text = folder.title,
            style = MaterialTheme.typography.bodyMediumEmphasized,
            color = colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(Res.string.fav_folder_count, folder.count),
            style = MaterialTheme.typography.labelSmall.tabular,
            color = colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private val FolderLipHeight = 6.dp
private val FolderLipGap = 2.dp
private val FolderLipCorner = 6.dp

/** 胶囊角标离封面边的距离与内边距。 */
private val LockIconSize = 12.dp
private val LockPadding = 2.dp
private val BadgeInset = 6.dp
private val BadgeHorizontalPadding = 8.dp
private val BadgeVerticalPadding = 2.dp
