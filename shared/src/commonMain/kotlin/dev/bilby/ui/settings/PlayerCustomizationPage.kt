package dev.bilby.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.bilby.data.CenterClusterConfig
import dev.bilby.data.CenterSlotAction
import dev.bilby.data.CenterSlotConfig
import dev.bilby.data.CenterSlotPosition
import dev.bilby.data.CenterSlotScale
import dev.bilby.data.ListenCoverStyle
import dev.bilby.data.ListenLayoutConfig
import dev.bilby.data.ListenQuickChip
import dev.bilby.data.MAX_DOUBLE_TAP_ZONE_FRACTION
import dev.bilby.data.MAX_SEEK_SECONDS
import dev.bilby.data.MAX_VERTICAL_ZONE_FRACTION
import dev.bilby.data.MIN_DOUBLE_TAP_ZONE_FRACTION
import dev.bilby.data.MIN_SEEK_SECONDS
import dev.bilby.data.MIN_VERTICAL_ZONE_FRACTION
import dev.bilby.data.PlayerBarSlot
import dev.bilby.data.PlayerControlId
import dev.bilby.data.PlayerFormFactorLayout
import dev.bilby.data.PlayerLayoutMode
import dev.bilby.data.PlayerLayoutPrefs
import dev.bilby.data.PlayerLayoutPreset
import dev.bilby.data.PlayerPanelItem
import dev.bilby.data.SEEK_SECONDS_PRESETS
import dev.bilby.data.SettingsStore
import dev.bilby.data.SwipeSeekSensitivity
import dev.bilby.data.VerticalSwipeAction
import kotlin.math.roundToInt
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.BilbyIcons
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.trackEditableFocus
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.player.BatteryStatus
import dev.bilby.ui.player.ControlScrimBottom
import dev.bilby.ui.player.MediaBorderlessTextShadow
import dev.bilby.ui.player.NetworkKind
import dev.bilby.ui.player.PlayerStatusCluster
import dev.bilby.ui.player.PlayerSystemStatus
import dev.bilby.ui.player.formatSpeed
import dev.bilby.ui.player.mediaControlContainer
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.PlayerTheme
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular

private enum class EditorSurfaceMode {
    Embedded,
    Fullscreen,
    Listen,
    Gestures,
}

private enum class ListenEditorSection {
    Cover,
    SideSlot,
    QuickChips,
}

private sealed interface PreviewSelection {
    data class CenterSlot(val position: CenterSlotPosition) : PreviewSelection
    data class PanelItem(val bar: PlayerBarSlot, val index: Int) : PreviewSelection
}

private sealed interface DragPayload {
    data class PaletteControl(val id: PlayerControlId) : DragPayload
    data object PaletteSpacer : DragPayload
    data class PaletteCenterAction(val action: CenterSlotAction) : DragPayload
    data class PlacedBarItem(
        val bar: PlayerBarSlot,
        val index: Int,
        val item: PlayerPanelItem,
    ) : DragPayload

    fun toCenterSlotActionOrNull(seekSeconds: Int): CenterSlotAction? = when (this) {
        is PaletteCenterAction -> action
        is PaletteControl -> id.toCenterActionOrNull(seekSeconds)
        is PlacedBarItem -> (item as? PlayerPanelItem.Control)?.id?.toCenterActionOrNull(seekSeconds)
        PaletteSpacer -> null
    }

    fun toPanelItemOrNull(): PlayerPanelItem? = when (this) {
        is PaletteControl -> PlayerPanelItem.Control(id)
        PaletteSpacer -> PlayerPanelItem.Spacer
        is PaletteCenterAction -> action.toControlIdOrNull()?.let { PlayerPanelItem.Control(it) }
        is PlacedBarItem -> item
    }
}

private fun PlayerControlId.toCenterActionOrNull(seekSeconds: Int): CenterSlotAction? = when (this) {
    PlayerControlId.Previous -> CenterSlotAction.Previous
    PlayerControlId.Next -> CenterSlotAction.Next
    PlayerControlId.SeekBackward -> CenterSlotAction.SeekBackward(seekSeconds)
    PlayerControlId.SeekForward -> CenterSlotAction.SeekForward(seekSeconds)
    else -> null
}

private data class ActiveDrag(
    val payload: DragPayload,
    val pointerInWindow: Offset,
)

private sealed interface DropTarget {
    data class BarInsert(val bar: PlayerBarSlot, val insertIndex: Int) : DropTarget
    data class CenterSlot(val position: CenterSlotPosition) : DropTarget
    data object Remove : DropTarget
}

private class OffsetRef(var value: Offset = Offset.Zero)

@Stable
private class PlayerEditorDragState {
    var activeDrag by mutableStateOf<ActiveDrag?>(null)
    var rootOriginInWindow: Offset = Offset.Zero
    var topBarBounds: Rect? = null
    var bottomBarBounds: Rect? = null
    var paletteBounds: Rect? = null
    var previewCanvasBounds: Rect? = null
    val barItemBounds = mutableMapOf<Pair<PlayerBarSlot, Int>, Rect>()
    val centerSlotBounds = mutableMapOf<CenterSlotPosition, Rect>()

    fun startDrag(payload: DragPayload, pointerInWindow: Offset) {
        if (!pointerInWindow.x.isFinite() || !pointerInWindow.y.isFinite()) return
        activeDrag = ActiveDrag(payload, pointerInWindow)
    }

    fun updateDrag(pointerInWindow: Offset) {
        if (!pointerInWindow.x.isFinite() || !pointerInWindow.y.isFinite()) return
        val current = activeDrag ?: return
        activeDrag = current.copy(pointerInWindow = pointerInWindow)
    }

    fun cancelDrag() {
        activeDrag = null
    }

    fun pruneBarBounds(topSize: Int, bottomSize: Int) {
        barItemBounds.keys.removeAll { (bar, idx) ->
            when (bar) {
                PlayerBarSlot.Top -> idx >= topSize
                PlayerBarSlot.Bottom -> idx >= bottomSize
            }
        }
    }

    fun clearBounds() {
        topBarBounds = null
        bottomBarBounds = null
        paletteBounds = null
        previewCanvasBounds = null
        barItemBounds.clear()
        centerSlotBounds.clear()
    }

    fun resolveDropTarget(
        layout: PlayerFormFactorLayout,
        isFullscreen: Boolean,
        showCenterControls: Boolean,
        seekSeconds: Int,
    ): DropTarget? {
        val drag = activeDrag ?: return null
        val pointer = drag.pointerInWindow

        // 1. 中央 4 槽位命中检测（仅支持可映射为中央动作的负载）
        if (showCenterControls && drag.payload.toCenterSlotActionOrNull(seekSeconds) != null) {
            val sidePositions = listOf(
                CenterSlotPosition.OuterLeft,
                CenterSlotPosition.InnerLeft,
                CenterSlotPosition.InnerRight,
                CenterSlotPosition.OuterRight,
            )
            for (pos in sidePositions) {
                val rect = centerSlotBounds[pos] ?: continue
                if (rect.inflate(14f).contains(pointer)) {
                    return DropTarget.CenterSlot(pos)
                }
            }
        }

        // 2. 顶栏与底栏插入位置计算
        if (drag.payload.toPanelItemOrNull() != null) {
            val topRect = topBarBounds
            if (topRect != null && topRect.inflate(18f).contains(pointer)) {
                val insertIndex = computeBarInsertIndex(
                    bar = PlayerBarSlot.Top,
                    items = layout.topBar,
                    pointerX = pointer.x,
                    isFullscreen = isFullscreen,
                )
                return DropTarget.BarInsert(PlayerBarSlot.Top, insertIndex)
            }
            val bottomRect = bottomBarBounds
            if (bottomRect != null && bottomRect.inflate(18f).contains(pointer)) {
                val insertIndex = computeBarInsertIndex(
                    bar = PlayerBarSlot.Bottom,
                    items = layout.bottomBar,
                    pointerX = pointer.x,
                    isFullscreen = isFullscreen,
                )
                return DropTarget.BarInsert(PlayerBarSlot.Bottom, insertIndex)
            }
        }

        // 3. 拖回托盘或拖出画布移除已放置的栏位项
        if (drag.payload is DragPayload.PlacedBarItem) {
            val overPalette = paletteBounds?.inflate(12f)?.contains(pointer) == true
            val outsideCanvas = previewCanvasBounds?.let { !it.inflate(24f).contains(pointer) } == true
            if (overPalette || outsideCanvas) {
                return DropTarget.Remove
            }
        }

        return null
    }

