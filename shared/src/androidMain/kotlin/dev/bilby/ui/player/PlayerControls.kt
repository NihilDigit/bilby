package dev.bilby.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import dev.bilby.formatDurationMillis
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.ui.components.BilbyIcons
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.PlayerTheme
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.rememberClockFont

/**
 * 控制条上的通用件。**视频与直播共用** —— 它们长在同一个 [PlayerShell] 的控制条 slot 里,
 * 各写一份的话两条控制条会慢慢长得不一样,而用户看到的是同一个播放器。
 */

/**
 * 弹幕开关。开着时图标染主色,这是"当前状态"而不是"点了会怎样"。
 *
 * 字形与标签行那个开关共用([BilbyIcons.Danmaku]):同一个开关在两处出现,长得不一样就成了
 * 两个东西。不借用气泡或字幕框的理由见 [BilbyIcons]。
 */
@Composable
internal fun DanmakuButton(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isFullscreen: Boolean,
) {
    val description = stringResource(if (enabled) R.string.danmaku_hide else R.string.danmaku_show)
    PlayerTooltip(description) {
        ControlButton(
            expanded = enabled,
            onClick = { onEnabledChange(!enabled) },
            label = null,
            icon = { tint ->
                Icon(
                    if (enabled) BilbyIcons.Danmaku else BilbyIcons.DanmakuOff,
                    description,
                    tint = tint,
                    modifier = Modifier.size(if (isFullscreen) 22.dp else 18.dp),
                )
            },
        )
    }
}

/**
 * 内嵌播放画面左上角的返回。**视频页和直播间共用一份**。
 *
 * 内嵌时 [PlayerShell] 只在全屏态给顶栏,而画面往往是这一页的第一屏内容,从动态或搜索点进来
 * 的人只剩系统返回可用。渐变是必需的而不是装饰:封面是 UP 主上传的任意图片,纯白封面上一个
 * 白箭头看不见 —— 同一条理由见风格指南 §1.2 的 `ScrimOnMedia`。
 *
 * 它**不跟控件一起自动隐藏**:自动隐藏的是"播放器现在在做什么"那一类控件,而离开这一页是
 * 页面级动作,藏起来就等于没有。
 *
 * @param scrim 自己画那条渐变。**画面里有弹幕时要传 false**,改由 [PlayerShell] 在弹幕
 *   下面画([MediaTopScrim])—— 这两个按钮是画在壳外面的,渐变跟着它们就压在弹幕之上,
 *   顶上那一两条弹幕会被这层黑纱洗掉一档对比度。没有壳的场合(直播下播后的封面页)
 *   保持 true,那时渐变只能自己带。
 */
