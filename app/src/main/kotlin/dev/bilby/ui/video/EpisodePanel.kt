package dev.bilby.ui.video

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.player.EpisodeList
import dev.bilby.ui.player.EpisodeRow
import dev.bilby.ui.player.EpisodeTarget
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing

/**
 * 全屏下的切集面板:从右边缘划出的一块,画面照放。
 *
 * **右侧划出,不用底部 sheet。** 全屏是横屏,底部 sheet 在这个方向上只剩一条缝,几十条的
 * 队列在里面翻不动。规范给组件的进出方向是"从它贴着的那条边展开"(transitions 页的
 * enter/exit),所以它从右边缘推进来。
 *
 * **点一条即关。** 目的达成之后这块面板挡的是画面本身,而全屏的全部意义就是画面。连续切几集
 * 的场合有,但比"切一集接着看"少得多,为它留着一块常驻的遮挡不划算。
 *
 * **画面不缩、播放不停。** 面板浮在画面上,不参与播放器的布局 —— 缩画面会让 `PlayerSurface`
 * 改尺寸,弹幕整池按新画布重编,而用户只是想换一集。
 */
@Composable
fun BoxScope.EpisodePanel(
    visible: Boolean,
    rows: List<EpisodeRow>,
    sourceLabel: String,
    onSelect: (EpisodeTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    // 遮罩。压暗是为了让面板这一侧读得出来是上层,同时接住面板之外的那一下点击 ——
    // 全屏下画面本身带着自己的手势(单击出控件、双击 seek),不接住的话点在画面上会既关面板
    // 又触发一次 seek。
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
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
    // 一块连 72dp 封面加两行标题都排不下的窄条。
    //
    // 下限同样是必需的:条目用的是全应用统一的 `CompactVideoRow`(封面 72dp + 两行文字),
    // 比它窄就不是"窄一点",是封面和标题一起被挤没。
    val windowWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val panelWidth = (windowWidth * PanelWidthFraction).coerceIn(PanelMinWidth, PanelMaxWidth)

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it },
        exit = slideOutHorizontally { it },
        // **贴右上角并铺满整个高度。** 全屏下右上角是空的(分享只在内嵌态出现),而一块只有
        // 半屏高、垂直居中的面板会读成浮在画面中间的一个窗口 —— 它是从边缘推进来的一层,
        // 不是一个浮块。
        modifier = Modifier.align(Alignment.TopEnd).fillMaxHeight(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxHeight().width(panelWidth),
        ) {
            Column(
                // 画面是全出血的,挖孔和手势条压在这块面板上。**只躲 end 和上下两侧** ——
                // 面板贴的是屏幕右缘,它的左边挨着的是画面,不是屏幕边缘;无条件躲四边会把
                // 横屏时落在左边缘的挖孔/状态栏那份 inset 也垫进来,表现是列表左侧空出一条。
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.barsAndCutout.only(
                        WindowInsetsSides.End + WindowInsetsSides.Vertical,
                    ),
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                // 队列来源那一行。**这里不给目录入口** —— 目录是另一个页面,而全屏下离开这一页
                // 要先退出全屏,一个点了就把人踢出全屏的链接不如不给。
                Text(
                    text = sourceLabel.ifEmpty { stringResource(R.string.video_parts) },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = Spacing.Comfortable,
                        end = Spacing.Comfortable,
                        top = Spacing.Comfortable,
                    ),
                )
                EpisodeList(
                    rows = rows,
                    onSelect = {
                        onSelect(it)
                        onDismiss()
                    },
                    contentPadding = PaddingValues(
                        horizontal = Spacing.Tight,
                        vertical = Spacing.Tight,
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    // 返回先关面板。全屏那条返回链现在是三级:关面板 → 解锁 → 退全屏,每级各吃一次。
    // 注册在这里而不是页面那一层,靠的是"组合得更晚、后进先出"——它只在 visible 时存在,
    // 面板不开时这一级自然不占。
    if (visible) BackHandler(onBack = onDismiss)
}

/** 面板宽度占屏宽的比例。 */
private const val PanelWidthFraction = 0.42f

/** 条目排得开的最小宽度:封面 72dp + 两行标题 + 两侧内边距。 */
private val PanelMinWidth = 280.dp

/** 上限。再宽条目本身也用不上,只是把画面挡得更多。 */
private val PanelMaxWidth = 400.dp

/** 面板里没有可切的东西时不给入口。单条队列且单 P 的视频就是这种。 */
fun List<EpisodeRow>.hasSomethingToSwitch(): Boolean =
    size > 1 || firstOrNull()?.parts?.isNotEmpty() == true
