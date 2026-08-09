package dev.bilby.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.ui.theme.Spacing

/**
 * 加载 / 出错 / 空 / 到底 —— 四个列表页以前各写了一份,文案和间距都差一点。收敛到这里,
 * 顺带固定一条规矩:**首屏和翻页用不同的粗细。** 首屏转圈是"这一屏还没有内容",占整屏;
 * 翻页转圈是"下面还有",只占一行的高度。以前两处用的是同一个尺寸,翻页时那个大圈看起来
 * 像整页重载了。
 */

/**
 * 首屏加载。整屏居中一个指示器,不放骨架屏 —— 骨架屏是在假装内容马上就到。
 *
 * 用 [LoadingIndicator] 而不是 `CircularProgressIndicator`(material3 1.5.0-alpha25 才有):
 * M3 把两者分开了 —— loading indicator 用于"短暂等待、进度不可知",progress indicator 用于
 * "有真实进度可报"。首屏拉一页动态正是前者,我们从来报不出百分比。
 * 翻页和上传那种也报不出进度,但它们不占整屏,仍用小号 circular(见 [ListFooter])。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingIndicator()
    }
}

/**
 * 首屏出错。错误文案用 onSurfaceVariant 而不是 error 色:整段话都染成红色会让一次网络抖动
 * 看起来像出了大事,红色留给"投币不可撤销"那种真需要停一下的地方。
 *
 * 图标是**状态标识不是插图**:整屏只有一行灰字时,读者要多看一眼才知道这是"出错了"还是
 * "本来就没有"。它和空态那个图标成对,区分的正是这两种情况。
 */
@Composable
fun FullScreenError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(Spacing.Loose),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Cozy, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(StateIconSize),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // 重试是这一屏唯一能做的事,但它不是"主行动号召"——用 text button,
        // 别把一次失败渲染成一个需要下决心的按钮。
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
    }
}

/**
 * 空态。只说事实,不加"去逛逛"这类把人推回内容池的引导。
 *
 * 图标同 [FullScreenError]:它标的是"这里本来就是空的",和"没读到"是两件事,
 * 不是给空屏配的插画。
 */
@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(Spacing.Spacious),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Icon(
                imageVector = Icons.Outlined.Inbox,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(StateIconSize),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 已经有内容时的失败。**不能复用 [FullScreenError]**:它 `fillMaxSize()`,塞进列表底部或
 * 结果流里会把一条错误撑成整页,把用户已经读到的东西顶出屏幕。
 */
@Composable
fun InlineError(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        onRetry?.let { retry ->
            TextButton(onClick = retry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

/**
 * 列表底部。翻页中显示小转圈,没有更多时显示"没有更多了";**翻页失败时在原列表下方给一行
 * 错误和重试,不把已经读到的内容清掉** —— 首屏空列表那种失败仍归 [FullScreenError]。
 * [hasItems] 为 false 且没有错误时什么都不显示 —— 空列表已经有空态在说话了。
 */
@Composable
fun ListFooter(
    appending: Boolean,
    hasMore: Boolean,
    hasItems: Boolean,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (!hasItems && error == null) return
    if (error != null) {
        InlineError(message = error, onRetry = onRetry, modifier = modifier)
        return
    }
    Box(
        modifier = modifier.fillMaxWidth().padding(Spacing.Comfortable),
        contentAlignment = Alignment.Center,
    ) {
        when {
            appending -> CircularProgressIndicator(modifier = Modifier.size(InlineSpinnerSize))
            !hasMore -> Text(
                text = stringResource(R.string.list_no_more),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一行之内的"正在做某事",用于助理过程、队列加载。 */
@Composable
fun InlineProgress(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(HairSpinnerSize), strokeWidth = 2.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 空态与错误态的状态图标。比正文大一档,但远小于插图——它是标识不是画面。 */
private val StateIconSize = 40.dp

/** 列表底部翻页用。默认 40dp 在一行文字旁边太大。 */
private val InlineSpinnerSize = 24.dp

/** 跟在一行文字旁边、和字号同量级。 */
private val HairSpinnerSize = 16.dp
