package dev.bilby.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LoadingIndicatorDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.bilby.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bilby.resources.*
import dev.bilby.ui.theme.Spacing

/**
 * 加载 / 出错 / 空 / 到底 —— 四个列表页以前各写了一份,文案和间距都差一点。收敛到这里,
 * 顺带固定一条规矩:**首屏和翻页用不同的粗细。** 首屏是"这一屏还没有内容",占整屏;
 * 翻页转圈是"下面还有",只占一行的高度。以前两处用的是同一个尺寸,翻页时那个大圈看起来
 * 像整页重载了。
 *
 * 全 app 的等待指示只有这几档,别处不要直接调 material3 的指示器:
 *
 * - 列表首屏、分节首载 —— 骨架屏(ui/components/Skeleton.kt),形状照内容画
 * - 形状说不准的整屏首载(文章、动态详情、直播间) —— [FullScreenLoading]
 * - 行内一个转圈 —— [LoadingSpinner],跟一行说明时用 [InlineProgress]
 * - 列表尾部续页 —— [ListFooter]
 * - 下拉刷新 —— [RefreshBox]
 *
 * 这四档全部走 M3 Expressive 的 loading indicator。**报得出百分比的等待不在其列**,那是
 * progress indicator,直接用 `LinearProgressIndicator`(更新下载、稍后再看容量条)。M3 把
 * 两者分开的判据是进度可不可知,不是形状:loading indicator 覆盖 200ms–5s 的不可知等待,
 * progress indicator 覆盖有真实百分比、通常超过 5s 的那种,且不允许从前者过渡到后者。
 */

/**
 * 指示器该不该画出来。**等到这一刻还没结束的等待才配有指示器。**
 *
 * 上面那段说明里 M3 给 loading indicator 定的范围是 200ms–5s 的不可知等待,下界不是随口定的:
 * 这个指示器一上来跑的是一条 `dampingRatio = 0.6f` 的欠阻尼弹簧形变(材料 1.5.0-alpha25 的
 * `LoadingIndicator.kt:401`),整圈旋转要 4666ms。它生命最初那几十毫秒正是整个循环里变化
 * 最快、幅度最大的一段 —— 只活两三帧的话,画出来的只有弹簧的前沿,看着是猛地抽一下,
 * 读不出"在转"。
 *
 * 而"只活两三帧"是常态而非例外:UI state 一律以 `loading = true` 开局,数据却可能已经在
 * 进程内的 store 里(动态流是 app 级单例),或者来自一次本地 Room 查询。清掉这个标志的续体
 * 排在当前帧之后,于是首帧必然画一次指示器,下一帧就换成完整列表。
 *
 * 所以补的是"晚一点再出现",不是"出现了就多留一会":后者会把真正快的加载也拖慢。
 */
@Composable
fun rememberLoadingVisible(): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LoadingAppearDelayMillis)
        visible = true
    }
    return visible
}

/** 见 [rememberLoadingVisible]。取 M3 给 loading indicator 定的那个下界。 */
private const val LoadingAppearDelayMillis = 200L

/**
 * 首屏的三态:整屏转圈 / 整屏出错 / 内容。**四个列表页原本各写一遍同一个 `when`**
 * (动态、稍后再看、收藏夹列表、[PagedColumn]),而且都是硬切 —— 上一屏整块消失、下一屏整块
 * 出现,读起来像换了一页。
 *
 * 用 [Crossfade] 而不是 `AnimatedContent`:这里换的是同一块区域的三种填充,没有方向可言,
 * 而 `AnimatedContent` 默认还要连着尺寸一起过渡 —— 整屏转圈和一屏列表的尺寸本来就一样,
 * 那份 `SizeTransform` 只会在切换时多抖一下。spec 取 `motionScheme` 的 effects 档:淡入淡出
 * 是"效果"不是空间位移,而整屏三态属于组件层(风格指南 §6 那张表),不是转场。
 *
 * **切换的键是"哪一种态",不是错误文案本身**([Phase] 把文案带进 target,而不是在分支里
 * 读外面的 `error`):淡出还没走完时旧分支仍在组合,那一刻 `error` 已经是 null 了。
 *
 * @param isEmpty 列表当前有没有内容。**加载态和错误都只在首屏(列表为空)时占整屏**,列表已经
 *   有内容时翻页的转圈和失败归 [ListFooter] —— 已经读到的东西不该被一次翻页失败清掉。
 * @param skeleton 首屏读取中画什么。默认一屏视频行的骨架([ListSkeleton]);"一个人一行"的列表
 *   传 [PersonRowSkeleton]。
 */
@Composable
fun FirstScreenState(
    loading: Boolean,
    error: String?,
    isEmpty: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    skeleton: @Composable () -> Unit = { ListSkeleton() },
    content: @Composable () -> Unit,
) {
    val phase = when {
        loading && isEmpty -> Phase.Loading
        error != null && isEmpty -> Phase.Failed(error)
        else -> Phase.Content
    }
    Crossfade(
        targetState = phase,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        modifier = modifier.fillMaxSize(),
        label = "first-screen",
    ) { current ->
        when (current) {
            Phase.Loading -> skeleton()
            is Phase.Failed -> FullScreenError(current.message, onRetry)
            Phase.Content -> content()
        }
    }
}

/** [FirstScreenState] 的三态。错误文案带在态里,理由见那个函数。 */
private sealed interface Phase {
    data object Loading : Phase

