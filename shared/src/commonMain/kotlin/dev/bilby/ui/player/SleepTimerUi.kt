package dev.bilby.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.bilby.formatDurationMillis
import dev.bilby.player.SleepTimerMode
import dev.bilby.player.SleepTimerState
import dev.bilby.resources.Res
import dev.bilby.resources.sleep_timer_cancel
import dev.bilby.resources.sleep_timer_end_of_item
import dev.bilby.resources.sleep_timer_end_of_item_option
import dev.bilby.resources.sleep_timer_less
import dev.bilby.resources.sleep_timer_minutes
import dev.bilby.resources.sleep_timer_more
import dev.bilby.resources.sleep_timer_off
import dev.bilby.stringResource
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.rememberClockFont

/** 倍速折算的除数下限。倍速滑到极小值时不让剩余时长炸成天文数字。 */
private const val MinSpeedForEstimate = 0.1f

/** 倒计时的字号。字形是瘦高的那一种(ClockAxes),和听视频那块矮胖的读数正好相反。 */
private val CountdownSize = 72.sp
private val MinCountdownSize = 36.sp

/** 定时的几档。睡前常用的就这几个整数;要更细的档位时,「播完当前」往往才是想要的。 */
private val SleepPresets = listOf(15, 30, 45, 60, 90, 120)
private const val SleepPresetColumns = 3

/** 剩余不到这么多时「−1」按不动:再减就撞上 SleepTimer 那一分钟的下限。 */
private const val SleepAdjustFloorMillis = 120_000L

/**
 * 当前这条还要放多久,把倍速折进去:2x 下剩的十分钟视频只放五分钟,不折算的话「播完当前」的剩余读数会一直偏大。
 */
fun computeItemRemainingMillis(
    positionMillis: Long,
    durationMillis: Long,
    speed: Float,
): Long? = (durationMillis - positionMillis)
    .takeIf { durationMillis > 0L && it > 0L }
    ?.let { (it / speed.coerceAtLeast(MinSpeedForEstimate)).toLong() }

/**
 * 距离真正停下来还有多久。不定时时返回 null。
 *
 * **只给一个数,不拼文案。** 两种模式各有一个显然的答案 —— 定时是它自己的剩余,播完这条是这一条的剩余。
 *
 * @param itemRemainingMillis 当前这条还要放多久,**已按倍速折算**。拿不到时长时为 null。
 */
@Composable
fun sleepTimerLabel(state: SleepTimerState, itemRemainingMillis: Long?): String? = when (state.mode) {
    SleepTimerMode.Off -> null
    is SleepTimerMode.After ->
        formatSleepRemaining(state.remainingMillis ?: (state.mode.minutes * 60_000L))
    // 时长还没到手(刚起播、还在取流)时先用文案占位:这一格退回图标的话,就和"没设定时"长得一模一样了。
    SleepTimerMode.EndOfItem -> itemRemainingMillis?.takeIf { it > 0L }?.let(::formatSleepRemaining)
        ?: stringResource(Res.string.sleep_timer_end_of_item)
}

/**
 * 定时剩余用「分:秒」,不是 `formatDurationMillis` 的「时:分:秒」。
 *
 * 定时上限是 120 分钟,所以分位最多三位,`120:00` 比 `1:59:55` 短两个字符 —— 挤在控制行 chip 里更容易读全,
 * 语义上回答"还有多少分钟"也更直接。
 */
