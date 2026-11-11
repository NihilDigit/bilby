package dev.bilby.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 桌面端紧凑标题栏融合契约(照 Chrome / Piko):界面声明自己能容纳窗口按钮与拖动区后,窗口不再画单独一条
 * 32dp 系统标题栏,贴着窗口右上角的那一行在末尾直接画出三个圆角窗口按钮,行内空白处登记为非客户区拖动区
 * ([windowDragArea])。Android 与全屏/小窗下为 null,调用侧零分支退回原状。
 */
interface WindowCaption {
    /**
     * 当前页面声明自己接管窗口按钮与拖动区,在组合里存活期间生效。未声明的页面(登录页、单栏窄窗口下的视频/直播页)
     * 自动退回单独的 32dp 标题栏,且内容在组合树中的位置不变,切换时播放器与状态不重建。
     */
    @Composable
    fun Host()

    /** 最小化、最大化/还原、关闭三颗按钮。画在何处由所在行决定,几何坐标自行上报给窗口过程。 */
    @Composable
    fun Buttons()

    /** 登记一块非客户区拖动矩形(窗口像素坐标),[bounds] 为 null 时撤下。注意切勿包住可点控件。 */
    fun setDragArea(key: Any, bounds: Rect?)

    /** 全窗口遮罩(如全屏看图器)在场期间暂时挂起底层顶栏的非客户区命中,避免遮罩上的按钮被当成标题栏拦截。 */
    @Composable
    fun Suppress() = Unit
}

val LocalWindowCaption = compositionLocalOf<WindowCaption?> { null }

/**
 * 把一块空白或纯展示区域登记为窗口拖动区(`WM_NCHITTEST` -> `HTCAPTION`)。
 *
 * **切勿挂在包含按钮或输入框的父容器上**:非客户区命中测试发生在系统消息层,早于 Compose 指针分发,
 * 盖住按钮会让点击变成拖窗口。只挂在 `Spacer` 或纯文字标题块上。
 */
fun Modifier.windowDragArea(enabled: Boolean = true): Modifier = composed {
    val caption = LocalWindowCaption.current
    if (!enabled || caption == null) return@composed Modifier
    val key = remember { Any() }
    DisposableEffect(caption, key) {
        onDispose { caption.setDragArea(key, null) }
    }
    Modifier.onGloballyPositioned { coordinates ->
        val bounds = if (coordinates.isAttached) coordinates.boundsInWindow() else Rect.Zero
        caption.setDragArea(key, bounds.takeUnless { it.isEmpty })
    }
}

/**
 * 一行顶栏的窗口按钮槽位:挂上 [modifier] 的那一行若贴近窗口顶边且右沿贴着窗口右沿,[buttons] 非空,
 * 追加在该行末尾即可。
 *
 * 按真实窗口几何判断而不是手工传参:主从双栏(`ListDetailSceneStrategy`)或可开关右侧面板(`SidePanelLayout`)下,
 * 哪一栏此刻贴着右上角只有布局自己知道,面板开合时槽位自动交接。
 */
class CaptionSlot(
    val modifier: Modifier,
    val buttons: (@Composable () -> Unit)?,
    /** 该行位于窗口顶部,行内空白应登记为 [windowDragArea]。 */
    val atTop: Boolean = false,
)

/**
 * @param topInset 允许该行上沿离窗口顶边的最大间距:宽屏圆角内容岛上沿留有 8dp 外框边,岛内页眉再留 8dp 内边距,
 *   24dp 足以把岛内首行认作窗口顶行,同时排除下方的正文或二级栏。
 */
@Composable
fun rememberCaptionSlot(topInset: Dp = 28.dp): CaptionSlot {
    val caption = LocalWindowCaption.current ?: return NoCaptionSlot
    val liveWindow = LocalLiveWindowInfo.current ?: LocalWindowInfo.current
    val density = LocalDensity.current
    val insetPx = with(density) { topInset.toPx() }
    val edgeTolerancePx = with(density) { CaptionEdgeTolerance.toPx() }
    var touches by remember { mutableStateOf(false) }
    var atTop by remember { mutableStateOf(false) }
    val modifier = Modifier.onGloballyPositioned { coordinates ->
        if (!coordinates.isAttached) return@onGloballyPositioned
        val bounds = coordinates.boundsInWindow()
        val nearTop = bounds.top >= -4f && bounds.top <= insetPx + 2f
        atTop = nearTop
        touches = nearTop && bounds.right >= liveWindow.containerSize.width - edgeTolerancePx
    }
    return CaptionSlot(
        modifier = modifier,
        buttons = if (touches) {
            {
                // 按钮前固定留出一段可拖动空白:操作区按钮紧凑时无需去别处找缝隙拖窗口。
                // 定高与按钮一致,不用 fillMaxHeight,避免在未定高的行里撑高整列。
                Spacer(Modifier.width(CaptionDragGap).height(CaptionDragHeight).windowDragArea())
                caption.Buttons()
            }
        } else {
            null
        },
        atTop = atTop,
    )
}

private val NoCaptionSlot = CaptionSlot(Modifier, null)

private val CaptionEdgeTolerance = 32.dp
private val CaptionDragGap = 32.dp
val CaptionDragHeight = 40.dp

/**
 * 顶栏里紧挨可点信息块(如账号头像名片、直播间主播信息)与右侧操作按钮之间的自适应排布:
 * - 无自绘标题栏(Android)时,[content] 直接填满整段可用宽度,保留原有的整行点按热区。
 * - 有自绘标题栏(Desktop)时,[content] 按自然宽度紧贴左侧,右侧剩余空白登记为 [windowDragArea]。
 */
@Composable
fun CaptionLeadWithDrag(
    dragEnabled: Boolean,
    modifier: Modifier = Modifier,
    dragHeight: Dp = CaptionDragHeight,
    content: @Composable () -> Unit,
) {
    if (LocalWindowCaption.current == null) {
        Box(modifier = modifier) { content() }
        return
    }
    Layout(
        content = {
            content()
            Spacer(Modifier.height(dragHeight).windowDragArea(dragEnabled))
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val lead = measurables[0].measure(constraints.copy(minWidth = 0))
        val totalWidth = constraints.maxWidth
        val gapWidth = (totalWidth - lead.width).coerceAtLeast(0)
        val drag = measurables[1].measure(Constraints.fixedWidth(gapWidth))
        val height = maxOf(lead.height, drag.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(totalWidth, height) {
            lead.placeRelative(0, (height - lead.height) / 2)
            drag.placeRelative(lead.width, (height - drag.height) / 2)
        }
    }
}
