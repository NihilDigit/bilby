package dev.bilby.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.data.CenterSlotAction
import dev.bilby.data.CenterSlotConfig
import dev.bilby.data.CenterSlotPosition
import dev.bilby.data.CenterSlotScale
import dev.bilby.data.ListenCoverStyle
import dev.bilby.data.ListenLayoutConfig
import dev.bilby.data.ListenQuickChip
import dev.bilby.data.ListenSideAction
import dev.bilby.data.MAX_SEEK_SECONDS
import dev.bilby.data.MIN_SEEK_SECONDS
import dev.bilby.data.PlayerBarSlot
import dev.bilby.data.PlayerControlId
import dev.bilby.data.PlayerFormFactor
import dev.bilby.data.PlayerFormFactorLayout
import dev.bilby.data.PlayerLayoutPrefs
import dev.bilby.data.PlayerLayoutPreset
import dev.bilby.data.PlayerPanelItem
import dev.bilby.data.SEEK_SECONDS_PRESETS
import dev.bilby.data.SettingsStore
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.components.BilbyIcons
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.PaneSheet
import dev.bilby.ui.components.trackEditableFocus
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.player.BatteryStatus
import dev.bilby.ui.player.CenterControlCluster
import dev.bilby.ui.player.ControlScrimBottom
import dev.bilby.ui.player.NetworkKind
import dev.bilby.ui.player.PlayerStatusCluster
import dev.bilby.ui.player.PlayerSystemStatus
import dev.bilby.ui.player.formatSpeed
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.PlayerTheme
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular

private enum class EditorSurfaceMode {
    Embedded,
    Fullscreen,
    Listen,
}

private enum class ListenEditorSection {
    Cover,
    SideAction,
    QuickChips,
}

private sealed interface PreviewSelection {
    data class CenterSlot(val position: CenterSlotPosition) : PreviewSelection
    data class PanelItem(val bar: PlayerBarSlot, val index: Int) : PreviewSelection
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
    val pointerSource = LocalPointerSource.current
    var editingTouch by rememberSaveable { mutableStateOf(pointerSource.isTouchLike) }
    var surfaceMode by rememberSaveable { mutableStateOf(EditorSurfaceMode.Embedded) }
    val isListenMode = surfaceMode == EditorSurfaceMode.Listen
    val editingFullscreen = surfaceMode == EditorSurfaceMode.Fullscreen
    val formFactor = PlayerFormFactor.of(isTouch = editingTouch, isFullscreen = editingFullscreen)
    val layoutPrefs = state.playerLayout
    val activeLayout = layoutPrefs.layoutFor(formFactor)

    var selection by remember {
        mutableStateOf<PreviewSelection>(PreviewSelection.CenterSlot(CenterSlotPosition.InnerLeft))
    }
    var listenSection by rememberSaveable { mutableStateOf(ListenEditorSection.Cover) }
    var addControlTargetBar by remember { mutableStateOf<PlayerBarSlot?>(null) }

