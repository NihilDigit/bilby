package dev.bilby.ui.offline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.R
import dev.bilby.formatDurationMillis
import dev.bilby.formatDurationSeconds
import dev.bilby.offline.OfflineDownloader
import dev.bilby.offline.OfflineItem
import dev.bilby.offline.OfflineStatus
import dev.bilby.offline.OfflineStore
import dev.bilby.player.isWatchedToEnd
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.components.SectionHeader
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 已缓存的内容。
 *
 * 条目的真相在 [OfflineDownloader.items] 上(它同时盖着盘上已有的和正在下的那条),这里只
 * 多算一个已用空间 —— 那是一次目录遍历,不值得每次进度更新都重算。
 */
class OfflineViewModel(
    private val downloader: OfflineDownloader,
    private val store: OfflineStore,
) : ViewModel() {

    val items: StateFlow<List<OfflineItem>> = downloader.items

    private val _usedBytes = MutableStateFlow(0L)
    val usedBytes: StateFlow<Long> = _usedBytes.asStateFlow()

    init {
        refreshUsedBytes()
    }

    fun delete(item: OfflineItem) {
        downloader.delete(item)
        refreshUsedBytes()
    }

    /**
     * 批量删。逐条走 [OfflineDownloader.delete] 而不是另开一条批量路径:那条要先掐掉正在下的
     * 那些协程再删文件,顺序反了会在盘上留下查无此人的文件(见 delete 的说明)。已用空间只在
     * 全部删完之后算一次。
     */
    fun deleteAll(items: List<OfflineItem>) {
        items.forEach(downloader::delete)
        refreshUsedBytes()
    }

    fun retry(item: OfflineItem) = downloader.retry(item)

    private fun refreshUsedBytes() {
        viewModelScope.launch { _usedBytes.value = store.usedBytes() }
    }
}

/**
 * 缓存列表:能看、能播、能删。分两节 —— 正在缓存的在前,已缓存的在后。
 *
 * **在途的排在前面**,因为它们是这一页此刻唯一会变的东西,也是唯一可能需要人做点什么的
 * (失败了要重试);已缓存的是一份静止的存货。混排的话,几条在途的散在几十条已完成之间,
 * 进度要逐行去找。
 *
 * 复用 [VideoRow] 而不是另写一份行:风格指南 §2.7 的"一条视频在列表里的样子只有这一份"。
 * **封面底边的进度条只表示看到哪儿**,和稍后再看、历史记录同一个意思;下载进度只写在文字里。
 * 两种进度共用一条刻度的话,一行完成到 60% 的缓存和一行看到 60% 的视频长得一模一样。
 */
