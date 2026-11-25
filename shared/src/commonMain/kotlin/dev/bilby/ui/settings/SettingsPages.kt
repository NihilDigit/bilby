package dev.bilby.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.HistoryToggleOff
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Start
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.bilby.stringResource
import dev.bilby.AppBuild
import dev.bilby.resources.*
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.data.CodecPreference
import dev.bilby.data.ContentBlockRule
import dev.bilby.data.DEFAULT_NIGHT_END_MINUTE
import dev.bilby.data.DEFAULT_NIGHT_START_MINUTE
import dev.bilby.data.LlmConfig
import dev.bilby.data.MINUTES_PER_DAY
import dev.bilby.data.NightModePrefs
import dev.bilby.data.NightWindowStatus
import dev.bilby.data.PlayEndMode
import dev.bilby.data.ScheduleRule
import dev.bilby.data.SettingsStore
import dev.bilby.data.SponsorBlockPrefs
import dev.bilby.data.WhitelistedUp
import dev.bilby.data.computeNextNightEndEpochMillis
import dev.bilby.data.evaluateNightMode
import dev.bilby.data.formatMinuteOfDay
import dev.bilby.data.formatNightRange
import dev.bilby.data.relaxesActiveRestriction
import dev.bilby.ui.components.MetaSeparator
import java.util.UUID
import dev.bilby.api.toHttpsUrl
import dev.bilby.ui.update.UpdateDialog
import dev.bilby.update.AppUpdateService
import dev.bilby.update.AvailableUpdate
import dev.bilby.update.UpdateStatus
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import dev.bilby.player.liveQualityLabel
import dev.bilby.player.videoQualityLabel
import dev.bilby.player.audioQualityLabel
import dev.bilby.player.DEFAULT_AUDIO_QUALITY_OPTIONS
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.ListItemPersonSkeleton
import dev.bilby.ui.components.ListSkeleton
import dev.bilby.ui.components.PaneSheet
import dev.bilby.ui.components.PrefetchNearEnd
import dev.bilby.ui.components.SearchField
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.night.NightExitHoldSheet
import dev.bilby.ui.player.formatSpeed
import dev.bilby.ui.player.labelRes
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.video.CATEGORY_DESCRIPTIONS
import dev.bilby.ui.video.CATEGORY_GROUPS
import dev.bilby.ui.video.CATEGORY_LABELS
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import org.jetbrains.compose.resources.StringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import dev.bilby.data.AppearancePrefs
import dev.bilby.data.MotionStyle
import dev.bilby.data.StartTab
import dev.bilby.data.ThemeMode
import dev.bilby.ui.AppLanguage
import dev.bilby.ui.LocalSystemActions
import dev.bilby.ui.theme.dynamicColorSchemes
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.LocalIsDarkTheme
import dev.bilby.ui.theme.PaletteSchemes
import dev.bilby.ui.theme.ThemePalette
import dev.bilby.ui.theme.dynamicColorAvailable
import dev.bilby.ui.theme.platformSupportsPredictiveBack
import dev.bilby.ui.theme.tabular

/**
 * 设置的二级页面。**每一页都是一整页,不是一个展开块**:展开块要么把首页撑回原来的长度,
 * 要么在滚动中途改变高度,而这两件事正是重做前的样子。
 *
 * 一页一个 [SettingsSection],由 `Destinations.kt` 的 `SettingsPage` 带过来 —— 七个页面
 * 共用一个 NavKey,而不是七条路由:它们的差别只有"哪一页",没有各自的参数。
 */
enum class SettingsSection {
    Appearance,
    Playback,
    Gestures,
    Navigation,

    /** SponsorBlock 的九个分类。从播放页再降一层,理由见 [PlaybackSettingsPage]。 */
    SponsorCategories,

    /** 排除的 UP 主名单。从隐私页再降一层,理由见 [ExcludedFeedPage]。 */
    ExcludedFeed,
    /** 本地内容屏蔽规则（UP 主与关键词）。从隐私页再降一层。 */
    BlockRules,
    Agent,
    Privacy,
    NightMode,
    About,
}

/**
 * 子页的外壳:顶栏、返回、可读宽度、滚动。抽出来是因为七页一模一样,而漏掉其中一样
 * (比如某一页忘了限宽)在平板上一眼看得出来。
 *
 * 分组由各页自己套 [SettingsGroup]。一页只有一组时不给组标题,节名就是顶栏标题。
 */
@Composable
internal fun SettingsSubPage(
    title: String,
    /** null 时不画返回:两栏的右栏里,这一页不是"进来的",返回在左栏的顶栏上。 */
    onBack: (() -> Unit)?,
    /**
     * 值到齐了没有。**没到齐就一行都不画**,不是画一个默认值等着改 —— 见
     * [SettingsUiState.loaded]。顶栏照画:标题和返回不依赖任何一项设置,先出来才不会闪。
     */
    ready: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    // pinned 而不是 enterAlways:顶栏留着不动,只在内容滚起来之后换一档容器色。各页的内容
    // 都短,滚起来的那一下顶栏跟着一起走反而像页面跳了一下。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollState.value == 0) {
        if (scrollState.value == 0) {
            scrollBehavior.state.contentOffset = 0f
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            SettingsTopBarTheme {
                BilbyTopBar(
                    title = title,
                    onBack = onBack,
                    scrollBehavior = scrollBehavior,
                    actions = actions,
                )
            }
        },
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { insets ->
        AdaptiveContent(
            modifier = Modifier.fillMaxSize().padScaffoldExceptBottom(insets),
            maxWidth = Breakpoints.ReadableWidth,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(bottom = Spacing.Spacious + navigationBarsBottom()),
            ) {
                if (ready) content()
            }
        }
    }
}

/**
 * 播放:播放、画质与音质、跳过片段、离线缓存四组。**弹幕不在这里** —— 它在播放页的设置面板里,
 * 否则这一页会像重做之前一样什么都往里塞。
 *
 * SponsorBlock 与缓存原先各占一页,一页只有一两行;两者都是"播放时怎么做",并进来各成一组。
 * **SponsorBlock 的九个分类仍然再降一层**:它们占的高度比这一页其余部分加起来还多,而多数人
 * 设一次就再也不动 —— 常驻在这里的结果是每次来改别的都要滚过它们。
 */
