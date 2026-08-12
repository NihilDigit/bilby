package dev.bilby.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.ui.theme.Spacing

/**
 * 播放量 / 弹幕数这类计数,图标 + 数字成对出现。
 *
 * 以前是拼成一句 `"12.3万播放 · 888弹幕 · 3小时前"` 的字符串。换成图标是照 PiliPlus 的
 * `common/widgets/stat/stat.dart` 做的,换来两件事:
 *
 * - **中文标签本身占的宽度和数字一样多**。"播放""弹幕"各两个汉字 ≈ 24dp,而一行元信息
 *   总共只有一百多 dp;图标 14dp 就说完同一件事,省下的宽度让日期不再被挤掉。
 * - **数字之间不再需要分隔点**。图标自己就是分隔,`·` 那一串在窄屏上会先于内容换行。
 *
 * 颜色统一取 `outline`(PiliPlus 也是),不是 `onSurfaceVariant`:这一行是列表里优先级
 * 最低的信息,和它上面的 UP 主名再拉开一档,扫列表时视线不会被数字勾住。
 */
private val StatIconSize = 14.dp

@Composable
fun StatRow(
    modifier: Modifier = Modifier,
    playText: String? = null,
    danmakuText: String? = null,
    dateText: String? = null,
    color: Color = MaterialTheme.colorScheme.outline,
) {
    if (playText == null && danmakuText == null && dateText == null) return
    CompositionLocalProvider(LocalContentColor provides color) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            playText?.let { Stat(Icons.Outlined.PlayCircle, stringResource(R.string.stat_play), it) }
            // 弹幕数用自己那个字形,不用字幕框([BilbyIcons])。这一行常常和播放页的控制条同屏,
            // 而那上面的字幕框说的是字幕 —— 同一个符号在一屏里说两件事。
            danmakuText?.let { Stat(BilbyIcons.Danmaku, stringResource(R.string.stat_danmaku), it) }
            dateText?.let {
                Text(text = it, style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
    }
}

@Composable
private fun Stat(icon: ImageVector, label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // contentDescription 给的是"播放"而不是 null:这里没有可见的中文标签,
        // 读屏只念一个数字说明不了它是什么。
        Icon(icon, contentDescription = label, modifier = Modifier.size(StatIconSize))
        Text(text = value, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * 计数折算。分档除数取自资源:中文按万/亿,英文按 K/M —— 只翻译单位后缀会让英文差一个量级。
 *
 * **整个应用只有这一份。** 它此前在 VideoTabs、SpaceScreen、DynamicCardView、SearchChatScreen
 * 里各有一份逐字相同的私有拷贝,于是"计数怎么显示"这件事没有可改的地方:改一处剩下三处照旧,
 * 而它们出现在同一屏上(动态流里的视频行和播放页的计数行)。
 */
@Composable
fun formatCount(value: Long): String {
    val large = integerResource(R.integer.count_divisor_large)
    val small = integerResource(R.integer.count_divisor_small)
    return when {
        value >= large -> stringResource(R.string.count_large, value.toDouble() / large)
        value >= small -> stringResource(R.string.count_small, value.toDouble() / small)
        else -> value.toString()
    }
}
