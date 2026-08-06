package dev.bilby.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/**
 * 一条视频在列表里的样子。
 *
 * 动态、搜索结果、空间投稿、稍后再看、助理答案 —— 五处以前各写了一份几乎一样的 Row,
 * 封面宽度、行距、截断行数各差一点点,滑过去能看出接缝。合并成这一个。
 *
 * 参数是扁平的展示字段而不是某个 data 层模型:五个调用方的模型各不相同
 * (FeedItem / SearchVideo / SpaceVideoItem / ToViewItem / AnswerItem),
 * 让 UI 组件认识其中任何一个都会把 data 层的形状焊进视图层。
 */
@Immutable
data class VideoRowUi(
    val title: String,
    val coverUrl: String,
    val durationText: String = "",
    /** UP 主名。空间页里整页都是同一个 UP,这行就该省掉,不是留空。 */
    val upName: String? = null,
    /** "12.3万播放 · 888弹幕 · 3小时前" 这类一行元信息。 */
    val meta: String? = null,
    /** 看过的比例,0..1。null 表示没看过或算不出时长。 */
    val progressFraction: Float? = null,
    /**
     * 第三行的补充说明,颜色跟着 [accentNote] 走。
     * 助理结果的"推荐理由"用它 —— 那句话是助理结果与推荐流的根本区别,永远显示。
     */
    val note: String? = null,
    /** note 是否用强调色。理由用 primary(它是这条为什么在这),状态文案用次级色。 */
    val accentNote: Boolean = false,
)

/**
 * @param trailing 行尾的操作(稍后再看的删除按钮)。放在这里而不是让调用方套一层 Row,
 *   是为了保证操作按钮和文字块的对齐在所有列表里一致。
 */
@Composable
fun VideoRow(
    item: VideoRowUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        verticalAlignment = Alignment.Top,
    ) {
        ListCover(
            url = item.coverUrl,
            durationText = item.durationText,
            progressFraction = item.progressFraction,
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.upName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.meta?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (item.accentNote) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        trailing?.invoke(this)
    }
}

/**
 * 队列、合集分集这类次级列表的紧凑行。比 [VideoRow] 矮一半,因为它出现在已经有主内容的
 * 页面里(播放页下半屏、听视频页下半屏),不该和主列表抢视觉重量。
 */
@Composable
fun CompactVideoRow(
    title: String,
    coverUrl: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        // 选中态用 secondaryContainer:M3 把它定义为"导航选中指示"这类不争夺注意力的选中填充,
        // primaryContainer 在一屏几十条的队列里太响。
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.Tight),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 外圆角 8dp(shapes.small)减去 8dp 内边距 = 4dp:嵌套的两层用同一个半径时,
            // 内层的角看上去反而比外层更方(M3 shape 页管这叫 optical roundness)。
            ListCover(
                url = coverUrl,
                width = Dimens.CompactCoverWidth,
                cornerRadius = 4.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
