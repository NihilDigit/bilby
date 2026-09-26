package dev.bilby.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDragHandle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import dev.bilby.data.SideSheetPrefs
import dev.bilby.ui.theme.Spacing

/**
 * 宽屏的主区加右侧侧栏。空间页的动态、订阅页的关注都走这里,开关、拖宽、记忆宽度只有一份。
 *
 * 侧栏取 M3 standard side sheet 的 detached 形态(side-sheets.md):和主区在同一平面(MDC 的
 * coplanar 款,elevation 0),右边与下边离窗口边缘 16dp(规格 measurements 表的 Margins
 * (when detached)),整块是一张卡([panelCard])。关闭按钮常驻,规格原话是没有它的话看不出
 * 这块是临时的还是固定的。Compose 没有这个组件(那一页的实现表里 Jetpack Compose 一栏是
 * UNAVAILABLE),按规格自己搭。
 *
 * **与规格不同的三处。** 宽度可以超过规格的 400dp 上限,因为它能拖宽;和主区之间是一段带
 * 拖动手柄的间隔(见 [PanelDragHandle]),不是分割线;栏名与主区的栏名同一个版式([PaneTitle]),
 * 不用规格那种大一号的标题 —— 两块内容的名字一大一小时,读起来分不清主次是版式给的还是
 * 内容给的。
 *
 * 读屏把它当作一个有名字的区域(paneTitle),不当 Dialog:规格给的角色是 Dialog,但这块不抢焦点、
 * 不挡主区,读成对话框会让人以为要先关掉它才能回到主区。
 *
 * @param prefs 设置里存的开关与宽度;null 是还没读出来。
 * @param ready 侧栏该不该出现已经判得出来(设置之外的条件,比如这一类内容有没有)。
 *   与 prefs 一起挡住头一次组合:AnimatedVisibility 头一次组合时不播进场动画,值到了再组合,
 *   开着的人看到它直接在那儿,关掉过的人不会看到它弹一下。
 * @param available 这一页此刻有没有侧栏可言(内容为空时整块不出现,开关也不给)。
 */
