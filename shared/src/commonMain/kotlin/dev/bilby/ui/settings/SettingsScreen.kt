package dev.bilby.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Palette
import dev.bilby.data.AppearancePrefs
import dev.bilby.ui.theme.ThemePalette
import dev.bilby.ui.theme.dynamicColorAvailable
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.bilby.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import dev.bilby.AppBuild
import dev.bilby.update.AvailableUpdate
import dev.bilby.update.UpdateStatus
import java.net.URI
import java.net.URISyntaxException
import dev.bilby.resources.*
import dev.bilby.data.CodecPreference
import dev.bilby.data.LlmConfig
import dev.bilby.data.SettingsStore
import dev.bilby.player.videoQualityLabel
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Spacing

/**
 * 设置首页。**只放入口,没有一项设置直接躺在这里**(登出除外,见下)。
 *
 * 重做之前这一页是一整条铺开的清单:七节、六十多行,其中三分之二是「几档选一」常驻画出来的
 * 单选行。要改一项弹幕密度得滚过二十几行自己早就选定的画质和编解码。
 *
 * **每一行标题下给当前值。** 一层菜单换来的如果是每次都要点进去才知道现在设成了什么,那这层
 * 菜单是净亏 —— 摘要是它成立的前提,不是装饰。
 *
 * **五个去处。** 缓存和 SponsorBlock 原先各占一页,一页只有一两行,点进去看到的几乎是空页;
 * 两者都是"播放时怎么做",并进播放页各成一组。
 *
 * **不再分宽屏双栏。** 那是为六十多行准备的;五行入口拆两栏只会让右边一栏空着。
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
    val notConfigured = stringResource(Res.string.settings_not_configured)

    // pinned 而不是 enterAlways:顶栏留着不动,内容滚起来之后只换一档容器色。这一页的入口
    // 不满一屏,顶栏跟着一起走反而像页面自己跳了一下。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(
                title = stringResource(Res.string.settings_title),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            )
        },
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
                // 五个去处装成一组,每个去处一块分段。
                SettingsGroup {
                    row { position ->
                        SettingRow(
                            position = position,
                            title = stringResource(Res.string.settings_section_appearance),
                            icon = Icons.Outlined.Palette,
                            // 摘要给配色的名字:明暗在这一页上一眼看得出来,配色要进去才知道是哪一套。
                            value = state.loaded.then { paletteLabel(state.appearance.palette) },
                            target = RowTarget.Page,
                            onClick = { onOpenSection(SettingsSection.Appearance) },
                        )
                    }
                    row { position ->
                        SettingRow(
                            position = position,
                            title = stringResource(Res.string.settings_section_playback),
                            icon = Icons.Outlined.PlayCircleOutline,
                            // 写这一页有什么,不写某一项的当前值:四组设置里挑哪一项的值都只说出了
                            // 一部分,而「1080P  自动跳过片段」读起来像一句话没说完。同隐私那一行。
                            value = stringResource(Res.string.settings_playback_summary),
                            target = RowTarget.Page,
                            onClick = { onOpenSection(SettingsSection.Playback) },
                        )
                    }
                    row { position ->
                        SettingRow(
                            position = position,
                            title = stringResource(Res.string.settings_section_agent),
                            icon = Icons.Outlined.AutoAwesome,
                            value = state.llm?.let { llm ->
                                if (llm.isConfigured) {
                                    stringResource(Res.string.settings_configured)
                                } else {
                                    notConfigured
                                }
                            },
                            target = RowTarget.Page,
                            onClick = { onOpenSection(SettingsSection.Agent) },
                        )
                    }
                    row { position ->
                        SettingRow(
                            position = position,
                            title = stringResource(Res.string.settings_section_privacy),
                            icon = Icons.Outlined.Shield,
                            // 这一页的值要么在服务端(暂停记录),要么是名单,首页给不出一个值,
                            // 于是写这一页管什么。空着的话这一行比别的行矮一截,像是没加载完。
                            value = stringResource(Res.string.settings_privacy_summary),
                            target = RowTarget.Page,
                            onClick = { onOpenSection(SettingsSection.Privacy) },
                        )
                    }
                    row { position ->
                        SettingRow(
                            position = position,
                            title = stringResource(Res.string.settings_section_about),
                            icon = Icons.Outlined.Info,
                            value = AppBuild.versionName,
                            target = RowTarget.Page,
                            onClick = { onOpenSection(SettingsSection.About) },
                        )
                    }
                }
                // **登出留在首页,不进任何子页。** 它是这一页唯一的破坏性动作,埋进二级菜单
                // 反而更危险:找不到的时候人会挨个点进去翻。
                SettingsGroup(title = stringResource(Res.string.settings_section_account)) {
                    row { position ->
                        SettingRow(
                            position = position,
                            title = stringResource(Res.string.settings_logout),
                            icon = Icons.AutoMirrored.Outlined.Logout,
                            onClick = { confirmingLogout = true },
                        )
                    }
                }
            }
        }
    }

    if (confirmingLogout) {
        AlertDialog(
            onDismissRequest = { confirmingLogout = false },
            title = { Text(stringResource(Res.string.settings_logout)) },
            text = { Text(stringResource(Res.string.settings_logout_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingLogout = false
                    onLogout()
                }) { Text(stringResource(Res.string.settings_logout)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLogout = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
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
    position: RowPosition,
    selected: CodecPreference,
    hardwareCodecIds: Set<Int>,
    onChange: (CodecPreference) -> Unit,
) {
    ChoiceRow(
        position = position,
        icon = Icons.Outlined.Memory,
        title = stringResource(Res.string.settings_codec),
        // 说清生效时机:改完不重开当前视频,不为一个设置项打断正在看的东西。
        subtitle = stringResource(Res.string.settings_codec_subtitle),
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
 * 那个答案现在写在标题下面。
 *
 * 对话框而不是下拉菜单:档位最多的那两项(默认画质)有七档,下拉菜单在小屏上会顶到边缘,
 * 而对话框有标题、有自己的最大高度。
 *
 * **滚动要自己给。** `AlertDialog` 的 `text` 槽位不滚 —— 它只把内容约束在对话框的最大高度里,
 * 超出的部分直接被裁掉。默认画质那七档在小屏横屏下就超了,末尾两档点不到,而且看不出还有。
 * 这里以前的注释写着"对话框自带滚动",那是一句没核实的断言。
 */
