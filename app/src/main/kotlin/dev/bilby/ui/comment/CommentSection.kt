package dev.bilby.ui.comment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilby.data.CommentItem
import dev.bilby.data.CommentSort
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.ImageViewer
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.LocalMentionColor
import dev.bilby.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

private const val PrefetchThreshold = 5

/**
 * 可嵌进播放页的评论区(DESIGN 2.3)。不是整页:自带 LazyColumn 提供滚动,但不假设自己
 * 独占屏幕,调用方通过 [modifier] 给出高度约束。楼中楼默认只显示
 * [CommentItem.previewReplies],点「展开」才请求更多。
 */
@Composable
fun CommentSection(
    state: CommentUiState,
    onSort: (CommentSort) -> Unit,
    onLoadMore: () -> Unit,
    onExpandReplies: (rootId: Long) -> Unit,
    onSend: (text: String, replyTo: Long?) -> Unit,
    onLike: (id: Long) -> Unit,
    onDelete: (id: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var replyTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 触底预取,写法照抄 FeedScreen:在 composition 外用 snapshotFlow 观察滚动位置,
    // 不能在 composable body 里直接调用 onLoadMore(那样每次重组都会触发一次)。
    LaunchedEffect(listState, state.hasMore, state.appending) {
        snapshotFlow { listState.layoutInfo }
            .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
            .distinctUntilChanged()
            .filter { (lastVisible, total) -> lastVisible != null && lastVisible >= total - 1 - PrefetchThreshold }
            .collect {
                if (state.hasMore && !state.appending) onLoadMore()
            }
    }

    Column(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = Spacing.Tight),
        ) {
            item(key = "sort-bar") { SortBar(state.sort, onSort) }

            state.topComment?.let { top ->
                item(key = "top-${top.rpid}") {
                    CommentRow(
                        comment = top,
                        pinned = true,
                        myMid = state.myMid,
                        expanded = state.expandedReplies[top.rpid],
                        onReply = { replyTarget = top.rpid },
                        onLike = onLike,
                        onDelete = onDelete,
                        onExpandReplies = onExpandReplies,
                    )
                }
            }

            if (!state.loading && state.items.isEmpty() && state.topComment == null) {
                item(key = "empty") { EmptyState("还没有评论") }
            }

            items(state.items, key = { it.rpid }) { comment ->
                // **主楼之间画 inset 分割线。** 一条热评加上楼中楼容器可以占到半屏,
                // 只靠留白的话上一条的楼中楼和下一条的头像挨在一起,读不出哪里换了人。
                //
                // 用 inset 而不是 full-width:M3 divider 页把 inset 定义为"分隔一个区块内部
                // 的相关内容",并要求它对齐头像这类锚定元素的前缘 —— 评论列表正是那一页
                // 举的"一列邮件"的例子。full-width 是留给不相关的大段内容的,评论条与条之间
                // 不是那个关系。
                HorizontalDivider(
                    modifier = Modifier.padding(start = CommentTextInset, end = Spacing.Comfortable),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                CommentRow(
                    comment = comment,
                    pinned = false,
                    myMid = state.myMid,
                    expanded = state.expandedReplies[comment.rpid],
                    onReply = { replyTarget = comment.rpid },
                    onLike = onLike,
                    onDelete = onDelete,
                    onExpandReplies = onExpandReplies,
                )
            }

            item(key = "footer") {
                if (state.loading) {
                    Box(Modifier.fillMaxWidth().padding(Spacing.Comfortable), Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    ListFooter(state.appending, state.hasMore, state.items.isNotEmpty())
                }
            }
        }
        HorizontalDivider()
        CommentInputBar(
            text = inputText,
            onTextChange = { inputText = it },
            replyTarget = replyTarget?.let { id -> findUname(state, id) },
            onCancelReply = { replyTarget = null },
            sending = state.sending,
            onSend = {
                onSend(inputText, replyTarget)
                inputText = ""
                replyTarget = null
            },
        )
    }
}

private fun findUname(state: CommentUiState, rpid: Long): String? {
    if (state.topComment?.rpid == rpid) return state.topComment.uname
    state.items.find { it.rpid == rpid }?.let { return it.uname }
    state.items.forEach { top -> top.previewReplies.find { it.rpid == rpid }?.let { return it.uname } }
    state.expandedReplies.values.forEach { expanded ->
        expanded.items.find { it.rpid == rpid }?.let { return it.uname }
    }
    return null
}

/** 最热 / 最新是排序,M3 把"排序元素"划给 segmented button;chip 是给动态选项集用的。 */
@Composable
private fun SortBar(sort: CommentSort, onSort: (CommentSort) -> Unit) {
    val options = listOf(CommentSort.HOT to "最热", CommentSort.TIME to "最新")
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Hair),
    ) {
        options.forEachIndexed { index, pair ->
            SegmentedButton(
                selected = sort == pair.first,
                onClick = { onSort(pair.first) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                label = { Text(pair.second) },
            )
        }
    }
}

