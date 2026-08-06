package dev.bilby.ui.comment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.bilby.data.CommentItem
import dev.bilby.data.CommentSort
import dev.bilby.ui.components.Avatar
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Avatar(url = comment.avatarUrl, size = Dimens.AvatarSmall)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                Text(comment.uname, style = MaterialTheme.typography.labelLarge)
                if (comment.isUploader) {
                    Tag("UP主", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                }
                if (pinned) {
                    Tag("置顶", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Text(
                text = "${comment.ipLocation}  ${formatRelativeTime(comment.ctimeEpochSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = highlightMessage(comment.message), style = MaterialTheme.typography.bodyMedium)

            if (comment.emotes.isNotEmpty() || comment.pictureUrls.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = Spacing.Hair),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                ) {
                    comment.emotes.values.forEach { url ->
                        AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(EmoteSize))
                    }
                    comment.pictureUrls.forEach { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = "评论配图",
                            modifier = Modifier.size(PictureSize),
                        )
                    }
                }
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
}

private val EmoteSize = 20.dp
private val PictureSize = 56.dp

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
    if (shown.isEmpty()) return
    Column(
        modifier = Modifier.padding(top = Spacing.Hair),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        shown.forEach { sub -> SubReplyRow(sub) }
        val remaining = comment.subReplyCount - shown.size
        when {
            expanded == null && remaining > 0 ->
                TextButton(onClick = { onExpandReplies(comment.rpid) }) { Text("展开 $remaining 条回复") }

            expanded != null && expanded.loadingMore ->
                CircularProgressIndicator(modifier = Modifier.size(SmallIconSize).padding(Spacing.Hair))

            expanded != null && expanded.hasMore ->
                TextButton(onClick = { onExpandReplies(comment.rpid) }) { Text("加载更多") }
        }
    }
}

/**
 * 楼中楼。底色用 surfaceContainer 而不是 surfaceVariant:M3 把 surface container 这一族
 * 定义为"容器填充",surfaceVariant 现在主要是给它的 on 色(onSurfaceVariant,低强调文字)
 * 留位置的。拿 surfaceVariant 当底,深色主题下会比周围的 surface 亮出一大截。
 */
@Composable
private fun SubReplyRow(comment: CommentItem) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
        // 撑满宽度而不是包内容:楼中楼是竖着摞的一列,每块各按自己的字数收宽会让右边缘
        // 参差不齐,看起来像三块没对齐的碎片而不是一组回复。
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
                Text(comment.uname, style = MaterialTheme.typography.labelMedium)
                if (comment.isUploader) {
                    Tag("UP主", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text(
                text = highlightMessage(comment.message),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
 * 富文本第一版只做轻量高亮:@提及与跳转链接标成 [LocalMentionColor],表情图片单独在下面一行
 * 展示(notes §1.4:content.members/jumpUrl 都是未强类型化字段,不解析,靠正则识别原文即可,
 * 拿不到结构化数据不等于丢内容——原文一个字不少地照原样显示)。
 */
private val AtOrLinkRegex = Regex("""@[^\s@]+|https?://\S+""")

@Composable
private fun highlightMessage(message: String) = buildAnnotatedString {
    val mention = LocalMentionColor.current
    var last = 0
    for (match in AtOrLinkRegex.findAll(message)) {
        append(message.substring(last, match.range.first))
        withStyle(SpanStyle(color = mention)) { append(match.value) }
        last = match.range.last + 1
    }
    append(message.substring(last))
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
