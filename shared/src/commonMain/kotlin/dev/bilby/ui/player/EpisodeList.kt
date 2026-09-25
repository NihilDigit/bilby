package dev.bilby.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.bilby.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.resources.*
import dev.bilby.formatDurationSeconds
import dev.bilby.data.VideoPart
import dev.bilby.player.QueueItem
import dev.bilby.ui.components.ListCover
import dev.bilby.ui.components.PlayingIndicator
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * 切集能落到的目标。**两种,因为队列和分 P 是两条不同的命令**:跳队列是标准 Player 命令
 * (`seekTo(index, 0)`),换分 P 是同稿件内的自定义命令
 * ([dev.bilby.player.AudioPlaybackService.ACTION_PLAY_PART])。
 *
 * 分派收在一处([EpisodeRow] 的调用方给的那个 `onSelect`),三种形态因此不必各写一遍
 * "这一下该发哪条命令"。
 */
sealed interface EpisodeTarget {

    /** 队列里的另一条视频。 */
    @JvmInline
    value class Video(val bvid: String) : EpisodeTarget

    /** 当前这条视频的另一 P。 */
    @JvmInline
    value class Part(val cid: Long) : EpisodeTarget
}

/** 二级条目:当前这条视频的一个分 P。 */
data class EpisodePart(
    val cid: Long,
    /** 展示用的 P 号,来自详情的 `page`,不是列表下标。 */
    val ordinal: Int,
    val title: String,
    val isCurrent: Boolean,
)

/**
 * 一级条目:队列里的一条视频。
 *
 * **[parts] 只有正在播的那一条可能非空。** 分 P 清单来自视频详情,而页面手上只有当前这条的
 * 详情;队列里其余各条要各打一次请求才知道有几 P,不值得为一个多数条目用不上的二级列表在
 * 打开面板时先发十几个请求。缓存面板早就是这么做的(见 `ui/video/OfflineCacheSheet.kt`),
 * 这里沿用同一条约束,不发明第二种。
 *
 * 单 P 视频的 [parts] 同样是空:一个只有 P1 的二级列表是纯噪声。
 */
data class EpisodeRow(
    val bvid: String,
    val title: String,
    val upName: String,
    val coverUrl: String,
    val durationSeconds: Long,
    val isCurrent: Boolean,
    val parts: List<EpisodePart> = emptyList(),
)

/**
 * 把队列与当前这条的分 P 拼成一份切集清单。**三种形态(详情页、听视频、全屏)共用这一份**,
 * 差别只在怎么画:详情页把 [EpisodeRow.parts] 摊成一排 chip,另外两种把它摊成二级列表。
 *
 * @param queue 队列的自然顺序,就是 playlist 的顺序(随机只改播放顺序,不重排列表)。
 * @param currentBvid 队列指着的那一条。
 * @param pageBvid 页面手上这份详情属于谁。**它和 [currentBvid] 不总是同一条**:自动连播走到
 *   下一条时页面要过一会儿才跟上去(见 `MainActivity` 的 `VideoRoute`),这段窗口里详情里的
 *   分 P 属于上一条视频。
 * @param parts 页面这份详情的分 P 清单。
 * @param pageDurationSeconds 页面这份详情的时长。起播时装进队列的那一条是临时条目,时长是 0,
 *   而补全队列时它原样留着不换(换了就是重新取流,见 AudioPlaybackService.fillQueueAround),
 *   于是当前这条永远没有时长。对得上身份时拿详情里的补上。
 * @param currentCid 播放器此刻真正装着的那一 P,由调用方按 `loadKey` 对过身份。
 *
 * **对不上身份时这条轴整个不存在,而不是点了没反应。** 拿本页的 cid 去切另一条视频的分 P,
 * 服务端回 -404,而那个错误离原因很远;原先的写法是在点击处加一道 `playerHoldsThisPage()`
 * 守卫,于是那段窗口里 chip 画得出来、点下去却什么都不发生。「没有这一栏」和「有这一栏但
 * 点不动」是两种不同的正确,这里取前者。
 */
fun buildEpisodeRows(
    queue: List<QueueItem>,
    currentBvid: String?,
    pageBvid: String?,
    parts: List<VideoPart>,
    pageDurationSeconds: Long,
    currentCid: Long,
): List<EpisodeRow> = queue.map { item ->
    val isCurrent = item.bvid == currentBvid
    EpisodeRow(
        bvid = item.bvid,
        title = item.title,
        upName = item.upName,
        coverUrl = item.coverUrl,
        durationSeconds = item.durationSeconds.takeIf { it > 0 }
            ?: if (item.bvid == pageBvid) pageDurationSeconds else 0L,
        isCurrent = isCurrent,
        parts = if (isCurrent && item.bvid == pageBvid && parts.size > 1) {
            parts.map { part ->
                EpisodePart(
                    cid = part.cid,
                    ordinal = part.index,
                    title = part.title,
                    isCurrent = part.cid == currentCid,
                )
            }
        } else {
            emptyList()
        },
    )
}