@Composable
fun SidePanelLayout(
    prefs: SideSheetPrefs?,
    available: Boolean,
    title: String,
    closeDescription: String,
    onOpenChange: (Boolean) -> Unit,
    onWidthChange: (Float) -> Unit,
    defaultWidth: Dp,
    minWidth: Dp,
    modifier: Modifier = Modifier,
    ready: Boolean = true,
    main: @Composable () -> Unit,
    panel: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = modifier) {
        // 拖过的宽度留在这里,松手才交给设置保存,每帧写盘没有必要。松手后也不清掉:
        // 清掉的话在设置写回来之前会先退回旧宽度,侧栏弹一下。
        var draggingWidth by remember { mutableStateOf<Dp?>(null) }
        // 上限让主区至少还留 [MainPaneMinWidth];窗口再窄时上限压到下限,侧栏就不再能拖宽。
        val maxPanelWidth = (maxWidth - PanelSpacer - MainPaneMinWidth).coerceAtLeast(minWidth)
        val savedWidth = prefs?.widthDp?.dp ?: defaultWidth
        val panelWidth = (draggingWidth ?: savedWidth).coerceIn(minWidth, maxPanelWidth)
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) { main() }
            if (prefs != null && ready) {
                AnimatedVisibility(
                    visible = available && prefs.open,
                    // 打开时主区收窄让位(side-sheets.md Adaptive design 一节),所以动的是宽度,
                    // 不是在主区上面滑进一块。内容贴着左沿,随左沿一起往左推出来。
                    enter = expandHorizontally(
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        expandFrom = Alignment.Start,
                    ),
                    exit = shrinkHorizontally(
                        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        shrinkTowards = Alignment.Start,
                    ),
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        PanelDragHandle(
                            // 在状态上累加,不从 panelWidth 算:panelWidth 是上一次重组时的值,
                            // 一帧里来几次位移就有几次从同一个旧宽度起算,只剩最后一次生效,
                            // 侧栏跟不上鼠标。状态写入当场可读,连着几次也不会丢。
                            onDrag = { deltaDp ->
                                draggingWidth = ((draggingWidth ?: panelWidth) - deltaDp)
                                    .coerceIn(minWidth, maxPanelWidth)
                            },
                            onDragStopped = { draggingWidth?.let { onWidthChange(it.value) } },
                        )
                        Column(
                            modifier = Modifier
                                .padding(end = Spacing.Comfortable, bottom = Spacing.Comfortable)
                                .width(panelWidth)
                                .fillMaxHeight()
                                .panelCard()
                                .semantics { paneTitle = title },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                // 关闭按钮的触摸区比图标宽 12dp,右边留 4dp,图标的右沿落在 16dp 线上。
                                modifier = Modifier.padding(end = Spacing.Hair),
                            ) {
                                PaneTitle(title, Modifier.weight(1f))
                                IconButton(onClick = { onOpenChange(false) }) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = closeDescription,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Box(modifier = Modifier.weight(1f)) { panel() }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 侧栏关掉之后回来的入口。开着时是实心图标,与底栏、rail 的选中态同一种表达。
 * 图标会随阅读方向镜像:RTL 下侧栏在左边(side-sheets.md 的 RTL 一节)。
 */
@Composable
fun SidePanelToggle(open: Boolean, onOpenChange: (Boolean) -> Unit, description: String) {
    IconToggleButton(checked = open, onCheckedChange = onOpenChange) {
        Icon(
            imageVector = if (open) Icons.AutoMirrored.Filled.ViewSidebar else Icons.AutoMirrored.Outlined.ViewSidebar,
            contentDescription = description,
        )
    }
}

/**
 * 侧栏那一整张卡。取 surfaceContainerLow:里面转发、直播这些块是 surfaceContainerHigh,
 * 要比底子高出一档才分得开。
 */
@Composable
fun Modifier.panelCard(): Modifier =
    clip(MaterialTheme.shapes.largeIncreased).background(MaterialTheme.colorScheme.surfaceContainerLow)

/**
 * 一栏的栏名,48dp 高,与标签栏同高,栏名与相邻的标签在同一条水平线上。只有一栏、无可切换时
 * 用它,不画成标签:一个孤零零的选中态标签读起来像另外几个没加载出来。字号与标签字同一档。
 */
@Composable
fun PaneTitle(text: String, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .height(PaneTitleHeight)
            .padding(horizontal = Spacing.Comfortable),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * 主区与侧栏之间的间隔,中间是 M3 的拖动手柄(layout-overview.md 的 Spacers 一节:两栏之间
 * 24dp,手柄放在间隔里,触摸区略压到两边)。手柄既标出了边界,又说明这条边能拖。
 *
 * [onDrag] 收到的位移按阅读方向计,正值朝行尾。侧栏在行尾,所以往行尾拖是收窄;
 * RTL 下侧栏到了左边,同一个换算仍然成立,不用在调用处再判方向。
 */
@Composable
private fun PanelDragHandle(onDrag: (Dp) -> Unit, onDragStopped: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val dragState = rememberDraggableState { deltaPx ->
        val towardsEnd = if (rtl) -deltaPx else deltaPx
        onDrag(with(density) { towardsEnd.toDp() })
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(PanelSpacer)
            .fillMaxHeight()
            .horizontalResizeCursor()
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                interactionSource = interactions,
                onDragStopped = { onDragStopped() },
            ),
    ) {
        VerticalDragHandle(interactionSource = interactions)
    }
}

/** 两栏之间的间隔宽度(layout-overview.md 的 Spacers 一节)。 */
private val PanelSpacer = 24.dp

/** 侧栏拖宽时给主区留的最小宽度:还排得下一列视频行。 */
private val MainPaneMinWidth = 440.dp

private val PaneTitleHeight = 48.dp
