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
    /** 面板之上的一层,写评论的编辑面板挂在这里(见 [PaneOverlay])。 */
    internal var overlay: PaneOverlaySlot? by mutableStateOf(null)
    internal var hosted: Boolean by mutableStateOf(false)

    internal val isOpen: Boolean get() = overlay != null || panel != null

    /** 关掉最上面那一层:先编辑面板,再二级面板。 */
    internal fun dismissTop() {
        overlay?.let { it.onDismiss(); return }
        panel?.onDismiss?.invoke()
    }
}

internal class PaneOverlaySlot(val onDismiss: () -> Unit, val content: @Composable () -> Unit)

internal class PanePanel(val title: String?, val onDismiss: () -> Unit, val content: @Composable ColumnScope.() -> Unit)

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
    state.overlay?.let { overlay ->
        Box(modifier = Modifier.matchParentSize()) { overlay.content() }
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
    if (!state.isOpen) return
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

/**
 * 把 [content] 挂到右栏最上面;不在两栏布局里时挂到窗口最上面([WindowOverlay])。
 *
 * 写评论的编辑面板走这里。两栏时它的遮罩只盖右栏,左栏的画面不压暗,点左栏是 [onDismiss]
 * (见 [SidePaneDismissLayer]);盖满整个窗口的话,一条上千 dp 宽的输入框横跨画面和评论两栏。
 */
@Composable
fun PaneOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val pane = LocalSidePane.current
    if (pane == null || !pane.hosted) {
        WindowOverlay(content)
        return
    }
    val latest by rememberUpdatedState(content)
    val latestDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(pane) {
        val slot = PaneOverlaySlot(onDismiss = { latestDismiss() }, content = { latest() })
        pane.overlay = slot
        onDispose { if (pane.overlay === slot) pane.overlay = null }
    }
}

@Composable
private fun PanePanelSurface(panel: PanePanel) {
    // 不压暗底下的简介与评论:面板盖满这一栏,底下本来就看不见;左栏的画面也不该变暗。
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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

private class PaneSheetScopeImpl(column: ColumnScope, override val inPane: Boolean) :
    PaneSheetScope, ColumnScope by column {
    override fun Modifier.bodyHeight(sheetFraction: Float): Modifier =
        if (inPane) weight(1f) else fillMaxHeight(sheetFraction)
}

/**
 * 二级面板。两栏布局里画在右栏(见 [SidePaneState]),单栏时是只有展开一档的底部 sheet。
 *
 * [title] 只在右栏里显示,和返回箭头排在一行:面板盖住了整栏,不说一句就不知道这是哪里。
 * sheet 上方还露着页面,打开它的那一行就是上下文,不另加标题。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaneSheet(
    onDismissRequest: () -> Unit,
    title: String? = null,
    /** sheet 形态下跳过半开(见 [rememberExpandedSheetState])。右栏里没有半开。 */
    skipPartiallyExpanded: Boolean = true,
    content: @Composable PaneSheetScope.() -> Unit,
) {
    val pane = LocalSidePane.current
    if (pane != null && pane.hosted) {
        val latestContent by rememberUpdatedState(content)
        val latestDismiss by rememberUpdatedState(onDismissRequest)
        // 返回键先关面板。面板画在右栏里,不像 ModalBottomSheet 自带返回处理。
        BackHandler { latestDismiss() }
        DisposableEffect(pane, title) {
            val panel = PanePanel(title, { latestDismiss() }) {
                PaneSheetScopeImpl(this, inPane = true).latestContent()
            }
            pane.panel = panel
            onDispose { if (pane.panel === panel) pane.panel = null }
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
