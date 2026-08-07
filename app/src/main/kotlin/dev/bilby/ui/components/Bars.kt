package dev.bilby.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.R
import dev.bilby.ui.theme.Spacing

/**
 * 顶栏。M3 的 small app bar:页面标题 + 最多两个动作。
 *
 * 以前每个页面自己拿 `WindowInsets.systemBars` 往内容上贴 padding,四个页面四种写法,
 * 状态栏下的留白各不相同,滚动时内容还会直接压到状态栏文字上。TopAppBar 自带 windowInsets
 * 处理和滚动时的容器色变化,这些不该每页重写一遍。
 *
 * 只用 small 这一档:medium/large flexible 是给"标题本身是内容"的页面用的(相册、文章),
 * Bilby 的每一页标题都只是个路牌,给它三行高度是浪费首屏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilbyTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    // AutoMirrored:supportsRtl 开着,RTL 语言下返回箭头必须翻过来。
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(),
        modifier = modifier,
    )
}

/**
 * 页面内的小节标题(播放队列、找相关)。用 titleSmall 而不是 labelLarge:
 * label 那一档是给控件里的文字用的,拿来当标题会和旁边的按钮文字一样重,分不出层级。
 *
 * @param trailing 小节标题右边的操作(顺序/随机、听视频)。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = Spacing.Tight),
        )
        trailing()
    }
}
