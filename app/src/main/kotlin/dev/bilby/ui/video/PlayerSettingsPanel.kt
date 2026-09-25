package dev.bilby.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.R
import dev.bilby.data.DanmakuPrefs
import dev.bilby.data.DanmakuPrefsEditor
import dev.bilby.data.QualityOption
import dev.bilby.player.SubtitleTrack
import dev.bilby.player.audioQualityLabel
import dev.bilby.ui.theme.Spacing
import dev.nihildigit.danmaku.DanmakuDensity
import dev.nihildigit.danmaku.DanmakuFrameRateCap
import kotlin.math.abs
import kotlin.math.roundToInt

/** 倍速的几档。和长按加速不是一回事:那个在设置页里定,是临时叠上去的。 */
internal val SpeedOptions = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * 播放设置:倍速、清晰度、音质、字幕、弹幕,竖排。内嵌时装在底部 sheet 里,全屏时装在右侧
 * 面板里(见 BilbyPlayer),内容是同一份。
 *
 * **三个下拉菜单合成一块面板。** 原先三个图标各挂一个 DropdownMenu,那是表单式控件:浮在
 * 全屏画面上一块白底长列表,横屏时十来档清晰度纵向拉出去半屏;而三者本来就是"这个播放器
 * 现在怎么放"的同一组设置。
 *
 * **单选一律用 M3E 的 toggle button,不用 radio 列表。** 倍速六档等宽,排成一条 connected
 * 组;清晰度与字幕的档名长短不一、条数不定,排成可换行的一片。选中即生效、面板不关:调清晰度
 * 或字幕时人要看着画面确认效果。
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
) {
    PanelColumn(modifier) {
        SectionTitle(stringResource(R.string.player_speed))
        // **只写数字,不带「x」。** 段标题已经写着「倍速」,每格再挂一个 x 是六遍同一个单位,
        // 还正好是「0.75x」放不下、被切掉的那一截。
        ConnectedChoices(
            options = SpeedOptions,
            selected = { it == speed },
            onSelect = onSpeedChange,
            label = ::formatSpeedNumber,
        )

        // 没有可选的就整段不画:一段只有一个选项的单选是纯噪声。
        if (qualities.size > 1) {
            SectionTitle(stringResource(R.string.player_quality))
            ChoiceGrid(
                options = qualities,
                selected = { it.quality == currentQuality },
                onSelect = { onQualityChange(it.quality) },
                // 不截断:两档截断之后可能一模一样("1080P 高码率" 与 "1080P60")。
                label = { it.label },
            )
        }

        // 音质:列这次真下发了的几条音轨(见 SelectedStreams.audioOptions),规则同画质那一段。
        if (audioOptions.size > 1) {
            SectionTitle(stringResource(R.string.player_audio_quality))
            ChoiceGrid(
                options = audioOptions,
                selected = { it == currentAudio },
                onSelect = onAudioChange,
                label = { audioQualityLabel(it) },
            )
        }

        if (subtitleTracks.isNotEmpty()) {
            SectionTitle(stringResource(R.string.player_subtitle))
            val offLabel = stringResource(R.string.player_subtitle_off)
            // null 是"关闭"那一格。
            val options: List<SubtitleTrack?> = listOf(null) + subtitleTracks
            ChoiceGrid(
                options = options,
                selected = { (it?.lan ?: "") == currentSubtitleLan },
                onSelect = { onSubtitleTrackChange(it?.lan ?: "") },
                label = { it?.displayName ?: offLabel },
            )
        }

        if (danmakuEditor != null) DanmakuSettingsSection(danmakuPrefs, danmakuEditor)
    }
}

/** 只有弹幕设置的一块面板。直播间用:那边的清晰度和仅声音仍在控制条上。 */
@Composable
internal fun DanmakuSettingsContent(
    prefs: DanmakuPrefs,
    editor: DanmakuPrefsEditor,
    modifier: Modifier = Modifier,
) {
    PanelColumn(modifier) { DanmakuSettingsSection(prefs, editor) }
}

@Composable
private fun PanelColumn(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
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
        SectionTitle(stringResource(R.string.settings_danmaku_opacity), Modifier.weight(1f))
        Text(
            stringResource(R.string.settings_danmaku_opacity_value, (opacity * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge,
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

    SectionTitle(stringResource(R.string.settings_danmaku_show_area))
    SectionNote(stringResource(R.string.settings_danmaku_show_area_subtitle))
    val areaLabels = DanmakuShowAreaSteps.associateWith {
        stringResource(R.string.settings_danmaku_show_area_value, (it * 100).roundToInt())
    }
    ConnectedChoices(
        options = DanmakuShowAreaSteps,
        // 存的是连续值,取最近的一档亮起。
        selected = { it == DanmakuShowAreaSteps.minBy { step -> abs(step - prefs.scrollShowArea) } },
        onSelect = editor::setScrollShowArea,
        label = { areaLabels.getValue(it) },
    )

    SectionTitle(stringResource(R.string.settings_danmaku_density))
    val standard = stringResource(R.string.settings_danmaku_density_standard)
    val unlimited = stringResource(R.string.settings_danmaku_density_unlimited)
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

    SectionTitle(stringResource(R.string.settings_danmaku_frame_rate))
    SectionNote(stringResource(R.string.settings_danmaku_frame_rate_subtitle))
    val fps30 = stringResource(R.string.settings_danmaku_frame_rate_30)
    val fps60 = stringResource(R.string.settings_danmaku_frame_rate_60)
    val display = stringResource(R.string.settings_danmaku_frame_rate_display)
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
            stringResource(R.string.settings_danmaku_in_pip),
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        options.forEachIndexed { index, option ->
            ToggleButton(
                checked = selected(option),
                onCheckedChange = { onSelect(option) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = Spacing.Hair),
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Text(label(option), maxLines = 1, softWrap = false)
            }
        }
    }
}

/**
 * 滚动弹幕显示区域的四档。存的是连续值(见 `SettingsStore.saveDanmakuScrollShowArea`),选中判定
 * 按最近档取,将来加档或换成连续滑杆都不必改存储格式。
 */
private val DanmakuShowAreaSteps = listOf(0.25f, 0.5f, 0.75f, 1f)

/** 与 `SettingsStore.saveDanmakuOpacity` 的取值范围一致。 */
private val OpacityRange = 0.1f..1f
private const val OpacitySteps = 8

/** 倍速的数字部分:"0.75"、"1"、"2",不带单位,见倍速那一段。 */
private fun formatSpeedNumber(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()

/**
 * 两列等宽的单选网格。**等宽而不是按字长排开**:档名长短差得多("360P" 与 "1080P 高码率"),
 * 各按字长排成一片时每行断在不同位置,选中那一格的位置也跟着乱跳。两列等宽之后一眼扫得出
 * 一共几档、选的是第几档;奇数个时最后一行留空位,不让最后一格独自撑满一整行。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ChoiceGrid(
    options: List<T>,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    label: (T) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        options.chunked(GridColumns).forEach { row ->
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
                repeat(GridColumns - row.size) { Spacer(modifier = Modifier.weight(1f)) }
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

