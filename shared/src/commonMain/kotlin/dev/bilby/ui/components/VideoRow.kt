package dev.bilby.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.interactiveCard
import dev.bilby.ui.theme.tabular

/**
 * 一条视频在列表里的样子。
 *
 * 动态、搜索结果、空间投稿、稍后再看、助理答案 —— 五处以前各写了一份几乎一样的 Row,
 * 封面宽度、行距、截断行数各差一点点,滑过去能看出接缝。合并成这一个。
 *
 * 参数是扁平的展示字段而不是某个 data 层模型:五个调用方的模型各不相同
 * (FeedEntry / SearchVideo / SpaceVideoItem / ToViewItem / AnswerSource),
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
    /**
     * UP 主头像。给了就画在那一行最前面,并且**人名排到时间前面**:只有订阅流给它,那里
     * 决定点不点的先是谁发的,头像又和页首那排关注对得上,扫一眼就认得出人。别处的列表
     * 要么整页同一个人(空间),要么人名只是附注(搜索),不给。
     */
    val upFaceUrl: String? = null,
    /** 播放量与弹幕数。走 [StatRow] 的图标形式,不再拼成中文串。 */
    val playText: String? = null,
    val danmakuText: String? = null,
    /** 计数之外的一行状态文案(稍后再看的"已看完""看到 12:30")。 */
    val meta: String? = null,
    /** 封面左上角的类型角标,见 [VideoCover] 的 typeBadge。收藏夹里的音频、剧集条目用它。 */
    val typeBadge: String = "",
    /** 看过的比例,0..1。null 表示没看过或算不出时长。 */
    val progressFraction: Float? = null,
    /**
     * 第三行的补充说明,颜色跟着 [accentNote] 走。
     * 助理结果的"推荐理由"用它 —— 那句话是助理结果与推荐流的根本区别,永远显示。
     */
    val note: String? = null,
    /** note 是否用强调色。理由用 primary(它是这条为什么在这),状态文案用次级色。 */
    val accentNote: Boolean = false,
    /** 标题里要标出的几段(搜索结果里命中的词),以 primary 色画。 */
    val titleHighlights: List<IntRange> = emptyList(),
)

