package dev.bilby.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.bilby.BuildConfig
import dev.bilby.R
import dev.bilby.data.CodecPreference
import dev.bilby.data.LlmConfig
import dev.bilby.data.SettingsStore
import dev.bilby.data.SponsorBlockPrefs
import dev.bilby.data.UpdateInfo
import dev.bilby.player.videoQualityLabel
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.player.formatSpeed
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.video.CATEGORY_DESCRIPTIONS
import dev.bilby.ui.video.CATEGORY_GROUPS
import dev.bilby.ui.video.CATEGORY_LABELS
import dev.nihildigit.danmaku.DanmakuDensity
import dev.nihildigit.danmaku.DanmakuFrameRateCap
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 设置的二级页面。**每一页都是一整页,不是一个展开块**:展开块要么把首页撑回原来的长度,
 * 要么在滚动中途改变高度,而这两件事正是重做前的样子。
 *
 * 一页一个 [SettingsSection],由 `Destinations.kt` 的 `SettingsPage` 带过来 —— 八个页面
 * 共用一个 NavKey,而不是八条路由:它们的差别只有"哪一页",没有各自的参数。
 */
enum class SettingsSection {
    Playback,
    Danmaku,
    SponsorBlock,

    /** SponsorBlock 的九个分类。再降一层,理由见 [SponsorBlockSettingsPage]。 */
    SponsorCategories,
    Offline,
    Agent,
    Privacy,
    About,
}

