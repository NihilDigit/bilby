package dev.bilby.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.resources.Res
import dev.bilby.resources.action_back
import dev.bilby.stringResource
import dev.bilby.ui.BackHandler
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.resources.action_close
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.bilby.ui.theme.Spacing

/**
 * 两栏布局里右栏的面板层。视频页、直播间宽屏时,右栏是简介与评论,二级面板(评论详情、
 * 分集、播放设置……)在这一栏里从右边缘划进来,盖住这一栏,不盖画面。
 *
 * 宿主状态提供在整个两栏布局上,而面板画在右栏里:播放设置是从左栏的播放器里打开的,
 * 它要能找到右栏。右栏不在组合里时(全屏)[hosted] 为 false,面板退回底部 sheet。
 *
 * 同一时刻只有一块面板;后打开的顶掉先打开的。
 */
@Stable
class SidePaneState internal constructor() {
    internal var panel: PanePanel? by mutableStateOf(null)
    internal var hosted: Boolean by mutableStateOf(false)

    internal val isOpen: Boolean get() = panel != null

    internal fun dismissTop() {
        panel?.onDismiss?.invoke()
    }
}

internal class PanePanel(
    val title: String?,
    val onDismiss: () -> Unit,
    /** 常驻面板,见 [PaneSheet] 的同名参数。 */
    val persistent: Boolean,
    val content: @Composable ColumnScope.() -> Unit,
)

val LocalSidePane = staticCompositionLocalOf<SidePaneState?> { null }

@Composable
fun rememberSidePaneState(): SidePaneState = remember { SidePaneState() }

/**
 * 画出挂到 [state] 上的面板。放在右栏那个 Box 的最后,铺满它。
 *
 * 退场时面板已经从 [state] 摘下,而划出去的那几帧里还得有东西可画,所以留住最后一份。
 */
@Composable
fun BoxScope.SidePaneLayer(state: SidePaneState) {
    DisposableEffect(state) {
        state.hosted = true
        onDispose { state.hosted = false }
    }
    val current = state.panel
    var shown by remember { mutableStateOf<PanePanel?>(null) }
    if (current != null) shown = current
    AnimatedVisibility(
        visible = current != null,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
        modifier = Modifier.matchParentSize(),
    ) {
        shown?.let { panel -> PanePanelSurface(panel) }
    }
}

/**
 * 右栏开着面板时盖在左栏上的一层:点左栏只是收起面板,不落到底下的画面上。
 *
 * 不压暗,画面照常看得见。点下去的那一下被这一层整个吃掉:落到画面上的话,用鼠标是暂停、
 * 双击是全屏,人只是想回到画面,却把视频停了。悬停与滚轮也一并接住,控件不会被光标唤出来。
 */
@Composable
fun BoxScope.SidePaneDismissLayer(state: SidePaneState) {
    // 常驻面板不装这一层:它和画面是一起看的,点画面就是在操作画面。
    if (!state.isOpen || state.panel?.persistent == true) return
    Box(
        modifier = Modifier
            .matchParentSize()
            .pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.type == PointerEventType.Press) state.dismissTop()
                    }
                }
            },
    )
}

@Composable
private fun PanePanelSurface(panel: PanePanel) {
    // 不压暗底下的简介与评论:面板盖满这一栏,底下本来就看不见;左栏的画面也不该变暗。
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 常驻面板的标题行归内容自己画(标题带着内容的状态,关闭在右端);这里再画一行
            // 返回箭头,就是两行标题摞在一起。
            if (!panel.persistent) Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = panel.onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                }
                panel.title?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = Spacing.Comfortable),
                    )
                }
            }
            panel.content(this)
        }
    }
}

/**
 * 列表滚到头剩下的滚轮位移,不再往上交给 sheet。交上去的话 sheet 被一格一格往下拖,又没有
 * 松手去吸附,停在半路。手指照旧交上去:滚到顶再往下拉就是把 sheet 拉下来关掉。
 */
private class WheelStopsAtSheet(private val pointers: PointerSource) : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && !pointers.isTouchLike) available else Offset.Zero
}

/** [PaneSheet] 内容所在的作用域。 */
interface PaneSheetScope : ColumnScope {
    /** 此刻画在右栏里,而不是底部 sheet 里。 */
    val inPane: Boolean

    /**
     * 列表这类要占住面板主体的内容用它定高:sheet 里是窗口高度的 [sheetFraction],
     * 右栏里是标题行以下的全部。
     */
    fun Modifier.bodyHeight(sheetFraction: Float): Modifier
}

/**
 * @param fillsHeight 容器本身就是整栏高(右栏、宽屏侧边面板),主体取剩下的全部高度;
 *   为 false 时是底部 sheet,主体按窗口高度的比例定。
 */
private class PaneSheetScopeImpl(
    column: ColumnScope,
    override val inPane: Boolean,
    private val fillsHeight: Boolean = inPane,
) : PaneSheetScope, ColumnScope by column {
    override fun Modifier.bodyHeight(sheetFraction: Float): Modifier =
        if (fillsHeight) weight(1f) else fillMaxHeight(sheetFraction)
}

