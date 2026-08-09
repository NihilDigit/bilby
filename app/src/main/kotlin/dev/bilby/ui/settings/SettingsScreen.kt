package dev.bilby.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.bilby.BuildConfig
import dev.bilby.data.UpdateInfo
import java.io.File
import dev.bilby.R
import dev.bilby.data.CodecPreference
import dev.bilby.data.LlmConfig
import dev.nihildigit.danmaku.DanmakuDensity
import dev.nihildigit.danmaku.DanmakuFrameRateCap
import dev.nihildigit.danmaku.DanmakuViewport
import dev.bilby.data.SettingsStore
import dev.bilby.data.SponsorBlockPrefs
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.video.CATEGORY_LABELS
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 设置页。
 *
 * **范围是定死的**(DESIGN 2 节):设置只调整**怎么做**,不调整**做不做**。
 * 推荐流、相关推荐、自动续接一个开关都不给 —— 它们能被开关掉的那一刻,
 * DESIGN 1.3 的结构约束就退化成了自制力工具。看视频时要调的(画质、倍速、连播、
 * 顺序/随机)留在播放页,在那里改即是改全局默认。
 *
 * 入口是「我的」页顶栏的图标,不进底部导航:底部三格是"我要去哪",设置不是目的地。
 * 账号信息与登出不在这里 —— 它们归「我的」页头部(`ui/profile/ProfileScreen`)。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onLlmChange: (LlmConfig) -> Unit,
    onSmokeTestLlm: () -> Unit,
    onCodecChange: (CodecPreference) -> Unit,
    onDanmakuOpacityChange: (Float) -> Unit,
    onDanmakuScrollShowAreaChange: (Float) -> Unit,
    onDanmakuDensityChange: (DanmakuDensity) -> Unit,
    onDanmakuFrameRateChange: (DanmakuFrameRateCap) -> Unit,
    onSponsorBlockChange: (SponsorBlockPrefs) -> Unit,
    onOpenGithub: () -> Unit,
    onClearExcludedFeed: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: (UpdateInfo) -> Unit,
    onInstallUpdate: (File) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingLlm by rememberSaveable { mutableStateOf(false) }
    var editingServer by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { BilbyTopBar(title = stringResource(R.string.settings_title), onBack = onBack) },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(bottom = Spacing.Spacious),
        ) {
            item("llm") {
                SectionTitle(stringResource(R.string.settings_section_agent))
                val llm = state.llm
                val notConfigured = stringResource(R.string.settings_not_configured)
                SettingRow(
                    title = stringResource(R.string.settings_llm_base_url),
                    subtitle = llm?.baseUrl?.ifBlank { notConfigured }
                        ?: stringResource(R.string.settings_loading),
                    onClick = { editingLlm = true },
                )
                SettingRow(
                    title = stringResource(R.string.settings_api_key),
                    // 永远只显示是否配置,不显示遮蔽后的原文:遮蔽只挡眼睛,截图和录屏挡不住。
                    subtitle = if (llm?.apiKey.isNullOrEmpty()) {
                        notConfigured
                    } else {
                        stringResource(R.string.settings_configured)
                    },
                    onClick = { editingLlm = true },
                )
                SettingRow(
                    title = stringResource(R.string.settings_model),
                    subtitle = llm?.model.orEmpty().ifBlank { SettingsStore.DEFAULT_LLM_MODEL },
                    onClick = { editingLlm = true },
                )
                SettingRow(
                    title = stringResource(R.string.settings_llm_test),
                    subtitle = when (val test = state.llmTest) {
                        LlmTest.Idle -> stringResource(R.string.settings_llm_test_hint)
                        LlmTest.Running -> stringResource(R.string.settings_llm_test_running)
                        is LlmTest.Ok -> stringResource(R.string.settings_llm_test_ok, test.millis)
                        is LlmTest.Failed -> test.message
                    },
                    onClick = onSmokeTestLlm,
                )
            }

            item("player") {
                SectionTitle(stringResource(R.string.settings_section_player))
                CodecSection(
                    selected = state.codec,
                    hardwareCodecIds = state.hardwareCodecIds,
                    onChange = onCodecChange,
                )
                SliderSettingRow(
                    title = stringResource(R.string.settings_danmaku_opacity),
                    value = state.danmaku.opacity,
                    valueLabel = { stringResource(R.string.settings_danmaku_opacity_value, (it * 100).roundToInt()) },
                    onChange = onDanmakuOpacityChange,
                )
                ChoiceSection(
                    title = stringResource(R.string.settings_danmaku_show_area),
                    // 说清它不管底部弹幕:调到 25% 之后底部那几条纹丝不动,不写清楚会被当成没生效。
                    subtitle = stringResource(R.string.settings_danmaku_show_area_subtitle),
                    options = SCROLL_SHOW_AREA_STEPS,
                    selected = SCROLL_SHOW_AREA_STEPS.minByOrNull { abs(it - state.danmaku.scrollShowArea) },
                    label = { stringResource(R.string.settings_danmaku_show_area_value, (it * 100).roundToInt()) },
                    onChange = onDanmakuScrollShowAreaChange,
                )
                ChoiceSection(
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
                    onChange = onDanmakuDensityChange,
                )
                ChoiceSection(
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
                    onChange = onDanmakuFrameRateChange,
                )
            }

            // 一个都没排除过就整节不显示:没用过这个功能的人不需要先认识"排除名单"这个概念,
            // 才能看懂设置页里多出来的一行。
            if (state.excludedFeedCount > 0) {
                item("feed") {
                    SectionTitle(stringResource(R.string.settings_section_feed))
                    SettingRow(
                        title = stringResource(R.string.settings_feed_clear_excluded),
                        subtitle = stringResource(
                            R.string.settings_feed_excluded_count,
                            state.excludedFeedCount,
                        ),
                        onClick = onClearExcludedFeed,
                    )
                }
            }

            item("sponsorblock") {
                SectionTitle("SponsorBlock")
                val prefs = state.sponsorBlock
                ToggleSettingRow(
                    title = stringResource(R.string.settings_sponsorblock_toggle),
                    subtitle = stringResource(R.string.settings_sponsorblock_toggle_subtitle),
                    checked = prefs.enabled,
                    onCheckedChange = { onSponsorBlockChange(prefs.copy(enabled = it)) },
                )
                if (prefs.enabled) {
                    CATEGORY_LABELS.forEach { (category, label) ->
                        ToggleSettingRow(
                            title = stringResource(label),
                            checked = category in prefs.categories,
                            onCheckedChange = { checked ->
                                val next = if (checked) {
                                    prefs.categories + category
                                } else {
                                    prefs.categories - category
                                }
                                onSponsorBlockChange(prefs.copy(categories = next))
                            },
                            indent = true,
                            useCheckbox = true,
                        )
                    }
                    SettingRow(
                        title = stringResource(R.string.settings_sponsorblock_server),
                        subtitle = prefs.serverUrl,
                        onClick = { editingServer = true },
                    )
                }
            }

            item("about") {
                SectionTitle(stringResource(R.string.settings_section_about))
                SettingRow(
                    title = stringResource(R.string.settings_version),
                    subtitle = "${BuildConfig.VERSION_NAME}(${BuildConfig.APPLICATION_ID})",
                )
                SettingRow(
                    title = stringResource(R.string.settings_license),
                    subtitle = "GPL-3.0-or-later",
                )
                SettingRow(
                    title = stringResource(R.string.settings_github),
                    onClick = onOpenGithub,
                )
                UpdateRow(
                    state = state.update,
                    onCheck = onCheckUpdate,
                    onDownload = onDownloadUpdate,
                    onInstall = onInstallUpdate,
                )
            }
        }
    }

    if (editingLlm && state.llm != null) {
        LlmDialog(
            initial = state.llm,
            onDismiss = { editingLlm = false },
            onConfirm = {
                editingLlm = false
                onLlmChange(it)
            },
        )
    }

    if (editingServer) {
        TextFieldDialog(
            title = stringResource(R.string.settings_sponsorblock_server_dialog),
            initial = state.sponsorBlock.serverUrl,
            // 留空即恢复默认:这是个第三方服务地址,改错了要有一条不用记原值的退路。
            placeholder = SettingsStore.DEFAULT_SB_SERVER,
            onDismiss = { editingServer = false },
            onConfirm = { value ->
                editingServer = false
                onSponsorBlockChange(
                    state.sponsorBlock.copy(
                        serverUrl = value.trim().ifBlank { SettingsStore.DEFAULT_SB_SERVER },
                    ),
                )
            },
        )
    }
}