/**
 * 子页的外壳:顶栏、返回、可读宽度、滚动。抽出来是因为八页一模一样,而漏掉其中一样
 * (比如某一页忘了限宽)在平板上一眼看得出来。
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
    content: @Composable () -> Unit,
) {
    Scaffold(topBar = { BilbyTopBar(title = title, onBack = onBack) }) { insets ->
        AdaptiveContent(
            modifier = Modifier.fillMaxSize().padding(insets),
            maxWidth = Breakpoints.ReadableWidth,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = Spacing.Spacious),
            ) {
                if (ready) content()
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
    onBack: () -> Unit,
) {
    SettingsSubPage(stringResource(R.string.settings_section_player), onBack, state.loaded) {
        ToggleSettingRow(
            title = stringResource(R.string.settings_auto_next),
            // 说明这一行为什么存在:队列不是用户建的,自动前进因此是一个没人点过头的默认。
            subtitle = stringResource(R.string.settings_auto_next_subtitle),
            checked = state.autoNext,
            onCheckedChange = onAutoNextChange,
        )
        // 两行是同一个值按网络分的两格,不是和播放页并列的第二个开关:在播放页切画质写的就是
        // 当下所在的那一格(见 AudioPlaybackService.setQuality)。
        ChoiceRow(
            title = stringResource(R.string.settings_default_quality_wifi),
            options = SettingsStore.QUALITY_OPTIONS,
            selected = state.defaultQualityWifi,
            label = { videoQualityLabel(it) },
            onChange = onWifiQualityChange,
        )
        ChoiceRow(
            title = stringResource(R.string.settings_default_quality_metered),
            // 判据是系统的流量计费标记,不是"是不是 WiFi" —— 手机热点和按量计费的 WiFi 都算
            // 计费网络,而这一点从标题上看不出来。
            subtitle = stringResource(R.string.settings_default_quality_metered_subtitle),
            options = SettingsStore.QUALITY_OPTIONS,
            selected = state.defaultQualityMetered,
            label = { videoQualityLabel(it) },
            onChange = onMeteredQualityChange,
        )
        CodecSection(
            selected = state.codec,
            hardwareCodecIds = state.hardwareCodecIds,
            onChange = onCodecChange,
        )
        ChoiceRow(
            title = stringResource(R.string.settings_fast_forward_speed),
            // 长按加速没有播放页入口 —— 它是"长按的时候有多快"这条规则本身,不是看的时候
            // 顺手调的东西,所以按 §2.8 的判据("怎么做")放这里。
            subtitle = stringResource(R.string.settings_fast_forward_speed_subtitle),
            options = SettingsStore.FAST_FORWARD_SPEEDS,
            selected = SettingsStore.FAST_FORWARD_SPEEDS.minByOrNull { abs(it - state.fastForwardSpeed) },
            label = { formatSpeed(it) },
            onChange = onFastForwardSpeedChange,
        )
    }
}

@Composable
fun DanmakuSettingsPage(
    state: SettingsUiState,
    onOpacityChange: (Float) -> Unit,
    onScrollShowAreaChange: (Float) -> Unit,
    onDensityChange: (DanmakuDensity) -> Unit,
    onFrameRateChange: (DanmakuFrameRateCap) -> Unit,
    onBack: () -> Unit,
) {
    SettingsSubPage(stringResource(R.string.settings_section_danmaku), onBack, state.loaded) {
        SliderSettingRow(
            title = stringResource(R.string.settings_danmaku_opacity),
            value = state.danmaku.opacity,
            valueLabel = { stringResource(R.string.settings_danmaku_opacity_value, (it * 100).roundToInt()) },
            onChange = onOpacityChange,
        )
        ChoiceRow(
            title = stringResource(R.string.settings_danmaku_show_area),
            // 说清它不管底部弹幕:调到 25% 之后底部那几条纹丝不动,不写清楚会被当成没生效。
            subtitle = stringResource(R.string.settings_danmaku_show_area_subtitle),
            options = SCROLL_SHOW_AREA_STEPS,
            selected = SCROLL_SHOW_AREA_STEPS.minByOrNull { abs(it - state.danmaku.scrollShowArea) },
            label = { stringResource(R.string.settings_danmaku_show_area_value, (it * 100).roundToInt()) },
            onChange = onScrollShowAreaChange,
        )
        ChoiceRow(
            title = stringResource(R.string.settings_danmaku_density),
            options = DanmakuDensity.entries,
            selected = state.danmaku.density,
            label = {
                stringResource(
                    when (it) {
                        DanmakuDensity.STANDARD -> R.string.settings_danmaku_density_standard
                        DanmakuDensity.UNLIMITED -> R.string.settings_danmaku_density_unlimited
                    },
                )
            },
            onChange = onDensityChange,
        )
        ChoiceRow(
            title = stringResource(R.string.settings_danmaku_frame_rate),
            subtitle = stringResource(R.string.settings_danmaku_frame_rate_subtitle),
            options = DanmakuFrameRateCap.entries,
            selected = state.danmaku.frameRateCap,
            label = {
                stringResource(
                    when (it) {
                        DanmakuFrameRateCap.FPS_30 -> R.string.settings_danmaku_frame_rate_30
                        DanmakuFrameRateCap.FPS_60 -> R.string.settings_danmaku_frame_rate_60
                        DanmakuFrameRateCap.DISPLAY -> R.string.settings_danmaku_frame_rate_display
                    },
                )
            },
            onChange = onFrameRateChange,
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
    SettingsSubPage(stringResource(R.string.settings_section_sponsorblock), onBack, state.loaded) {
        ToggleSettingRow(
            title = stringResource(R.string.settings_sponsorblock_toggle),
            subtitle = stringResource(R.string.settings_sponsorblock_toggle_subtitle),
            checked = prefs.enabled,
            onCheckedChange = { onChange(prefs.copy(enabled = it)) },
        )
        if (prefs.enabled) {
            SettingRow(
                title = stringResource(R.string.settings_sponsorblock_categories),
                value = stringResource(
                    R.string.settings_sponsorblock_enabled_count,
                    prefs.categories.count { it in CATEGORY_LABELS },
                    CATEGORY_LABELS.size,
                ),
                onClick = onOpenCategories,
            )
            // 服务器地址和分类不是一类东西:那些是"跳什么",这个是"问谁"。
            SettingRow(
                title = stringResource(R.string.settings_sponsorblock_server),
                subtitle = "${prefs.serverUrl}\n${stringResource(R.string.settings_sponsorblock_server_subtitle)}",
                onClick = { editingServer = true },
            )
        }
    }
    if (editingServer) {
        TextFieldDialog(
            title = stringResource(R.string.settings_sponsorblock_server_dialog),
            initial = prefs.serverUrl,
            placeholder = SettingsStore.DEFAULT_SB_SERVER,
            onDismiss = { editingServer = false },
            onConfirm = { onChange(prefs.copy(serverUrl = it)) },
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
    SettingsSubPage(stringResource(R.string.settings_sponsorblock_categories), onBack, state.loaded) {
        CATEGORY_GROUPS.forEach { (groupTitle, categories) ->
            GroupLabel(stringResource(groupTitle))
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

/** 缓存。只有并发度一项 —— 清晰度在缓存面板上选(那是"这一次下什么"),而这里是"怎么下"。 */
@Composable
fun OfflineSettingsPage(
    state: SettingsUiState,
    onConcurrencyChange: (Int) -> Unit,
    onBack: () -> Unit,
) {
    SettingsSubPage(stringResource(R.string.settings_section_offline), onBack, state.loaded) {
        ChoiceRow(
            title = stringResource(R.string.settings_offline_concurrency),
            // 说清代价:调大不是白拿的,而"下载多了刷不动"是最容易被归到别处的那种症状。
            subtitle = stringResource(R.string.settings_offline_concurrency_subtitle),
            options = SettingsStore.OFFLINE_CONCURRENCY_OPTIONS,
            selected = state.offlineConcurrency,
            label = { stringResource(R.string.settings_offline_concurrency_value, it) },
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
    SettingsSubPage(stringResource(R.string.settings_section_agent), onBack, state.loaded) {
        SettingRow(
            title = stringResource(R.string.settings_agent_config),
            // 只说配没配,不显示遮蔽后的 key:遮蔽只挡眼睛,截图和录屏挡不住。
            subtitle = state.llm?.let { llm ->
                if (llm.baseUrl.isBlank() || llm.apiKey.isBlank()) {
                    stringResource(R.string.settings_not_configured)
                } else {
                    "${llm.baseUrl}\n${llm.model.ifBlank { SettingsStore.DEFAULT_LLM_MODEL }}"
                }
            } ?: stringResource(R.string.settings_loading),
            onClick = { editing = true },
        )
        SettingRow(
            title = stringResource(R.string.settings_llm_test),
            subtitle = when (val test = state.llmTest) {
                LlmTest.Idle -> stringResource(R.string.settings_llm_test_hint)
                LlmTest.Running -> stringResource(R.string.settings_llm_test_running)
                is LlmTest.Ok -> stringResource(R.string.settings_llm_test_ok, test.millis)
                is LlmTest.Failed -> test.message
            },
            onClick = onSmokeTest,
        )
    }
    if (editing) {
        LlmDialog(
            initial = state.llm ?: LlmConfig("", "", SettingsStore.DEFAULT_LLM_MODEL),
            onDismiss = { editing = false },
            onConfirm = onLlmChange,
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
    onClearExcludedFeed: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsSubPage(stringResource(R.string.settings_section_privacy), onBack, state.loaded) {
        // 和这一页其余开关不同,它读的是服务端的账号设置,所以读不到是一种真实状态。三档各画
        // 各的,不把未知折成一个关着的开关 —— 那会让人以为"正在记录",而实际上谁也不知道。
        when (val pause = state.historyPause) {
            HistoryPause.Loading -> SettingRow(
                title = stringResource(R.string.settings_pause_history),
                subtitle = stringResource(R.string.settings_pause_history_loading),
            )

            HistoryPause.Unavailable -> SettingRow(
                title = stringResource(R.string.settings_pause_history),
                subtitle = stringResource(R.string.settings_pause_history_failed),
                onClick = onRetryHistoryPause,
            )

            is HistoryPause.Known -> ToggleSettingRow(
                title = stringResource(R.string.settings_pause_history),
                subtitle = stringResource(R.string.settings_pause_history_subtitle),
                checked = pause.paused,
                onCheckedChange = onHistoryPausedChange,
            )
        }
        SettingRow(title = stringResource(R.string.blacklist_title), onClick = onOpenBlacklist)
        // 一个都没排除过的人不需要看见这个概念。
        if (state.excludedFeedCount > 0) {
            SettingRow(
                title = stringResource(R.string.settings_feed_clear_excluded),
                subtitle = stringResource(R.string.settings_feed_excluded_count, state.excludedFeedCount),
                onClick = onClearExcludedFeed,
            )
        }
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
    SettingsSubPage(stringResource(R.string.settings_section_about), onBack) {
        SettingRow(
            title = stringResource(R.string.settings_version),
            subtitle = "${BuildConfig.VERSION_NAME}(${BuildConfig.APPLICATION_ID})",
        )
        SettingRow(
            title = stringResource(R.string.settings_license),
            subtitle = "GPL-3.0-or-later",
        )
        SettingRow(title = stringResource(R.string.settings_github), onClick = onOpenGithub)
        UpdateRow(
            state = state.update,
            onCheck = onCheckUpdate,
            onDownload = onDownloadUpdate,
            onInstall = onInstallUpdate,
        )
    }
}

/** 首页那一行的摘要用得上:开着还是关着。 */
@Composable
internal fun onOffLabel(on: Boolean): String =
    stringResource(if (on) R.string.settings_on else R.string.settings_off)