/**
 * 队列两头还能不能续、正不正在续,以及续的动作(docs/queue-redesign.md 决定 4)。
 * 滚到一头就往那头续,人不必找一个"加载更多"按钮。
 */
class QueueEdges(
    val canLoadBefore: Boolean,
    val canLoadAfter: Boolean,
    val loadingBefore: Boolean,
    val loadingAfter: Boolean,
    val onLoad: (before: Boolean) -> Unit,
)

/** 正在播的那一条在清单里的下标,没有则 -1。滚动定位与「N / M」都用它。 */
fun List<EpisodeRow>.currentIndex(): Int = indexOfFirst { it.isCurrent }

/**
 * 切集清单:一列视频,正在播的那一条底下摊开它的分 P。
 *
 * **一个列表,不是两个并列的列表。** 队列和分 P 确实是两条不同的轴(不同 bvid vs 同一 bvid
 * 的不同 cid),但它们在界面上回答的是同一个问题——现在放的是哪一条,以及可以换到哪去。
 * 摆成两块时"走到第几条"和"放到第几 P"是两个高亮,人要自己在两块之间对位;摊成二级之后
 * 缩进本身就说明了分 P 属于哪一条,不会被读成"分 P 也是队列的一部分"。
 *
 * 听视频的队列 Sheet 与全屏的切集面板共用它。详情页不用:那里有整屏宽度,分 P 横排成一条
 * 可滚的 chip 带扫得更快(见 `ui/video/VideoTabs.kt` 的 `PartRow`),而横向空间正是另外两种
 * 形态没有的。
 */
@Composable
fun EpisodeList(
    rows: List<EpisodeRow>,
    onSelect: (EpisodeTarget) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: LazyListState = rememberLazyListState(),
    /** 队列面板才有;没有时两头不续。 */
    edges: QueueEdges? = null,
    /** 正在放还是停着,决定当前那条尾部的指示跳不跳。 */
    playing: Boolean = false,
) {
    val currentIndex = remember(rows) { rows.currentIndex() }
    val currentBvid = rows.getOrNull(currentIndex)?.bvid

    // 列表按自然顺序摆着不动,切集时让高亮那条滚到中间。随机播放不重排列表——列表跟着重排
    // 会让人找不到刚才看的那条在哪。
    //
    // 居中而不是"滚到可见":队列是当前视频前后各一段,只滚到可见的话它会贴在顶或底,
    // 看不出前后还有多少。
    //
    // **只在当前条换了时居中,不看条数。** 两头续取会改条数,按条数重新居中的话,人往上翻到
    // 头、续回来一页,列表就被拽回当前条,永远翻不过去。往前插入的条目不会挪动视野:条目
    // 按 bvid 作 key,LazyColumn 守住的是第一个可见条目,不是下标。
    LaunchedEffect(currentBvid) {
        if (currentIndex < 0) return@LaunchedEffect
        // 展开动画期间第一帧 layoutInfo 可能是空的,等布局真的跑过一次再滚,否则滚动扑空、
        // 居中那步被整个跳过。
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        // 顶上那行续取进度也占一个下标。
        val target = currentIndex + if (edges?.loadingBefore == true) 1 else 0
        state.scrollToItem(target)
        val info = state.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == target } ?: return@LaunchedEffect
        state.scrollToItem(target, -(info.viewportSize.height - row.size) / 2)
    }

    if (edges != null) {
        val latest by rememberUpdatedState(edges)
        LaunchedEffect(state) {
            snapshotFlow {
                val info = state.layoutInfo
                val visible = info.visibleItemsInfo
                val atTop = visible.firstOrNull()?.index == 0
                val atBottom = info.totalItemsCount > 0 && visible.lastOrNull()?.index == info.totalItemsCount - 1
                val wantBefore = atTop && latest.canLoadBefore && !latest.loadingBefore
                val wantAfter = atBottom && latest.canLoadAfter && !latest.loadingAfter
                wantBefore to wantAfter
            }.distinctUntilChanged().collect { (wantBefore, wantAfter) ->
                if (wantBefore) latest.onLoad(true)
                if (wantAfter) latest.onLoad(false)
            }
        }
    }

    // 视频与分 P 摊平成一组分段:分 P 是当前那条的内部结构,排在同一组里,首尾圆角才落在
    // 整份清单的两头,而不是分 P 前后各断开一次。
    val segmentCount = rows.size + rows.sumOf { it.parts.size }
    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        if (edges?.loadingBefore == true) {
            item(key = "loading-before") { EdgeProgress() }
        }
        var segment = 0
        rows.forEach { row ->
            val rowSegment = segment++
            item(key = row.bvid) {
                QueueRowItem(
                    row = row,
                    playing = playing,
                    index = rowSegment,
                    count = segmentCount,
                    onClick = { onSelect(EpisodeTarget.Video(row.bvid)) },
                )
            }
            // 二级:当前这条的分 P。**key 带上 bvid**,否则连播走到另一条多 P 视频时,
            // 两组分 P 的 cid 都是 Long,复用会把上一条的选中态带过来。
            val firstPartSegment = segment
            segment += row.parts.size
            itemsIndexed(row.parts, key = { _, part -> "${row.bvid}-${part.cid}" }) { i, part ->
                PartListItem(
                    part = part,
                    index = firstPartSegment + i,
                    count = segmentCount,
                    onClick = { onSelect(EpisodeTarget.Part(part.cid)) },
                )
            }
        }
        if (edges?.loadingAfter == true) {
            item(key = "loading-after") { EdgeProgress() }
        }
    }
}