@Composable
private fun CommentRow(
    comment: CommentItem,
    pinned: Boolean,
    myMid: Long?,
    expanded: ExpandedReplies?,
    onReply: () -> Unit,
    onLike: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onExpandReplies: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.Comfortable, end = Spacing.Cozy, top = Spacing.Cozy, bottom = Spacing.Hair),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
    ) {
        Avatar(url = comment.avatarUrl, size = Dimens.AvatarSmall)
        Column(modifier = Modifier.weight(1f)) {
            // 头部两行(名字 / 时间·属地)是一块整的元信息,照 PiliPlus 的
            // `reply_item_grpc.dart` 的 _buildHeader。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                // **用户名用 outline,不用满对比度。** 一屏几十条评论,真正要读的是正文;
                // 名字和正文一样重的话,视线会被每条开头的名字拽住,整片看起来就是一团。
                // PiliPlus 也是把 member.name 画成 outline 的。
                Text(
                    text = comment.uname,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (comment.isUploader) {
                    Tag("UP主", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                }
                if (pinned) {
                    Tag("置顶", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            SubLine(comment)

            Spacer(Modifier.height(Spacing.Tight))
            CommentText(
                message = comment.message,
                emotes = comment.emotes,
                style = CommentBodyStyle,
            )

            if (comment.pictureUrls.isNotEmpty()) {
                PictureGrid(
                    urls = comment.pictureUrls,
                    onClick = { index -> viewerIndex = index },
                    modifier = Modifier.padding(top = Spacing.Tight),
                )
            }

            CommentActions(
                comment = comment,
                canDelete = myMid != null && myMid == comment.mid,
                onLike = onLike,
                onDelete = onDelete,
                onReply = onReply,
            )
            SubReplies(comment, expanded, onExpandReplies)
        }
    }

    viewerIndex?.let { index ->
        ImageViewer(
            urls = comment.pictureUrls,
            initialIndex = index,
            onDismiss = { viewerIndex = null },
        )
    }
}

/** "3 小时前 • IP属地:广东"。11sp,和名字同属元信息那一块。 */
@Composable
private fun SubLine(comment: CommentItem) {
    val text = listOf(formatRelativeTime(comment.ctimeEpochSeconds), comment.ipLocation)
        .filter { it.isNotBlank() }
        .joinToString("  •  ")
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 评论正文的字号与行高。**行高是这一屏可读性的主要杠杆**:`bodyMedium` 是 14/22,
 * 而 PiliPlus 的评论正文用的是 `TextStyle(height: 1.75, fontSize: 14)`,也就是 14/24.5。
 * 汉字墨迹几乎占满 em 框,22 的行距在一条五六行的长评论里会糊成一片。这里取 14/24。
 *
 * 不改 `Typography` 里的 `bodyMedium`:那一档还给列表标题、队列条目等等用着,
 * 它们要的是紧凑,不是宽松。行高是按**这段文字有多长**定的,不是按字号定的。
 */
private val CommentBodyStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp)

/** 楼中楼正文比主楼小一档,行高同比例收。 */
private val SubReplyBodyStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(lineHeight = 20.sp)

private val EmoteSize = 20.sp

/** 内联表情的绘制尺寸。占位是按 sp 给的(跟着字号缩放),画的时候要一个 dp。 */
private val EmoteBoxSize = 20.dp
private val GridSpacing = 4.dp

/**
 * 评论配图。张数决定列数,照 PiliPlus 的
 * `common/widgets/image_grid/image_grid_builder.dart`:1 张单独放大,2 与 4 张走两列,
 * 其余走三列;格子是正方形,超出的部分裁掉,**点开才看全**。
 *
 * 单张那格用 4:3 而不是按原图比例,是因为接口层的 `ReplyPictureDto` 只解析了 `img_src`,
 * 没有 `img_width`/`img_height` —— 拿不到原始比例就没法像 PiliPlus 那样按比例定尺寸。
 * 补这两个字段要动 `api/dto`,不在这一轮的边界内。
 */
@Composable
private fun PictureGrid(urls: List<String>, onClick: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (urls.size == 1) {
        BiliAsyncImage(
            url = urls[0],
            contentDescription = "评论配图,点击查看大图",
            modifier = modifier
                .fillMaxWidth(SinglePictureWidthFraction)
                .aspectRatio(4f / 3f)
                .clip(MaterialTheme.shapes.small)
                .clickable { onClick(0) },
        )
        return
    }

    val columns = if (urls.size == 2 || urls.size == 4) 2 else 3
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(GridSpacing)) {
        urls.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GridSpacing), modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { indexInRow, url ->
                    val index = urls.indexOf(url).takeIf { it >= 0 } ?: indexInRow
                    BiliAsyncImage(
                        url = url,
                        contentDescription = "评论配图,点击查看大图",
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onClick(index) },
                    )
                }
                // 最后一行不满时补空位,否则两张图会被拉宽到占满整行。
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val SinglePictureWidthFraction = 0.7f

/**
 * 正文相对屏幕左缘的缩进 = 页边距 16 + 头像 36 + 头像与文字的间距 12。
 * inset 分割线对齐到这里,也就是对齐头像的**后**缘、正文的前缘(M3 divider 页对
 * inset divider 的要求:与锚定元素对齐)。
 */
private val CommentTextInset = Spacing.Comfortable + Dimens.AvatarSmall + Spacing.Cozy

/**
 * 点赞 / 回复 / 删除。三个按钮以前都是 32dp 见方,低于 48dp 的最小触摸目标 ——
 * 在正文旁边一行密排着,误触相邻按钮的概率不低,而"删除"就在里面。
 * 现在统一用默认尺寸的 IconButton(自带 48dp 触摸区),视觉上仍靠 16dp 的图标保持轻。
 */
@Composable
private fun CommentActions(
    comment: CommentItem,
    canDelete: Boolean,
    onLike: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onReply: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onLike(comment.rpid) }) {
            Icon(
                imageVector = if (comment.liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                contentDescription = if (comment.liked) "取消点赞" else "点赞",
                tint = if (comment.liked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(SmallIconSize),
            )
        }
        Text(
            text = "${comment.likeCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onReply) { Text("回复") }
        if (canDelete) {
            IconButton(onClick = { onDelete(comment.rpid) }) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除我的评论",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(SmallIconSize),
                )
            }
        }
    }
}