    // 切换形态或列表长度变化时修正越界选中索引。
    LaunchedEffect(formFactor, activeLayout.topBar.size, activeLayout.bottomBar.size) {
        val current = selection
        if (current is PreviewSelection.PanelItem) {
            val size = activeLayout.itemsFor(current.bar).size
            if (current.index !in 0 until size) {
                selection = if (size > 0) {
                    PreviewSelection.PanelItem(current.bar, (size - 1).coerceAtLeast(0))
                } else {
                    PreviewSelection.CenterSlot(CenterSlotPosition.Center)
                }
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val windowSize = rememberBilbyWindowSize()
    val useTwoPane = windowSize.isAtLeast(BilbyWindowSize.Expanded)

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

        val previewPane: @Composable (Modifier) -> Unit = { mod ->
            Column(
                modifier = mod,
                verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            ) {
                FormFactorModeBar(
                    isTouch = editingTouch,
                    onTouchChange = { editingTouch = it },
                    surfaceMode = surfaceMode,
                    onSurfaceModeChange = { surfaceMode = it },
                )
                if (isListenMode) {
                    ListenPreviewCanvas(
                        config = layoutPrefs.listen,
                        seekButtonSeconds = layoutPrefs.seekButtonSeconds,
                        selectedSection = listenSection,
                        onSelectSection = { listenSection = it },
                    )
                    Text(
                        text = stringResource(Res.string.player_custom_listen_preview_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    PlayerPreviewCanvas(
                        layout = activeLayout,
                        isFullscreen = editingFullscreen,
                        seekButtonSeconds = layoutPrefs.seekButtonSeconds,
                        selection = selection,
                        onSelect = { selection = it },
                    )
                    Text(
                        text = stringResource(Res.string.player_custom_preview_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PresetChipsRow(
                    current = layoutPrefs,
                    onApplyPreset = { preset -> onUpdateLayout { PlayerLayoutPrefs.preset(preset) } },
                )
            }
        }

        val editorPane: @Composable (Modifier) -> Unit = { mod ->
            Column(
                modifier = mod,
                verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            ) {
                if (isListenMode) {
                    ListenCustomizationCards(
                        config = layoutPrefs.listen,
                        seekButtonSeconds = layoutPrefs.seekButtonSeconds,
                        selectedSection = listenSection,
                        onSelectSection = { listenSection = it },
                        onUpdateLayout = onUpdateLayout,
                    )
                } else {
                    SelectionInspectorCard(
                        formFactor = formFactor,
                        layout = activeLayout,
                        layoutPrefs = layoutPrefs,
                        selection = selection,
                        onSelect = { selection = it },
                        onUpdateLayout = onUpdateLayout,
                    )

                    PanelQuickAddCard(
                        isFullscreen = editingFullscreen,
                        onAddControl = { bar -> addControlTargetBar = bar },
                        onAddSpacer = { bar ->
                            val nextIdx = activeLayout.itemsFor(bar).size
                            onUpdateLayout { prefs ->
                                prefs.updateLayout(formFactor) {
                                    it.addToBar(bar, PlayerPanelItem.Spacer, formFactor.isFullscreen)
                                }
                            }
                            selection = PreviewSelection.PanelItem(bar, nextIdx)
                        },
                    )

                    GesturesAndSpeedSection(
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

        if (useTwoPane) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
                modifier = Modifier
                    .fillMaxSize()
                    .padScaffoldExceptBottom(insets)
                    .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
            ) {
                previewPane(
                    Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = Spacing.Spacious + navigationBarsBottom()),
                )
                editorPane(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = Spacing.Spacious + navigationBarsBottom()),
                )
            }
        } else {
            AdaptiveContent(
                modifier = Modifier.fillMaxSize().padScaffoldExceptBottom(insets),
                maxWidth = Breakpoints.ReadableWidth,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 窄屏下 16:9 预览画布与形态切换固定在顶部，下方编辑器独立滚动。
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
                    ) {
                        FormFactorModeBar(
                            isTouch = editingTouch,
                            onTouchChange = { editingTouch = it },
                            surfaceMode = surfaceMode,
                            onSurfaceModeChange = { surfaceMode = it },
                        )
                        if (isListenMode) {
                            ListenPreviewCanvas(
                                config = layoutPrefs.listen,
                                seekButtonSeconds = layoutPrefs.seekButtonSeconds,
                                selectedSection = listenSection,
                                onSelectSection = { listenSection = it },
                            )
                        } else {
                            PlayerPreviewCanvas(
                                layout = activeLayout,
                                isFullscreen = editingFullscreen,
                                seekButtonSeconds = layoutPrefs.seekButtonSeconds,
                                selection = selection,
                                onSelect = { selection = it },
                            )
                        }
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.Comfortable)
                            .padding(top = Spacing.Tight, bottom = Spacing.Spacious + navigationBarsBottom()),
                    ) {
                        PresetChipsRow(
                            current = layoutPrefs,
                            onApplyPreset = { preset -> onUpdateLayout { PlayerLayoutPrefs.preset(preset) } },
                        )
                        editorPane(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    val targetBar = addControlTargetBar
    if (targetBar != null) {
        PaneSheet(
            onDismissRequest = { addControlTargetBar = null },
            title = stringResource(
                if (targetBar == PlayerBarSlot.Top) Res.string.player_custom_top_bar else Res.string.player_custom_bottom_bar,
            ),
        ) {
            AddControlPickerContent(
                targetBar = targetBar,
                isFullscreen = editingFullscreen,
                layout = activeLayout,
                onPick = { controlId ->
                    onUpdateLayout { prefs ->
                        prefs.updateLayout(formFactor) {
                            it.addToBar(targetBar, PlayerPanelItem.Control(controlId), formFactor.isFullscreen)
                        }
                    }
                    addControlTargetBar = null
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FormFactorModeBar(
    isTouch: Boolean,
    onTouchChange: (Boolean) -> Unit,
    surfaceMode: EditorSurfaceMode,
    onSurfaceModeChange: (EditorSurfaceMode) -> Unit,
) {
    val isListen = surfaceMode == EditorSurfaceMode.Listen
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (!isListen) {
            BinaryTogglePair(
                leftLabel = stringResource(Res.string.player_custom_input_touch),
                rightLabel = stringResource(Res.string.player_custom_input_pointer),
                leftSelected = isTouch,
                onSelectLeft = { onTouchChange(true) },
                onSelectRight = { onTouchChange(false) },
                modifier = Modifier.weight(0.9f),
            )
        }
        SurfaceModeToggleGroup(
            surfaceMode = surfaceMode,
            onSurfaceModeChange = onSurfaceModeChange,
            modifier = Modifier.weight(if (isListen) 1f else 1.45f),
        )
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
    )
    ButtonGroup(
        overflowIndicator = {},
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = modifier,
    ) {
        options.forEachIndexed { index, (option, label) ->
            val shapes = when (index) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            customItem(
                buttonGroupContent = {
                    ToggleButton(
                        checked = surfaceMode == option,
                        onCheckedChange = { if (it) onSurfaceModeChange(option) },
                        shapes = shapes,
                        colors = colors,
                        contentPadding = PaddingValues(horizontal = Spacing.Tight, vertical = Spacing.Tight),
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

@Composable
private fun ListenPreviewCanvas(
    config: ListenLayoutConfig,
    seekButtonSeconds: Int,
    selectedSection: ListenEditorSection,
    onSelectSection: (ListenEditorSection) -> Unit,
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
                        modifier = Modifier.size(66.dp),
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
                            .width(96.dp)
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

        // 底部悬浮控制胶囊预览
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
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "1.0x",
                            style = MaterialTheme.typography.labelSmall.tabular,
                            color = onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }

                val controlsSelected = selectedSection == ListenEditorSection.SideAction
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    modifier = Modifier
                        .clip(CircleShape)
                        .then(if (controlsSelected) Modifier.border(2.dp, highlight, CircleShape) else Modifier)
                        .clickable { onSelectSection(ListenEditorSection.SideAction) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 3.dp, vertical = 2.dp),
                ) {
                    when (config.sideAction) {
                        ListenSideAction.Episode -> {
                            ListenPreviewMiniCircleButton(surfaceContainerHighest) {
                                Icon(Icons.Filled.SkipPrevious, null, tint = onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                        }
                        ListenSideAction.SeekDelta -> {
                            ListenPreviewMiniSeekPill(surfaceContainerHighest, Icons.Filled.FastRewind, "${seekButtonSeconds}s", onSurfaceVariant)
                        }
                        ListenSideAction.None -> Unit
                    }

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

                    when (config.sideAction) {
                        ListenSideAction.Episode -> {
                            ListenPreviewMiniCircleButton(surfaceContainerHighest) {
                                Icon(Icons.Filled.SkipNext, null, tint = onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                        }
                        ListenSideAction.SeekDelta -> {
                            ListenPreviewMiniSeekPill(surfaceContainerHighest, Icons.Filled.FastForward, "${seekButtonSeconds}s", onSurfaceVariant)
                        }
                        ListenSideAction.None -> Unit
                    }
                }

                if (showSleep) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(surfaceContainerHighest)
                            .then(chipHighlightBorder)
                            .clickable { onSelectSection(ListenEditorSection.QuickChips) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(horizontal = 8.dp, vertical = 5.dp),
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
private fun ListenPreviewMiniCircleButton(
    containerColor: Color,
    content: @Composable () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(containerColor),
    ) {
        content()
    }
}

@Composable
private fun ListenPreviewMiniSeekPill(
    containerColor: Color,
    icon: ImageVector,
    label: String,
    tint: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(containerColor)
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        Text(label, style = MaterialTheme.typography.labelSmall.tabular, color = tint, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListenCustomizationCards(
    config: ListenLayoutConfig,
    seekButtonSeconds: Int,
    selectedSection: ListenEditorSection,
    onSelectSection: (ListenEditorSection) -> Unit,
    onUpdateLayout: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    // 1. 封面样式卡片
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

    // 2. 播放键两侧按钮卡片
    ListenEditorCard(
        title = stringResource(Res.string.player_custom_listen_side_title),
        selected = selectedSection == ListenEditorSection.SideAction,
        onClick = { onSelectSection(ListenEditorSection.SideAction) },
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            val sideOptions = listOf(
                ListenSideAction.Episode to stringResource(Res.string.player_custom_listen_side_episode),
                ListenSideAction.SeekDelta to stringResource(Res.string.player_custom_listen_side_seek, seekButtonSeconds),
                ListenSideAction.None to stringResource(Res.string.player_custom_listen_side_none),
            )
            for ((action, label) in sideOptions) {
                FilterChip(
                    selected = config.sideAction == action,
                    onClick = {
                        onSelectSection(ListenEditorSection.SideAction)
                        onUpdateLayout { prefs -> prefs.updateListen { it.copy(sideAction = action) } }
                    },
                    label = { Text(label) },
                )
            }
        }
        if (config.sideAction == ListenSideAction.SeekDelta) {
            SeekSecondsEditor(
                title = stringResource(Res.string.player_custom_seek_seconds),
                seconds = seekButtonSeconds,
                onSecondsChange = { sec ->
                    onUpdateLayout { prefs -> prefs.copy(seekButtonSeconds = sec) }
                },
            )
        }
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BinaryTogglePair(
    leftLabel: String,
    rightLabel: String,
    leftSelected: Boolean,
    onSelectLeft: () -> Unit,
    onSelectRight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    ButtonGroup(
        overflowIndicator = {},
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = modifier,
    ) {
        customItem(
            buttonGroupContent = {
                ToggleButton(
                    checked = leftSelected,
                    onCheckedChange = { if (it) onSelectLeft() },
                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                    colors = colors,
                    contentPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                ) {
                    Text(leftLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            menuContent = {},
        )
        customItem(
            buttonGroupContent = {
                ToggleButton(
                    checked = !leftSelected,
                    onCheckedChange = { if (it) onSelectRight() },
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    colors = colors,
                    contentPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                ) {
                    Text(rightLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            menuContent = {},
        )
    }
}

@Composable
private fun PresetChipsRow(
    current: PlayerLayoutPrefs,
    onApplyPreset: (PlayerLayoutPreset) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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

@Composable
private fun PlayerPreviewCanvas(
    layout: PlayerFormFactorLayout,
    isFullscreen: Boolean,
    seekButtonSeconds: Int,
    selection: PreviewSelection,
    onSelect: (PreviewSelection) -> Unit,
) {
    val sampleStatus = remember {
        PlayerSystemStatus(
            timeText = "21:30",
            battery = BatteryStatus(percent = 86, isCharging = false),
            network = NetworkKind.Wifi,
        )
    }
    PlayerTheme {
        Box(
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
            // 顶栏预览
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(ControlScrimBottom, Color.Transparent)))
                    .padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
            ) {
                layout.topBar.forEachIndexed { index, item ->
                    val isSelected = selection == PreviewSelection.PanelItem(PlayerBarSlot.Top, index)
                    PreviewPanelItemChip(
                        item = item,
                        isFullscreen = isFullscreen,
                        seekButtonSeconds = seekButtonSeconds,
                        sampleStatus = sampleStatus,
                        selected = isSelected,
                        onClick = { onSelect(PreviewSelection.PanelItem(PlayerBarSlot.Top, index)) },
                        modifier = if (item == PlayerPanelItem.Spacer) Modifier.weight(1f) else Modifier,
                    )
                }
            }

            // 中央控件簇预览（含虚线圆框占位）
            CenterControlCluster(
                config = layout.center,
                isPlaying = false,
                loading = false,
                large = isFullscreen,
                showSideButtons = true,
                canPrevious = true,
                canNext = true,
                allowSeek = true,
                onTogglePlayPause = {},
                onPrevious = {},
                onNext = {},
                onSeekDeltaMillis = {},
                previewMode = true,
                selectedSlot = (selection as? PreviewSelection.CenterSlot)?.position,
                onSelectSlot = { onSelect(PreviewSelection.CenterSlot(it)) },
                scaleMultiplier = 0.72f,
                modifier = Modifier.align(Alignment.Center),
            )

            // 底栏预览
            Column(
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, ControlScrimBottom)))
                    .padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
            ) {
                if (isFullscreen) {
                    PreviewSeekTrack(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Hair))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    layout.bottomBar.forEachIndexed { index, item ->
                        val isSelected = selection == PreviewSelection.PanelItem(PlayerBarSlot.Bottom, index)
                        PreviewPanelItemChip(
                            item = item,
                            isFullscreen = isFullscreen,
                            seekButtonSeconds = seekButtonSeconds,
                            sampleStatus = sampleStatus,
                            selected = isSelected,
                            onClick = { onSelect(PreviewSelection.PanelItem(PlayerBarSlot.Bottom, index)) },
                            modifier = if (item == PlayerPanelItem.Spacer) Modifier.weight(1f) else Modifier,
                        )
                    }
                }
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
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlight = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(8.dp)
    if (item == PlayerPanelItem.Spacer) {
        val strokeColor = if (selected) highlight else FixedColors.OnMedia.copy(alpha = 0.35f)
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .height(26.dp)
                .widthIn(min = 24.dp)
                .clip(shape)
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = (if (selected) 2.dp else 1.dp).toPx()
                drawRoundRect(
                    color = strokeColor,
                    topLeft = Offset(strokePx / 2f, strokePx / 2f),
                    size = Size(size.width - strokePx, size.height - strokePx),
                    cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                    style = Stroke(
                        width = strokePx,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                    ),
                )
            }
            Text(
                text = stringResource(Res.string.player_custom_spacer_short),
                style = MaterialTheme.typography.labelSmall,
                color = strokeColor,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
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
            .padding(horizontal = 3.dp, vertical = 2.dp),
    ) {
        when (control.id) {
            PlayerControlId.Title -> Text(
                text = stringResource(Res.string.player_custom_preview_title),
                style = MaterialTheme.typography.labelMedium,
                color = FixedColors.OnMedia,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp),
            )
            PlayerControlId.SystemStatus -> PlayerStatusCluster(status = sampleStatus)
            PlayerControlId.TimeLabel -> Text(
                text = "01:23 / 12:34",
                style = MaterialTheme.typography.labelSmall.tabular,
                color = FixedColors.OnMedia,
                maxLines = 1,
            )
            PlayerControlId.Speed -> PreviewTextPill("1.0x")
            PlayerControlId.Quality -> PreviewTextPill("1080P")
            PlayerControlId.Audio -> PreviewTextPill("Hi-Res")
            PlayerControlId.SeekBackward -> PreviewIconWithBadge(Icons.Filled.FastRewind, "${seekButtonSeconds}s")
            PlayerControlId.SeekForward -> PreviewIconWithBadge(Icons.Filled.FastForward, "${seekButtonSeconds}s")
            else -> Icon(
                imageVector = controlIcon(control.id, isFullscreen),
                contentDescription = controlLabel(control.id),
                tint = FixedColors.OnMedia,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PreviewTextPill(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.tabular,
        color = FixedColors.OnMedia,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(FixedColors.ScrimOnMedia)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun PreviewIconWithBadge(icon: ImageVector, badge: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, contentDescription = null, tint = FixedColors.OnMedia, modifier = Modifier.size(15.dp))
        Text(badge, style = MaterialTheme.typography.labelSmall.tabular, color = FixedColors.OnMedia, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionInspectorCard(
    formFactor: PlayerFormFactor,
    layout: PlayerFormFactorLayout,
    layoutPrefs: PlayerLayoutPrefs,
    selection: PreviewSelection,
    onSelect: (PreviewSelection) -> Unit,
    onUpdateLayout: ((PlayerLayoutPrefs) -> PlayerLayoutPrefs) -> Unit,
) {
    val selectedTitle = when (selection) {
        is PreviewSelection.CenterSlot -> centerSlotLabel(selection.position)
        is PreviewSelection.PanelItem -> {
            val item = layout.itemsFor(selection.bar).getOrNull(selection.index)
            when (item) {
                null -> ""
                PlayerPanelItem.Spacer -> stringResource(Res.string.player_custom_spacer)
                is PlayerPanelItem.Control -> controlLabel(item.id)
            }
        }
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            modifier = Modifier.padding(Spacing.Comfortable),
        ) {
            Text(
                text = stringResource(Res.string.player_custom_selected_item, selectedTitle),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            when (selection) {
                is PreviewSelection.CenterSlot -> {
                    if (selection.position == CenterSlotPosition.Center) {
                        SettingsGroup {
                            row { pos ->
                                ToggleSettingRow(
                                    position = pos,
                                    icon = Icons.Filled.PlayArrow,
                                    title = stringResource(Res.string.player_custom_show_center_play),
                                    subtitle = stringResource(Res.string.player_custom_show_center_play_subtitle),
                                    checked = layout.center.showCenterPlay,
                                    onCheckedChange = { show ->
                                        onUpdateLayout { prefs ->
                                            prefs.updateLayout(formFactor) {
                                                it.withCenterPlay(show, formFactor.isFullscreen)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    } else {
                        val slot = layout.center.slot(selection.position)
                        val currentSeconds = slot.action.seekSecondsOrNull() ?: layoutPrefs.seekButtonSeconds

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
                                        onUpdateLayout { prefs ->
                                            prefs.updateLayout(formFactor) {
                                                it.withCenterSlot(
                                                    selection.position,
                                                    slot.copy(action = actionOption),
                                                    formFactor.isFullscreen,
                                                )
                                            }
                                        }
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }

                        // 快进/快退秒数选择（快捷档 + 自定义整数 1..300s）
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
                                            .updateLayout(formFactor) {
                                                it.withCenterSlot(
                                                    selection.position,
                                                    slot.copy(action = updatedAction),
                                                    formFactor.isFullscreen,
                                                )
                                            }
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
                                            onUpdateLayout { prefs ->
                                                prefs.updateLayout(formFactor) {
                                                    it.withCenterSlot(
                                                        selection.position,
                                                        slot.copy(scale = scaleOption),
                                                        formFactor.isFullscreen,
                                                    )
                                                }
                                            }
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }

                        // 对称应用 + 清空
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    onUpdateLayout { prefs ->
                                        prefs.updateLayout(formFactor) {
                                            it.withSymmetricCenterSlot(selection.position, formFactor.isFullscreen)
                                        }
                                    }
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
                                        onUpdateLayout { prefs ->
                                            prefs.updateLayout(formFactor) {
                                                it.withCenterSlot(
                                                    selection.position,
                                                    slot.copy(action = CenterSlotAction.None),
                                                    formFactor.isFullscreen,
                                                )
                                            }
                                        }
                                    },
                                ) {
                                    Text(stringResource(Res.string.player_custom_clear_slot))
                                }
                            }
                        }
                    }
                }

                is PreviewSelection.PanelItem -> {
                    val items = layout.itemsFor(selection.bar)
                    val item = items.getOrNull(selection.index)
                    if (item != null) {
                        val canMoveLeft = selection.index > 0 &&
                            !(formFactor.isFullscreen && selection.bar == PlayerBarSlot.Top && selection.index <= 1)
                        val canMoveRight = selection.index < items.lastIndex &&
                            !(formFactor.isFullscreen && selection.bar == PlayerBarSlot.Top && selection.index == 0)
                        val canMoveOtherBar = item !is PlayerPanelItem.Control || item.id != PlayerControlId.Back
                        val canRemove = layout.canRemoveBarItem(selection.bar, selection.index, formFactor.isFullscreen)

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                        ) {
                            FilledTonalButton(
                                enabled = canMoveLeft,
                                onClick = {
                                    val target = selection.index - 1
                                    onUpdateLayout { prefs ->
                                        prefs.updateLayout(formFactor) {
                                            it.moveBarItem(selection.bar, selection.index, target, formFactor.isFullscreen)
                                        }
                                    }
                                    onSelect(PreviewSelection.PanelItem(selection.bar, target))
                                },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.Hair))
                                Text(stringResource(Res.string.player_custom_move_left))
                            }
                            FilledTonalButton(
                                enabled = canMoveRight,
                                onClick = {
                                    val target = selection.index + 1
                                    onUpdateLayout { prefs ->
                                        prefs.updateLayout(formFactor) {
                                            it.moveBarItem(selection.bar, selection.index, target, formFactor.isFullscreen)
                                        }
                                    }
                                    onSelect(PreviewSelection.PanelItem(selection.bar, target))
                                },
                            ) {
                                Text(stringResource(Res.string.player_custom_move_right))
                                Spacer(Modifier.width(Spacing.Hair))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                            }
                            if (canMoveOtherBar) {
                                OutlinedButton(
                                    onClick = {
                                        val otherBar = selection.bar.other()
                                        onUpdateLayout { prefs ->
                                            prefs.updateLayout(formFactor) {
                                                it.moveBarItemToOtherBar(selection.bar, selection.index, formFactor.isFullscreen)
                                            }
                                        }
                                        onSelect(PreviewSelection.PanelItem(otherBar, layout.itemsFor(otherBar).size))
                                    },
                                ) {
                                    Icon(Icons.Filled.SwapVert, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(Spacing.Hair))
                                    Text(
                                        stringResource(
                                            if (selection.bar == PlayerBarSlot.Top) {
                                                Res.string.player_custom_move_to_bottom
                                            } else {
                                                Res.string.player_custom_move_to_top
                                            },
                                        ),
                                    )
                                }
                            }
                            OutlinedButton(
                                enabled = canRemove,
                                onClick = {
                                    onUpdateLayout { prefs ->
                                        prefs.updateLayout(formFactor) {
                                            it.removeBarItem(selection.bar, selection.index, formFactor.isFullscreen)
                                        }
                                    }
                                },
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.Hair))
                                Text(stringResource(Res.string.player_custom_remove_item))
                            }
                        }
                        if (!canRemove) {
                            Text(
                                text = stringResource(Res.string.player_custom_required_item),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun PanelQuickAddCard(
    isFullscreen: Boolean,
    onAddControl: (PlayerBarSlot) -> Unit,
    onAddSpacer: (PlayerBarSlot) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            modifier = Modifier.padding(Spacing.Comfortable),
        ) {
            if (isFullscreen) {
                PanelBarActionRow(
                    title = stringResource(Res.string.player_custom_top_bar),
                    onAddControl = { onAddControl(PlayerBarSlot.Top) },
                    onAddSpacer = { onAddSpacer(PlayerBarSlot.Top) },
                )
            }
            PanelBarActionRow(
                title = stringResource(Res.string.player_custom_bottom_bar),
                onAddControl = { onAddControl(PlayerBarSlot.Bottom) },
                onAddSpacer = { onAddSpacer(PlayerBarSlot.Bottom) },
            )
        }
    }
}

@Composable
private fun PanelBarActionRow(
    title: String,
    onAddControl: () -> Unit,
    onAddSpacer: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = onAddControl) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.Hair))
            Text(stringResource(Res.string.player_custom_add_control))
        }
        OutlinedButton(onClick = onAddSpacer) {
            Icon(Icons.Filled.SpaceBar, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.Hair))
            Text(stringResource(Res.string.player_custom_add_spacer))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddControlPickerContent(
    targetBar: PlayerBarSlot,
    isFullscreen: Boolean,
    layout: PlayerFormFactorLayout,
    onPick: (PlayerControlId) -> Unit,
) {
    val candidates = remember(targetBar, isFullscreen) {
        PlayerControlId.entries.filter { id ->
            when (id) {
                PlayerControlId.Back -> false
                PlayerControlId.Title, PlayerControlId.SystemStatus, PlayerControlId.Lock -> isFullscreen
                else -> true
            }
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.Comfortable),
    ) {
        for (id in candidates) {
            val alreadyInTarget = if (targetBar == PlayerBarSlot.Top) {
                layout.hasInTopBar(id)
            } else {
                layout.hasInBottomBar(id)
            }
            FilterChip(
                selected = alreadyInTarget,
                onClick = { onPick(id) },
                leadingIcon = {
                    Icon(
                        imageVector = controlIcon(id, isFullscreen),
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.IconInline),
                    )
                },
                label = { Text(controlLabel(id)) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GesturesAndSpeedSection(
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
    val holdSpeedShown = remember { MutableTransitionState(state.longPressSpeed) }
    holdSpeedShown.targetState = state.longPressSpeed
    val holdSpeedOnScreen = state.longPressSpeed || holdSpeedShown.currentState
    val enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
        fadeIn(MaterialTheme.motionScheme.fastEffectsSpec())
    val exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
        fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Cozy)) {
        SettingsGroup(title = stringResource(Res.string.player_custom_group_gestures)) {
            row { position ->
                ToggleSettingRow(
                    position = position,
                    icon = Icons.Outlined.FastForward,
                    title = stringResource(Res.string.settings_double_tap_seek),
                    subtitle = stringResource(Res.string.settings_double_tap_seek_subtitle),
                    checked = state.doubleTapSeek,
                    onCheckedChange = onDoubleTapSeekChange,
                )
            }
            if (state.doubleTapSeek) {
                row { position ->
                    ToggleSettingRow(
                        position = position,
                        icon = Icons.Outlined.Timer,
                        title = stringResource(Res.string.player_custom_sync_double_tap),
                        subtitle = stringResource(Res.string.player_custom_sync_double_tap_subtitle),
                        checked = layoutPrefs.syncDoubleTapWithSeekButton,
                        onCheckedChange = { sync ->
                            onUpdateLayout { it.copy(syncDoubleTapWithSeekButton = sync) }
                        },
                    )
                }
            }
            row { position ->
                ToggleSettingRow(
                    position = position,
                    icon = Icons.Outlined.Speed,
                    title = stringResource(Res.string.settings_long_press_speed),
                    subtitle = stringResource(Res.string.settings_long_press_speed_subtitle),
                    checked = state.longPressSpeed,
                    onCheckedChange = onLongPressSpeedChange,
                )
            }
            if (holdSpeedOnScreen) {
                row { position ->
                    AnimatedVisibility(visibleState = holdSpeedShown, enter = enter, exit = exit) {
                        ToggleSettingRow(
                            position = position,
                            icon = Icons.Outlined.Tune,
                            title = stringResource(Res.string.settings_long_press_speed_dial),
                            subtitle = stringResource(Res.string.settings_long_press_speed_dial_subtitle),
                            checked = state.longPressSpeedDial,
                            onCheckedChange = onLongPressSpeedDialChange,
                        )
                    }
                }
                row { position ->
                    AnimatedVisibility(visibleState = holdSpeedShown, enter = enter, exit = exit) {
                        ChoiceRow(
                            position = position,
                            icon = Icons.Outlined.Speed,
                            title = stringResource(Res.string.settings_default_hold_speed),
                            options = SettingsStore.HOLD_SPEED_OPTIONS,
                            selected = state.defaultHoldSpeed,
                            label = { formatSpeed(it) },
                            onChange = onDefaultHoldSpeedChange,
                            valueAtEnd = true,
                        )
                    }
                }
            }
            row { position ->
                ToggleSettingRow(
                    position = position,
                    icon = Icons.Outlined.SwapHoriz,
                    title = stringResource(Res.string.settings_swipe_seek),
                    subtitle = stringResource(Res.string.settings_swipe_seek_subtitle),
                    checked = state.swipeSeek,
                    onCheckedChange = onSwipeSeekChange,
                )
            }
            row { position ->
                ToggleSettingRow(
                    position = position,
                    icon = Icons.Outlined.SwapVert,
                    title = stringResource(Res.string.settings_swipe_brightness_volume),
                    subtitle = stringResource(Res.string.settings_swipe_brightness_volume_subtitle),
                    checked = state.swipeBrightnessVolume,
                    onCheckedChange = onSwipeBrightnessVolumeChange,
                )
            }
        }

        if (state.doubleTapSeek && !layoutPrefs.syncDoubleTapWithSeekButton) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(modifier = Modifier.padding(Spacing.Comfortable)) {
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
    }
}

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