/**
 * 拖动中只改本地状态,**松手才回调 [onChange]**。
 *
 * `onValueChange` 是逐帧回调的,直接接到持久化上等于把一次拖动变成十几次 DataStore 写;
 * 而 `danmakuPrefs` 每写一次,所有还活着的播放页 ViewModel 都会被唤醒收一遍。
 */
@Composable
private fun SliderSettingRow(
    title: String,
    value: Float,
    valueLabel: @Composable (Float) -> String,
    onChange: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: value
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                valueLabel(shown),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let(onChange)
                dragging = null
            },
            valueRange = 0.1f..1f,
            steps = 8,
        )
    }
}

/**
 * 编解码偏好。**只列本机真有硬解器的编码** —— 列一个选了也只能软解的选项,
 * 等于让用户自己给自己挑一条掉帧的路。查询走 `player/DeviceCodecs`。
 */
@Composable
private fun CodecSection(
    selected: CodecPreference,
    hardwareCodecIds: Set<Int>,
    onChange: (CodecPreference) -> Unit,
) {
    val options = CodecPreference.entries.filter { option ->
        option.requiredCodecId()?.let { it in hardwareCodecIds } ?: true
    }
    SettingRow(
        title = stringResource(R.string.settings_codec),
        // 说清生效时机:改完不重开当前视频,不为一个设置项打断正在看的东西。
        subtitle = stringResource(R.string.settings_codec_subtitle),
    )
    options.forEach { option ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.MinTouchTarget)
                .selectable(
                    selected = option == selected,
                    role = Role.RadioButton,
                    onClick = { onChange(option) },
                )
                .padding(start = Spacing.Loose, end = Spacing.Comfortable),
        ) {
            RadioButton(selected = option == selected, onClick = null)
            Text(option.label, modifier = Modifier.padding(start = Spacing.Cozy))
        }
    }
}