private val SmallIconSize = 16.dp

@Composable
private fun SubReplies(comment: CommentItem, expanded: ExpandedReplies?, onExpandReplies: (Long) -> Unit) {
    // 已展开就用展开结果(含翻页累加),否则用主楼自带的预览楼层垫着,避免展开前一片空白。
    val shown = expanded?.items ?: comment.previewReplies
    val remaining = comment.subReplyCount - shown.size
    if (shown.isEmpty() && remaining <= 0) return

    // **一个容器装下整组楼中楼,不是每条一张卡片。** 以前每条各套一个 Surface,
    // 三条回复就是三块圆角色块摞在一起,比主楼本身还抢眼。PiliPlus 的 `replyItemRow`
    // 也是一个容器里排若干行(`lib/pages/video/reply/widgets/reply_item_grpc.dart`)。
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.Hair),
    ) {
        Column(modifier = Modifier.padding(vertical = Spacing.Hair)) {
            shown.forEach { sub -> SubReplyRow(sub) }
            when {
                expanded == null && remaining > 0 -> SubReplyMoreButton(
                    text = "展开 $remaining 条回复",
                    onClick = { onExpandReplies(comment.rpid) },
                )

                expanded != null && expanded.loadingMore -> Box(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.Tight),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(SmallIconSize), strokeWidth = 2.dp)
                }

                expanded != null && expanded.hasMore -> SubReplyMoreButton(
                    text = "加载更多",
                    onClick = { onExpandReplies(comment.rpid) },
                )
            }
        }
    }
}

