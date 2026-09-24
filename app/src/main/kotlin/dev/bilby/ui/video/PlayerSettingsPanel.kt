package dev.bilby.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.R
import dev.bilby.data.QualityOption
import dev.bilby.player.SubtitleTrack
import dev.bilby.player.audioQualityLabel
import dev.bilby.ui.theme.Spacing

/** 倍速的几档。和长按加速不是一回事:那个在设置页里定,是临时叠上去的。 */
internal val SpeedOptions = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * 播放设置:倍速、清晰度、字幕,三段竖排。内嵌时装在底部 sheet 里,全屏时装在右侧面板里
 * (见 BilbyPlayer),内容是同一份。
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
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    ) {
        SectionTitle(stringResource(R.string.player_speed))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            SpeedOptions.forEachIndexed { index, option ->
                ToggleButton(
                    checked = option == speed,
                    onCheckedChange = { onSpeedChange(option) },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        SpeedOptions.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                    // 默认内边距左右各 16dp,六档平分 360dp 宽时每格只剩二十来 dp 放字。
                    contentPadding = PaddingValues(horizontal = Spacing.Hair),
                    modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                ) {
                    // **只写数字,不带「x」。** 段标题已经写着「倍速」,每格再挂一个 x 是六遍
                    // 同一个单位,还正好是「0.75x」放不下、被切掉的那一截。
                    Text(formatSpeedNumber(option), maxLines = 1, softWrap = false)
                }
            }
        }

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
    }
}

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
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.Cozy, bottom = Spacing.Tight),
    )
}