/**
 * @param trailing 行尾的操作(稍后再看的删除按钮)。放在这里而不是让调用方套一层 Row,
 *   是为了保证操作按钮和文字块的对齐在所有列表里一致。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    // 单遍 Layout 替代原先的 BoxWithConstraints(SubcomposeLayout) + Row(IntrinsicSize.Min):
    // 先按行宽算出封面宽度并量出封面高度,再把封面高度作为文字列的 minHeight 传入,一遍测量即可让
    // 文字比封面矮时上下贴齐封面(SpaceBetween)、文字比封面高时自然撑高且封面垂直居中。
    Layout(
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
        content = {
            ProvideTextStyle(LocalTextStyle.current.tabular) {
                VideoCover(
                    url = item.coverUrl,
                    durationText = item.durationText,
                    progressFraction = item.progressFraction,
                    typeBadge = item.typeBadge,
                )
            }

            Column(
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // bodyLargeEmphasized(16sp/24 Medium)。字号与两行行高预算不变,字重升至 Medium
                // 让主标题明确领跑底下的 onSurfaceVariant 元信息。标题不给溢出按钮让位,见 [TitleOverflowGutter]。
                Text(
                    text = highlightedTitle(item.title, item.titleHighlights),
                    modifier = Modifier.padding(end = if (overflow != null) TitleOverflowGutter else 0.dp),
                    style = MaterialTheme.typography.bodyLargeEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // 标题以下的几行让出按钮那一格。它们本来右边就是空的:元信息是几个 14dp 图标
                // 加几个数字,时间和 UP 名合起来也占不满一行。
                // 上边距是标题与这一块之间的最小间隔:文字撑满封面高度时 SpaceBetween 分不出
                // 空隙来。
                Column(
                    modifier = Modifier
                        .padding(top = Spacing.Hair)
                        .padding(end = if (overflow != null) OverflowReserve else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                ) {
                    // 时间和 UP 名合成一行(PiliPlus `video_card_h.dart` 的 content()):
                    // 两者都是"这条是谁什么时候发的",分两行摆会把三行文字撑到四行,
                    // 而封面高度是固定的,多出来的那行只能让行距变松、看起来更空。
                    SecondaryLine(dateText = item.dateText, upName = item.upName, upFaceUrl = item.upFaceUrl)
                    ProvideTextStyle(LocalTextStyle.current.tabular) {
                        StatRow(playText = item.playText, danmakuText = item.danmakuText)
                    }
                    item.meta?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall.tabular,
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
            //
            // **图标对准最后一行字的中线,不是按钮框贴底。** 贴底时 48dp 的框往上伸到标题
            // 第二行,文字只有两层时图标和标题末尾挤在一起,看着也像悬在半空。往下挪半个框
            // 减半行,图标落在元信息那一行上;多出来的那截触控区伸进行的下边距,被下一行
            // 盖住的部分归下一行,抢不走它的点击。
            //
            // **横向也往外挪一个图标内缩,伸进行的右边距。** 48dp 的格贴着文字列右沿时,图标
            // 离屏幕右沿是 16 + 12 = 28dp,标题再让出一截又更远,左边封面却只离 16dp,整行
            // 看起来右边空得多。挪出去之后图标框的右沿落在 16dp 页边线上,和左边对称。
            if (overflow != null) {
                Box(modifier = Modifier.offset(x = OverflowIconInset, y = OverflowDrop)) {
                    overflow()
                }
            }

            if (trailing != null) {
                Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        },
    ) { measurables, constraints ->
        val contentWidth = constraints.maxWidth
        val gapPx = Spacing.Cozy.roundToPx()
        val coverWidthPx = listCoverWidthFor(contentWidth.toDp() + Spacing.Comfortable * 2).roundToPx()
        val coverPlaceable = measurables[0].measure(Constraints.fixedWidth(coverWidthPx))

        var nextIndex = 2
        val overflowMeasurable = if (overflow != null) measurables[nextIndex++] else null
        val trailingMeasurable = if (trailing != null) measurables[nextIndex] else null

        val trailingPlaceable = trailingMeasurable?.measure(
            Constraints(maxWidth = (contentWidth - coverWidthPx - gapPx).coerceAtLeast(0)),
        )
        val trailingWidth = if (trailingPlaceable != null) trailingPlaceable.width + gapPx else 0
        val textWidthPx = (contentWidth - coverWidthPx - gapPx - trailingWidth).coerceAtLeast(0)

        val textPlaceable = measurables[1].measure(
            Constraints(
                minWidth = textWidthPx,
                maxWidth = textWidthPx,
                minHeight = coverPlaceable.height,
            ),
        )
        val overflowPlaceable = overflowMeasurable?.measure(Constraints())

        // 字体放大或助理理由变长时,右侧内容可能高过固定比例的封面。
        // 居中比把封面钉在顶部更稳定,不会在行尾留下明显的“封面下坠”空白。
        val rowHeight = maxOf(
            coverPlaceable.height,
            textPlaceable.height,
            trailingPlaceable?.height ?: 0,
        )
        layout(contentWidth, rowHeight) {
            coverPlaceable.placeRelative(0, (rowHeight - coverPlaceable.height) / 2)
            val textX = coverWidthPx + gapPx
            val textY = (rowHeight - textPlaceable.height) / 2
            textPlaceable.placeRelative(textX, textY)
            overflowPlaceable?.placeRelative(
                textX + textWidthPx - overflowPlaceable.width,
                textY + textPlaceable.height - overflowPlaceable.height,
            )
            trailingPlaceable?.placeRelative(
                textX + textWidthPx + gapPx,
                (rowHeight - trailingPlaceable.height) / 2,
            )
        }
    }
}

/**
 * 列表行里的封面宽度,按这一行的宽度取:文字列到 [Dimens.ListTextMaxWidth] 为止,余下给封面。
 * 行的左右边距与封面和文字的间距同 [VideoRow];推送行等照这个版式画的行也用它,才和视频行一样大。
 */
