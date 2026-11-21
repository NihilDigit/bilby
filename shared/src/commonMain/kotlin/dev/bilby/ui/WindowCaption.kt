package dev.bilby.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.LoadingSpinner
import dev.bilby.ui.components.LocalPageRefreshRegistry
import dev.bilby.ui.components.LocalPointerSource

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

    /** 所属窗口当前是否处于活动态,用于同步紧邻标题栏按钮的失焦变灰。 */
    val isActive: Boolean get() = true
}

val LocalWindowCaption = compositionLocalOf<WindowCaption?> { null }

/**
 * 桌面端应用级全屏期间的右上角槽位跟踪器：供顶栏 [rememberCaptionSlot] 在实际渲染
 * `[刷新] [退出全屏]` 时登记自身，使宿主窗口在无顶栏槽位的界面（如登录页、单栏窄播放页）
 * 能可靠展示兜底退出全屏按钮，避免用户被困在全屏中。
 */
@Stable
class FullscreenCaptionSlotTracker {
    private val activeSlots = mutableStateListOf<Any>()

    val hasActiveSlot: Boolean by derivedStateOf { activeSlots.isNotEmpty() }

    @Composable
    fun Track() {
        val key = remember { Any() }
        DisposableEffect(this, key) {
            activeSlots.add(key)
            onDispose { activeSlots.remove(key) }
        }
    }
}

