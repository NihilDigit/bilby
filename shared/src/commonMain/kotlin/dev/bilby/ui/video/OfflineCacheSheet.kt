package dev.bilby.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.data.QualityOption
import dev.bilby.offline.CachedIndex
import dev.bilby.offline.OfflineRequest
import dev.bilby.offline.offlineId
import dev.bilby.player.QueueItem
import dev.bilby.player.videoQualityLabel
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.components.PaneSheet
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular

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
    val copyright: Int = 0,
    val noReprint: Boolean = false,
    val chargingOnly: Boolean = false,
) {
    /** 列表 key 与勾选状态的键。用 (bvid, cid) 而不是 bvid:同一条视频的两个分 P 是两行。 */
    val key: String get() = offlineId(item.bvid, cid)

    fun isCached(cached: CachedIndex): Boolean = (item.bvid to cid) in cached

    fun isInProgress(cached: CachedIndex): Boolean = cached.isInProgress(item.bvid to cid)

    fun isFailed(cached: CachedIndex): Boolean = cached.isFailed(item.bvid to cid)

    /**
     * 勾不勾得了。已缓存的不用再下;排队和下载中的再勾一次会被下载器的去重挡掉,勾上了什么都
     * 不发生。失败的可以勾:重新勾选就是重试。
     */
    fun isSelectable(cached: CachedIndex): Boolean = !isCached(cached) && !isInProgress(cached)
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
 * 用统一面板([PaneSheet],`skipPartiallyExpanded = true`):标题与全选在统一顶栏,清晰度档位以
 * [FlowRow] 排在固定 `subheader`(避免窄侧边面板内产生横向滚动条,`side-sheets.md:320`),
 * 条目列表占弹性主体,底部确认按钮常驻 `footer`。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
            val initial = targets.firstOrNull { it.key == initialSelection && it.isSelectable(cached) }
            initial?.let { put(it.key, true) }
        }
    }
    var quality by rememberSaveable { mutableStateOf(defaultQuality) }

    val selectable = targets.filter { it.isSelectable(cached) }
    val chosen = selectable.filter { selected[it.key] == true }
    val allChosen = selectable.isNotEmpty() && chosen.size == selectable.size
    val options = qualities.ifEmpty {
        listOf(QualityOption(defaultQuality, videoQualityLabel(defaultQuality)))
    }

    PaneSheet(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.offline_sheet_title),
        skipPartiallyExpanded = true,
        headerTrailing = if (selectable.isNotEmpty()) {
            {
                // 全选/全不选是同一个入口:已缓存的那些不在 [selectable] 里,全选也不会碰它们。
                TextButton(
                    onClick = {
                        selectable.forEach { selected[it.key] = !allChosen }
                    },
                ) {
                    Text(
                        stringResource(
                            if (allChosen) Res.string.offline_sheet_select_none else Res.string.offline_sheet_select_all,
                        ),
                    )
                }
            }
        } else {
            null
        },
        subheader = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Loose, vertical = Spacing.Tight),
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                Text(
                    text = stringResource(Res.string.offline_sheet_quality),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    modifier = Modifier.selectableGroup(),
                ) {
                    options.forEach { option ->
                        FilledTonalToggleButton(
                            checked = quality == option.quality,
                            onCheckedChange = { quality = option.quality },
                            modifier = Modifier.semantics { role = Role.RadioButton },
                        ) {
                            Text(option.label, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        },
        footer = {
            Button(
                onClick = { onConfirm(chosen, quality) },
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (chosen.isEmpty()) {
                        stringResource(Res.string.offline_sheet_empty_selection)
                    } else {
                        stringResource(Res.string.offline_sheet_confirm, chosen.size)
                    },
                    style = LocalTextStyle.current.tabular,
                )
            }
        },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = Spacing.Tight),
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        ) {
            itemsIndexed(
                targets,
                key = { _, target -> target.key },
                contentType = { _, _ -> "target" },
            ) { index, target ->
                val enabled = target.isSelectable(cached)
                val checked = selected[target.key] == true
                // 状态行只在有话说时出现。失败的那条也要说:它可勾,但人得知道这是在重下一条
                // 失败过的,不是第一次缓存。
                val status = when {
                    target.isCached(cached) -> Res.string.offline_already_cached
                    target.isInProgress(cached) -> Res.string.offline_sheet_in_progress
                    target.isFailed(cached) -> Res.string.offline_status_failed
                    else -> null
                }
                @OptIn(ExperimentalMaterial3ExpressiveApi::class)
                SegmentedListItem(
                    checked = checked,
                    onCheckedChange = { selected[target.key] = it },
                    shapes = ListItemDefaults.segmentedShapes(index = index, count = targets.size),
                    enabled = enabled,
                    supportingContent = if (status != null) {
                        { Text(stringResource(status)) }
                    } else {
                        null
                    },
                    leadingContent = {
                        Checkbox(checked = checked || !enabled, enabled = enabled, onCheckedChange = null)
                    },
                    colors = ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedLeadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        selectedSupportingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap),
                ) {
                    // 分 P 行显示的是这一 P 的名字。整条视频的标题不再重复印在每一行上 ——
                    // 它们紧挨着,而用户正是从这条视频点进来的。
                    Text(
                        text = target.partTitle.ifEmpty { target.item.title },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