fun listCoverWidthFor(rowWidth: Dp): Dp =
    (rowWidth - Spacing.Comfortable * 2 - Spacing.Cozy - Dimens.ListTextMaxWidth)
        .coerceIn(Dimens.ListCoverWidth, Dimens.ListCoverMaxWidth)

@Composable
private fun highlightedTitle(title: String, highlights: List<IntRange>): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        append(title)
        highlights.forEach { range ->
            if (range.last < title.length) addStyle(SpanStyle(color = color), range.first, range.last + 1)
        }
    }
}

private const val DisabledContentAlpha = 0.38f

/** 溢出按钮那一格:M3 的最小触控尺寸。 */
private val OverflowButtonSize = 48.dp

/** 24dp 图标在 48dp 触控格里每边的内缩。按钮往右挪这么多,图标框右沿就在页边线上。 */
private val OverflowIconInset = 12.dp

/**
 * 标题以下的几行让出的宽度:按钮挪出去之后,文字列里被它占的只剩图标框加左边那截内缩。
 */
private val OverflowReserve = OverflowButtonSize - OverflowIconInset

/**
 * 标题让出的宽度:0。按钮挪到页边线之后,图标框右沿和标题右沿都在那条线上,标题和
 * 左边的封面对称;图标只在最后一行,碰不到两行以内的标题。
 */
private val TitleOverflowGutter = 0.dp

/** labelSmall 与 bodySmall 的行高,最后一行只会是这两种之一。 */
private val LastLineHeight = 16.dp

/** 溢出按钮往下挪多少,图标中线才对得上最后一行的中线。 */
private val OverflowDrop = (OverflowButtonSize - LastLineHeight) / 2

