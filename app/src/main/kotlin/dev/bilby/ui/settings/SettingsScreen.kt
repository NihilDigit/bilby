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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.bilby.BuildConfig
import dev.bilby.data.CodecPreference
import dev.bilby.data.LlmConfig
import dev.bilby.data.SettingsStore
import dev.bilby.data.SponsorBlockPrefs
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.video.CATEGORY_LABELS

/**
 * 设置页。
 *
 * **范围是定死的**(DESIGN 2 节):设置只调整**怎么做**,不调整**做不做**。
 * 推荐流、相关推荐、自动续接一个开关都不给 —— 它们能被开关掉的那一刻,
 * DESIGN 1.3 的结构约束就退化成了自制力工具。看视频时要调的(画质、倍速、连播、
 * 顺序/随机)留在播放页,在那里改即是改全局默认。
 *
 * 入口是动态页顶栏的图标,不进底部导航:底部三格是"我要去哪",设置不是目的地。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onLlmChange: (LlmConfig) -> Unit,
    onCodecChange: (CodecPreference) -> Unit,
    onSponsorBlockChange: (SponsorBlockPrefs) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingLlm by rememberSaveable { mutableStateOf(false) }
    var editingServer by rememberSaveable { mutableStateOf(false) }
    var confirmingLogout by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { BilbyTopBar(title = "设置", onBack = onBack) },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = PaddingValues(bottom = Spacing.Spacious),
        ) {
            item("account") {
                SectionTitle("账号")
                SettingRow(
                    title = state.account.name ?: "UID ${state.account.uid}",
                    subtitle = if (state.account.name != null) "UID ${state.account.uid}" else null,
                )
                SettingRow(
                    title = "登出",
                    subtitle = "清除本机凭据并停止播放。凭据过期同样走这里,重新扫码即可",
                    onClick = { confirmingLogout = true },
                )
            }

            item("llm") {
                SectionTitle("助理")
                val llm = state.llm
                SettingRow(
                    title = "接口地址",
                    subtitle = llm?.baseUrl?.ifBlank { "未配置" } ?: "读取中…",
                    onClick = { editingLlm = true },
                )
                SettingRow(
                    title = "API key",
                    // 永远只显示是否配置,不显示遮蔽后的原文:遮蔽只挡眼睛,截图和录屏挡不住。
                    subtitle = if (llm?.apiKey.isNullOrEmpty()) "未配置" else "已配置",
                    onClick = { editingLlm = true },
                )
                SettingRow(
                    title = "模型",
                    subtitle = llm?.model.orEmpty().ifBlank { SettingsStore.DEFAULT_LLM_MODEL },
                    onClick = { editingLlm = true },
                )
            }

            item("player") {
                SectionTitle("播放器")
                CodecSection(
                    selected = state.codec,
                    hardwareCodecIds = state.hardwareCodecIds,
                    onChange = onCodecChange,
                )
                SettingRow(
                    title = "视频解码器",
                    subtitle = state.tech.videoDecoder?.let { name ->
                        val kind = when (state.tech.videoIsHardware) {
                            true -> "硬解"
                            false -> "软解"
                            null -> "未知"
                        }
                        "$name($kind)"
                    } ?: "还没开始解码",
                )
                SettingRow(
                    title = "音频解码器",
                    subtitle = state.tech.audioDecoder ?: "还没开始解码",
                )
                SettingRow(
                    title = "倍速音频算法",
                    // 没有开关:WSOLA 默认开且有透明回退,开关能解决的问题已经被回退覆盖。
                    // 这里只报告此刻真正在做时间伸缩的那一个。
                    subtitle = state.tech.speedAlgorithm.name +
                        (state.tech.speedFallbackReason?.let { "(已回退:$it)" } ?: ""),
                )
            }

            item("sponsorblock") {
                SectionTitle("SponsorBlock")
                val prefs = state.sponsorBlock
                ToggleSettingRow(
                    title = "自动跳过",
                    subtitle = "由社区标注的赞助、片头片尾等片段",
                    checked = prefs.enabled,
                    onCheckedChange = { onSponsorBlockChange(prefs.copy(enabled = it)) },
                )
                if (prefs.enabled) {
                    CATEGORY_LABELS.forEach { (category, label) ->
                        ToggleSettingRow(
                            title = label,
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
                        title = "服务器地址",
                        subtitle = prefs.serverUrl,
                        onClick = { editingServer = true },
                    )
                }
            }

            item("about") {
                SectionTitle("关于")
                SettingRow(title = "版本", subtitle = "${BuildConfig.VERSION_NAME}(${BuildConfig.APPLICATION_ID})")
                SettingRow(title = "许可证", subtitle = "GPL-3.0-or-later")
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
            title = "SponsorBlock 服务器",
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

    if (confirmingLogout) {
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            title = { Text("登出") },
            text = { Text("会清除本机保存的登录凭据并停止正在播放的内容。重新登录需要再扫一次码。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLogout = false
                    onLogout()
                }) { Text("登出") }
            },
            dismissButton = { TextButton(onClick = { confirmingLogout = false }) { Text("取消") } },
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
        title = "编解码偏好",
        // 说清生效时机:改完不重开当前视频,不为一个设置项打断正在看的东西。
        subtitle = "决定取哪条流,改动对下一个视频生效",
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
 * 分组标题。**分组之间不画分割线**,靠这行带 primary 色的标题加上它上方的留白分隔。
 *
 * M3 cards 页的 don't 是"Don't force content into cards when spacing, headlines, or dividers
 * would create a simpler visual hierarchy",divider 页则要求 full-width divider 用得
 * sparingly。这一页每组头上本来就有一个带色标题,再压一条线是同一件事说两遍。
 */
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
        title = { Text("助理接口") },
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
                    label = { Text("API key") },
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
                                contentDescription = if (keyVisible) "隐藏 key" else "显示 key",
                            )
                        }
                    },
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型") },
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
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
