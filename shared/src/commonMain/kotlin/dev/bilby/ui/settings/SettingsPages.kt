package dev.bilby.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.bilby.data.UpdateInfo
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.bilby.data.AppearancePrefs
import dev.bilby.data.ThemeMode
import dev.bilby.ui.AppLanguage
import dev.bilby.ui.LocalSystemActions
import dev.bilby.ui.theme.dynamicColorSchemes
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.ThemePalette
import dev.bilby.ui.theme.dynamicColorAvailable
import java.io.File
import kotlin.math.abs

/**
 * 设置的二级页面。**每一页都是一整页,不是一个展开块**:展开块要么把首页撑回原来的长度,
 * 要么在滚动中途改变高度,而这两件事正是重做前的样子。
 *
 * 一页一个 [SettingsSection],由 `Destinations.kt` 的 `SettingsPage` 带过来 —— 八个页面
 * 共用一个 NavKey,而不是八条路由:它们的差别只有"哪一页",没有各自的参数。
 */
enum class SettingsSection {
    Appearance,
    Playback,
    SponsorBlock,

    /** SponsorBlock 的九个分类。再降一层,理由见 [SponsorBlockSettingsPage]。 */
    SponsorCategories,

    /** 排除的 UP 主名单。从隐私页再降一层,理由见 [ExcludedFeedPage]。 */
    ExcludedFeed,
    Offline,
    Agent,
    Privacy,
    About,
}

/**
 * 子页的外壳:顶栏、返回、可读宽度、滚动、装行的那个容器。抽出来是因为八页一模一样,
 * 而漏掉其中一样(比如某一页忘了限宽)在平板上一眼看得出来。
 */
@Composable
private fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    /**
     * 值到齐了没有。**没到齐就一行都不画**,不是画一个默认值等着改 —— 见
     * [SettingsUiState.loaded]。顶栏照画:标题和返回不依赖任何一项设置,先出来才不会闪。
     */
    ready: Boolean = true,
    /**
     * 内容自己分组时传 false。**一页只有一节的时候节名就是顶栏标题**,所以默认把整页内容
     * 装进一个 [SettingsGroup];分类页那样内部还有 [GroupLabel] 分几组的,由它自己逐组套,
     * 否则组标题会被关到容器里面,读起来像是这一组的第一行。
     */
    grouped: Boolean = true,
    content: @Composable () -> Unit,
) {
    // pinned 而不是 enterAlways:顶栏留着不动,只在内容滚起来之后换一档容器色。八页的内容
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
                if (ready) {
                    if (grouped) SettingsGroup { content() } else content()
                }
            }
        }
    }
}