    data class Failed(val message: String) : Phase

    data object Content : Phase
}

/**
 * 首屏加载。整屏居中一个指示器,给形状事先说不准的页面(一篇文章、一条动态、直播间);
 * 列表的首屏用骨架屏,见 [FirstScreenState]。
 *
 * 不带尺寸:48dp 的默认值就是为整屏居中定的。
 *
 * 占位的 [Box] 无条件铺满:指示器还没到出现的时候,这一屏也该是它自己的空白,而不是让下面
 * 的东西先顶上来再被推开。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (rememberLoadingVisible()) LoadingIndicator()
    }
}

/**
 * 下拉刷新。**必须走这里,不要直接用 `PullToRefreshBox`** —— 它默认那个指示器仍是
 * M3 Expressive 之前的箭头圈(`PullToRefreshDefaults.Indicator`),整个 app 十几处下拉
 * 会各自长成旧样子,而 M3E 恰恰把 loading indicator 定为下拉刷新的组件。
 *
 * 指示器和 [PullToRefreshBox] 共用同一个 state:分成两个的话指示器收不到拖拽距离,
 * 手指往下拉时它一动不动,松手才突然出现。
 *
 * 用 contained 那一档(默认色就是 primaryContainer / onPrimaryContainer):指示器压在列表
 * 内容上,没有容器托底时深浅两套主题里都可能撞上正文。
 *
 * **只有手指能拉**,见 [PointerSource]。最近一次输入是鼠标时刷新手势整个关掉,不是把下拉量
 * 吃掉:关掉之后到顶多出来的那一截照常往外传,外层可收起的页头照样能被滚轮展开。
 * 用手写的 `pullToRefresh` 而不是 [PullToRefreshBox],是因为后者在桌面端编译的 material3
 * alpha22 里不给 enabled 开关。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    Box(
        modifier.pullToRefresh(
            state = state,
            isRefreshing = refreshing,
            enabled = LocalPointerSource.current.isTouchLike,
            onRefresh = onRefresh,
        ),
    ) {
        content()
        PullToRefreshDefaults.LoadingIndicator(
            state = state,
            isRefreshing = refreshing,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

/**
 * 顶栏上的刷新按钮,给用鼠标的人:鼠标没法下拉,[RefreshBox] 在最近一次输入是鼠标时关掉了
 * 下拉手势,刷新入口就挪到这里。**用手指时不出现**:那时下拉就是刷新入口,顶栏再摆一个是
 * 同一件事的两个入口。判据跟 [RefreshBox] 是同一个([PointerSource]),两者此消彼长。
 *
 * 刷新中转圈而不是禁用:按下之后看得出它在做事,也不会连点出第二次请求。
 */
@Composable
fun RefreshAction(refreshing: Boolean, onRefresh: () -> Unit) {
    if (LocalPointerSource.current.isTouchLike) return
    if (refreshing) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) { LoadingSpinner() }
    } else {
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.action_refresh))
        }
    }
}

/**
 * 行内一个转圈,不带文字:按钮里替掉图标、对话框里占住内容位、播放条上替掉播放键。
 *
 * [color] 是给压在画面上的那几处准备的 —— 直播间和播放器的控件用固定色,不跟主题走。
 *
 * [size] 只在"它替掉的那个图标本来就不是常规尺寸"时才传,例如听音页 40dp 的播放键。
 * 一行文字旁边、按钮里、列表尾部都用默认值,那是同一件事,没有理由是三个尺寸。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoadingSpinner(
    modifier: Modifier = Modifier,
    size: Dp = InlineSpinnerSize,
    color: Color = LoadingIndicatorDefaults.indicatorColor,
) {
    LoadingIndicator(modifier = modifier.size(size), color = color)
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
        TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
    }
}

/**
 * 空态。只说事实,不加"去逛逛"这类把人推回内容池的引导。
 *
 * 图标同 [FullScreenError]:它标的是"这里本来就是空的",和"没读到"是两件事,
 * 不是给空屏配的插画。
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    /** 默认那个收件箱是"这里本该有东西但现在没有";空态另有含义时换掉它。 */
    icon: ImageVector = Icons.Outlined.Inbox,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(Spacing.Spacious),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Icon(
                imageVector = icon,
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
            TextButton(onClick = retry) { Text(stringResource(Res.string.action_retry)) }
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
    showEndMarker: Boolean = true,
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
            appending -> LoadingSpinner()
            !hasMore && showEndMarker -> Text(
                text = stringResource(Res.string.list_no_more),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 一行之内的"正在做某事":[LoadingSpinner] 加一句说明。用于助理过程、队列加载、个人页分段。 */
@Composable
fun InlineProgress(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        LoadingSpinner()
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 空态与错误态的状态图标。比正文大一档,但远小于插图——它是标识不是画面。 */
private val StateIconSize = 40.dp

/**
 * 行内转圈的尺寸。默认 48dp 在一行文字旁边太大,24dp 是 loading indicator 规格里的下限,
 * 再小那个形变的形状只剩几个像素,看不出它在动。
 *
 * 这里原来分两档:翻页 24、跟在文字旁边的 16。16 那一档正是掉到下限以下的那个,
 * 而两处要说的是同一件事,没有理由是两个尺寸。
 */
private val InlineSpinnerSize = 24.dp