    private fun computeBarInsertIndex(
        bar: PlayerBarSlot,
        items: List<PlayerPanelItem>,
        pointerX: Float,
        isFullscreen: Boolean,
    ): Int {
        var idx = items.size
        for (i in items.indices) {
            val rect = barItemBounds[bar to i] ?: continue
            if (pointerX < rect.center.x) {
                idx = i
                break
            }
        }
        val minIndex = if (
            isFullscreen &&
            bar == PlayerBarSlot.Top &&
            items.firstOrNull().let { it is PlayerPanelItem.Control && it.id == PlayerControlId.Back }
        ) {
            1
        } else {
            0
        }
        return idx.coerceIn(minIndex, items.size)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerCustomizationPage(
    state: SettingsUiState,
    onUpdateLayout: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
    onResetLayout: () -> Unit,
    onDoubleTapSeekChange: (Boolean) -> Unit,
    onLongPressSpeedChange: (Boolean) -> Unit,
    onLongPressSpeedDialChange: (Boolean) -> Unit,
    onDefaultHoldSpeedChange: (Float) -> Unit,
    onSwipeSeekChange: (Boolean) -> Unit,
    onSwipeBrightnessVolumeChange: (Boolean) -> Unit,
    onBack: (() -> Unit)?,
) {
    var surfaceMode by rememberSaveable { mutableStateOf(EditorSurfaceMode.Embedded) }
    val isListenMode = surfaceMode == EditorSurfaceMode.Listen
    val isGestureMode = surfaceMode == EditorSurfaceMode.Gestures
    val editingFullscreen = surfaceMode == EditorSurfaceMode.Fullscreen
    val layoutMode = if (editingFullscreen) PlayerLayoutMode.Fullscreen else PlayerLayoutMode.Embedded
    val layoutPrefs = state.playerLayout
    val activeLayout = layoutPrefs.layoutFor(layoutMode)
    val defaultTouchPreview = LocalPointerSource.current.isTouchLike
    var previewTouchMode by rememberSaveable { mutableStateOf(defaultTouchPreview) }
    val previewTransportInCenter = previewTouchMode &&
        layoutPrefs.showCenterControlsOnTouch &&
        activeLayout.center.showCenterPlay

    var selection by remember {
        mutableStateOf<PreviewSelection?>(PreviewSelection.CenterSlot(CenterSlotPosition.InnerLeft))
    }
    var listenSection by rememberSaveable { mutableStateOf(ListenEditorSection.SideSlot) }
    var selectedListenSlot by rememberSaveable { mutableStateOf(CenterSlotPosition.InnerLeft) }
    val dragState = remember { PlayerEditorDragState() }

    LaunchedEffect(surfaceMode) {
        dragState.cancelDrag()
        dragState.clearBounds()
    }

    LaunchedEffect(previewTransportInCenter) {
        dragState.centerSlotBounds.clear()
    }

    // 切换形态或列表长度变化时修正越界选中索引并清理过期边界缓存。
    LaunchedEffect(
        layoutMode,
        activeLayout.topBar.size,
        activeLayout.bottomBar.size,
    ) {
        dragState.pruneBarBounds(activeLayout.topBar.size, activeLayout.bottomBar.size)
        val current = selection ?: return@LaunchedEffect
        if (current is PreviewSelection.PanelItem) {
            val size = activeLayout.itemsFor(current.bar).size
            if (current.index !in 0 until size) {
                selection = if (size > 0) {
                    PreviewSelection.PanelItem(current.bar, (size - 1).coerceAtLeast(0))
                } else {
                    null
                }
            }
        }
    }

    val currentDropTarget = if (!isListenMode && !isGestureMode) {
        dragState.resolveDropTarget(
            layout = activeLayout,
            isFullscreen = editingFullscreen,
            showCenterControls = true,
            seekSeconds = layoutPrefs.seekButtonSeconds,
        )
    } else {
        null
    }

    val finishDrag: () -> Unit = {
        val drag = dragState.activeDrag
        val target = currentDropTarget
        dragState.cancelDrag()
        if (drag != null && target != null) {
            when (target) {
                is DropTarget.CenterSlot -> {
                    val action = drag.payload.toCenterSlotActionOrNull(layoutPrefs.seekButtonSeconds)
                    if (action != null) {
                        onUpdateLayout { prefs ->
                            prefs.updateLayout(layoutMode) { layout ->
                                val existing = layout.center.slot(target.position)
                                layout.withCenterSlot(
                                    target.position,
                                    existing.copy(action = action),
                                    layoutMode.isFullscreen,
                                )
                            }
                        }
                        selection = PreviewSelection.CenterSlot(target.position)
                    }
                }
                is DropTarget.BarInsert -> {
                    when (val payload = drag.payload) {
                        is DragPayload.PlacedBarItem -> {
                            val newIndex = if (payload.bar == target.bar && payload.index < target.insertIndex) {
                                (target.insertIndex - 1).coerceAtLeast(0)
                            } else {
                                target.insertIndex
                            }
                            onUpdateLayout { prefs ->
                                prefs.updateLayout(layoutMode) { layout ->
                                    layout.moveBarItemToBarAt(
                                        fromBar = payload.bar,
                                        fromIndex = payload.index,
                                        toBar = target.bar,
                                        toIndex = target.insertIndex,
                                        isFullscreen = layoutMode.isFullscreen,
                                    )
                                }
                            }
                            selection = PreviewSelection.PanelItem(target.bar, newIndex)
                        }
                        else -> {
                            val panelItem = payload.toPanelItemOrNull()
                            if (panelItem != null) {
                                onUpdateLayout { prefs ->
                                    prefs.updateLayout(layoutMode) { layout ->
                                        layout.insertIntoBar(
                                            bar = target.bar,
                                            index = target.insertIndex,
                                            item = panelItem,
                                            isFullscreen = layoutMode.isFullscreen,
                                        )
                                    }
                                }
                                selection = PreviewSelection.PanelItem(target.bar, target.insertIndex)
                            }
                        }
                    }
                }
                DropTarget.Remove -> {
                    val placed = drag.payload as? DragPayload.PlacedBarItem
                    if (placed != null && activeLayout.canRemoveBarItem(placed.bar, placed.index, layoutMode.isFullscreen)) {
                        onUpdateLayout { prefs ->
                            prefs.updateLayout(layoutMode) { layout ->
                                layout.removeBarItem(placed.bar, placed.index, layoutMode.isFullscreen)
                            }
                        }
                    }
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            SettingsTopBarTheme {
                BilbyTopBar(
                    title = stringResource(Res.string.settings_section_player_customization),
                    onBack = onBack,
                    scrollBehavior = scrollBehavior,
                    actions = {
                        TextButton(onClick = onResetLayout) {
                            Text(stringResource(Res.string.player_custom_reset_default))
                        }
                    },
                )
            }
        },
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { insets ->
        if (!state.loaded) return@Scaffold

        val onQuickAddControl: (PlayerControlId) -> Unit = { controlId ->
            val targetBar = if (
                editingFullscreen &&
                controlId in listOf(
                    PlayerControlId.Title,
                    PlayerControlId.SystemStatus,
                    PlayerControlId.Lock,
                )
            ) {
                PlayerBarSlot.Top
            } else {
                PlayerBarSlot.Bottom
            }
            onUpdateLayout { prefs ->
                prefs.updateLayout(layoutMode) {
                    it.addToBar(
                        targetBar,
                        PlayerPanelItem.Control(controlId),
                        layoutMode.isFullscreen,
                    )
                }
            }
        }
        val onQuickAddSpacer: () -> Unit = {
            val nextIdx = activeLayout.itemsFor(PlayerBarSlot.Bottom).size
            onUpdateLayout { prefs ->
                prefs.updateLayout(layoutMode) {
                    it.addToBar(
                        PlayerBarSlot.Bottom,
                        PlayerPanelItem.Spacer,
                        layoutMode.isFullscreen,
                    )
                }
            }
            selection = PreviewSelection.PanelItem(PlayerBarSlot.Bottom, nextIdx)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    if (coords.isAttached) dragState.rootOriginInWindow = coords.positionInWindow()
                },
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padScaffoldExceptBottom(insets),
            ) {
                val isLandscapeWide = maxWidth >= 840.dp && maxWidth > maxHeight
                LaunchedEffect(isLandscapeWide) {
                    dragState.cancelDrag()
                    dragState.clearBounds()
                }

                if (isLandscapeWide) {
                    // 横屏/宽屏三区布局：左列（0.64f）顶栏模式切换 + 大尺寸 16:9 预览 + 底部单行预设/开关；
                    // 右侧边栏（0.36f，280..360dp）上方可用控件托盘 + 下方选中项属性检查器，右向左短距离拖放。
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = Spacing.Comfortable)
                            .padding(top = Spacing.Cozy, bottom = Spacing.Cozy + navigationBarsBottom()),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                            modifier = Modifier
                                .weight(0.64f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                SurfaceModeToggleGroup(
                                    surfaceMode = surfaceMode,
                                    onSurfaceModeChange = { surfaceMode = it },
                                    modifier = Modifier.weight(1f),
                                )
                                if (!isListenMode && !isGestureMode) {
                                    PreviewPointerModeToggle(
                                        isTouch = previewTouchMode,
                                        onTouchChange = { previewTouchMode = it },
                                    )
                                }
                            }

                            when (surfaceMode) {
                                EditorSurfaceMode.Embedded, EditorSurfaceMode.Fullscreen -> {
                                    PlayerPreviewCanvas(
                                        layout = activeLayout,
                                        isFullscreen = editingFullscreen,
                                        transportInCenter = previewTransportInCenter,
                                        seekButtonSeconds = layoutPrefs.seekButtonSeconds,
                                        selection = selection,
                                        dragState = dragState,
                                        dropTarget = currentDropTarget,
                                        onSelect = { selection = it },
                                        onFinishDrag = finishDrag,
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        PresetChipsRow(
                                            current = layoutPrefs,
                                            onApplyPreset = { preset -> onUpdateLayout { PlayerLayoutPrefs.preset(preset) } },
                                            modifier = Modifier.weight(1f),
                                        )
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                                            modifier = Modifier
                                                .clip(CircleShape)
                                                .clickable {
                                                    onUpdateLayout { prefs ->
                                                        prefs.copy(showCenterControlsOnTouch = !prefs.showCenterControlsOnTouch)
                                                    }
                                                }
                                                .pointerHoverIcon(PointerIcon.Hand)
                                                .padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
                                        ) {
                                            Text(
                                                text = stringResource(Res.string.player_custom_show_center_on_touch),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                            Switch(
                                                checked = layoutPrefs.showCenterControlsOnTouch,
                                                onCheckedChange = { enabled ->
                                                    onUpdateLayout { prefs ->
                                                        prefs.copy(showCenterControlsOnTouch = enabled)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }

                                EditorSurfaceMode.Listen -> {
                                    ListenPreviewCanvas(
                                        config = layoutPrefs.listen,
                                        selectedSection = listenSection,
                                        selectedSlot = selectedListenSlot,
                                        onSelectSection = { listenSection = it },
                                        onSelectSlot = { pos ->
                                            selectedListenSlot = pos
                                            listenSection = ListenEditorSection.SideSlot
                                        },
                                    )
                                    Text(
                                        text = stringResource(Res.string.player_custom_listen_preview_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    PresetChipsRow(
                                        current = layoutPrefs,
                                        onApplyPreset = { preset -> onUpdateLayout { PlayerLayoutPrefs.preset(preset) } },
                                    )
                                }

                                EditorSurfaceMode.Gestures -> {
                                    GesturePreviewCanvas(
                                        state = state,
                                        layoutPrefs = layoutPrefs,
                                    )
                                    Text(
                                        text = stringResource(Res.string.player_custom_gesture_preview_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                            modifier = Modifier
                                .weight(0.36f)
                                .widthIn(min = 280.dp, max = 360.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            when (surfaceMode) {
                                EditorSurfaceMode.Embedded, EditorSurfaceMode.Fullscreen -> {
                                    ControlPaletteTray(
                                        layout = activeLayout,
                                        isFullscreen = editingFullscreen,
                                        showCenterControls = true,
                                        seekButtonSeconds = layoutPrefs.seekButtonSeconds,
                                        dragState = dragState,
                                        dropTarget = currentDropTarget,
                                        onFinishDrag = finishDrag,
                                        onQuickAddControl = onQuickAddControl,
                                        onQuickAddSpacer = onQuickAddSpacer,
                                    )
                                    val activeSelection = selection
                                    if (activeSelection != null) {
                                        SelectionInspectorStrip(
                                            layoutMode = layoutMode,
                                            layout = activeLayout,
                                            layoutPrefs = layoutPrefs,
                                            selection = activeSelection,
                                            onSelect = { selection = it },
                                            onDismiss = { selection = null },
                                            onUpdateLayout = onUpdateLayout,
                                        )
                                    } else {
                                        InspectorEmptyHint()
                                    }
                                }

                                EditorSurfaceMode.Listen -> {
                                    ListenCustomizationCards(
                                        config = layoutPrefs.listen,
                                        seekButtonSeconds = layoutPrefs.seekButtonSeconds,
                                        selectedSection = listenSection,
                                        selectedSlot = selectedListenSlot,
                                        onSelectSection = { listenSection = it },
                                        onSelectSlot = { pos ->
                                            selectedListenSlot = pos
                                            listenSection = ListenEditorSection.SideSlot
                                        },
                                        onUpdateLayout = onUpdateLayout,
                                    )
                                }

                                EditorSurfaceMode.Gestures -> {
                                    GestureCustomizationCards(
                                        state = state,
                                        layoutPrefs = layoutPrefs,
                                        onUpdateLayout = onUpdateLayout,
                                        onDoubleTapSeekChange = onDoubleTapSeekChange,
                                        onLongPressSpeedChange = onLongPressSpeedChange,
                                        onLongPressSpeedDialChange = onLongPressSpeedDialChange,
                                        onDefaultHoldSpeedChange = onDefaultHoldSpeedChange,
                                        onSwipeSeekChange = onSwipeSeekChange,
                                        onSwipeBrightnessVolumeChange = onSwipeBrightnessVolumeChange,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // 竖屏/窄屏三区布局：顶部固定（不随下方滚动）模式切换 + 预览视角切换 + 全宽 16:9 预览画布；
                    // 下方独立滚动：可用控件托盘紧贴预览画布下方（便于向上拖放）→ 选中项检查器 → 预设与触控开关。
                    val inlinePortraitPointerToggle = maxWidth >= 460.dp
                    AdaptiveContent(
                        modifier = Modifier.fillMaxSize(),
                        maxWidth = 840.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = Spacing.Comfortable),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = Spacing.Cozy, bottom = Spacing.Cozy),
                            ) {
                                if (!isListenMode && !isGestureMode && inlinePortraitPointerToggle) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        SurfaceModeToggleGroup(
                                            surfaceMode = surfaceMode,
                                            onSurfaceModeChange = { surfaceMode = it },
                                            modifier = Modifier.weight(1f),
                                        )
                                        PreviewPointerModeToggle(
                                            isTouch = previewTouchMode,
                                            onTouchChange = { previewTouchMode = it },
                                        )
                                    }
                                } else {
                                    SurfaceModeToggleGroup(
                                        surfaceMode = surfaceMode,
                                        onSurfaceModeChange = { surfaceMode = it },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (!isListenMode && !isGestureMode) {
                                        Row(
                                            horizontalArrangement = Arrangement.End,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            PreviewPointerModeToggle(
                                                isTouch = previewTouchMode,
                                                onTouchChange = { previewTouchMode = it },
                                            )
                                        }
                                    }
                                }

                                when (surfaceMode) {
                                    EditorSurfaceMode.Embedded, EditorSurfaceMode.Fullscreen -> {
                                        PlayerPreviewCanvas(
                                            layout = activeLayout,
                                            isFullscreen = editingFullscreen,
                                            transportInCenter = previewTransportInCenter,
                                            seekButtonSeconds = layoutPrefs.seekButtonSeconds,
                                            selection = selection,
                                            dragState = dragState,
                                            dropTarget = currentDropTarget,
                                            onSelect = { selection = it },
                                            onFinishDrag = finishDrag,
                                        )
                                    }

                                    EditorSurfaceMode.Listen -> {
                                        ListenPreviewCanvas(
                                            config = layoutPrefs.listen,
                                            selectedSection = listenSection,
                                            selectedSlot = selectedListenSlot,
                                            onSelectSection = { listenSection = it },
                                            onSelectSlot = { pos ->
                                                selectedListenSlot = pos
                                                listenSection = ListenEditorSection.SideSlot
                                            },
                                        )
                                    }

                                    EditorSurfaceMode.Gestures -> {
                                        GesturePreviewCanvas(
                                            state = state,
                                            layoutPrefs = layoutPrefs,
                                        )
                                    }
                                }
                            }

                            Column(
                                verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(bottom = Spacing.Spacious + navigationBarsBottom()),
                            ) {
                                when (surfaceMode) {
                                    EditorSurfaceMode.Embedded, EditorSurfaceMode.Fullscreen -> {
                                        ControlPaletteTray(
                                            layout = activeLayout,
                                            isFullscreen = editingFullscreen,
                                            showCenterControls = true,
                                            seekButtonSeconds = layoutPrefs.seekButtonSeconds,
                                            dragState = dragState,
                                            dropTarget = currentDropTarget,
                                            onFinishDrag = finishDrag,
                                            onQuickAddControl = onQuickAddControl,
                                            onQuickAddSpacer = onQuickAddSpacer,
                                        )

                                        val activeSelection = selection
                                        if (activeSelection != null) {
                                            SelectionInspectorStrip(
                                                layoutMode = layoutMode,
                                                layout = activeLayout,
                                                layoutPrefs = layoutPrefs,
                                                selection = activeSelection,
                                                onSelect = { selection = it },
                                                onDismiss = { selection = null },
                                                onUpdateLayout = onUpdateLayout,
                                            )
                                        } else {
                                            InspectorEmptyHint()
                                        }

                                        PresetChipsRow(
                                            current = layoutPrefs,
                                            onApplyPreset = { preset -> onUpdateLayout { PlayerLayoutPrefs.preset(preset) } },
                                        )

                                        Surface(
                                            shape = MaterialTheme.shapes.large,
                                            color = MaterialTheme.colorScheme.surfaceContainer,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            PlainToggleRow(
                                                icon = Icons.Filled.PlayArrow,
                                                title = stringResource(Res.string.player_custom_show_center_on_touch),
                                                subtitle = stringResource(Res.string.player_custom_show_center_on_touch_subtitle),
                                                checked = layoutPrefs.showCenterControlsOnTouch,
                                                onCheckedChange = { enabled ->
                                                    onUpdateLayout { prefs -> prefs.copy(showCenterControlsOnTouch = enabled) }
                                                },
                                                modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
                                            )
                                        }
                                    }

                                    EditorSurfaceMode.Listen -> {
                                        Text(
                                            text = stringResource(Res.string.player_custom_listen_preview_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        ListenCustomizationCards(
                                            config = layoutPrefs.listen,
                                            seekButtonSeconds = layoutPrefs.seekButtonSeconds,
                                            selectedSection = listenSection,
                                            selectedSlot = selectedListenSlot,
                                            onSelectSection = { listenSection = it },
                                            onSelectSlot = { pos ->
                                                selectedListenSlot = pos
                                                listenSection = ListenEditorSection.SideSlot
                                            },
                                            onUpdateLayout = onUpdateLayout,
                                        )
                                        PresetChipsRow(
                                            current = layoutPrefs,
                                            onApplyPreset = { preset -> onUpdateLayout { PlayerLayoutPrefs.preset(preset) } },
                                        )
                                    }

                                    EditorSurfaceMode.Gestures -> {
                                        Text(
                                            text = stringResource(Res.string.player_custom_gesture_preview_hint),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        GestureCustomizationCards(
                                            state = state,
                                            layoutPrefs = layoutPrefs,
                                            onUpdateLayout = onUpdateLayout,
                                            onDoubleTapSeekChange = onDoubleTapSeekChange,
                                            onLongPressSpeedChange = onLongPressSpeedChange,
                                            onLongPressSpeedDialChange = onLongPressSpeedDialChange,
                                            onDefaultHoldSpeedChange = onDefaultHoldSpeedChange,
                                            onSwipeSeekChange = onSwipeSeekChange,
                                            onSwipeBrightnessVolumeChange = onSwipeBrightnessVolumeChange,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 拖拽浮层幽灵（跟随鼠标与触控指针）
            val activeDrag = dragState.activeDrag
            if (activeDrag != null) {
                val removing = currentDropTarget == DropTarget.Remove &&
                    (activeDrag.payload as? DragPayload.PlacedBarItem)?.let {
                        activeLayout.canRemoveBarItem(it.bar, it.index, editingFullscreen)
                    } == true
                DragGhostOverlay(
                    payload = activeDrag.payload,
                    pointerInRoot = activeDrag.pointerInWindow - dragState.rootOriginInWindow,
                    isFullscreen = editingFullscreen,
                    removing = removing,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SurfaceModeToggleGroup(
    surfaceMode: EditorSurfaceMode,
    onSurfaceModeChange: (EditorSurfaceMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    val options = listOf(
        EditorSurfaceMode.Embedded to stringResource(Res.string.player_custom_form_embedded),
        EditorSurfaceMode.Fullscreen to stringResource(Res.string.player_custom_form_fullscreen),
        EditorSurfaceMode.Listen to stringResource(Res.string.player_custom_form_listen),
        EditorSurfaceMode.Gestures to stringResource(Res.string.player_custom_form_gestures),
    )
    ButtonGroup(
        overflowIndicator = {},
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = modifier,
    ) {
        options.forEachIndexed { index, (option, label) ->
            customItem(
                buttonGroupContent = {
                    val shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }
                    ToggleButton(
                        checked = surfaceMode == option,
                        onCheckedChange = { if (it) onSurfaceModeChange(option) },
                        shapes = shapes,
                        colors = colors,
                        contentPadding = PaddingValues(horizontal = Spacing.Hair, vertical = Spacing.Tight),
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                    ) {
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                menuContent = {},
            )
        }
    }
}

/** 仅用于预览画布的「触控 / 键鼠」视角切换：不拆分配置数据，只切换一体化播放控制簇在画布中央或底栏左端的呈现位置。 */
@Composable
private fun PreviewPointerModeToggle(
    isTouch: Boolean,
    onTouchChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(3.dp),
        ) {
            val items = listOf(
                true to stringResource(Res.string.player_custom_input_touch),
                false to stringResource(Res.string.player_custom_input_pointer),
            )
            for ((touchOption, label) in items) {
                val selected = isTouch == touchOption
                Surface(
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onTouchChange(touchOption) }
                        .pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InspectorEmptyHint() {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(Res.string.player_custom_preview_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        )
    }
}

@Composable
private fun ListenPreviewCanvas(
    config: ListenLayoutConfig,
    selectedSection: ListenEditorSection,
    selectedSlot: CenterSlotPosition,
    onSelectSection: (ListenEditorSection) -> Unit,
    onSelectSlot: (CenterSlotPosition) -> Unit,
) {
    val highlight = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val tertiaryContainer = MaterialTheme.colorScheme.tertiaryContainer
    val onPrimaryContainer = MaterialTheme.colorScheme.onPrimaryContainer
    val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val surfaceContainerHighest = MaterialTheme.colorScheme.surfaceContainerHighest
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val showSpeed = config.hasChip(ListenQuickChip.Speed)
    val showSleep = config.hasChip(ListenQuickChip.SleepTimer)
    val showQuality = config.hasChip(ListenQuickChip.Quality)
    val showSubtitle = config.hasChip(ListenQuickChip.Subtitle)

    val chipHighlightBorder = if (selectedSection == ListenEditorSection.QuickChips) {
        Modifier.border(2.dp, highlight, CircleShape)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), MaterialTheme.shapes.large)
            .padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
    ) {
        // 顶栏预览：返回箭头 + 右侧音质/字幕快捷图标
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                modifier = Modifier
                    .clip(CircleShape)
                    .then(if (showQuality || showSubtitle) chipHighlightBorder else Modifier)
                    .clickable { onSelectSection(ListenEditorSection.QuickChips) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) {
                if (showQuality) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = stringResource(Res.string.player_custom_ctrl_audio),
                        tint = onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                if (showSubtitle) {
                    Icon(
                        imageVector = Icons.Filled.Subtitles,
                        contentDescription = stringResource(Res.string.player_custom_ctrl_subtitle),
                        tint = onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        // 中央封面与曲目信息预览
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
            modifier = Modifier.align(Alignment.Center).padding(bottom = 26.dp),
        ) {
            val coverSelected = selectedSection == ListenEditorSection.Cover
            val coverShape = if (config.coverStyle == ListenCoverStyle.VinylDisc) {
                CircleShape
            } else {
                MaterialTheme.shapes.medium
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(coverShape)
                    .then(if (coverSelected) Modifier.border(2.dp, highlight, coverShape) else Modifier)
                    .clickable { onSelectSection(ListenEditorSection.Cover) }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(3.dp),
            ) {
                if (config.coverStyle == ListenCoverStyle.VinylDisc) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(62.dp),
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val r = size.minDimension / 2f
                            val c = Offset(size.width / 2f, size.height / 2f)
                            drawCircle(FixedColors.VinylBody, radius = r, center = c)
                            val inner = r * 0.58f
                            repeat(3) { i ->
                                val t = (i + 1f) / 4f
                                drawCircle(
                                    color = FixedColors.VinylGroove,
                                    radius = inner + (r - inner) * t,
                                    center = c,
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            }
                            drawCircle(
                                brush = Brush.linearGradient(listOf(primaryContainer, tertiaryContainer)),
                                radius = inner,
                                center = c,
                            )
                            drawCircle(FixedColors.VinylHole, radius = r * 0.08f, center = c)
                        }
                    }
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .width(92.dp)
                            .aspectRatio(16f / 10f)
                            .clip(MaterialTheme.shapes.small)
                            .background(Brush.linearGradient(listOf(primaryContainer, tertiaryContainer))),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Headphones,
                            contentDescription = null,
                            tint = onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            Text(
                text = stringResource(Res.string.player_custom_preview_title),
                style = MaterialTheme.typography.labelMedium,
                color = onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "01:23 / 12:34",
                style = MaterialTheme.typography.labelSmall.tabular,
                color = onSurfaceVariant,
                maxLines = 1,
            )
        }

        // 底部悬浮控制胶囊预览：[倍速?] [外左] [内左] [▶] [内右] [外右] [定时?]
        Surface(
            shape = CircleShape,
            color = surfaceContainerHigh,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                modifier = Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
            ) {
                if (showSpeed) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(surfaceContainerHighest)
                            .then(chipHighlightBorder)
                            .clickable { onSelectSection(ListenEditorSection.QuickChips) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 7.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "1.0x",
                            style = MaterialTheme.typography.labelSmall.tabular,
                            color = onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }

                ListenPreviewSideSlot(
                    slot = config.outerLeft,
                    selected = selectedSection == ListenEditorSection.SideSlot && selectedSlot == CenterSlotPosition.OuterLeft,
                    containerColor = surfaceContainerHighest,
                    contentColor = onSurfaceVariant,
                    highlightColor = highlight,
                    onClick = { onSelectSlot(CenterSlotPosition.OuterLeft) },
                )
                ListenPreviewSideSlot(
                    slot = config.innerLeft,
                    selected = selectedSection == ListenEditorSection.SideSlot && selectedSlot == CenterSlotPosition.InnerLeft,
                    containerColor = surfaceContainerHighest,
                    contentColor = onSurfaceVariant,
                    highlightColor = highlight,
                    onClick = { onSelectSlot(CenterSlotPosition.InnerLeft) },
                )

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = 34.dp, height = 28.dp)
                        .clip(CircleShape)
                        .background(highlight),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }

                ListenPreviewSideSlot(
                    slot = config.innerRight,
                    selected = selectedSection == ListenEditorSection.SideSlot && selectedSlot == CenterSlotPosition.InnerRight,
                    containerColor = surfaceContainerHighest,
                    contentColor = onSurfaceVariant,
                    highlightColor = highlight,
                    onClick = { onSelectSlot(CenterSlotPosition.InnerRight) },
                )
                ListenPreviewSideSlot(
                    slot = config.outerRight,
                    selected = selectedSection == ListenEditorSection.SideSlot && selectedSlot == CenterSlotPosition.OuterRight,
                    containerColor = surfaceContainerHighest,
                    contentColor = onSurfaceVariant,
                    highlightColor = highlight,
                    onClick = { onSelectSlot(CenterSlotPosition.OuterRight) },
                )

                if (showSleep) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(surfaceContainerHighest)
                            .then(chipHighlightBorder)
                            .clickable { onSelectSection(ListenEditorSection.QuickChips) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 7.dp, vertical = 5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Bedtime,
                            contentDescription = stringResource(Res.string.player_custom_ctrl_sleep_timer),
                            tint = onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListenPreviewSideSlot(
    slot: CenterSlotConfig,
    selected: Boolean,
    containerColor: Color,
    contentColor: Color,
    highlightColor: Color,
    onClick: () -> Unit,
) {
    val diameter: Dp = when (slot.scale) {
        CenterSlotScale.Large -> 30.dp
        CenterSlotScale.Medium -> 26.dp
        CenterSlotScale.Small -> 22.dp
        CenterSlotScale.ExtraSmall -> 19.dp
    }
    if (slot.action == CenterSlotAction.None) {
        val strokeColor = if (selected) highlightColor else contentColor.copy(alpha = 0.45f)
        val strokeWidth = if (selected) 2.dp else 1.2.dp
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = strokeWidth.toPx()
                drawCircle(
                    color = strokeColor,
                    radius = (size.minDimension - strokePx) / 2f,
                    style = Stroke(
                        width = strokePx,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f),
                    ),
                )
            }
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(Res.string.player_custom_add_slot),
                tint = strokeColor,
                modifier = Modifier.size((diameter * 0.48f).coerceIn(10.dp, 14.dp)),
            )
        }
        return
    }

    val icon = when (slot.action) {
        CenterSlotAction.Previous -> Icons.Filled.SkipPrevious
        CenterSlotAction.Next -> Icons.Filled.SkipNext
        is CenterSlotAction.SeekBackward -> Icons.Filled.FastRewind
        is CenterSlotAction.SeekForward -> Icons.Filled.FastForward
        CenterSlotAction.None -> Icons.Filled.Add
    }
    val secondsBadge = slot.action.seekSecondsOrNull()
    val iconSize = (diameter * (if (secondsBadge != null) 0.42f else 0.56f)).coerceIn(10.dp, 16.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(diameter)
            .clip(CircleShape)
            .background(if (slot.showBackground) containerColor else Color.Transparent)
            .then(if (selected) Modifier.border(2.dp, highlightColor, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        if (secondsBadge != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(iconSize))
                Text(
                    text = "${secondsBadge}s",
                    style = MaterialTheme.typography.labelSmall.tabular.copy(fontSize = 7.sp, lineHeight = 8.sp),
                    color = contentColor,
                    maxLines = 1,
                )
            }
        } else {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(iconSize))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListenCustomizationCards(
    config: ListenLayoutConfig,
    seekButtonSeconds: Int,
    selectedSection: ListenEditorSection,
    selectedSlot: CenterSlotPosition,
    onSelectSection: (ListenEditorSection) -> Unit,
    onSelectSlot: (CenterSlotPosition) -> Unit,
    onUpdateLayout: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    // 1. 播放键两侧槽位卡片（支持外左 / 内左 / 内右 / 外右 4 槽位）
    val slot = config.slot(selectedSlot)
    val currentSeconds = slot.action.seekSecondsOrNull() ?: seekButtonSeconds
    ListenEditorCard(
        title = stringResource(Res.string.player_custom_listen_side_title),
        selected = selectedSection == ListenEditorSection.SideSlot,
        onClick = { onSelectSection(ListenEditorSection.SideSlot) },
    ) {
        // 槽位切换
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            val slotTabs = listOf(
                CenterSlotPosition.OuterLeft to stringResource(Res.string.player_custom_slot_outer_left_short),
                CenterSlotPosition.InnerLeft to stringResource(Res.string.player_custom_slot_inner_left_short),
                CenterSlotPosition.InnerRight to stringResource(Res.string.player_custom_slot_inner_right_short),
                CenterSlotPosition.OuterRight to stringResource(Res.string.player_custom_slot_outer_right_short),
            )
            for ((pos, label) in slotTabs) {
                FilterChip(
                    selected = selectedSlot == pos,
                    onClick = { onSelectSlot(pos) },
                    label = { Text(label) },
                )
            }
        }

        // 动作选择
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            val actions = listOf(
                CenterSlotAction.None to stringResource(Res.string.player_custom_action_none),
                CenterSlotAction.Previous to stringResource(Res.string.player_custom_action_previous),
                CenterSlotAction.Next to stringResource(Res.string.player_custom_action_next),
                CenterSlotAction.SeekBackward(currentSeconds) to stringResource(Res.string.player_custom_action_seek_backward),
                CenterSlotAction.SeekForward(currentSeconds) to stringResource(Res.string.player_custom_action_seek_forward),
            )
            for ((actionOption, label) in actions) {
                val isCurrent = when {
                    actionOption is CenterSlotAction.SeekBackward && slot.action is CenterSlotAction.SeekBackward -> true
                    actionOption is CenterSlotAction.SeekForward && slot.action is CenterSlotAction.SeekForward -> true
                    else -> slot.action == actionOption
                }
                FilterChip(
                    selected = isCurrent,
                    onClick = {
                        onSelectSection(ListenEditorSection.SideSlot)
                        onUpdateLayout { prefs ->
                            prefs.updateListen { it.withSlot(selectedSlot, slot.copy(action = actionOption)) }
                        }
                    },
                    label = { Text(label) },
                )
            }
        }

        val isSeekAction = slot.action is CenterSlotAction.SeekBackward ||
            slot.action is CenterSlotAction.SeekForward
        if (isSeekAction) {
            SeekSecondsEditor(
                title = stringResource(Res.string.player_custom_seek_seconds),
                seconds = currentSeconds,
                onSecondsChange = { newSec ->
                    val clamped = newSec.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
                    val updatedAction = when (slot.action) {
                        is CenterSlotAction.SeekBackward -> CenterSlotAction.SeekBackward(clamped)
                        is CenterSlotAction.SeekForward -> CenterSlotAction.SeekForward(clamped)
                        else -> slot.action
                    }
                    onUpdateLayout { prefs ->
                        prefs.copy(seekButtonSeconds = clamped)
                            .updateListen { it.withSlot(selectedSlot, slot.copy(action = updatedAction)) }
                    }
                },
            )
        }

        // 相对尺寸档位
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
            Text(
                text = stringResource(Res.string.player_custom_scale_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                val scales = listOf(
                    CenterSlotScale.Large to stringResource(Res.string.player_custom_scale_large),
                    CenterSlotScale.Medium to stringResource(Res.string.player_custom_scale_medium),
                    CenterSlotScale.Small to stringResource(Res.string.player_custom_scale_small),
                    CenterSlotScale.ExtraSmall to stringResource(Res.string.player_custom_scale_extra_small),
                )
                for ((scaleOption, label) in scales) {
                    FilterChip(
                        selected = slot.scale == scaleOption,
                        onClick = {
                            onSelectSection(ListenEditorSection.SideSlot)
                            onUpdateLayout { prefs ->
                                prefs.updateListen { it.withSlot(selectedSlot, slot.copy(scale = scaleOption)) }
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
        }

        if (slot.action != CenterSlotAction.None) {
            ShowBackgroundSwitchRow(
                checked = slot.showBackground,
                onCheckedChange = { showBg ->
                    onSelectSection(ListenEditorSection.SideSlot)
                    onUpdateLayout { prefs ->
                        prefs.updateListen { it.withSlot(selectedSlot, slot.copy(showBackground = showBg)) }
                    }
                },
            )
        }

        // 对称应用 + 清空
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilledTonalButton(
                onClick = {
                    onSelectSection(ListenEditorSection.SideSlot)
                    onUpdateLayout { prefs -> prefs.updateListen { it.withSymmetricSlot(selectedSlot) } }
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.Hair))
                Text(stringResource(Res.string.player_custom_apply_symmetric), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (slot.action != CenterSlotAction.None) {
                OutlinedButton(
                    onClick = {
                        onSelectSection(ListenEditorSection.SideSlot)
                        onUpdateLayout { prefs ->
                            prefs.updateListen { it.withSlot(selectedSlot, slot.copy(action = CenterSlotAction.None)) }
                        }
                    },
                ) {
                    Text(stringResource(Res.string.player_custom_clear_slot))
                }
            }
        }
    }

    // 2. 封面样式卡片
    ListenEditorCard(
        title = stringResource(Res.string.player_custom_listen_cover_title),
        selected = selectedSection == ListenEditorSection.Cover,
        onClick = { onSelectSection(ListenEditorSection.Cover) },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            val coverOptions = listOf(
                ListenCoverStyle.VinylDisc to stringResource(Res.string.player_custom_listen_cover_vinyl),
                ListenCoverStyle.StaticCover to stringResource(Res.string.player_custom_listen_cover_static),
            )
            for ((style, label) in coverOptions) {
                FilterChip(
                    selected = config.coverStyle == style,
                    onClick = {
                        onSelectSection(ListenEditorSection.Cover)
                        onUpdateLayout { prefs -> prefs.updateListen { it.copy(coverStyle = style) } }
                    },
                    label = { Text(label) },
                )
            }
        }
        Text(
            text = stringResource(Res.string.player_custom_listen_cover_static_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 3. 快捷项显示卡片
    ListenEditorCard(
        title = stringResource(Res.string.player_custom_listen_chips_title),
        selected = selectedSection == ListenEditorSection.QuickChips,
        onClick = { onSelectSection(ListenEditorSection.QuickChips) },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            val chips = listOf(
                Triple(ListenQuickChip.Speed, Icons.Filled.Speed, stringResource(Res.string.player_custom_ctrl_speed)),
                Triple(ListenQuickChip.SleepTimer, Icons.Filled.Bedtime, stringResource(Res.string.player_custom_ctrl_sleep_timer)),
                Triple(ListenQuickChip.Quality, Icons.Filled.GraphicEq, stringResource(Res.string.player_custom_ctrl_audio)),
                Triple(ListenQuickChip.Subtitle, Icons.Filled.Subtitles, stringResource(Res.string.player_custom_ctrl_subtitle)),
            )
            for ((chip, icon, label) in chips) {
                val visible = config.hasChip(chip)
                FilterChip(
                    selected = visible,
                    onClick = {
                        onSelectSection(ListenEditorSection.QuickChips)
                        onUpdateLayout { prefs -> prefs.updateListen { it.toggleChip(chip, !visible) } }
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.IconInline),
                        )
                    },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun ShowBackgroundSwitchRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = Spacing.Hair),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.player_custom_show_background),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(Res.string.player_custom_show_background_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun ListenEditorCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val borderMod = if (selected) {
        Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier
    }
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .then(borderMod)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            modifier = Modifier.padding(Spacing.Comfortable),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            content()
        }
    }
}

@Composable
private fun PresetChipsRow(
    current: PlayerLayoutPrefs,
    onApplyPreset: (PlayerLayoutPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = stringResource(Res.string.player_custom_presets),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val presets = listOf(
            PlayerLayoutPreset.Default to stringResource(Res.string.player_custom_preset_default),
            PlayerLayoutPreset.Minimal to stringResource(Res.string.player_custom_preset_minimal),
            PlayerLayoutPreset.Podcast to stringResource(Res.string.player_custom_preset_podcast),
        )
        for ((preset, label) in presets) {
            val selected = current == PlayerLayoutPrefs.preset(preset)
            FilterChip(
                selected = selected,
                onClick = { onApplyPreset(preset) },
                label = { Text(label) },
            )
        }
    }
}

private data class PreviewDensity(
    val barHorizontalPadding: Dp,
    val barMinHeight: Dp,
    val barItemSpacing: Dp,
    val chipPaddingH: Dp,
    val chipPaddingV: Dp,
    val iconContainerSize: Dp,
    val iconSize: Dp,
    val pillPaddingH: Dp,
    val pillPaddingV: Dp,
    val pillFontSize: TextUnit,
    val badgeIconSize: Dp,
    val timeText: String,
    val timeMinWidth: Dp,
    val titleMaxWidth: Dp,
    val titleFontSize: TextUnit,
    val spacerMinWidth: Dp,
    val spacerHeight: Dp,
    val centerPlayOuterSize: Dp,
    val centerPlayInnerSize: Dp,
    val centerPlayIconSize: Dp,
    val centerSideBaseSize: Dp,
    val centerSpacing: Dp,
)

private fun previewDensityFor(canvasWidth: Dp): PreviewDensity = when {
    canvasWidth >= 600.dp -> PreviewDensity(
        barHorizontalPadding = 8.dp,
        barMinHeight = 36.dp,
        barItemSpacing = 3.dp,
        chipPaddingH = 2.dp,
        chipPaddingV = 2.dp,
        iconContainerSize = 26.dp,
        iconSize = 16.dp,
        pillPaddingH = 6.dp,
        pillPaddingV = 2.dp,
        pillFontSize = 11.sp,
        badgeIconSize = 14.dp,
        timeText = "1:23 / 12:34",
        timeMinWidth = 68.dp,
        titleMaxWidth = 140.dp,
        titleFontSize = 12.sp,
        spacerMinWidth = 12.dp,
        spacerHeight = 26.dp,
        centerPlayOuterSize = 46.dp,
        centerPlayInnerSize = 40.dp,
        centerPlayIconSize = 22.dp,
        centerSideBaseSize = 42.dp,
        centerSpacing = 8.dp,
    )
    canvasWidth >= 420.dp -> PreviewDensity(
        barHorizontalPadding = 6.dp,
        barMinHeight = 30.dp,
        barItemSpacing = 2.dp,
        chipPaddingH = 1.dp,
        chipPaddingV = 1.dp,
        iconContainerSize = 22.dp,
        iconSize = 14.dp,
        pillPaddingH = 4.dp,
        pillPaddingV = 1.5.dp,
        pillFontSize = 10.sp,
        badgeIconSize = 12.dp,
        timeText = "1:23 / 12:34",
        timeMinWidth = 58.dp,
        titleMaxWidth = 96.dp,
        titleFontSize = 11.sp,
        spacerMinWidth = 8.dp,
        spacerHeight = 22.dp,
        centerPlayOuterSize = 40.dp,
        centerPlayInnerSize = 34.dp,
        centerPlayIconSize = 20.dp,
        centerSideBaseSize = 36.dp,
        centerSpacing = 6.dp,
    )
    else -> PreviewDensity(
        barHorizontalPadding = 4.dp,
        barMinHeight = 26.dp,
        barItemSpacing = 1.dp,
        chipPaddingH = 1.dp,
        chipPaddingV = 1.dp,
        iconContainerSize = 19.dp,
        iconSize = 12.dp,
        pillPaddingH = 3.dp,
        pillPaddingV = 1.dp,
        pillFontSize = 8.5.sp,
        badgeIconSize = 10.dp,
        timeText = "1:23/12:34",
        timeMinWidth = 44.dp,
        titleMaxWidth = 72.dp,
        titleFontSize = 10.sp,
        spacerMinWidth = 8.dp,
        spacerHeight = 19.dp,
        centerPlayOuterSize = 34.dp,
        centerPlayInnerSize = 29.dp,
        centerPlayIconSize = 17.dp,
        centerSideBaseSize = 31.dp,
        centerSpacing = 4.dp,
    )
}

private fun estimateBarMinWidth(
    items: List<PlayerPanelItem>,
    centerConfig: CenterClusterConfig,
    transportInCenter: Boolean,
    transportInAnyBar: Boolean,
    density: PreviewDensity,
): Dp {
    if (items.isEmpty()) return 0.dp
    var visibleCount = 0
    var total = 0.dp
    val chipPad = density.chipPaddingH * 2
    for (item in items) {
        if (item is PlayerPanelItem.Control) {
            val hidden = when (item.id) {
                PlayerControlId.PlayPause -> transportInCenter
                else -> (transportInCenter || transportInAnyBar) && centerConfig.hasControl(item.id)
            }
            if (hidden) continue
        }
        visibleCount++
        total += when (item) {
            PlayerPanelItem.Spacer -> density.spacerMinWidth
            is PlayerPanelItem.Control -> when (item.id) {
                PlayerControlId.Title -> density.titleMaxWidth + chipPad
                PlayerControlId.SystemStatus -> 76.dp + chipPad
                PlayerControlId.TimeLabel -> density.timeMinWidth + chipPad
                PlayerControlId.DanmakuInput -> 72.dp + chipPad
                PlayerControlId.PlayPause -> {
                    val sideCount = listOf(
                        centerConfig.outerLeft,
                        centerConfig.innerLeft,
                        centerConfig.innerRight,
                        centerConfig.outerRight,
                    ).count { it.action != CenterSlotAction.None }
                    density.iconContainerSize * (1 + sideCount) + density.barItemSpacing * sideCount + chipPad
                }
                PlayerControlId.Speed -> density.pillPaddingH * 2 + 24.dp + chipPad
                PlayerControlId.Quality -> density.pillPaddingH * 2 + 30.dp + chipPad
                PlayerControlId.Audio -> density.pillPaddingH * 2 + 32.dp + chipPad
                PlayerControlId.SeekBackward, PlayerControlId.SeekForward ->
                    density.pillPaddingH * 2 + density.badgeIconSize + 18.dp + chipPad
                else -> density.iconContainerSize + chipPad
            }
        }
    }
    if (visibleCount > 1) {
        total += density.barItemSpacing * (visibleCount - 1)
    }
    return total
}

@Composable
private fun PlayerPreviewCanvas(
    layout: PlayerFormFactorLayout,
    isFullscreen: Boolean,
    transportInCenter: Boolean,
    seekButtonSeconds: Int,
    selection: PreviewSelection?,
    dragState: PlayerEditorDragState,
    dropTarget: DropTarget?,
    onSelect: (PreviewSelection) -> Unit,
    onFinishDrag: () -> Unit,
) {
    val sampleStatus = remember {
        PlayerSystemStatus(
            timeText = "21:30",
            battery = BatteryStatus(percent = 86, isCharging = false),
            network = NetworkKind.Wifi,
        )
    }
    val activePlaced = dragState.activeDrag?.payload as? DragPayload.PlacedBarItem
    val topBarDropIndex = (dropTarget as? DropTarget.BarInsert)
        ?.takeIf { it.bar == PlayerBarSlot.Top }?.insertIndex
    val bottomBarDropIndex = (dropTarget as? DropTarget.BarInsert)
        ?.takeIf { it.bar == PlayerBarSlot.Bottom }?.insertIndex
    val hoveredCenterSlot = (dropTarget as? DropTarget.CenterSlot)?.position
    val transportInAnyBar = !transportInCenter &&
        (layout.hasInBottomBar(PlayerControlId.PlayPause) || layout.hasInTopBar(PlayerControlId.PlayPause))

    PlayerTheme {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) dragState.previewCanvasBounds = coords.boundsInWindow()
                    }
                    .clip(MaterialTheme.shapes.large)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF151922), Color(0xFF202634), Color(0xFF12151C)),
                        ),
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), MaterialTheme.shapes.large),
            ) {
                val density = previewDensityFor(maxWidth)
                val availableBarWidth = (maxWidth - density.barHorizontalPadding * 2).coerceAtLeast(120.dp)

                // 顶栏预览（支持直接拖拽重排、跨栏放置与实时插入指示线）
                PreviewControlBar(
                    bar = PlayerBarSlot.Top,
                    items = layout.topBar,
                    centerConfig = layout.center,
                    transportInCenter = transportInCenter,
                    transportInAnyBar = transportInAnyBar,
                    isFullscreen = isFullscreen,
                    seekButtonSeconds = seekButtonSeconds,
                    sampleStatus = sampleStatus,
                    density = density,
                    availableWidth = availableBarWidth,
                    selection = selection,
                    hoveredCenterSlot = hoveredCenterSlot,
                    activePlaced = activePlaced,
                    dropIndex = topBarDropIndex,
                    dragState = dragState,
                    onSelect = onSelect,
                    onFinishDrag = onFinishDrag,
                    onBarBounds = { dragState.topBarBounds = it },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(ControlScrimBottom, Color.Transparent)))
                        .padding(horizontal = density.barHorizontalPadding, vertical = Spacing.Hair),
                )

                // 中央控件簇预览（触控预览且启用中央播放键时居中显示；键鼠预览时整体迁至底栏左端，绝不重复显示）
                if (transportInCenter) {
                    PreviewCenterCluster(
                        config = layout.center,
                        density = density,
                        selectedSlot = (selection as? PreviewSelection.CenterSlot)?.position,
                        dropTargetSlot = hoveredCenterSlot,
                        onSelectSlot = { onSelect(PreviewSelection.CenterSlot(it)) },
                        onSlotBounds = { pos, rect -> dragState.centerSlotBounds[pos] = rect },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                // 底栏预览（支持直接拖拽重排、跨栏放置与实时插入指示线）
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, ControlScrimBottom)))
                        .padding(horizontal = density.barHorizontalPadding, vertical = Spacing.Hair),
                ) {
                    if (isFullscreen) {
                        PreviewSeekTrack(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Hair))
                    }
                    PreviewControlBar(
                        bar = PlayerBarSlot.Bottom,
                        items = layout.bottomBar,
                        centerConfig = layout.center,
                        transportInCenter = transportInCenter,
                        transportInAnyBar = transportInAnyBar,
                        isFullscreen = isFullscreen,
                        seekButtonSeconds = seekButtonSeconds,
                        sampleStatus = sampleStatus,
                        density = density,
                        availableWidth = availableBarWidth,
                        selection = selection,
                        hoveredCenterSlot = hoveredCenterSlot,
                        activePlaced = activePlaced,
                        dropIndex = bottomBarDropIndex,
                        dragState = dragState,
                        onSelect = onSelect,
                        onFinishDrag = onFinishDrag,
                        onBarBounds = { dragState.bottomBarBounds = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewControlBar(
    bar: PlayerBarSlot,
    items: List<PlayerPanelItem>,
    centerConfig: CenterClusterConfig,
    transportInCenter: Boolean,
    transportInAnyBar: Boolean,
    isFullscreen: Boolean,
    seekButtonSeconds: Int,
    sampleStatus: PlayerSystemStatus,
    density: PreviewDensity,
    availableWidth: Dp,
    selection: PreviewSelection?,
    hoveredCenterSlot: CenterSlotPosition?,
    activePlaced: DragPayload.PlacedBarItem?,
    dropIndex: Int?,
    dragState: PlayerEditorDragState,
    onSelect: (PreviewSelection) -> Unit,
    onFinishDrag: () -> Unit,
    onBarBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestFinishDrag by rememberUpdatedState(onFinishDrag)
    val overflowScroll = estimateBarMinWidth(
        items = items,
        centerConfig = centerConfig,
        transportInCenter = transportInCenter,
        transportInAnyBar = transportInAnyBar,
        density = density,
    ) > availableWidth
    val scrollMod = if (overflowScroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier
    val draggingCenterAction = dragState.activeDrag?.payload?.toCenterSlotActionOrNull(seekButtonSeconds) != null

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(density.barItemSpacing),
        modifier = modifier
            .heightIn(min = density.barMinHeight)
            .onGloballyPositioned { coords ->
                if (coords.isAttached) onBarBounds(coords.boundsInWindow())
            }
            .then(
                if (dropIndex != null) {
                    Modifier.border(
                        1.5.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                        RoundedCornerShape(8.dp),
                    )
                } else {
                    Modifier
                },
            )
            .then(scrollMod),
    ) {
        items.forEachIndexed { index, item ->
            if (dropIndex == index) {
                BarInsertionIndicator(height = density.spacerHeight)
            }
            val hiddenByTransport = item is PlayerPanelItem.Control && when (item.id) {
                PlayerControlId.PlayPause -> transportInCenter
                else -> (transportInCenter || transportInAnyBar) && centerConfig.hasControl(item.id)
            }
            if (hiddenByTransport) {
                dragState.barItemBounds.remove(bar to index)
                return@forEachIndexed
            }
            key(bar, index) {
                val isSelected = selection == PreviewSelection.PanelItem(bar, index)
                val isBeingDragged = activePlaced?.bar == bar && activePlaced.index == index
                val canDrag = !(
                    isFullscreen &&
                        bar == PlayerBarSlot.Top &&
                        item is PlayerPanelItem.Control &&
                        item.id == PlayerControlId.Back
                    )
                val originRef = remember { OffsetRef() }
                val flexibleMod = when {
                    item == PlayerPanelItem.Spacer -> if (overflowScroll) {
                        Modifier.width(density.spacerMinWidth.coerceAtLeast(18.dp))
                    } else {
                        Modifier.weight(1f)
                    }
                    item is PlayerPanelItem.Control && item.id == PlayerControlId.DanmakuInput -> if (overflowScroll) {
                        Modifier.widthIn(min = 84.dp)
                    } else {
                        Modifier.weight(1f)
                    }
                    else -> Modifier
                }
                val itemModifier = flexibleMod
                    .alpha(if (isBeingDragged) 0.32f else 1f)
                    .onGloballyPositioned { coords ->
                        if (coords.isAttached) {
                            originRef.value = coords.positionInWindow()
                            dragState.barItemBounds[bar to index] = coords.boundsInWindow()
                        }
                    }
                    .then(
                        if (canDrag) {
                            Modifier.pointerInput(bar, index, item) {
                                var pointerWindow = Offset.Zero
                                detectDragGestures(
                                    onDragStart = { startOffset ->
                                        pointerWindow = originRef.value + startOffset
                                        dragState.startDrag(
                                            DragPayload.PlacedBarItem(bar, index, item),
                                            pointerWindow,
                                        )
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        pointerWindow += dragAmount
                                        dragState.updateDrag(pointerWindow)
                                    },
                                    onDragEnd = { latestFinishDrag() },
                                    onDragCancel = { dragState.cancelDrag() },
                                )
                            }
                        } else {
                            Modifier
                        },
                    )

                if (item is PlayerPanelItem.Control && item.id == PlayerControlId.PlayPause) {
                    PreviewInlineTransportCluster(
                        config = centerConfig,
                        density = density,
                        selectedSlot = (selection as? PreviewSelection.CenterSlot)?.position,
                        dropTargetSlot = hoveredCenterSlot,
                        showEmptySlots = draggingCenterAction,
                        onSelectSlot = { onSelect(PreviewSelection.CenterSlot(it)) },
                        onSlotBounds = { pos, rect -> dragState.centerSlotBounds[pos] = rect },
                        modifier = itemModifier,
                    )
                } else {
                    PreviewPanelItemChip(
                        item = item,
                        isFullscreen = isFullscreen,
                        seekButtonSeconds = seekButtonSeconds,
                        sampleStatus = sampleStatus,
                        density = density,
                        selected = isSelected,
                        onClick = { onSelect(PreviewSelection.PanelItem(bar, index)) },
                        modifier = itemModifier,
                    )
                }
            }
        }
        if (dropIndex == items.size) {
            BarInsertionIndicator(height = density.spacerHeight)
        }
    }
}

/** 键鼠视角下位于底栏（或顶栏）的一体化播放控制簇预览：点选与拖放均直接编辑同一份 `layout.center`。 */
@Composable
private fun PreviewInlineTransportCluster(
    config: CenterClusterConfig,
    density: PreviewDensity,
    selectedSlot: CenterSlotPosition?,
    dropTargetSlot: CenterSlotPosition?,
    showEmptySlots: Boolean,
    onSelectSlot: (CenterSlotPosition) -> Unit,
    onSlotBounds: (CenterSlotPosition, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlight = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(density.barItemSpacing),
        modifier = modifier,
    ) {
        PreviewInlineTransportSideSlot(
            position = CenterSlotPosition.OuterLeft,
            slot = config.outerLeft,
            density = density,
            selected = selectedSlot == CenterSlotPosition.OuterLeft,
            dropHighlighted = dropTargetSlot == CenterSlotPosition.OuterLeft,
            showEmpty = showEmptySlots,
            onSelect = { onSelectSlot(CenterSlotPosition.OuterLeft) },
            onBounds = { onSlotBounds(CenterSlotPosition.OuterLeft, it) },
        )
        PreviewInlineTransportSideSlot(
            position = CenterSlotPosition.InnerLeft,
            slot = config.innerLeft,
            density = density,
            selected = selectedSlot == CenterSlotPosition.InnerLeft,
            dropHighlighted = dropTargetSlot == CenterSlotPosition.InnerLeft,
            showEmpty = showEmptySlots,
            onSelect = { onSelectSlot(CenterSlotPosition.InnerLeft) },
            onBounds = { onSlotBounds(CenterSlotPosition.InnerLeft, it) },
        )
        val centerSelected = selectedSlot == CenterSlotPosition.Center
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .then(if (centerSelected) Modifier.border(2.dp, highlight, RoundedCornerShape(8.dp)) else Modifier)
                .clickable { onSelectSlot(CenterSlotPosition.Center) }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = density.chipPaddingH, vertical = density.chipPaddingV),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(density.iconContainerSize)
                    .clip(CircleShape)
                    .background(highlight),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = controlLabel(PlayerControlId.PlayPause),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(density.iconSize),
                )
            }
        }
        PreviewInlineTransportSideSlot(
            position = CenterSlotPosition.InnerRight,
            slot = config.innerRight,
            density = density,
            selected = selectedSlot == CenterSlotPosition.InnerRight,
            dropHighlighted = dropTargetSlot == CenterSlotPosition.InnerRight,
            showEmpty = showEmptySlots,
            onSelect = { onSelectSlot(CenterSlotPosition.InnerRight) },
            onBounds = { onSlotBounds(CenterSlotPosition.InnerRight, it) },
        )
        PreviewInlineTransportSideSlot(
            position = CenterSlotPosition.OuterRight,
            slot = config.outerRight,
            density = density,
            selected = selectedSlot == CenterSlotPosition.OuterRight,
            dropHighlighted = dropTargetSlot == CenterSlotPosition.OuterRight,
            showEmpty = showEmptySlots,
            onSelect = { onSelectSlot(CenterSlotPosition.OuterRight) },
            onBounds = { onSlotBounds(CenterSlotPosition.OuterRight, it) },
        )
    }
}

@Composable
private fun PreviewInlineTransportSideSlot(
    position: CenterSlotPosition,
    slot: CenterSlotConfig,
    density: PreviewDensity,
    selected: Boolean,
    dropHighlighted: Boolean,
    showEmpty: Boolean,
    onSelect: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    if (slot.action == CenterSlotAction.None && !showEmpty && !selected) return
    val highlight = MaterialTheme.colorScheme.primary
    val boundsMod = Modifier.onGloballyPositioned { coords ->
        if (coords.isAttached) onBounds(coords.boundsInWindow())
    }
    if (slot.action == CenterSlotAction.None) {
        val strokeColor = if (dropHighlighted || selected) highlight else FixedColors.OnMedia.copy(alpha = 0.45f)
        Box(
            contentAlignment = Alignment.Center,
            modifier = boundsMod
                .size(density.iconContainerSize)
                .clip(CircleShape)
                .background(if (dropHighlighted) highlight.copy(alpha = 0.28f) else Color.Transparent)
                .clickable(onClick = onSelect)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = (if (dropHighlighted || selected) 2.dp else 1.2.dp).toPx()
                val radius = (size.minDimension - strokePx) / 2f
                if (radius > 0f) {
                    drawCircle(
                        color = strokeColor,
                        radius = radius,
                        style = Stroke(
                            width = strokePx,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f), 0f),
                        ),
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = centerSlotLabel(position),
                tint = strokeColor,
                modifier = Modifier.size((density.iconSize - 2.dp).coerceAtLeast(10.dp)),
            )
        }
        return
    }
    val icon = when (slot.action) {
        CenterSlotAction.Previous -> Icons.Filled.SkipPrevious
        CenterSlotAction.Next -> Icons.Filled.SkipNext
        is CenterSlotAction.SeekBackward -> Icons.Filled.FastRewind
        is CenterSlotAction.SeekForward -> Icons.Filled.FastForward
        CenterSlotAction.None -> Icons.Filled.Add
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = boundsMod
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (dropHighlighted || selected) {
                    Modifier.border(if (dropHighlighted) 2.5.dp else 2.dp, highlight, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onSelect)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = density.chipPaddingH, vertical = density.chipPaddingV),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(density.iconContainerSize)
                .clip(CircleShape)
                .background(
                    when {
                        dropHighlighted -> highlight.copy(alpha = 0.35f)
                        slot.showBackground -> mediaControlContainer()
                        else -> Color.Transparent
                    },
                ),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = centerSlotLabel(position),
                tint = FixedColors.OnMedia,
                modifier = Modifier.size(density.iconSize),
            )
        }
    }
}

@Composable
private fun BarInsertionIndicator(height: Dp = 24.dp) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(height)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
    )
}

@Composable
private fun PreviewCenterCluster(
    config: CenterClusterConfig,
    density: PreviewDensity,
    selectedSlot: CenterSlotPosition?,
    dropTargetSlot: CenterSlotPosition?,
    onSelectSlot: (CenterSlotPosition) -> Unit,
    onSlotBounds: (CenterSlotPosition, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlight = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(density.centerSpacing),
        modifier = modifier,
    ) {
        PreviewCenterSideSlot(
            position = CenterSlotPosition.OuterLeft,
            slot = config.outerLeft,
            baseSize = density.centerSideBaseSize,
            selected = selectedSlot == CenterSlotPosition.OuterLeft,
            dropHighlighted = dropTargetSlot == CenterSlotPosition.OuterLeft,
            onSelect = { onSelectSlot(CenterSlotPosition.OuterLeft) },
            onBounds = { onSlotBounds(CenterSlotPosition.OuterLeft, it) },
        )
        PreviewCenterSideSlot(
            position = CenterSlotPosition.InnerLeft,
            slot = config.innerLeft,
            baseSize = density.centerSideBaseSize,
            selected = selectedSlot == CenterSlotPosition.InnerLeft,
            dropHighlighted = dropTargetSlot == CenterSlotPosition.InnerLeft,
            onSelect = { onSelectSlot(CenterSlotPosition.InnerLeft) },
            onBounds = { onSlotBounds(CenterSlotPosition.InnerLeft, it) },
        )

        val centerSelected = selectedSlot == CenterSlotPosition.Center
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(density.centerPlayOuterSize)
                .clip(CircleShape)
                .then(if (centerSelected) Modifier.border(2.dp, highlight, CircleShape) else Modifier)
                .clickable { onSelectSlot(CenterSlotPosition.Center) }
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            if (config.showCenterPlay) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(density.centerPlayInnerSize)
                        .clip(CircleShape)
                        .background(highlight),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(density.centerPlayIconSize),
                    )
                }
            } else {
                val strokeColor = if (centerSelected) highlight else FixedColors.OnMedia.copy(alpha = 0.45f)
                Canvas(modifier = Modifier.size(density.centerPlayInnerSize)) {
                    val strokePx = (if (centerSelected) 2.dp else 1.2.dp).toPx()
                    val radius = (size.minDimension - strokePx) / 2f
                    if (radius > 0f) {
                        drawCircle(
                            color = strokeColor,
                            radius = radius,
                            style = Stroke(
                                width = strokePx,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                            ),
                        )
                    }
                }
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = strokeColor,
                    modifier = Modifier.size((density.centerPlayIconSize - 4.dp).coerceAtLeast(14.dp)),
                )
            }
        }

        PreviewCenterSideSlot(
            position = CenterSlotPosition.InnerRight,
            slot = config.innerRight,
            baseSize = density.centerSideBaseSize,
            selected = selectedSlot == CenterSlotPosition.InnerRight,
            dropHighlighted = dropTargetSlot == CenterSlotPosition.InnerRight,
            onSelect = { onSelectSlot(CenterSlotPosition.InnerRight) },
            onBounds = { onSlotBounds(CenterSlotPosition.InnerRight, it) },
        )
        PreviewCenterSideSlot(
            position = CenterSlotPosition.OuterRight,
            slot = config.outerRight,
            baseSize = density.centerSideBaseSize,
            selected = selectedSlot == CenterSlotPosition.OuterRight,
            dropHighlighted = dropTargetSlot == CenterSlotPosition.OuterRight,
            onSelect = { onSelectSlot(CenterSlotPosition.OuterRight) },
            onBounds = { onSlotBounds(CenterSlotPosition.OuterRight, it) },
        )
    }
}

@Composable
private fun PreviewCenterSideSlot(
    position: CenterSlotPosition,
    slot: CenterSlotConfig,
    baseSize: Dp,
    selected: Boolean,
    dropHighlighted: Boolean,
    onSelect: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    val highlight = MaterialTheme.colorScheme.primary
    val diameter = (baseSize * slot.scale.ratio).coerceIn(20.dp, 38.dp)
    val boundsMod = Modifier.onGloballyPositioned { coords ->
        if (coords.isAttached) onBounds(coords.boundsInWindow())
    }

    if (slot.action == CenterSlotAction.None) {
        val strokeColor = when {
            dropHighlighted -> highlight
            selected -> highlight
            else -> FixedColors.OnMedia.copy(alpha = 0.45f)
        }
        val strokeWidth = if (dropHighlighted || selected) 2.2.dp else 1.2.dp
        Box(
            contentAlignment = Alignment.Center,
            modifier = boundsMod
                .size(diameter)
                .clip(CircleShape)
                .background(if (dropHighlighted) highlight.copy(alpha = 0.28f) else Color.Transparent)
                .clickable(onClick = onSelect)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = strokeWidth.toPx()
                val radius = (size.minDimension - strokePx) / 2f
                if (radius > 0f) {
                    drawCircle(
                        color = strokeColor,
                        radius = radius,
                        style = Stroke(
                            width = strokePx,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f),
                        ),
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = centerSlotLabel(position),
                tint = strokeColor,
                modifier = Modifier.size((diameter * 0.45f).coerceIn(10.dp, 16.dp)),
            )
        }
        return
    }

    val icon = when (slot.action) {
        CenterSlotAction.Previous -> Icons.Filled.SkipPrevious
        CenterSlotAction.Next -> Icons.Filled.SkipNext
        is CenterSlotAction.SeekBackward -> Icons.Filled.FastRewind
        is CenterSlotAction.SeekForward -> Icons.Filled.FastForward
        CenterSlotAction.None -> Icons.Filled.Add
    }
    val secondsBadge = slot.action.seekSecondsOrNull()
    val iconSize = (diameter * (if (secondsBadge != null) 0.42f else 0.56f)).coerceIn(10.dp, 18.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = boundsMod
            .size(diameter)
            .clip(CircleShape)
            .background(
                when {
                    dropHighlighted -> highlight.copy(alpha = 0.35f)
                    slot.showBackground -> mediaControlContainer()
                    else -> Color.Transparent
                },
            )
            .then(
                if (dropHighlighted || selected) {
                    Modifier.border(if (dropHighlighted) 2.5.dp else 2.dp, highlight, CircleShape)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onSelect)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        if (secondsBadge != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, contentDescription = null, tint = FixedColors.OnMedia, modifier = Modifier.size(iconSize))
                Text(
                    text = "${secondsBadge}s",
                    style = MaterialTheme.typography.labelSmall.tabular.copy(fontSize = 8.sp, lineHeight = 9.sp),
                    color = FixedColors.OnMedia,
                    maxLines = 1,
                )
            }
        } else {
            Icon(icon, contentDescription = null, tint = FixedColors.OnMedia, modifier = Modifier.size(iconSize))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlPaletteTray(
    layout: PlayerFormFactorLayout,
    isFullscreen: Boolean,
    showCenterControls: Boolean,
    seekButtonSeconds: Int,
    dragState: PlayerEditorDragState,
    dropTarget: DropTarget?,
    onFinishDrag: () -> Unit,
    onQuickAddControl: (PlayerControlId) -> Unit,
    onQuickAddSpacer: () -> Unit,
) {
    val activePlaced = dragState.activeDrag?.payload as? DragPayload.PlacedBarItem
    val isRemovingHover = dropTarget == DropTarget.Remove && activePlaced != null
    val canRemovePlaced = activePlaced != null &&
        layout.canRemoveBarItem(activePlaced.bar, activePlaced.index, isFullscreen)

    val unusedControls = remember(layout, isFullscreen) {
        PlayerControlId.entries.filter { id ->
            val allowed = when (id) {
                PlayerControlId.Back -> false
                PlayerControlId.Title, PlayerControlId.SystemStatus, PlayerControlId.Lock -> isFullscreen
                else -> true
            }
            allowed && !layout.hasInTopBar(id) && !layout.hasInBottomBar(id)
        }
    }

    val shape = MaterialTheme.shapes.large
    val borderColor = when {
        isRemovingHover && canRemovePlaced -> MaterialTheme.colorScheme.error
        activePlaced != null -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }

    Surface(
        shape = shape,
        color = if (isRemovingHover && canRemovePlaced) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                if (coords.isAttached) dragState.paletteBounds = coords.boundsInWindow()
            }
            .border(if (isRemovingHover) 2.dp else 1.dp, borderColor, shape),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier.padding(Spacing.Comfortable),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = when {
                        activePlaced != null && canRemovePlaced -> stringResource(Res.string.player_custom_palette_drop_remove)
                        activePlaced != null -> stringResource(Res.string.player_custom_palette_drop_required)
                        else -> stringResource(Res.string.player_custom_palette_title)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        isRemovingHover && canRemovePlaced -> MaterialTheme.colorScheme.error
                        activePlaced != null -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                if (activePlaced == null) {
                    Text(
                        text = stringResource(Res.string.player_custom_palette_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // 栏位控件（含弹性空白 + 当前形态尚未放置的控件）
            Text(
                text = stringResource(Res.string.player_custom_palette_bar_controls),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                DraggablePaletteChip(
                    icon = Icons.Filled.SpaceBar,
                    label = stringResource(Res.string.player_custom_spacer),
                    payload = DragPayload.PaletteSpacer,
                    dragState = dragState,
                    onFinishDrag = onFinishDrag,
                    onTap = onQuickAddSpacer,
                )
                for (id in unusedControls) {
                    key(id) {
                        DraggablePaletteChip(
                            icon = controlIcon(id, isFullscreen),
                            label = controlLabel(id),
                            payload = DragPayload.PaletteControl(id),
                            dragState = dragState,
                            onFinishDrag = onFinishDrag,
                            onTap = { onQuickAddControl(id) },
                        )
                    }
                }
                if (unusedControls.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.player_custom_palette_all_placed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }

            // 中央快捷动作（可拖入中央 4 槽位，也可拖入顶栏/底栏）
            if (showCenterControls) {
                Spacer(Modifier.height(Spacing.Hair))
                Text(
                    text = stringResource(Res.string.player_custom_palette_center_actions),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                ) {
                    val centerActions = listOf(
                        Triple(
                            CenterSlotAction.Previous,
                            Icons.Filled.SkipPrevious,
                            stringResource(Res.string.player_custom_action_previous),
                        ),
                        Triple(
                            CenterSlotAction.Next,
                            Icons.Filled.SkipNext,
                            stringResource(Res.string.player_custom_action_next),
                        ),
                        Triple(
                            CenterSlotAction.SeekBackward(seekButtonSeconds),
                            Icons.Filled.FastRewind,
                            stringResource(Res.string.player_custom_action_seek_backward),
                        ),
                        Triple(
                            CenterSlotAction.SeekForward(seekButtonSeconds),
                            Icons.Filled.FastForward,
                            stringResource(Res.string.player_custom_action_seek_forward),
                        ),
                    )
                    for ((action, icon, label) in centerActions) {
                        DraggablePaletteChip(
                            icon = icon,
                            label = label,
                            payload = DragPayload.PaletteCenterAction(action),
                            dragState = dragState,
                            onFinishDrag = onFinishDrag,
                            onTap = {},
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DraggablePaletteChip(
    icon: ImageVector,
    label: String,
    payload: DragPayload,
    dragState: PlayerEditorDragState,
    onFinishDrag: () -> Unit,
    onTap: () -> Unit,
) {
    val latestFinishDrag by rememberUpdatedState(onFinishDrag)
    val originRef = remember { OffsetRef() }
    val shape = CircleShape
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .onGloballyPositioned { coords ->
                if (coords.isAttached) originRef.value = coords.positionInWindow()
            }
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .clickable(onClick = onTap)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(payload) {
                var pointerWindow = Offset.Zero
                detectDragGestures(
                    onDragStart = { startOffset ->
                        pointerWindow = originRef.value + startOffset
                        dragState.startDrag(payload, pointerWindow)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        pointerWindow += dragAmount
                        dragState.updateDrag(pointerWindow)
                    },
                    onDragEnd = { latestFinishDrag() },
                    onDragCancel = { dragState.cancelDrag() },
                )
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
            modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = 6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DragGhostOverlay(
    payload: DragPayload,
    pointerInRoot: Offset,
    isFullscreen: Boolean,
    removing: Boolean,
) {
    val icon: ImageVector = when (payload) {
        is DragPayload.PaletteControl -> controlIcon(payload.id, isFullscreen)
        DragPayload.PaletteSpacer -> Icons.Filled.SpaceBar
        is DragPayload.PaletteCenterAction -> when (payload.action) {
            CenterSlotAction.Previous -> Icons.Filled.SkipPrevious
            CenterSlotAction.Next -> Icons.Filled.SkipNext
            is CenterSlotAction.SeekBackward -> Icons.Filled.FastRewind
            is CenterSlotAction.SeekForward -> Icons.Filled.FastForward
            CenterSlotAction.None -> Icons.Filled.Add
        }
        is DragPayload.PlacedBarItem -> when (payload.item) {
            PlayerPanelItem.Spacer -> Icons.Filled.SpaceBar
            is PlayerPanelItem.Control -> controlIcon(payload.item.id, isFullscreen)
        }
    }
    val label: String = when (payload) {
        is DragPayload.PaletteControl -> controlLabel(payload.id)
        DragPayload.PaletteSpacer -> stringResource(Res.string.player_custom_spacer)
        is DragPayload.PaletteCenterAction -> when (payload.action) {
            CenterSlotAction.Previous -> stringResource(Res.string.player_custom_action_previous)
            CenterSlotAction.Next -> stringResource(Res.string.player_custom_action_next)
            is CenterSlotAction.SeekBackward -> stringResource(Res.string.player_custom_action_seek_backward)
            is CenterSlotAction.SeekForward -> stringResource(Res.string.player_custom_action_seek_forward)
            CenterSlotAction.None -> ""
        }
        is DragPayload.PlacedBarItem -> when (payload.item) {
            PlayerPanelItem.Spacer -> stringResource(Res.string.player_custom_spacer)
            is PlayerPanelItem.Control -> controlLabel(payload.item.id)
        }
    }
    val containerColor = if (removing) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.92f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
    }
    val contentColor = if (removing) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Surface(
        shape = CircleShape,
        color = containerColor,
        shadowElevation = 8.dp,
        modifier = Modifier
            .zIndex(100f)
            .offset {
                val safeX = if (pointerInRoot.x.isFinite()) (pointerInRoot.x - 48f).roundToInt() else 0
                val safeY = if (pointerInRoot.y.isFinite()) (pointerInRoot.y - 44f).roundToInt() else 0
                IntOffset(x = safeX, y = safeY)
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
            modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
        ) {
            Icon(
                imageVector = if (removing) Icons.Outlined.DeleteOutline else icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun GesturePreviewCanvas(
    state: SettingsUiState,
    layoutPrefs: PlayerLayoutPrefs,
) {
    val gestures = layoutPrefs.gestures
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary
    val error = MaterialTheme.colorScheme.error

    PlayerTheme {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(MaterialTheme.shapes.large)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF151922), Color(0xFF202634), Color(0xFF12151C)),
                    ),
                )
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), MaterialTheme.shapes.large),
        ) {
            val canvasWidth = maxWidth
            val canvasHeight = maxHeight

            // 1. 两侧纵向滑动热区（随 verticalZoneFraction 实时缩放）
            if (state.swipeBrightnessVolume) {
                val zoneWidth = canvasWidth * gestures.verticalZoneFraction.coerceIn(
                    MIN_VERTICAL_ZONE_FRACTION,
                    MAX_VERTICAL_ZONE_FRACTION,
                )
                val zonePct = (gestures.verticalZoneFraction * 100f).roundToInt()
                val leftActive = gestures.leftVerticalSwipe != VerticalSwipeAction.None
                val rightActive = gestures.rightVerticalSwipe != VerticalSwipeAction.None

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(zoneWidth)
                        .fillMaxHeight()
                        .background(if (leftActive) primary.copy(alpha = 0.18f) else FixedColors.OnMedia.copy(alpha = 0.05f))
                        .border(
                            width = 1.dp,
                            color = if (leftActive) primary.copy(alpha = 0.55f) else FixedColors.OnMedia.copy(alpha = 0.18f),
                        )
                        .padding(Spacing.Hair),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(Res.string.player_custom_gesture_zone_left),
                            style = MaterialTheme.typography.labelSmall,
                            color = FixedColors.OnMedia.copy(alpha = 0.72f),
                            maxLines = 1,
                        )
                        Text(
                            text = verticalSwipeActionLabel(gestures.leftVerticalSwipe),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (leftActive) primary else FixedColors.OnMedia.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                        Text(
                            text = "${zonePct}%",
                            style = MaterialTheme.typography.labelSmall.tabular,
                            color = FixedColors.OnMedia.copy(alpha = 0.65f),
                            maxLines = 1,
                        )
                    }
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(zoneWidth)
                        .fillMaxHeight()
                        .background(if (rightActive) tertiary.copy(alpha = 0.18f) else FixedColors.OnMedia.copy(alpha = 0.05f))
                        .border(
                            width = 1.dp,
                            color = if (rightActive) tertiary.copy(alpha = 0.55f) else FixedColors.OnMedia.copy(alpha = 0.18f),
                        )
                        .padding(Spacing.Hair),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(Res.string.player_custom_gesture_zone_right),
                            style = MaterialTheme.typography.labelSmall,
                            color = FixedColors.OnMedia.copy(alpha = 0.72f),
                            maxLines = 1,
                        )
                        Text(
                            text = verticalSwipeActionLabel(gestures.rightVerticalSwipe),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (rightActive) tertiary else FixedColors.OnMedia.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                        Text(
                            text = "${zonePct}%",
                            style = MaterialTheme.typography.labelSmall.tabular,
                            color = FixedColors.OnMedia.copy(alpha = 0.65f),
                            maxLines = 1,
                        )
                    }
                }
            }

            // 2. 顶部两角松手取消区（15% x 15%，随 swipeSeek && swipeSeekCancelZone 开关显隐）
            if (state.swipeSeek && gestures.swipeSeekCancelZone) {
                val cancelW = canvasWidth * 0.18f
                val cancelH = canvasHeight * 0.20f
                val cancelLabel = stringResource(Res.string.player_custom_gesture_zone_cancel)
                for (alignment in listOf(Alignment.TopStart, Alignment.TopEnd)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .align(alignment)
                            .size(width = cancelW, height = cancelH)
                            .background(error.copy(alpha = 0.28f))
                            .border(1.dp, error.copy(alpha = 0.75f)),
                    ) {
                        Text(
                            text = cancelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = FixedColors.OnMedia,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
            }

            // 3. 中央横划进度灵敏度标签
            if (state.swipeSeek) {
                val sensitivityLabel = swipeSeekSensitivityLabel(gestures.swipeSeekSensitivity)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(FixedColors.ScrimOnMedia)
                        .border(1.dp, FixedColors.OnMedia.copy(alpha = 0.25f), CircleShape)
                        .padding(horizontal = Spacing.Cozy, vertical = Spacing.Hair),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SwapHoriz,
                        contentDescription = null,
                        tint = FixedColors.OnMedia,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = stringResource(Res.string.player_custom_gesture_zone_swipe_seek, sensitivityLabel),
                        style = MaterialTheme.typography.labelSmall,
                        color = FixedColors.OnMedia,
                        maxLines = 1,
                    )
                }
            }

            // 4. 底部两侧双击进退热区（随 doubleTapZoneFraction 实时缩放）
            if (state.doubleTapSeek) {
                val dtWidth = canvasWidth * gestures.doubleTapZoneFraction.coerceIn(
                    MIN_DOUBLE_TAP_ZONE_FRACTION,
                    MAX_DOUBLE_TAP_ZONE_FRACTION,
                )
                val sec = layoutPrefs.effectiveDoubleTapSeekSeconds
                val dtShape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .width(dtWidth)
                        .height(30.dp)
                        .clip(dtShape)
                        .background(secondary.copy(alpha = 0.30f))
                        .border(1.dp, secondary.copy(alpha = 0.75f), dtShape),
                ) {
                    Text(
                        text = stringResource(Res.string.player_custom_gesture_zone_double_tap_back, sec),
                        style = MaterialTheme.typography.labelSmall.tabular,
                        color = FixedColors.OnMedia,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .width(dtWidth)
                        .height(30.dp)
                        .clip(dtShape)
                        .background(secondary.copy(alpha = 0.30f))
                        .border(1.dp, secondary.copy(alpha = 0.75f), dtShape),
                ) {
                    Text(
                        text = stringResource(Res.string.player_custom_gesture_zone_double_tap_forward, sec),
                        style = MaterialTheme.typography.labelSmall.tabular,
                        color = FixedColors.OnMedia,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                    )
                }

                Text(
                    text = stringResource(Res.string.player_custom_gesture_zone_double_tap_play),
                    style = MaterialTheme.typography.labelSmall,
                    color = FixedColors.OnMedia.copy(alpha = 0.7f),
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewSeekTrack(modifier: Modifier = Modifier) {
    val active = MaterialTheme.colorScheme.primary
    val track = FixedColors.OnMedia.copy(alpha = 0.3f)
    Canvas(modifier = modifier.height(8.dp)) {
        val cy = size.height / 2f
        val progressX = size.width * 0.36f
        drawLine(track, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 3.dp.toPx())
        drawLine(active, Offset(0f, cy), Offset(progressX, cy), strokeWidth = 3.dp.toPx())
        drawCircle(active, radius = 4.dp.toPx(), center = Offset(progressX, cy))
    }
}

@Composable
private fun PreviewPanelItemChip(
    item: PlayerPanelItem,
    isFullscreen: Boolean,
    seekButtonSeconds: Int,
    sampleStatus: PlayerSystemStatus,
    density: PreviewDensity,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlight = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(8.dp)
    if (item == PlayerPanelItem.Spacer) {
        val strokeColor = if (selected) highlight else FixedColors.OnMedia.copy(alpha = 0.35f)
        BoxWithConstraints(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .height(density.spacerHeight)
                .widthIn(min = density.spacerMinWidth)
                .clip(shape)
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = (if (selected) 2.dp else 1.dp).toPx()
                val rectW = size.width - strokePx
                val rectH = size.height - strokePx
                if (rectW > 0f && rectH > 0f) {
                    drawRoundRect(
                        color = strokeColor,
                        topLeft = Offset(strokePx / 2f, strokePx / 2f),
                        size = Size(rectW, rectH),
                        cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                        style = Stroke(
                            width = strokePx,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                        ),
                    )
                }
            }
            if (maxWidth >= 22.dp) {
                Text(
                    text = stringResource(Res.string.player_custom_spacer_short),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = density.pillFontSize),
                    color = strokeColor,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        return
    }

    val control = item as PlayerPanelItem.Control
    val borderMod = if (selected) {
        Modifier.border(2.dp, highlight, shape)
    } else {
        Modifier
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(shape)
            .then(borderMod)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = density.chipPaddingH, vertical = density.chipPaddingV),
    ) {
        when (control.id) {
            PlayerControlId.Title -> Text(
                text = stringResource(Res.string.player_custom_preview_title),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = density.titleFontSize),
                color = FixedColors.OnMedia,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = density.titleMaxWidth),
            )
            PlayerControlId.SystemStatus -> PlayerStatusCluster(
                status = sampleStatus,
                showBackground = control.showBackground,
            )
            PlayerControlId.TimeLabel -> Text(
                text = density.timeText,
                style = MaterialTheme.typography.labelSmall.tabular.copy(fontSize = density.pillFontSize),
                color = FixedColors.OnMedia,
                maxLines = 1,
                softWrap = false,
            )
            PlayerControlId.DanmakuInput -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(density.iconContainerSize)
                    .clip(CircleShape)
                    .background(if (control.showBackground) mediaControlContainer() else Color.Transparent)
                    .then(
                        if (!control.showBackground) {
                            Modifier.border(1.dp, FixedColors.OnMedia.copy(alpha = 0.3f), CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = density.pillPaddingH.coerceAtLeast(6.dp)),
            ) {
                Text(
                    text = stringResource(Res.string.danmaku_input_hint),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = density.pillFontSize),
                    color = FixedColors.OnMedia.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            PlayerControlId.Speed -> PreviewTextPill("1.0x", density = density, showBackground = control.showBackground)
            PlayerControlId.Quality -> PreviewTextPill("1080P", density = density, showBackground = control.showBackground)
            PlayerControlId.Audio -> PreviewTextPill("Hi-Res", density = density, showBackground = control.showBackground)
            PlayerControlId.SeekBackward -> PreviewIconWithBadge(
                Icons.Filled.FastRewind,
                "${seekButtonSeconds}s",
                density = density,
                showBackground = control.showBackground,
            )
            PlayerControlId.SeekForward -> PreviewIconWithBadge(
                Icons.Filled.FastForward,
                "${seekButtonSeconds}s",
                density = density,
                showBackground = control.showBackground,
            )
            PlayerControlId.PlayPause -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(density.iconContainerSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = controlLabel(control.id),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(density.iconSize),
                )
            }
            else -> Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(density.iconContainerSize)
                    .clip(CircleShape)
                    .background(if (control.showBackground) mediaControlContainer() else Color.Transparent),
            ) {
                Icon(
                    imageVector = controlIcon(control.id, isFullscreen),
                    contentDescription = controlLabel(control.id),
                    tint = FixedColors.OnMedia,
                    modifier = Modifier.size(density.iconSize),
                )
            }
        }
    }
}

@Composable
private fun PreviewTextPill(
    text: String,
    density: PreviewDensity,
    showBackground: Boolean = true,
) {
    val baseStyle = MaterialTheme.typography.labelSmall.tabular.copy(fontSize = density.pillFontSize)
    Text(
        text = text,
        style = if (showBackground) {
            baseStyle
        } else {
            baseStyle.copy(shadow = MediaBorderlessTextShadow)
        },
        color = FixedColors.OnMedia,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (showBackground) mediaControlContainer() else Color.Transparent)
            .padding(
                horizontal = if (showBackground) density.pillPaddingH else (density.pillPaddingH / 2),
                vertical = density.pillPaddingV,
            ),
    )
}

@Composable
private fun PreviewIconWithBadge(
    icon: ImageVector,
    badge: String,
    density: PreviewDensity,
    showBackground: Boolean = true,
) {
    val baseStyle = MaterialTheme.typography.labelSmall.tabular.copy(fontSize = density.pillFontSize)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(if (showBackground) mediaControlContainer() else Color.Transparent)
            .padding(
                horizontal = if (showBackground) density.pillPaddingH else (density.pillPaddingH / 2),
                vertical = density.pillPaddingV,
            ),
    ) {
        Icon(icon, contentDescription = null, tint = FixedColors.OnMedia, modifier = Modifier.size(density.badgeIconSize))
        Text(
            badge,
            style = if (showBackground) {
                baseStyle
            } else {
                baseStyle.copy(shadow = MediaBorderlessTextShadow)
            },
            color = FixedColors.OnMedia,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun supportsBackgroundToggle(id: PlayerControlId): Boolean = when (id) {
    PlayerControlId.Title, PlayerControlId.TimeLabel, PlayerControlId.PlayPause -> false
    else -> true
}

@Composable
private fun PlainToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable { onCheckedChange(!checked) }
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionInspectorStrip(
    layoutMode: PlayerLayoutMode,
    layout: PlayerFormFactorLayout,
    layoutPrefs: PlayerLayoutPrefs,
    selection: PreviewSelection,
    onSelect: (PreviewSelection) -> Unit,
    onDismiss: () -> Unit,
    onUpdateLayout: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    if (selection is PreviewSelection.PanelItem &&
        layout.itemsFor(selection.bar).getOrNull(selection.index) == null
    ) {
        return
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        when (selection) {
            is PreviewSelection.PanelItem -> {
                val items = layout.itemsFor(selection.bar)
                val item = items[selection.index]
                val itemTitle = when (item) {
                    PlayerPanelItem.Spacer -> stringResource(Res.string.player_custom_spacer)
                    is PlayerPanelItem.Control -> controlLabel(item.id)
                }
                val itemIcon = when (item) {
                    PlayerPanelItem.Spacer -> Icons.Filled.SpaceBar
                    is PlayerPanelItem.Control -> controlIcon(item.id, layoutMode.isFullscreen)
                }
                val canMoveLeft = selection.index > 0 &&
                    !(layoutMode.isFullscreen && selection.bar == PlayerBarSlot.Top && selection.index <= 1)
                val canMoveRight = selection.index < items.lastIndex &&
                    !(layoutMode.isFullscreen && selection.bar == PlayerBarSlot.Top && selection.index == 0)
                val canMoveOtherBar = item !is PlayerPanelItem.Control || item.id != PlayerControlId.Back
                val canRemove = layout.canRemoveBarItem(selection.bar, selection.index, layoutMode.isFullscreen)
                val supportsBg = item is PlayerPanelItem.Control && supportsBackgroundToggle(item.id)

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
                ) {
                    val inlineBgSwitch = maxWidth >= 460.dp
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = itemIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = itemTitle,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (!canRemove) {
                                    Text(
                                        text = stringResource(Res.string.player_custom_required_item),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (supportsBg && inlineBgSwitch) {
                                val control = item as PlayerPanelItem.Control
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                                ) {
                                    Text(
                                        text = stringResource(Res.string.player_custom_show_background),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                    Switch(
                                        checked = control.showBackground,
                                        onCheckedChange = { showBg ->
                                            onUpdateLayout { prefs ->
                                                prefs.updateLayout(layoutMode) {
                                                    it.withBarItemBackground(
                                                        bar = selection.bar,
                                                        index = selection.index,
                                                        showBackground = showBg,
                                                        isFullscreen = layoutMode.isFullscreen,
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                            IconButton(
                                enabled = canMoveLeft,
                                onClick = {
                                    val target = selection.index - 1
                                    onUpdateLayout { prefs ->
                                        prefs.updateLayout(layoutMode) {
                                            it.moveBarItem(selection.bar, selection.index, target, layoutMode.isFullscreen)
                                        }
                                    }
                                    onSelect(PreviewSelection.PanelItem(selection.bar, target))
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(Res.string.player_custom_move_left),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(
                                enabled = canMoveRight,
                                onClick = {
                                    val target = selection.index + 1
                                    onUpdateLayout { prefs ->
                                        prefs.updateLayout(layoutMode) {
                                            it.moveBarItem(selection.bar, selection.index, target, layoutMode.isFullscreen)
                                        }
                                    }
                                    onSelect(PreviewSelection.PanelItem(selection.bar, target))
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = stringResource(Res.string.player_custom_move_right),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            if (canMoveOtherBar) {
                                IconButton(
                                    onClick = {
                                        val otherBar = selection.bar.other()
                                        onUpdateLayout { prefs ->
                                            prefs.updateLayout(layoutMode) {
                                                it.moveBarItemToOtherBar(
                                                    selection.bar,
                                                    selection.index,
                                                    layoutMode.isFullscreen,
                                                )
                                            }
                                        }
                                        onSelect(PreviewSelection.PanelItem(otherBar, layout.itemsFor(otherBar).size))
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SwapVert,
                                        contentDescription = stringResource(
                                            if (selection.bar == PlayerBarSlot.Top) {
                                                Res.string.player_custom_move_to_bottom
                                            } else {
                                                Res.string.player_custom_move_to_top
                                            },
                                        ),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            IconButton(
                                enabled = canRemove,
                                onClick = {
                                    onUpdateLayout { prefs ->
                                        prefs.updateLayout(layoutMode) {
                                            it.removeBarItem(selection.bar, selection.index, layoutMode.isFullscreen)
                                        }
                                    }
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = stringResource(Res.string.player_custom_remove_item),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(Res.string.action_close),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        if (supportsBg && !inlineBgSwitch) {
                            val control = item as PlayerPanelItem.Control
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        onUpdateLayout { prefs ->
                                            prefs.updateLayout(layoutMode) {
                                                it.withBarItemBackground(
                                                    bar = selection.bar,
                                                    index = selection.index,
                                                    showBackground = !control.showBackground,
                                                    isFullscreen = layoutMode.isFullscreen,
                                                )
                                            }
                                        }
                                    },
                            ) {
                                Text(
                                    text = stringResource(Res.string.player_custom_show_background),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = control.showBackground,
                                    onCheckedChange = { showBg ->
                                        onUpdateLayout { prefs ->
                                            prefs.updateLayout(layoutMode) {
                                                it.withBarItemBackground(
                                                    bar = selection.bar,
                                                    index = selection.index,
                                                    showBackground = showBg,
                                                    isFullscreen = layoutMode.isFullscreen,
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            is PreviewSelection.CenterSlot -> {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
                ) {
                    // 5 槽位快捷切换（触控与键鼠预览共享同一套 layout.center，点选任一槽位均可直接切换编辑外左/内左/播放/内右/外右）
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    ) {
                        val slotTabs = listOf(
                            CenterSlotPosition.OuterLeft to stringResource(Res.string.player_custom_slot_outer_left_short),
                            CenterSlotPosition.InnerLeft to stringResource(Res.string.player_custom_slot_inner_left_short),
                            CenterSlotPosition.Center to stringResource(Res.string.player_custom_ctrl_play_pause),
                            CenterSlotPosition.InnerRight to stringResource(Res.string.player_custom_slot_inner_right_short),
                            CenterSlotPosition.OuterRight to stringResource(Res.string.player_custom_slot_outer_right_short),
                        )
                        for ((pos, label) in slotTabs) {
                            FilterChip(
                                selected = selection.position == pos,
                                onClick = { onSelect(PreviewSelection.CenterSlot(pos)) },
                                label = { Text(label, maxLines = 1) },
                            )
                        }
                    }

                    if (selection.position == CenterSlotPosition.Center) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            PlainToggleRow(
                                icon = Icons.Filled.PlayArrow,
                                title = stringResource(Res.string.player_custom_show_center_play),
                                subtitle = stringResource(Res.string.player_custom_show_center_play_subtitle),
                                checked = layout.center.showCenterPlay,
                                onCheckedChange = { show ->
                                    onUpdateLayout { prefs ->
                                        prefs.updateLayout(layoutMode) {
                                            it.withCenterPlay(show, layoutMode.isFullscreen)
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(Res.string.action_close),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    } else {
                        val slot = layout.center.slot(selection.position)
                        val currentSeconds = slot.action.seekSecondsOrNull() ?: layoutPrefs.seekButtonSeconds
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = centerSlotLabel(selection.position),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalButton(
                                onClick = {
                                    onUpdateLayout { prefs ->
                                        prefs.updateLayout(layoutMode) {
                                            it.withSymmetricCenterSlot(selection.position, layoutMode.isFullscreen)
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = 6.dp),
                            ) {
                                Icon(Icons.Filled.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(Spacing.Hair))
                                Text(
                                    text = stringResource(Res.string.player_custom_apply_symmetric),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (slot.action != CenterSlotAction.None) {
                                OutlinedButton(
                                    onClick = {
                                        onUpdateLayout { prefs ->
                                            prefs.updateLayout(layoutMode) {
                                                it.withCenterSlot(
                                                    selection.position,
                                                    slot.copy(action = CenterSlotAction.None),
                                                    layoutMode.isFullscreen,
                                                )
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = stringResource(Res.string.player_custom_clear_slot),
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                    )
                                }
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(Res.string.action_close),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                        ) {
                            val actions = listOf(
                                CenterSlotAction.None to stringResource(Res.string.player_custom_action_none),
                                CenterSlotAction.Previous to stringResource(Res.string.player_custom_action_previous),
                                CenterSlotAction.Next to stringResource(Res.string.player_custom_action_next),
                                CenterSlotAction.SeekBackward(currentSeconds) to stringResource(Res.string.player_custom_action_seek_backward),
                                CenterSlotAction.SeekForward(currentSeconds) to stringResource(Res.string.player_custom_action_seek_forward),
                            )
                            for ((actionOption, label) in actions) {
                                val isCurrent = when {
                                    actionOption is CenterSlotAction.SeekBackward && slot.action is CenterSlotAction.SeekBackward -> true
                                    actionOption is CenterSlotAction.SeekForward && slot.action is CenterSlotAction.SeekForward -> true
                                    else -> slot.action == actionOption
                                }
                                FilterChip(
                                    selected = isCurrent,
                                    onClick = {
                                        onUpdateLayout { prefs ->
                                            prefs.updateLayout(layoutMode) {
                                                it.withCenterSlot(
                                                    selection.position,
                                                    slot.copy(action = actionOption),
                                                    layoutMode.isFullscreen,
                                                )
                                            }
                                        }
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                        ) {
                            Text(
                                text = stringResource(Res.string.player_custom_scale_title),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterVertically),
                            )
                            val scales = listOf(
                                CenterSlotScale.Large to stringResource(Res.string.player_custom_scale_large),
                                CenterSlotScale.Medium to stringResource(Res.string.player_custom_scale_medium),
                                CenterSlotScale.Small to stringResource(Res.string.player_custom_scale_small),
                                CenterSlotScale.ExtraSmall to stringResource(Res.string.player_custom_scale_extra_small),
                            )
                            for ((scaleOption, label) in scales) {
                                FilterChip(
                                    selected = slot.scale == scaleOption,
                                    onClick = {
                                        onUpdateLayout { prefs ->
                                            prefs.updateLayout(layoutMode) {
                                                it.withCenterSlot(
                                                    selection.position,
                                                    slot.copy(scale = scaleOption),
                                                    layoutMode.isFullscreen,
                                                )
                                            }
                                        }
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }

                        val isSeekAction = slot.action is CenterSlotAction.SeekBackward ||
                            slot.action is CenterSlotAction.SeekForward
                        if (isSeekAction) {
                            SeekSecondsEditor(
                                title = stringResource(Res.string.player_custom_seek_seconds),
                                seconds = currentSeconds,
                                onSecondsChange = { newSec ->
                                    val clamped = newSec.coerceIn(MIN_SEEK_SECONDS, MAX_SEEK_SECONDS)
                                    val updatedAction = when (slot.action) {
                                        is CenterSlotAction.SeekBackward -> CenterSlotAction.SeekBackward(clamped)
                                        is CenterSlotAction.SeekForward -> CenterSlotAction.SeekForward(clamped)
                                        else -> slot.action
                                    }
                                    onUpdateLayout { prefs ->
                                        prefs.copy(seekButtonSeconds = clamped)
                                            .updateLayout(layoutMode) {
                                                it.withCenterSlot(
                                                    selection.position,
                                                    slot.copy(action = updatedAction),
                                                    layoutMode.isFullscreen,
                                                )
                                            }
                                    }
                                },
                            )
                        }

                        if (slot.action != CenterSlotAction.None) {
                            ShowBackgroundSwitchRow(
                                checked = slot.showBackground,
                                onCheckedChange = { showBg ->
                                    onUpdateLayout { prefs ->
                                        prefs.updateLayout(layoutMode) {
                                            it.withCenterSlot(
                                                selection.position,
                                                slot.copy(showBackground = showBg),
                                                layoutMode.isFullscreen,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeekSecondsEditor(
    title: String,
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
) {
    var customInput by remember(seconds) { mutableStateOf(seconds.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            for (preset in SEEK_SECONDS_PRESETS) {
                FilterChip(
                    selected = seconds == preset,
                    onClick = { onSecondsChange(preset) },
                    label = {
                        Text(
                            text = stringResource(Res.string.player_custom_seek_seconds_unit, preset),
                            style = MaterialTheme.typography.labelLarge.tabular,
                        )
                    },
                )
            }
        }
        OutlinedTextField(
            value = customInput,
            onValueChange = { text ->
                val digits = text.filter { it.isDigit() }.take(3)
                customInput = digits
                digits.toIntOrNull()?.let { parsed ->
                    if (parsed in MIN_SEEK_SECONDS..MAX_SEEK_SECONDS) {
                        onSecondsChange(parsed)
                    }
                }
            },
            singleLine = true,
            label = { Text(stringResource(Res.string.player_custom_seek_seconds_custom)) },
            textStyle = MaterialTheme.typography.bodyLarge.tabular,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().trackEditableFocus(),
        )
    }
}

@Composable
private fun GestureSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Spacing.Hair),
        )
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                modifier = Modifier.padding(Spacing.Comfortable),
            ) {
                content()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GestureCustomizationCards(
    state: SettingsUiState,
    layoutPrefs: PlayerLayoutPrefs,
    onUpdateLayout: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
    onDoubleTapSeekChange: (Boolean) -> Unit,
    onLongPressSpeedChange: (Boolean) -> Unit,
    onLongPressSpeedDialChange: (Boolean) -> Unit,
    onDefaultHoldSpeedChange: (Float) -> Unit,
    onSwipeSeekChange: (Boolean) -> Unit,
    onSwipeBrightnessVolumeChange: (Boolean) -> Unit,
) {
    val gestures = layoutPrefs.gestures

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Cozy)) {
        // 1. 纵向滑动手势
        GestureSectionCard(title = stringResource(Res.string.player_custom_gesture_vertical_group)) {
            PlainToggleRow(
                icon = Icons.Outlined.SwapVert,
                title = stringResource(Res.string.settings_swipe_brightness_volume),
                subtitle = stringResource(Res.string.settings_swipe_brightness_volume_subtitle),
                checked = state.swipeBrightnessVolume,
                onCheckedChange = onSwipeBrightnessVolumeChange,
            )
            if (state.swipeBrightnessVolume) {
                VerticalSwipeActionSelector(
                    title = stringResource(Res.string.player_custom_gesture_left_vertical),
                    selected = gestures.leftVerticalSwipe,
                    onSelect = { action ->
                        onUpdateLayout { it.updateGestures { g -> g.copy(leftVerticalSwipe = action) } }
                    },
                )
                VerticalSwipeActionSelector(
                    title = stringResource(Res.string.player_custom_gesture_right_vertical),
                    selected = gestures.rightVerticalSwipe,
                    onSelect = { action ->
                        onUpdateLayout { it.updateGestures { g -> g.copy(rightVerticalSwipe = action) } }
                    },
                )
                FractionSliderSection(
                    title = stringResource(Res.string.player_custom_gesture_vertical_zone),
                    value = gestures.verticalZoneFraction,
                    valueRange = MIN_VERTICAL_ZONE_FRACTION..MAX_VERTICAL_ZONE_FRACTION,
                    steps = 5,
                    onValueChange = { fraction ->
                        val snapped = ((fraction * 20f).roundToInt() / 20f)
                            .coerceIn(MIN_VERTICAL_ZONE_FRACTION, MAX_VERTICAL_ZONE_FRACTION)
                        onUpdateLayout { it.updateGestures { g -> g.copy(verticalZoneFraction = snapped) } }
                    },
                )
            }
        }

        // 2. 横向滑动进度
        GestureSectionCard(title = stringResource(Res.string.player_custom_gesture_horizontal_group)) {
            PlainToggleRow(
                icon = Icons.Outlined.SwapHoriz,
                title = stringResource(Res.string.settings_swipe_seek),
                subtitle = stringResource(Res.string.settings_swipe_seek_subtitle),
                checked = state.swipeSeek,
                onCheckedChange = onSwipeSeekChange,
            )
            if (state.swipeSeek) {
                PlainToggleRow(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(Res.string.player_custom_gesture_seek_cancel_zone),
                    subtitle = stringResource(Res.string.player_custom_gesture_seek_cancel_zone_subtitle),
                    checked = gestures.swipeSeekCancelZone,
                    onCheckedChange = { enabled ->
                        onUpdateLayout { it.updateGestures { g -> g.copy(swipeSeekCancelZone = enabled) } }
                    },
                )
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
                    Text(
                        text = stringResource(Res.string.player_custom_gesture_seek_sensitivity),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    ) {
                        for (option in SwipeSeekSensitivity.entries) {
                            FilterChip(
                                selected = gestures.swipeSeekSensitivity == option,
                                onClick = {
                                    onUpdateLayout {
                                        it.updateGestures { g -> g.copy(swipeSeekSensitivity = option) }
                                    }
                                },
                                label = { Text(swipeSeekSensitivityLabel(option)) },
                            )
                        }
                    }
                }
            }
        }

        // 3. 双击手势
        GestureSectionCard(title = stringResource(Res.string.player_custom_gesture_double_tap_group)) {
            PlainToggleRow(
                icon = Icons.Outlined.FastForward,
                title = stringResource(Res.string.settings_double_tap_seek),
                subtitle = stringResource(Res.string.settings_double_tap_seek_subtitle),
                checked = state.doubleTapSeek,
                onCheckedChange = onDoubleTapSeekChange,
            )
            if (state.doubleTapSeek) {
                PlainToggleRow(
                    icon = Icons.Outlined.Timer,
                    title = stringResource(Res.string.player_custom_sync_double_tap),
                    subtitle = stringResource(Res.string.player_custom_sync_double_tap_subtitle),
                    checked = layoutPrefs.syncDoubleTapWithSeekButton,
                    onCheckedChange = { sync ->
                        onUpdateLayout { it.copy(syncDoubleTapWithSeekButton = sync) }
                    },
                )
                FractionSliderSection(
                    title = stringResource(Res.string.player_custom_gesture_double_tap_zone),
                    value = gestures.doubleTapZoneFraction,
                    valueRange = MIN_DOUBLE_TAP_ZONE_FRACTION..MAX_DOUBLE_TAP_ZONE_FRACTION,
                    steps = 4,
                    onValueChange = { fraction ->
                        val snapped = ((fraction * 20f).roundToInt() / 20f)
                            .coerceIn(MIN_DOUBLE_TAP_ZONE_FRACTION, MAX_DOUBLE_TAP_ZONE_FRACTION)
                        onUpdateLayout { it.updateGestures { g -> g.copy(doubleTapZoneFraction = snapped) } }
                    },
                )
                if (!layoutPrefs.syncDoubleTapWithSeekButton) {
                    SeekSecondsEditor(
                        title = stringResource(Res.string.player_custom_double_tap_seconds),
                        seconds = layoutPrefs.doubleTapSeekSeconds,
                        onSecondsChange = { sec ->
                            onUpdateLayout { it.copy(doubleTapSeekSeconds = sec) }
                        },
                    )
                }
            }
        }

        // 4. 长按倍速与选档
        GestureSectionCard(title = stringResource(Res.string.player_custom_gesture_speed_group)) {
            PlainToggleRow(
                icon = Icons.Outlined.Speed,
                title = stringResource(Res.string.settings_long_press_speed),
                subtitle = stringResource(Res.string.settings_long_press_speed_subtitle),
                checked = state.longPressSpeed,
                onCheckedChange = onLongPressSpeedChange,
            )
            if (state.longPressSpeed) {
                PlainToggleRow(
                    icon = Icons.Outlined.Tune,
                    title = stringResource(Res.string.settings_long_press_speed_dial),
                    subtitle = stringResource(Res.string.settings_long_press_speed_dial_subtitle),
                    checked = state.longPressSpeedDial,
                    onCheckedChange = onLongPressSpeedDialChange,
                )
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
                    Text(
                        text = stringResource(Res.string.settings_default_hold_speed),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    ) {
                        for (option in SettingsStore.HOLD_SPEED_OPTIONS) {
                            FilterChip(
                                selected = state.defaultHoldSpeed == option,
                                onClick = { onDefaultHoldSpeedChange(option) },
                                label = {
                                    Text(
                                        text = formatSpeed(option),
                                        style = MaterialTheme.typography.labelLarge.tabular,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VerticalSwipeActionSelector(
    title: String,
    selected: VerticalSwipeAction,
    onSelect: (VerticalSwipeAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            for (action in VerticalSwipeAction.entries) {
                FilterChip(
                    selected = selected == action,
                    onClick = { onSelect(action) },
                    label = { Text(verticalSwipeActionLabel(action)) },
                )
            }
        }
    }
}

@Composable
private fun FractionSliderSection(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    val percent = (value * 100f).roundToInt()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${percent}%",
                style = MaterialTheme.typography.labelLarge.tabular,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun verticalSwipeActionLabel(action: VerticalSwipeAction): String = stringResource(
    when (action) {
        VerticalSwipeAction.Brightness -> Res.string.player_custom_gesture_action_brightness
        VerticalSwipeAction.Volume -> Res.string.player_custom_gesture_action_volume
        VerticalSwipeAction.Episode -> Res.string.player_custom_gesture_action_episode
        VerticalSwipeAction.None -> Res.string.player_custom_gesture_action_none
    },
)

@Composable
private fun swipeSeekSensitivityLabel(sensitivity: SwipeSeekSensitivity): String = stringResource(
    when (sensitivity) {
        SwipeSeekSensitivity.Low -> Res.string.player_custom_gesture_sensitivity_low
        SwipeSeekSensitivity.Standard -> Res.string.player_custom_gesture_sensitivity_standard
        SwipeSeekSensitivity.High -> Res.string.player_custom_gesture_sensitivity_high
    },
)

@Composable
private fun centerSlotLabel(position: CenterSlotPosition): String = stringResource(
    when (position) {
        CenterSlotPosition.OuterLeft -> Res.string.player_custom_slot_outer_left
        CenterSlotPosition.InnerLeft -> Res.string.player_custom_slot_inner_left
        CenterSlotPosition.Center -> Res.string.player_custom_slot_center
        CenterSlotPosition.InnerRight -> Res.string.player_custom_slot_inner_right
        CenterSlotPosition.OuterRight -> Res.string.player_custom_slot_outer_right
    },
)

@Composable
private fun controlLabel(id: PlayerControlId): String = stringResource(
    when (id) {
        PlayerControlId.Back -> Res.string.player_custom_ctrl_back
        PlayerControlId.Title -> Res.string.player_custom_ctrl_title
        PlayerControlId.SystemStatus -> Res.string.player_custom_ctrl_system_status
        PlayerControlId.PlayPause -> Res.string.player_custom_ctrl_play_pause
        PlayerControlId.Previous -> Res.string.player_custom_ctrl_previous
        PlayerControlId.Next -> Res.string.player_custom_ctrl_next
        PlayerControlId.SeekBackward -> Res.string.player_custom_ctrl_seek_backward
        PlayerControlId.SeekForward -> Res.string.player_custom_ctrl_seek_forward
        PlayerControlId.TimeLabel -> Res.string.player_custom_ctrl_time
        PlayerControlId.Volume -> Res.string.player_custom_ctrl_volume
        PlayerControlId.DanmakuToggle -> Res.string.player_custom_ctrl_danmaku_toggle
        PlayerControlId.DanmakuInput -> Res.string.player_custom_ctrl_danmaku_input
        PlayerControlId.Speed -> Res.string.player_custom_ctrl_speed
        PlayerControlId.Quality -> Res.string.player_custom_ctrl_quality
        PlayerControlId.Audio -> Res.string.player_custom_ctrl_audio
        PlayerControlId.Subtitle -> Res.string.player_custom_ctrl_subtitle
        PlayerControlId.SleepTimer -> Res.string.player_custom_ctrl_sleep_timer
        PlayerControlId.Pip -> Res.string.player_custom_ctrl_pip
        PlayerControlId.Listen -> Res.string.player_custom_ctrl_listen
        PlayerControlId.Share -> Res.string.player_custom_ctrl_share
        PlayerControlId.Settings -> Res.string.player_custom_ctrl_settings
        PlayerControlId.Lock -> Res.string.player_custom_ctrl_lock
        PlayerControlId.Fullscreen -> Res.string.player_custom_ctrl_fullscreen
    },
)

private fun controlIcon(id: PlayerControlId, isFullscreen: Boolean): ImageVector = when (id) {
    PlayerControlId.Back -> Icons.AutoMirrored.Filled.ArrowBack
    PlayerControlId.Title -> Icons.Filled.Edit
    PlayerControlId.SystemStatus -> Icons.Outlined.Timer
    PlayerControlId.PlayPause -> Icons.Filled.PlayArrow
    PlayerControlId.Previous -> Icons.Filled.SkipPrevious
    PlayerControlId.Next -> Icons.Filled.SkipNext
    PlayerControlId.SeekBackward -> Icons.Filled.FastRewind
    PlayerControlId.SeekForward -> Icons.Filled.FastForward
    PlayerControlId.TimeLabel -> Icons.Outlined.Timer
    PlayerControlId.Volume -> Icons.AutoMirrored.Filled.VolumeUp
    PlayerControlId.DanmakuToggle -> BilbyIcons.Danmaku
    PlayerControlId.DanmakuInput -> Icons.Filled.Edit
    PlayerControlId.Speed -> Icons.Filled.Speed
    PlayerControlId.Quality -> Icons.Filled.HighQuality
    PlayerControlId.Audio -> Icons.Filled.GraphicEq
    PlayerControlId.Subtitle -> Icons.Filled.Subtitles
    PlayerControlId.SleepTimer -> Icons.Filled.Bedtime
    PlayerControlId.Pip -> Icons.Filled.PictureInPictureAlt
    PlayerControlId.Listen -> Icons.Filled.Headphones
    PlayerControlId.Share -> Icons.Filled.Share
    PlayerControlId.Settings -> Icons.Filled.Tune
    PlayerControlId.Lock -> Icons.Filled.LockOpen
    PlayerControlId.Fullscreen -> if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen
}