@Composable
internal fun BoxScope.MediaBackButton(
    onBack: () -> Unit,
    onShare: (() -> Unit)? = null,
    scrim: Boolean = true,
) {
    if (scrim) MediaTopScrim()
    // 画面在横屏两栏下是全出血的,状态栏和刘海就压在这两个按钮上。竖排时页面那一层已经
    // 躲过并消费掉这份 inset,这里量到 0。
    val safeInsets = WindowInsets.barsAndCutout
    val box = this
    // **和控制条上的按钮同一种样子**:半透明容器(见 [mediaControlContainer])。它们画在
    // PlayerShell 外面,拿不到壳里那层深色主题,这里自己套一层 —— 不套的话容器取的是页面的
    // 浅色配色,画面上浮着两颗浅灰的圆。
    PlayerTheme {
        with(box) {
            PlayerIconButton(
                onClick = onBack,
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(safeInsets)
                    .padding(Spacing.Tight),
            )
            // 分享和返回一样是页面级动作,所以对称地摆在另一端,而不是塞进四格动作栏
            // —— 那一行是「对这条视频表态」(赞/币/藏/稍后再看),分享不是表态,而且 M3 也说
            // 一处按钮不超过三个。
            if (onShare != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(safeInsets)
                        .padding(Spacing.Tight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    onShare.let { share ->
                        PlayerIconButton(
                            onClick = share,
                            icon = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.action_share),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 画面顶部那条渐变,托着左上角的返回和右上角的分享。
 *
 * **单独拿出来是为了让它落在弹幕下面。** 按钮本身画在壳外面(页面那一层),渐变跟着它们的话
 * 整条黑纱就压在弹幕之上,顶上那一两条弹幕被洗淡一档;而这条渐变要垫的只是那两个图标。
 * 现在它由 [PlayerShell] 在 overlay 之前画,z 序成了 画面 → 渐变 → 弹幕 → 按钮:
 * 按钮照旧有底,弹幕落在渐变上反而比落在原画面上更清楚。
 */
@Composable
internal fun BoxScope.MediaTopScrim() {
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(MediaScrimHeight)
            .background(Brush.verticalGradient(listOf(FixedColors.ScrimOnMedia, Color.Transparent))),
    )
}

/** 够盖住一个 48dp 触摸目标再加一段渐隐,不到画面高度的四分之一。 */
private val MediaScrimHeight = 72.dp

/**
 * 画面中央那个框的内容:**按住画面连续调一个量**时的读数。横划进退、音量、亮度三种。
 *
 * 这三种原先和长按加速、双击快进共用一个"图标 + 一句话"的框,理由是位置固定、学一次。
 * 共用下来的毛病是它只剩一句话:音量是多少只能读百分比,进退只有一串"12:30 / 45:00",看不出
 * 挪了多少。现在分两类:
 *
 * - **连续调整**(这三种)留在正中,加一条量:手指在动,读数就该跟着一条看得见的量走。
 * - **一下就完的**(双击、长按加速)各回到它们发生的地方:双击在落点那一侧([DoubleTapSeekHint]),
 *   长按加速在顶上([SpeedBoostCapsule])。正中将来要让给播放键。
 *
 * **摆位归调用方**,所以不是 `BoxScope` 扩展:它套在 `AnimatedVisibility` 里淡入淡出
 * (见 [PlayerShell]),而那一层的 content scope 不是 `BoxScope`,`align` 得挂在外面那个
 * `AnimatedVisibility` 上。
 */
internal sealed interface PlayerHud {
    /** 横划进退。[cancelArmed] 为真时松手就取消,那时只说这一句。 */
    data class Seek(
        val targetMillis: Long,
        val durationMillis: Long,
        val deltaMillis: Long,
        val cancelArmed: Boolean,
    ) : PlayerHud

    /** 音量或亮度。[value] 在 0..1。 */
    data class Level(val icon: ImageVector, val label: String, val value: Float) : PlayerHud
}

@Composable
internal fun PlayerHudOverlay(hud: PlayerHud, modifier: Modifier = Modifier) {
    Overlay(modifier = modifier) {
        when (hud) {
            is PlayerHud.Level -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            ) {
                Icon(
                    imageVector = hud.icon,
                    contentDescription = null,
                    tint = FixedColors.OnMedia,
                    modifier = Modifier.size(HintIconSize),
                )
                // 条而不是只写百分比:手指在屏上滑,眼睛扫一下条的长短比读一个两位数快。
                // 百分比留在右边给要精确值的人,读屏也念它。
                LinearProgressIndicator(
                    progress = { hud.value },
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier.width(LevelBarWidth),
                )
                Text(
                    text = hud.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = FixedColors.OnMedia,
                )
            }

            is PlayerHud.Seek -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                if (hud.cancelArmed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            tint = FixedColors.OnMedia,
                            modifier = Modifier.size(HintIconSize),
                        )
                        Text(
                            stringResource(R.string.player_seek_release_to_cancel),
                            style = MaterialTheme.typography.titleMedium,
                            color = FixedColors.OnMedia,
                        )
                    }
                } else {
                    // 目标时间大写,总时长小一号跟在后面:拖的时候要读的是"到哪了"。
                    // 瘦高的读数(ClockAxes),同定时倒计时与进度条气泡。
                    // 等宽数字:秒数每跳一下,不等宽的话整行左右抖。
                    val readoutFont = rememberClockFont()
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            formatDurationMillis(hud.targetMillis),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontFamily = readoutFont,
                                fontFeatureSettings = "tnum",
                            ),
                            color = FixedColors.OnMedia,
                        )
                        Text(
                            " / ${formatDurationMillis(hud.durationMillis)}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = readoutFont,
                                fontFeatureSettings = "tnum",
                            ),
                            color = FixedColors.OnMedia.copy(alpha = SecondaryTextAlpha),
                            modifier = Modifier.padding(bottom = Spacing.Hair / 2),
                        )
                    }
                    // 挪了多少。起手那一刻是 0,不写"快进 0 秒"。
                    val seconds = kotlin.math.abs(hud.deltaMillis) / 1000
                    if (seconds > 0) {
                        Text(
                            stringResource(
                                if (hud.deltaMillis > 0) R.string.player_seek_forward else R.string.player_seek_backward,
                                seconds,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = {
                        if (hud.durationMillis > 0) {
                            (hud.targetMillis.toFloat() / hud.durationMillis).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    },
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier.width(SeekBarHudWidth).padding(top = Spacing.Hair),
                )
            }
        }
    }
}

/**
 * 双击快进快退的反馈:**落在点下去的那一侧**,贴着那条边画一块半圆,里面写累计了多少秒。
 *
 * 在正中写"快进 10 秒"说不清是哪一边:人点的是左四分之一还是右四分之一,反馈就该在那儿亮。
 * 连着双击时秒数累加(见 [PlayerShell] 的 seekNudgeMillis),连点三下看到的是 30 秒,
 * 不是三次一模一样的 10 秒。
 */
@Composable
internal fun DoubleTapSeekHint(deltaMillis: Long, modifier: Modifier = Modifier) {
    val forward = deltaMillis >= 0
    val shape = if (forward) {
        RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
    } else {
        RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
    }
    // 尺寸与贴哪条边归调用方(见 [DoubleTapArcFraction]):它套在 AnimatedVisibility 里,
    // 对齐得挂在外面那一层上,道理同 [PlayerHudOverlay]。
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .clip(shape)
            .background(FixedColors.OnMedia.copy(alpha = DoubleTapArcAlpha)),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (forward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                contentDescription = null,
                tint = FixedColors.OnMedia,
                modifier = Modifier.size(HintIconSize),
            )
            Text(
                stringResource(
                    if (forward) R.string.player_seek_forward else R.string.player_seek_backward,
                    kotlin.math.abs(deltaMillis) / 1000,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = FixedColors.OnMedia,
            )
        }
    }
}

