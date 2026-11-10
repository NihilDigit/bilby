package dev.bilby.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import dev.bilby.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilby.api.toHttpsUrl
import dev.bilby.live.LiveEmote
import dev.bilby.live.LiveEmoteMatch
import dev.bilby.live.LiveFanMedal
import dev.bilby.live.findEmotes
import dev.bilby.resources.*
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.inlineEmoteSize
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing

/**
 * 消息流里的一行。
 *
 * 四类消息共用这一个入口,而**它们的槽位排布是一致的:文字一律从左边开始**。M3 的 lists 页对
 * 异构列表的要求就是这一条 —— "Place supporting visuals and primary text in the same position in
 * each list item. Don't vary the position of elements within a list."
 *
 * **弹幕行没有头像**,尽管消息里带着(`info[0][15].user.base.face`,见 `LiveMessage.Danmaku`)。
 * 判据是密度:直播聊天的价值在于一屏能读到几行,而 `Dimens.AvatarRow` 那一档会把一行从 24dp 撑到
 * 48dp,手机上从十二行掉到六行。风格指南 §2.7b 只允许三档头像尺寸,所以这里没有"画小一点"这个
 * 折中 —— 要么占满一行,要么不画。醒目留言那一行例外:它本来就是两行的块,而且不常出现。
 *
 * 类别的区分因此靠容器色加文字,不靠一列图标。**颜色不是唯一线索** —— 每一类的文字本身就说明
 * 它是什么("开通了舰长"、"主播已下播"、金额数字)。
 */
@Composable
fun LiveFeedRow(item: LiveFeedItem, onUserClick: (Long) -> Unit, modifier: Modifier = Modifier) {
    when (item) {
        is LiveFeedItem.Danmaku -> DanmakuRow(item, onUserClick, modifier)
        is LiveFeedItem.SuperChat -> SuperChatRow(item, onUserClick, modifier)
        is LiveFeedItem.Guard -> GuardRow(item, onUserClick, modifier)
        is LiveFeedItem.Notice -> NoticeRow(item, modifier)
    }
}

/**
 * 一条弹幕。
 *
 * **整行是一段文字,不是几个并排的控件。** 勋章、昵称、正文串在同一个 [buildAnnotatedString] 里,
 * 于是长句子会自然折到下一行的最左边。把昵称拆成一个定宽的 `Text`、正文另占一列(此前的写法)会
 * 让正文被挤在半个屏幕里,而聊天要看的正是正文。
 *
 * 自己发的那条把昵称换成 `primary`。它是这一栏里唯一需要被认出来的行 —— 发出去之后要能确认
 * 服务端收到了(本地不回显,见 `LiveRoomViewModel.sendDanmaku`)。
 */
@Composable
private fun DanmakuRow(item: LiveFeedItem.Danmaku, onUserClick: (Long) -> Unit, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyMedium
    // 昵称那一段挂 LinkAnnotation,点它进这个人的空间。**用 rememberUpdatedState 取最新的
    // 回调**:下面那段 AnnotatedString 按 item 记忆,而回调每次重组都是新的 lambda,
    // 把它排进 remember 的键会让每条弹幕每帧重建一次文本 —— 这是每秒几十行的热路径。
    val openUser by rememberUpdatedState(onUserClick)
    val containerColor = if (item.isSelf) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val nameColor = if (item.isSelf) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bodyColor = if (item.isSelf) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val medalInline = rememberMedalInline(item.medal)
    val inlineMatches = remember(item.text, item.emote, item.inlineEmotes) {
        if (item.emote != null) emptyList() else findEmotes(item.text, item.inlineEmotes)
    }
    val emoteInline = rememberEmoteInline(style, item.emote, inlineMatches)
    val text = remember(item, inlineMatches, nameColor, bodyColor) {
        buildAnnotatedString {
            appendMedal(item.medal)
            appendName(item.name, item.mid, nameColor) { openUser(it) }
            withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Medium)) { append("：") }
            // 回复某人:本项目没有可跳转的目标页,所以它只是一段低强调的字,不染可点色。
            item.replyName?.let {
                withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Medium)) { append("@$it ") }
            }
            if (item.emote != null) {
                appendInlineContent(StickerInlineTag, item.text.ifBlank { "[表情]" })
            } else if (inlineMatches.isEmpty()) {
                withStyle(SpanStyle(color = bodyColor)) { append(item.text) }
            } else {
                var cursor = 0
                inlineMatches.forEachIndexed { index, match ->
                    if (match.start > cursor) {
                        withStyle(SpanStyle(color = bodyColor)) {
                            append(item.text.substring(cursor, match.start))
                        }
                    }
                    appendInlineContent(inlineEmoteTag(index), item.text.substring(match.start, match.end))
                    cursor = match.end
                }
                if (cursor < item.text.length) {
                    withStyle(SpanStyle(color = bodyColor)) {
                        append(item.text.substring(cursor))
                    }
                }
            }
        }
    }
    // 每条弹幕套一层紧凑的 surfaceContainer 圆角胶囊(contained list,风格指南 §2.3c):
    // 短弹幕按内容宽度成块,两行长弹幕折行后收在自己的气泡内,不与上下行的昵称粘成一片。
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = style,
            inlineContent = if (emoteInline.isEmpty()) medalInline else medalInline + emoteInline,
            modifier = Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
        )
    }
}

