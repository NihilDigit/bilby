package dev.bilby.ui.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import dev.bilby.ui.components.PaneSheet
import dev.bilby.ui.components.BilbyIcons
import dev.bilby.ui.player.PlayerIconButton
import dev.bilby.ui.player.PlayerSidePanel
import dev.bilby.ui.player.PlayerSidePanelHeader
import dev.bilby.ui.player.PlayerTooltip
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import dev.bilby.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.data.DanmakuPrefs
import dev.bilby.data.DanmakuPrefsEditor
import dev.bilby.data.SettingsStore
import dev.bilby.data.QualityOption
import dev.bilby.player.SleepTimerMode
import dev.bilby.player.SleepTimerState
import dev.bilby.player.SubtitleTrack
import dev.bilby.player.audioQualityLabel
import dev.bilby.resources.*
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.rememberClockFont
import dev.bilby.ui.theme.tabular
import dev.bilby.ui.player.SleepTimerCompactSection
import dev.bilby.ui.player.SleepTimerStandaloneContent
import dev.bilby.ui.player.formatSpeed
import dev.bilby.ui.player.speedHundredths
import dev.bilby.ui.player.speedNumber
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.nihildigit.danmaku.DanmakuDensity
import dev.nihildigit.danmaku.DanmakuFrameRateCap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放设置:倍速入口、清晰度、音质、字幕、弹幕,竖排。内嵌时装在底部 sheet 里,全屏时装在右侧
 * 面板里(见 BilbyPlayer),内容是同一份。全屏控制条上的 chip 打开同一块面板里的一段,
 * 见 [PlayerSettingsSection]。
 *
 * **三个下拉菜单合成一块面板。** 原先三个图标各挂一个 DropdownMenu,那是表单式控件:浮在
 * 全屏画面上一块白底长列表,横屏时十来档清晰度纵向拉出去半屏;而三者本来就是"这个播放器
 * 现在怎么放"的同一组设置。
 *
 * 清晰度与字幕的档名长短不一、条数不定,以 toggle button 排成可换行的一片。
 * 选中即生效、面板不关;倍速入口则关闭面板,打开页面上的贴边选择器。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerSettingsContent(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    qualities: List<QualityOption>,
    currentQuality: Int,
    onQualityChange: (Int) -> Unit,
    subtitleTracks: List<SubtitleTrack>,
    currentSubtitleLan: String,
    onSubtitleTrackChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 这次下发了的音轨 id,从高到低。只有一条或拿不到时这一段不画。 */
    audioOptions: List<Int> = emptyList(),
    currentAudio: Int = 0,
    onAudioChange: (Int) -> Unit = {},
    danmakuPrefs: DanmakuPrefs = DanmakuPrefs(),
    danmakuEditor: DanmakuPrefsEditor? = null,
    sleepTimer: SleepTimerState = SleepTimerState(),
    itemRemainingMillis: Long? = null,
    onSleepTimer: ((SleepTimerMode) -> Unit)? = null,
    onSleepTimerAdjust: (Int) -> Unit = {},
    onDismiss: () -> Unit = {},
    /** 只画这一段(全屏控制条上的 chip 打开的)。null 画整块。 */
    only: PlayerSettingsSection? = null,
    /** 宿主是整屏高的横屏右侧栏:单开倍速时铺开大号读数舞台填满纵向空间,竖屏底部面板不受影响。 */
    fullHeightHost: Boolean = false,
) {
    if (only == PlayerSettingsSection.Speed) {
        ExpandedSpeedPanel(
            speed = speed,
            onSpeedChange = onSpeedChange,
            fillHeight = fullHeightHost,
            modifier = modifier,
        )
        return
    }
    if (only == PlayerSettingsSection.SleepTimer && onSleepTimer != null) {
        PanelColumn(modifier) {
            SleepTimerStandaloneContent(
                sleepTimer = sleepTimer,
                itemRemainingMillis = itemRemainingMillis,
                onSet = onSleepTimer,
                onAdjust = onSleepTimerAdjust,
                onDismiss = onDismiss,
                modifier = Modifier.padding(vertical = Spacing.Hair),
            )
        }
        return
    }
    val titled = only == null
    // 只开一段时一行一项:这时面板里只有这一组,竖着排一眼扫完,不必横着挤。
    val columns = if (only == null) GridColumns else 1
    PanelColumn(modifier) {
        if (only == null || only == PlayerSettingsSection.Speed) {
            SpeedSection(speed = speed, onSpeedChange = onSpeedChange, titled = titled)
        }

        if (only == null || only == PlayerSettingsSection.Quality) {
            QualitySection(qualities, currentQuality, onQualityChange, titled)
        }

        // 音质:列这次真下发了的几条音轨(见 SelectedStreams.audioOptions),规则同画质那一段。
        if ((only == null || only == PlayerSettingsSection.Audio) && audioOptions.size > 1) {
            if (titled) SectionTitle(stringResource(Res.string.player_audio_quality))
            ChoiceGrid(
                options = audioOptions,
                selected = { it == currentAudio },
                onSelect = onAudioChange,
                label = { audioQualityLabel(it) },
                columns = columns,
            )
        }

        if ((only == null || only == PlayerSettingsSection.Subtitle) && subtitleTracks.isNotEmpty()) {
            if (titled) SectionTitle(stringResource(Res.string.player_subtitle))
            val offLabel = stringResource(Res.string.player_subtitle_off)
            // null 是"关闭"那一格。
            val options: List<SubtitleTrack?> = listOf(null) + subtitleTracks
            ChoiceGrid(
                options = options,
                selected = { (it?.lan ?: "") == currentSubtitleLan },
                onSelect = { onSubtitleTrackChange(it?.lan ?: "") },
                label = { it?.displayName ?: offLabel },
                columns = columns,
            )
        }

        if (only == null && onSleepTimer != null) {
            SleepTimerCompactSection(
                sleepTimer = sleepTimer,
                itemRemainingMillis = itemRemainingMillis,
                onSet = onSleepTimer,
                onAdjust = onSleepTimerAdjust,
            )
        }

        if ((only == null || only == PlayerSettingsSection.Danmaku) && danmakuEditor != null) {
            DanmakuSettingsSection(danmakuPrefs, danmakuEditor)
        }
    }
}