/**
 * 「几档选一」的通用形态:一行标题(可带副标题)加若干单选行。[CodecSection] 是同一个形态,
 * 但它要按本机硬解能力过滤选项、标签也来自枚举自己,不套进来——为一处特例给通用组件加参数,
 * 换来的是两边都变难读。
 */
@Composable
private fun <T> ChoiceSection(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onChange: (T) -> Unit,
    subtitle: String? = null,
) {
    SettingRow(title = title, subtitle = subtitle)
    options.forEach { option ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.MinTouchTarget)
                .selectable(
                    selected = option == selected,
                    role = Role.RadioButton,
                    onClick = { onChange(option) },
                )
                .padding(start = Spacing.Loose, end = Spacing.Comfortable),
        ) {
            RadioButton(selected = option == selected, onClick = null)
            Text(label(option), modifier = Modifier.padding(start = Spacing.Cozy))
        }
    }
}

/**
 * 滚动弹幕显示区域的四档。存的是连续的 Float(公共 API 不写死成枚举,见 [DanmakuViewport]),
 * 界面上只给这四档,选中判定按最近档取——将来加档或换成连续滑杆都不必改存储格式。
 */
private val SCROLL_SHOW_AREA_STEPS = listOf(0.25f, 0.5f, 0.75f, 1f)

/**
 * 分组标题。**分组之间不画分割线**,靠这行带 primary 色的标题加上它上方的留白分隔。
 *
 * M3 cards 页的 don't 是"Don't force content into cards when spacing, headlines, or dividers
 * would create a simpler visual hierarchy",divider 页则要求 full-width divider 用得
 * sparingly。这一页每组头上本来就有一个带色标题,再压一条线是同一件事说两遍。
 */