/**
 * 一条醒目留言。
 *
 * 按 M3 的 containment 做法直接用该档位的成对容器色([FixedColors.superChatPalette])作底色与前景色,
 * 不用左侧竖条加灰底(风格指南 §2.3c)。
 *
 * 被撤回的那条留在流里并加删除线(理由见 [LiveFeedItem.SuperChat.removed])。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SuperChatRow(item: LiveFeedItem.SuperChat, onUserClick: (Long) -> Unit, modifier: Modifier = Modifier) {
    val sc = item.message
    // 档位色分深浅两套,而判断深浅要看**此刻主题的 surface**,不能问系统:动态取色和强制深色
    // 两种情况下系统的答案都可能与眼前这一屏不一致。
    val dark = MaterialTheme.colorScheme.surface.luminance() < DarkSurfaceLuminance
    val palette = FixedColors.superChatPalette(sc.priceYuan, dark)
    Surface(
        color = palette.container,
        contentColor = palette.onContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                // 头像加昵称一起可点,正文不可点 —— 这张卡片上要读的是留言本身,
                // 整卡可点会让"想读完"和"想看看是谁"变成同一个手势。
                SenderRow(
                    face = sc.senderFace,
                    name = sc.senderName,
                    contentColor = palette.onContainer,
                    modifier = Modifier.weight(1f),
                    onClick = { onUserClick(sc.senderMid) },
                )
                Text(
                    text = stringResource(Res.string.live_super_chat_price, sc.priceYuan),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onContainer,
                )
            }
            Text(
                text = sc.message,
                // 同字号换 token,行高和布局都不动。醒目留言相对周围的弹幕正是 M3 说的
                // "highlighted moment",而放大字号会把行高一起改掉,整条流跟着跳。
                style = MaterialTheme.typography.bodyMediumEmphasized,
                textDecoration = if (item.removed) TextDecoration.LineThrough else null,
                color = palette.onContainer,
            )
        }
    }
}

/**
 * 有人上舰。
 *
 * 按总督/提督/舰长三档取对应的成对容器色([FixedColors.guardPalette]),与大航海列表页共用同一套色调。
 * 月数只有 `USER_TOAST_MSG` 那条带,拿不到就不写 —— 「开通了舰长」本身是完整的一句话,而补一个
 * 猜出来的月数是在说一件没有依据的事。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GuardRow(item: LiveFeedItem.Guard, onUserClick: (Long) -> Unit, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < DarkSurfaceLuminance
    val palette = FixedColors.guardPalette(item.guardLevel, dark)
    val level = guardLevelLabel(item.guardLevel)
    Surface(
        onClick = { onUserClick(item.mid) },
        color = palette.container,
        contentColor = palette.onContainer,
        shape = MaterialTheme.shapes.small,
        // 用 minimumInteractiveComponentSize 保证 48dp 触控热区(Dimens.MinTouchTarget),而不把色块背景本身硬撑成 48dp 高的大空块。
        modifier = modifier.fillMaxWidth().minimumInteractiveComponentSize(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Surface(
                color = palette.onContainer.copy(alpha = GuardBadgeBgAlpha),
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    text = level,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onContainer,
                    modifier = Modifier.padding(horizontal = Spacing.Hair, vertical = 2.dp),
                )
            }
            Text(
                text = item.months?.let {
                    stringResource(Res.string.live_guard_opened_months, item.name, level, it)
                } ?: stringResource(Res.string.live_guard_opened, item.name, level),
                style = MaterialTheme.typography.bodyMediumEmphasized,
                color = palette.onContainer,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 大航海列表页与流内共用的舰种名称。 */
@Composable
internal fun guardLevelLabel(guardLevel: Int): String = stringResource(
    when (guardLevel) {
        1 -> Res.string.live_guard_governor
        2 -> Res.string.live_guard_admiral
        else -> Res.string.live_guard_captain
    },
)

/** 大航海列表页行尾的舰种色调胶囊(总督金、提督紫、舰长蓝)。 */
@Composable
internal fun GuardLevelBadge(guardLevel: Int, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < DarkSurfaceLuminance
    val palette = FixedColors.guardPalette(guardLevel, dark)
    Surface(
        color = palette.container,
        contentColor = palette.onContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            text = guardLevelLabel(guardLevel),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = palette.onContainer,
            modifier = Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
        )
    }
}

