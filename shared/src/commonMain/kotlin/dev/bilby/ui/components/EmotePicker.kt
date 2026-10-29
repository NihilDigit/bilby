package dev.bilby.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SentimentSatisfied
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bilby.api.BiliResult
import dev.bilby.data.EmoteAction
import dev.bilby.data.EmoteEntry
import dev.bilby.data.EmoteTab
import dev.bilby.data.EmoteTabKind
import dev.bilby.data.LocalEmoteRepository
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.errorTextRes
import dev.bilby.ui.imeTarget
import dev.bilby.ui.screenOrientationKey
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

@Immutable
sealed interface EmoteLoadState {
    data object Idle : EmoteLoadState
    data object Loading : EmoteLoadState
    data class Ready(val tabs: List<EmoteTab>) : EmoteLoadState
    data class Failed(val message: StringResource) : EmoteLoadState
}

@Composable
fun rememberReplyEmoteState(active: Boolean): Pair<EmoteLoadState, () -> Unit> {
    val repository = LocalEmoteRepository.current
    var state by remember { mutableStateOf<EmoteLoadState>(EmoteLoadState.Idle) }
    var retryNonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(repository, active, retryNonce) {
        if (!active || repository == null) return@LaunchedEffect
        if (state is EmoteLoadState.Ready && retryNonce == 0) return@LaunchedEffect
        state = EmoteLoadState.Loading
        state = when (val result = repository.loadReplyEmotes(forceRefresh = retryNonce > 0)) {
            is BiliResult.Ok -> EmoteLoadState.Ready(result.value)
            else -> EmoteLoadState.Failed(result.errorTextRes("加载表情列表"))
        }
    }
    return state to { retryNonce++ }
}

@Composable
fun rememberLiveEmoteState(roomId: Long, active: Boolean): Pair<EmoteLoadState, () -> Unit> {
    val repository = LocalEmoteRepository.current
    var state by remember(roomId) { mutableStateOf<EmoteLoadState>(EmoteLoadState.Idle) }
    var retryNonce by remember(roomId) { mutableIntStateOf(0) }
    LaunchedEffect(repository, roomId, active, retryNonce) {
        if (!active || repository == null || roomId == 0L) return@LaunchedEffect
        if (state is EmoteLoadState.Ready && retryNonce == 0) return@LaunchedEffect
        state = EmoteLoadState.Loading
        state = when (val result = repository.loadLiveEmotes(roomId, forceRefresh = retryNonce > 0)) {
            is BiliResult.Ok -> EmoteLoadState.Ready(result.value)
            else -> EmoteLoadState.Failed(result.errorTextRes("加载直播表情"))
        }
    }
    return state to { retryNonce++ }
}

/** 输入框旁的「表情 / 键盘」切换按钮。没有提供 [LocalEmoteRepository] 时(如单测与预览)自动隐藏。 */
@Composable
fun EmoteToggleButton(
    open: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (LocalEmoteRepository.current == null) return
    IconButton(onClick = onToggle, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = if (open) Icons.Outlined.Keyboard else Icons.Outlined.SentimentSatisfied,
            contentDescription = stringResource(if (open) Res.string.emote_keyboard else Res.string.emote_open),
            tint = if (open) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * 评论区、私信与直播间共用的表情面板。
 *
 * 所有表情图走 [BiliAsyncImage](带 Referer,防 403);分区靠容器色阶,不画分割线。
 */
@Composable
fun EmotePickerPanel(
    state: EmoteLoadState,
    onRetry: () -> Unit,
    onSelect: (EmoteAction) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = DefaultEmotePanelHeight,
    /** 贴屏幕物理底边时(全屏输入浮层)给系统手势小横条让出的底部安全高度。 */
    bottomInset: Dp = 0.dp,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth().height(height + bottomInset),
    ) {
        when (state) {
            EmoteLoadState.Idle, EmoteLoadState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(bottom = bottomInset),
                contentAlignment = Alignment.Center,
            ) {
                LoadingSpinner()
            }

            is EmoteLoadState.Failed -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = Spacing.Comfortable,
                        end = Spacing.Comfortable,
                        top = Spacing.Comfortable,
                        bottom = Spacing.Comfortable + bottomInset,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                ) {
                    Text(
                        text = stringResource(state.message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }
            }

            is EmoteLoadState.Ready -> {
                if (state.tabs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(bottom = bottomInset),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.emote_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    EmoteTabsContent(tabs = state.tabs, onSelect = onSelect, bottomInset = bottomInset)
                }
            }
        }
    }
}

