package dev.bilby.ui.video

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.player.EpisodeList
import dev.bilby.ui.player.EpisodeRow
import dev.bilby.ui.player.EpisodeTarget
import dev.bilby.ui.player.PlayerSidePanel
import dev.bilby.ui.player.QueueEdges
import dev.bilby.ui.theme.Spacing

/**
 * 全屏下的切集面板。外壳(右侧划出、遮罩、返回先关)是 [PlayerSidePanel],和播放设置面板
 * 同一层。
 *
 * **点一条即关。** 目的达成之后这块面板挡的是画面本身,而全屏的全部意义就是画面。连续切几集
 * 的场合有,但比"切一集接着看"少得多,为它留着一块常驻的遮挡不划算。
 */
@Composable
fun BoxScope.EpisodePanel(
    visible: Boolean,
    rows: List<EpisodeRow>,
    sourceLabel: String,
    onSelect: (EpisodeTarget) -> Unit,
    onDismiss: () -> Unit,
    edges: QueueEdges? = null,
    playing: Boolean = false,
) {
    PlayerSidePanel(visible = visible, onDismiss = onDismiss) {
        // 队列来源那一行。**这里不给目录入口** —— 目录是另一个页面,而全屏下离开这一页
        // 要先退出全屏,一个点了就把人踢出全屏的链接不如不给。
        Text(
            text = sourceLabel.ifEmpty { stringResource(Res.string.video_parts) },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                start = Spacing.Comfortable,
                end = Spacing.Comfortable,
                top = Spacing.Comfortable,
                bottom = Spacing.Hair,
            ),
        )
        EpisodeList(
            rows = rows,
            edges = edges,
            playing = playing,
            onSelect = {
                onSelect(it)
                onDismiss()
            },
            contentPadding = PaddingValues(
                horizontal = Spacing.Tight,
                vertical = Spacing.Tight,
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 面板里没有可切的东西时不给入口。单条队列且单 P 的视频就是这种。 */
fun List<EpisodeRow>.hasSomethingToSwitch(): Boolean =
    size > 1 || firstOrNull()?.parts?.isNotEmpty() == true
