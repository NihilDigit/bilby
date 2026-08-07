package dev.bilby.ui.fav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.api.BiliResult
import dev.bilby.data.FavFolder
import dev.bilby.data.FavRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FavHubUiState(
    val folders: List<FavFolder> = emptyList(),
    val loading: Boolean = true,
)

/**
 * 第三屏的入口页。
 *
 * 稍后再看与收藏夹在产品上是同一类东西:**用户自己挑好的有限存货**。DESIGN 1.2 否决点心盒
 * 时给的理由正是这个 —— 疲惫时需要的不是一个定量投喂的管道,而是让好内容同样零启动成本。
 * 把两者并排放在一屏,打开就能进自己的存货,是那条结论的落点。
 */
class FavHubViewModel(private val repository: FavRepository) : ViewModel() {

    private val _state = MutableStateFlow(FavHubUiState())
    val state: StateFlow<FavHubUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() = viewModelScope.launch {
        when (val result = repository.folders()) {
            is BiliResult.Ok -> _state.value = FavHubUiState(folders = result.value, loading = false)
            // 收藏夹拉不到就只剩稍后再看那一项,不整屏报错:稍后再看是这一屏的主要用途,
            // 不该被收藏夹的失败连坐。但要留一行,否则界面上只是少了几行,查不出原因。
            is BiliResult.ApiError -> {
                BiliLog.w("取收藏夹列表失败(${result.code}): ${result.message}")
                _state.update { it.copy(loading = false) }
            }

            is BiliResult.Failure -> {
                BiliLog.w("取收藏夹列表异常", result.cause)
                _state.update { it.copy(loading = false) }
            }
        }
    }
}

@Composable
fun FavHubScreen(
    state: FavHubUiState,
    toViewCount: Int,
    onOpenToView: () -> Unit,
    onOpenFolder: (FavFolder) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = contentPadding) {
        item(key = "toview") {
            HubRow(
                icon = Icons.Outlined.WatchLater,
                title = stringResource(R.string.tab_toview),
                subtitle = stringResource(R.string.fav_folder_count, toViewCount),
                onClick = onOpenToView,
            )
            HorizontalDivider()
        }
        items(state.folders, key = { it.id }) { folder ->
            HubRow(
                icon = Icons.Outlined.Star,
                title = folder.title,
                subtitle = stringResource(R.string.fav_folder_count, folder.count),
                onClick = { onOpenFolder(folder) },
            )
        }
    }
}

@Composable
private fun HubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