internal fun formatSleepRemaining(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * 独立打开的定时关闭面板正文(听视频的定时 sheet 与视频控制条定时 chip 共用):
 * 顶上是闹钟式钟面([SleepClock]),下面是预设与「播完当前视频」,**点选档位或取消即生效并收起**,
 * 「−1 / +1」原地微调不收面板。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SleepTimerStandaloneContent(
    sleepTimer: SleepTimerState,
    itemRemainingMillis: Long?,
    onSet: (SleepTimerMode) -> Unit,
    onAdjust: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode = sleepTimer.mode
    val choose: (SleepTimerMode) -> Unit = {
        onSet(it)
        onDismiss()
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        SleepClock(
            mode = mode,
            remainingMillis = when (mode) {
                is SleepTimerMode.After -> sleepTimer.remainingMillis
                SleepTimerMode.EndOfItem -> itemRemainingMillis
                SleepTimerMode.Off -> null
            },
            onAdjust = onAdjust,
            onCancel = { choose(SleepTimerMode.Off) },
        )

        Spacer(modifier = Modifier.height(Spacing.Hair))
        SleepPresets.chunked(SleepPresetColumns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                row.forEach { minutes ->
                    ToggleButton(
                        checked = (mode as? SleepTimerMode.After)?.minutes == minutes,
                        onCheckedChange = { choose(SleepTimerMode.After(minutes)) },
                        modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                    ) {
                        Text(
                            stringResource(Res.string.sleep_timer_minutes, minutes),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        ToggleButton(
            checked = mode == SleepTimerMode.EndOfItem,
            onCheckedChange = { choose(SleepTimerMode.EndOfItem) },
            modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton },
        ) {
            Text(stringResource(Res.string.sleep_timer_end_of_item_option), maxLines = 1)
        }
    }
}

/**
 * 整块播放设置面板(`⚙`)内的紧凑定时节:不摆 72sp 大钟面抢走整张设置面板的重心,选中也不自动关面板
 * (与同面板里的画质、字幕、弹幕一致)。已选中的档再点一下即取消定时。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SleepTimerCompactSection(
    sleepTimer: SleepTimerState,
    itemRemainingMillis: Long?,
    onSet: (SleepTimerMode) -> Unit,
    onAdjust: (Int) -> Unit,
) {
    val mode = sleepTimer.mode
    val activeLabel = sleepTimerLabel(sleepTimer, itemRemainingMillis)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(Res.string.sleep_timer_off),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(top = Spacing.Cozy, bottom = Spacing.Tight),
        )
        if (activeLabel != null) {
            Text(
                activeLabel,
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.Cozy, bottom = Spacing.Tight),
            )
        }
    }
    if (mode != SleepTimerMode.Off) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.Tight),
        ) {
            if (mode is SleepTimerMode.After) {
                val less = stringResource(Res.string.sleep_timer_less)
                val more = stringResource(Res.string.sleep_timer_more)
                val remaining = sleepTimer.remainingMillis ?: (mode.minutes * 60_000L)
                FilledTonalIconButton(
                    onClick = { onAdjust(-1) },
                    enabled = remaining > SleepAdjustFloorMillis,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.semantics { contentDescription = less },
                ) {
                    Text("−1", style = MaterialTheme.typography.labelLarge)
                }
                FilledTonalIconButton(
                    onClick = { onAdjust(1) },
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.semantics { contentDescription = more },
                ) {
                    Text("+1", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { onSet(SleepTimerMode.Off) }) {
                Text(stringResource(Res.string.sleep_timer_cancel))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        SleepPresets.chunked(SleepPresetColumns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                row.forEach { minutes ->
                    val checked = (mode as? SleepTimerMode.After)?.minutes == minutes
                    ToggleButton(
                        checked = checked,
                        onCheckedChange = { on -> onSet(if (on) SleepTimerMode.After(minutes) else SleepTimerMode.Off) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            stringResource(Res.string.sleep_timer_minutes, minutes),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        val endChecked = mode == SleepTimerMode.EndOfItem
        ToggleButton(
            checked = endChecked,
            onCheckedChange = { on -> onSet(if (on) SleepTimerMode.EndOfItem else SleepTimerMode.Off) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.sleep_timer_end_of_item_option), maxLines = 1)
        }
    }
}

/**
 * 定时面板的顶栏:左边是钟面,右边是控件,像一只闹钟。
 *
 * **左边**有时间就是一个大的瘦高数字,不另写"还剩":一个在走的数本身就说明了。
 * - 按时长:剩余时间。
 * - 播完当前视频:这一条还剩多久。
 * - 没有定时(或这一条的时长还没拿到):没有数可写,这个位置写面板标题「定时关闭」。
 *
 * **右边**的控件一直在,不可用时置灰,不隐藏:位置固定,人记得住它们在哪;有无定时之间
 * 切换时这一行也不变形。
 * - 「−1」「+1」挪的是正在走的倒计时本身(秒数原样保留,见 SleepTimer.extend),点了不收
 *   面板 —— 微调就是要连着按几下、看着数字变。只在按时长计时时可用。
 * - 「取消定时」在它们下面。取消在这里是一个动作,不是关掉面板;关面板用下滑或点外面。
 *
 * 这一行的高度由右边那两层控件定,比大数字高,所以左边在标题和数字之间换时整行不跳。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SleepClock(
    mode: SleepTimerMode,
    remainingMillis: Long?,
    onAdjust: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val adjustable = mode is SleepTimerMode.After
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.weight(1f).padding(end = Spacing.Tight)) {
            if (remainingMillis != null) {
                Text(
                    text = formatDurationMillis(remainingMillis),
                    style = TextStyle(
                        fontFamily = rememberClockFont(),
                        fontSize = CountdownSize,
                        lineHeight = CountdownSize,
                        fontFeatureSettings = "tnum",
                    ),
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = MinCountdownSize,
                        maxFontSize = CountdownSize,
                    ),
                    maxLines = 1,
                )
            } else {
                Text(
                    stringResource(Res.string.sleep_timer_off),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                val less = stringResource(Res.string.sleep_timer_less)
                val more = stringResource(Res.string.sleep_timer_more)
                FilledTonalIconButton(
                    onClick = { onAdjust(-1) },
                    // 剩余不到两分钟时再减就只剩一分钟的下限了(见 SleepTimer),按不动比按了没反应清楚。
                    enabled = adjustable && (remainingMillis ?: 0L) > SleepAdjustFloorMillis,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.semantics { contentDescription = less },
                ) {
                    Text("−1", style = MaterialTheme.typography.labelLarge)
                }
                FilledTonalIconButton(
                    onClick = { onAdjust(1) },
                    enabled = adjustable,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.semantics { contentDescription = more },
                ) {
                    Text("+1", style = MaterialTheme.typography.labelLarge)
                }
            }
            TextButton(onClick = onCancel, enabled = mode != SleepTimerMode.Off) {
                Text(stringResource(Res.string.sleep_timer_cancel))
            }
        }
    }
}