/**
 * 手动更新那一行。**副标题就是状态本身**,不另开一块区域:检查、下载、可安装、失败
 * 四种情况读起来都是"这一项现在怎么样了",挤进同一行反而比弹对话框更安静。
 *
 * 点击的语义随状态变:空闲/已是最新/失败时是"再查一次",查到新版是"下载",
 * 下好了是"安装",下载中不响应。
 */
@Composable
private fun UpdateRow(
    state: UpdateState,
    onCheck: () -> Unit,
    onDownload: (UpdateInfo) -> Unit,
    onInstall: (File) -> Unit,
) {
    val subtitle = when (state) {
        UpdateState.Idle -> stringResource(R.string.settings_update_idle)
        UpdateState.Checking -> stringResource(R.string.settings_update_checking)
        UpdateState.UpToDate -> stringResource(R.string.settings_update_latest)
        is UpdateState.Available ->
            stringResource(R.string.settings_update_available, state.info.version)
        is UpdateState.Downloading ->
            stringResource(R.string.settings_update_downloading, (state.progress * 100).toInt())
        is UpdateState.Ready ->
            stringResource(R.string.settings_update_ready, state.info.version)
        is UpdateState.Failed ->
            stringResource(R.string.settings_update_failed, state.message)
    }
    SettingRow(
        title = stringResource(R.string.settings_update),
        subtitle = subtitle,
        onClick = when (state) {
            is UpdateState.Available -> ({ onDownload(state.info) })
            is UpdateState.Ready -> ({ onInstall(state.apk) })
            UpdateState.Checking, is UpdateState.Downloading -> null
            else -> onCheck
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = Spacing.Comfortable,
            end = Spacing.Comfortable,
            // 上下不对称:分组之间的断开全靠这段上留白,所以它比下方大一档。
            top = Spacing.Loose,
            bottom = Spacing.Tight,
        ),
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = Dimens.MinTouchTarget)
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 整行可切换。用 `Modifier.toggleable` + role 而不是给控件单独挂 onClick:
 * 前者让整行成为一个语义节点(读屏念"开关,已开启,自动跳过"),后者只有那个小控件能点。
 */
@Composable
private fun ToggleSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    indent: Boolean = false,
    useCheckbox: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .toggleable(
                value = checked,
                role = if (useCheckbox) Role.Checkbox else Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(
                start = if (indent) Spacing.Loose else Spacing.Comfortable,
                end = Spacing.Comfortable,
                top = Spacing.Tight,
                bottom = Spacing.Tight,
            ),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (useCheckbox) {
            Checkbox(checked = checked, onCheckedChange = null)
        } else {
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/**
 * LLM 三项一起改。三个框在一个框里,是因为它们只有凑齐了才有意义 ——
 * 单改一项存下去,中间那个状态是"配了地址没配 key",助理照样跑不起来。
 */
@Composable
private fun LlmDialog(initial: LlmConfig, onDismiss: () -> Unit, onConfirm: (LlmConfig) -> Unit) {
    var baseUrl by rememberSaveable { mutableStateOf(initial.baseUrl) }
    var apiKey by rememberSaveable { mutableStateOf(initial.apiKey) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var keyVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_llm_dialog)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Cozy)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.settings_api_key)) },
                    singleLine = true,
                    // 默认遮蔽。给一个显形按钮是因为长串 key 手输时看不见就没法核对,
                    // 但默认态必须是遮住的 —— 设置页经常是当着别人的面打开的。
                    visualTransformation = if (keyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { keyVisible = !keyVisible }) {
                            Icon(
                                imageVector = if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (keyVisible) {
                                        R.string.settings_key_hide
                                    } else {
                                        R.string.settings_key_show
                                    },
                                ),
                            )
                        }
                    },
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.settings_model)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    LlmConfig(
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        model = model.trim().ifBlank { SettingsStore.DEFAULT_LLM_MODEL },
                    ),
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun TextFieldDialog(
    title: String,
    initial: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