/**
 * 长按加速:顶上居中一枚胶囊。**不占正中**:加速时人在看画面走得快不快,正中一个框正好挡在
 * 视线上;而"现在是 3x"这件事只要余光扫得到。
 */
@Composable
internal fun SpeedBoostCapsule(text: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
        modifier = modifier
            .clip(CircleShape)
            .background(FixedColors.ScrimOnMedia)
            .padding(horizontal = Spacing.Cozy, vertical = Spacing.Hair + Spacing.Hair / 2),
    ) {
        Icon(
            Icons.Filled.FastForward,
            contentDescription = null,
            tint = FixedColors.OnMedia,
            modifier = Modifier.size(Dimens.IconInline),
        )
        Text(text, style = MaterialTheme.typography.labelLarge, color = FixedColors.OnMedia)
    }
}

/** 比正文大一档,和 titleMedium 的行高对得上。 */
private val HintIconSize = 22.dp

private val LevelBarWidth = 120.dp
private val SeekBarHudWidth = 180.dp

/** 双击那块半圆占画面宽度的比例。比双击判定区(四分之一)略宽,落点一定在它里面。 */
internal const val DoubleTapArcFraction = 0.3f
private const val DoubleTapArcAlpha = 0.16f
private const val SecondaryTextAlpha = 0.7f

/**
 * 控制条上那几个纯图标按钮的 tooltip。**长按说出这个按钮叫什么**——倍速、清晰度、字幕、
 * 弹幕、全屏、锁,六个图标挨着排在画面右下角,而它们的字形没有一个是约定俗成到不必解释的
 * (M3 的 icon buttons 页对纯图标按钮给的办法就是挂 tooltip)。
 *
 * [text] 直接复用按钮自己的 `contentDescription`,不另写一条:读屏念的和长按看到的是同一句,
 * 分成两条迟早各自漂移。
 *
 * 包一层函数而不是在六处各写一遍 `TooltipBox`:那三行样板里有两行(位置提供者、state)在
 * 每一处都一模一样,而抄六遍的东西改一处就会漏五处。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerTooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        content = content,
    )
}

/**
 * 浮在画面上的控件的容器色:深色主题(见 PlayerTheme)的最高一档 surface 容器,72% 不透明。
 *
 * **浮在视频上的按钮要有容器。** 裸图标的对比度全看底下那一帧:白衣服、雪地、亮色封面上
 * 白色线框就没了,以前只能靠一整条渐变压暗去兜。有了容器,对比度是按钮自己带的,渐变可以淡。
 * 留 28% 透出画面,是为了不在画面上开一个实心的洞。
 */
@Composable
internal fun mediaControlContainer(): Color =
    MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = MediaControlContainerAlpha)

