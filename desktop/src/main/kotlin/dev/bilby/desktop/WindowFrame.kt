package dev.bilby.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import dev.bilby.BiliLog
import dev.bilby.ui.CaptionButtonSize
import dev.bilby.ui.CaptionButtonSpacing
import dev.bilby.ui.CaptionUtilityButtons
import dev.bilby.ui.LocalAppFullscreen
import dev.bilby.ui.LocalIsDesktopWindow
import dev.bilby.ui.LocalLiveWindowInfo
import dev.bilby.ui.LocalWindowCaption
import dev.bilby.ui.LocalWindowResizing
import dev.bilby.ui.WindowCaption
import dev.bilby.ui.captionCloseLabel
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.PointerSource
import dev.bilby.ui.components.trackPointerSource
import dev.bilby.ui.theme.LocalIsDarkTheme
import dev.bilby.ui.theme.Spacing
import dev.nihildigit.windowstouch.ProvideTouchViewConfiguration
import java.awt.Frame
import java.awt.event.WindowEvent
import kotlinx.coroutines.delay
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle

/**
 * 带自绘标题栏的窗口内容:默认支持经 [LocalWindowCaption] 将窗口按钮并入各页顶栏(消除双顶栏,侧栏通顶);
 * 未声明 [WindowCaption.Host] 的界面(登录页、单栏窄窗口播放页)自动回退到顶部 32dp 标题栏。
 *
 * 系统行为经 [WindowsCaption] 保留。接管失败时(拿不到 HWND 或 FFM 调用出错)系统标题栏还在,
 * 不再画第二条。
 *
 * [showTitleBar] 为 false 时(无边框全屏、小窗)不画标题栏,整个窗口交给内容。标题栏放在
 * [content] 之前的条件分支里,出入时 [content] 在组合里的位置不变,播放页不会因此重建。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun FrameWindowScope.WindowFrame(
    title: String,
    icon: Painter?,
    showTitleBar: Boolean,
    forceActiveCaption: Boolean = false,
    fullscreenRevealBar: Boolean = false,
    onRequestClose: () -> Unit = {
        window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
    },
    content: @Composable () -> Unit,
) {
    // 在组合时就接管:此时窗口已有 HWND 而尚未显示,首帧就是去掉系统标题栏后的布局。
    val caption = remember(window, forceActiveCaption) {
        WindowsCaption(window, forceActiveAppearance = forceActiveCaption).takeIf { it.install() }
    }
    val dark = LocalIsDarkTheme.current
    // 用 DisposableEffect 而不是 LaunchedEffect:前者在组合提交时同步执行,赶在窗口首次显示之前;
    // 后者要等下一帧,窗口会先闪一下浅色边框。
    DisposableEffect(window, dark) {
        DwmWindowFrame.setDark(window, dark)
        onDispose {}
    }
    val compact = remember(caption, showTitleBar) {
        caption?.takeIf { showTitleBar }?.let(::CompactCaption)
    }
    // 界面声明了由它放按钮与拖动区(WindowCaption.Host)才收起独立标题栏;内容始终在同一个槽位组合,
    // 标题栏收起与出现时播放器和页面状态不丢。
    val hosted = showTitleBar && compact?.hosted == true
    LaunchedEffect(hosted, compact) {
        if (hosted) compact.publish()
    }
    DisposableEffect(caption, showTitleBar) {
        if (!showTitleBar) caption?.clearLayout()
        onDispose {}
    }

    // 应用级全屏顶部 UWP 风格唤出控制栏状态（鼠标移至顶边或触控/手写笔自顶边下拉呼出）。
    var revealOpen by remember { mutableStateOf(false) }
    var pointerOverSensor by remember { mutableStateOf(false) }
    var pointerOverBar by remember { mutableStateOf(false) }
    var touchPinned by remember { mutableStateOf(false) }
    var hostOrigin by remember { mutableStateOf(Offset.Zero) }
    var revealBarBounds by remember { mutableStateOf(Rect.Zero) }
    LaunchedEffect(fullscreenRevealBar) {
        if (!fullscreenRevealBar) {
            revealOpen = false
            pointerOverSensor = false
            pointerOverBar = false
            touchPinned = false
            revealBarBounds = Rect.Zero
        }
    }
    LaunchedEffect(fullscreenRevealBar, revealOpen, pointerOverSensor, pointerOverBar, touchPinned) {
        if (fullscreenRevealBar && revealOpen && !pointerOverSensor && !pointerOverBar && !touchPinned) {
            delay(FullscreenRevealHideDelayMillis)
            revealOpen = false
        }
    }

    val windowPointerSource = remember { PointerSource() }
    CompositionLocalProvider(LocalPointerSource provides windowPointerSource) {
    Column(Modifier.fillMaxSize().trackPointerSource(windowPointerSource)) {
        if (showTitleBar && caption != null && !hosted) {
            CompositionLocalProvider(LocalWindowCaption provides compact) {
                WindowsTitleBar(caption, title, icon, suppressed = compact?.suppressed == true)
            }
        }
        // Compose Desktop 的触摸拖动阈值写死 18dp,手指要划出一大截才开始滚动;换成 Android 的 8dp。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .onGloballyPositioned { coordinates ->
                    if (coordinates.isAttached) hostOrigin = coordinates.positionInWindow()
                }
                .pointerInput(fullscreenRevealBar) {
                    if (!fullscreenRevealBar) return@pointerInput
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Press && revealOpen) {
                                val pressPos = event.changes.firstOrNull()?.position
                                if (pressPos != null && !revealBarBounds.contains(pressPos + hostOrigin)) {
                                    revealOpen = false
                                    touchPinned = false
                                }
                            }
                        }
                    }
                },
        ) {
            CompositionLocalProvider(
                LocalIsDesktopWindow provides true,
                LocalWindowCaption provides compact,
            ) {
                SettledWindowInfo(caption) {
                    ProvideTouchViewConfiguration(content)
                }
            }
            // 兜底:声明了 Host 但首帧加载或滚动中途暂时没有哪一行贴在右上角画按钮时,浮在角上补一组,
            // 保证窗口任何时刻都关得掉、拖得动。
            if (hosted && !compact.hasActiveButtonBounds) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = FallbackCaptionTopPadding, end = Spacing.Cozy),
                ) {
                    Spacer(
                        modifier = Modifier
                            .width(32.dp)
                            .height(40.dp)
                            .onGloballyPositioned { compact.setFallbackDrag(it.boundsInWindow()) },
                    )
                    CompositionLocalProvider(LocalWindowCaption provides compact) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(CaptionButtonSpacing),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CaptionUtilityButtons()
                            compact.ButtonRow(countAsRow = false)
                        }
                    }
                }
            } else {
                DisposableEffect(compact) {
                    compact?.setFallbackDrag(null)
                    onDispose {}
                }
            }

            if (fullscreenRevealBar) {
                val density = LocalDensity.current
                val revealDragThresholdPx = with(density) { FullscreenRevealSwipeThreshold.toPx() }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(FullscreenRevealSensorHeight)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull()
                                    val isHoverPointer = change == null ||
                                        change.type == PointerType.Mouse ||
                                        !change.pressed
                                    when (event.type) {
                                        PointerEventType.Enter,
                                        PointerEventType.Move,
                                        -> if (isHoverPointer) {
                                            pointerOverSensor = true
                                            touchPinned = false
                                            revealOpen = true
                                        }
                                        PointerEventType.Exit -> {
                                            pointerOverSensor = false
                                        }
                                        else -> Unit
                                    }
                                }
                            }
                        }
                        .pointerInput(revealDragThresholdPx) {
                            var dragDown = 0f
                            detectVerticalDragGestures(
                                onDragStart = { dragDown = 0f },
                                onDragEnd = { dragDown = 0f },
                                onDragCancel = { dragDown = 0f },
                            ) { _, dragAmount ->
                                dragDown = (dragDown + dragAmount).coerceAtLeast(0f)
                                if (dragDown >= revealDragThresholdPx) {
                                    dragDown = 0f
                                    touchPinned = true
                                    revealOpen = true
                                }
                            }
                        },
                )
            }

            val appFullscreen = LocalAppFullscreen.current
            AnimatedVisibility(
                visible = fullscreenRevealBar && revealOpen,
                enter = slideInVertically(
                    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    initialOffsetY = { -it },
                ) + fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                exit = slideOutVertically(
                    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    targetOffsetY = { -it },
                ) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = Spacing.Tight, end = Spacing.Cozy),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    ),
                    shadowElevation = FullscreenRevealShadowElevation,
                    modifier = Modifier
                        .onGloballyPositioned { coordinates ->
                            if (coordinates.isAttached) revealBarBounds = coordinates.boundsInWindow()
                        }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    when (event.type) {
                                        PointerEventType.Enter,
                                        PointerEventType.Move,
                                        -> pointerOverBar = true
                                        PointerEventType.Exit -> pointerOverBar = false
                                        else -> Unit
                                    }
                                }
                            }
                        },
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(CaptionButtonSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(Spacing.Hair),
                    ) {
                        CaptionUtilityButtons(
                            onFullscreenClick = {
                                revealOpen = false
                                touchPinned = false
                                appFullscreen?.setFullscreen(false)
                            },
                        )
                        RevealWindowButton(
                            button = WindowsCaption.Button.MINIMIZE,
                            isMaximized = false,
                            onClick = {
                                revealOpen = false
                                touchPinned = false
                                minimizeWindow(window)
                            },
                        )
                        RevealWindowButton(
                            button = WindowsCaption.Button.MAXIMIZE,
                            isMaximized = true,
                            onClick = {
                                revealOpen = false
                                touchPinned = false
                                if (appFullscreen != null) {
                                    appFullscreen.setFullscreen(false)
                                } else {
                                    toggleMaximizeWindow(window)
                                }
                            },
                        )
                        RevealWindowButton(
                            button = WindowsCaption.Button.CLOSE,
                            isMaximized = false,
                            contentDescription = captionCloseLabel(),
                            onClick = {
                                revealOpen = false
                                touchPinned = false
                                onRequestClose()
                            },
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * 拖拉窗口边框(`WM_ENTERSIZEMOVE`..`WM_EXITSIZEMOVE`)期间冻结 [LocalWindowInfo] 的 `containerSize`,
 * 避免跨过响应式断点时在拖拉中途反复拆装树结构与弹层;松开鼠标后一次性按落定的尺寸切换。
 */