@Composable
internal fun <T> ChoiceRow(
    position: RowPosition,
    icon: ImageVector,
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onChange: (T) -> Unit,
    subtitle: String? = null,
    /** 当前值画在行尾,见 [SettingRow] 的同名参数。 */
    valueAtEnd: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    SettingRow(
        position = position,
        icon = icon,
        title = title,
        subtitle = subtitle,
        value = selected?.let { label(it) },
        onClick = { open = true },
        valueAtEnd = valueAtEnd,
    )
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            // selectableGroup:读屏把这几行念成一组单选,而不是几个互不相干的按钮。
            Column(modifier = Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
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
            TextButton(onClick = { open = false }) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/**
 * 关于页卡片里的更新按钮。
 *
 * 没有查到新版时是「检查更新」;查到之后是「新版本 X」,点开更新对话框,下载、安装、重启都在
 * 那里做,和开屏弹出的是同一个(见 [dev.bilby.ui.update.UpdateDialog])。检查中禁用。
 * 查到新版时按钮带一个角标:状态行在卡片里偏小,这是整页唯一需要用户接着动手的状态。
 */
@Composable
internal fun UpdateRow(
    status: UpdateStatus,
    onCheck: () -> Unit,
    onOpen: (AvailableUpdate) -> Unit,
) {
    val update = status.pendingUpdate()
    val onClick: (() -> Unit)? = when {
        update != null -> ({ onOpen(update) })
        status is UpdateStatus.Checking -> null
        else -> onCheck
    }
    val label = update?.let { stringResource(Res.string.update_dialog_title, it.version) }
        ?: stringResource(Res.string.settings_update)
    FilledTonalButton(onClick = onClick ?: {}, enabled = onClick != null) {
        Icon(
            imageVector = Icons.Outlined.SystemUpdate,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
        Text(label)
        if (status is UpdateStatus.Available) {
            Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
            Badge()
        }
    }
}

/** 这个状态挂着的新版本;没有新版本可说时为 null。 */
internal fun UpdateStatus.pendingUpdate(): AvailableUpdate? = when (this) {
    is UpdateStatus.Available -> update
    is UpdateStatus.Downloading -> update
    is UpdateStatus.ReadyToRestart -> update
    is UpdateStatus.Installing -> update
    is UpdateStatus.Failed -> update
    else -> null
}

/**
 * 关于页卡片里版本号下面那一行:更新现在怎么样了。
 *
 * 检查、下载、可安装、失败读起来都是"这一项现在怎么样了",所以写成一行字。
 * 查到新版与下载完成用 primary,失败用 error,其余与版本号同色。
 */
@Composable
internal fun UpdateStatusLine(state: UpdateStatus) {
    val colors = MaterialTheme.colorScheme
    val (text, color) = when (state) {
        UpdateStatus.Idle -> stringResource(Res.string.settings_update_idle) to colors.onSurfaceVariant
        UpdateStatus.Checking -> stringResource(Res.string.settings_update_checking) to colors.onSurfaceVariant
        UpdateStatus.UpToDate -> stringResource(Res.string.settings_update_latest) to colors.onSurfaceVariant
        is UpdateStatus.Available ->
            stringResource(Res.string.settings_update_available, state.update.version) to colors.primary
        is UpdateStatus.Downloading ->
            stringResource(Res.string.settings_update_downloading, (state.progress * 100).toInt()) to
                colors.onSurfaceVariant
        is UpdateStatus.ReadyToRestart ->
            stringResource(Res.string.settings_update_ready, state.update.version) to colors.primary
        is UpdateStatus.Installing ->
            stringResource(Res.string.update_installing) to colors.onSurfaceVariant
        is UpdateStatus.Failed -> stringResource(state.reason.message) to colors.error
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
    // 下载是这一页唯一有真实百分比的等待,所以给 determinate 而不是转圈。状态行里
    // 那个数字精确,但一串跳动的数字看不出走得快还是慢,而这正是等待时要判断的事。
    if (state is UpdateStatus.Downloading) {
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.Hair),
        )
    }
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
    // 空着是合法的:那是"不配助理"。挡的只是填了、但填得不成形的那一种。
    val baseUrlValid = baseUrl.isBlank() || isHttpUrl(baseUrl)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.settings_llm_dialog)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Cozy)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(Res.string.settings_llm_base_url)) },
                    isError = !baseUrlValid,
                    supportingText = if (baseUrlValid) {
                        null
                    } else {
                        ({ Text(stringResource(Res.string.settings_url_invalid)) })
                    },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text(stringResource(Res.string.settings_api_key)) },
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
                                        Res.string.settings_key_hide
                                    } else {
                                        Res.string.settings_key_show
                                    },
                                ),
                            )
                        }
                    },
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(Res.string.settings_model)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = baseUrlValid,
                onClick = {
                    onConfirm(
                        LlmConfig(
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            model = model.trim().ifBlank { SettingsStore.DEFAULT_LLM_MODEL },
                        ),
                    )
                },
            ) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/**
 * 改一个服务地址。
 *
 * **label 常驻,不靠 placeholder 说明填的是什么。** placeholder 一开始打字就没了,而这个框
 * 里要填的是一串没有自明性的地址;对话框标题在小屏上又会被弹起的键盘顶出视野。
 *
 * 校验只挡形态不对的输入,保存按钮跟着一起禁掉 —— 存进去一个不成形的地址,症状要等到下一次
 * 播放时才以"跳过没生效"的样子出现,那时没人会想到是这里。
 */
