package dev.bilby.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.R
import dev.bilby.formatDurationSeconds
import dev.bilby.data.VideoPart
import dev.bilby.player.QueueItem
import dev.bilby.ui.components.CompactVideoRow
import dev.bilby.ui.theme.Spacing
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
    currentCid: Long,
): List<EpisodeRow> = queue.map { item ->
    val isCurrent = item.bvid == currentBvid
    EpisodeRow(
        bvid = item.bvid,
        title = item.title,
        upName = item.upName,
        coverUrl = item.coverUrl,
        durationSeconds = item.durationSeconds,
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
) {
    val currentIndex = remember(rows) { rows.currentIndex() }

    // 列表按自然顺序摆着不动,切集时让高亮那条滚到中间。随机播放不重排列表——列表跟着重排
    // 会让人找不到刚才看的那条在哪。
    //
    // 居中而不是"滚到可见":队列是当前视频前后各一段,只滚到可见的话它会贴在顶或底,
    // 看不出前后还有多少。
    LaunchedEffect(currentIndex, rows.size) {
        if (currentIndex < 0) return@LaunchedEffect
        // 展开动画期间第一帧 layoutInfo 可能是空的,等布局真的跑过一次再滚,否则滚动扑空、
        // 居中那步被整个跳过。
        snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
        state.scrollToItem(currentIndex)
        val info = state.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == currentIndex } ?: return@LaunchedEffect
        state.scrollToItem(currentIndex, -(info.viewportSize.height - row.size) / 2)
    }

    LazyColumn(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        rows.forEach { row ->
            item(key = row.bvid) {
                CompactVideoRow(
                    title = row.title,
                    coverUrl = row.coverUrl,
                    // 时长优先于 UP 名:这份清单里绝大多数条目是同一个 UP 的,重复印一样的
                    // 名字占掉的正是能读出"这条多长"的那一行。取不到时长才退回 UP 名 ——
                    // 系列与动态两条来源的窗口条目没有时长(见 QueueSourceRepository)。
                    subtitle = if (row.durationSeconds > 0) {
                        formatDurationSeconds(row.durationSeconds)
                    } else {
                        row.upName.takeIf { it.isNotEmpty() }
                    },
                    selected = row.isCurrent,
                    onClick = { onSelect(EpisodeTarget.Video(row.bvid)) },
                )
            }
            // 二级:当前这条的分 P。**key 带上 bvid**,否则连播走到另一条多 P 视频时,
            // 两组分 P 的 cid 都是 Long,复用会把上一条的选中态带过来。
            items(row.parts, key = { "${row.bvid}-${it.cid}" }) { part ->
                PartListItem(part = part, onClick = { onSelect(EpisodeTarget.Part(part.cid)) })
            }
        }
    }
}

/**
 * 二级行。**缩进 + 只有文字**,不给封面:分 P 共用整条视频的一张封面,每行印一遍等于一列
 * 完全相同的图,而"这是同一条视频的内部结构"正是缩进已经说清的事。
 *
 * 当前这一 P 只换文字颜色,不加选中背景:上一级已经是一块 secondaryContainer,里面再套一块
 * 会让二级看起来比一级还重。这一条与 `VideoTabs.PartSheet` 的取舍相同。
 */
@Composable
private fun PartListItem(part: EpisodePart, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                stringResource(R.string.video_part_label, part.ordinal, part.title),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            headlineColor = if (part.isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = PartIndent)
            .selectable(selected = part.isCurrent, role = Role.Button, onClick = onClick),
    )
}

/** 二级行的缩进。对齐一级行封面右缘之后的文字,让两级读起来是同一条竖线上的父子关系。 */
private val PartIndent = Spacing.Spacious
