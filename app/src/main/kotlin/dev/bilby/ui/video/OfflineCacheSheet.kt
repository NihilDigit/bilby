package dev.bilby.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.data.QualityOption
import dev.bilby.player.QueueItem
import dev.bilby.player.videoQualityLabel
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/**
 * 选择要缓存哪几条、用哪一档清晰度。
 *
 * **选择的对象就是当前播放队列**(合集分集,或这位 UP 的其他投稿),不是一个能无限往下翻的
 * 目录 —— 队列本来就是用户打开这条视频时选定的有限集合(DESIGN 2.4b),缓存只是把其中几条
 * 搬到本地,不引入任何新的内容来源。
 *
 * 用 `ModalBottomSheet` 而不是对话框,理由和全部分集那个 sheet 一样:条数从一条到几十条都有,
 * 必然要滚,而 dialogs 页写着 "Most dialog content should avoid scrolling"。
 *
 * 已经缓存过的条目**显示出来但不可选**:藏起来会让人以为队列少了一条,而"这条已经在本地了"
 * 正是用户此刻想知道的事。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineCacheSheet(
    items: List<QueueItem>,
    /** 已缓存(或正在缓存)的 bvid。这些条目在列表里显示为已缓存且不可勾。 */
    cachedBvids: Set<String>,
    /** 可选清晰度,来自当前这条视频的 accept_quality。为空时只显示默认档。 */
    qualities: List<QualityOption>,
    defaultQuality: Int,
    /** 打开时默认勾中的那条(正在播的这条)。 */
    initialSelection: String?,
    onConfirm: (selected: List<QueueItem>, qualityId: Int, withDanmaku: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember(items) {
        mutableStateMapOf<String, Boolean>().apply {
            initialSelection?.takeIf { it !in cachedBvids }?.let { put(it, true) }
        }
    }
    var quality by rememberSaveable { mutableStateOf(defaultQuality) }
    // **默认连弹幕一起缓存。** 离线看没有弹幕的 B 站视频是另一个东西,而弹幕只有几十 KB,
    // 不值得让用户每次都去勾一下。
    var withDanmaku by rememberSaveable { mutableStateOf(true) }

    val selectable = items.filterNot { it.bvid in cachedBvids }
    val chosen = selectable.filter { selected[it.bvid] == true }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.Comfortable),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Text(
                text = stringResource(R.string.offline_sheet_title),
                style = MaterialTheme.typography.titleMedium,
            )

            Text(
                text = stringResource(R.string.offline_sheet_quality),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // chip 而不是 connected button group:档数随视频变(几档到十几档都有)、要横滚,
            // 正是风格指南 §2.1 给 chip 的那一格。
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                val options = qualities.ifEmpty {
                    listOf(QualityOption(defaultQuality, videoQualityLabel(defaultQuality)))
                }
                items(options, key = { it.quality }) { option ->
                    FilterChip(
                        selected = quality == option.quality,
                        onClick = { quality = option.quality },
                        label = { Text(option.label) },
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.MinTouchTarget)
                    .toggleable(
                        value = withDanmaku,
                        role = Role.Checkbox,
                        onValueChange = { withDanmaku = it },
                    ),
            ) {
                Checkbox(checked = withDanmaku, onCheckedChange = null)
                Text(
                    text = stringResource(R.string.offline_sheet_danmaku),
                    modifier = Modifier.padding(start = Spacing.Tight),
                )
            }
        }

        LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = SheetListMaxHeight)) {
            items(items, key = { it.bvid }) { item ->
                val cached = item.bvid in cachedBvids
                val checked = selected[item.bvid] == true
                ListItem(
                    headlineContent = {
                        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = if (cached) {
                        { Text(stringResource(R.string.offline_already_cached)) }
                    } else {
                        null
                    },
                    leadingContent = {
                        Checkbox(checked = checked || cached, enabled = !cached, onCheckedChange = null)
                    },
                    // sheet 自己就是容器,行底色跟着它走 —— 画死 surface 会在容器里留下一条条
                    // 比容器亮的补丁(风格指南 §2.3c 的第二个坑)。
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = checked,
                        enabled = !cached,
                        role = Role.Checkbox,
                        onValueChange = { selected[item.bvid] = it },
                    ),
                )
            }
        }

        Button(
            onClick = { onConfirm(chosen, quality, withDanmaku) },
            enabled = chosen.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        ) {
            Text(
                if (chosen.isEmpty()) {
                    stringResource(R.string.offline_sheet_empty_selection)
                } else {
                    stringResource(R.string.offline_sheet_confirm, chosen.size)
                },
            )
        }
    }
}

/** 列表最多占这么高,再高会把下面的确认按钮顶出 sheet。 */
private val SheetListMaxHeight = 320.dp
