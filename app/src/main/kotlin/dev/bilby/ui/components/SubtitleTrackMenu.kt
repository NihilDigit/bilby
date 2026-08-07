package dev.bilby.ui.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import dev.bilby.R
import dev.bilby.player.SubtitleTrack

/**
 * 字幕轨下拉菜单:一条「关闭字幕」+ 可用轨清单。看视频的控制条(`BilbyPlayer.SubtitleButton`)
 * 和听视频封面右上角的按钮(`ListenScreen`)共用这一份——两处各写一份迟早会各自漂移
 * (菜单项顺序、选中态标记这类细节没有理由长成两样)。
 *
 * 只管菜单内容,不管触发它的按钮长什么样:两处的按钮外观差得远(控制条上是图标+可选文字,
 * 封面上是浮在图片上的圆按钮),硬凑成一个组件只会得到一个到处是 if 的壳。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleTrackMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    tracks: List<SubtitleTrack>,
    currentLan: String,
    onSelect: (String) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.player_subtitle_off)) },
            onClick = { onDismissRequest(); onSelect("") },
            trailingIcon = if (currentLan.isEmpty()) subtitleSelectedMark else null,
        )
        tracks.forEach { track ->
            DropdownMenuItem(
                text = { Text(track.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = { onDismissRequest(); onSelect(track.lan) },
                trailingIcon = if (track.lan == currentLan) subtitleSelectedMark else null,
            )
        }
    }
}

private val subtitleSelectedMark: @Composable () -> Unit = {
    Text("·", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
}