@Composable
fun PlaybackSettingsPage(
    state: SettingsUiState,
    onWifiQualityChange: (Int) -> Unit,
    onMeteredQualityChange: (Int) -> Unit,
    onCodecChange: (CodecPreference) -> Unit,
    onEndModeChange: (PlayEndMode) -> Unit,
    onPbpOutroCompletionChange: (Boolean) -> Unit = {},
    onWifiAudioChange: (Int) -> Unit,
    onMeteredAudioChange: (Int) -> Unit,
    onWifiLiveQualityChange: (Int) -> Unit = {},
    onMeteredLiveQualityChange: (Int) -> Unit = {},
    onPickUpdatesDefaultChange: (Boolean) -> Unit,
    onSponsorBlockChange: (SponsorBlockPrefs) -> Unit,
    onOpenSponsorCategories: () -> Unit,
    onOfflineConcurrencyChange: (Int) -> Unit,
    onBack: (() -> Unit)?,
) {
    var editingServer by rememberSaveable { mutableStateOf(false) }
    val prefs = state.sponsorBlock
    // SponsorBlock 关掉时它下面两行不适用,但**它们的出现和消失要看得见**:直接 `if` 掉的话页面在同一次
    // 点击里换掉了两行的高度,读起来像整页跳了一下,而跳的原因(刚按下的那个开关)
    // 已经滚出视线的可能也有。沿竖轴展开收起就把因果连起来了。
    //
    // 两行各一个过渡状态:一个 MutableTransitionState 只能驱动一个 AnimatedVisibility。
    val categoriesShown = remember { MutableTransitionState(prefs.enabled) }
    val serverShown = remember { MutableTransitionState(prefs.enabled) }
    categoriesShown.targetState = prefs.enabled
    serverShown.targetState = prefs.enabled
    // 两行算不算进这一组,看它们还在不在屏上,不看开关:收起动画走完之前开关那一行仍是
    // 三行里的首行。按开关算的话,它的下沿在按下的那一刻就圆起来,底下两行却还在往回缩。
    val extrasOnScreen = prefs.enabled || categoriesShown.currentState || serverShown.currentState
    // spec 取 motionScheme 的 fast 档:这一块是组件显隐,不是转场(风格指南 §6 那张表),
    // 而且它紧跟着一次点击,慢一档就成了"按下去要等一下"。
    val enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
        fadeIn(MaterialTheme.motionScheme.fastEffectsSpec())
    val exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
        fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())
    SettingsSubPage(stringResource(Res.string.settings_section_playback), onBack, state.loaded) {
        SettingsGroup(title = stringResource(Res.string.settings_group_playback)) {
            row { position ->
                ChoiceRow(
                    position = position,
                    icon = Icons.Outlined.SkipNext,
                    title = stringResource(Res.string.settings_end_mode),
                    subtitle = stringResource(Res.string.settings_end_mode_subtitle),
                    options = PlayEndMode.entries,
                    selected = state.endMode,
                    label = { stringResource(it.labelRes()) },
                    onChange = onEndModeChange,
                )
            }
            row { position ->
                ToggleSettingRow(
                    position = position,
                    icon = Icons.Outlined.Flag,
                    title = stringResource(Res.string.settings_pbp_outro_completion),
                    subtitle = stringResource(Res.string.settings_pbp_outro_completion_subtitle),
                    checked = state.pbpOutroCompletion,
                    onCheckedChange = onPbpOutroCompletionChange,
                )
            }
        }
        // 六行默认值两两成对,差别在网络,所以图标标网络,画质还是音质由标题说。
        SettingsGroup(title = stringResource(Res.string.settings_group_quality)) {
            // 两行是同一个值按网络分的两格。播放页里切画质默认只管那一次播放,「播放页切换改为默认」
            // 打开之后才写进当下所在的那一格(见 AudioPlaybackService.setQuality)。
            row { position ->
                ChoiceRow(
                    position = position,
                    icon = Icons.Outlined.Wifi,
                    title = stringResource(Res.string.settings_default_quality_wifi),
                    options = SettingsStore.QUALITY_OPTIONS,
                    selected = state.defaultQualityWifi,
                    label = { videoQualityLabel(it) },
                    onChange = onWifiQualityChange,
                )
            }
            row { position ->
                ChoiceRow(
                    position = position,
                    icon = Icons.Outlined.SignalCellularAlt,
                    title = stringResource(Res.string.settings_default_quality_metered),
                    // 判据是系统的流量计费标记,不是"是不是 WiFi" —— 手机热点和按量计费的 WiFi 都算
                    // 计费网络,而这一点从标题上看不出来。
                    subtitle = stringResource(Res.string.settings_default_quality_metered_subtitle),
                    options = SettingsStore.QUALITY_OPTIONS,
                    selected = state.defaultQualityMetered,
                    label = { videoQualityLabel(it) },
                    onChange = onMeteredQualityChange,
                )
            }
            // 音质同一套:按网络分两格,档位是固定表(DEFAULT_AUDIO_QUALITY_OPTIONS)。
            row { position ->
                ChoiceRow(
                    position = position,
                    icon = Icons.Outlined.Wifi,
                    title = stringResource(Res.string.settings_default_audio_wifi),
                    options = DEFAULT_AUDIO_QUALITY_OPTIONS,
                    selected = state.defaultAudioWifi,
                    label = { audioQualityLabel(it) },
                    onChange = onWifiAudioChange,
                )
            }
            row { position ->
                ChoiceRow(
                    position = position,
                    icon = Icons.Outlined.SignalCellularAlt,
                    title = stringResource(Res.string.settings_default_audio_metered),
                    options = DEFAULT_AUDIO_QUALITY_OPTIONS,
                    selected = state.defaultAudioMetered,
                    label = { audioQualityLabel(it) },
                    onChange = onMeteredAudioChange,
                )
            }
            // 直播画质同样按网络分两格。服务端没有该档时自动就近退档。
            row { position ->
                ChoiceRow(
                    position = position,
                    icon = Icons.Outlined.Wifi,
                    title = stringResource(Res.string.settings_default_live_quality_wifi),
                    options = SettingsStore.LIVE_QUALITY_OPTIONS,
                    selected = state.defaultLiveQualityWifi,
                    label = { liveQualityLabel(it) },
                    onChange = onWifiLiveQualityChange,
                )
            }
            row { position ->
                ChoiceRow(
                    position = position,
                    icon = Icons.Outlined.SignalCellularAlt,
                    title = stringResource(Res.string.settings_default_live_quality_metered),
                    options = SettingsStore.LIVE_QUALITY_OPTIONS,
                    selected = state.defaultLiveQualityMetered,
                    label = { liveQualityLabel(it) },
                    onChange = onMeteredLiveQualityChange,
                )
            }
            // 放这一组而不是「播放」组:它管的正是上面四行什么时候被播放页改写。
            row { position ->
                ToggleSettingRow(
                    position = position,
                    icon = Icons.Outlined.PushPin,
                    title = stringResource(Res.string.settings_pick_updates_default),
                    subtitle = stringResource(Res.string.settings_pick_updates_default_subtitle),
                    checked = state.playerPickUpdatesDefault,
                    onCheckedChange = onPickUpdatesDefaultChange,
                )
            }
            row { position ->
                CodecSection(
                    position = position,
                    selected = state.codec,
                    hardwareCodecIds = state.hardwareCodecIds,
                    onChange = onCodecChange,
                )
            }
        }
        SettingsGroup(title = stringResource(Res.string.settings_group_sponsorblock)) {
            row { position ->
                ToggleSettingRow(
                    position = position,
                    icon = Icons.Outlined.FastForward,
                    title = stringResource(Res.string.settings_sponsorblock_toggle),
                    subtitle = stringResource(Res.string.settings_sponsorblock_toggle_subtitle),
                    checked = prefs.enabled,
                    onCheckedChange = { onSponsorBlockChange(prefs.copy(enabled = it)) },
                )
            }
            if (extrasOnScreen) {
                row { position ->
                    AnimatedVisibility(visibleState = categoriesShown, enter = enter, exit = exit) {
                        SettingRow(
                            position = position,
                            icon = Icons.Outlined.Checklist,
                            title = stringResource(Res.string.settings_sponsorblock_categories),
                            value = stringResource(
                                Res.string.settings_sponsorblock_enabled_count,
                                prefs.categories.count { it in CATEGORY_LABELS },
                                CATEGORY_LABELS.size,
                            ),
                            target = RowTarget.Page,
                            onClick = onOpenSponsorCategories,
                        )
                    }
                }
                // 服务器地址和分类不是一类东西:那些是"跳什么",这个是"问谁"。
                row { position ->
                    AnimatedVisibility(visibleState = serverShown, enter = enter, exit = exit) {
                        SettingRow(
                            position = position,
                            icon = Icons.Outlined.Dns,
                            title = stringResource(Res.string.settings_sponsorblock_server),
                            value = prefs.serverUrl,
                            subtitle = stringResource(Res.string.settings_sponsorblock_server_subtitle),
                            onClick = { editingServer = true },
                        )
                    }
                }
            }
        }
        // 缓存只有并发度一项 —— 清晰度在缓存面板上选(那是"这一次下什么"),而这里是"怎么下"。
        SettingsGroup(title = stringResource(Res.string.settings_group_offline)) {
            row { position ->
                ChoiceRow(
                    position = position,
                    icon = Icons.Outlined.DownloadForOffline,
                    title = stringResource(Res.string.settings_offline_concurrency),
                    // 说清代价:调大不是白拿的,而"下载多了刷不动"是最容易被归到别处的那种症状。
                    subtitle = stringResource(Res.string.settings_offline_concurrency_subtitle),
                    options = SettingsStore.OFFLINE_CONCURRENCY_OPTIONS,
                    selected = state.offlineConcurrency,
                    label = { stringResource(Res.string.settings_offline_concurrency_value, it) },
                    onChange = onOfflineConcurrencyChange,
                    valueAtEnd = true,
                )
            }
        }
    }
    if (editingServer) {
        UrlFieldDialog(
            title = stringResource(Res.string.settings_sponsorblock_server_dialog),
            label = stringResource(Res.string.settings_sponsorblock_server),
            initial = prefs.serverUrl,
            placeholder = SettingsStore.DEFAULT_SB_SERVER,
            onDismiss = { editingServer = false },
            onConfirm = {
                editingServer = false
                onSponsorBlockChange(prefs.copy(serverUrl = it))
            },
        )
    }
}

/**
 * 快捷手势兼容入口，已整合升级为 [PlayerCustomizationPage]。
 */
@Composable
fun GestureSettingsPage(
    state: SettingsUiState,
    onDoubleTapSeekChange: (Boolean) -> Unit,
    onLongPressSpeedChange: (Boolean) -> Unit,
    onLongPressSpeedDialChange: (Boolean) -> Unit = {},
    onDefaultHoldSpeedChange: (Float) -> Unit,
    onSwipeSeekChange: (Boolean) -> Unit,
    onSwipeBrightnessVolumeChange: (Boolean) -> Unit,
    onBack: (() -> Unit)?,
) {
    PlayerCustomizationPage(
        state = state,
        onUpdateLayout = {},
        onResetLayout = {},
        onDoubleTapSeekChange = onDoubleTapSeekChange,
        onLongPressSpeedChange = onLongPressSpeedChange,
        onLongPressSpeedDialChange = onLongPressSpeedDialChange,
        onDefaultHoldSpeedChange = onDefaultHoldSpeedChange,
        onSwipeSeekChange = onSwipeSeekChange,
        onSwipeBrightnessVolumeChange = onSwipeBrightnessVolumeChange,
        onBack = onBack,
    )
}