@Composable
private fun SettledWindowInfo(
    caption: WindowsCaption?,
    content: @Composable () -> Unit,
) {
    val live = LocalWindowInfo.current
    val sizing = caption?.isSizing == true
    val liveSize = live.containerSize
    var settledSize by remember { mutableStateOf(liveSize) }
    if (!sizing || settledSize == IntSize.Zero) {
        settledSize = liveSize
    }
    val settled = remember(live) {
        object : WindowInfo by live {
            override val containerSize: IntSize get() = settledSize
        }
    }
    CompositionLocalProvider(
        LocalWindowInfo provides settled,
        LocalLiveWindowInfo provides live,
        LocalWindowResizing provides sizing,
        content = content,
    )
}

/**
 * 并进内容的紧凑标题栏:汇总内容各处登记的拖动区与右上角行的按钮坐标,一并交给 [WindowsCaption]。
 * 没有谁声明 [Host] 时不写布局,交由外层的 [WindowsTitleBar] 管理。
 */
private class CompactCaption(private val caption: WindowsCaption) : WindowCaption {
    override val isActive: Boolean get() = caption.isActive

    private var hosts by mutableIntStateOf(0)
    val hosted: Boolean get() = hosts > 0

    private var suppressors by mutableIntStateOf(0)
    val suppressed: Boolean get() = suppressors > 0