/** 直播间的设置面板:画质、弹幕。"只听声音"不在这里,它在控制条上。 */
@Composable
internal fun LiveSettingsContent(
    qualities: List<QualityOption>,
    currentQuality: Int,
    onQualityChange: (Int) -> Unit,
    danmakuPrefs: DanmakuPrefs,
    danmakuEditor: DanmakuPrefsEditor,
    modifier: Modifier = Modifier,
    only: PlayerSettingsSection? = null,
) {
    PanelColumn(modifier) {
        if (only == null || only == PlayerSettingsSection.Quality) {
            QualitySection(qualities, currentQuality, onQualityChange, titled = only == null)
        }
        if (only == null || only == PlayerSettingsSection.Danmaku) DanmakuSettingsSection(danmakuPrefs, danmakuEditor)
    }
}

/**
 * 面板里能被单独打开的几段,对应宽排法控制条上那几枚 chip 与弹幕设置键。整块面板只从
 * 窄排法的 ⚙ 打开:宽排法里每一段都有自己的入口,整块面板只是把它们再摆一遍。
 *
 * chip 只开自己那一段,不开整块:几枚 chip 看起来各管一项,点开却是同一块长面板,读不出
 * 它们之间有什么区别。
 */
internal enum class PlayerSettingsSection { Speed, Quality, Audio, Subtitle, SleepTimer, Danmaku }