/**
 * 队列里的一条视频。**详情页那几条预览、完整队列、听视频、全屏面板是同一种行**:四处原先两种
 * 长相,预览是分段列表,其余三处是 `CompactVideoRow`,点「查看全部」像是换了一个 app。
 *
 * 容器色是这里唯一改掉的默认值:分段列表项默认用 `surface`,是给放在带底色的分组背景上用的;
 * 这几处底下都是 surface 一档,照默认画,未选中的条目和底色分不开。
 *
 * 选中不只靠颜色(lists.md 的 Accessibility):正在播的那条尾部另有一个播放指示,放着时跳动。
 *
 * 时长压在封面右下角,不另占一行,也就不再退回 UP 名 —— 取不到时长的条目(系列与动态两条
 * 来源的窗口条目,见 QueueSourceRepository)角上空着。这份清单里绝大多数条目是同一个 UP 的,
 * 名字印一列也读不出什么。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueueRowItem(row: EpisodeRow, playing: Boolean, index: Int, count: Int, onClick: () -> Unit) {
    SegmentedListItem(
        selected = row.isCurrent,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap),
        // 居中,不用默认值:默认在条目高过 88dp 时把首尾元素顶到上沿(lists.md 规格表),两行标题
        // 加一行时长就过线,播放指示于是跑到右上角,而且只在那一条变成当前项时才露出来。
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = {
            ListCover(
                url = row.coverUrl,
                width = QueueCoverWidth,
                cornerRadius = QueueCoverCorner,
                durationText = if (row.durationSeconds > 0) formatDurationSeconds(row.durationSeconds) else "",
            )
        },
        trailingContent = if (row.isCurrent) {
            {
                PlayingIndicator(
                    active = playing,
                    contentDescription = stringResource(
                        if (playing) Res.string.video_queue_now_playing else Res.string.video_queue_paused,
                    ),
                    modifier = Modifier.size(Dimens.IconInline),
                )
            }
        } else {
            null
        },
    ) {
        Text(text = row.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 续取在飞。一行高的小圈,不占一条视频的高度:它只说"还有,在来的路上"。 */
@Composable
private fun EdgeProgress() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Tight),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(Dimens.IconInline), strokeWidth = 2.dp)
    }
}

/**
 * 二级行,和视频行同在一组分段里。**只有文字**,不给封面:分 P 共用整条视频的一张封面,每行
 * 印一遍等于一列完全相同的图。封面那一格空着留位,文字因此和上面视频标题落在同一条竖线上,
 * 读得出是父子。
 *
 * 当前这一 P 只换文字颜色,不走分段的选中态:上一级已经是一块 secondaryContainer,紧挨着再来
 * 一块会让二级看起来比一级还重。这一条与 `VideoTabs.PartSheet` 的取舍相同。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PartListItem(part: EpisodePart, index: Int, count: Int, onClick: () -> Unit) {
    SegmentedListItem(
        selected = false,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap)
            .semantics { selected = part.isCurrent },
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = { Spacer(modifier = Modifier.width(QueueCoverWidth)) },
    ) {
        Text(
            stringResource(Res.string.video_part_label, part.ordinal, part.title),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = if (part.isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 队列条目的封面宽度。16:9 下约 54dp 高,和两行标题齐平。 */
private val QueueCoverWidth = 96.dp

/** 队列条目封面的圆角。列表项自己的圆角在 4dp 到 16dp 之间变,封面取中间一档。 */
private val QueueCoverCorner = 8.dp