/**
 * 系统提示。
 *
 * 开播下播、以及连接断开与恢复,都是居中一行灰字,没有头像;超管警告和自己被禁言换成
 * `errorContainer` 的一块 —— 那两条要求读者立刻做点什么(调整内容、停止发言),其余的只是陈述。
 *
 * **超管那两条用服务端的原话。** 「图片内容不适宜,请立即调整」是一条具体指示,本地改写只会
 * 丢掉读者真正需要的那部分。
 */
@Composable
private fun NoticeRow(item: LiveFeedItem.Notice, modifier: Modifier = Modifier) {
    val urgent = item.kind == LiveFeedItem.Notice.Kind.Warning ||
        item.kind == LiveFeedItem.Notice.Kind.CutOff ||
        item.kind == LiveFeedItem.Notice.Kind.SelfBlocked
    val text = item.message ?: stringResource(
        when (item.kind) {
            LiveFeedItem.Notice.Kind.LiveStarted -> Res.string.live_notice_started
            LiveFeedItem.Notice.Kind.LiveEnded -> Res.string.live_notice_ended
            LiveFeedItem.Notice.Kind.SelfBlocked -> Res.string.live_notice_self_blocked
            // 连接状态那两条走这里的低强调分支:它们不要求读者做什么(重连是自动的),
            // 只是标出"这段中间的消息没读到"。所以不进上面那个 errorContainer 的块。
            LiveFeedItem.Notice.Kind.Disconnected -> Res.string.live_notice_disconnected
            LiveFeedItem.Notice.Kind.Reconnected -> Res.string.live_notice_reconnected
            else -> Res.string.live_notice_warning
        },
    )
    if (!urgent) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(vertical = Spacing.Tight),
        )
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
        )
    }
}

/**
 * 把勋章拼进文字流,占一个 [InlineTextContent] 的位置。
 *
 * **不能用 `SpanStyle.background` 画那一块底色。** 那样写不必先量文字,曾经是这里的做法;但它
 * 画出来的是**整行行盒**的高度,跟着这一行最高的那段文字走,而不是勋章自己的字号 —— 于是把
 * 勋章字号调小只让字变小,色块照旧那么高,真机上一眼就能看出来。占位符要在测量前给出尺寸,
 * 所以牌名先量一遍([rememberTextMeasurer]),按 [key] 记住结果:同一个人连发十条弹幕只量一次。
 *
 * 换成占位符之后顺带拿回了圆角,而勋章仍然跟着昵称一起自然折行 —— 那是当初不摆一个并排
 * `Surface` 的理由,这一条没有变。
 */
private fun AnnotatedString.Builder.appendMedal(medal: LiveFanMedal?) {
    if (medal == null) return
    appendInlineContent(MedalInlineTag, medal.name)
    append(" ")
}

/**
 * 勋章那一格的 [InlineTextContent],尺寸按牌名量出来。
 *
 * **样式照 PiliPlus 的 `MedalWidget`**(`lib/pages/member/widget/medal_widget.dart`):字号固定
 * 10sp(不跟正文缩放)、行高压成 1 倍字号、左右 6 上下 3、圆角 10、等级那一段加粗。行高那一条
 * 是关键 —— 它对应 Flutter 那边的 `StrutStyle(height: 1, leading: 0)`,不压的话默认行距会让
 * 色块比字高出四五个 dp,一列几十行看过去就是一列在跳的色块。
 */
@Composable
private fun rememberMedalInline(medal: LiveFanMedal?): Map<String, InlineTextContent> {
    if (medal == null) return emptyMap()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textColor = Color(medal.textArgb)
    val label = remember(medal.name, medal.level, textColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = textColor)) { append(medal.name) }
            withStyle(SpanStyle(color = textColor, fontWeight = FontWeight.Bold)) { append(" ${medal.level}") }
        }
    }
    val labelStyle = remember {
        TextStyle(
            fontSize = MedalFontSize,
            lineHeight = MedalFontSize,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
        )
    }
    val content = remember(label, medal.backgroundArgb) {
        val measured = measurer.measure(label, labelStyle)
        // 先在像素上加内边距再换成 sp:TextUnit 之间没有加法,而占位符只收 sp。
        val width = with(density) { (measured.size.width + (MedalHPadding * 2).roundToPx()).toSp() }
        val height = with(density) { (measured.size.height + (MedalVPadding * 2).roundToPx()).toSp() }
        InlineTextContent(
            Placeholder(width, height, PlaceholderVerticalAlign.TextCenter),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(MedalCorner))
                    .background(Color(medal.backgroundArgb)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = label, style = labelStyle, maxLines = 1)
            }
        }
    }
    return mapOf(MedalInlineTag to content)
}

