package dev.bilby.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.bilby.ui.components.LoadingSpinner
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular

/*
 * 设置页的行与分组。
 *
 * 每一行是一块独立的分段(M3 Expressive 的 SegmentedListItem),组内行与行之间只留
 * SegmentedGap,首尾两行各带一侧大圆角。取代的是一整块 surfaceContainer 容器里摆透明
 * ListItem 的做法:那种写法组的边界清楚,行的边界却只剩文字本身,点下去涟漪铺满整条,
 * 看不出按的是哪一行。依据仍是风格指南 §2.3c 引的 M3 lists 页,contained list 用 gap 分,
 * 这里只是把 containment 从组下放到了行。
 */

/**
 * 一行在它那一组里的位置,决定这一块分段的圆角:首行上圆下方,末行反之,单独一行四角全圆。
 *
 * 用它而不是直接传 ListItemShapes:后者在两套 material3 里都还标着
 * ExperimentalMaterial3ExpressiveApi,出现在函数签名里就得让每个调用方一起 opt-in。
 */
@Immutable
internal data class RowPosition(val index: Int, val count: Int)

/**
 * 一组里的行先登记,由 [SettingsGroup] 数完再画,于是每行的位置不必手写。
 *
 * 设置页的行常常是有条件的:语言那一行只在支持切换的平台出现,排除名单那一行只在排除过人之后
 * 出现。手写下标的话,每加一个条件都要把它后面所有行的数改一遍,漏一处就是一块分段在组中间
 * 长出圆角,编译和预览都发现不了。
 *
 * 登记这一步不是 @Composable,因为组要在画第一行之前知道总数。条件里要读 CompositionLocal 的
 * (LocalSystemActions 之类),在调用 [SettingsGroup] 之前先读成局部变量。
 */
internal class SettingsRows {
    internal val entries = mutableListOf<@Composable (RowPosition) -> Unit>()

    fun row(content: @Composable (RowPosition) -> Unit) {
        entries += content
    }
}

/**
 * 一组设置。标题在分段外面,读起来是这一组的名字,不是第一行。
 *
 * 页面左右边距由这里给,而不是由页面外壳给:关于页那张卡片与分段对齐,也走同一份边距。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsGroup(title: String? = null, rows: SettingsRows.() -> Unit) {
    val entries = SettingsRows().apply(rows).entries
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Comfortable)) {
        if (title != null) {
            GroupTitle(title)
        } else {
            Spacer(modifier = Modifier.height(Spacing.Tight))
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            entries.forEachIndexed { index, entry -> entry(RowPosition(index, entries.size)) }
        }
    }
}

/**
 * 组标题。分组之间不画分割线,靠这行 primary 色的标题和它上方的留白分开:M3 divider 页要求
 * full-width divider 用得 sparingly,每组头上已经有一个带色标题,再压一条线是同一件事说两遍。
 */
@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            // 起点与分段里的文字对齐,分段自带 16dp 内边距。
            start = Spacing.Comfortable,
            end = Spacing.Comfortable,
            // 上下不对称:组与组之间的断开全靠这段上留白,所以它比下方大一档。
            top = Spacing.Loose,
            bottom = Spacing.Tight,
        ),
    )
}

