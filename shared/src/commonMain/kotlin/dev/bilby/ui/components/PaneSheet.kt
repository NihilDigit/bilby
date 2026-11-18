package dev.bilby.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.bilby.resources.Res
import dev.bilby.resources.action_back
import dev.bilby.resources.action_close
import dev.bilby.stringResource
import dev.bilby.ui.BackHandler
import dev.bilby.ui.CaptionDragHeight
import dev.bilby.ui.LocalWindowCaption
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.rememberCaptionSlot
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.useTwoPaneMediaLayout
import dev.bilby.ui.windowDragArea

/**
 * 两栏布局里右栏的面板层。视频页、直播间宽屏时,右栏是简介与评论,二级面板(评论详情、
 * 分集、播放设置……)在这一栏里从右边缘划进来,盖住这一栏,不盖画面。
 *
 * 宿主状态提供在整个两栏布局上,而面板画在右栏里:播放设置是从左栏的播放器里打开的,
 * 它要能找到右栏。右栏不在组合里时(全屏)[hosted] 为 false,面板退回底部或侧边 sheet。
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

internal class PaneSlots(
    val title: String?,
    val modifier: Modifier,
    val headerTrailing: (@Composable RowScope.() -> Unit)?,
    val subheader: (@Composable ColumnScope.() -> Unit)?,
    val footer: (@Composable ColumnScope.() -> Unit)?,
    val content: @Composable PaneSheetScope.() -> Unit,
)

internal class PanePanel(
    val onDismiss: () -> Unit,
    /** 常驻面板,见 [PaneSheet] 的同名参数。 */
    val persistent: Boolean,
    val slots: () -> PaneSlots,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PanePanelSurface(panel: PanePanel) {
    val slots = panel.slots()
    // 不压暗底下的简介与评论:面板盖满这一栏,底下本来就看不见;左栏的画面也不该变暗。
    Surface(modifier = slots.modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 常驻面板的标题行归内容自己画(标题带着内容的状态,关闭在右端);这里再画一行
            // 返回箭头,就是两行标题摞在一起。
            if (!panel.persistent) {
                val captionSlot = rememberCaptionSlot()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = captionSlot.modifier
                        .fillMaxWidth()
                        .padding(end = if (captionSlot.buttons != null) Spacing.Hair else 0.dp),
                ) {
                    val backLabel = stringResource(Res.string.action_back)
                    BilbyTooltip(text = backLabel, shortcut = "Esc") {
                        IconButton(onClick = panel.onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backLabel)
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = CaptionDragHeight)
                            .windowDragArea(captionSlot.atTop),
                    ) {
                        slots.title?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(end = Spacing.Comfortable),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    if (slots.headerTrailing != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                            modifier = Modifier.padding(end = Spacing.Tight),
                            content = slots.headerTrailing,
                        )
                    }
                    captionSlot.buttons?.invoke()
                }
            }
            slots.subheader?.invoke(this)
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                PaneSheetScopeImpl(this, inPane = true).apply {
                    slots.content(this)
                }
            }
            SheetFooterSlot(footer = slots.footer, applyBottomInsetsWhenEmpty = false)
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
    /** 此刻画在右栏里,而不是底部或侧边 sheet 里。 */
    val inPane: Boolean
}

private class PaneSheetScopeImpl(
    column: ColumnScope,
    override val inPane: Boolean,
) : PaneSheetScope, ColumnScope by column