private const val MediaControlContainerAlpha = 0.72f

/**
 * 一颗浮在画面上的图标按钮(全屏、设置、弹幕开关这类),带半透明容器,理由见
 * [mediaControlContainer]。M3E 的按压形变由 `shapes` 给。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    /** 图标尺寸。听视频的上一条/下一条要大一档。 */
    iconSize: Dp = Dimens.IconInline,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else mediaControlContainer(),
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        modifier = modifier,
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize))
    }
}

/**
 * 控制条上的一枚 chip:图标,可选地跟一段文字(当前倍速、当前清晰度)。容器是 M3E 的 XS 按钮
 * 那一档高(32dp),触控仍撑到 48dp。选中(菜单开着、开关打开)时换成 primaryContainer。
 */
@Composable
internal fun ControlButton(
    expanded: Boolean,
    onClick: () -> Unit,
    label: String?,
    icon: @Composable (Color) -> Unit,
) {
    val container = if (expanded) MaterialTheme.colorScheme.primaryContainer else mediaControlContainer()
    val tint = if (expanded) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            // 宽度也吃触控下限:没有文字的那几个只有图标宽,挨着排时按下去经常是隔壁那个。
            .sizeIn(minWidth = Dimens.MinTouchTarget, minHeight = Dimens.MinTouchTarget),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
            modifier = Modifier
                .height(ControlChipHeight)
                .clip(CircleShape)
                .background(container)
                .padding(horizontal = if (label != null) Spacing.Cozy else Spacing.Tight - Spacing.Hair / 2),
        ) {
            icon(tint)
            if (label != null) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                    maxLines = 1,
                    // **不设宽度上限,也不截断**:这里的标签是画质档名("1080P60"、"1080P 高码率"),
                    // 截断之后两个档看起来一模一样,那正是这个标签唯一要回答的问题。
                )
            }
        }
    }
}

/** 控制条 chip 的容器高度,M3E XS 按钮那一档。 */
private val ControlChipHeight = 32.dp

/**
 * 画面正中的播放键,**三种形态由同一块容器连续变过去**:
 *
 * - 停着:正圆,primary 实底 —— 这时它是整个画面上唯一要按的东西。
 * - 放着:略宽的圆角方块,半透明容器 —— 退成一个"随时能停"的控件,不该和画面抢。
 * - 加载:变回正圆,primaryContainer 底,里面是 M3E 的 LoadingIndicator。加载指示原先是
 *   另一个叠在正中的圈,和这颗键摞在同一个位置上;并进来之后"在等"和"能按"是同一处。
 *
 * 形状走 spatial 弹簧,颜色走 effects 弹簧(无回弹):颜色回弹没有物理意义也看得出来。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CenterPlayButton(
    isPlaying: Boolean,
    loading: Boolean,
    large: Boolean,
    onClick: () -> Unit,
) {
    val height = if (large) CenterButtonHeightLarge else CenterButtonHeight
    val playingShape = isPlaying && !loading
    val width by animateDpAsState(
        targetValue = if (playingShape) height * PlayingWidthRatio else height,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "center-width",
    )
    val corner by animateDpAsState(
        targetValue = if (playingShape) height * PlayingCornerRatio else height / 2,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "center-corner",
    )
    val colors = MaterialTheme.colorScheme
    val containerTarget = when {
        loading -> colors.primaryContainer
        isPlaying -> mediaControlContainer()
        else -> colors.primary
    }
    val contentColor = when {
        loading -> colors.onPrimaryContainer
        isPlaying -> colors.onSurface
        else -> colors.onPrimary
    }
    val container by animateColorAsState(
        targetValue = containerTarget,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "center-container",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = container,
        contentColor = contentColor,
        modifier = Modifier.size(width = width, height = height),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (loading) {
                LoadingIndicator(color = contentColor, modifier = Modifier.size(height * LoadingRatio))
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                    modifier = Modifier.size(height * IconRatio),
                )
            }
        }
    }
}

/**
 * 内嵌时 56dp:360dp 宽的屏上画面约 202dp 高,顶上返回键占 56、底下一行控制条约 68,
 * 中间留得下 56 再加上下各一点空。全屏 72。
 */
private val CenterButtonHeight = 56.dp
private val CenterButtonHeightLarge = 72.dp
private const val PlayingWidthRatio = 1.15f
private const val PlayingCornerRatio = 0.3f
private const val LoadingRatio = 0.72f
private const val IconRatio = 0.5f