/**
 * 底色取 surfaceContainer 不取 surfaceContainerLow:后者在浅色主题下和页面的 surface 只差
 * 一点,真机上分段的边界几乎看不出来(风格指南 §2.3c 同一条,踩过两次)。
 *
 * 所有设置分段行都走自建 [SettingsSegmentedRow](Surface + Row/Column),不直接调用 Material3
 * 1.5.0-rc01 的 SegmentedListItem / 交互式 ListItem:后者依赖 InteractiveListItemMeasurePolicy,
 * 在 NavDisplay 的 AnimatedContent lookahead 阶段会触发 LookaheadDelegate.getAlignmentLinesOwner NPE。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun segmentedRowShape(position: RowPosition): Shape =
    ListItemDefaults.segmentedShapes(index = position.index, count = position.count).shape

private val SingleLineRowMinHeight = 56.dp
private val TwoLineRowMinHeight = 64.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SegmentedRowContent(
    headlineContent: @Composable () -> Unit,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    leadingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    supportingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val minHeight = if (supportingContent != null) TwoLineRowMinHeight else SingleLineRowMinHeight
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .padding(ListItemDefaults.ContentPadding),
    ) {
        if (leadingContent != null) {
            CompositionLocalProvider(LocalContentColor provides leadingContentColor) {
                leadingContent()
            }
            Spacer(modifier = Modifier.width(Spacing.Cozy))
        }
        Column(modifier = Modifier.weight(1f)) {
            CompositionLocalProvider(
                LocalContentColor provides contentColor,
                LocalTextStyle provides MaterialTheme.typography.bodyLarge,
            ) {
                headlineContent()
            }
            if (supportingContent != null) {
                CompositionLocalProvider(
                    LocalContentColor provides supportingContentColor,
                    LocalTextStyle provides MaterialTheme.typography.bodyMedium,
                ) {
                    supportingContent()
                }
            }
        }
        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(Spacing.Cozy))
            CompositionLocalProvider(
                LocalContentColor provides trailingContentColor,
                LocalTextStyle provides MaterialTheme.typography.labelSmall,
            ) {
                trailingContent()
            }
        }
    }
}

@Composable
internal fun SettingsSegmentedRow(
    shape: Shape,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    leadingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    supportingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        SegmentedRowContent(
            headlineContent = content,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            supportingContent = supportingContent,
            contentColor = contentColor,
            leadingContentColor = leadingContentColor,
            supportingContentColor = supportingContentColor,
            trailingContentColor = trailingContentColor,
        )
    }
}

@Composable
internal fun SettingsSegmentedRow(
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    leadingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    supportingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        SegmentedRowContent(
            headlineContent = content,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            supportingContent = supportingContent,
            contentColor = contentColor,
            leadingContentColor = leadingContentColor,
            supportingContentColor = supportingContentColor,
            trailingContentColor = trailingContentColor,
        )
    }
}

@Composable
internal fun SettingsSegmentedRow(
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    leadingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    supportingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        SegmentedRowContent(
            headlineContent = content,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            supportingContent = supportingContent,
            contentColor = contentColor,
            leadingContentColor = leadingContentColor,
            supportingContentColor = supportingContentColor,
            trailingContentColor = trailingContentColor,
        )
    }
}

@Composable
internal fun SettingsSegmentedRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    leadingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    supportingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingContentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        shape = shape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier.fillMaxWidth(),
    ) {
        SegmentedRowContent(
            headlineContent = content,
            leadingContent = leadingContent,
            trailingContent = trailingContent,
            supportingContent = supportingContent,
            contentColor = contentColor,
            leadingContentColor = leadingContentColor,
            supportingContentColor = supportingContentColor,
            trailingContentColor = trailingContentColor,
        )
    }
}

/**
 * 点这一行之后人会到哪里。行尾图标据此分开:去下一页给箭头,离开应用给外链图标,
 * 弹对话框和当场执行的不给。
 *
 * 从前所有可点行一律画箭头,于是"点进去还有一页"这个承诺被用在了三种不同的行为上 ——
 * 而箭头在 M3 的 list 里说的就是这一件事,给多了它就不再说明任何事情。
 */
internal enum class RowTarget { Page, External, Here }

/**
 * 一行设置。[onClick] 为 null 时画成不可点的一块,外观相同,只是没有按压态。
 *
 * @param icon 行首图标,每一行都有。子页里一行常常只有标题和一句说明,滚动时靠图标比靠读字
 *   更快找到要改的那一项;首页与子页用同一种行,两层看上去才是同一套东西。
 * @param value 这一项当前是什么,写在标题下面第一行,[subtitle] 的说明排在它之后。不放行尾:
 *   行尾的值与标题争同一行的宽度,窄屏上先被截断的是标题,而服务器地址这类长值在行尾本来就
 *   放不下。行尾只留 [RowTarget] 的图标。
 */
