package dev.bilby.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AltRoute
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
import androidx.compose.material.icons.outlined.HistoryToggleOff
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MusicOff
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Start
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.bilby.stringResource
import dev.bilby.AppBuild
import dev.bilby.resources.*
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.data.CodecPreference
import dev.bilby.data.LlmConfig
import dev.bilby.data.SettingsStore
import dev.bilby.data.SponsorBlockPrefs
import dev.bilby.ui.update.UpdateDialog
import dev.bilby.update.AppUpdateService
import dev.bilby.update.AvailableUpdate
import dev.bilby.update.UpdateStatus
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import dev.bilby.player.videoQualityLabel
import dev.bilby.player.audioQualityLabel
import dev.bilby.player.DEFAULT_AUDIO_QUALITY_OPTIONS
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.player.formatSpeed
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.video.CATEGORY_DESCRIPTIONS
import dev.bilby.ui.video.CATEGORY_GROUPS
import dev.bilby.ui.video.CATEGORY_LABELS
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import org.jetbrains.compose.resources.StringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.bilby.data.AppearancePrefs
import dev.bilby.data.ThemeMode
import dev.bilby.ui.AppLanguage
import dev.bilby.ui.LocalSystemActions
import dev.bilby.ui.theme.dynamicColorSchemes
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.LocalIsDarkTheme
import dev.bilby.ui.theme.PaletteSchemes
import dev.bilby.ui.theme.ThemePalette
import dev.bilby.ui.theme.dynamicColorAvailable
import kotlin.math.abs

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

    /** SponsorBlock 的九个分类。从播放页再降一层,理由见 [PlaybackSettingsPage]。 */
    SponsorCategories,

    /** 排除的 UP 主名单。从隐私页再降一层,理由见 [ExcludedFeedPage]。 */
    ExcludedFeed,
    Agent,
    Privacy,
    About,
}

/**
 * 子页的外壳:顶栏、返回、可读宽度、滚动。抽出来是因为七页一模一样,而漏掉其中一样
 * (比如某一页忘了限宽)在平板上一眼看得出来。
 *
 * 分组由各页自己套 [SettingsGroup]。一页只有一组时不给组标题,节名就是顶栏标题。
 */
@Composable
private fun SettingsSubPage(
    title: String,
    /** null 时不画返回:两栏的右栏里,这一页不是"进来的",返回在左栏的顶栏上。 */
    onBack: (() -> Unit)?,
    /**
     * 值到齐了没有。**没到齐就一行都不画**,不是画一个默认值等着改 —— 见
     * [SettingsUiState.loaded]。顶栏照画:标题和返回不依赖任何一项设置,先出来才不会闪。
     */
    ready: Boolean = true,
    content: @Composable () -> Unit,
) {
    // pinned 而不是 enterAlways:顶栏留着不动,只在内容滚起来之后换一档容器色。各页的内容
    // 都短,滚起来的那一下顶栏跟着一起走反而像页面跳了一下。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        topBar = { BilbyTopBar(title = title, onBack = onBack, scrollBehavior = scrollBehavior) },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { insets ->
        AdaptiveContent(
            modifier = Modifier.fillMaxSize().padScaffoldExceptBottom(insets),
            maxWidth = Breakpoints.ReadableWidth,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
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
    onFastForwardSpeedChange: (Float) -> Unit,
    onAutoNextChange: (Boolean) -> Unit,
    onWifiAudioChange: (Int) -> Unit,
    onMeteredAudioChange: (Int) -> Unit,
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
                ToggleSettingRow(
                    position = position,
                    icon = Icons.Outlined.SkipNext,
                    title = stringResource(Res.string.settings_auto_next),
                    // 说明这一行为什么存在:队列不是用户建的,自动前进因此是一个没人点过头的默认。
                    subtitle = stringResource(Res.string.settings_auto_next_subtitle),
                    checked = state.autoNext,
                    onCheckedChange = onAutoNextChange,
                )
            }
            row { position ->
                ChoiceRow(
                    position = position,
                    icon = Icons.Outlined.Speed,
                    title = stringResource(Res.string.settings_fast_forward_speed),
                    // 长按加速没有播放页入口 —— 它是"长按的时候有多快"这条规则本身,不是看的时候
                    // 顺手调的东西,所以按 §2.8 的判据("怎么做")放这里。
                    subtitle = stringResource(Res.string.settings_fast_forward_speed_subtitle),
                    options = SettingsStore.FAST_FORWARD_SPEEDS,
                    selected = SettingsStore.FAST_FORWARD_SPEEDS.minByOrNull { abs(it - state.fastForwardSpeed) },
                    label = { formatSpeed(it) },
                    onChange = onFastForwardSpeedChange,
                    valueAtEnd = true,
                )
            }
        }
        // 四行默认值两两成对,差别在网络,所以图标标网络,画质还是音质由标题说。
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
    "filler" to Icons.Outlined.AltRoute,
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