    private val activeRows = mutableStateListOf<Any>()
    private val dragAreas = HashMap<Any, Rect>()
    private var fallbackDrag: Rect? = null
    private val buttons = mutableStateMapOf<Any, Map<WindowsCaption.Button, Rect>>()

    val hasActiveButtonBounds: Boolean get() = activeButtonBounds() != null

    // 转场或侧栏覆盖期间可能同时存在两行,由最后挂载且三颗按钮坐标齐全的那一行接管命中。
    private fun activeButtonBounds(): Map<WindowsCaption.Button, Rect>? =
        activeRows.asReversed().firstNotNullOfOrNull { key ->
            buttons[key]?.takeIf { bounds ->
                WindowsCaption.Button.entries.all { button -> bounds[button]?.isEmpty == false }
            }
        }

    @Composable
    override fun Host() {
        DisposableEffect(this) {
            hosts++
            onDispose { hosts-- }
        }
    }

    @Composable
    override fun Suppress() {
        DisposableEffect(this) {
            suppressors++
            publish()
            onDispose {
                suppressors--
                publish()
            }
        }
    }

    @Composable
    override fun Buttons() = ButtonRow(countAsRow = true)

    @Composable
    fun ButtonRow(countAsRow: Boolean) {
        val key = remember { Any() }
        DisposableEffect(this, key, countAsRow) {
            if (countAsRow) activeRows.add(key)
            onDispose {
                activeRows.remove(key)
                buttons.remove(key)
                publish()
            }
        }
        val color = MaterialTheme.colorScheme.onSurfaceVariant.let {
            if (caption.isActive) it else it.copy(alpha = 0.45f)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(CaptionButtonSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (button in WindowsCaption.Button.entries) {
                RoundCaptionButton(
                    button = button,
                    isMaximized = caption.isMaximized,
                    hovered = caption.hovered == button,
                    pressed = caption.pressed == button && caption.hovered == button,
                    contentColor = color,
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        if (!coordinates.isAttached) return@onGloballyPositioned
                        val bounds = coordinates.boundsInWindow()
                        val current = buttons[key]
                        if (current?.get(button) != bounds) {
                            buttons[key] = (current ?: emptyMap()) + (button to bounds)
                            publish()
                        }
                    },
                )
            }
        }
    }