/**
 * 统一二级面板。两栏布局里画在右栏(见 [SidePaneState]);没有右栏可挂时,宽屏(`Expanded+`)是从右边缘划进来的
 * 模态侧边面板([ModalSideSheet]),窄屏是底部 sheet([ModalBottomSheet])。
 *
 * 标题行(`title` / `headerTrailing`)、固定副头栏(`subheader`)、弹性主体与常驻底栏(`footer`)在三种形态下
 * 统一渲染,调用方无需再手写 `if (!inPane) Text(title)` 或自行拼装底部安全区。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@NonSkippableComposable
@Composable
fun PaneSheet(
    onDismissRequest: () -> Unit,
    title: String? = null,
    /** sheet 形态下跳过半开(见 [rememberExpandedSheetState])。带底栏/输入框或紧凑内容默认跳过。 */
    skipPartiallyExpanded: Boolean = true,
    /**
     * 右栏里常驻:点左栏不收起,也不画统一标题行,标题与关闭按钮由内容自己画(如找相关)。
     */
    persistent: Boolean = false,
    modifier: Modifier = Modifier,
    headerTrailing: (@Composable RowScope.() -> Unit)? = null,
    subheader: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    maxHeightFraction: Float = DefaultBottomSheetMaxHeightFraction,
    content: @Composable PaneSheetScope.() -> Unit,
) {
    val pane = LocalSidePane.current
    if (pane != null && pane.hosted) {
        // **右栏里的面板不在调用方的组合位置上**,调用方重组不会顺带重画它,只能靠这份状态
        // 通知。用 neverEqualPolicy 保证每次重组捕获到的新值都能推给右栏面板。
        val slotsState = remember {
            mutableStateOf(
                PaneSlots(title, modifier, headerTrailing, subheader, footer, content),
                neverEqualPolicy(),
            )
        }
        SideEffect {
            slotsState.value = PaneSlots(title, modifier, headerTrailing, subheader, footer, content)
        }
        val latestDismiss by rememberUpdatedState(onDismissRequest)
        // 返回键先关面板。面板画在右栏里,不像 ModalBottomSheet 自带返回处理。
        BackHandler { latestDismiss() }
        DisposableEffect(pane, persistent) {
            val panel = PanePanel(
                onDismiss = { latestDismiss() },
                persistent = persistent,
                slots = { slotsState.value },
            )
            pane.panel = panel
            onDispose { if (pane.panel === panel) pane.panel = null }
        }
    } else if (rememberBilbyWindowSize().useTwoPaneMediaLayout) {
        ModalSideSheet(
            onDismissRequest = onDismissRequest,
            title = title,
            persistent = persistent,
            modifier = modifier,
            headerTrailing = headerTrailing,
            subheader = subheader,
            footer = footer,
            content = content,
        )
    } else {
        // 用鼠标时一律不停半开。sheet 把滚动当成拖它自己:半开时往下滚先被 sheet 拿去往上挪,
        // 而滚轮没有松手那一下,sheet 不会吸附到哪一档,表现是列表滚不动。
        val pointers = LocalPointerSource.current
        val forceExpanded = skipPartiallyExpanded || footer != null || !pointers.isTouchLike
        val sheetState = if (forceExpanded) {
            rememberExpandedSheetState()
        } else {
            rememberBottomSheetState(SheetValue.Hidden)
        }
        val windowHeight = with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height.toDp()
        }
        val maxSheetHeight = windowHeight * maxHeightFraction.coerceIn(MinSheetHeightFraction, 1f)
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            shape = MaterialTheme.shapes.extraLarge,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentWindowInsets = { WindowInsets(0) },
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .imePadding()
                    .trackPointerSource(pointers)
                    .nestedScroll(remember(pointers) { WheelStopsAtSheet(pointers) }),
            ) {
                if (!persistent && (title != null || headerTrailing != null)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = Spacing.Loose,
                                end = if (headerTrailing != null) Spacing.Cozy else Spacing.Loose,
                                bottom = Spacing.Tight,
                            ),
                    ) {
                        if (title != null) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        if (headerTrailing != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                                content = headerTrailing,
                            )
                        }
                    }
                }
                subheader?.invoke(this)
                Column(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    PaneSheetScopeImpl(this, inPane = false).content()
                }
                SheetFooterSlot(footer = footer, applyBottomInsetsWhenEmpty = true)
            }
        }
    }
}

