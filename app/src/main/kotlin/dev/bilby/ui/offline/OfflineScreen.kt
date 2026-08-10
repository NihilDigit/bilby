package dev.bilby.ui.offline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.R
import dev.bilby.formatDurationSeconds
import dev.bilby.offline.OfflineDownloader
import dev.bilby.offline.OfflineItem
import dev.bilby.offline.OfflineStatus
import dev.bilby.offline.OfflineStore
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun retry(item: OfflineItem) = downloader.retry(item)

    private fun refreshUsedBytes() {
        viewModelScope.launch { _usedBytes.value = store.usedBytes() }
    }
}

/**
 * 缓存列表:能看、能播、能删。
 *
 * 复用 [VideoRow] 而不是另写一份行:风格指南 §2.7 的"一条视频在列表里的样子只有这一份"。
 * 状态(排队 / 下载 %/ 失败)走它的 `meta` 槽,进度走 `progressFraction` —— 那一槽本来是给
 * 稍后再看的"看到哪儿"用的,两者要表达的是同一件事:这一行完成到什么程度。
 */
@Composable
fun OfflineScreen(
    items: List<OfflineItem>,
    usedBytes: Long,
    onPlay: (OfflineItem) -> Unit,
    onDelete: (OfflineItem) -> Unit,
    onRetry: (OfflineItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var pendingDelete by remember { mutableStateOf<OfflineItem?>(null) }

    if (items.isEmpty()) {
        EmptyState(message = stringResource(R.string.offline_empty), modifier = modifier.fillMaxSize())
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        items(items, key = { it.id }) { item ->
            VideoRow(
                item = item.toRowUi(),
                // 失败的那条点一下是重试,不是播 —— 它本来就没东西可播。
                onClick = { if (item.status == OfflineStatus.Failed) onRetry(item) else onPlay(item) },
                trailing = {
                    IconButton(onClick = { pendingDelete = item }) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.offline_delete, item.title),
                        )
                    }
                },
            )
        }
        item {
            Text(
                text = stringResource(R.string.offline_used_space, formatBytes(usedBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
            )
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

/**
 * 缓存条目 → 列表行。已经下完的不显示状态行(它就是一条普通视频),只有在途和失败才有话说。
 */
@Composable
fun OfflineItem.toRowUi(): VideoRowUi = VideoRowUi(
    title = title,
    coverUrl = coverUrl,
    durationText = if (durationSeconds > 0) formatDurationSeconds(durationSeconds) else "",
    upName = upName.takeIf { it.isNotEmpty() },
    meta = when (status) {
        OfflineStatus.Completed -> listOfNotNull(
            qualityLabel.takeIf { it.isNotEmpty() },
            stringResource(R.string.offline_with_danmaku).takeIf { hasDanmaku },
            formatBytes(downloadedBytes),
        ).joinToString(" · ")
        OfflineStatus.Queued -> stringResource(R.string.offline_status_queued)
        OfflineStatus.Running -> progress
            ?.let { stringResource(R.string.offline_status_running, (it * 100).toInt()) }
            ?: stringResource(R.string.offline_status_running_unknown)
        OfflineStatus.Failed -> error ?: stringResource(R.string.offline_status_failed)
    },
    progressFraction = progress.takeIf { status == OfflineStatus.Running },
)

/**
 * 字节数。**没有 KB 这一档** —— 缓存的东西全是视频,最小的一条也在几十 MB,给它一个
 * "12345 KB" 只是把同一个数字写长。
 */
@Composable
private fun formatBytes(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024
    return if (mb >= 1024) {
        stringResource(R.string.size_gb, mb / 1024)
    } else {
        stringResource(R.string.size_mb, mb)
    }
}
