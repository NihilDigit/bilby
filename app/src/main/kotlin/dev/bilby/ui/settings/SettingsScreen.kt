package dev.bilby.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.bilby.BuildConfig
import dev.bilby.data.UpdateInfo
import java.io.File
import dev.bilby.R
import dev.bilby.data.CodecPreference
import dev.bilby.ui.components.BilbyIcons
import dev.bilby.data.LlmConfig
import dev.bilby.data.SettingsStore
import dev.bilby.player.videoQualityLabel
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Spacing

/**
 * 设置首页。**只放入口,没有一项设置直接躺在这里**(登出除外,见下)。
 *
 * 重做之前这一页是一整条铺开的清单:七节、六十多行,其中三分之二是「几档选一」常驻画出来的
 * 单选行。要改一项弹幕密度得滚过二十几行自己早就选定的画质和编解码。
 *
 * **每一行右侧给当前值。** 一层菜单换来的如果是每次都要点进去才知道现在设成了什么,那这层
 * 菜单是净亏 —— 摘要是它成立的前提,不是装饰。
 *
 * **不再分宽屏双栏。** 那是为六十多行准备的;八行入口拆两栏只会让右边一栏空着。
 *
 * 范围仍然是定死的(DESIGN 2 节):设置只调整**怎么做**,不调整**做不做**。推荐流、相关
 * 推荐一个开关都不给 —— 它们能被开关掉的那一刻,DESIGN 1.3 的结构约束就退化成了自制力工具。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onOpenSection: (SettingsSection) -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingLogout by rememberSaveable { mutableStateOf(false) }
    val notConfigured = stringResource(R.string.settings_not_configured)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { BilbyTopBar(title = stringResource(R.string.settings_title), onBack = onBack) },
    ) { insets ->
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
                SettingRow(
                    title = stringResource(R.string.settings_section_player),
                    icon = Icons.Outlined.PlayCircleOutline,
                    // 摘要给 WiFi 那一档:它是绝大多数时候真正生效的那个值。
                    value = state.loaded.then { videoQualityLabel(state.defaultQualityWifi) },
                    onClick = { onOpenSection(SettingsSection.Playback) },
                )
                SettingRow(
                    title = stringResource(R.string.settings_section_danmaku),
                    icon = BilbyIcons.Danmaku,
                    onClick = { onOpenSection(SettingsSection.Danmaku) },
                )
                SettingRow(
                    title = stringResource(R.string.settings_section_sponsorblock),
                    icon = Icons.Outlined.FastForward,
                    value = state.loaded.then { onOffLabel(state.sponsorBlock.enabled) },
                    onClick = { onOpenSection(SettingsSection.SponsorBlock) },
                )
                SettingRow(
                    title = stringResource(R.string.settings_section_offline),
                    icon = Icons.Outlined.DownloadForOffline,
                    value = state.loaded.then {
                        stringResource(R.string.settings_offline_concurrency_value, state.offlineConcurrency)
                    },
                    onClick = { onOpenSection(SettingsSection.Offline) },
                )
                SettingRow(
                    title = stringResource(R.string.settings_section_agent),
                    icon = Icons.Outlined.AutoAwesome,
                    value = state.llm?.let { llm ->
                        if (llm.isConfigured) {
                            stringResource(R.string.settings_configured)
                        } else {
                            notConfigured
                        }
                    },
                    onClick = { onOpenSection(SettingsSection.Agent) },
                )
                SettingRow(
                    title = stringResource(R.string.settings_section_privacy),
                    icon = Icons.Outlined.Shield,
                    onClick = { onOpenSection(SettingsSection.Privacy) },
                )
                SettingRow(
                    title = stringResource(R.string.settings_section_about),
                    icon = Icons.Outlined.Info,
                    value = BuildConfig.VERSION_NAME,
                    onClick = { onOpenSection(SettingsSection.About) },
                )
                // **登出留在首页,不进任何子页。** 它是这一页唯一的破坏性动作,埋进二级菜单
                // 反而更危险:找不到的时候人会挨个点进去翻。
                SectionTitle(stringResource(R.string.settings_section_account))
                SettingRow(
                    title = stringResource(R.string.settings_logout),
                    icon = Icons.AutoMirrored.Outlined.Logout,
                    onClick = { confirmingLogout = true },
                )
            }
        }
    }

    if (confirmingLogout) {
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            title = { Text(stringResource(R.string.settings_logout)) },
            text = { Text(stringResource(R.string.settings_logout_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLogout = false
                    onLogout()
                }) { Text(stringResource(R.string.settings_logout)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLogout = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * 组标签。比 [SectionTitle] 轻一档:它分的是同一节内部的几组,不是另起一节。
 */
@Composable
internal fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = Spacing.Comfortable,
            end = Spacing.Comfortable,
            top = Spacing.Cozy,
            bottom = Spacing.Hair,
        ),
    )
}

/**
 * 拖动中只改本地状态,**松手才回调 [onChange]**。
 *
 * `onValueChange` 是逐帧回调的,直接接到持久化上等于把一次拖动变成十几次 DataStore 写;
 * 而 `danmakuPrefs` 每写一次,所有还活着的播放页 ViewModel 都会被唤醒收一遍。
 */