@Composable
fun SponsorCategoriesPage(
    state: SettingsUiState,
    onChange: (SponsorBlockPrefs) -> Unit,
    onBack: (() -> Unit)?,
) {
    val prefs = state.sponsorBlock
    SettingsSubPage(stringResource(Res.string.settings_sponsorblock_categories), onBack, state.loaded) {
        CATEGORY_GROUPS.forEach { (groupTitle, categories) ->
            SettingsGroup(title = stringResource(groupTitle)) {
                categories.forEach { category ->
                    val label = CATEGORY_LABELS[category] ?: return@forEach
                    row { position ->
                        ToggleSettingRow(
                            position = position,
                            icon = CategoryIcons[category] ?: Icons.Outlined.FastForward,
                            title = stringResource(label),
                            // 类别名解释不了自己,判断"要不要跳过它"靠的是这一行。
                            subtitle = CATEGORY_DESCRIPTIONS[category]?.let { stringResource(it) },
                            checked = category in prefs.categories,
                            onCheckedChange = { checked ->
                                val next = if (checked) prefs.categories + category else prefs.categories - category
                                onChange(prefs.copy(categories = next))
                            },
                            useCheckbox = true,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 各分类的行首图标,键与 CATEGORY_LABELS 相同。放在这里而不放进 ui/video 那张表:图标只有
 * 设置页用,跳过时的提示只写类别名。表里没有的分类退回快进图标,与「自动跳过」那一行同一个。
 */
private val CategoryIcons: Map<String, ImageVector> = mapOf(
    "sponsor" to Icons.Outlined.Paid,
    "selfpromo" to Icons.Outlined.Campaign,
    "interaction" to Icons.Outlined.ThumbUp,
    "intro" to Icons.Outlined.Start,
    "outro" to Icons.Outlined.Flag,
    "preview" to Icons.Outlined.Preview,
    "padding" to Icons.Outlined.HourglassEmpty,
    "filler" to Icons.AutoMirrored.Outlined.AltRoute,
    "music_offtopic" to Icons.Outlined.MusicOff,
)

/**
 * 外观:明暗、纯黑、配色、语言。
 *
 * - **明暗**三档是一组连体按钮,直接摆在行里:只有三档,点开对话框再选是多走一步,
 *   而连体按钮组正是 M3 给"固定 2 到 5 项单选"的控件(风格指南 §2.1)。
 *   **纯黑**是它下面的一个开关。
 * - **配色**是一片色块而不是一行文字加对话框:这一项选的就是一个颜色,读名字挑颜色是在
 *   绕路。第一格是按壁纸取色(系统不支持时不给这一格),其余是内置配色。选中那一格画一个勾,
 *   不只靠颜色(风格指南 §2.6);选中的名字写在标题下面。
 * - **语言**三项单选,改完立即重建页面。
 */
@Composable
fun AppearanceSettingsPage(
    state: SettingsUiState,
    language: AppLanguage,
    onModeChange: (ThemeMode) -> Unit,
    onPureBlackChange: (Boolean) -> Unit,
    onPaletteChange: (String) -> Unit,
    onMotionStyleChange: (MotionStyle) -> Unit = {},
    onStartTabChange: (StartTab) -> Unit = {},
    onSearchLandingChange: (Boolean) -> Unit = {},
    onPredictiveBackChange: (Boolean) -> Unit = {},
    onVideoGridColumnsChange: (Int) -> Unit = {},
    onLanguageChange: (AppLanguage) -> Unit,
    onBack: (() -> Unit)?,
) {
    val appearance = state.appearance
    // 在登记行之前读出来:登记那一步不是 @Composable(见 SettingsRows)。
    val supportsLanguageSwitch = LocalSystemActions.current.supportsLanguageSwitch
    SettingsSubPage(stringResource(Res.string.settings_section_appearance), onBack, state.loaded) {
        SettingsGroup {
            row { position ->
                ThemeModeRow(position = position, selected = appearance.mode, onSelect = onModeChange)
            }
            row { position ->
                ToggleSettingRow(
                    position = position,
                    icon = Icons.Outlined.Contrast,
                    title = stringResource(Res.string.settings_pure_black),
                    subtitle = stringResource(Res.string.settings_pure_black_subtitle),
                    checked = appearance.pureBlack,
                    onCheckedChange = onPureBlackChange,
                )
            }
            row { position ->
                PaletteRow(position = position, selected = appearance.palette, onSelect = onPaletteChange)
            }
            row { position ->
                MotionStyleRow(
                    position = position,
                    selected = appearance.motionStyle,
                    onSelect = onMotionStyleChange,
                )
            }
            row { position ->
                StartTabRow(position = position, selected = appearance.startTab, onSelect = onStartTabChange)
            }
            row { position ->
                ToggleSettingRow(
                    position = position,
                    icon = Icons.Outlined.Search,
                    title = stringResource(Res.string.settings_search_landing),
                    checked = appearance.searchLanding,
                    onCheckedChange = onSearchLandingChange,
                )
            }
            if (platformSupportsPredictiveBack) {
                row { position ->
                    ToggleSettingRow(
                        position = position,
                        icon = Icons.Outlined.Gesture,
                        title = stringResource(Res.string.settings_predictive_back),
                        subtitle = stringResource(Res.string.settings_predictive_back_subtitle),
                        checked = appearance.predictiveBack,
                        onCheckedChange = onPredictiveBackChange,
                    )
                }
            }
            row { position ->
                VideoGridColumnsRow(
                    position = position,
                    selected = appearance.videoGridColumns,
                    onSelect = onVideoGridColumnsChange,
                )
            }
            if (supportsLanguageSwitch) {
                row { position ->
                    ChoiceRow(
                        position = position,
                        icon = Icons.Outlined.Language,
                        title = stringResource(Res.string.settings_language),
                        options = AppLanguage.entries,
                        selected = language,
                        label = { stringResource(it.label) },
                        onChange = onLanguageChange,
                    )
                }
            }
        }
    }
}

private val ThemeMode.label: StringResource
    get() = when (this) {
        ThemeMode.System -> Res.string.settings_theme_system
        ThemeMode.Light -> Res.string.settings_theme_light
        ThemeMode.Dark -> Res.string.settings_theme_dark
    }

private val MotionStyle.label: StringResource
    get() = when (this) {
        MotionStyle.Reduced -> Res.string.settings_motion_reduced
        MotionStyle.Standard -> Res.string.settings_motion_standard
        MotionStyle.Expressive -> Res.string.settings_motion_expressive
    }

private val MotionStyle.subtitle: StringResource
    get() = when (this) {
        MotionStyle.Reduced -> Res.string.settings_motion_reduced_subtitle
        MotionStyle.Standard -> Res.string.settings_motion_standard_subtitle
        MotionStyle.Expressive -> Res.string.settings_motion_expressive_subtitle
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MotionStyleRow(position: RowPosition, selected: MotionStyle, onSelect: (MotionStyle) -> Unit) {
    val options = MotionStyle.entries
    val interactionSources = remember(options.size) { List(options.size) { MutableInteractionSource() } }
    val tonalColors = FilledTonalToggleButtonDefaults.colors()
    ControlSettingRow(
        position = position,
        icon = Icons.Outlined.Animation,
        title = stringResource(Res.string.settings_motion_style),
        value = stringResource(selected.subtitle),
    ) {
        val labels = options.map { stringResource(it.label) }
        ButtonGroup(
            overflowIndicator = { menuState ->
                IconButton(onClick = { menuState.show() }) {
                    Icon(
                        Icons.Outlined.MoreHoriz,
                        contentDescription = stringResource(Res.string.menu_item_actions),
                    )
                }
            },
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            options.forEachIndexed { index, option ->
                val label = labels[index]
                val interactionSource = interactionSources[index]
                val itemModifier = Modifier.weight(1f).animateWidth(interactionSource)
                customItem(
                    buttonGroupContent = {
                        ToggleButton(
                            checked = option == selected,
                            onCheckedChange = { onSelect(option) },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            colors = tonalColors,
                            contentPadding = PaddingValues(horizontal = Spacing.Tight),
                            interactionSource = interactionSource,
                            modifier = itemModifier.semantics { role = Role.RadioButton },
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    menuContent = { menuState ->
                        DropdownMenuItem(
                            onClick = {
                                menuState.dismiss()
                                onSelect(option)
                            },
                            text = { Text(label) },
                            shape = menuItemShape(index, options.size),
                        )
                    },
                )
            }
        }
    }
}

private val StartTab.label: StringResource
    get() = when (this) {
        StartTab.Feed -> Res.string.tab_feed
        StartTab.Search -> Res.string.tab_search
        StartTab.Profile -> Res.string.tab_profile
    }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StartTabRow(position: RowPosition, selected: StartTab, onSelect: (StartTab) -> Unit) {
    val options = StartTab.entries
    val interactionSources = remember(options.size) { List(options.size) { MutableInteractionSource() } }
    val tonalColors = FilledTonalToggleButtonDefaults.colors()
    ControlSettingRow(
        position = position,
        icon = Icons.Outlined.Start,
        title = stringResource(Res.string.settings_start_tab),
    ) {
        val labels = options.map { stringResource(it.label) }
        ButtonGroup(
            overflowIndicator = { menuState ->
                IconButton(onClick = { menuState.show() }) {
                    Icon(
                        Icons.Outlined.MoreHoriz,
                        contentDescription = stringResource(Res.string.menu_item_actions),
                    )
                }
            },
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            options.forEachIndexed { index, option ->
                val label = labels[index]
                val interactionSource = interactionSources[index]
                val itemModifier = Modifier.weight(1f).animateWidth(interactionSource)
                customItem(
                    buttonGroupContent = {
                        ToggleButton(
                            checked = option == selected,
                            onCheckedChange = { onSelect(option) },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            colors = tonalColors,
                            contentPadding = PaddingValues(horizontal = Spacing.Tight),
                            interactionSource = interactionSource,
                            modifier = itemModifier.semantics { role = Role.RadioButton },
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    menuContent = { menuState ->
                        DropdownMenuItem(
                            onClick = {
                                menuState.dismiss()
                                onSelect(option)
                            },
                            text = { Text(label) },
                            shape = menuItemShape(index, options.size),
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VideoGridColumnsRow(position: RowPosition, selected: Int, onSelect: (Int) -> Unit) {
    val min = AppearancePrefs.MIN_VIDEO_GRID_COLUMNS
    val max = AppearancePrefs.MAX_VIDEO_GRID_COLUMNS
    val effective = selected.coerceIn(min, max)
    ControlSettingRow(
        position = position,
        icon = Icons.Outlined.GridView,
        title = stringResource(Res.string.settings_video_grid_columns),
        value = stringResource(Res.string.settings_video_grid_columns_subtitle),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Slider(
                value = effective.toFloat(),
                onValueChange = { raw ->
                    val snapped = raw.roundToInt().coerceIn(min, max)
                    if (snapped != effective) onSelect(snapped)
                },
                valueRange = min.toFloat()..max.toFloat(),
                steps = (max - min - 1).coerceAtLeast(0),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = effective.toString(),
                style = MaterialTheme.typography.titleMediumEmphasized.tabular,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 明暗三档，连体按钮组。写法与播放器设置面板的 ConnectedChoices 相同：各格等宽，
 * 内边距收小，读屏按单选念，并在按压时提供弹性宽度缩放。
 *
 * 用默认的实心配色，不换 tonal：这里一页只有这一组，选中哪一档正需要一眼看出来。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemeModeRow(position: RowPosition, selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = ThemeMode.entries
    val interactionSources = remember(options.size) { List(options.size) { MutableInteractionSource() } }
    ControlSettingRow(
        position = position,
        icon = Icons.Outlined.DarkMode,
        title = stringResource(Res.string.settings_theme_mode),
    ) {
        val labels = options.map { stringResource(it.label) }
        ButtonGroup(
            overflowIndicator = { menuState ->
                IconButton(onClick = { menuState.show() }) {
                    Icon(
                        Icons.Outlined.MoreHoriz,
                        contentDescription = stringResource(Res.string.menu_item_actions),
                    )
                }
            },
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            options.forEachIndexed { index, option ->
                val label = labels[index]
                val interactionSource = interactionSources[index]
                val itemModifier = Modifier.weight(1f).animateWidth(interactionSource)
                customItem(
                    buttonGroupContent = {
                        ToggleButton(
                            checked = option == selected,
                            onCheckedChange = { onSelect(option) },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            // 默认左右各 16dp，窄屏上三格平分之后「跟随系统」放不下。
                            contentPadding = PaddingValues(horizontal = Spacing.Tight),
                            interactionSource = interactionSource,
                            modifier = itemModifier.semantics { role = Role.RadioButton },
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    menuContent = { menuState ->
                        DropdownMenuItem(
                            onClick = {
                                menuState.dismiss()
                                onSelect(option)
                            },
                            text = { Text(label) },
                            shape = menuItemShape(index, options.size),
                        )
                    },
                )
            }
        }
    }
}

/**
 * 配色那一行:标题下写选中的名字,再下面一片圆形色块。
 *
 * 色块画的是这一套在当前明暗下的 primary,即选中后按钮与强调色的实际颜色,不是种子色:
 * 种子色经 TonalSpot 调和后会变淡,按种子色画会与选完之后看到的对不上。按壁纸取色那一格
 * 同理,画壁纸取出来的 primary,没选中时压一个壁纸图标,与内置配色分开。
 *
 * **折行,不横滑。** 十格在窄屏上折成两行放得下,横滑的话后几个色要拖一下才看得到,而这一项
 * 选的就是颜色,挑之前得先一眼看全。
 */
@Composable
private fun PaletteRow(position: RowPosition, selected: String, onSelect: (String) -> Unit) {
    val dark = LocalIsDarkTheme.current
    val dynamicLabel = stringResource(Res.string.theme_palette_dynamic)
    val swatches: List<Swatch> = buildList {
        if (dynamicColorAvailable) {
            add(Swatch(AppearancePrefs.DYNAMIC, dynamicColorSchemes(), dynamicLabel, Icons.Outlined.Wallpaper))
        }
        ThemePalette.entries.forEach { add(Swatch(it.name, it.schemes, stringResource(it.label))) }
    }
    // 系统不支持壁纸取色时存着的 dynamic 实际落到默认那一套,选中标记也跟过去。
    val effective = if (swatches.any { it.key == selected }) selected else ThemePalette.Default.name
    ControlSettingRow(
        position = position,
        icon = Icons.Outlined.Palette,
        title = stringResource(Res.string.settings_theme_palette),
        value = paletteLabel(selected),
    ) {
        // **多行按统一网格列严格对齐，不按每行独立 SpaceBetween 分散。** 9 格拆成 5+4 时，
        // 每行用等宽 weight(1f) 单元加末尾空占位，保证第二行 4 个色块与第一行前 4 列垂直对齐。
        var rowWidthPx by remember { mutableIntStateOf(0) }
        val rowWidthDp = with(LocalDensity.current) { rowWidthPx.toDp() }
        val fit = if (rowWidthPx > 0) (rowWidthDp / SwatchTouchSize).toInt().coerceAtLeast(1) else 5
        val rows = (swatches.size + fit - 1) / fit
        val perRow = (swatches.size + rows - 1) / rows
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { if (it.width != rowWidthPx) rowWidthPx = it.width }
                .selectableGroup(),
        ) {
            if (rows <= 1) {
                Row(
                    horizontalArrangement = Arrangement.Start,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    swatches.forEach { swatch ->
                        val scheme = if (dark) swatch.schemes.dark else swatch.schemes.light
                        ColorSwatch(
                            color = scheme.primary,
                            onColor = scheme.onPrimary,
                            label = swatch.label,
                            selected = swatch.key == effective,
                            idleIcon = swatch.idleIcon,
                            onClick = { onSelect(swatch.key) },
                        )
                    }
                }
            } else {
                swatches.chunked(perRow).forEach { rowSwatches ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        rowSwatches.forEach { swatch ->
                            val scheme = if (dark) swatch.schemes.dark else swatch.schemes.light
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.weight(1f),
                            ) {
                                ColorSwatch(
                                    color = scheme.primary,
                                    onColor = scheme.onPrimary,
                                    label = swatch.label,
                                    selected = swatch.key == effective,
                                    idleIcon = swatch.idleIcon,
                                    onClick = { onSelect(swatch.key) },
                                )
                            }
                        }
                        repeat(perRow - rowSwatches.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** 色板的一格。[idleIcon] 只有按壁纸取色那一格有。 */
private class Swatch(
    val key: String,
    val schemes: PaletteSchemes,
    val label: String,
    val idleIcon: ImageVector? = null,
)

/** 一格色块。色块本身不写名字，读屏靠 contentDescription、桌面悬停靠 BilbyTooltip。 */
@Composable
private fun ColorSwatch(
    color: Color,
    onColor: Color,
    label: String,
    selected: Boolean,
    idleIcon: ImageVector?,
    onClick: () -> Unit,
) {
    BilbyTooltip(text = label) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(SwatchTouchSize)
                .clip(CircleShape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = label },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(SwatchSize)
                    .clip(CircleShape)
                    .background(color),
            ) {
                val icon = if (selected) Icons.Outlined.Check else idleIcon
                // 勾取这一套的 onPrimary：它就是为压在 primary 上设计的，不必再按亮度猜黑白。
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = onColor, modifier = Modifier.size(Dimens.IconInline))
                }
            }
        }
    }
}

/** 触摸目标取 48dp 下限(风格指南 §3)。 */
private val SwatchTouchSize = 48.dp

/** 色块比触摸区小一圈,相邻两格之间才留得出缝。 */
private val SwatchSize = 36.dp

/**
 * 搜索助理。
 *
 * **地址、key、模型收成一行。** 它们只有凑齐了才有意义,本来就是一个对话框一起改的
 * (见 [LlmDialog]);拆成三行陈列是把一件事画成三件,而中间那两个状态("配了地址没配 key")
 * 谁也不会单独去看。
 */
@Composable
fun AgentSettingsPage(
    state: SettingsUiState,
    onLlmChange: (LlmConfig) -> Unit,
    onSmokeTest: () -> Unit,
    onBack: (() -> Unit)?,
    onFetchModels: ((baseUrl: String, apiKey: String) -> Unit)? = null,
    onClearModels: (() -> Unit)? = null,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    val clearModels = onClearModels ?: state.llmModelFetcher.clear
    val fetchModels = onFetchModels ?: state.llmModelFetcher.fetch
    SettingsSubPage(stringResource(Res.string.settings_section_agent), onBack, state.loaded) {
        SettingsGroup {
            row { position ->
                SettingRow(
                    position = position,
                    icon = Icons.Outlined.Api,
                    title = stringResource(Res.string.settings_agent_config),
                    // 只说配没配,不显示遮蔽后的 key:遮蔽只挡眼睛,截图和录屏挡不住。
                    subtitle = state.llm?.let { llm ->
                        if (!llm.isConfigured) {
                            stringResource(Res.string.settings_not_configured)
                        } else {
                            "${llm.baseUrl}\n${llm.model}"
                        }
                    } ?: stringResource(Res.string.settings_loading),
                    onClick = { editing = true },
                )
            }
            // **这一行故意保留接口原话**,是全应用唯一的例外。别处收成三句
            // (`ui/ErrorText.kt`)的理由是"用户拿错误码做不了任何事";这里反过来 ——
            // 这条连的是用户自己填的 base URL 和 key,而这一行的用途就是告诉他填错在哪。
            // 换成「请求被拒绝」之后 401、DNS 解析失败、模型名不存在读起来一模一样,
            // 这个冒烟测试就没用了。
            row { position ->
                SettingRow(
                    position = position,
                    icon = Icons.Outlined.NetworkCheck,
                    title = stringResource(Res.string.settings_llm_test),
                    subtitle = when (val test = state.llmTest) {
                        LlmTest.Idle -> stringResource(Res.string.settings_llm_test_hint)
                        LlmTest.Running -> stringResource(Res.string.settings_llm_test_running)
                        is LlmTest.Ok -> stringResource(Res.string.settings_llm_test_ok, test.millis)
                        is LlmTest.Failed -> test.message
                    },
                    onClick = onSmokeTest,
                )
            }
        }
    }
    if (editing) {
        LlmDialog(
            initial = state.llm ?: LlmConfig("", "", SettingsStore.DEFAULT_LLM_MODEL),
            modelsState = state.llmModels,
            onFetchModels = fetchModels,
            onDismiss = {
                editing = false
                clearModels()
            },
            // 保存也要收起对话框。原先只有取消收,按下保存之后配置存进去了、框还开着,
            // 读起来像没生效,于是再按一次。
            onConfirm = {
                editing = false
                clearModels()
                onLlmChange(it)
            },
        )
    }
}

/**
 * 隐私与屏蔽。三项都是「不想看到 / 不想被记录」,放在一起才成体系 —— 从前它们散在三节里,
 * 其中「首页已排除的 UP」还是自己独占一节的孤儿。
 */
@Composable
fun PrivacySettingsPage(
    state: SettingsUiState,
    onHistoryPausedChange: (Boolean) -> Unit,
    onRetryHistoryPause: () -> Unit,
    onOpenBlacklist: () -> Unit,
    onOpenExcludedFeed: () -> Unit,
    onOpenBlockRules: () -> Unit,
    onDanmakusArchiveChange: (Boolean) -> Unit,
    onBack: (() -> Unit)?,
) {
    SettingsSubPage(stringResource(Res.string.settings_section_privacy), onBack, state.loaded) {
        SettingsGroup {
            // 和这一页其余开关不同,它读的是服务端的账号设置,所以读不到是一种真实状态。三档各画
            // 各的,不把未知折成一个关着的开关 —— 那会让人以为"正在记录",而实际上谁也不知道。
            row { position ->
                when (val pause = state.historyPause) {
                    // 读取中与读到之后是同一个开关行,只有行尾在转圈与开关之间换,见 loading 参数。
                    // 开关的位置不画成关:服务端的值还不知道,画一个关着的开关等于说"正在记录"。
                    HistoryPause.Loading -> ToggleSettingRow(
                        position = position,
                        icon = Icons.Outlined.HistoryToggleOff,
                        title = stringResource(Res.string.settings_pause_history),
                        subtitle = stringResource(Res.string.settings_pause_history_subtitle),
                        checked = false,
                        onCheckedChange = {},
                        loading = true,
                    )

                    HistoryPause.Unavailable -> SettingRow(
                        position = position,
                        icon = Icons.Outlined.HistoryToggleOff,
                        title = stringResource(Res.string.settings_pause_history),
                        subtitle = stringResource(Res.string.settings_pause_history_failed),
                        onClick = onRetryHistoryPause,
                    )

                    is HistoryPause.Known -> ToggleSettingRow(
                        position = position,
                        icon = Icons.Outlined.HistoryToggleOff,
                        title = stringResource(Res.string.settings_pause_history),
                        subtitle = stringResource(Res.string.settings_pause_history_subtitle),
                        checked = pause.paused,
                        onCheckedChange = onHistoryPausedChange,
                    )
                }
            }
            row { position ->
                SettingRow(
                    position = position,
                    icon = Icons.Outlined.Block,
                    title = stringResource(Res.string.blacklist_title),
                    target = RowTarget.Page,
                    onClick = onOpenBlacklist,
                )
            }
            // 和上面几项一样是"谁能知道我在看什么":这一条要发给站外服务器,副标题把发的是什么
            // 说清楚 —— 一个只写"补全醒目留言"的开关,读者无从判断该不该关。
            row { position ->
                ToggleSettingRow(
                    position = position,
                    icon = Icons.Outlined.Forum,
                    title = stringResource(Res.string.settings_danmakus_archive),
                    subtitle = stringResource(Res.string.settings_danmakus_archive_subtitle),
                    checked = state.danmakusArchive,
                    onCheckedChange = onDanmakusArchiveChange,
                )
            }
            row { position ->
                SettingRow(
                    position = position,
                    icon = Icons.Outlined.Block,
                    title = stringResource(Res.string.settings_block_rules),
                    subtitle = stringResource(Res.string.settings_block_rules_subtitle),
                    value = state.contentBlockRules.size.takeIf { it > 0 }?.let {
                        stringResource(Res.string.settings_block_rules_count, it)
                    },
                    target = RowTarget.Page,
                    onClick = onOpenBlockRules,
                )
            }
            // 一个都没排除过的人不需要看见这个概念。
            if (state.excludedFeedUps.isNotEmpty()) {
                row { position ->
                    SettingRow(
                        position = position,
                        icon = Icons.Outlined.PersonOff,
                        title = stringResource(Res.string.settings_feed_excluded),
                        value = stringResource(Res.string.settings_feed_excluded_count, state.excludedFeedUps.size),
                        target = RowTarget.Page,
                        onClick = onOpenExcludedFeed,
                    )
                }
            }
        }
    }
}

/**
 * 排除的 UP 主。**逐个撤销**,不是只给一个"全部清空"。
 *
 * 排除是在动态流里单条操作的,那条动态被隐藏之后界面上再没有它的痕迹,撤销无处可落 ——
 * 于是从前只能整份清空,而那要求用户为了找回一个人放弃其余全部。名字在排除那一刻就记下了
 * (见 SettingsStore.excludedFeedMids),这一页才画得出来。
 */
@Composable
fun ExcludedFeedPage(
    state: SettingsUiState,
    onRestore: (Long) -> Unit,
    onClearAll: () -> Unit,
    onBack: (() -> Unit)?,
) {
    var confirmingClearAll by rememberSaveable { mutableStateOf(false) }
    SettingsSubPage(stringResource(Res.string.settings_feed_excluded), onBack, state.loaded) {
        SettingsGroup {
            state.excludedFeedUps.forEach { up ->
                row { position ->
                    StaticSettingRow(
                        position = position,
                        icon = Icons.Outlined.Person,
                        title = up.name,
                        trailing = {
                            TextButton(onClick = { onRestore(up.mid) }) {
                                Text(stringResource(Res.string.settings_feed_excluded_restore))
                            }
                        },
                    )
                }
            }
            if (state.excludedFeedUps.size > 1) {
                row { position ->
                    SettingRow(
                        position = position,
                        icon = Icons.Outlined.Restore,
                        title = stringResource(Res.string.settings_feed_clear_excluded),
                        onClick = { confirmingClearAll = true },
                    )
                }
            }
        }
    }
    // **一次点掉整份名单要确认。** 逐条恢复是可逆的(把人再排除一次就行),整份清空不是:
    // 名字只在排除那一刻记下来(SettingsStore.excludedFeedMids),清掉之后这一页就没有内容,
    // 想找回其中某一个得等他下次发投稿才看得见。形状照登出那个框:标题是动作名,一句说明,
    // 确认按钮再写一遍动作名。
    if (confirmingClearAll) {
        AlertDialog(
            onDismissRequest = { confirmingClearAll = false },
            title = { Text(stringResource(Res.string.settings_feed_clear_excluded)) },
            text = { Text(stringResource(Res.string.settings_feed_clear_excluded_message)) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        confirmingClearAll = false
                        onClearAll()
                    },
                ) { Text(stringResource(Res.string.settings_feed_clear_excluded)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClearAll = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

/**
 * 本地内容屏蔽规则：按 UP 主或关键词（匹配标题与 UP 名）在关注动态与搜索结果中实时过滤。
 */
@Composable
fun BlockRulesPage(
    state: SettingsUiState,
    onAddKeyword: (String) -> Unit,
    onRemoveRule: (ContentBlockRule.Kind, String) -> Unit,
    onClearAll: () -> Unit,
    onBack: (() -> Unit)?,
) {
    var keywordInput by rememberSaveable { mutableStateOf("") }
    var confirmingClearAll by rememberSaveable { mutableStateOf(false) }
    val upPrefix = stringResource(Res.string.settings_block_rule_up_prefix)
    SettingsSubPage(stringResource(Res.string.settings_block_rules), onBack, state.loaded) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        ) {
            OutlinedTextField(
                value = keywordInput,
                onValueChange = { keywordInput = it },
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.settings_block_keyword_hint)) },
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = keywordInput.isNotBlank(),
                onClick = {
                    val kw = keywordInput.trim()
                    if (kw.isNotEmpty()) {
                        onAddKeyword(kw)
                        keywordInput = ""
                    }
                },
            ) {
                Text(stringResource(Res.string.settings_block_keyword_add))
            }
        }
        if (state.contentBlockRules.isEmpty()) {
            Text(
                text = stringResource(Res.string.settings_block_rules_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
            )
        } else {
            SettingsGroup {
                state.contentBlockRules.forEach { rule ->
                    val title = if (rule.kind == ContentBlockRule.Kind.Up) {
                        "$upPrefix  ${rule.label}"
                    } else {
                        rule.label
                    }
                    val icon = if (rule.kind == ContentBlockRule.Kind.Up) {
                        Icons.Outlined.Person
                    } else {
                        Icons.Outlined.Search
                    }
                    row { position ->
                        StaticSettingRow(
                            position = position,
                            icon = icon,
                            title = title,
                            trailing = {
                                TextButton(onClick = { onRemoveRule(rule.kind, rule.value) }) {
                                    Text(stringResource(Res.string.settings_block_rule_remove))
                                }
                            },
                        )
                    }
                }
                if (state.contentBlockRules.size > 1) {
                    row { position ->
                        SettingRow(
                            position = position,
                            icon = Icons.Outlined.Restore,
                            title = stringResource(Res.string.settings_block_rules_clear),
                            onClick = { confirmingClearAll = true },
                        )
                    }
                }
            }
        }
    }
    if (confirmingClearAll) {
        AlertDialog(
            onDismissRequest = { confirmingClearAll = false },
            title = { Text(stringResource(Res.string.settings_block_rules_clear)) },
            text = { Text(stringResource(Res.string.settings_block_rules_clear_message)) },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    onClick = {
                        confirmingClearAll = false
                        onClearAll()
                    },
                ) { Text(stringResource(Res.string.settings_block_rules_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClearAll = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

/**
 * @param updater 应用内更新,不做的平台为 null。状态归它,不归这一页:在这里开始的下载,
 *   离开设置页再回来还是那一份,开屏弹窗读的也是它。
 */
@Composable
fun AboutSettingsPage(
    updater: AppUpdateService?,
    onOpenGithub: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    var dialogUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    SettingsSubPage(stringResource(Res.string.settings_section_about), onBack) {
        AboutCard(
            update = updater?.status,
            // 用户点的这一次,失败要说出来,不走静默。
            onCheckUpdate = { updater?.let { scope.launch { it.check() } } },
            onOpenUpdate = { dialogUpdate = it },
        )
        Spacer(modifier = Modifier.height(Spacing.Tight))
        SettingsGroup {
            row { position ->
                StaticSettingRow(
                    position = position,
                    icon = Icons.Outlined.Info,
                    title = stringResource(Res.string.settings_license),
                    trailing = {
                        Text(
                            text = "GPL-3.0-or-later",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
            row { position ->
                SettingRow(
                    position = position,
                    icon = Icons.Outlined.Code,
                    title = stringResource(Res.string.settings_github),
                    target = RowTarget.External,
                    onClick = onOpenGithub,
                )
            }
        }
    }
    val shown = dialogUpdate
    if (updater != null && shown != null) {
        UpdateDialog(updater = updater, update = shown, onDismiss = { dialogUpdate = null })
    }
}

/**
 * 关于页身份与更新卡片:承载版本号、更新状态及更新操作按钮,下方配合 [SettingsGroup] 分段块陈列许可证与项目仓库。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AboutCard(
    /** 不支持应用内更新的平台为 null,不给状态行,也不给更新按钮。 */
    update: UpdateStatus?,
    onCheckUpdate: () -> Unit,
    onOpenUpdate: (AvailableUpdate) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.largeIncreased,
        color = colors.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable)
            .padding(top = Spacing.Tight),
    ) {
        Column(modifier = Modifier.padding(Spacing.Comfortable)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(AboutBadgeSize)
                        .clip(MaterialTheme.shapes.medium)
                        .background(colors.primaryContainer),
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.onPrimaryContainer)
                }
                Spacer(modifier = Modifier.width(Spacing.Comfortable))
                Column(modifier = Modifier.weight(1f)) {
                    Text(AppName, style = MaterialTheme.typography.titleLargeEmphasized)
                    Text(
                        text = "${stringResource(Res.string.settings_version)} " +
                            "${AppBuild.versionName} (${AppBuild.applicationId})",
                        style = MaterialTheme.typography.bodyMedium.tabular,
                        color = colors.onSurfaceVariant,
                    )
                    if (update != null) UpdateStatusLine(update)
                }
            }
            if (update != null) {
                Spacer(modifier = Modifier.height(Spacing.Cozy))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                ) {
                    UpdateRow(
                        status = update,
                        onCheck = onCheckUpdate,
                        onOpen = onOpenUpdate,
                    )
                }
            }
        }
    }
}

/** 品牌名,不随界面语言翻译。 */
private const val AppName = "Bilby"

/** 卡片左上角图标块。 */
private val AboutBadgeSize = 48.dp

private enum class TimeEditTarget { Start, End }

private sealed interface PendingNightRestrictionAction {
    data object DisableMaster : PendingNightRestrictionAction
    data class SetRuleEnabled(val ruleId: String, val enabled: Boolean) : PendingNightRestrictionAction
    data class UpsertRule(val rule: ScheduleRule) : PendingNightRestrictionAction
    data class DeleteRule(val ruleId: String) : PendingNightRestrictionAction
}

/**
 * 定时白名单设置页：总开关、恢复当前时段/立即进入、多时段规则列表与规则编辑器。
 *
 * 仅拦截「放宽当前生效限制」的操作（生效中关总开关、关闭/删除/缩短当前生效规则、
 * 向当前生效非空规则加 UP 主）；编辑未生效规则或新建规则无需长按确认。
 */
@Composable
fun NightModeSettingsPage(
    state: SettingsUiState,
    followPicker: NightFollowPickerState,
    onLoadFollowings: () -> Unit,
    onLoadMoreFollowings: () -> Unit,
    onSearchFollowings: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onUpsertRule: (ScheduleRule) -> Unit,
    onRuleEnabledChange: (String, Boolean) -> Unit,
    onDeleteRule: (String) -> Unit,
    onEngageRuleManually: (String) -> Unit,
    onClearOverride: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val prefs = state.nightMode
    var unlocked by rememberSaveable { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(prefs) {
        nowMillis = System.currentTimeMillis()
        if (!prefs.enabled && prefs.manualUntilEpochMillis <= 0L) return@LaunchedEffect
        while (true) {
            delay(15_000L)
            nowMillis = System.currentTimeMillis()
        }
    }

    val status = evaluateNightMode(prefs, nowMillis)
    val activeRuleIds = remember(status) {
        (status as? NightWindowStatus.Active)?.activeRules?.mapTo(HashSet()) { it.id }.orEmpty()
    }

    var editingRule by remember { mutableStateOf<ScheduleRule?>(null) }
    var editingIsNew by rememberSaveable { mutableStateOf(false) }
    var showRulePicker by rememberSaveable { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PendingNightRestrictionAction?>(null) }

    fun executeAction(action: PendingNightRestrictionAction) {
        when (action) {
            PendingNightRestrictionAction.DisableMaster -> onEnabledChange(false)
            is PendingNightRestrictionAction.SetRuleEnabled -> onRuleEnabledChange(action.ruleId, action.enabled)
            is PendingNightRestrictionAction.UpsertRule -> onUpsertRule(action.rule)
            is PendingNightRestrictionAction.DeleteRule -> onDeleteRule(action.ruleId)
        }
    }

    fun runOrGate(action: PendingNightRestrictionAction, nextPrefs: NightModePrefs) {
        val now = System.currentTimeMillis()
        if (!unlocked && relaxesActiveRestriction(prefs, nextPrefs, now)) {
            pendingAction = action
        } else {
            executeAction(action)
        }
    }

    SettingsSubPage(stringResource(Res.string.settings_section_night_mode), onBack, state.loaded) {
        NightModeToggleGroup(
            prefs = prefs,
            status = status,
            onEnabledChange = { enabled ->
                if (enabled) {
                    onEnabledChange(true)
                } else {
                    runOrGate(
                        action = PendingNightRestrictionAction.DisableMaster,
                        nextPrefs = prefs.copy(enabled = false, overrideUntilEpochMillis = 0L),
                    )
                }
            },
            onClearOverride = onClearOverride,
            onEnterNow = {
                when (prefs.rules.size) {
                    0 -> Unit
                    1 -> onEngageRuleManually(prefs.rules.first().id)
                    else -> showRulePicker = true
                }
            },
        )
        SettingsGroup(title = stringResource(Res.string.night_mode_schedule_group)) {
            prefs.rules.forEach { rule ->
                row { position ->
                    ScheduleRuleRow(
                        position = position,
                        rule = rule,
                        active = rule.id in activeRuleIds,
                        onClick = {
                            editingRule = rule
                            editingIsNew = false
                        },
                        onEnabledChange = { nextEnabled ->
                            if (nextEnabled) {
                                onRuleEnabledChange(rule.id, true)
                            } else {
                                val nextPrefs = prefs.copy(
                                    rules = prefs.rules.map { if (it.id == rule.id) it.copy(enabled = false) else it },
                                )
                                runOrGate(
                                    action = PendingNightRestrictionAction.SetRuleEnabled(rule.id, false),
                                    nextPrefs = nextPrefs,
                                )
                            }
                        },
                    )
                }
            }
            row { position ->
                SettingRow(
                    position = position,
                    icon = Icons.Outlined.Add,
                    title = stringResource(Res.string.night_mode_add_rule),
                    onClick = {
                        editingRule = ScheduleRule(
                            id = UUID.randomUUID().toString(),
                            name = "",
                            enabled = true,
                            startMinuteOfDay = DEFAULT_NIGHT_START_MINUTE,
                            endMinuteOfDay = DEFAULT_NIGHT_END_MINUTE,
                        )
                        editingIsNew = true
                    },
                )
            }
        }
    }

    editingRule?.let { initial ->
        key(initial.id, editingIsNew) {
            ScheduleRuleEditorSheet(
                initialRule = initial,
                isNew = editingIsNew,
                followPicker = followPicker,
                onLoadFollowings = onLoadFollowings,
                onLoadMoreFollowings = onLoadMoreFollowings,
                onSearchFollowings = onSearchFollowings,
                onSave = { savedRule ->
                    editingRule = null
                    val normalized = savedRule.normalized()
                    if (editingIsNew) {
                        onUpsertRule(normalized)
                    } else {
                        val nextRules = prefs.rules.map { if (it.id == normalized.id) normalized else it }
                        val nextManualUntil = if (prefs.manualRuleId == normalized.id && prefs.manualUntilEpochMillis > System.currentTimeMillis()) {
                            computeNextNightEndEpochMillis(normalized.endMinuteOfDay, System.currentTimeMillis())
                        } else {
                            prefs.manualUntilEpochMillis
                        }
                        runOrGate(
                            action = PendingNightRestrictionAction.UpsertRule(normalized),
                            nextPrefs = prefs.copy(rules = nextRules, manualUntilEpochMillis = nextManualUntil),
                        )
                    }
                },
                onDelete = if (editingIsNew) null else {
                    {
                        val targetId = initial.id
                        editingRule = null
                        val clearingManual = prefs.manualRuleId == targetId
                        val nextPrefs = prefs.copy(
                            rules = prefs.rules.filterNot { it.id == targetId },
                            manualRuleId = if (clearingManual) null else prefs.manualRuleId,
                            manualUntilEpochMillis = if (clearingManual) 0L else prefs.manualUntilEpochMillis,
                        )
                        runOrGate(
                            action = PendingNightRestrictionAction.DeleteRule(targetId),
                            nextPrefs = nextPrefs,
                        )
                    }
                },
                onDismiss = { editingRule = null },
            )
        }
    }

    if (showRulePicker) {
        ScheduleRulePickerSheet(
            rules = prefs.rules,
            onSelect = { rule ->
                showRulePicker = false
                onEngageRuleManually(rule.id)
            },
            onDismiss = { showRulePicker = false },
        )
    }

    pendingAction?.let { action ->
        NightExitHoldSheet(
            title = stringResource(Res.string.night_mode_hold_settings_title),
            onCompleted = {
                unlocked = true
                executeAction(action)
                pendingAction = null
            },
            onDismiss = { pendingAction = null },
        )
    }
}

@Composable
private fun NightModeToggleGroup(
    prefs: NightModePrefs,
    status: NightWindowStatus,
    onEnabledChange: (Boolean) -> Unit,
    onClearOverride: () -> Unit,
    onEnterNow: () -> Unit,
) {
    SettingsGroup {
        row { position ->
            ToggleSettingRow(
                position = position,
                icon = Icons.Outlined.Schedule,
                title = stringResource(Res.string.night_mode_enable),
                subtitle = stringResource(Res.string.night_mode_enable_subtitle),
                checked = prefs.enabled,
                onCheckedChange = onEnabledChange,
            )
        }
        when (status) {
            is NightWindowStatus.Overridden -> {
                row { position ->
                    SettingRow(
                        position = position,
                        icon = Icons.Outlined.Restore,
                        title = stringResource(Res.string.night_mode_resume_now),
                        onClick = onClearOverride,
                    )
                }
            }

            NightWindowStatus.Inactive -> if (prefs.rules.isNotEmpty()) {
                row { position ->
                    SettingRow(
                        position = position,
                        icon = Icons.Outlined.PlayArrow,
                        title = stringResource(Res.string.night_mode_enter_action),
                        onClick = onEnterNow,
                    )
                }
            }

            is NightWindowStatus.Active -> Unit
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ScheduleRuleRow(
    position: RowPosition,
    rule: ScheduleRule,
    active: Boolean,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val range = formatNightRange(rule.startMinuteOfDay, rule.endMinuteOfDay)
    val daysSummary = formatDaysSummary(rule.daysOfWeek)
    val upCountLabel = stringResource(Res.string.night_mode_rule_up_count, rule.allowedUps.size)
    val activeBadge = if (active) stringResource(Res.string.night_mode_rule_active_badge) else null
    val titleText = rule.name.ifBlank { range }
    val subtitleParts = buildList {
        if (activeBadge != null) add(activeBadge)
        if (rule.name.isNotBlank()) add(range)
        add(daysSummary)
        add(upCountLabel)
    }
    SettingsSegmentedRow(
        onClick = onClick,
        shape = segmentedRowShape(position),
        containerColor = colors.surfaceContainer,
        supportingContent = {
            Text(
                text = subtitleParts.joinToString(MetaSeparator),
                style = MaterialTheme.typography.bodySmall.tabular,
                color = if (active) colors.primary else colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Switch(
                checked = rule.enabled,
                onCheckedChange = onEnabledChange,
            )
        },
        modifier = Modifier.semantics { role = Role.Button },
    ) {
        Text(
            text = titleText,
            style = MaterialTheme.typography.bodyLarge.tabular,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ScheduleRuleEditorSheet(
    initialRule: ScheduleRule,
    isNew: Boolean,
    followPicker: NightFollowPickerState,
    onLoadFollowings: () -> Unit,
    onLoadMoreFollowings: () -> Unit,
    onSearchFollowings: (String) -> Unit,
    onSave: (ScheduleRule) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialRule.name) }
    var startMinute by rememberSaveable { mutableIntStateOf(initialRule.startMinuteOfDay) }
    var endMinute by rememberSaveable { mutableIntStateOf(initialRule.endMinuteOfDay) }
    var daysOfWeek by remember { mutableStateOf(initialRule.daysOfWeek) }
    var allowedUps by remember { mutableStateOf(initialRule.allowedUps) }
    var editingTimeTarget by rememberSaveable { mutableStateOf<TimeEditTarget?>(null) }
    var showUpPicker by rememberSaveable { mutableStateOf(false) }

    val colors = MaterialTheme.colorScheme
    val dayShortLabels = listOf(
        1 to stringResource(Res.string.night_mode_day_short_mon),
        2 to stringResource(Res.string.night_mode_day_short_tue),
        3 to stringResource(Res.string.night_mode_day_short_wed),
        4 to stringResource(Res.string.night_mode_day_short_thu),
        5 to stringResource(Res.string.night_mode_day_short_fri),
        6 to stringResource(Res.string.night_mode_day_short_sat),
        7 to stringResource(Res.string.night_mode_day_short_sun),
    )

    PaneSheet(
        onDismissRequest = onDismiss,
        title = stringResource(if (isNew) Res.string.night_mode_add_rule else Res.string.night_mode_edit_rule),
        skipPartiallyExpanded = true,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                if (onDelete != null) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                    ) {
                        Text(stringResource(Res.string.night_mode_rule_delete))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.action_cancel))
                }
                Button(
                    onClick = {
                        onSave(
                            initialRule.copy(
                                name = name,
                                startMinuteOfDay = startMinute,
                                endMinuteOfDay = endMinute,
                                daysOfWeek = daysOfWeek,
                                allowedUps = allowedUps,
                            ),
                        )
                    },
                ) {
                    Text(stringResource(Res.string.night_mode_rule_save))
                }
            }
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= 24) name = it },
                singleLine = true,
                label = { Text(stringResource(Res.string.night_mode_rule_name_label)) },
                placeholder = { Text(stringResource(Res.string.night_mode_rule_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SettingRow(
                    position = RowPosition(0, 2),
                    icon = Icons.Outlined.Schedule,
                    title = stringResource(Res.string.night_mode_start_time),
                    value = formatMinuteOfDay(startMinute),
                    valueAtEnd = true,
                    onClick = { editingTimeTarget = TimeEditTarget.Start },
                )
                SettingRow(
                    position = RowPosition(1, 2),
                    icon = Icons.Outlined.AccessTime,
                    title = stringResource(Res.string.night_mode_end_time),
                    value = formatMinuteOfDay(endMinute),
                    valueAtEnd = true,
                    onClick = { editingTimeTarget = TimeEditTarget.End },
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(Res.string.night_mode_rule_days),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = colors.onSurface,
                    )
                    Text(
                        text = formatDaysSummary(daysOfWeek),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    dayShortLabels.forEach { (isoDay, label) ->
                        val selected = isoDay in daysOfWeek
                        Surface(
                            selected = selected,
                            onClick = {
                                val next = if (selected) daysOfWeek - isoDay else daysOfWeek + isoDay
                                daysOfWeek = if (next.size == 7) emptySet() else next
                            },
                            shape = CircleShape,
                            color = if (selected) colors.secondaryContainer else colors.surfaceContainer,
                            contentColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(Res.string.night_mode_allowed_ups),
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = colors.onSurface,
                    )
                    TextButton(onClick = { showUpPicker = true }) {
                        Icon(
                            imageVector = Icons.Outlined.PersonAdd,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.IconInline),
                        )
                        Spacer(Modifier.width(Spacing.Hair))
                        Text(stringResource(Res.string.night_mode_add_up))
                    }
                }
                if (allowedUps.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.night_mode_empty_ups),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacing.Hair),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        allowedUps.forEachIndexed { index, up ->
                            AllowedUpRow(
                                position = RowPosition(index, allowedUps.size),
                                up = up,
                                onRemove = { allowedUps = allowedUps.filterNot { it.mid == up.mid } },
                            )
                        }
                    }
                }
            }
        }
    }

    editingTimeTarget?.let { target ->
        key(target) {
            val initialMinute = when (target) {
                TimeEditTarget.Start -> startMinute
                TimeEditTarget.End -> endMinute
            }
            val dialogTitle = stringResource(
                when (target) {
                    TimeEditTarget.Start -> Res.string.night_mode_start_time
                    TimeEditTarget.End -> Res.string.night_mode_end_time
                },
            )
            NightTimePickerDialog(
                title = dialogTitle,
                initialMinuteOfDay = initialMinute,
                onConfirm = { pickedMinute ->
                    when (target) {
                        TimeEditTarget.Start -> startMinute = pickedMinute
                        TimeEditTarget.End -> endMinute = pickedMinute
                    }
                    editingTimeTarget = null
                },
                onDismiss = { editingTimeTarget = null },
            )
        }
    }

    if (showUpPicker) {
        NightFollowPickerSheet(
            followPicker = followPicker,
            initialAllowed = allowedUps,
            onLoadFollowings = onLoadFollowings,
            onLoadMoreFollowings = onLoadMoreFollowings,
            onSearchFollowings = onSearchFollowings,
            onConfirm = { selectedUps ->
                allowedUps = selectedUps
                showUpPicker = false
            },
            onDismiss = { showUpPicker = false },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScheduleRulePickerSheet(
    rules: List<ScheduleRule>,
    onSelect: (ScheduleRule) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    PaneSheet(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.night_mode_pick_rule_title),
        skipPartiallyExpanded = true,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        ) {
            itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
                val range = formatNightRange(rule.startMinuteOfDay, rule.endMinuteOfDay)
                val upCount = stringResource(Res.string.night_mode_rule_up_count, rule.allowedUps.size)
                val title = rule.name.ifBlank { range }
                val subtitle = buildList {
                    if (rule.name.isNotBlank()) add(range)
                    add(upCount)
                }.joinToString(MetaSeparator)
                SettingsSegmentedRow(
                    onClick = { onSelect(rule) },
                    shape = segmentedRowShape(RowPosition(index, rules.size)),
                    containerColor = colors.surfaceContainer,
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall.tabular,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.semantics { role = Role.Button },
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge.tabular,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun formatDaysSummary(daysOfWeek: Set<Int>): String {
    val normalized = daysOfWeek.filter { it in 1..7 }.toSet()
    return when {
        normalized.isEmpty() || normalized.size == 7 -> stringResource(Res.string.night_mode_days_daily)
        normalized == setOf(1, 2, 3, 4, 5) -> stringResource(Res.string.night_mode_days_weekdays)
        normalized == setOf(6, 7) -> stringResource(Res.string.night_mode_days_weekends)
        else -> {
            val sep = stringResource(Res.string.night_mode_day_sep)
            val labels = mapOf(
                1 to stringResource(Res.string.night_mode_day_mon),
                2 to stringResource(Res.string.night_mode_day_tue),
                3 to stringResource(Res.string.night_mode_day_wed),
                4 to stringResource(Res.string.night_mode_day_thu),
                5 to stringResource(Res.string.night_mode_day_fri),
                6 to stringResource(Res.string.night_mode_day_sat),
                7 to stringResource(Res.string.night_mode_day_sun),
            )
            normalized.sorted().mapNotNull { labels[it] }.joinToString(sep)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AllowedUpRow(
    position: RowPosition,
    up: WhitelistedUp,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    SettingsSegmentedRow(
        shape = segmentedRowShape(position),
        containerColor = colors.surfaceContainer,
        leadingContent = {
            if (up.faceUrl.isNotEmpty()) {
                Avatar(url = up.faceUrl, size = Dimens.AvatarRow)
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(Dimens.AvatarRow),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        },
        trailingContent = {
            TextButton(onClick = onRemove) {
                Text(stringResource(Res.string.night_mode_remove_up))
            }
        },
    ) {
        Text(
            text = up.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NightTimePickerDialog(
    title: String,
    initialMinuteOfDay: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val clamped = initialMinuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    val pickerState = rememberTimePickerState(
        initialHour = clamped / 60,
        initialMinute = clamped % 60,
        is24Hour = true,
    )
    val compactHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < 480.dp
    var showDial by rememberSaveable { mutableStateOf(!compactHeight) }

    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = Spacing.Comfortable),
            )
        },
        modeToggleButton = {
            val toggleLabel = stringResource(
                if (showDial) Res.string.night_mode_time_input else Res.string.night_mode_time_dial,
            )
            BilbyTooltip(text = toggleLabel) {
                IconButton(onClick = { showDial = !showDial }) {
                    Icon(
                        imageVector = if (showDial) Icons.Outlined.Keyboard else Icons.Outlined.Schedule,
                        contentDescription = toggleLabel,
                    )
                }
            }
        },
        confirmButton = {
            val inputValid = showDial || (pickerState.hourInput in 0..23 && pickerState.minuteInput in 0..59)
            TextButton(
                enabled = inputValid,
                onClick = { onConfirm(pickerState.hour * 60 + pickerState.minute) },
            ) {
                Text(stringResource(Res.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    ) {
        Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (showDial) {
                TimePicker(state = pickerState)
            } else {
                TimeInput(state = pickerState)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun NightFollowPickerSheet(
    followPicker: NightFollowPickerState,
    initialAllowed: List<WhitelistedUp>,
    onLoadFollowings: () -> Unit,
    onLoadMoreFollowings: () -> Unit,
    onSearchFollowings: (String) -> Unit,
    onConfirm: (List<WhitelistedUp>) -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onLoadFollowings() }
    var draftSelected by remember { mutableStateOf(initialAllowed.distinctBy { it.mid }) }
    val selectedMids = remember(draftSelected) { draftSelected.mapTo(HashSet()) { it.mid } }
    var queryInput by rememberSaveable(followPicker.query) { mutableStateOf(followPicker.query) }
    val listState = rememberLazyListState()
    PrefetchNearEnd(
        state = listState,
        canLoad = followPicker.hasMore &&
            !followPicker.loading &&
            !followPicker.appending &&
            followPicker.error == null,
        onLoadMore = onLoadMoreFollowings,
    )

    PaneSheet(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.night_mode_picker_title),
        skipPartiallyExpanded = true,
        subheader = {
            SearchField(
                value = queryInput,
                onValueChange = { text ->
                    queryInput = text
                    if (text.isEmpty()) onSearchFollowings("")
                },
                placeholder = stringResource(Res.string.followings_search_hint),
                onSearch = { onSearchFollowings(queryInput) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            )
        },
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.action_cancel))
                }
                Button(onClick = { onConfirm(draftSelected) }) {
                    Text(
                        text = if (draftSelected.isNotEmpty()) {
                            stringResource(Res.string.action_confirm_count, draftSelected.size)
                        } else {
                            stringResource(Res.string.action_confirm)
                        },
                        style = LocalTextStyle.current.tabular,
                    )
                }
            }
        },
    ) {
        val colors = MaterialTheme.colorScheme
        when {
            followPicker.loading && followPicker.items.isEmpty() -> {
                ListSkeleton(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    row = { ListItemPersonSkeleton() },
                )
            }

            followPicker.error != null && followPicker.items.isEmpty() -> {
                FullScreenError(
                    message = stringResource(followPicker.error),
                    onRetry = onLoadMoreFollowings,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                )
            }

            followPicker.items.isEmpty() -> {
                EmptyState(
                    message = stringResource(
                        if (followPicker.query.isNotBlank()) {
                            Res.string.followings_search_empty
                        } else {
                            Res.string.followings_empty
                        },
                    ),
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                )
            }

            else -> {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                ) {
                    val count = followPicker.items.size
                    itemsIndexed(followPicker.items, key = { _, up -> up.mid }) { index, up ->
                        val checked = up.mid in selectedMids
                        SettingsSegmentedRow(
                            checked = checked,
                            onCheckedChange = { nextChecked ->
                                draftSelected = if (nextChecked) {
                                    if (up.mid in selectedMids) {
                                        draftSelected
                                    } else {
                                        draftSelected + WhitelistedUp(
                                            mid = up.mid,
                                            name = up.name,
                                            faceUrl = up.faceUrl.toHttpsUrl(),
                                        )
                                    }
                                } else {
                                    draftSelected.filterNot { it.mid == up.mid }
                                }
                            },
                            shape = segmentedRowShape(RowPosition(index, count)),
                            containerColor = if (checked) colors.secondaryContainer else colors.surfaceContainer,
                            contentColor = if (checked) colors.onSecondaryContainer else colors.onSurface,
                            leadingContentColor = if (checked) colors.onSecondaryContainer else colors.onSurfaceVariant,
                            trailingContentColor = if (checked) colors.onSecondaryContainer else colors.onSurfaceVariant,
                            leadingContent = { Avatar(url = up.faceUrl, size = Dimens.AvatarRow) },
                            trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                            modifier = Modifier.semantics { role = Role.Checkbox },
                        ) {
                            Text(
                                text = up.name,
                                style = if (checked) {
                                    MaterialTheme.typography.bodyLargeEmphasized
                                } else {
                                    MaterialTheme.typography.bodyLarge
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    item(key = "footer") {
                        ListFooter(
                            appending = followPicker.appending,
                            hasMore = followPicker.hasMore,
                            hasItems = followPicker.items.isNotEmpty(),
                            error = followPicker.error?.let { stringResource(it) },
                            onRetry = onLoadMoreFollowings,
                        )
                    }
                }
            }
        }
    }
}