/**
 * 昵称那一段,可点,进这个人的空间。
 *
 * **不染链接色。** 一条聊天流里每一行都有昵称,把它们全画成链接就是一屏彩色的字,而这一栏
 * 要读的是正文;可点是次要通路,不必先声夺人。走 `LinkAnnotation` 而不是自己接 pointerInput,
 * 命中、按压反馈和读屏都由文本层负责(同一条理由见 CommentSection 里的时间戳)。
 */
private fun AnnotatedString.Builder.appendName(
    name: String,
    mid: Long,
    nameColor: Color,
    onUserClick: (Long) -> Unit,
) {
    if (mid == 0L) {
        withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Medium)) { append(name) }
        return
    }
    // **显式关掉下划线。** 不给 styles 时用的是文本层的默认链接样式,那一份带下划线;
    // 一屏几十行昵称全画上下划线,读起来就是一列横杠。字重抬到 Medium(对应 emphasized 档),
    // 让句子式的中文昵称与正文一眼分开。
    val plain = TextLinkStyles(
        style = SpanStyle(
            color = nameColor,
            fontWeight = FontWeight.Medium,
            textDecoration = TextDecoration.None,
        ),
    )
    withLink(LinkAnnotation.Clickable(tag = "live-user-$mid", styles = plain) { onUserClick(mid) }) {
        append(name)
    }
}

/**
 * 聊天行里的表情占位符(整条大表情与正文内联小表情)。
 *
 * 高度一律走 [inlineEmoteSize] 夹在当前行高之内(风格指南 §2.7c & `docs/live-room-redesign.md` §3.3):
 * Compose 的 `lineHeight` 是硬行距,占位符超过行高不会把行撑高,只会压到上下行文字上。
 */
@Composable
private fun rememberEmoteInline(
    style: TextStyle,
    sticker: LiveEmote?,
    inlineMatches: List<LiveEmoteMatch>,
): Map<String, InlineTextContent> {
    if (sticker == null && inlineMatches.isEmpty()) return emptyMap()
    val baseHeight = inlineEmoteSize(style)
    return remember(sticker, inlineMatches, baseHeight) {
        buildMap {
            if (sticker != null) {
                val aspect = if (sticker.widthPx > 0 && sticker.heightPx > 0) {
                    (sticker.widthPx.toFloat() / sticker.heightPx).coerceIn(0.5f, 2.5f)
                } else {
                    1f
                }
                val width = (baseHeight.value * aspect).sp
                put(
                    StickerInlineTag,
                    InlineTextContent(
                        Placeholder(width, baseHeight, PlaceholderVerticalAlign.TextCenter),
                    ) {
                        BiliAsyncImage(
                            url = sticker.url.toHttpsUrl(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            }
            inlineMatches.forEachIndexed { index, match ->
                val emote = match.emote
                val aspect = if (emote.widthPx > 0 && emote.heightPx > 0) {
                    (emote.widthPx.toFloat() / emote.heightPx).coerceIn(0.5f, 2.5f)
                } else {
                    1f
                }
                val width = (baseHeight.value * aspect).sp
                put(
                    inlineEmoteTag(index),
                    InlineTextContent(
                        Placeholder(width, baseHeight, PlaceholderVerticalAlign.TextCenter),
                    ) {
                        BiliAsyncImage(
                            url = emote.url.toHttpsUrl(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                )
            }
        }
    }
}

private fun inlineEmoteTag(index: Int) = "live-emote-$index"

/** 头像加昵称,一起可点。醒目留言的三处(流里那一行、顶部展开卡、汇总屏那张卡)用的是同一份。 */
@Composable
internal fun SenderRow(
    face: String,
    name: String,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Avatar(url = face, size = Dimens.AvatarNested)
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// 勋章那一块的四个数,全部照 PiliPlus 的 MedalWidget(见 rememberMedalInline)。
// **字号是定值,不跟正文缩放** —— 它是身份标记,在哪儿都该一样大。
private val MedalFontSize = 10.sp
private val MedalHPadding = 6.dp
private val MedalVPadding = 3.dp
private val MedalCorner = 10.dp

private const val MedalInlineTag = "live-medal"
private const val StickerInlineTag = "live-sticker"
private const val GuardBadgeBgAlpha = 0.14f

/**
 * `surface` 的相对亮度低于这个数就按深色主题取档位色。0.5 是中点,而深浅两套 surface
 * (`#FAF8FF` 与 `#121318`)离它都很远,不存在需要调这个阈值的中间地带。
 */
/** 档位色分明暗两套时判"此刻是深色主题"的阈值。上方那一栏 chip 也用它。 */
internal const val DarkSurfaceLuminance = 0.5f