/**
 * 设置面板的外壳,点播与直播共用。全屏从右边划出(横屏下底部 sheet 只剩一条缝),内嵌时
 * 两栏画在右栏、单栏从底部弹出(见 [PaneSheet]),内容是同一份。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoxScope.PlayerSettingsHost(
    isFullscreen: Boolean,
    open: Boolean,
    only: PlayerSettingsSection?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val sheetTitle = if (only == PlayerSettingsSection.SleepTimer) null else playerSettingsTitle(only)
    if (isFullscreen) {
        // 窄的那一档只给一列短选项的单选段;弹幕、倍速、定时关闭分别带滑块、连体按钮或三列钟面预设,窄了挤不开。
        val narrow = only != null &&
            only != PlayerSettingsSection.Danmaku &&
            only != PlayerSettingsSection.Speed &&
            only != PlayerSettingsSection.SleepTimer
        PlayerSidePanel(visible = open, onDismiss = onDismiss, narrow = narrow) {
            // 统一侧栏顶栏(带 48dp 关闭按钮,`side-sheets.md:354`);定时关闭单开时 SleepClock 自己在下方写「定时关闭」或倒计时。
            PlayerSidePanelHeader(
                title = sheetTitle.orEmpty(),
                onClose = onDismiss,
            )
            content()
        }
    } else if (open) {
        // 两栏时画在右栏,画面不挡,调画质、倍速时看得见效果;底部系统安全区由统一 PaneSheet 负责。
        PaneSheet(onDismissRequest = onDismiss, title = sheetTitle) {
            content()
        }
    }
}

/** 窄排法里打开整块设置面板的 ⚙。点播与直播都是这一枚,位置在全屏键左边。 */
@Composable
internal fun PlayerSettingsButton(selected: Boolean = false, onClick: () -> Unit) {
    val label = stringResource(Res.string.player_settings)
    PlayerTooltip(label) {
        PlayerIconButton(onClick = onClick, icon = Icons.Filled.Tune, contentDescription = label, selected = selected)
    }
}

/** 宽排法里只开弹幕那一段的键,紧挨弹幕开关。字形与开关同一个小电视,见 [BilbyIcons.DanmakuSettings]。 */
@Composable
internal fun DanmakuSettingsButton(onClick: () -> Unit) {
    val label = stringResource(Res.string.player_danmaku_settings)
    PlayerTooltip(label) {
        PlayerIconButton(onClick = onClick, icon = BilbyIcons.DanmakuSettings, contentDescription = label)
    }
}

/** 面板的标题:整块叫"播放设置",只开一段时用那一段的名字。 */
@Composable
private fun playerSettingsTitle(only: PlayerSettingsSection?): String = stringResource(
    when (only) {
        null -> Res.string.player_settings
        PlayerSettingsSection.Speed -> Res.string.player_speed
        PlayerSettingsSection.Quality -> Res.string.player_quality
        PlayerSettingsSection.Audio -> Res.string.player_audio_quality
        PlayerSettingsSection.Subtitle -> Res.string.player_subtitle
        PlayerSettingsSection.SleepTimer -> Res.string.sleep_timer_off
        PlayerSettingsSection.Danmaku -> Res.string.player_danmaku_settings
    },
)

