package dev.bilby.ui.fav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.data.FavFolderDetail
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.CoverAspectRatio
import dev.bilby.ui.components.CoverCornerRadius
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing

/**
 * 收藏夹封面。比例与圆角同视频封面(16:10、10dp),收藏夹的封面本来就是其中某条视频的封面。
 *
 * **没有封面时画一个文件夹图标,不留一块空白。** 空收藏夹没有封面可取,而空白的 16:10 色块
 * 读起来像图还没加载完。
 */
@Composable
fun FavFolderCover(url: String, modifier: Modifier = Modifier, private: Boolean = false) {
    Box(
        modifier = modifier
            .aspectRatio(CoverAspectRatio)
            .clip(RoundedCornerShape(CoverCornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNotEmpty()) {
            // 封面是装饰:旁边就是收藏夹的名字。
            BiliAsyncImage(url = url, contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 私密的在封面右下角压一把锁,和视频封面上时长角标同一个位置、同一种底:封面是 UP 主
        // 上传的任意图片,不衬底的话白封面上看不见。
        if (private) {
            Icon(
                Icons.Outlined.Lock,
                // 这把锁没有旁边的文字替它说,读屏要念出来。
                contentDescription = stringResource(R.string.fav_folder_private),
                tint = FixedColors.OnMedia,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(LockInset)
                    .background(FixedColors.ScrimOnMedia, MaterialTheme.shapes.extraSmall)
                    .padding(LockPadding)
                    .size(LockIconSize),
            )
        }
    }
}

/** "12 个视频  私密"。公开性和条数排在一行,两者都是这个收藏夹的属性。 */
@Composable
fun favFolderMeta(folder: FavFolderDetail): String =
    stringResource(R.string.fav_folder_count, folder.count) + MetaSeparator +
        stringResource(if (folder.isPublic) R.string.fav_folder_public else R.string.fav_folder_private)

/**
 * 收藏夹列表里的一行:封面、名字、一行简介、条数与公开性。
 *
 * 版式照视频行([dev.bilby.ui.components.VideoRow]):同一宽度的封面、同一个页边距,
 * 收藏夹页和视频列表挨着出现时封面是一条竖线对齐下来的。
 *
 * @param trailing 行尾的操作(管理菜单)。
 */
@Composable
fun FavFolderRow(
    folder: FavFolderDetail,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FavFolderCover(url = folder.coverUrl, modifier = Modifier.width(Dimens.ListCoverWidth))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            Text(
                text = folder.title,
                style = MaterialTheme.typography.bodyLarge,
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
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        trailing?.invoke(this)
    }
}

/**
 * 「我的」页横排的收藏夹卡片:封面、名字、条数。
 *
 * **私密的在封面右下角给一把锁,公开的什么都不标。** 卡片只有封面那么宽,"12 个视频  公开"
 * 排不下;而大多数收藏夹的公开性就是默认那一档,需要被看见的是例外。
 */
@Composable
fun FavFolderCard(folder: FavFolderDetail, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // 带底色,和同一页的账号卡、预览行同一种"一块一块"的读法。外圆角 16、内边距 8,封面的
    // 10dp 圆角和外层差得不多,嵌套两层读起来不会一个圆一个方(风格指南 §1.4)。
    Column(
        modifier = modifier
            .width(Dimens.ListCoverWidth + Spacing.Tight * 2)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Spacing.Tight),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        FavFolderCover(url = folder.coverUrl, modifier = Modifier.fillMaxWidth(), private = !folder.isPublic)
        Text(
            text = folder.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.fav_folder_count, folder.count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** 锁的大小与离封面边的距离,同视频封面上时长角标的量级。 */
private val LockIconSize = 12.dp
private val LockPadding = 2.dp
private val LockInset = 4.dp
