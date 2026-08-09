package dev.bilby.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Comment
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing

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

/**
 * 内嵌播放画面左上角的返回。**视频页和直播间共用一份**。
 *
 * 内嵌时 [PlayerShell] 只在全屏态给顶栏,而画面往往是这一页的第一屏内容,从动态或搜索点进来
 * 的人只剩系统返回可用。渐变是必需的而不是装饰:封面是 UP 主上传的任意图片,纯白封面上一个
 * 白箭头看不见 —— 同一条理由见风格指南 §1.2 的 `ScrimOnMedia`。
 *
 * 它**不跟控件一起自动隐藏**:自动隐藏的是"播放器现在在做什么"那一类控件,而离开这一页是
 * 页面级动作,藏起来就等于没有。
 */
@Composable
internal fun BoxScope.MediaBackButton(onBack: () -> Unit, onShare: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(MediaScrimHeight)
            .background(Brush.verticalGradient(listOf(FixedColors.ScrimOnMedia, Color.Transparent))),
    )
    IconButton(
        onClick = onBack,
        modifier = Modifier.align(Alignment.TopStart).padding(Spacing.Tight),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.action_back),
            tint = FixedColors.OnMedia,
        )
    }
    // 分享和返回一样是页面级动作,所以对称地摆在这条渐变的另一端,而不是塞进四格动作栏
    // —— 那一行是「对这条视频表态」(赞/币/藏/稍后再看),分享不是表态,而且 M3 也说一处
    // 按钮不超过三个。
    onShare?.let { share ->
        IconButton(
            onClick = share,
            modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.Tight),
        ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = stringResource(R.string.action_share),
                tint = FixedColors.OnMedia,
            )
        }
    }
}

/** 够盖住一个 48dp 触摸目标再加一段渐隐,不到画面高度的四分之一。 */
private val MediaScrimHeight = 72.dp

/**
 * 手势反馈浮层。**画面中央一个框,图标 + 一句话,四种手势共用这一份。**
 *
 * 以前是各画各的:长按加速贴在顶部、音量亮度只有一行百分比、双击快进又是另一段文字。
 * 手势本身是"同一类东西"(按住画面调整某个量),反馈却在三个位置以三种样子出现,用户要
 * 分别学三次。合并之后位置固定在正中,眼睛不用找。
 *
 * 图标不是装饰:百分比数字回答不了"这是音量还是亮度",而横屏时手指正压在半屏上,
 * 文字很容易被挡住一半。
 */
@Composable
internal fun BoxScope.PlayerHintOverlay(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Overlay(modifier = modifier.align(Alignment.Center)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = FixedColors.OnMedia,
                modifier = Modifier.size(HintIconSize),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = FixedColors.OnMedia,
            )
        }
    }
}

/** 比正文大一档,和 titleMedium 的行高对得上。 */
private val HintIconSize = 22.dp

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
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = Dimens.MinTouchTarget)
            .padding(horizontal = Spacing.Tight),
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
                modifier = Modifier.padding(start = Spacing.Hair),
            )
        }
    }
}