    override fun setDragArea(key: Any, bounds: Rect?) {
        val normalized = bounds?.takeUnless { it.isEmpty }
        if (dragAreas[key] == normalized) return
        if (normalized == null) dragAreas.remove(key) else dragAreas[key] = normalized
        publish()
    }

    fun setFallbackDrag(bounds: Rect?) {
        val normalized = bounds?.takeUnless { it.isEmpty }
        if (fallbackDrag == normalized) return
        fallbackDrag = normalized
        publish()
    }

    fun publish() {
        if (!hosted) return
        if (suppressed) {
            caption.clearLayout()
            return
        }
        val buttonBounds = activeButtonBounds()
            ?: buttons.entries.firstOrNull { it.key !in activeRows }?.value.orEmpty()
        val drags = buildList {
            addAll(dragAreas.values)
            fallbackDrag?.let(::add)
        }
        caption.updateLayout(drags, buttonBounds.filterValues { !it.isEmpty })
    }
}

/**
 * 并进内容时的圆形窗口按钮(40dp),与所在顶栏的 M3 `IconButton` 视觉圆直径、水平中线完全一致;
 * 字形仍取系统 Segoe 图标字体,关闭键悬停红底白字。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RoundCaptionButton(
    button: WindowsCaption.Button,
    isMaximized: Boolean,
    hovered: Boolean,
    pressed: Boolean,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val isClose = button == WindowsCaption.Button.CLOSE
    val targetBackground = when {
        isClose && pressed -> CloseRedPressed
        isClose && hovered -> CloseRed
        pressed -> contentColor.copy(alpha = 0.12f)
        hovered -> contentColor.copy(alpha = 0.08f)
        else -> contentColor.copy(alpha = 0f)
    }
    val targetGlyphColor = when {
        isClose && pressed -> Color.White.copy(alpha = 0.85f)
        isClose && hovered -> Color.White
        else -> contentColor
    }
    val background by animateColorAsState(
        targetValue = targetBackground,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "roundCaptionBg",
    )
    val glyphColor by animateColorAsState(
        targetValue = targetGlyphColor,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "roundCaptionGlyph",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(CaptionButtonSize).clip(CircleShape).background(background),
    ) {
        CaptionGlyphIcon(button, isMaximized, glyphColor)
    }
}

private val FallbackCaptionTopPadding = 20.dp
private val FullscreenRevealSensorHeight = 4.dp
private val FullscreenRevealSwipeThreshold = 12.dp
private val FullscreenRevealShadowElevation = 6.dp
private const val FullscreenRevealHideDelayMillis = 350L

@Composable
private fun RevealWindowButton(
    button: WindowsCaption.Button,
    isMaximized: Boolean,
    onClick: () -> Unit,
    contentDescription: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    RoundCaptionButton(
        button = button,
        isMaximized = isMaximized,
        hovered = hovered,
        pressed = pressed,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    )
}

private fun minimizeWindow(window: java.awt.Window) {
    val handle = (window as? ComposeWindow)?.windowHandle ?: 0L
    if (handle != 0L && System.getProperty("os.name").orEmpty().startsWith("Windows")) {
        runCatching {
            User32.INSTANCE.ShowWindow(HWND(Pointer(handle)), WinUser.SW_MINIMIZE)
            return
        }.onFailure { BiliLog.w("全屏栏最小化窗口失败，回退 AWT", it) }
    }
    if (window is Frame) {
        window.extendedState = window.extendedState or Frame.ICONIFIED
    }
}

private fun toggleMaximizeWindow(window: java.awt.Window) {
    if (window is Frame) {
        val maximized = (window.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH
        window.extendedState = if (maximized) {
            window.extendedState and Frame.MAXIMIZED_BOTH.inv()
        } else {
            window.extendedState or Frame.MAXIMIZED_BOTH
        }
    }
}

/** 与 Windows 11 标题栏按钮(46×32 epx)同高。 */
private val TitleBarHeight = 32.dp
private val TitleBarUtilityButtonWidth = 40.dp