@Composable
private fun SheetFooterSlot(
    footer: (@Composable ColumnScope.() -> Unit)?,
    applyBottomInsetsWhenEmpty: Boolean,
) {
    if (footer != null) {
        // 用 surfaceContainer 色阶差承托粘性底栏,不画分割线(docs/ui-style-guide.md §2.3c)。
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = Spacing.Loose, vertical = Spacing.Cozy),
                content = footer,
            )
        }
    } else if (applyBottomInsetsWhenEmpty) {
        Spacer(modifier = Modifier.navigationBarsPadding().imePadding())
    }
}

/**
 * 宽屏的模态侧边面板,底部 sheet 在大窗口里的替身(M3 `side-sheets.md` modal 款):
 * 容器 `surfaceContainerLow`,圆角 `shapes.large`(16dp),最大宽 400dp,四周 16dp 悬浮岛边距,
 * 背景 0.32 半透明遮罩,单行 56dp 标题栏(`[title] [headerTrailing] [Close]`),并避让系统栏、挖孔与软键盘。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModalSideSheet(
    onDismissRequest: () -> Unit,
    title: String?,
    persistent: Boolean,
    modifier: Modifier,
    headerTrailing: (@Composable RowScope.() -> Unit)?,
    subheader: (@Composable ColumnScope.() -> Unit)?,
    footer: (@Composable ColumnScope.() -> Unit)?,
    content: @Composable PaneSheetScope.() -> Unit,
) {
    val visibility = remember { MutableTransitionState(false).apply { targetState = true } }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val dismiss = { visibility.targetState = false }
    // 划出去的动画走完才真正关:此刻才通知调用方把它移出组合。
    LaunchedEffect(visibility.isIdle, visibility.currentState) {
        if (visibility.isIdle && !visibility.currentState && !visibility.targetState) latestDismiss()
    }
    val pointers = LocalPointerSource.current
    Dialog(onDismissRequest = dismiss, properties = fullScreenDialogProperties()) {
        LocalWindowCaption.current?.Suppress()
        DialogIntoDisplayCutout()
        Box(modifier = Modifier.fillMaxSize().trackPointerSource(pointers)) {
            AnimatedVisibility(
                visibleState = visibility,
                enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                modifier = Modifier.fillMaxSize(),
            ) {
                // 点在面板外面就是关掉。不画按下的波纹:这一整片是遮罩,不是一个按钮。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = ModalSideSheetScrimAlpha))
                        .clickable(interactionSource = null, indication = null, onClick = dismiss),
                )
            }
            AnimatedVisibility(
                visibleState = visibility,
                enter = slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it } + fadeIn(),
                exit = slideOutHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) { it } + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = modifier
                        .windowInsetsPadding(WindowInsets.barsAndCutout.union(WindowInsets.ime))
                        .padding(Spacing.Comfortable)
                        .widthIn(max = ModalSideSheetMaxWidth)
                        .fillMaxWidth()
                        .fillMaxHeight(),
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!persistent) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = ModalSideSheetHeaderHeight)
                                    .padding(start = Spacing.Loose, end = Spacing.Tight),
                            ) {
                                if (title != null) {
                                    Text(
                                        text = title,
                                        style = MaterialTheme.typography.titleMediumEmphasized,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = Spacing.Tight),
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                if (headerTrailing != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                                        content = headerTrailing,
                                    )
                                }
                                val closeLabel = stringResource(Res.string.action_close)
                                BilbyTooltip(text = closeLabel, shortcut = "Esc") {
                                    IconButton(onClick = dismiss) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = closeLabel,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        subheader?.invoke(this)
                        Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            PaneSheetScopeImpl(this, inPane = false).content()
                        }
                        SheetFooterSlot(footer = footer, applyBottomInsetsWhenEmpty = true)
                    }
                }
            }
        }
    }
}

private const val DefaultBottomSheetMaxHeightFraction = 0.88f
private const val MinSheetHeightFraction = 0.4f
private const val ModalSideSheetScrimAlpha = 0.32f

/** modal side sheet 的最大宽度与标题栏高度(side-sheets.md measurements 表)。 */
private val ModalSideSheetMaxWidth = 400.dp
private val ModalSideSheetHeaderHeight = 56.dp
