package dev.bilby.ui.dynamic

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.R
import dev.bilby.api.BiliConstants
import dev.bilby.data.model.ArticleImage
import dev.bilby.data.model.DynamicAdditional
import dev.bilby.data.model.DynamicCard
import dev.bilby.data.model.DynamicContent
import dev.bilby.ui.CalendarEvent
import dev.bilby.ui.article.ArticleParagraph
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.formatCount
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.CoverCornerRadius
import dev.bilby.ui.components.ImageViewer
import dev.bilby.ui.components.ListCover
import dev.bilby.ui.components.LivePulse
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp

/**
 * 这条动态被点开时要去哪儿。**用一个密封类型而不是七个回调**:调用方只需要认识"要去的地方",
 * 而不是替每一种卡片各接一根线;新增一种卡片时也不必改所有调用点的签名。
 */
sealed interface DynamicAction {
    data class OpenVideo(val bvid: String) : DynamicAction
    data class OpenLive(val roomId: Long) : DynamicAction

    /**
     * 进这个人的直播间,房间号由调用方现查。预约中的直播接口不给房间号(只给 up_mid),
     * 而"这场直播在哪"这个问题在开播前后是同一个答案。
     */
    data class OpenLiveOfUser(val mid: Long) : DynamicAction
    data class OpenArticle(val id: String, val isRead: Boolean) : DynamicAction
    data class OpenUser(val mid: Long) : DynamicAction
    /** 站内没有落点的东西(番剧、音频、活动、收藏夹分享)。由调用方决定是站内解析还是外跳。 */
    data class OpenUrl(val url: String) : DynamicAction
}

/**
 * 一条动态的完整样子:作者 → 正文 → 内容 → 附加块 → 被转发的那条。
 *
 * **转发把被转发的那条原样嵌进来,所以五种形态必须走同一个入口** —— 分散成几个调用点的话,
 * 嵌套那一层就得再挑一遍类型,同一套判断会有两份(空间页的动态 tab 已经踩过这条)。
 *
 * @param nested 为真时是"被转发的那一条":不画自己的日期(外层已经有了),也不再往下嵌套。
 * @param showAuthor 画不画头像与名字。**空间页整页都是同一个人,要传 false** —— 每条重复
 *   印一遍他自己的头像,读起来像一页别人转他的动态。时间那一行照旧留着。
 */
