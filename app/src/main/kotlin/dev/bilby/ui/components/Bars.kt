package dev.bilby.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.R
import dev.bilby.ui.theme.Dimens
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
 * @param onTitleClick 非空时标题本身是个入口(播放队列的标题就是它的来源合集)。**只有标题
 *   那一段可点**,不是整行 —— 行尾那几个图标各有各的动作,把它们盖在一个更大的点击区里,
 *   点偏一点就会跑去开合集。可点时补一个右尖括号:同样一行字,没有这个记号读不出它能点。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val titleStyle = MaterialTheme.typography.titleSmall
        if (onTitleClick == null) {
            Text(
                text = title,
                style = titleStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = Spacing.Tight),
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(role = Role.Button, onClick = onTitleClick)
                    .padding(vertical = Spacing.Tight),
            ) {
                Text(
                    text = title,
                    style = titleStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.IconInline),
                )
            }
        }
        trailing()
    }
}

/**
 * 靠右的一个去处:图标 + 文字 + 箭头,整体贴在行尾。
 *
 * **它标的是"去哪儿",不是一条内容。** 这两处原先都是铺满整行的 `ListItem`,于是长得和它们
 * 上下的内容行一模一样 —— 「我的」页的消息入口挤在一堆预览区之间,首页的「其他动态」排在时间序
 * 流里,两个都读起来像列表的一员。缩到行尾之后,它和 [SectionHeader] 右边的「全部」是同一类
 * 东西,一眼分得出这是个出口。
 *
 * **只在这两处用**(`ProfileScreen` 的消息、`FeedScreen` 的其他动态)。别处的入口该不该也换成
 * 这个形态,是逐个判断的事:铺满整行的入口在只有它一个的地方是对的,这里的毛病来自"和邻居撞脸"。
 *
 * **永远不给它红点、未读计数或角标。** 那是 DESIGN 1.3 永不实现清单上的第一条,而这两处正是
 * 它们最容易被加回来的位置 —— 「顺手显示有几条新的」听起来是信息,实际是把一条静态入口变成
 * 催人回来的提醒。
 */
@Composable
fun TrailingEntry(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.Tight),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // TextButton 而不是自己拼一个 clickable Row:它自带 48dp 的触控下限和涟漪,
        // 而这个控件本身只有一行字那么高。内容色用它默认的 primary —— 这一行本来就要在一片
        // 内容里被认出来是个出口,压成 onSurfaceVariant 之后它和周围的正文分不开。
        TextButton(onClick = onClick) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.IconInline))
            Text(
                text = text,
                // 比 TextButton 默认的 labelLarge 大一档,与列表行的标题同字号:它和那些行
                // 并排,小一号会让它读起来像那些行的附注。
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Spacing.Tight, end = Spacing.Hair),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconInline),
            )
        }
    }
}
