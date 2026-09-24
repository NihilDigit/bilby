package dev.bilby.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/**
 * 骨架屏:内容还在读时,先按内容的形状画几块灰色占位,读到了再淡入换掉。
 *
 * M3 transitions 页的 Stable layouts:「Use skeleton loaders so that UI elements are coherent
 * and stable during a transition. Avoid content shifting positions or instantly popping in as
 * it loads」。整屏一个转圈的问题正在后半句:内容回来那一刻,一屏的东西凭空出现,分节加载的
 * 页面还会一节一节往下顶。占位按真实行的尺寸画,内容换上来时什么都不挪。
 *
 * 几条约定:
 * - **形状照实画。** 占位的封面、标题、元信息和真实的行同尺寸同位置,换上来时只有颜色在变。
 *   条数按"一屏大概能放几条"给,不是承诺会有这么多 —— 真的是空的,淡出之后就是空态。
 * - **脉动,不扫光。** 规范写的是 "a subtle pulsing animation":整块透明度在两档之间来回。
 *   一屏只有一个动画时钟([SkeletonPulse]),所有占位块读同一个值,不是每块各起一个。
 * - **只用于首屏和分节首载。** 翻页的底部、下拉刷新仍是 loading indicator:那时候列表已经
 *   在屏幕上,没有要稳住的布局。
 * - 颜色取 surfaceContainerHighest,层次只用 container 色阶(风格指南 §1.1)。
 */

/** 一片骨架共用的脉动值,由 [SkeletonPulse] 提供。没有提供时占位静止不动。 */
private val LocalSkeletonAlpha = compositionLocalOf<State<Float>?> { null }

/**
 * 给 [content] 里所有占位块提供同一个脉动。放在一片骨架的最外层。
 *
 * 读值放在 `graphicsLayer` 的 lambda 里(见 [skeleton]):透明度每帧在变,在组合阶段读的话
 * 每帧整片重组一次。
 */
@Composable
fun SkeletonPulse(content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha = transition.animateFloat(
        initialValue = SkeletonAlphaHigh,
        targetValue = SkeletonAlphaLow,
        animationSpec = infiniteRepeatable(tween(SkeletonPulseMillis), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    CompositionLocalProvider(LocalSkeletonAlpha provides alpha, content = content)
}

/** 把这一块画成占位:底色加形状,透明度跟着 [SkeletonPulse] 走。 */
@Composable
fun Modifier.skeleton(shape: Shape = SkeletonTextShape): Modifier {
    val alpha = LocalSkeletonAlpha.current
    val color = MaterialTheme.colorScheme.surfaceContainerHighest
    return this
        .graphicsLayer { this.alpha = alpha?.value ?: 1f }
        .background(color, shape)
}

/** 一行字的占位。高度取正文行高里字形那一截,不是整个行高 —— 整个行高画出来是一根粗棍。 */
@Composable
fun SkeletonLine(modifier: Modifier = Modifier, height: Dp = SkeletonLineHeight) {
    Box(modifier = modifier.height(height).skeleton())
}

/**
 * [VideoRow] 的占位:同一个页边、同一个封面尺寸、同一条行高。标题两行(第二行短一截,两行标题
 * 本来就很少排满)、元信息一行。
 */
@Composable
fun VideoRowSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(Dimens.ListCoverWidth)
                .aspectRatio(CoverAspectRatio)
                .skeleton(RoundedCornerShape(CoverCornerRadius)),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            SkeletonLine(Modifier.fillMaxWidth())
            SkeletonLine(Modifier.fillMaxWidth(SecondLineFraction))
            SkeletonLine(Modifier.fillMaxWidth(MetaLineFraction).padding(top = Spacing.Hair), height = SkeletonMetaHeight)
        }
    }
}

/**
 * 头像加两行字的占位:关注列表、黑名单、消息、搜索用户这类"一个人一行"的列表。
 *
 * 两种真实的行长得不一样,占位跟着各自的尺寸画,换上内容时不跳:
 * - 消息、搜索用户:48dp 头像,上下 12(默认值);
 * - 关注、黑名单:M3 `ListItem` 两行形态,36dp 头像,整行 72dp 高 —— 传 [ListItemPersonSkeleton]
 *   那一组参数。
 */
@Composable
fun PersonRowSkeleton(
    modifier: Modifier = Modifier,
    avatarSize: Dp = Dimens.AvatarStack,
    verticalPadding: Dp = Spacing.Cozy,
    minHeight: Dp = 0.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .padding(horizontal = Spacing.Comfortable, vertical = verticalPadding),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(avatarSize).skeleton(CircleShape))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            SkeletonLine(Modifier.fillMaxWidth(NameLineFraction))
            SkeletonLine(Modifier.fillMaxWidth(MetaLineFraction), height = SkeletonMetaHeight)
        }
    }
}

/** `ListItem` 两行形态那种一人一行的占位:36dp 头像,上下 8,整行至少 72dp(lists.md 规格)。 */
@Composable
fun ListItemPersonSkeleton(modifier: Modifier = Modifier) {
    PersonRowSkeleton(
        modifier = modifier,
        avatarSize = Dimens.AvatarRow,
        verticalPadding = Spacing.Tight,
        minHeight = TwoLineListItemHeight,
    )
}

private val TwoLineListItemHeight = 72.dp

/**
 * 一条动态卡片的占位:卡片底色与圆角同 DynamicCardView,里面是头像加名字、两行正文、一块图。
 * 动态的真实高度差得很远(一句话到九宫格),这里取常见的"一段字加一张图"。
 */
@Composable
fun DynamicCardSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Hair + Spacing.Hair / 2)
            .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.largeIncreased)
            .padding(Spacing.Cozy),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Box(modifier = Modifier.size(Dimens.AvatarRow).skeleton(CircleShape))
            SkeletonLine(Modifier.fillMaxWidth(NameLineFraction))
        }
        SkeletonLine(Modifier.fillMaxWidth())
        SkeletonLine(Modifier.fillMaxWidth(SecondLineFraction))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CoverAspectRatio)
                .skeleton(MaterialTheme.shapes.medium),
        )
    }
}

/**
 * 一屏的骨架:[row] 重复 [count] 次,外面一层脉动。**不可滚**,超出视口的裁掉 —— 它只是
 * 这一屏的样子,不是一个列表。
 */
@Composable
fun ListSkeleton(
    modifier: Modifier = Modifier,
    count: Int = SkeletonScreenRows,
    row: @Composable () -> Unit = { VideoRowSkeleton() },
) {
    SkeletonPulse {
        Column(modifier = modifier.fillMaxSize().clipToBounds()) {
            repeat(count) { row() }
        }
    }
}

/** 一屏大概放得下几行列表。多给几行没关系,多出来的裁在视口外。 */
private const val SkeletonScreenRows = 10

private val SkeletonTextShape = RoundedCornerShape(4.dp)
private val SkeletonLineHeight = 14.dp
private val SkeletonMetaHeight = 10.dp
private const val SecondLineFraction = 0.6f
private const val MetaLineFraction = 0.4f
private const val NameLineFraction = 0.5f

/** 脉动的两档透明度与半个周期。一秒来回一次,慢到不像在闪。 */
private const val SkeletonAlphaHigh = 1f
private const val SkeletonAlphaLow = 0.45f
private const val SkeletonPulseMillis = 900