/**
 * 明暗三档,连体按钮组。写法与播放器设置面板的 ConnectedChoices 相同:各格等宽,
 * 内边距收小,读屏按单选念。
 *
 * 用默认的实心配色,不换 tonal:风格指南 §2.1 嫌实心太响说的是压在几十条评论上的排序,
 * 这里一页只有这一组,选中哪一档正需要一眼看出来。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThemeModeRow(position: RowPosition, selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = ThemeMode.entries
    ControlSettingRow(
        position = position,
        icon = Icons.Outlined.DarkMode,
        title = stringResource(Res.string.settings_theme_mode),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = Modifier.fillMaxWidth().selectableGroup(),
        ) {
            options.forEachIndexed { index, option ->
                ToggleButton(
                    checked = option == selected,
                    onCheckedChange = { onSelect(option) },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    // 默认左右各 16dp,窄屏上三格平分之后「跟随系统」放不下。
                    contentPadding = PaddingValues(horizontal = Spacing.Tight),
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) {
                    Text(stringResource(option.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
@OptIn(ExperimentalLayoutApi::class)
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
        // **每行格数按行数平均分,不按"放得下几格"放满。** 放满时十格在手机上排成 6 + 4,第二行
        // 缺一截,读起来像漏了两格。先算这个宽度下最少要几行,再把十格平均摊到这几行上(手机
        // 5 + 5,宽屏一行 10);每行格数相同,两端对齐之后上下两行的色块也是对齐的。
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val fit = (maxWidth / SwatchTouchSize).toInt().coerceAtLeast(1)
            val rows = (swatches.size + fit - 1) / fit
            val perRow = (swatches.size + rows - 1) / rows
        FlowRow(
            maxItemsInEachRow = perRow,
            horizontalArrangement = if (rows > 1) Arrangement.SpaceBetween else Arrangement.Start,
            modifier = Modifier.fillMaxWidth().selectableGroup(),
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

/** 一格色块。色块本身不写名字,读屏靠 contentDescription 念出来。 */
@Composable
private fun ColorSwatch(
    color: Color,
    onColor: Color,
    label: String,
    selected: Boolean,
    idleIcon: ImageVector?,
    onClick: () -> Unit,
) {
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
            // 勾取这一套的 onPrimary:它就是为压在 primary 上设计的,不必再按亮度猜黑白。
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = onColor, modifier = Modifier.size(Dimens.IconInline))
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
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    SettingsSubPage(stringResource(Res.string.settings_section_agent), onBack, state.loaded) {
        SettingsGroup {
            row { position ->
                SettingRow(
                    position = position,
                    icon = Icons.Outlined.Api,
                    title = stringResource(Res.string.settings_agent_config),
                    // 只说配没配,不显示遮蔽后的 key:遮蔽只挡眼睛,截图和录屏挡不住。
                    subtitle = state.llm?.let { llm ->
                        if (llm.baseUrl.isBlank() || llm.apiKey.isBlank()) {
                            stringResource(Res.string.settings_not_configured)
                        } else {
                            "${llm.baseUrl}\n${llm.model.ifBlank { SettingsStore.DEFAULT_LLM_MODEL }}"
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
            onDismiss = { editing = false },
            // 保存也要收起对话框。原先只有取消收,按下保存之后配置存进去了、框还开着,
            // 读起来像没生效,于是再按一次。
            onConfirm = {
                editing = false
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
                TextButton(onClick = {
                    confirmingClearAll = false
                    onClearAll()
                }) { Text(stringResource(Res.string.settings_feed_clear_excluded)) }
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
            onOpenGithub = onOpenGithub,
        )
    }
    val shown = dialogUpdate
    if (updater != null && shown != null) {
        UpdateDialog(updater = updater, update = shown, onDismiss = { dialogUpdate = null })
    }
}

/**
 * 关于:一张卡片,版本、许可证、更新状态与两个动作放在一起。
 *
 * 原先是四行列表:版本和许可证各占一整行却不可点,更新的状态又要另读一行副标题。
 * 这一页没有要逐项调的东西,只有"这是哪个版本、有没有新的、源码在哪",一张卡片答完。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutCard(
    /** 不支持应用内更新的平台为 null,不给状态行,也不给更新按钮。 */
    update: UpdateStatus?,
    onCheckUpdate: () -> Unit,
    onOpenUpdate: (AvailableUpdate) -> Unit,
    onOpenGithub: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
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
                        .clip(CircleShape)
                        .background(colors.primaryContainer),
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = colors.onPrimaryContainer)
                }
                Spacer(modifier = Modifier.width(Spacing.Comfortable))
                Column(modifier = Modifier.weight(1f)) {
                    Text(AppName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${stringResource(Res.string.settings_version)} " +
                            "${AppBuild.versionName}(${AppBuild.applicationId})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        text = "${stringResource(Res.string.settings_license)} GPL-3.0-or-later",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    if (update != null) UpdateStatusLine(update)
                }
            }
            Spacer(modifier = Modifier.height(Spacing.Cozy))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                if (update != null) {
                    UpdateRow(
                        status = update,
                        onCheck = onCheckUpdate,
                        onOpen = onOpenUpdate,
                    )
                }
                // 这个按钮走出应用去浏览器,所以文字后面跟外链图标:与设置行的 RowTarget.External
                // 同一个约定,箭头说的是"应用里还有一页"。
                OutlinedButton(onClick = onOpenGithub) {
                    Icon(
                        imageVector = Icons.Outlined.Code,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(Res.string.settings_github))
                    Spacer(modifier = Modifier.width(Spacing.Hair))
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(ExternalMarkSize),
                    )
                }
            }
        }
    }
}

/** 品牌名,不随界面语言翻译。 */
private const val AppName = "Bilby"

/** 卡片左上那个圆。装下一个 24dp 图标,四周留出一圈底色。 */
private val AboutBadgeSize = 48.dp

/** 外链标记比按钮前置图标小一档,读作附注,不与前面的 Code 图标抢。 */
private val ExternalMarkSize = 16.dp
