package dev.bilby.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.ui.theme.FixedColors

/**
 * 控制条上的通用件。**视频与直播共用** —— 它们长在同一个 [PlayerShell] 的控制条 slot 里,
 * 各写一份的话两条控制条会慢慢长得不一样,而用户看到的是同一个播放器。
 */

/** 弹幕开关。开着时图标染主色,这是"当前状态"而不是"点了会怎样"。 */
@Composable
internal fun DanmakuButton(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isFullscreen: Boolean,
) {
    ControlButton(
        expanded = enabled,
        onClick = { onEnabledChange(!enabled) },
        label = null,
        icon = { tint ->
            Icon(
                Icons.AutoMirrored.Filled.Comment,
                stringResource(R.string.player_danmaku),
                tint = tint,
                modifier = Modifier.size(if (isFullscreen) 22.dp else 18.dp),
            )
        },
    )
}

/** 图标按钮,可选地在图标右边挂一小段文字(当前倍速、当前清晰度)。 */
@Composable
internal fun ControlButton(
    expanded: Boolean,
    onClick: () -> Unit,
    label: String?,
    icon: @Composable (Color) -> Unit,
) {
    val tint = if (expanded) MaterialTheme.colorScheme.primary else FixedColors.OnMedia
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 8.dp),
    ) {
        icon(tint)
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                // **不设宽度上限**:这里的标签是画质档名("1080P60"、"1080P 高码率"),
                // 截断之后两个档看起来一模一样,那正是这个标签唯一要回答的问题。
                // 只在全屏显示(内嵌时 label 传 null),横屏有的是宽度,不会挤掉别的控件。
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