@Composable
internal fun SliderSettingRow(
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
 *
 * 过滤完之后就是一个普通的「几档选一」,所以套 [ChoiceRow]。它当初单写一份是因为选项要过滤、
 * 标签来自枚举自己,而这两件事在调用点做掉即可,不必让通用组件多两个参数。
 */
@Composable
internal fun CodecSection(
    selected: CodecPreference,
    hardwareCodecIds: Set<Int>,
    onChange: (CodecPreference) -> Unit,
) {
    ChoiceRow(
        title = stringResource(R.string.settings_codec),
        // 说清生效时机:改完不重开当前视频,不为一个设置项打断正在看的东西。
        subtitle = stringResource(R.string.settings_codec_subtitle),
        options = CodecPreference.entries.filter { option ->
            option.requiredCodecId()?.let { it in hardwareCodecIds } ?: true
        },
        selected = selected,
        label = { it.label },
        onChange = onChange,
    )
}

/**
 * 「几档选一」:**一行显示当前值,点开才是那几档**。
 *
 * 原先每一档都常驻画一行单选。七处这样的设置加起来占了整页三分之二的高度,而其中六处是
 * 设一次就再不动的东西 —— 用户为了翻到下一节,每次都要滚过二十几行自己早就选定的选项。
 * 一屏铺开唯一的好处是"能同时看见所有档",而设置页要回答的问题是"现在是哪一档",
 * 那个答案现在写在行尾。
 *
 * 对话框而不是下拉菜单:档位最多的那两项(默认画质)有七档,下拉菜单在小屏上会顶到边缘,
 * 而对话框自带滚动和标题。
 */
@Composable
internal fun <T> ChoiceRow(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onChange: (T) -> Unit,
    subtitle: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(
        title = title,
        subtitle = subtitle,
        value = selected?.let { label(it) },
        onClick = { open = true },
    )
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            // selectableGroup:读屏把这几行念成一组单选,而不是几个互不相干的按钮。
            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { option ->
                    ListItem(
                        headlineContent = { Text(label(option)) },
                        leadingContent = { RadioButton(selected = option == selected, onClick = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == selected,
                                role = Role.RadioButton,
                                onClick = {
                                    open = false
                                    onChange(option)
                                },
                            ),
                    )
                }
            }
        },
        // 选中即生效即关闭,所以只留取消。多一个「确定」等于让人按两次才改得掉一项。
        confirmButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * 滚动弹幕显示区域的四档。存的是连续的 Float(公共 API 不写死成枚举,见 [DanmakuViewport]),
 * 界面上只给这四档,选中判定按最近档取——将来加档或换成连续滑杆都不必改存储格式。
 */
internal val SCROLL_SHOW_AREA_STEPS = listOf(0.25f, 0.5f, 0.75f, 1f)

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
internal fun UpdateRow(
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
internal fun SectionTitle(text: String) {
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

/**
 * @param value 这一项**当前是什么**,显示在行尾箭头之前。设置首页那七个入口靠它做到"不点进去
 *   也知道现在设成了什么" —— 一层菜单换来的如果是每次都得点进去看一眼,那这层菜单是净亏。
 */
@Composable
internal fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    /**
     * 行首图标。**只有设置首页那一层给**:那七行是七个去处,图标让它们在滚动中彼此可分。
     * 子页里的每一行是一项设置而不是一个去处,逐行配图标只会让人以为它们还能再点进去。
     */
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = icon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        supportingContent = subtitle?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = if (onClick != null || value != null) {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (value != null) {
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (onClick != null) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            null
        },
        modifier = Modifier
            .then(
                if (onClick != null) {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .fillMaxWidth(),
    )
}

/**
 * 整行可切换。用 `Modifier.toggleable` + role 而不是给控件单独挂 onClick:
 * 前者让整行成为一个语义节点(读屏念"开关,已开启,自动跳过"),后者只有那个小控件能点。
 */
@Composable
internal fun ToggleSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    indent: Boolean = false,
    useCheckbox: Boolean = false,
) {
    ListItem(
        headlineContent = {
            Text(title, style = MaterialTheme.typography.bodyLarge)
        },
        supportingContent = subtitle?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            if (useCheckbox) {
                Checkbox(checked = checked, onCheckedChange = null)
            } else {
                Switch(checked = checked, onCheckedChange = null)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            // toggleable 在 padding **外面**:反过来的话内边距那一圈不在触摸区里,涟漪也只
            // 盖住中间一块。ListItem 自带 16dp 横向内边距和 56dp 起的行高,所以这里只补
            // 子项相对父项的那一档缩进,不再重复给一份左右边距。
            .toggleable(
                value = checked,
                role = if (useCheckbox) Role.Checkbox else Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(start = if (indent) Spacing.Tight else 0.dp),
    )
}

/**
 * LLM 三项一起改。三个框在一个框里,是因为它们只有凑齐了才有意义 ——
 * 单改一项存下去,中间那个状态是"配了地址没配 key",助理照样跑不起来。
 */
@Composable
internal fun LlmDialog(initial: LlmConfig, onDismiss: () -> Unit, onConfirm: (LlmConfig) -> Unit) {
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
                    label = { Text(stringResource(R.string.settings_llm_base_url)) },
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
internal fun TextFieldDialog(
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

/**
 * 值没到齐时不给摘要。**留空,不是给一个默认值** —— 首页每行右侧那串字是"现在是什么"的
 * 回答,先答错再改口比不答更糟。见 [SettingsUiState.loaded]。
 */
@Composable
private inline fun Boolean.then(text: @Composable () -> String): String? = if (this) text() else null
