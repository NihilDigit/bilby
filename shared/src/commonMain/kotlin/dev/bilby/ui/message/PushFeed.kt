package dev.bilby.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import dev.bilby.ui.theme.Dimens
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.bilby.ui.components.BiliRichText
import dev.bilby.ui.components.ListCover
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.bilby.data.WhisperContent
import dev.bilby.formatDurationSeconds
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveListContent
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FirstScreenState
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.PrefetchNearEnd
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 从「UP 主推送」进来的会话:这位 UP 推过来的投稿,**最新的在上**,按月分段。
 *
 * 不画成聊天。推送是服务端代发的投稿通知,不是一来一回的对话;人来这里是扫一眼最近发了什么、
 * 挑一条点开,聊天那种最新沉在底部、要往上翻的读法和这件事相反。每一条就是全应用的视频行,
 * 附言(UP 主写给关注者的那一句)在第三行。
 *
 * 会话里夹着的文字消息不在这里:顶栏右端进完整对话,那里是正常的聊天。
 */
@Composable
internal fun PushFeedScreen(
    state: WhisperUiState,
    onRetry: () -> Unit,
    onLoadOlder: () -> Unit,
    onOpenSpace: () -> Unit,
    onOpenVideo: (String) -> Unit,
    /** 附言里的链接。 */
    onOpenLink: (String) -> Unit,
    onOpenFullChat: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            WhisperTopBar(
                name = state.name,
                faceUrl = state.faceUrl,
                onOpenSpace = if (state.isSystem) null else onOpenSpace,
                onBack = onBack,
            ) {
                IconButton(onClick = onOpenFullChat) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Chat,
                        contentDescription = stringResource(Res.string.whisper_open_full_chat),
                    )
                }
            }
        },
    ) { insets ->
        // 消息是按时间正序存的;推送只取投稿那几条,倒过来。
        val pushes = remember(state.messages) {
            state.messages.mapNotNull { message ->
                (message.content as? WhisperContent.VideoPush)?.let { PushItem(it, message.seqno, message.timeSeconds) }
            }.asReversed()
        }
        AdaptiveListContent(
            modifier = Modifier.padScaffoldExceptBottom(insets),
            maxCellWidth = PushCellMaxWidth,
        ) { columns ->
            FirstScreenState(
                loading = state.loading,
                error = state.error?.let { stringResource(it) },
                // 读回来的这一段里一条推送都没有、而更早的还有,不算空:触底预取会接着往回翻。
                isEmpty = pushes.isEmpty() && !state.hasOlder,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
                skeleton = { FullScreenLoading() },
            ) {
                if (pushes.isEmpty() && !state.hasOlder) {
                    EmptyState(stringResource(Res.string.push_feed_empty), Modifier.fillMaxSize())
                    return@FirstScreenState
                }
                PushGrid(state, pushes, columns, onLoadOlder, onOpenVideo, onOpenLink)
            }
        }
    }
}

private class PushItem(val push: WhisperContent.VideoPush, val seqno: Long, val timeSeconds: Long)

/**
 * 一次推送。样子同全应用的视频行(封面在左、同样的边距),但**标题与附言都不截断**:
 * 视频行是给整齐的列表用的,行数固定;这里一屏只有几条,附言又是 UP 写给关注者的整句话,
 * 截成两行加省略号就只剩半句。附言按富文本画,里面的表情是图,不是 `[UPOWER_…]` 这种代码。
 *
 * 附言左边一道竖线,是引用的写法:读得出这是 UP 说的话,而不是这条视频的简介。
 */
@Composable
private fun PushRow(item: PushItem, onClick: () -> Unit, onOpenLink: (String) -> Unit) {
    val push = item.push
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    // 封面跟着这一格的宽度放大,同对话里的卡片(见 WhisperScreen 的 ChatSizes):一格五六百 dp
    // 宽时还是 128dp 的封面,右边一大片字,封面反倒成了角落里的缩略图。下限是视频行的原值,手机上不变。
    val coverWidth = (maxWidth * 0.38f).coerceIn(Dimens.ListCoverWidth, PushCoverMaxWidth)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
    ) {
        ListCover(
            url = push.coverUrl,
            durationText = if (push.durationSeconds > 0) formatDurationSeconds(push.durationSeconds) else "",
            width = coverWidth,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            Text(text = push.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = formatChatTime(item.timeSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (push.note.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = Spacing.Hair).height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                ) {
                    Box(
                        modifier = Modifier
                            .width(QuoteBarWidth)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                    BiliRichText(
                        spans = push.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        onLinkClick = onOpenLink,
                        onMentionClick = {},
                    )
                }
            }
        }
    }
    }
}

private val QuoteBarWidth = 3.dp

/** 推送一格最宽多少。比视频行的 480 宽一档:封面要跟着放大,标题和附言又不截断,需要这一截宽度。 */
private val PushCellMaxWidth = 640.dp

/** 封面放大的上限,右栏两列时大约落在这里。 */
private val PushCoverMaxWidth = 240.dp

@Composable
private fun PushGrid(
    state: WhisperUiState,
    pushes: List<PushItem>,
    columns: GridCells,
    onLoadOlder: () -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val gridState = rememberLazyGridState()
    // 列表的尾部就是更早的推送,触底往回翻。
    PrefetchNearEnd(
        gridState,
        canLoad = state.hasOlder && !state.loadingOlder && state.olderError == null,
        onLoadMore = onLoadOlder,
    )
    val zone = remember { ZoneId.systemDefault() }
    val sections = remember(pushes) {
        pushes.groupBy { YearMonth.from(Instant.ofEpochSecond(it.timeSeconds).atZone(zone)) }
    }
    val monthPattern = stringResource(Res.string.push_feed_month)
    val monthYearPattern = stringResource(Res.string.push_feed_month_year)
    val thisYear = remember { LocalDate.now(zone).year }
    LazyVerticalGrid(
        columns = columns,
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = navigationBarsBottom()),
    ) {
        sections.forEach { (month, items) ->
            // 月份吸顶:往下翻的时候始终知道翻到了哪一段,不必回头找日期。今年的省掉年份,
            // 同 formatChatTime。
            stickyHeader(key = "month-$month") {
                Surface(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = month.format(
                            DateTimeFormatter.ofPattern(if (month.year == thisYear) monthPattern else monthYearPattern),
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(
                            start = Spacing.Comfortable,
                            end = Spacing.Comfortable,
                            top = Spacing.Cozy,
                            bottom = Spacing.Tight,
                        ),
                    )
                }
            }
            items(items, key = { it.seqno }) { item ->
                PushRow(item, onClick = { onOpenVideo(item.push.bvid) }, onOpenLink = onOpenLink)
            }
        }
        item(key = "older", span = { GridItemSpan(maxLineSpan) }) {
            ListFooter(
                appending = state.loadingOlder,
                hasMore = state.hasOlder,
                hasItems = pushes.isNotEmpty(),
                error = state.olderError?.let { stringResource(it) },
                onRetry = onLoadOlder,
            )
        }
    }
}