val LocalFullscreenCaptionSlotTracker = compositionLocalOf<FullscreenCaptionSlotTracker?> { null }

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
    /** 当前上下文处于桌面窗口(含应用全屏)且该槽位参与右上角几何检测。 */
    val active: Boolean = false,
    private val dragGap: (@Composable () -> Unit)? = null,
    private val rawButtons: (@Composable () -> Unit)? = null,
) {
    /**
     * 将与标题栏同视觉体系的动作按钮(如全屏、刷新)紧贴窗口控制按钮(`— ❐ ×`)左侧排布:
     * 拖动留白落在整组按钮之前,动作按钮与系统三键共用 [CaptionButtonSpacing],中间无额外断层。
     * 处于无标题栏全屏态时([rawButtons] 为空或仅含退出全屏),仍原样渲染 [leading]。
     */
    @Composable
    fun ButtonsWithLeading(leading: @Composable RowScope.() -> Unit) {
        dragGap?.invoke()
        Row(
            horizontalArrangement = Arrangement.spacedBy(CaptionButtonSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading()
            rawButtons?.invoke()
        }
    }
}

/**
 * @param topInset 允许该行上沿离窗口顶边的最大间距:宽屏圆角内容岛上沿留有 8dp 外框边,岛内页眉再留 8dp 内边距,
 *   24dp 足以把岛内首行认作窗口顶行,同时排除下方的正文或二级栏。
 * @param dragGap 覆盖按钮组前方的拖动留白宽度;传 null 时按窗口宽窄自动取默认值。
 */
@Composable
fun rememberCaptionSlot(
    topInset: Dp = 28.dp,
    dragGap: Dp? = null,
): CaptionSlot {
    val caption = LocalWindowCaption.current
    val appFullscreen = LocalAppFullscreen.current
    val desktopAppFullscreen = caption == null &&
        LocalIsDesktopWindow.current &&
        appFullscreen?.isFullscreen == true &&
        !appFullscreen.isPlayerFullscreen
    if (caption == null && !desktopAppFullscreen) return NoCaptionSlot
    val liveWindow = LocalLiveWindowInfo.current ?: LocalWindowInfo.current
    val density = LocalDensity.current
    val insetPx = with(density) { topInset.toPx() }
    val edgeTolerancePx = with(density) { CaptionEdgeTolerance.toPx() }
    val narrowWindow = liveWindow.containerSize.width < with(density) { CaptionCompactWindowWidth.toPx() }
    var touches by remember { mutableStateOf(false) }
    var atTop by remember { mutableStateOf(false) }
    val modifier = Modifier.onGloballyPositioned { coordinates ->
        if (!coordinates.isAttached) return@onGloballyPositioned
        val bounds = coordinates.boundsInWindow()
        val nearTop = bounds.top >= -4f && bounds.top <= insetPx + 2f
        atTop = nearTop
        touches = nearTop && bounds.right >= liveWindow.containerSize.width - edgeTolerancePx
    }
    val dragGapContent: (@Composable () -> Unit)? = if (touches && caption != null) {
        {
            // 按钮前固定留出一段可拖动空白:操作区按钮紧凑时无需去别处找缝隙拖窗口。
            // 窄窗口收紧间距,为顶栏标题与返回/操作按钮让出空间。
            val gapWidth = dragGap ?: if (narrowWindow) CaptionDragGapNarrow else CaptionDragGap
            Spacer(Modifier.width(gapWidth).height(CaptionDragHeight).windowDragArea())
        }
    } else {
        null
    }
    val rawButtons: (@Composable () -> Unit)? = if (touches) {
        {
            if (desktopAppFullscreen) {
                LocalFullscreenCaptionSlotTracker.current?.Track()
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(CaptionButtonSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CaptionUtilityButtons()
                caption?.Buttons()
            }
        }
    } else {
        null
    }
    return CaptionSlot(
        modifier = modifier,
        buttons = if (touches) {
            {
                dragGapContent?.invoke()
                rawButtons?.invoke()
            }
        } else {
            null
        },
        atTop = caption != null && atTop,
        active = true,
        dragGap = dragGapContent,
        rawButtons = rawButtons,
    )
}

/**
 * 桌面窗口右上角与系统三键(`— ❐ ×`)平级的通用窗口动作组:
 * `[刷新(仅非触控模式且当前最上层页面注册了刷新能力时显示)] [全屏/退出全屏(仅桌面窗口支持时显示)]`。
 */
@Composable
fun CaptionUtilityButtons(
    showFullscreen: Boolean = true,
    onFullscreenClick: (() -> Unit)? = null,
    buttonWidth: Dp = CaptionButtonSize,
    buttonHeight: Dp = CaptionButtonSize,
    buttonShape: Shape = CircleShape,
) {
    val isTouch = LocalPointerSource.current.isTouchLike
    val pageRefresh = LocalPageRefreshRegistry.current
    val appFullscreen = LocalAppFullscreen.current
    if (!isTouch && pageRefresh.canRefresh) {
        val refreshLabel = stringResource(Res.string.action_refresh)
        BilbyTooltip(text = refreshLabel, shortcut = "F5") {
            CaptionActionButton(
                icon = Icons.Filled.Refresh,
                contentDescription = refreshLabel,
                loading = pageRefresh.refreshing,
                width = buttonWidth,
                height = buttonHeight,
                shape = buttonShape,
                onClick = { pageRefresh.trigger() },
            )
        }
    }
    if (showFullscreen && appFullscreen != null) {
        val isWindowFullscreen = appFullscreen.isFullscreen
        val fullscreenLabel = stringResource(
            if (isWindowFullscreen) Res.string.player_exit_fullscreen else Res.string.player_fullscreen,
        )
        val fullscreenIcon = if (isWindowFullscreen) {
            Icons.Filled.FullscreenExit
        } else {
            Icons.Filled.Fullscreen
        }
        BilbyTooltip(text = fullscreenLabel, shortcut = "F11") {
            CaptionActionButton(
                icon = fullscreenIcon,
                contentDescription = fullscreenLabel,
                width = buttonWidth,
                height = buttonHeight,
                shape = buttonShape,
                onClick = { onFullscreenClick?.invoke() ?: appFullscreen.toggle() },
            )
        }
    }
}

/**
 * 紧邻桌面窗口控制按钮(`— ❐ ×`)排布的无边框标题栏动作按钮(如应用全屏、刷新)。
 * 与桌面圆形窗口按钮保持同一尺寸([CaptionButtonSize])、圆形热区、透明静息态、
 * 悬停(`8%`)/按下(`12%`)底色与非活动窗口变灰规则。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CaptionActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    width: Dp = CaptionButtonSize,
    height: Dp = CaptionButtonSize,
    shape: Shape = CircleShape,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val caption = LocalWindowCaption.current
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
    val contentColor = if (caption == null || caption.isActive) baseColor else baseColor.copy(alpha = 0.45f)
    val targetBackground = when {
        loading -> contentColor.copy(alpha = 0f)
        pressed -> contentColor.copy(alpha = 0.12f)
        hovered -> contentColor.copy(alpha = 0.08f)
        else -> contentColor.copy(alpha = 0f)
    }
    val background by animateColorAsState(
        targetValue = targetBackground,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "captionActionBg",
    )
    val glyphColor by animateColorAsState(
        targetValue = contentColor,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "captionActionGlyph",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(width, height)
            .clip(shape)
            .background(background)
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                enabled = !loading,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        if (loading) {
            LoadingSpinner(size = CaptionActionIconSize, color = glyphColor)
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = glyphColor,
                modifier = Modifier.size(CaptionActionIconSize),
            )
        }
    }
}

private val NoCaptionSlot = CaptionSlot(Modifier, null)

private val CaptionEdgeTolerance = 32.dp
private val CaptionDragGap = 32.dp
private val CaptionDragGapNarrow = 8.dp
private val CaptionCompactWindowWidth = 600.dp
val CaptionButtonSize = 40.dp
val CaptionButtonSpacing = 2.dp
private val CaptionActionIconSize = 18.dp
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
    if (!LocalIsDesktopWindow.current) {
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
