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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import dev.bilby.R
import dev.bilby.data.QualityOption
import dev.bilby.player.QueueItem
import dev.bilby.player.videoQualityLabel
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
 *
 * 弹幕这里没有开关:它一律跟着视频一起缓存。一条视频的全部弹幕只有几十 KB,给它一个勾等于
 * 让人为一个不存在的取舍做决定,而漏勾的代价是离线打开时才发现一条弹幕都没有。
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
    onConfirm: (selected: List<QueueItem>, qualityId: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember(items) {
        mutableStateMapOf<String, Boolean>().apply {
            initialSelection?.takeIf { it !in cachedBvids }?.let { put(it, true) }
        }
    }
    var quality by rememberSaveable { mutableStateOf(defaultQuality) }

    val selectable = items.filterNot { it.bvid in cachedBvids }
    val chosen = selectable.filter { selected[it.bvid] == true }
    val allChosen = selectable.isNotEmpty() && chosen.size == selectable.size

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.Comfortable),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.offline_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                // 全选/全不选是同一个入口:一个只能全选的按钮,按下之后就再没有用处了,而这
                // 恰好是最想撤销的一刻。已缓存的那些不在 [selectable] 里,全选也不会碰它们。
                if (selectable.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            selectable.forEach { selected[it.bvid] = !allChosen }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (allChosen) R.string.offline_sheet_select_none else R.string.offline_sheet_select_all,
                            ),
                        )
                    }
                }
            }

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
        }

        // 列表最多占窗口的这么高。**不能写死 dp** —— 横屏时窗口只有六百多 dp 高,一个按竖屏
        // 定的 320dp 列表加上标题和档位那两行正好把确认按钮顶出屏幕外,而 sheet 里 `weight`
        // 分不到东西(内容测的是 wrap content),那个按钮就只能滚出来。按比例算才跟着窗口走。
        val density = LocalDensity.current
        val listMaxHeight = with(density) {
            (LocalWindowInfo.current.containerSize.height * SheetListHeightFraction).toDp()
        }
        LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = listMaxHeight)) {
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
            onClick = { onConfirm(chosen, quality) },
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

/**
 * 列表最多占窗口高度的这一比例。剩下的留给标题、清晰度那一行和底部的确认按钮 —— 竖屏上
 * 40% 约等于原先那个 320dp,横屏上它会自己收窄,而不是把按钮挤出去。
 */
private const val SheetListHeightFraction = 0.4f
