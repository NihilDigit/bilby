package dev.bilby.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/**
 * 一条视频在列表里的样子。
 *
 * 动态、搜索结果、空间投稿、稍后再看、助理答案 —— 五处以前各写了一份几乎一样的 Row,
 * 封面宽度、行距、截断行数各差一点点,滑过去能看出接缝。合并成这一个。
 *
 * 参数是扁平的展示字段而不是某个 data 层模型:五个调用方的模型各不相同
 * (FeedEntry / SearchVideo / SpaceVideoItem / ToViewItem / AnswerItem),
 * 让 UI 组件认识其中任何一个都会把 data 层的形状焊进视图层。
 */
@Immutable
data class VideoRowUi(
    val title: String,
    val coverUrl: String,
    val durationText: String = "",
    /** UP 主名。空间页里整页都是同一个 UP,这行就该省掉,不是留空。 */
    val upName: String? = null,
    /** 发布时间。和 [upName] 排在同一行,顺序按 PiliPlus:先时间后人名。 */
    val dateText: String? = null,
    /** 播放量与弹幕数。走 [StatRow] 的图标形式,不再拼成中文串。 */
    val playText: String? = null,
    val danmakuText: String? = null,
    /** 计数之外的一行状态文案(稍后再看的"已看完""看到 12:30")。 */
    val meta: String? = null,
    /** 看过的比例,0..1。null 表示没看过或算不出时长。 */
    val progressFraction: Float? = null,
    /**
     * 第三行的补充说明,颜色跟着 [accentNote] 走。
     * 助理结果的"推荐理由"用它 —— 那句话是助理结果与推荐流的根本区别,永远显示。
     */
    val note: String? = null,
    /** note 是否用强调色。理由用 primary(它是这条为什么在这),状态文案用次级色。 */
    val accentNote: Boolean = false,
)

/**
 * @param trailing 行尾的操作(稍后再看的删除按钮)。放在这里而不是让调用方套一层 Row,
 *   是为了保证操作按钮和文字块的对齐在所有列表里一致。
 */
