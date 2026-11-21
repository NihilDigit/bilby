package dev.bilby.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.bilby.api.BiliResult
import dev.bilby.data.CoinLogEntry
import dev.bilby.data.CoinLogRepository
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveListContent
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.CoinGlyph
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.PagedLayout
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.SkeletonLine
import dev.bilby.ui.components.skeleton
import dev.bilby.ui.errorTextRes
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular
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
 */
@Composable
fun CoinLogRoute(repository: CoinLogRepository, onBack: () -> Unit) {
    val vm: CoinLogViewModel = viewModel(
        factory = viewModelFactory { initializer { CoinLogViewModel(repository) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    CoinLogScreen(state = state, onRefresh = vm::refresh, onRetry = vm::load, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoinLogScreen(
    state: CoinLogUiState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(
                title = stringResource(Res.string.coin_log_title),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                refreshing = state.refreshing,
                onRefresh = onRefresh,
            )
        },
    ) { insets ->
        AdaptiveListContent(modifier = Modifier.padScaffoldExceptBottom(insets)) { columns ->
            RefreshBox(
                refreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                PagedColumn(
                    layout = PagedLayout.Grid(columns),
                    items = state.items,
                    key = { entry -> entry.position },
                    skeletonRow = { CoinLogRowSkeleton() },
                    loading = state.loading,
                    appending = false,
                    hasMore = false,
                    error = state.error?.let { stringResource(it) },
                    emptyText = stringResource(Res.string.coin_log_empty),
                    onLoadMore = {},
                    onRetry = onRetry,
                    contentPadding = PaddingValues(
                        start = Spacing.Comfortable - CardCellInset,
                        end = Spacing.Comfortable - CardCellInset,
                        top = Spacing.Tight,
                        bottom = Spacing.Comfortable + navigationBarsBottom(),
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) { entry ->
                    CoinLogRow(entry)
                }
            }
        }
    }
}

private val CardCellInset = Spacing.Hair
private val LeadingBadgeSize = 36.dp
private val LeadingCoinGlyphSize = 18.dp

/**
 * 一条硬币记录。装进 `surfaceContainer` 圆角容器里,增减胶囊与时间戳全部启用等宽数字(`tabular`)。
 * 增用 `primaryContainer`、减用 `surfaceContainerHigh`,绝不用 `error` 红色渲染正常的投币支出。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CoinLogRow(entry: CoinLogEntry) {
    val colorScheme = MaterialTheme.colorScheme
    val positive = entry.delta > 0.0
    val badgeContainer = if (positive) colorScheme.primaryContainer else colorScheme.surfaceContainerHigh
    val badgeContent = if (positive) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant

    Surface(
        color = colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(CardCellInset)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = badgeContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.size(LeadingBadgeSize),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    CoinGlyph(
                        tint = badgeContent,
                        filled = positive,
                        cutout = badgeContainer,
                        size = LeadingCoinGlyphSize,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair / 2),
            ) {
                Text(
                    text = entry.reason,
                    style = MaterialTheme.typography.bodyLargeEmphasized,
                    color = colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = coinLogTime(entry.timeText),
                    style = MaterialTheme.typography.labelSmall.tabular,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            Surface(
                color = badgeContainer,
                contentColor = badgeContent,
                shape = CircleShape,
            ) {
                Text(
                    text = formatDelta(entry.delta),
                    style = MaterialTheme.typography.titleSmallEmphasized.tabular,
                    color = badgeContent,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Hair),
                )
            }
        }
    }
}

/** "+1"、"-2"、"+0.1"。整数不带小数点,小数去掉末尾的 0。 */
private fun formatDelta(delta: Double): String {
    val plain = BigDecimal.valueOf(delta).stripTrailingZeros().toPlainString()
    return if (delta > 0) "+$plain" else plain
}

@Composable
private fun coinLogTime(text: String): String {
    val epoch = remember(text) {
        runCatching {
            LocalDateTime.parse(text, ServerTimeFormat).atZone(ServerZone).toEpochSecond()
        }.getOrNull()
    } ?: return text
    return formatRelativeTime(epoch)
}

private val ServerTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val ServerZone: ZoneId = ZoneId.of("Asia/Shanghai")

@Composable
private fun CoinLogRowSkeleton() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(CardCellInset),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(LeadingBadgeSize)
                    .skeleton(MaterialTheme.shapes.medium),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                SkeletonLine(Modifier.fillMaxWidth(ReasonFraction))
                SkeletonLine(Modifier.fillMaxWidth(TimeFraction), height = TimeLineHeight)
            }
            Box(
                modifier = Modifier
                    .width(DeltaWidth)
                    .height(DeltaHeight)
                    .skeleton(CircleShape),
            )
        }
    }
}

private const val ReasonFraction = 0.6f
private const val TimeFraction = 0.25f
private val TimeLineHeight = 10.dp
private val DeltaWidth = 48.dp
private val DeltaHeight = 28.dp

@Immutable
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
