package dev.bilby.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
        val offSelected = currentLan.isEmpty()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.player_subtitle_off)) },
            onClick = { onDismissRequest(); onSelect("") },
            trailingIcon = if (offSelected) subtitleSelectedMark else null,
            modifier = Modifier.selectedSemantics(offSelected),
        )
        tracks.forEach { track ->
            val trackSelected = track.lan == currentLan
            DropdownMenuItem(
                text = { Text(track.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = { onDismissRequest(); onSelect(track.lan) },
                trailingIcon = if (trackSelected) subtitleSelectedMark else null,
                modifier = Modifier.selectedSemantics(trackSelected),
            )
        }
    }
}

/**
 * 选中标记。**勾而不是「·」**:小圆点既不像选中态,读屏还会把它当成一个标点节点念出来。
 * 勾 + 主色是两条通道,色觉障碍下也读得出哪一条在用。
 *
 * 图标本身 `contentDescription = null` —— 选中态由行上的 [selectedSemantics] 说,
 * 两处都说会让读屏在同一行里念两遍。
 */
private val subtitleSelectedMark: @Composable () -> Unit = {
    Icon(
        Icons.Filled.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
    )
}

/** 选中态挂在整行上,不挂在那个勾上:读屏念的是「关闭字幕,已选中」,而不是孤零零一个图标。 */
private fun Modifier.selectedSemantics(isSelected: Boolean) = semantics { selected = isSelected }
