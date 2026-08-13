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
import dev.bilby.offline.CachedIndex
import dev.bilby.offline.OfflineRequest
import dev.bilby.offline.offlineId
import dev.bilby.player.QueueItem
import dev.bilby.player.videoQualityLabel
import dev.bilby.ui.theme.Spacing

/**
 * 缓存面板里的一行:**一个能单独缓存的单元**,不是队列里的一条视频。
 *
 * 这两者过去是同一个东西,而那正是"缓存只能缓存整条视频、选不了具体哪一 P"的来源:缓存的身份
 * 一直是 (bvid, cid)([dev.bilby.offline.OfflineItem]),下载器也一直按 cid 下,只有选择界面
 * 表达不出"哪一 P",于是多 P 视频永远缓存队列项手上那个 cid ——正在播的那条是当前 P,其余的
 * 是 P1。
 *
 * 多 P 视频的每一 P 各占一行,[partTitle] 非空;单 P 视频和队列里的其他视频仍是一行,
 * [partTitle] 为空。**只有当前这条视频摊得开** —— 分 P 清单来自视频详情,队列里别的视频要各
 * 打一次详情请求才知道,而缓存面板不值得为此在打开时先发十几个请求。
 */
data class OfflineTarget(
    val item: QueueItem,
    /**
     * 要缓存这条视频的哪一 P。**0 = 还不知道**,由下载器用视频详情补(见 [OfflineRequest])。
     *
     * 分 P 在这里而不在 [QueueItem] 上:队列项的身份只有 bvid,分 P 是这一行自己的事 ——
     * 当前这条视频摊成几行,每行一个 P,而队列里其他视频各只有一行。
     */
    val cid: Long = 0,
    val partTitle: String = "",
) {
    /** 列表 key 与勾选状态的键。用 (bvid, cid) 而不是 bvid:同一条视频的两个分 P 是两行。 */
    val key: String get() = offlineId(item.bvid, cid)

    fun isCached(cached: CachedIndex): Boolean = (item.bvid to cid) in cached
}

/** 这一行落成一条下载请求。 */
fun OfflineTarget.toOfflineRequest(qualityId: Int) = OfflineRequest(
    bvid = item.bvid,
    cid = cid,
    title = item.title,
    upName = item.upName,
    coverUrl = item.coverUrl,
    durationSeconds = item.durationSeconds,
    qualityId = qualityId,
)

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
    targets: List<OfflineTarget>,
    /** 盘上已有的东西。命中的条目显示为已缓存且不可勾。 */
    cached: CachedIndex,
    /** 可选清晰度,来自当前这条视频的 accept_quality。为空时只显示默认档。 */
    qualities: List<QualityOption>,
    defaultQuality: Int,
    /** 打开时默认勾中的那个单元(正在播的这一 P)。 */
    initialSelection: String?,
    onConfirm: (selected: List<OfflineTarget>, qualityId: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember(targets) {
        mutableStateMapOf<String, Boolean>().apply {
            val initial = targets.firstOrNull { it.key == initialSelection && !it.isCached(cached) }
            initial?.let { put(it.key, true) }
        }
    }
    var quality by rememberSaveable { mutableStateOf(defaultQuality) }

    val selectable = targets.filterNot { it.isCached(cached) }
    val chosen = selectable.filter { selected[it.key] == true }
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
                            selectable.forEach { selected[it.key] = !allChosen }
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
            items(targets, key = { it.key }) { target ->
                val isCached = target.isCached(cached)
                val checked = selected[target.key] == true
                ListItem(
                    headlineContent = {
                        // 分 P 行显示的是这一 P 的名字。整条视频的标题不再重复印在每一行上 ——
                        // 它们紧挨着,而用户正是从这条视频点进来的。
                        Text(
                            text = target.partTitle.ifEmpty { target.item.title },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = if (isCached) {
                        { Text(stringResource(R.string.offline_already_cached)) }
                    } else {
                        null
                    },
                    leadingContent = {
                        Checkbox(checked = checked || isCached, enabled = !isCached, onCheckedChange = null)
                    },
                    // sheet 自己就是容器,行底色跟着它走 —— 画死 surface 会在容器里留下一条条
                    // 比容器亮的补丁(风格指南 §2.3c 的第二个坑)。
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = checked,
                        enabled = !isCached,
                        role = Role.Checkbox,
                        onValueChange = { selected[target.key] = it },
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