@Composable
private fun SubReplyMoreButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = Spacing.Tight),
        modifier = Modifier.padding(start = Spacing.Hair),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * 楼中楼的一条。名字和正文排在同一段文字流里(`名字:正文`),不像主楼那样分两行 ——
 * 楼中楼一屏可能有五六条,每条再占两行的话主楼就被压没了。PiliPlus 同样是把楼中楼
 * 压成一行起排的紧凑形态。
 *
 * 容器色见外层:M3 把 surface container 这一族定义为"容器填充",`surfaceVariant`
 * 现在主要是给它的 on 色(低强调文字)留位置的,拿它当底在深色主题下会亮出一大截。
 */
@Composable
private fun SubReplyRow(comment: CommentItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        Text(
            text = "${comment.uname}:",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (comment.isUploader) {
            Tag("UP主", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
    CommentText(
        message = comment.message,
        emotes = comment.emotes,
        style = SubReplyBodyStyle,
        maxLines = 5,
        modifier = Modifier.padding(start = Spacing.Tight, end = Spacing.Tight, bottom = Spacing.Tight),
    )
}

/**
 * "UP主""置顶"这类标记。容器色和文字色成对取自同一组 role,不再用 `color.copy(alpha=0.15f)`
 * 兑一个底色 —— 那样兑出来的对比度取决于底下是什么,深色主题里经常糊成一团。
 */
@Composable
private fun Tag(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.extraSmall) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = Spacing.Hair, vertical = 1.dp),
        )
    }
}