/** 播放。**弹幕不在这里** —— 它自成一页,否则这一页会像重做之前一样什么都往里塞。 */
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
    onBack: () -> Unit,
) {
    SettingsSubPage(stringResource(Res.string.settings_section_player), onBack, state.loaded) {
        ToggleSettingRow(
            title = stringResource(Res.string.settings_auto_next),
            // 说明这一行为什么存在:队列不是用户建的,自动前进因此是一个没人点过头的默认。
            subtitle = stringResource(Res.string.settings_auto_next_subtitle),
            checked = state.autoNext,
            onCheckedChange = onAutoNextChange,
        )
        // 两行是同一个值按网络分的两格。播放页里切画质默认只管那一次播放,下面那个开关打开
        // 之后才写进当下所在的那一格(见 AudioPlaybackService.setQuality)。
        ChoiceRow(
            title = stringResource(Res.string.settings_default_quality_wifi),
            options = SettingsStore.QUALITY_OPTIONS,
            selected = state.defaultQualityWifi,
            label = { videoQualityLabel(it) },
            onChange = onWifiQualityChange,
        )
        ChoiceRow(
            title = stringResource(Res.string.settings_default_quality_metered),
            // 判据是系统的流量计费标记,不是"是不是 WiFi" —— 手机热点和按量计费的 WiFi 都算
            // 计费网络,而这一点从标题上看不出来。
            subtitle = stringResource(Res.string.settings_default_quality_metered_subtitle),
            options = SettingsStore.QUALITY_OPTIONS,
            selected = state.defaultQualityMetered,
            label = { videoQualityLabel(it) },
            onChange = onMeteredQualityChange,
        )
        // 音质同一套:按网络分两格,档位是固定表(DEFAULT_AUDIO_QUALITY_OPTIONS)。
        ChoiceRow(
            title = stringResource(Res.string.settings_default_audio_wifi),
            options = DEFAULT_AUDIO_QUALITY_OPTIONS,
            selected = state.defaultAudioWifi,
            label = { audioQualityLabel(it) },
            onChange = onWifiAudioChange,
        )
        ChoiceRow(
            title = stringResource(Res.string.settings_default_audio_metered),
            options = DEFAULT_AUDIO_QUALITY_OPTIONS,
            selected = state.defaultAudioMetered,
            label = { audioQualityLabel(it) },
            onChange = onMeteredAudioChange,
        )
        ToggleSettingRow(
            title = stringResource(Res.string.settings_pick_updates_default),
            subtitle = stringResource(Res.string.settings_pick_updates_default_subtitle),
            checked = state.playerPickUpdatesDefault,
            onCheckedChange = onPickUpdatesDefaultChange,
        )
        CodecSection(
            selected = state.codec,
            hardwareCodecIds = state.hardwareCodecIds,
            onChange = onCodecChange,
        )
        ChoiceRow(
            title = stringResource(Res.string.settings_fast_forward_speed),
            // 长按加速没有播放页入口 —— 它是"长按的时候有多快"这条规则本身,不是看的时候
            // 顺手调的东西,所以按 §2.8 的判据("怎么做")放这里。
            subtitle = stringResource(Res.string.settings_fast_forward_speed_subtitle),
            options = SettingsStore.FAST_FORWARD_SPEEDS,
            selected = SettingsStore.FAST_FORWARD_SPEEDS.minByOrNull { abs(it - state.fastForwardSpeed) },
            label = { formatSpeed(it) },
            onChange = onFastForwardSpeedChange,
        )
    }
}

/**
 * SponsorBlock。**九个分类再降一层**:它们占的高度比这一页其余部分加起来还多,而多数人
 * 设一次就再也不动 —— 常驻在这里的结果是每次来改服务器地址都要滚过它们。
 */