@Composable
fun VideoRow(
    item: VideoRowUi,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    /**
     * 长按。**给的是次要操作,不能是这一行唯一能做的事** —— 长按没有任何视觉提示,
     * 只发现得了点击的人必须仍然能用这一行。
     */
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    /**
     * 这一行自己的溢出菜单。**和 [trailing] 是两个位置**:[trailing] 占满整行右侧的一列,
     * 从标题那一列切走 48dp;这个叠在文字列的右下角,一格宽度都不占。
     *
     * 判据是这个操作作用于什么。多选的 Checkbox、稍后再看的删除按钮作用于"这一行",
     * 它们该在行尾那一列;溢出菜单里装的是这一条的次要操作,挤掉标题三分之一的字换不来。
     */
    overflow: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            // 失效稿件仍保留在收藏夹里,但它必须看起来不可打开。仅禁用语义和点击
            // 会留下一个“看起来正常、点了没反应”的粗糙行。
            .alpha(if (enabled) 1f else DisabledContentAlpha)
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        // 字体放大或助理理由变长时,右侧内容可能高过固定比例的封面。
        // 居中比把封面钉在顶部更稳定,不会在行尾留下明显的“封面下坠”空白。
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListCover(
            url = item.coverUrl,
            durationText = item.durationText,
            progressFraction = item.progressFraction,
        )

        Box(modifier = Modifier.weight(1f)) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
                // bodyMedium(14sp)而不是 bodyLarge(16sp)。PiliPlus 的卡片标题也是 14sp,
                // 换来的是同样两行里多放两三个字 —— 见 Dimens.ListCoverWidth 那道算术。
                //
                // **标题只让出按钮那一格的一半,不让出整格**:让出 48dp 就是那道算术里的
                // 13 个字掉到 9 个;一点不让的话,标题的右边界比图标的右边界还往外,
                // 那一列没有任何东西与它对齐,看起来是浮着的。见 [TitleOverflowGutter]。
                Text(
                    text = item.title,
                    modifier = Modifier.padding(end = if (overflow != null) TitleOverflowGutter else 0.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 标题以下的几行让出按钮那一格。它们本来右边就是空的:元信息是几个 14dp 图标
                // 加几个数字,时间和 UP 名合起来也占不满一行。
                Column(
                    modifier = Modifier.padding(end = if (overflow != null) OverflowReserve else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                ) {
                    // 时间和 UP 名合成一行(PiliPlus `video_card_h.dart` 的 content()):
                    // 两者都是"这条是谁什么时候发的",分两行摆会把三行文字撑到四行,
                    // 而封面高度是固定的,多出来的那行只能让行距变松、看起来更空。
                    SecondaryLine(dateText = item.dateText, upName = item.upName)
                    StatRow(playText = item.playText, danmakuText = item.danmakuText)
                    item.meta?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    item.note?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.accentNote) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // 叠在文字列右下角,不进列的布局流 —— 排进去的话这一行会跟着按钮的 48dp 长高,
            // 而它挡住的位置上本来就没有字(见上面那圈 end padding)。
            overflow?.let {
                Box(modifier = Modifier.align(Alignment.BottomEnd)) { it() }
            }
        }

        trailing?.invoke(this)
    }
}

private const val DisabledContentAlpha = 0.38f

/** 溢出按钮那一格:M3 的最小触控尺寸。标题以下的几行让出这么宽。 */
private val OverflowReserve = 48.dp

/**
 * 标题让出的宽度:按钮那一格的一半。
 *
 * 让 12dp(图标在 48dp 触控格里的内缩)时标题的右边界正好压在图标的右边界上,量是对齐了,
 * 看起来却像贴着;24dp 之后两者之间有一格喘息,而标题仍有 164dp,比让出整格宽 36dp。
 */
private val TitleOverflowGutter = OverflowReserve / 2

/**
 * 一行元信息里各段之间的间隔:**两个空格,不是 `·` 或 `•`**。
 *
 * 这一行的末段常常是要截断的(UP 名、IP 属地),分隔点跟着被截掉会留下一个孤零零的点;
 * 窄屏上那一串点还会先于内容换行。两个空格没有这两个问题,也不必再挑用哪个点。
 */
const val MetaSeparator = "  "

/**
 * "3 小时前  某某 UP 主"。
 *
 * 低强调文字取 `onSurfaceVariant`,不取 `outline` —— 后者是描边角色,只保证约 3:1,
 * 浅色主题下这一行量出来 4.3:1,小字不达标。
 */
@Composable
private fun SecondaryLine(dateText: String?, upName: String?) {
    val text = listOfNotNull(dateText, upName).filter { it.isNotBlank() }.joinToString(MetaSeparator)
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 队列、合集分集这类次级列表的紧凑行。比 [VideoRow] 矮一半,因为它出现在已经有主内容的
 * 页面里(播放页下半屏、听视频页下半屏),不该和主列表抢视觉重量。
 */
@Composable
fun CompactVideoRow(
    title: String,
    coverUrl: String,
    subtitle: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        // 选中态用 secondaryContainer:M3 把它定义为"导航选中指示"这类不争夺注意力的选中填充,
        // primaryContainer 在一屏几十条的队列里太响。
        //
        // 未选中是**透明**而不是 surface:这个组件既出现在播放页那张 surfaceContainerLow 的
        // 队列卡片里,也出现在听视频页的裸 surface 上。画死 surface 的话,卡片里每一条
        // 未选中的行都会是一块比卡片亮的补丁,几十条排下来就是一条条横杠。
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.Tight),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 外圆角 8dp(shapes.small)减去 8dp 内边距 = 4dp:嵌套的两层用同一个半径时,
            // 内层的角看上去反而比外层更方(M3 shape 页管这叫 optical roundness)。
            ListCover(
                url = coverUrl,
                width = Dimens.CompactCoverWidth,
                cornerRadius = 4.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