@Composable
internal fun SettingRow(
    position: RowPosition,
    icon: ImageVector,
    title: String,
    value: String? = null,
    subtitle: String? = null,
    target: RowTarget = RowTarget.Here,
    onClick: (() -> Unit)? = null,
    /**
     * [value] 画在行尾,不画在标题下面。给数量、倍速这类短读数用:M3 列表把行尾文字留给
     * "价格、数量、日期"这类附加信息(lists.md 的 Trailing text 一节),标题下面是说明文字的
     * 位置。短读数压在一段说明上面时,它短、说明长,主次读反了。
     */
    valueAtEnd: Boolean = false,
    /**
     * 两栏里的左栏行:这一行的页面是否正开在右栏。null 表示不在两栏里。
     *
     * 在两栏里时 [RowTarget.Page] 不画箭头:页面开在旁边,不是"进下一页";哪一页开着由选中底色
     * 说,同私信列表里正开着的那个会话。
     */
    selected: Boolean? = null,
) {
    val colors = MaterialTheme.colorScheme
    val isSelected = selected == true
    val trailingIcon = when (target) {
        RowTarget.Page -> Icons.AutoMirrored.Filled.KeyboardArrowRight.takeIf { selected == null }
        RowTarget.External -> Icons.AutoMirrored.Filled.OpenInNew
        RowTarget.Here -> null
    }
    val endValue = value.takeIf { valueAtEnd }
    val trailing: (@Composable () -> Unit)? = when {
        endValue != null -> { { Text(endValue, style = MaterialTheme.typography.labelLarge.tabular) } }
        trailingIcon != null -> { { Icon(imageVector = trailingIcon, contentDescription = null) } }
        else -> null
    }
    val supporting = listOfNotNull(value.takeUnless { valueAtEnd }, subtitle).joinToString("\n").ifEmpty { null }
    if (onClick == null) {
        StaticSettingRow(position = position, icon = icon, title = title, subtitle = supporting, trailing = trailing)
        return
    }
    SettingsSegmentedRow(
        onClick = onClick,
        shape = segmentedRowShape(position),
        containerColor = if (isSelected) colors.secondaryContainer else colors.surfaceContainer,
        contentColor = if (isSelected) colors.onSecondaryContainer else colors.onSurface,
        leadingContentColor = if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        supportingContentColor = if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        trailingContentColor = if (isSelected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        modifier = Modifier.semantics {
            role = Role.Button
            if (selected != null) {
                this.selected = isSelected
            }
        },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        trailingContent = trailing,
        supportingContent = supporting?.let { { Text(it) } },
    ) {
        Text(title)
    }
}

/**
 * 整行可切换,行尾的 Switch 或 Checkbox 只作指示(onCheckedChange = null)。
 *
 * 整行是一个语义节点,读屏念出开关状态,不必再单独聚焦到行尾那个小控件上。
 * 底色不随开关状态变化:开关状态已经由行尾的 Switch/Checkbox 表达。
 */
@Composable
internal fun ToggleSettingRow(
    position: RowPosition,
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    useCheckbox: Boolean = false,
    /**
     * 值还没读到(服务端的账号设置)。开关的位置换成一个小转圈,点击不起作用;标题、副标题、
     * 行高与颜色都不变。换成另一种行、或者把整行置灰来表示"读取中",读到的那一下都会整行
     * 变样,看起来是闪了一下。
     */
    loading: Boolean = false,
) {
    SettingsSegmentedRow(
        checked = checked,
        onCheckedChange = { if (!loading) onCheckedChange(it) },
        shape = segmentedRowShape(position),
        modifier = Modifier.semantics { role = if (useCheckbox) Role.Checkbox else Role.Switch },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            if (useCheckbox) {
                Checkbox(checked = checked, onCheckedChange = null)
            } else {
                // 转圈叠在一个透明的开关上,占住开关的宽高:两种状态之间行尾不挪位置。
                Box(contentAlignment = Alignment.Center) {
                    Switch(
                        checked = checked,
                        onCheckedChange = null,
                        modifier = if (loading) Modifier.alpha(0f) else Modifier,
                    )
                    if (loading) {
                        LoadingSpinner(size = LoadingIndicatorSize)
                    }
                }
            }
        },
    ) {
        Text(title)
    }
}

/**
 * 不可点的一块分段,里面放什么由调用方排。[ControlSettingRow] 画在它上面。
 */
@Composable
private fun SegmentedTile(position: RowPosition, content: @Composable () -> Unit) {
    Surface(
        shape = segmentedRowShape(position),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

/**
 * 不可点的一行,与相邻可点行对齐圆角、内边距和行高。
 */
@Composable
internal fun StaticSettingRow(
    position: RowPosition,
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    SettingsSegmentedRow(
        shape = segmentedRowShape(position),
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = trailing,
    ) {
        Text(title)
    }
}

/** 开关位置上的加载指示器尺寸。比开关的滑块略大,读得出是在等,又不比开关宽。 */
private val LoadingIndicatorSize = 20.dp

/**
 * 控件直接摆在行里的一行:标题、可选的一行当前值,下面是控件本身(明暗那组连体按钮、配色色板)。
 *
 * 不套 [StaticSettingRow]:那里图标在整行竖直居中,这里下面挂着一排按钮或色块,居中的图标会
 * 悬在标题与控件之间。所以图标与标题首行顶端对齐,内边距和图标间距仍取分段列表的数值,
 * 标题因此与相邻的可点行左缘对齐。
 *
 * 颜色走 LocalContentColor,不写进 TextStyle:控件里的 ToggleButton 靠 LocalContentColor 给选中项
 * 换字色,TextStyle 里的颜色优先级更高,会把它盖掉。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ControlSettingRow(
    position: RowPosition,
    icon: ImageVector,
    title: String,
    value: String? = null,
    control: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    SegmentedTile(position) {
        Row(modifier = Modifier.fillMaxWidth().padding(ListItemDefaults.ContentPadding)) {
            Icon(imageVector = icon, contentDescription = null, tint = colors.onSurfaceVariant)
            Spacer(modifier = Modifier.width(Spacing.Cozy))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                if (value != null) {
                    Text(text = value, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.height(Spacing.Tight))
                CompositionLocalProvider(LocalContentColor provides colors.onSurface) { control() }
            }
        }
    }
}