@Composable
private fun EmoteTabsContent(
    tabs: List<EmoteTab>,
    onSelect: (EmoteAction) -> Unit,
    bottomInset: Dp,
) {
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val tabRowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val hasTabBar = tabs.size > 1

    LaunchedEffect(pagerState.currentPage) {
        tabRowState.animateScrollToItem((pagerState.currentPage - 2).coerceAtLeast(0))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .touchOnlyPaging(),
        ) { page ->
            val tab = tabs[page]
            EmoteGrid(
                tab = tab,
                onSelect = onSelect,
                extraBottomPadding = if (hasTabBar) 0.dp else bottomInset,
            )
        }
        if (hasTabBar) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                LazyRow(
                    state = tabRowState,
                    modifier = Modifier.padding(bottom = bottomInset),
                    contentPadding = PaddingValues(horizontal = Spacing.Tight, vertical = Spacing.Hair),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    itemsIndexed(tabs, key = { _, tab -> tab.id }) { index, tab ->
                        val selected = pagerState.currentPage == index
                        val containerColor = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            Color.Transparent
                        }
                        val contentColor = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .heightIn(min = TabBarChipHeight)
                                .clip(MaterialTheme.shapes.small)
                                .background(containerColor)
                                .clickable(role = Role.Tab) {
                                    scope.launch { pagerState.animateScrollToPage(index) }
                                }
                                .padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
                        ) {
                            if (tab.iconUrl.isNotEmpty()) {
                                BiliAsyncImage(
                                    url = tab.iconUrl,
                                    contentDescription = tab.name.ifBlank { null },
                                    modifier = Modifier.size(TabIconSize),
                                )
                            } else {
                                Text(
                                    text = tab.name.ifBlank { stringResource(Res.string.emote_kaomoji) },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = contentColor,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmoteGrid(
    tab: EmoteTab,
    onSelect: (EmoteAction) -> Unit,
    extraBottomPadding: Dp = 0.dp,
) {
    val minCellWidth = when (tab.kind) {
        EmoteTabKind.SmallImage -> SmallEmoteCellMinWidth
        EmoteTabKind.LargeImage -> LargeEmoteCellMinWidth
        EmoteTabKind.Kaomoji -> KaomojiCellMinWidth
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = minCellWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.Tight,
            end = Spacing.Tight,
            top = Spacing.Tight,
            bottom = Spacing.Tight + extraBottomPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        items(tab.items, key = { it.key }) { item ->
            when (tab.kind) {
                EmoteTabKind.Kaomoji -> KaomojiCell(item, onSelect)
                EmoteTabKind.SmallImage -> SmallEmoteCell(item, onSelect)
                EmoteTabKind.LargeImage -> LargeEmoteCell(item, onSelect)
            }
        }
    }
}

@Composable
private fun SmallEmoteCell(item: EmoteEntry, onSelect: (EmoteAction) -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .heightIn(min = Dimens.MinTouchTarget)
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = item.enabled, role = Role.Button) { onSelect(item.action) }
            .alpha(if (item.enabled) 1f else LockedAlpha)
            .padding(Spacing.Hair),
    ) {
        BiliAsyncImage(
            url = item.imageUrl,
            contentDescription = item.label.ifBlank { null },
            modifier = Modifier.size(SmallEmoteImageSize),
        )
    }
}

@Composable
private fun LargeEmoteCell(item: EmoteEntry, onSelect: (EmoteAction) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair / 2),
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = item.enabled, role = Role.Button) { onSelect(item.action) }
            .alpha(if (item.enabled) 1f else LockedAlpha)
            .padding(vertical = Spacing.Hair, horizontal = Spacing.Hair / 2),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            BiliAsyncImage(
                url = item.imageUrl,
                contentDescription = item.label.ifBlank { null },
                modifier = Modifier.size(LargeEmoteImageSize),
            )
            if (!item.enabled) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = item.lockHint.ifBlank { null },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(LockBadgeSize),
                )
            }
        }
        val caption = if (!item.enabled && item.lockHint.isNotBlank()) item.lockHint else item.label
        if (caption.isNotBlank()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun KaomojiCell(item: EmoteEntry, onSelect: (EmoteAction) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .heightIn(min = KaomojiCellHeight)
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = item.enabled, role = Role.Button) { onSelect(item.action) },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

val DefaultEmotePanelHeight = 256.dp
private val TabBarChipHeight = 40.dp
private val TabIconSize = 24.dp
private val SmallEmoteCellMinWidth = 48.dp
private val SmallEmoteImageSize = 32.dp
private val LargeEmoteCellMinWidth = 76.dp
private val LargeEmoteImageSize = 52.dp
private val LockBadgeSize = 14.dp
private val KaomojiCellMinWidth = 96.dp
private val KaomojiCellHeight = 40.dp
private const val LockedAlpha = 0.38f

/** 按屏幕横竖方向在进程内共享最近一次真实落定过的软键盘净高度(dp),打开表情面板第一帧即对齐输入法高度。 */
private val ProcessSettledNetKeyboardDp = mutableMapOf<Int, Float>()

/**
 * 记住软键盘真正展开时的净高度(扣除导航条小横条),并在键盘收起动画途中冻结它,供表情面板 1:1 接棒。
 *
 * - **不设 360dp 的低上限**:高分屏手机带工具栏的输入法净高常在 340–420dp,截断会导致切表情时矮一截跳动;
 * - **只在键盘落定或正在升起(`target >= current`)时更新**,收起途中冻结不变。
 */
internal class SettledKeyboardHeightTracker(
    private val orientationKey: Int? = null,
    defaultHeightDp: Float = DefaultEmotePanelHeight.value,
    private val minRecognizeDp: Float = 120f,
    private val minPanelDp: Float = 180f,
    private val maxPanelDp: Float = 560f,
) {
    var settledDp: Float = orientationKey?.let { ProcessSettledNetKeyboardDp[it] } ?: defaultHeightDp
        private set

    /**
     * @param currentImeDp 当前这一帧扣除了导航栏小横条之后的净 IME 高度(dp)。
     * @param targetImeDp 系统报告的本次动画目标净 IME 高度(dp)。
     */
    fun onFrame(currentImeDp: Float, targetImeDp: Float): Float {
        val risingOrSettled = targetImeDp >= currentImeDp - 0.5f
        if (risingOrSettled && currentImeDp > minRecognizeDp) {
            val clamped = currentImeDp.coerceIn(minPanelDp, maxPanelDp)
            settledDp = clamped
            if (orientationKey != null) {
                ProcessSettledNetKeyboardDp[orientationKey] = clamped
            }
        }
        return settledDp
    }

    /** 外层已经由 `imePadding` 垫了 [currentImeDp] 时,表情面板需要补足的剩余高度(dp)。 */
    fun complementPanelDp(currentImeDp: Float, targetImeDp: Float): Float =
        (onFrame(currentImeDp, targetImeDp) - currentImeDp).coerceAtLeast(0f)
}

/**
 * 供外层已经消费了 `navigationBars` 且挂了 `imePadding` 的页面(私信、直播间)计算表情面板高度:
 * 软键盘收起途中补足差额,使「剩余 IME 垫高 + 表情面板高度」始终等于软键盘落定时的净高度。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberComplementEmotePanelHeight(): Dp {
    val density = LocalDensity.current
    val orientation = screenOrientationKey()
    val tracker = remember(orientation) { SettledKeyboardHeightTracker(orientationKey = orientation) }
    val navPx = WindowInsets.navigationBars.getBottom(density)
    val netCurrentDp = with(density) { (WindowInsets.ime.getBottom(density) - navPx).coerceAtLeast(0).toDp().value }
    val netTargetDp = with(density) { (WindowInsets.imeTarget.getBottom(density) - navPx).coerceAtLeast(0).toDp().value }
    return tracker.complementPanelDp(netCurrentDp, netTargetDp).dp
}