/**
 * 二级面板。两栏布局里画在右栏(见 [SidePaneState]);没有右栏可挂时,宽屏是从右边缘划进来的
 * 侧边面板([ModalSideSheet]),窄屏是只有展开一档的底部 sheet。
 *
 * [title] 只在右栏里显示,和返回箭头排在一行:面板盖住了整栏,不说一句就不知道这是哪里。
 * sheet 与侧边面板旁边还露着页面,打开它的那一行就是上下文,不另加标题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@NonSkippableComposable
@Composable
fun PaneSheet(
    onDismissRequest: () -> Unit,
    title: String? = null,
    /** sheet 形态下跳过半开(见 [rememberExpandedSheetState])。右栏里没有半开。 */
    skipPartiallyExpanded: Boolean = true,
    /**
     * 右栏里常驻:点左栏不收起,也不画返回箭头那一行,标题与关闭按钮由内容自己画。给和画面
     * 一起看的面板用(找相关),不给看一眼就走的面板(播放设置、评论详情)——那些点回画面就
     * 收起正好。sheet 与侧边面板两种形态不受影响。
     */
    persistent: Boolean = false,
    content: @Composable PaneSheetScope.() -> Unit,
) {
    val pane = LocalSidePane.current
    if (pane != null && pane.hosted) {
        // **右栏里的面板不在调用方的组合位置上**,调用方重组不会顺带重画它,只能靠这份状态
        // 通知。曾经用 rememberUpdatedState,楼中楼面板一直转圈,而同一份回复早已摊进主列表:
        // 它只在拿到另一个 lambda 实例时才通知,PaneSheet 又会在参数全等时被整个跳过,
        // 捕获的值变了也传不过去。所以这里不跳过,且每次都写、每次都算变化。
        val contentState = remember { mutableStateOf(content, neverEqualPolicy()) }
        SideEffect { contentState.value = content }
        val latestDismiss by rememberUpdatedState(onDismissRequest)
        // 返回键先关面板。面板画在右栏里,不像 ModalBottomSheet 自带返回处理。
        BackHandler { latestDismiss() }
        DisposableEffect(pane, title, persistent) {
            val panel = PanePanel(title, { latestDismiss() }, persistent) {
                val latestContent = contentState.value
                PaneSheetScopeImpl(this, inPane = true).latestContent()
            }
            pane.panel = panel
            onDispose { if (pane.panel === panel) pane.panel = null }
        }
    } else if (rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)) {
        ModalSideSheet(onDismissRequest) {
            // 不是右栏(inPane = false),高度却是整栏。
            PaneSheetScopeImpl(this, inPane = false, fillsHeight = true).content()
        }
    } else {
        // 用鼠标时一律不停半开。sheet 把滚动当成拖它自己:半开时往下滚先被 sheet 拿去往上挪,
        // 而滚轮没有松手那一下,sheet 不会吸附到哪一档,表现是列表滚不动。
        val pointers = LocalPointerSource.current
        val sheetState = if (skipPartiallyExpanded || !pointers.isTouchLike) {
            rememberExpandedSheetState()
        } else {
            rememberModalBottomSheetState()
        }
        ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
            Column(modifier = Modifier.nestedScroll(remember(pointers) { WheelStopsAtSheet(pointers) })) {
                PaneSheetScopeImpl(this, inPane = false).content()
            }
        }
    }
}

/**
 * 宽屏的模态侧边面板,底部 sheet 在大窗口里的替身。bottom-sheets.md 的 Adaptive design 一节原话:
 * "On larger expanded breakpoints, like desktop, a bottom sheet can be swapped for a side sheet
 * that shows similar content." 底部 sheet 在宽窗口里只能居中升起一块最宽 640dp 的板子,横在
 * 页面正中,离打开它的那一行隔着半个屏幕。
 *
 * 规格取 side-sheets.md 的 modal 款:容器 surfaceContainerLow,圆角 16dp(Differences from M2
 * 一节),最宽 400dp,四周离窗口边缘 16dp(Margins (when detached)),关闭按钮常驻。
 *
 * 自成一个窗口(Dialog),与 ModalBottomSheet 同。遮罩用平台对话框自带的那一层。
 *
 * 人关掉它(点遮罩、关闭按钮、Esc 或返回)时先划出去再通知调用方;调用方自己撤掉它时
 * (点了名单里的一项)直接消失,与底部 sheet 一致。
 */
@Composable
private fun ModalSideSheet(onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val dismiss = { visibility.targetState = false }
    // 划出去的动画走完才真正关:此刻才通知调用方把它移出组合。
    LaunchedEffect(visibility.isIdle, visibility.currentState) {
        if (visibility.isIdle && !visibility.currentState && !visibility.targetState) latestDismiss()
    }
    Dialog(onDismissRequest = dismiss, properties = fullScreenDialogProperties()) {
        DialogIntoDisplayCutout()
        Box(modifier = Modifier.fillMaxSize()) {
            // 点在面板外面就是关掉。不画按下的波纹:这一整片是遮罩,不是一个按钮。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = null, indication = null, onClick = dismiss),
            )
            AnimatedVisibility(
                visibleState = visibility,
                enter = slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } + fadeIn(),
                exit = slideOutHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) { it } + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.barsAndCutout)
                        .padding(Spacing.Comfortable)
                        .width(ModalSideSheetWidth)
                        .fillMaxHeight(),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                            IconButton(onClick = dismiss) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(Res.string.action_close),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        content()
                    }
                }
            }
        }
    }
}

/** modal side sheet 的最大宽度(side-sheets.md 的 measurements 表)。 */
private val ModalSideSheetWidth = 400.dp