@Composable
fun OfflineScreen(
    items: List<OfflineItem>,
    usedBytes: Long,
    onPlay: (OfflineItem) -> Unit,
    onDelete: (OfflineItem) -> Unit,
    onRetry: (OfflineItem) -> Unit,
    /** 听全部。给的是已缓存那一节的第一条,队列是整个缓存库(见 `QueueSourceRepository.offline`)。 */
    onListenAll: (OfflineItem) -> Unit,
    /**
     * 已选中的条目 id,null 表示不在多选态。
     *
     * 不另设一个布尔:那样"空集合 + 还在多选态"是个画得出来、退不出去的组合。用 null 而不是
     * 空集合当出口,是因为顶栏那个「多选」按钮要能在一个都没选的情况下进多选态 ——
     * 拿"集合非空"当判据的话,这个入口无从表达。稍后再看的历史页是同一套(见 HistoryRoute)。
     */
    selectedIds: Set<String>? = null,
    onToggleSelection: (OfflineItem) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var pendingDelete by remember { mutableStateOf<OfflineItem?>(null) }

    AdaptiveContent(modifier = modifier) {
        if (items.isEmpty()) {
            EmptyState(
                message = stringResource(R.string.offline_empty),
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
            return@AdaptiveContent
        }

        val (completed, inFlight) = items.partition { it.status == OfflineStatus.Completed }
        val rowScope = OfflineRowScope(
            selectedIds = selectedIds,
            onPlay = onPlay,
            onRetry = onRetry,
            onToggleSelection = onToggleSelection,
            onDeleteRequest = { item ->
                // **还没开始下的那条不问。** 确认框存在的理由是"删掉就要重下几百 MB",
                // 而排队中的这条一个字节都还没下,取消它的代价是零 —— 再弹一次确认只是
                // 让取消一批误选的条目变成点两下一条。
                if (item.status == OfflineStatus.Queued) onDelete(item) else pendingDelete = item
            },
        )

        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            if (inFlight.isNotEmpty()) {
                item(key = "header-in-flight") {
                    SectionHeader(
                        title = stringResource(R.string.offline_section_in_flight, inFlight.size),
                        modifier = Modifier.padding(horizontal = Spacing.Comfortable).animateItem(),
                    )
                }
                offlineRows(inFlight, rowScope)
            }
            if (completed.isNotEmpty()) {
                item(key = "header-completed") {
                    SectionHeader(
                        title = stringResource(R.string.offline_section_completed),
                        // 标题行右端的 TextButton 自带内边距,这一侧的页边距让它来补。
                        modifier = Modifier
                            .padding(start = Spacing.Comfortable, end = Spacing.Tight)
                            .animateItem(),
                    ) {
                        // 已用空间说的是整个缓存目录,在途的那几条也算在里面;放在这一节是因为
                        // 它和"已缓存"是同一个问题的两个答案:存了什么、占了多少。
                        Text(
                            text = formatBytes(usedBytes),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 多选态下不给:此刻点一行是勾选,这个按钮却会离开这一页去播放。
                        TextButton(
                            onClick = { onListenAll(completed.first()) },
                            enabled = selectedIds == null,
                        ) {
                            Icon(
                                Icons.Filled.Headphones,
                                contentDescription = null,
                                modifier = Modifier.size(Dimens.IconInline),
                            )
                            Text(
                                stringResource(R.string.offline_listen_all),
                                modifier = Modifier.padding(start = Spacing.Tight),
                            )
                        }
                    }
                }
                offlineRows(completed, rowScope)
            }
        }
    }

    // 删除要确认:文件删掉之后要重新下一次几百 MB,而这一行的删除图标就在整行可点区的旁边。
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.offline_delete_confirm_title)) },
            text = { Text(stringResource(R.string.offline_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(target)
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** 两节共用的行为,打包成一份传给 [offlineRows],免得每节各抄一遍这几个回调。 */
private class OfflineRowScope(
    val selectedIds: Set<String>?,
    val onPlay: (OfflineItem) -> Unit,
    val onRetry: (OfflineItem) -> Unit,
    val onToggleSelection: (OfflineItem) -> Unit,
    val onDeleteRequest: (OfflineItem) -> Unit,
)

private fun LazyListScope.offlineRows(rows: List<OfflineItem>, scope: OfflineRowScope) {
    items(rows, key = { it.id }) { item ->
        OfflineRow(item, scope, Modifier.animateItem())
    }
}

@Composable
private fun OfflineRow(item: OfflineItem, scope: OfflineRowScope, modifier: Modifier = Modifier) {
    val selecting = scope.selectedIds != null
    val selected = scope.selectedIds != null && item.id in scope.selectedIds
    val haptics = LocalHapticFeedback.current
    // 选中态淡入,不是跳变:一次长按同时换掉整行底色和行尾那一格,两处一起硬切
    // 读起来像换了一份列表。取 effects 档 —— 变的是颜色,没有任何东西在移动。
    val rowColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "offline-row-selected",
    )
    VideoRow(
        item = item.toRowUi(),
        // 多选态下点一行是勾选,不是播 —— 进了多选还去播放,等于长按一下就再也删不成批。
        // 失败的那条点一下是重试,不是播:它本来就没东西可播。
        onClick = {
            when {
                selecting -> scope.onToggleSelection(item)
                item.status == OfflineStatus.Failed -> scope.onRetry(item)
                else -> scope.onPlay(item)
            }
        },
        onLongClick = {
            // 进多选态的那一下要有触感:屏上的变化只是多出一列勾选框,而长按本身
            // 没有任何提示。已经在多选态里时不再震 —— 那一下只是勾选,和点击等价。
            if (!selecting) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            scope.onToggleSelection(item)
        },
        // 选中的那几行要能一眼扫出来。整行染色而不是只画一个勾:勾在行尾,而人是从
        // 左往右扫的,只靠行尾那个小方块得逐行去对。
        modifier = modifier.background(rowColor),
        trailing = {
            // 删除图标和勾选框占的是同一格,所以是**换内容**而不是各自显隐:两个
            // 48dp 的东西在同一个位置上直接对调,读起来像图标变成了方块。
            // transitionSpec 不是组合上下文,主题里的 spec 要先在外面取出来。
            val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedContent(
                targetState = selecting,
                transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
                label = "offline-row-trailing",
            ) { inSelection ->
                if (inSelection) {
                    Checkbox(checked = selected, onCheckedChange = null)
                } else {
                    IconButton(onClick = { scope.onDeleteRequest(item) }) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.offline_delete, item.title),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    )
}

/**
 * 缓存条目 → 列表行。
 *
 * **多 P 的那一 P 排在状态行最前面。** 同一条视频缓了两个 P 就是两行同名条目,谁是谁只有
 * 分 P 名答得上来。不接在标题后面:标题两行就截断,长标题会把分 P 名截掉,而它正是这两行
 * 之间唯一的区别。
 */
@Composable
fun OfflineItem.toRowUi(): VideoRowUi {
    val durationMillis = durationSeconds * 1000
    val watchedToEnd = isWatchedToEnd(watchedPositionMillis, durationMillis)
    val statusText = when (status) {
        OfflineStatus.Completed -> listOfNotNull(
            when {
                watchedToEnd -> stringResource(R.string.history_finished)
                watchedPositionMillis > 0 ->
                    stringResource(R.string.history_progress, formatDurationMillis(watchedPositionMillis))
                else -> null
            },
            qualityLabel.takeIf { it.isNotEmpty() },
            stringResource(R.string.offline_with_danmaku).takeIf { hasDanmaku },
            formatBytes(downloadedBytes),
        ).joinToString(MetaSeparator)
        OfflineStatus.Queued -> stringResource(R.string.offline_status_queued)
        // 速度和百分比一起给:并发之后同时有几条在动,只有百分比的话看不出带宽分给了谁,
        // 也看不出某一条是不是已经停住了。速度为 0 的那一瞬(刚起步)不显示,别闪一下 0。
        OfflineStatus.Running -> listOfNotNull(
            progress
                ?.let { stringResource(R.string.offline_status_running, (it * 100).toInt()) }
                ?: stringResource(R.string.offline_status_running_unknown),
            speedBytesPerSecond.takeIf { it > 0 }?.let { formatSpeed(it) },
        ).joinToString(MetaSeparator)
        OfflineStatus.Failed -> error ?: stringResource(R.string.offline_status_failed)
    }
    return VideoRowUi(
        title = title,
        coverUrl = coverUrl,
        durationText = if (durationSeconds > 0) formatDurationSeconds(durationSeconds) else "",
        upName = upName.takeIf { it.isNotEmpty() },
        dateText = formatRelativeTime(publishedAtEpochSeconds),
        meta = listOfNotNull(partTitle.takeIf { it.isNotBlank() }, statusText).joinToString(MetaSeparator),
        // 只有下完的才有"看到哪儿"可言。
        progressFraction = when {
            status != OfflineStatus.Completed -> null
            watchedToEnd -> 1f
            durationMillis > 0 && watchedPositionMillis > 0 -> watchedPositionMillis.toFloat() / durationMillis
            else -> null
        },
    )
}

/**
 * 下载速度。**这里要 KB 那一档**,和 [formatBytes] 相反:手机网络下几百 KB/s 是常态,
 * 写成 "0.4 MB/s" 就把速度之间的差别压没了 —— 而速度这一栏存在的意义正是看出差别。
 */
@Composable
private fun formatSpeed(bytesPerSecond: Long): String {
    val kb = bytesPerSecond / 1024.0
    return if (kb >= 1024) {
        stringResource(R.string.speed_mb, kb / 1024)
    } else {
        stringResource(R.string.speed_kb, kb.roundToInt())
    }
}

/**
 * 字节数。**没有 KB 这一档** —— 缓存的东西全是视频,最小的一条也在几十 MB,给它一个
 * "12345 KB" 只是把同一个数字写长。
 *
 * internal:「我的」页缓存那一节的标题行也显示已用空间。
 */
@Composable
internal fun formatBytes(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024
    return if (mb >= 1024) {
        stringResource(R.string.size_gb, mb / 1024)
    } else {
        stringResource(R.string.size_mb, mb)
    }
}