@Composable
fun DynamicCardView(
    card: DynamicCard,
    onAction: (DynamicAction) -> Unit,
    modifier: Modifier = Modifier,
    nested: Boolean = false,
    showAuthor: Boolean = true,
) {
    val block = blockStyle(nested)
    Surface(
        // 一条动态是一张 contained 卡片,**卡片自己带容器,不由调用方套一层**:关注动态页和
        // 空间页各套过一份,底色、圆角、边距三处都对不齐,同一条动态在两页里不是一个样子。
        color = if (nested) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        // 20dp 不是随手挑的:内边距 12 时 20 − 12 = 8,正好落在 shapes.small 上,
        // 里面那些块的圆角因此有据可依(风格指南 §1.4 的 optical roundness)。
        shape = if (nested) block.shape else MaterialTheme.shapes.largeIncreased,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(if (nested) Spacing.Tight else Spacing.Cozy),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            if (!nested) {
                DynamicAuthorRow(card, onAction, showAuthor)
            } else if (card.author.name.isNotBlank()) {
                // 嵌套那一层只留一行名字:再摆一次头像会让转发卡片看起来像两条并排的动态。
                Text(
                    text = card.author.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            DynamicText(card = card, onAction = onAction)

            // 话题不染 primary:这一页里 primary 只标"这段能点",而话题在本应用里没有落点
            // (没有话题页,也不该有——那是一条按热度排的池子)。染成可点色再点不动最难受。
            card.topic?.let {
                Text(
                    text = "#$it",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            card.content?.let { DynamicContentView(it, onAction, block) }
            card.additional?.let {
                DynamicAdditionalView(
                    additional = it,
                    dynamicId = card.id,
                    onAction = onAction,
                    block = block,
                )
            }

            // 被转发的那条自己就是一张卡片,**这里不再多套一层 Surface** —— 套两层等于同一个
            // 边界画两遍,内层的圆角还会比外层方。源没了也要画,否则这条动态看起来像转发者
            // 对着空气说话。
            val forwarded = card.forwarded
            when {
                forwarded != null -> DynamicCardView(card = forwarded, onAction = onAction, nested = true)

                card.forwardTips != null -> Surface(
                    color = block.color,
                    shape = block.shape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = card.forwardTips.ifBlank { stringResource(R.string.dynamic_origin_gone) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.Cozy),
                    )
                }
            }
        }
    }
}

/**
 * 卡片里那些带底色的块(视频、直播、预约、投票、被转发的那条)统一的一档。
 *
 * **底色和圆角必须成对取**,分开写的结果是同一层里两个块一个 12dp 一个 16dp、底色还差一档。
 * 色阶按嵌套深度递增,层次靠 `surfaceContainer` 族的色差表达,不靠描边和阴影(§1.1);
 * 圆角按 optical roundness 从外层推算(§1.4):
 *
 * ```
 * 页面 surface → 卡片 surfaceContainer(20dp,内边距 12)→ 块 surfaceContainerHigh(8dp)
 *              → 被转发的那条也是一个块(8dp,内边距 8)→ 它里面的块 surfaceContainerHighest(4dp)
 * ```
 *
 * 最里面那一档按公式该是 8 − 8 = 0。取 4 而不是 0:直角套在圆角里读起来像没画完,
 * 而 4dp 是刻度上的最后一档。
 */
private data class BlockStyle(val color: Color, val shape: Shape)

@Composable
private fun blockStyle(nested: Boolean) = BlockStyle(
    color = if (nested) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    },
    shape = if (nested) MaterialTheme.shapes.extraSmall else MaterialTheme.shapes.small,
)

/**
 * 动态正文,以及正文只是摘要时的那一行出口。
 *
 * **完整的正文原样铺开,不设行数上限也不给出口。** 绝大多数动态——包括大半被算作"专栏动态"
 * 的图文——接口给的就是全文,在下面挂一行「阅读全文」等于让人为了同样的几行字再跳一次。以前
 * 这里对每条认得出专栏编号的动态都画这一行,一屏十几条就是十几个假出口。
 *
 * **判据是 [DynamicCard.textIsSummary],不是这段文字有多长。** 一条一千字的文字动态也是完整
 * 的,截断它只会让人无处可读;一段五百字的专栏摘要后面却真的还有正文。长度说不出这个区别,
 * 服务端的 `has_more` 说得出。
 *
 * 摘要收在 [DynamicTextMaxLines] 行以内,出口有两种:认得出专栏编号就跳那一页(完整排版和
 * 配图都在那边),认不出就地展开——那时摘要是仅有的一份内容。做成一行字而不是按钮:一屏
 * 十几条动态,每条顶一个 tonal 按钮会排成一列按钮墙,而风格指南 §2.4 给整屏只留一个高强调
 * 按钮。触摸区仍按 48dp 撑开。
 */
@Composable
private fun DynamicText(card: DynamicCard, onAction: (DynamicAction) -> Unit) {
    var expanded by remember(card.id) { mutableStateOf(false) }
    val collapsed = card.textIsSummary && !expanded

    if (card.text.isNotEmpty()) {
        ArticleParagraph(
            spans = card.text,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = DynamicTextLineHeight),
            onLinkClick = { onAction(DynamicAction.OpenUrl(it)) },
            onMentionClick = { onAction(DynamicAction.OpenUser(it)) },
            maxLines = if (collapsed) DynamicTextMaxLines else Int.MAX_VALUE,
        )
    }

    val article = card.article
    // 正文一个字都没有(只有图或只有卡片)时,专栏入口照旧要给:那篇正文没有别的去处。
    val showEntry = card.textIsSummary || (article != null && card.text.isEmpty())
    if (!showEntry || expanded) return

    // 可点区收在这几个字上,不铺满整行:铺满的话正文右边的空白也按得动,而一屏十几条动态
    // 里那片空白正是滑动时手指最常落到的地方。圆角跟着块的那一档,按下去的涟漪才不是方的。
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = Dimens.MinTouchTarget)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button) {
                if (article != null) {
                    onAction(DynamicAction.OpenArticle(article.id, article.isRead))
                } else {
                    expanded = true
                }
            }
            .padding(horizontal = Spacing.Tight),
    ) {
        Text(
            text = stringResource(
                if (article != null) R.string.dynamic_article_open else R.string.dynamic_text_expand,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            imageVector = if (article != null) {
                Icons.AutoMirrored.Outlined.ArrowForward
            } else {
                Icons.Outlined.KeyboardArrowDown
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(Dimens.IconInline),
        )
    }
}

@Composable
private fun DynamicAuthorRow(card: DynamicCard, onAction: (DynamicAction) -> Unit, showAuthor: Boolean) {
    // **这一行只有时间,没有类型名。** 以前恒定印着"图文动态""专栏动态""文字动态""转发动态",
    // 而那些字说的是接口怎么分类,不是读者要判断的东西:一条动态是图是字,看下面那块就知道了;
    // 转发也一样,它下面嵌着一整条别人的动态,那个套起来的框比"转发动态"四个字说得清楚。
    //
    // 时间走全应用同一份相对时间(见 formatRelativeTime)—— 这里以前是绝对日期,同一个 app 里
    // 首页写"4分钟前"、动态卡片写"2026-08-09",读起来像两处在说不同的东西。
    val meta = formatRelativeTime(card.publishedAtEpochSeconds)
    if (!showAuthor) {
        // 空间页这一支以前把 tag 一起丢了,而「置顶」正是在那一页最有用 —— 整页按时间倒序,
        // 唯独第一条不是最新的,没有标记就读成"这个人今天发了条三个月前的东西"。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            card.tag?.let { DynamicTagChip(it) }
        }
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, enabled = card.author.mid != 0L) {
                onAction(DynamicAction.OpenUser(card.author.mid))
            },
    ) {
        Avatar(url = card.author.faceUrl, size = Dimens.AvatarRow)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = card.author.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        card.tag?.let { DynamicTagChip(it) }
    }
}