@Composable
private fun WindowsTitleBar(
    caption: WindowsCaption,
    title: String,
    icon: Painter?,
    suppressed: Boolean = false,
) {
    // 各部分在根坐标系里的位置,每次变化都汇总成一份交给窗口过程做命中测试。只在布局回调里读写,
    // 不参与重组,所以不用 State。
    val bounds = remember { CaptionBounds() }
    fun publish() {
        if (suppressed) caption.clearLayout() else caption.updateLayout(bounds.bar, bounds.buttons.toMap())
    }
    LaunchedEffect(suppressed) { publish() }
    // 标题栏撤下(进全屏、进小窗、切进紧凑顶栏页)时清掉布局,窗口过程不再把这块答成标题栏。
    DisposableEffect(caption) {
        onDispose { caption.clearLayout() }
    }

    val colors = MaterialTheme.colorScheme
    // Windows 11 的非活动窗口标题与按钮图标变灰。
    val contentColor = if (caption.isActive) colors.onSurface else colors.onSurface.copy(alpha = 0.45f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(colors.surface),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onGloballyPositioned {
                    bounds.bar = it.boundsInWindow()
                    publish()
                },
        ) {
            Spacer(Modifier.width(16.dp))
            if (icon != null) {
                Image(icon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = title,
                color = contentColor,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        CaptionUtilityButtons(
            buttonWidth = TitleBarUtilityButtonWidth,
            buttonHeight = TitleBarHeight,
            buttonShape = RectangleShape,
        )
        for (button in WindowsCaption.Button.entries) {
            CaptionButton(
                button = button,
                isMaximized = caption.isMaximized,
                hovered = caption.hovered == button,
                pressed = caption.pressed == button && caption.hovered == button,
                contentColor = contentColor,
                modifier = Modifier.onGloballyPositioned {
                    bounds.buttons[button] = it.boundsInWindow()
                    publish()
                },
            )
        }
    }
}

private class CaptionBounds {
    var bar: Rect = Rect.Zero
    val buttons = mutableMapOf<WindowsCaption.Button, Rect>()
}

/**
 * 标题栏按钮。悬停与按下状态来自窗口过程而不是 Compose 的指针事件:这三块在系统看来是非客户区,
 * 鼠标消息不发给画布,见 [WindowsCaption]。
 *
 * 配色照 Windows 11 系统按钮,不取主题的 container 色:这三个按钮模仿的是系统外框,与相邻窗口的
 * 按钮一致比与应用内控件一致要紧。普通按钮悬停与按下各叠一层 6% 与 4% 的前景色,关闭按钮悬停为
 * 红底白字。
 */
@Composable
private fun CaptionButton(
    button: WindowsCaption.Button,
    isMaximized: Boolean,
    hovered: Boolean,
    pressed: Boolean,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val isClose = button == WindowsCaption.Button.CLOSE
    val background = when {
        isClose && pressed -> CloseRed.copy(alpha = 0.9f)
        isClose && hovered -> CloseRed
        pressed -> contentColor.copy(alpha = 0.04f)
        hovered -> contentColor.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    val glyphColor = when {
        isClose && pressed -> Color.White.copy(alpha = 0.7f)
        isClose && hovered -> Color.White
        pressed -> contentColor.copy(alpha = contentColor.alpha * 0.6f)
        else -> contentColor
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(46.dp, TitleBarHeight).background(background),
    ) {
        CaptionGlyphIcon(button, isMaximized, glyphColor)
    }
}

@Composable
private fun CaptionGlyphIcon(
    button: WindowsCaption.Button,
    isMaximized: Boolean,
    glyphColor: Color,
) {
    val glyph = when (button) {
        WindowsCaption.Button.MINIMIZE -> CaptionGlyph.Minimize
        WindowsCaption.Button.MAXIMIZE -> if (isMaximized) CaptionGlyph.Restore else CaptionGlyph.Maximize
        WindowsCaption.Button.CLOSE -> CaptionGlyph.Close
    }
    val iconFont = CaptionIconFont
    if (iconFont != null) {
        Text(glyph.char.toString(), color = glyphColor, fontFamily = iconFont, fontSize = 10.sp)
    } else {
        Icon(glyph.fallback, contentDescription = null, tint = glyphColor, modifier = Modifier.size(14.dp))
    }
}

private val CloseRed = Color(0xFFC42B1C)
private val CloseRedPressed = Color(0xFFB12618)

/** 码位在 Segoe Fluent Icons(Windows 11)与 Segoe MDL2 Assets(Windows 10)里相同。 */
private enum class CaptionGlyph(val char: Char, val fallback: ImageVector) {
    Minimize(Char(0xE921), Icons.Filled.Remove),
    Maximize(Char(0xE922), Icons.Filled.CropSquare),
    Restore(Char(0xE923), Icons.Filled.FilterNone),
    Close(Char(0xE8BB), Icons.Filled.Close),
}

/**
 * 系统标题栏按钮用的图标字体,与系统按钮的字形一致。两个都没有时用 Material 图标代替。
 * 按名字直接查 FontMgr:Compose 的 FontFamily(名字) 找不到字体时静默换成默认字体,私用区码位会画成方框。
 */
private val CaptionIconFont: FontFamily? by lazy {
    listOf("Segoe Fluent Icons", "Segoe MDL2 Assets").firstNotNullOfOrNull { name ->
        runCatching { FontMgr.default.matchFamilyStyle(name, FontStyle.NORMAL) }
            .onFailure { BiliLog.w("标题栏图标字体 $name 查找失败", it) }
            .getOrNull()
            ?.let { FontFamily(Typeface(it)) }
    }
}