/**
 * 单独打开倍速时的 M3E 精调面板：
 * 顶层 tonal 卡片承载大号等宽读数、±0.05x 步进微调按钮、一键复位 1x 与连续无挡点滑杆，
 * 下方配两行四列常用快捷倍速；兼顾整高侧栏居中排布与底部弹出面板自适应滚动。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpandedSpeedPanel(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    fillHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val hundredths = speedHundredths(speed)
    val isDefault = abs(speed - 1f) < 0.005f
    val lessLabel = stringResource(Res.string.player_speed_decrease)
    val moreLabel = stringResource(Res.string.player_speed_increase)
    val resetLabel = stringResource(Res.string.player_speed_reset)
    val defaultLabel = stringResource(Res.string.player_speed_default)

    val panelBody: @Composable () -> Unit = {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Comfortable),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FilledTonalIconButton(
                        onClick = { onSpeedChange(stepFineSpeed(speed, -1)) },
                        enabled = hundredths > SpeedMinHundredths,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.semantics { contentDescription = lessLabel },
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = null)
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = speedNumber(hundredths),
                                style = TextStyle(
                                    fontFamily = rememberClockFont(),
                                    fontSize = MaterialTheme.typography.displayMedium.fontSize,
                                    lineHeight = MaterialTheme.typography.displayMedium.lineHeight,
                                    fontFeatureSettings = "tnum",
                                ),
                                color = if (isDefault) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                maxLines = 1,
                                modifier = Modifier.alignByBaseline(),
                            )
                            Text(
                                text = "x",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.alignByBaseline().padding(start = 2.dp),
                            )
                        }
                        if (isDefault) {
                            Text(
                                text = defaultLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = Spacing.Hair),
                            )
                        } else {
                            TextButton(
                                onClick = { onSpeedChange(1f) },
                                contentPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = 0.dp),
                                modifier = Modifier.height(SpeedResetActionHeight),
                            ) {
                                Text(resetLabel, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    FilledTonalIconButton(
                        onClick = { onSpeedChange(stepFineSpeed(speed, 1)) },
                        enabled = hundredths < SpeedMaxHundredths,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.semantics { contentDescription = moreLabel },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.Cozy))
                FineSpeedSlider(
                    speed = speed,
                    onSpeedChange = onSpeedChange,
                    showTicks = true,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.Comfortable))
        SpeedPresetGrid(speed = speed, onSpeedChange = onSpeedChange)
    }

    if (!fillHeight) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        ) {
            panelBody()
            Spacer(modifier = Modifier.height(Spacing.Cozy))
        }
        return
    }

    val density = LocalDensity.current
    var panelHeight by remember { mutableStateOf(CompactFallbackHeight) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { panelHeight = with(density) { it.height.toDp() } }
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
    ) {
        if (panelHeight < CompactFallbackHeight) {
            PanelColumn(Modifier) {
                SpeedSection(speed = speed, onSpeedChange = onSpeedChange, titled = false)
            }
            return@Box
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.weight(0.75f))
            panelBody()
            Spacer(modifier = Modifier.weight(1.1f))
        }
    }
}

/**
 * 倍速(整块播放设置面板内的紧凑节): 行内 ±0.05x 微调、连续精调滑杆与 2×4 快捷预设，与单独面板共用精调能力。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpeedSection(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    titled: Boolean,
) {
    val hundredths = speedHundredths(speed)
    val isDefault = abs(speed - 1f) < 0.005f
    val lessLabel = stringResource(Res.string.player_speed_decrease)
    val moreLabel = stringResource(Res.string.player_speed_increase)
    val resetLabel = stringResource(Res.string.player_speed_reset)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (titled) Spacing.Cozy else Spacing.Hair, bottom = Spacing.Hair),
    ) {
        if (titled) {
            Text(
                stringResource(Res.string.player_speed),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (!isDefault) {
            TextButton(
                onClick = { onSpeedChange(1f) },
                contentPadding = PaddingValues(horizontal = Spacing.Tight, vertical = 0.dp),
                modifier = Modifier.height(SpeedCompactStepperSize),
            ) {
                Text(resetLabel, style = MaterialTheme.typography.labelMedium)
            }
        }
        FilledTonalIconButton(
            onClick = { onSpeedChange(stepFineSpeed(speed, -1)) },
            enabled = hundredths > SpeedMinHundredths,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.semantics { contentDescription = lessLabel },
        ) {
            Icon(Icons.Filled.Remove, contentDescription = null, modifier = Modifier.size(Dimens.IconInline))
        }
        Text(
            formatSpeed(speed),
            style = MaterialTheme.typography.titleSmall.tabular,
            color = if (isDefault) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = Spacing.Tight),
        )
        FilledTonalIconButton(
            onClick = { onSpeedChange(stepFineSpeed(speed, 1)) },
            enabled = hundredths < SpeedMaxHundredths,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.semantics { contentDescription = moreLabel },
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(Dimens.IconInline))
        }
    }
    FineSpeedSlider(speed = speed, onSpeedChange = onSpeedChange, showTicks = false)
    SpeedPresetGrid(speed = speed, onSpeedChange = onSpeedChange)
}

@Composable
private fun FineSpeedSlider(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    showTicks: Boolean,
) {
    val clamped = speed.coerceIn(SpeedSliderRange.start, SpeedSliderRange.endInclusive)
    Column(modifier = Modifier.fillMaxWidth()) {
        // 连续轨道不画密集刻度点，拖动时以 0.05x 精度实时吸附。
        Slider(
            value = clamped,
            onValueChange = { raw ->
                val snapped = snapFineSpeed(raw)
                if (abs(snapped - speed) >= 0.005f) onSpeedChange(snapped)
            },
            valueRange = SpeedSliderRange,
        )
        if (showTicks) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Hair),
            ) {
                SpeedScaleTicks.forEach { tick ->
                    val active = abs(tick - speed) < 0.01f
                    Text(
                        text = formatSpeed(tick),
                        style = MaterialTheme.typography.labelSmall.tabular,
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable(role = Role.Button) { onSpeedChange(tick) }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpeedPresetGrid(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        modifier = Modifier.fillMaxWidth(),
    ) {
        SpeedQuickPresets.chunked(SpeedPresetColumns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                row.forEach { preset ->
                    ToggleButton(
                        checked = abs(preset - speed) < 0.01f,
                        onCheckedChange = { onSpeedChange(preset) },
                        contentPadding = PaddingValues(horizontal = Spacing.Hair),
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 0.dp)
                            .semantics { role = Role.RadioButton },
                    ) {
                        Text(
                            formatSpeed(preset),
                            style = MaterialTheme.typography.labelLarge.tabular,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 画质。没有可选的就整段不画:一段只有一个选项的单选是纯噪声。[titled] 为假时是 chip 单独打开的
 * 这一段,不画段标题(面板标题就是它),一行一项。
 */