/**
 * 服务端给的标记,实际见到的只有「置顶」。
 *
 * **做成带底色的一小块,不是一行灰字。** 它和旁边的时间不是同一类信息:时间是这条动态自己的
 * 属性,置顶是这一列的排序被作者按住了 —— 灰字排在时间后面会被读成时间的一部分
 * ("3天前 置顶")。给它一个容器,那一格就退不回文字流里。
 *
 * **底色取 `secondaryContainer`,不染 primary,也不用 alpha 兑色。** primary 在这张卡片里只标
 * "能点进去"(§2.7c),而置顶不是入口;兑出来的色对比度取决于底下是什么,深色主题里会糊
 * (§3 的最后一条)。容器色和文字色成对取自同一组 role。
 *
 * 它不是 §4.2 里被否掉的那种徽章:那条针对的是"有新东西,你该回来看"的注意力标记,
 * 而置顶不随时间变化,也不暗示这里有未读。判据与 `LevelBadge` 那条例外相同。
 */
@Composable
private fun DynamicTagChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = Spacing.Hair),
        )
    }
}

@Composable
private fun DynamicContentView(
    content: DynamicContent,
    onAction: (DynamicAction) -> Unit,
    block: BlockStyle,
) {
    when (content) {
        is DynamicContent.Video -> VideoRow(
            item = VideoRowUi(
                title = content.title,
                coverUrl = content.coverUrl,
                durationText = content.durationText,
                playText = content.playCountText,
                danmakuText = content.danmakuCountText,
            ),
            onClick = { onAction(DynamicAction.OpenVideo(content.bvid)) },
        )

        is DynamicContent.Season -> SideBySideCard(
            title = content.title,
            description = listOf(content.badge, content.durationText).firstOrNull { it.isNotBlank() }.orEmpty(),
            coverUrl = content.coverUrl,
            block = block,
            onClick = content.webUrl.takeIf { it.isNotEmpty() }?.let { { onAction(DynamicAction.OpenUrl(it)) } },
        )

        is DynamicContent.Images -> DynamicImageGrid(content.images)

        is DynamicContent.Live -> LiveCard(content, onAction, block)

        is DynamicContent.Music -> SideBySideCard(
            title = content.title,
            description = content.label,
            coverUrl = content.coverUrl,
            block = block,
            onClick = content.webUrl.takeIf { it.isNotEmpty() }?.let { { onAction(DynamicAction.OpenUrl(it)) } },
        )

        is DynamicContent.Medialist -> SideBySideCard(
            title = content.title,
            description = content.subTitle,
            coverUrl = content.coverUrl,
            block = block,
            onClick = content.webUrl.takeIf { it.isNotEmpty() }?.let { { onAction(DynamicAction.OpenUrl(it)) } },
        )

        is DynamicContent.Common -> SideBySideCard(
            title = content.title,
            description = content.description,
            coverUrl = content.coverUrl,
            block = block,
            onClick = content.webUrl.takeIf { it.isNotEmpty() }?.let { { onAction(DynamicAction.OpenUrl(it)) } },
        )

        is DynamicContent.Gone -> Text(
            text = content.tips.ifBlank { stringResource(R.string.dynamic_origin_gone) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveCard(content: DynamicContent.Live, onAction: (DynamicAction) -> Unit, block: BlockStyle) {
    SideBySideCard(
        title = content.title,
        description = listOf(content.areaName, content.watchingText)
            .filter { it.isNotBlank() }
            .joinToString(MetaSeparator),
        coverUrl = content.coverUrl,
        block = block,
        // 直播是唯一还留着标签的一种,因为"直播中/已结束"说的是**现在的状态**,不是这条动态的
        // 类型 —— 类型那一行已经写着"直播动态"了,而它说不出这个房间此刻能不能进。
        label = stringResource(if (content.live) R.string.dynamic_live_on else R.string.dynamic_live_off),
        // 全卡片唯一还染 primary 的一格:正在播的房间此刻点得进去,这是状态也是入口。
        // 下播之后退回常态色,那时它只是一句陈述。
        labelColor = if (content.live) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        // 已经下播的房间不给入口:点进去只有一块黑屏加一句"主播不在"。
        onClick = if (content.live && content.roomId != 0L) {
            { onAction(DynamicAction.OpenLive(content.roomId)) }
        } else {
            null
        },
        leadingLabel = {
            if (content.live) {
                LivePulse(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Dimens.LivePulseInline),
                )
            }
        },
    )
}

/**
 * 附加块。**它这里的标签留着**:附加块挂在正文下面,说的不是这条动态是什么类型,而是
 * "下面这块是投票/预约/一条视频" —— 和顶上那行类型名不重复。
 */
@Composable
private fun DynamicAdditionalView(
    additional: DynamicAdditional,
    dynamicId: String,
    onAction: (DynamicAction) -> Unit,
    block: BlockStyle,
) {
    when (additional) {
        // 投票**只显示,不投**:投票要一整套选项与已投状态,而动态流里只给得到标题和参与人数,
        // 画一个按得下去却不知道自己投了什么的按钮比不画更糟。想投的人点进原动态。
        // 预约不同,它就是一个开关,状态与入参接口都给全了,所以那一条能操作。
        is DynamicAdditional.Vote -> AdditionalRow(
            label = stringResource(R.string.dynamic_vote),
            title = additional.title,
            description = stringResource(R.string.dynamic_vote_joined, formatCount(additional.joinCount)),
            block = block,
        )

        is DynamicAdditional.Reserve -> ReserveRow(additional, dynamicId, block, onAction)

        is DynamicAdditional.Video -> SideBySideCard(
            title = additional.title,
            description = additional.description,
            coverUrl = additional.coverUrl,
            block = block,
            label = stringResource(R.string.dynamic_type_video),
            onClick = additional.webUrl.takeIf { it.isNotEmpty() }?.let { { onAction(DynamicAction.OpenUrl(it)) } },
        )

        is DynamicAdditional.Common -> SideBySideCard(
            title = additional.title,
            description = additional.description,
            coverUrl = additional.coverUrl,
            block = block,
            label = stringResource(R.string.dynamic_type_common),
            onClick = additional.webUrl.takeIf { it.isNotEmpty() }?.let { { onAction(DynamicAction.OpenUrl(it)) } },
        )
    }
}

/**
 * 左封面右两行字的卡片。番剧、直播、音频、收藏夹、活动共用同一份 —— 它们在信息结构上
 * 是同一件事(一张图、一个名字、一句说明、一个去处),各画一份只会让同一页里的卡片高度、
 * 圆角、留白各差一点点。
 *
 * @param label **默认没有**。这一格以前恒定印着类型名,而作者行上已经有一个 ——
 *   同一条动态里"图文动态"和"专栏动态"两个类型名叠在一起,正文反被挤小。
 *   现在只有说明**状态**(直播中/已结束)或标注附加块时才传。
 * @param onClick 为 null 时整块不可点(内容已失效、直播已结束)。
 */
@Composable
private fun SideBySideCard(
    title: String,
    description: String,
    coverUrl: String,
    block: BlockStyle,
    onClick: (() -> Unit)?,
    label: String? = null,
    /**
     * **默认 `onSurfaceVariant`,不是 `primary`。** 这一格写的是"下面这块是什么"(预约、投票、
     * 一条视频),是块里优先级最低的一行;染成 primary 的话,一屏十几条动态就是十几块高亮,
     * 比正文还响。只有直播那格例外——它说的是此刻能不能进,见 [LiveCard]。
     */
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    leadingLabel: @Composable () -> Unit = {},
) {
    Surface(
        color = block.color,
        shape = block.shape,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.Tight),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (coverUrl.isNotEmpty()) {
                ListCover(url = coverUrl, width = Dimens.CompactCoverWidth)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
                if (label != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        leadingLabel()
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = labelColor,
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 预约块:说明、一个去处、一颗「加入日历」。
 *
 * **不做报给 B 站的那个预约动作**,理由见 [DynamicAdditional.Reserve]:服务端对同一批卡片
 * 给出三种按钮,一列预约里只有小半数按得动。日历不依赖那套状态。
 *
 * **去处按两种预约分开:**
 * - 直播预约整块随时可点,进直播间。没开播时接口不给房间号,所以按 UP 的 mid 现查
 *   ([DynamicAction.OpenLiveOfUser])—— 人没在播的话进去看到的是那个房间的下播页面,
 *   而那正是"这场直播在哪"的答案。
 * - 视频预约要等发布。**判据是本地时间过了预告的时刻**,不是服务端那颗按钮的状态;
 *   地址则用服务端给的 `jump_url`,它在视频发布之后才有值。两者都满足才给入口,少了地址
 *   就点不出结果,时候没到点了也只是一个空壳。
 *
 * 「加入日历」同样按本地时间判:时刻在将来才画。[DynamicAdditional.Reserve.startAtEpochSeconds]
 * 是绝对时刻(解析时按东八区,见 ReserveStartTime),所以设备在哪个时区比较都成立。
 *
 * `TextButton` 而不是实心按钮:风格指南 §2.4 给整屏只留一个 filled/tonal 名额,而这一页是
 * 一列动态,每张卡片顶一颗实心按钮会排成一堵按钮墙。
 */
@Composable
private fun ReserveRow(
    reserve: DynamicAdditional.Reserve,
    dynamicId: String,
    block: BlockStyle,
    onAction: (DynamicAction) -> Unit,
) {
    val context = LocalContext.current
    val nowSeconds = System.currentTimeMillis() / 1000
    val started = reserve.startAtEpochSeconds?.let { it <= nowSeconds } ?: false
    val open: (() -> Unit)? = when {
        reserve.isLive && reserve.upMid != 0L -> {
            { onAction(DynamicAction.OpenLiveOfUser(reserve.upMid)) }
        }

        !reserve.isLive && started && reserve.jumpUrl.isNotEmpty() -> {
            { onAction(DynamicAction.OpenUrl(reserve.jumpUrl)) }
        }

        else -> null
    }
    Surface(
        color = block.color,
        shape = block.shape,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (open != null) Modifier.clickable(role = Role.Button, onClick = open) else Modifier),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Cozy),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            Text(
                text = stringResource(R.string.dynamic_reserve),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (reserve.title.isNotBlank()) {
                Text(reserve.title, style = MaterialTheme.typography.bodyMedium)
            }
            if (reserve.description.isNotBlank()) {
                Text(
                    text = reserve.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 时间解不出来就不摆这个入口:建出来的日程时间是错的,比没有更糟。已经开始的
            // 那些同样不摆:日历提醒不可能在过去响。
            reserve.startAtEpochSeconds?.takeIf { !started }?.let { startAt ->
                val title = reserve.title.ifBlank { stringResource(R.string.dynamic_reserve) }
                // 说明里放这条动态的地址,用户从日历点得回来。**放的不是直播间**:预约块里
                // 没有房间号(接口只给 rid,那是预约的编号),而这条动态是这场预约的入口。
                val description = stringResource(
                    R.string.dynamic_reserve_calendar_note,
                    reserve.description,
                    "${BiliConstants.DYNAMIC_HOST}/$dynamicId",
                )
                TextButton(onClick = { CalendarEvent.insert(context, title, startAt, description) }) {
                    Text(stringResource(R.string.dynamic_reserve_calendar))
                }
            }
        }
    }
}

@Composable
private fun AdditionalRow(label: String, title: String, description: String, block: BlockStyle) {
    Surface(
        color = block.color,
        shape = block.shape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Cozy),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.bodyMedium)
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 图文动态的配图:单张整宽按原比例,多张等分方格,点开进看图器并能左右翻。
 *
 * **专栏正文里的图不走这里**(见 ArticleScreen 的 ArticleImages):那边一律一张一行整宽,
 * 因为正文的图是被读的内容且顺序是作者排的;这里的是一组快照,方格才扫得快。
 *
 * 看图器的状态留在这里,不往上交给页面:一条动态的配图是一组独立的图,转发卡片里那一组
 * 和外层那一组不该串在一起翻 —— 交给页面统一管的话,两组会被拼成一份名单。
 */
@Composable
private fun DynamicImageGrid(images: List<ArticleImage>) {
    var viewerIndex by remember(images) { mutableStateOf<Int?>(null) }
    val columns = if (images.size == 2 || images.size == 4) 2 else 3
    viewerIndex?.let { index ->
        ImageViewer(
            urls = images.map { it.url },
            initialIndex = index,
            onDismiss = { viewerIndex = null },
        )
    }
    // 单张按原图比例整宽铺开,不占九宫格里的一格 —— 一张图配一段话是最常见的一种图文动态,
    // 按三列排会把它缩到三分之一宽,内容基本看不清。多张才成"一组图",那时才是方格。
    if (images.size == 1) {
        val image = images.first()
        val ratio = when {
            image.isLongImage || image.width <= 0 || image.height <= 0 -> 16f / 10f
            else -> image.width.toFloat() / image.height
        }
        BiliAsyncImage(
            url = image.url,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(CoverCornerRadius))
                .aspectRatio(ratio)
                .clickable(role = Role.Button) { viewerIndex = 0 },
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
        images.chunked(columns).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { image ->
                    val index = images.indexOf(image)
                    BiliAsyncImage(
                        url = image.url,
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(CoverCornerRadius))
                            .clickable(role = Role.Button) { viewerIndex = index },
                    )
                }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 动态正文的行高。**按这段文字有多长定,不按字号定**(风格指南 §2.7b 的同一条判据):
 * 一条动态常常是五六行连排的汉字,`bodyLarge` 那档的行高在里面会糊成一片。
 * 不去改 `Typography.bodyLarge` —— 那一档还给列表标题用着,它们要的是紧凑。
 */
private val DynamicTextLineHeight = 26.sp

/**
 * 摘要收在几行以内。**只作用于摘要**,完整正文不受它约束。
 *
 * 12 行在 360dp 宽的屏上约 200 个汉字,一屏还能放下另一条动态。服务端给的摘要一般短于这个数,
 * 所以它多数时候不生效——挡的是偶尔特别长的那一份。
 */
private const val DynamicTextMaxLines = 12

/**
 * 计数折算。分档除数也是本地化资源:中文按万/亿分档,英文按 K/M,只翻译单位后缀会让英文
 * 差一个量级(同一份写法见 SpaceScreen、VideoTabs)。
 */