@Composable
internal fun UrlFieldDialog(
    title: String,
    label: String,
    initial: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    val valid = isHttpUrl(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                isError = !valid,
                // 错误态配一句说明,不只是把框描红:M3 的 text fields 页把两者算一件事,
                // 而一个只变红的框说不出它嫌哪里不对。没有错误时传 null,那一行不占高度。
                supportingText = if (valid) {
                    null
                } else {
                    ({ Text(stringResource(Res.string.settings_url_invalid)) })
                },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.trim()) }, enabled = valid) {
                Text(stringResource(Res.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/**
 * 地址形态是不是对的。只认 http/https 加一个非空主机名 —— 能不能连上要发出去才知道,
 * 这里挡的是漏掉协议头、把一整段说明粘进来这类当场就看得出来的输入。
 *
 * 用 [URI] 而不是自己写正则:它按 RFC 3986 拆,而手写的正则每次都会漏掉端口、路径
 * 或 IPv6 里的某一种写法。
 */
private fun isHttpUrl(value: String): Boolean {
    val uri = try {
        URI(value.trim())
    } catch (malformed: URISyntaxException) {
        return false
    }
    val scheme = uri.scheme?.lowercase() ?: return false
    return scheme in HttpSchemes && !uri.host.isNullOrBlank()
}

private val HttpSchemes = setOf("http", "https")

/**
 * 值没到齐时不给摘要。**留空,不是给一个默认值** —— 首页每行标题下那串字是"现在是什么"的
 * 回答,先答错再改口比不答更糟。见 [SettingsUiState.loaded]。
 */
@Composable
private inline fun Boolean.then(text: @Composable () -> String): String? = if (this) text() else null


/** 配色的名字。存的是 dynamic 但系统不支持时,实际用的是默认那一套,写它的名字。 */
@Composable
internal fun paletteLabel(palette: String): String {
    if (palette == AppearancePrefs.DYNAMIC && dynamicColorAvailable) {
        return stringResource(Res.string.theme_palette_dynamic)
    }
    val resolved = ThemePalette.entries.firstOrNull { it.name == palette } ?: ThemePalette.Default
    return stringResource(resolved.label)
}