@Composable
private fun QualitySection(
    qualities: List<QualityOption>,
    currentQuality: Int,
    onQualityChange: (Int) -> Unit,
    titled: Boolean,
) {
    if (qualities.size <= 1) return
    if (titled) SectionTitle(stringResource(Res.string.player_quality))
    ChoiceGrid(
        options = qualities,
        selected = { it.quality == currentQuality },
        onSelect = { onQualityChange(it.quality) },
        // 不截断:两档截断之后可能一模一样("1080P 高码率" 与 "1080P60")。
        label = { it.label },
        columns = if (titled) GridColumns else 1,
    )
}

@Composable
private fun PanelColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                start = Spacing.Comfortable,
                end = Spacing.Comfortable,
                top = Spacing.Tight,
                bottom = Spacing.Comfortable,
            ),
        content = content,
    )
}

/**
 * 弹幕的几项设置。改完立即生效、面板不关,和这块面板其余几段一样:调的时候人要看着画面。
 *
 * 显示区域、密度、帧率一变,当前窗口按新参数重排,屏上的弹幕会跳一下,这是重排本身带来的。
 * 透明度松手才生效:它同样会让弹幕引擎整池重建,拖动中逐档生效就是一路重建。
 */
@Composable
private fun DanmakuSettingsSection(prefs: DanmakuPrefs, editor: DanmakuPrefsEditor) {
    // 松手前只动滑块自己。键是存下来的值:别处改了它(另一个页面、下一次打开面板),这里跟着。
    var opacity by remember(prefs.opacity) { mutableFloatStateOf(prefs.opacity) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(Res.string.settings_danmaku_opacity), Modifier.weight(1f))
        Text(
            stringResource(Res.string.settings_danmaku_opacity_value, (opacity * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge.tabular,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        value = opacity,
        onValueChange = { opacity = it },
        onValueChangeFinished = { editor.setOpacity(opacity) },
        valueRange = OpacityRange,
        // 10% 一档,两端之间 8 个刻度。
        steps = OpacitySteps,
    )

    // 字号倍数,同透明度一样松手才落盘。倍数乘在按播放器形态分好的档上,全屏与内嵌之间的
    // 大小关系不变(见 DanmakuFontSizeSp)。
    var fontScale by remember(prefs.fontScale) { mutableFloatStateOf(prefs.fontScale) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(Res.string.settings_danmaku_font_scale), Modifier.weight(1f))
        Text(
            stringResource(Res.string.settings_danmaku_opacity_value, (fontScale * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge.tabular,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        value = fontScale,
        onValueChange = { fontScale = it },
        onValueChangeFinished = { editor.setFontScale(fontScale) },
        valueRange = FontScaleRange,
        steps = FontScaleSteps,
    )

    SectionTitle(stringResource(Res.string.settings_danmaku_show_area))
    SectionNote(stringResource(Res.string.settings_danmaku_show_area_subtitle))
    val areaLabel25 = stringResource(Res.string.settings_danmaku_show_area_value, 25)
    val areaLabel50 = stringResource(Res.string.settings_danmaku_show_area_value, 50)
    val areaLabel75 = stringResource(Res.string.settings_danmaku_show_area_value, 75)
    val areaLabel100 = stringResource(Res.string.settings_danmaku_show_area_value, 100)
    val areaLabels = remember(areaLabel25, areaLabel50, areaLabel75, areaLabel100) {
        mapOf(0.25f to areaLabel25, 0.5f to areaLabel50, 0.75f to areaLabel75, 1f to areaLabel100)
    }
    ConnectedChoices(
        options = DanmakuShowAreaSteps,
        // 存的是连续值,取最近的一档亮起。
        selected = { it == DanmakuShowAreaSteps.minBy { step -> abs(step - prefs.scrollShowArea) } },
        onSelect = editor::setScrollShowArea,
        label = { areaLabels.getValue(it) },
    )

    SectionTitle(stringResource(Res.string.settings_danmaku_density))
    val standard = stringResource(Res.string.settings_danmaku_density_standard)
    val unlimited = stringResource(Res.string.settings_danmaku_density_unlimited)
    ConnectedChoices(
        options = DanmakuDensity.entries,
        selected = { it == prefs.density },
        onSelect = editor::setDensity,
        label = {
            when (it) {
                DanmakuDensity.STANDARD -> standard
                DanmakuDensity.UNLIMITED -> unlimited
            }
        },
    )

    SectionTitle(stringResource(Res.string.settings_danmaku_frame_rate))
    SectionNote(stringResource(Res.string.settings_danmaku_frame_rate_subtitle))
    val fps30 = stringResource(Res.string.settings_danmaku_frame_rate_30)
    val fps60 = stringResource(Res.string.settings_danmaku_frame_rate_60)
    val display = stringResource(Res.string.settings_danmaku_frame_rate_display)
    ConnectedChoices(
        options = DanmakuFrameRateCap.entries,
        selected = { it == prefs.frameRateCap },
        onSelect = editor::setFrameRate,
        label = {
            when (it) {
                DanmakuFrameRateCap.FPS_30 -> fps30
                DanmakuFrameRateCap.FPS_60 -> fps60
                DanmakuFrameRateCap.DISPLAY -> display
            }
        },
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.Cozy)
            .toggleable(value = prefs.inPip, role = Role.Switch, onValueChange = editor::setInPip),
    ) {
        Text(
            stringResource(Res.string.settings_danmaku_in_pip),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // 整行可点,开关本身不再单独接点击,否则一次点击会被两处各处理一遍。
        Switch(checked = prefs.inPip, onCheckedChange = null)
    }
}

/**
 * 连成一条的单选组,各格等宽。
 *
 * 默认内边距左右各 16dp,倍速六档平分 360dp 宽时每格只剩二十来 dp 放字,所以收到最小。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ConnectedChoices(
    options: List<T>,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    label: (T) -> String,
) {
    val interactionSources = remember(options.size) { List(options.size) { MutableInteractionSource() } }
    ButtonGroup(
        overflowIndicator = {},
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            val interactionSource = interactionSources[index]
            customItem(
                buttonGroupContent = {
                    ToggleButton(
                        checked = selected(option),
                        onCheckedChange = { onSelect(option) },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        contentPadding = PaddingValues(horizontal = Spacing.Hair),
                        interactionSource = interactionSource,
                        modifier = Modifier
                            .weight(1f)
                            .animateWidth(interactionSource)
                            .semantics { role = Role.RadioButton },
                    ) {
                        Text(label(option), maxLines = 1, softWrap = false)
                    }
                },
                menuContent = {},
            )
        }
    }
}

private val SpeedQuickPresets = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f)
private const val SpeedPresetColumns = 4
private val SpeedScaleTicks = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 3.5f)
private val SpeedSliderRange = 0.25f..3.5f
private const val SpeedMinHundredths = 25
private const val SpeedMaxHundredths = 350
private const val SpeedFineStepHundredths = 5
private val SpeedCompactStepperSize = 32.dp
private val SpeedResetActionHeight = 28.dp
private val CompactFallbackHeight = 260.dp

private fun snapFineSpeed(raw: Float): Float {
    val hundredths = ((raw * 100f / SpeedFineStepHundredths).roundToInt() * SpeedFineStepHundredths)
        .coerceIn(SpeedMinHundredths, SpeedMaxHundredths)
    return hundredths / 100f
}

private fun stepFineSpeed(current: Float, deltaSteps: Int): Float {
    val base = speedHundredths(current)
    val snapped = ((base.toFloat() / SpeedFineStepHundredths).roundToInt() + deltaSteps) * SpeedFineStepHundredths
    return snapped.coerceIn(SpeedMinHundredths, SpeedMaxHundredths) / 100f
}

/**
 * 滚动弹幕显示区域的四档。存的是连续值(见 `SettingsStore.saveDanmakuScrollShowArea`),选中判定
 * 按最近档取,将来加档或换成连续滑杆都不必改存储格式。
 */
private val DanmakuShowAreaSteps = listOf(0.25f, 0.5f, 0.75f, 1f)

/** 与 `SettingsStore.saveDanmakuOpacity` 的取值范围一致。 */
private val OpacityRange = 0.1f..1f
private const val OpacitySteps = 8

/** 50% 到 200%,10% 一档:两端之间 14 个刻度。 */
private val FontScaleRange = SettingsStore.DANMAKU_FONT_SCALE_MIN..SettingsStore.DANMAKU_FONT_SCALE_MAX
private const val FontScaleSteps = 14

/**
 * 等宽的单选网格,整块面板里两列,单开一段时一列。**等宽而不是按字长排开**:档名长短差得多
 * ("360P" 与 "1080P 高码率"),各按字长排成一片时每行断在不同位置,选中那一格的位置也跟着乱跳。
 * 等宽之后一眼扫得出一共几档、选的是第几档;奇数个时最后一行留空位,不让最后一格独自撑满一整行。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ChoiceGrid(
    options: List<T>,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    columns: Int = GridColumns,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        modifier = Modifier.selectableGroup(),
    ) {
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                row.forEach { option ->
                    ToggleButton(
                        checked = selected(option),
                        onCheckedChange = { onSelect(option) },
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    ) {
                        Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                repeat(columns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

private const val GridColumns = 2

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Spacing.Cozy, bottom = Spacing.Tight),
    )
}

/** 段标题下的一句说明,讲清这一项不管什么。 */
@Composable
private fun SectionNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Spacing.Tight),
    )
}