/** 元信息行里的头像。比行高略大一点,圆形在一行小字旁边看着才不显小。 */
private val InlineAvatarSize = 18.dp

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
private fun SecondaryLine(dateText: String?, upName: String?, upFaceUrl: String?) {
    // 带头像时人名在前,见 [VideoRowUi.upFaceUrl]。
    val parts = if (upFaceUrl != null) listOf(upName, dateText) else listOf(dateText, upName)
    val text = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(MetaSeparator)
    if (text.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        if (!upFaceUrl.isNullOrEmpty()) Avatar(url = upFaceUrl, size = InlineAvatarSize)
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.tabular,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 宽屏纵向卡片里 UP 主的小头像尺寸。 */
private val GridAvatarSize = 20.dp

/** 带溢出按钮时底栏的最小高度,避免单行元信息时 48dp 触控区向上侵占标题行。 */
private val GridOverflowMinFooterHeight = 32.dp

/**
 * 宽屏多列网格(`Medium+` / `Expanded+`)专用的纵向 16:10 媒体卡。
 *
 * - 封面固定 16:10 与 [Dimens.CoverCornerGrid](14dp)圆角,承载类型/充电角标、时长读数与已看进度条。
 * - 标题固定占满两行(`minLines = 2`),让同一横排卡片的元信息基线严格对齐。
 * - 元信息行可选展示 20dp 头像与可点击 UP 名,与发布时间之间以 [MetaSeparator] 分隔(禁用中点),
 *   下接播放/弹幕等宽计数与行尾 [overflow] 槽位。
 * - 外层通过非消费式的 [interactiveCard] 提供平滑悬停底色(`surfaceContainerLow`、`shapes.large`、8dp 内边距)。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun VideoGridCard(
    ui: VideoRowUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onUpClick: (() -> Unit)? = null,
    upAvatarUrl: String? = null,
    enabled: Boolean = true,
    overflow: (@Composable () -> Unit)? = null,
) {
    val resolvedAvatarUrl = (upAvatarUrl ?: ui.upFaceUrl)?.takeIf { it.isNotBlank() }
    val upText = ui.upName?.takeIf { it.isNotBlank() }
    val dateText = ui.dateText?.takeIf { it.isNotBlank() }
    val showAuthorLine = resolvedAvatarUrl != null || upText != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .interactiveCard(
                shape = MaterialTheme.shapes.large,
                baseColor = Color.Transparent,
                hoverColor = if (enabled) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent,
                enableScale = enabled,
            )
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .alpha(if (enabled) 1f else DisabledContentAlpha)
            .padding(Spacing.Tight),
    ) {
        ProvideTextStyle(LocalTextStyle.current.tabular) {
            VideoCover(
                url = ui.coverUrl,
                durationText = ui.durationText,
                progressFraction = ui.progressFraction,
                cornerRadius = Dimens.CoverCornerGrid,
                typeBadge = ui.typeBadge,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            text = highlightedTitle(ui.title, ui.titleHighlights),
            style = MaterialTheme.typography.bodyLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.Tight),
        )

        Layout(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.Hair),
            content = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                ) {
                    if (showAuthorLine) {
                        GridAuthorLine(
                            upName = upText,
                            dateText = dateText,
                            avatarUrl = resolvedAvatarUrl,
                            onUpClick = onUpClick?.takeIf { enabled },
                        )
                    }
                    ProvideTextStyle(LocalTextStyle.current.tabular) {
                        StatRow(
                            playText = ui.playText,
                            danmakuText = ui.danmakuText,
                            dateText = if (!showAuthorLine) dateText else null,
                        )
                    }
                    ui.meta?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall.tabular,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ui.note?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ui.accentNote) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (overflow != null) {
                    Box { overflow() }
                }
            },
        ) { measurables, constraints ->
            val overflowPlaceable = measurables.getOrNull(1)?.measure(Constraints())
            val standardTouchPx = Dimens.MinTouchTarget.roundToPx()
            val outsetPx = if (overflowPlaceable != null && overflowPlaceable.width >= standardTouchPx) {
                Spacing.Tight.roundToPx()
            } else {
                0
            }
            val reservedEnd = if (overflowPlaceable != null) {
                (overflowPlaceable.width - outsetPx).coerceAtLeast(0)
            } else {
                0
            }
            val metaMaxWidth = if (constraints.hasBoundedWidth) {
                (constraints.maxWidth - reservedEnd).coerceAtLeast(0)
            } else {
                Constraints.Infinity
            }
            val metaMinWidth = if (constraints.hasBoundedWidth) metaMaxWidth else 0
            val metaPlaceable = measurables[0].measure(
                Constraints(minWidth = metaMinWidth, maxWidth = metaMaxWidth),
            )
            val layoutWidth = if (constraints.hasBoundedWidth) {
                constraints.maxWidth
            } else {
                metaPlaceable.width + reservedEnd
            }
            val minFooterHeight = if (overflowPlaceable != null) {
                GridOverflowMinFooterHeight.roundToPx()
            } else {
                0
            }
            val layoutHeight = maxOf(metaPlaceable.height, minFooterHeight)
            layout(layoutWidth, layoutHeight) {
                metaPlaceable.placeRelative(0, (layoutHeight - metaPlaceable.height) / 2)
                if (overflowPlaceable != null) {
                    val overflowX = layoutWidth - overflowPlaceable.width + outsetPx
                    val overflowY = ((layoutHeight - overflowPlaceable.height) / 2)
                        .coerceAtLeast(-Spacing.Hair.roundToPx())
                    overflowPlaceable.placeRelative(overflowX, overflowY)
                }
            }
        }
    }
}

@Composable
private fun GridAuthorLine(
    upName: String?,
    dateText: String?,
    avatarUrl: String?,
    onUpClick: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
            modifier = Modifier
                .weight(1f, fill = false)
                .then(
                    if (onUpClick != null) {
                        Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .clickable(role = Role.Button, onClick = onUpClick)
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (avatarUrl != null) {
                Avatar(url = avatarUrl, size = GridAvatarSize)
            }
            if (upName != null) {
                Text(
                    text = upName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (dateText != null) {
            Text(
                text = MetaSeparator + dateText,
                style = MaterialTheme.typography.labelSmall.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