@Composable
private fun CommentInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    replyTarget: String?,
    onCancelReply: () -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            if (replyTarget != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.Comfortable, end = Spacing.Hair)
                        .heightIn(min = Dimens.MinTouchTarget),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("回复 @$replyTarget", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCancelReply) { Text("取消") }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.Tight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (replyTarget != null) "回复 $replyTarget" else "发条友善的评论") },
                    maxLines = 4,
                    shape = MaterialTheme.shapes.large,
                )
                FilledIconButton(onClick = onSend, enabled = !sending && text.isNotBlank()) {
                    if (sending) {
                        CircularProgressIndicator(modifier = Modifier.size(Dimens.IconInline), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

/**
 * 正文里要特殊处理的三种东西:表情占位符 `[doge]`、@提及、跳转链接。
 * 一次扫描全认出来 —— 分成两遍就得处理"第二遍的匹配落在第一遍的替换里"这种交叉。
 *
 * 表情键的长度设了上限:`[` 到 `]` 之间不限长的话,一句"[这里省略一万字]看看"会被整段
 * 当成一个表情键去查表(查不到,原样显示,但白扫一遍)。B 站的表情名都很短。
 */
private val RichTokenRegex = Regex("""\[[^\[\]]{1,20}]|@[^\s@]+|https?://\S+""")

/**
 * 评论正文。
 *
 * **表情内联回文字流**,不再是"正文里留着 `[doge]` 三个字、底下另起一行摆一排图标"——
 * 那样读者得自己把图标和占位符对应回去,而且表情出现两次时下面那排根本对不上。
 * 走 `InlineTextContent`:占位符在 [AnnotatedString] 里留一个带 id 的空位,
 * 渲染时把图片填进去,换行、对齐、选中都跟着文字走。
 *
 * @提及与链接标成 [LocalMentionColor]。仍然不解析 `content.members`/`jump_url`
 * (notes §1.4:两者都是未强类型化字段),靠正则认原文——拿不到结构化数据不等于丢内容。
 */
@Composable
private fun CommentText(
    message: String,
    emotes: Map<String, String>,
    style: androidx.compose.ui.text.TextStyle,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    val mention = LocalMentionColor.current
    val used = remember(message, emotes) { linkedMapOf<String, String>() }

    val text = remember(message, emotes, mention) {
        used.clear()
        buildAnnotatedString {
            var last = 0
            for (match in RichTokenRegex.findAll(message)) {
                append(message.substring(last, match.range.first))
                val token = match.value
                val emoteUrl = emotes[token]
                if (emoteUrl != null) {
                    used[token] = emoteUrl
                    appendInlineContent(token, token)
                } else {
                    withStyle(SpanStyle(color = mention)) { append(token) }
                }
                last = match.range.last + 1
            }
            append(message.substring(last))
        }
    }

    val inline = used.mapValues { (_, url) ->
        InlineTextContent(
            Placeholder(EmoteSize, EmoteSize, PlaceholderVerticalAlign.TextCenter),
        ) {
            BiliAsyncImage(url = url, contentDescription = null, modifier = Modifier.size(EmoteBoxSize))
        }
    }

    Text(
        text = text,
        style = style,
        inlineContent = inline,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private val AbsoluteDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun formatRelativeTime(epochSeconds: Long, nowEpochSeconds: Long = Instant.now().epochSecond): String {
    if (epochSeconds <= 0L) return ""
    val diff = nowEpochSeconds - epochSeconds
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60}分钟前"
        diff < 24 * 3600 -> "${diff / 3600}小时前"
        diff < 7 * 24 * 3600 -> "${diff / (24 * 3600)}天前"
        else -> Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(AbsoluteDateFormatter)
    }
}

// ---- Preview ----

// 显式写返回类型:它在 previewReplies 里递归调用自己,不写会让类型推断绕不出来。
private fun previewComment(
    rpid: Long,
    uname: String,
    message: String,
    likes: Int,
    isUp: Boolean = false,
): CommentItem = CommentItem(
    rpid = rpid,
    rootRpid = rpid,
    mid = rpid,
    uname = uname,
    avatarUrl = "",
    isUploader = isUp,
    ipLocation = "IP属地：广东",
    ctimeEpochSeconds = Instant.now().epochSecond - 3600,
    likeCount = likes,
    liked = false,
    message = message,
    emotes = emptyMap(),
    pictureUrls = emptyList(),
    subReplyCount = 2,
    previewReplies = listOf(
        previewComment(rpid * 100 + 1, "路人甲", "楼上说得对 @楼主", 3),
    ),
)

@Preview(showBackground = true, name = "评论区")
@Composable
private fun CommentSectionPreview() {
    BilbyTheme {
        CommentSection(
            state = CommentUiState(
                myMid = 1L,
                topComment = previewComment(1L, "UP主置顶", "感谢支持,下期见 https://b23.tv/xxx", 200, isUp = true),
                items = listOf(
                    previewComment(2L, "热心网友", "这期讲得很清楚,做了笔记", 88),
                    previewComment(3L, "路人乙", "催更催更", 5),
                ),
                hasMore = false,
            ),
            onSort = {},
            onLoadMore = {},
            onExpandReplies = {},
            onSend = { _, _ -> },
            onLike = {},
            onDelete = {},
            modifier = Modifier.height(600.dp),
        )
    }
}