@Composable
fun SponsorBlockSettingsPage(
    state: SettingsUiState,
    onChange: (SponsorBlockPrefs) -> Unit,
    onOpenCategories: () -> Unit,
    onBack: () -> Unit,
) {
    var editingServer by rememberSaveable { mutableStateOf(false) }
    val prefs = state.sponsorBlock
    SettingsSubPage(stringResource(Res.string.settings_section_sponsorblock), onBack, state.loaded) {
        ToggleSettingRow(
            title = stringResource(Res.string.settings_sponsorblock_toggle),
            subtitle = stringResource(Res.string.settings_sponsorblock_toggle_subtitle),
            checked = prefs.enabled,
            onCheckedChange = { onChange(prefs.copy(enabled = it)) },
        )
        // 关掉时这两行不适用,但**它们的出现和消失要看得见**:直接 `if` 掉的话页面在同一次
        // 点击里换掉了两行的高度,读起来像整页跳了一下,而跳的原因(刚按下的那个开关)
        // 已经滚出视线的可能也有。沿竖轴展开收起就把因果连起来了。
        //
        // spec 取 motionScheme 的 fast 档:这一块是组件显隐,不是转场(风格指南 §6 那张表),
        // 而且它紧跟着一次点击,慢一档就成了"按下去要等一下"。
        AnimatedVisibility(
            visible = prefs.enabled,
            enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
                fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            Column {
                SettingRow(
                    title = stringResource(Res.string.settings_sponsorblock_categories),
                    value = stringResource(
                        Res.string.settings_sponsorblock_enabled_count,
                        prefs.categories.count { it in CATEGORY_LABELS },
                        CATEGORY_LABELS.size,
                    ),
                    target = RowTarget.Page,
                    onClick = onOpenCategories,
                )
                // 服务器地址和分类不是一类东西:那些是"跳什么",这个是"问谁"。
                SettingRow(
                    title = stringResource(Res.string.settings_sponsorblock_server),
                    subtitle = "${prefs.serverUrl}\n${stringResource(Res.string.settings_sponsorblock_server_subtitle)}",
                    onClick = { editingServer = true },
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
                onChange(prefs.copy(serverUrl = it))
            },
        )
    }
}

@Composable
fun SponsorCategoriesPage(
    state: SettingsUiState,
    onChange: (SponsorBlockPrefs) -> Unit,
    onBack: () -> Unit,
) {
    val prefs = state.sponsorBlock
    // 这一页内部还分几组,组标题要留在容器外面,所以不用外壳那个默认的整页容器。
    SettingsSubPage(
        title = stringResource(Res.string.settings_sponsorblock_categories),
        onBack = onBack,
        ready = state.loaded,
        grouped = false,
    ) {
        CATEGORY_GROUPS.forEach { (groupTitle, categories) ->
            GroupLabel(stringResource(groupTitle))
            SettingsGroup {
                categories.forEach { category ->
                    val label = CATEGORY_LABELS[category] ?: return@forEach
                    ToggleSettingRow(
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

/**
 * 外观:明暗、纯黑、配色、语言。
 *
 * - **明暗**三档单选;**纯黑**是它下面的一个开关,只在会出现深色(深色或跟随系统)时可用。
 * - **配色**是一片色板(5×2)而不是一行文字加对话框:这一项选的就是一个颜色,读名字挑颜色是在
 *   绕路。第一格是按壁纸取色(系统不支持时不给这一格),其余是内置配色,名字在色板下面。
 *   选中那一格画一圈外框加一个勾,不只靠颜色(风格指南 §2.6)。
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
    onBack: () -> Unit,
) {
    val appearance = state.appearance
    SettingsSubPage(stringResource(Res.string.settings_section_appearance), onBack, state.loaded) {
        ChoiceRow(
            title = stringResource(Res.string.settings_theme_mode),
            options = ThemeMode.entries,
            selected = appearance.mode,
            label = { stringResource(it.label) },
            onChange = onModeChange,
        )
        ToggleSettingRow(
            title = stringResource(Res.string.settings_pure_black),
            subtitle = stringResource(Res.string.settings_pure_black_subtitle),
            checked = appearance.pureBlack,
            onCheckedChange = onPureBlackChange,
        )
        PaletteRow(selected = appearance.palette, onSelect = onPaletteChange)
        if (LocalSystemActions.current.supportsLanguageSwitch) {
            ChoiceRow(
                title = stringResource(Res.string.settings_language),
                options = AppLanguage.entries,
                selected = language,
                label = { stringResource(it.label) },
                onChange = onLanguageChange,
            )
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
 * 配色那一块:标题,下面一片 5 列的色板。
 *
 * 每格是一个圆(种子色本身)加名字;按壁纸取色那一格画当前壁纸取出来的主色,名字叫「系统」。
 * 圆里不画别的:颜色就是这一项的全部内容。
 */
@Composable
private fun PaletteRow(selected: String, onSelect: (String) -> Unit) {
    Column(modifier = Modifier.padding(vertical = Spacing.Tight)) {
        Text(
            stringResource(Res.string.settings_theme_palette),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        )
        // **铺开成 5×2,不横滑。** 十格一屏放得下,横滑的话后几个色要拖一下才看得到,而这一项
        // 选的就是颜色,挑之前得先一眼看全。
        val wallpaperPrimary = if (dynamicColorAvailable) dynamicColorSchemes().light.primary else Color.Unspecified
        val dynamicLabel = stringResource(Res.string.theme_palette_dynamic)
        val swatches: List<Swatch> = buildList {
            if (dynamicColorAvailable) {
                add(Swatch(AppearancePrefs.DYNAMIC, wallpaperPrimary, dynamicLabel))
            }
            ThemePalette.entries.forEach { add(Swatch(it.name, it.seed, "", it.label)) }
        }
        // 系统不支持壁纸取色时存着的 dynamic 实际落到默认那一套,选中标记也跟过去。
        val effective = if (swatches.any { it.key == selected }) selected else ThemePalette.Default.name
        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier
                .padding(horizontal = Spacing.Tight)
                .selectableGroup(),
        ) {
            swatches.chunked(PaletteColumns).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { swatch ->
                        PaletteSwatch(
                            color = swatch.color,
                            label = swatch.labelRes?.let { stringResource(it) } ?: swatch.label,
                            selected = swatch.key == effective,
                            onClick = { onSelect(swatch.key) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 最后一行不满时留空位,不让剩下几格撑宽。
                    repeat(PaletteColumns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** 色板的一格。[labelRes] 与 [label] 二选一:内置配色取资源,「系统」那格已经取好了字。 */
private class Swatch(val key: String, val color: Color, val label: String, val labelRes: StringResource? = null)

private const val PaletteColumns = 5

@Composable
private fun PaletteSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(Spacing.Hair),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(SwatchSize)
                .then(
                    if (selected) {
                        Modifier.border(SwatchRingWidth, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    },
                )
                .padding(SwatchRingGap)
                .clip(CircleShape)
                .background(color),
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    // 勾压在种子色上:浅色种子(山吹)上用深色,其余用白。
                    tint = if (color.luminance() > CheckContrastLuminance) Color.Black else Color.White,
                    modifier = Modifier.size(Dimens.IconInline),
                )
            }
        }
        // 五列时一格约 60dp 宽,罗马音最长的 Wakatake 在 labelMedium 下刚好放得下;字体放大时截断。
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 五列时一格约 60dp,圆取 44 留出选中外圈与左右的缝。 */
private val SwatchSize = 44.dp
private val SwatchRingWidth = 2.dp
/** 选中外圈与色块之间的缝:外圈贴着色块的话,和种子色相近的外圈看不出来。 */
private val SwatchRingGap = 4.dp
private const val CheckContrastLuminance = 0.5f

/** 缓存。只有并发度一项 —— 清晰度在缓存面板上选(那是"这一次下什么"),而这里是"怎么下"。 */
@Composable
fun OfflineSettingsPage(
    state: SettingsUiState,
    onConcurrencyChange: (Int) -> Unit,
    onBack: () -> Unit,
) {
    SettingsSubPage(stringResource(Res.string.settings_section_offline), onBack, state.loaded) {
        ChoiceRow(
            title = stringResource(Res.string.settings_offline_concurrency),
            // 说清代价:调大不是白拿的,而"下载多了刷不动"是最容易被归到别处的那种症状。
            subtitle = stringResource(Res.string.settings_offline_concurrency_subtitle),
            options = SettingsStore.OFFLINE_CONCURRENCY_OPTIONS,
            selected = state.offlineConcurrency,
            label = { stringResource(Res.string.settings_offline_concurrency_value, it) },
            onChange = onConcurrencyChange,
        )
    }
}

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
    onBack: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    SettingsSubPage(stringResource(Res.string.settings_section_agent), onBack, state.loaded) {
        SettingRow(
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
        // **这一行故意保留接口原话**,是全应用唯一的例外。别处收成三句
        // (`ui/ErrorText.kt`)的理由是"用户拿错误码做不了任何事";这里反过来 ——
        // 这条连的是用户自己填的 base URL 和 key,而这一行的用途就是告诉他填错在哪。
        // 换成「请求被拒绝」之后 401、DNS 解析失败、模型名不存在读起来一模一样,
        // 这个冒烟测试就没用了。
        SettingRow(
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
    onBack: () -> Unit,
) {
    SettingsSubPage(stringResource(Res.string.settings_section_privacy), onBack, state.loaded) {
        // 和这一页其余开关不同,它读的是服务端的账号设置,所以读不到是一种真实状态。三档各画
        // 各的,不把未知折成一个关着的开关 —— 那会让人以为"正在记录",而实际上谁也不知道。
        when (val pause = state.historyPause) {
            HistoryPause.Loading -> SettingRow(
                title = stringResource(Res.string.settings_pause_history),
                subtitle = stringResource(Res.string.settings_pause_history_loading),
            )

            HistoryPause.Unavailable -> SettingRow(
                title = stringResource(Res.string.settings_pause_history),
                subtitle = stringResource(Res.string.settings_pause_history_failed),
                onClick = onRetryHistoryPause,
            )

            is HistoryPause.Known -> ToggleSettingRow(
                title = stringResource(Res.string.settings_pause_history),
                subtitle = stringResource(Res.string.settings_pause_history_subtitle),
                checked = pause.paused,
                onCheckedChange = onHistoryPausedChange,
            )
        }
        SettingRow(
            title = stringResource(Res.string.blacklist_title),
            target = RowTarget.Page,
            onClick = onOpenBlacklist,
        )
        // 和上面几项一样是"谁能知道我在看什么":这一条要发给站外服务器,副标题把发的是什么
        // 说清楚 —— 一个只写"补全醒目留言"的开关,读者无从判断该不该关。
        ToggleSettingRow(
            title = stringResource(Res.string.settings_danmakus_archive),
            subtitle = stringResource(Res.string.settings_danmakus_archive_subtitle),
            checked = state.danmakusArchive,
            onCheckedChange = onDanmakusArchiveChange,
        )
        // 一个都没排除过的人不需要看见这个概念。
        if (state.excludedFeedUps.isNotEmpty()) {
            SettingRow(
                title = stringResource(Res.string.settings_feed_excluded),
                subtitle = stringResource(Res.string.settings_feed_excluded_count, state.excludedFeedUps.size),
                target = RowTarget.Page,
                onClick = onOpenExcludedFeed,
            )
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
    onBack: () -> Unit,
) {
    var confirmingClearAll by rememberSaveable { mutableStateOf(false) }
    SettingsSubPage(stringResource(Res.string.settings_feed_excluded), onBack, state.loaded) {
        state.excludedFeedUps.forEach { up ->
            ListItem(
                headlineContent = { Text(up.name, style = MaterialTheme.typography.bodyLarge) },
                trailingContent = {
                    TextButton(onClick = { onRestore(up.mid) }) {
                        Text(stringResource(Res.string.settings_feed_excluded_restore))
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (state.excludedFeedUps.size > 1) {
            SettingRow(
                title = stringResource(Res.string.settings_feed_clear_excluded),
                onClick = { confirmingClearAll = true },
            )
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

@Composable
fun AboutSettingsPage(
    state: SettingsUiState,
    onOpenGithub: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: (UpdateInfo) -> Unit,
    onInstallUpdate: (File) -> Unit,
    onBack: () -> Unit,
) {
    SettingsSubPage(stringResource(Res.string.settings_section_about), onBack) {
        SettingRow(
            title = stringResource(Res.string.settings_version),
            subtitle = "${AppBuild.versionName}(${AppBuild.applicationId})",
        )
        SettingRow(
            title = stringResource(Res.string.settings_license),
            subtitle = "GPL-3.0-or-later",
        )
        // 这一行走出应用去浏览器,所以给外链图标而不是箭头:箭头说的是"应用里还有一页"。
        SettingRow(
            title = stringResource(Res.string.settings_github),
            target = RowTarget.External,
            onClick = onOpenGithub,
        )
        if (LocalSystemActions.current.supportsSelfUpdate) {
            UpdateRow(
                state = state.update,
                onCheck = onCheckUpdate,
                onDownload = onDownloadUpdate,
                onInstall = onInstallUpdate,
            )
        }
    }
}

/** 首页那一行的摘要用得上:开着还是关着。 */
@Composable
internal fun onOffLabel(on: Boolean): String =
    stringResource(if (on) Res.string.settings_on else Res.string.settings_off)
