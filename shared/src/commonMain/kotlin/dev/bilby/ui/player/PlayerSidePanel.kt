package dev.bilby.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.resources.Res
import dev.bilby.resources.action_close
import dev.bilby.stringResource
import dev.bilby.ui.BackHandler
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.LoadingSpinner
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular

/**
 * 全屏下从右边缘划出的一块面板,画面照放。切集面板与播放设置面板共用这一层外壳。
 *
 * **右侧划出,不用底部 sheet。** 全屏是横屏,底部 sheet 在这个方向上只剩一条缝,几十条的
 * 列表在里面翻不动。规范给组件的进出方向是"从它贴着的那条边展开"(transitions 页的
 * enter/exit),所以它从右边缘推进来。
 *
 * **画面不缩、播放不停。** 面板浮在画面上,不参与播放器的布局 —— 缩画面会让 `PlayerSurface`
 * 改尺寸,弹幕整池按新画布重编,而用户只是想换一集、调一档。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BoxScope.PlayerSidePanel(
    visible: Boolean,
    onDismiss: () -> Unit,
    /** 只放一列短选项时(控制条上 chip 单开的一段)用窄款,少挡画面。 */
    narrow: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 遮罩。压暗是为了让面板这一侧读得出来是上层,同时接住面板之外的那一下点击 ——
    // 全屏下画面本身带着自己的手势(单击出控件、双击 seek),不接住的话点在画面上会既关面板
    // 又触发一次 seek。
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(FixedColors.ScrimOnMedia)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        )
    }

    // **宽度算出来,不叠两个宽度修饰符。** 这里原先是 `widthIn(max).fillMaxWidth(0.42f)`,
    // 而外层的 widthIn 决定了内层 fillMaxWidth 看到的 maxWidth —— 结果是 0.42 × 400 = 168dp,
    // 一块连封面加两行标题都排不下的窄条。下限同样必需:比它窄就不是"窄一点",是内容被挤没。
    val windowWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val panelWidth = if (narrow) {
        NarrowPanelWidth
    } else {
        (windowWidth * PanelWidthFraction).coerceIn(PanelMinWidth, PanelMaxWidth)
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(MaterialTheme.motionScheme.defaultSpatialSpec()) { it },
        exit = slideOutHorizontally(MaterialTheme.motionScheme.fastSpatialSpec()) { it },
        // **贴右上角并铺满整个高度。** 一块只有半屏高、垂直居中的面板会读成浮在画面中间的一个
        // 窗口 —— 它是从边缘推进来的一层,不是一个浮块。
        modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = PanelCornerRadius, bottomStart = PanelCornerRadius),
            modifier = Modifier
                .fillMaxHeight()
                .widthIn(max = PanelMaxWidth)
                .width(panelWidth),
        ) {
            Column(
                // 画面是全出血的,挖孔和手势条压在这块面板上。**只躲 end 和上下两侧** ——
                // 面板贴的是屏幕右缘,它的左边挨着的是画面,不是屏幕边缘。
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.barsAndCutout.only(WindowInsetsSides.End + WindowInsetsSides.Vertical),
                ),
                content = content,
            )
        }
    }

    // 返回先关面板。组合得更晚、后进先出,所以它排在解锁、退全屏那几级前面;只在 visible 时
    // 存在,面板不开时这一级自然不占。
    if (visible) BackHandler(onBack = onDismiss)
}

/**
 * 全屏侧滑面板统一顶栏(`side-sheets.md:354`):主标题、可选 `X / N` 胶囊角标、可选加载指示与 48dp 关闭按钮。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PlayerSidePanelHeader(
    title: String,
    badgeText: String? = null,
    loading: Boolean = false,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PanelHeaderHeight)
            .padding(start = Spacing.Comfortable, end = Spacing.Hair),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (!badgeText.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = CircleShape,
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmallEmphasized.tabular,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = Spacing.Tight, vertical = BadgeVerticalPadding),
                    )
                }
            }
            if (loading) {
                LoadingSpinner()
            }
        }
        val closeLabel = stringResource(Res.string.action_close)
        BilbyTooltip(text = closeLabel, shortcut = "Esc") {
            IconButton(
                onClick = onClose,
                modifier = Modifier.sizeIn(minWidth = Dimens.MinTouchTarget, minHeight = Dimens.MinTouchTarget),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = closeLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 面板宽度占屏宽的比例。 */
private const val PanelWidthFraction = 0.42f

/** 条目排得开的最小宽度:封面 96dp + 两行标题 + 两侧内边距。 */
private val PanelMinWidth = 280.dp

/** 上限。再宽内容本身也用不上,只是把画面挡得更多。 */
private val PanelMaxWidth = 380.dp

/** 窄款。一列档名("高清 1080P+"这类)加两侧内边距,放得下就够。 */
private val NarrowPanelWidth = 240.dp

private val PanelCornerRadius = 16.dp
private val PanelHeaderHeight = 56.dp
private val BadgeVerticalPadding = 2.dp
