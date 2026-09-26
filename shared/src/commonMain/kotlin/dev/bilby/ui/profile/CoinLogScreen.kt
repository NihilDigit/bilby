package dev.bilby.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveListContent
import dev.bilby.ui.components.PagedLayout
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.api.BiliResult
import dev.bilby.data.CoinLogEntry
import dev.bilby.data.CoinLogRepository
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.RefreshAction
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.SkeletonLine
import dev.bilby.ui.components.skeleton
import dev.bilby.ui.errorTextRes
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.theme.Spacing
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * 硬币记录:自己账号每一次硬币增减,新的在前。从「我的」页进来。
 *
 * 一行三样:原因、时间、增减。**原因是主角**,排在左边用正文字号 —— 读这一页是为了弄清
 * "硬币去哪了、从哪来的",数字只是结果。PiliPlus 画成时间 / 变化 / 原因三列的表格
 * (`pages/log_table`),窄屏上原因那一列只剩几个字宽。
 */
@Composable
fun CoinLogRoute(repository: CoinLogRepository, onBack: () -> Unit) {
    val vm: CoinLogViewModel = viewModel(
        factory = viewModelFactory { initializer { CoinLogViewModel(repository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    CoinLogScreen(state = state, onRefresh = vm::refresh, onRetry = vm::load, onBack = onBack)
}

@Composable
private fun CoinLogScreen(
    state: CoinLogUiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(
                title = stringResource(Res.string.coin_log_title),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            ) {
                RefreshAction(refreshing = state.refreshing, onRefresh = onRefresh)
            }
        },
    ) { insets ->
        // 宽屏同其他列表页:一格最宽一行视频行那么宽,按行从左往右读,时间序不因分列而打乱。
        // 一行只有原因、时间、增减,单列铺满一千多 dp 时原因和数字之间隔着半屏。
        AdaptiveListContent(modifier = Modifier.padScaffoldExceptBottom(insets)) { columns ->
            RefreshBox(
                refreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                PagedColumn(
                    layout = PagedLayout.Grid(columns),
                    items = state.items,
                    // 接口不给 id,而同一秒里两条原因、数额都相同的记录是可能的,只能按位置作键。
                    key = { entry -> entry.position },
                    skeletonRow = { CoinLogRowSkeleton() },
                    loading = state.loading,
                    appending = false,
                    // 接口一次给全(见 CoinLogRepository),没有下一页。
                    hasMore = false,
                    error = state.error?.let { stringResource(it) },
                    emptyText = stringResource(Res.string.coin_log_empty),
                    onLoadMore = {},
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                ) { entry ->
                    CoinLogRow(entry)
                }
            }
        }
    }
}

/**
 * 一条记录。增减靠右,用等宽数字(tnum):一列 +1、-2、+10 上下对齐,扫一眼就分得出大小。
 *
 * 增用 primary,减用 onSurfaceVariant,**不用 error 的红色**:花掉硬币是用户自己投的币,
 * 不是出了什么问题;红色留给真需要停一下的地方(同 FullScreenError 的判据)。
 */
@Composable
private fun CoinLogRow(entry: CoinLogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Hair / 2)) {
            Text(
                text = entry.reason,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = coinLogTime(entry.timeText),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = formatDelta(entry.delta),
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = if (entry.delta > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "+1"、"-2"、"+0.1"。整数不带小数点,小数去掉末尾的 0。 */
private fun formatDelta(delta: Double): String {
    val plain = BigDecimal.valueOf(delta).stripTrailingZeros().toPlainString()
    return if (delta > 0) "+$plain" else plain
}

/**
 * 记录的时间,折成全应用那一份相对时间([formatRelativeTime]),与动态、评论同一种说法。
 *
 * 接口给的是拼好的 `yyyy-MM-dd HH:mm:ss` 字符串,不带时区。按北京时间解析是假定,没有核实过
 * (notes/space-and-search.md §1.10);设备本身在 UTC+8 时两种解法没有差别。解析不了就原样显示,
 * 不丢这一行的时间。
 */
@Composable
private fun coinLogTime(text: String): String {
    val epoch = runCatching {
        LocalDateTime.parse(text, ServerTimeFormat).atZone(ServerZone).toEpochSecond()
    }.getOrNull() ?: return text
    return formatRelativeTime(epoch)
}

private val ServerTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val ServerZone: ZoneId = ZoneId.of("Asia/Shanghai")

/** 与 [CoinLogRow] 同边距:两行字在左,一小块数字在右。 */
@Composable
private fun CoinLogRowSkeleton() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            SkeletonLine(Modifier.fillMaxWidth(ReasonFraction))
            SkeletonLine(Modifier.fillMaxWidth(TimeFraction), height = TimeLineHeight)
        }
        Box(modifier = Modifier.width(DeltaWidth).height(DeltaHeight).skeleton())
    }
}

private const val ReasonFraction = 0.6f
private const val TimeFraction = 0.25f
private val TimeLineHeight = 10.dp
private val DeltaWidth = 28.dp
private val DeltaHeight = 16.dp

data class CoinLogUiState(
    val items: List<CoinLogEntry> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: StringResource? = null,
)

class CoinLogViewModel(private val repository: CoinLogRepository) : ViewModel() {

    private val _state = MutableStateFlow(CoinLogUiState())
    val state: StateFlow<CoinLogUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = it.items.isEmpty(), error = null) }
        fetch()
    }

    /** 下拉刷新:列表留在屏上,回来的那一份整片换掉。 */
    fun refresh() {
        _state.update { it.copy(refreshing = true, error = null) }
        fetch()
    }

    private fun fetch() {
        viewModelScope.launch {
            when (val result = repository.load()) {
                is BiliResult.Ok -> _state.update {
                    it.copy(items = result.value, loading = false, refreshing = false)
                }

                else -> {
                    val error = result.errorTextRes("硬币记录")
                    _state.update { it.copy(loading = false, refreshing = false, error = error) }
                }
            }
        }
    }
}
