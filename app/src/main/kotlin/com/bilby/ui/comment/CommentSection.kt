package com.bilby.ui.comment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.bilby.data.CommentItem
import com.bilby.data.CommentSort
import com.bilby.ui.theme.BilbyTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

private const val PrefetchThreshold = 5

/**
 * 可嵌进播放页的评论区(DESIGN 2.3)。不是整页:自带 LazyColumn 提供滚动,但不假设自己
 * 独占屏幕,调用方通过 [modifier] 给出高度约束(sheet 里用 fillMaxSize,播放页下方用固定
 * height 或 weight)。楼中楼默认只显示 [CommentItem.previewReplies],点「展开」才请求更多。
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
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
            item(key = "sort-bar") {
                SortBar(state.sort, onSort)
            }
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
                CommentFooter(state)
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

@Composable
private fun SortBar(sort: CommentSort, onSort: (CommentSort) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = sort == CommentSort.HOT, onClick = { onSort(CommentSort.HOT) }, label = { Text("最热") })
        FilterChip(selected = sort == CommentSort.TIME, onClick = { onSort(CommentSort.TIME) }, label = { Text("最新") })
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
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(comment.avatarUrl)
                    .httpHeaders(NetworkHeaders.Builder().add("Referer", "https://www.bilibili.com").build())
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp).clip(CircleShape),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(comment.uname, style = MaterialTheme.typography.labelLarge)
                    if (comment.isUploader) Tag("UP主", MaterialTheme.colorScheme.primary)
                    if (pinned) Tag("置顶", MaterialTheme.colorScheme.tertiary)
                }
                Text(
                    "${comment.ipLocation}  ${formatRelativeTime(comment.ctimeEpochSeconds)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(text = highlightMessage(comment.message), style = MaterialTheme.typography.bodyMedium)
                if (comment.emotes.isNotEmpty() || comment.pictureUrls.isNotEmpty()) {
                    Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        comment.emotes.values.forEach { url ->
                            AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        comment.pictureUrls.forEach { url ->
                            AsyncImage(
                                model = url,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onLike(comment.rpid) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (comment.liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                            contentDescription = "点赞",
                            tint = if (comment.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Text("${comment.likeCount}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = onReply) { Text("回复") }
                    if (myMid != null && myMid == comment.mid) {
                        IconButton(onClick = { onDelete(comment.rpid) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除", modifier = Modifier.size(16.dp))
                        }
                    }
                }
                SubReplies(comment, expanded, onExpandReplies)
            }
        }
    }
}

@Composable
private fun SubReplies(comment: CommentItem, expanded: ExpandedReplies?, onExpandReplies: (Long) -> Unit) {
    // 已展开就用展开结果(含翻页累加),否则用主楼自带的预览楼层垫着,避免展开前一片空白。
    val shown = expanded?.items ?: comment.previewReplies
    if (shown.isEmpty()) return
    Column(modifier = Modifier.padding(top = 4.dp)) {
        shown.forEach { sub -> SubReplyRow(sub) }
        val remaining = comment.subReplyCount - shown.size
        if (expanded == null && remaining > 0) {
            TextButton(onClick = { onExpandReplies(comment.rpid) }) { Text("展开 $remaining 条回复") }
        } else if (expanded != null) {
            if (expanded.loadingMore) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
            } else if (expanded.hasMore) {
                TextButton(onClick = { onExpandReplies(comment.rpid) }) { Text("加载更多") }
            }
        }
    }
}

@Composable
private fun SubReplyRow(comment: CommentItem) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(comment.uname, style = MaterialTheme.typography.labelMedium)
                if (comment.isUploader) Tag("UP主", MaterialTheme.colorScheme.primary)
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

@Composable
private fun Tag(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.extraSmall) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun CommentFooter(state: CommentUiState) {
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            state.loading -> CircularProgressIndicator()
            state.appending -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            state.error != null && state.items.isEmpty() -> Text(state.error, color = MaterialTheme.colorScheme.error)
            !state.hasMore && state.items.isNotEmpty() -> Text(
                "没有更多了",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    Column {
        if (replyTarget != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("回复 @$replyTarget", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onCancelReply) { Text("取消") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (replyTarget != null) "回复 $replyTarget" else "发条友善的评论") },
                maxLines = 4,
            )
            IconButton(onClick = onSend, enabled = !sending && text.isNotBlank()) {
                if (sending) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                else Icon(Icons.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

/**
 * 富文本第一版只做轻量高亮:@提及与跳转链接标红/标蓝,表情图片单独在下面一行展示
 * (notes §1.4:content.members/jumpUrl 都是未强类型化字段,不解析,靠正则识别原文即可,
 * 拿不到结构化数据不等于丢内容——原文一个字不少地照原样显示)。
 */
private val AtOrLinkRegex = Regex("""@[^\s@]+|https?://\S+""")

/** B 站站内链接蓝。这里不用主题色:它标的是"这段是可点的",跨主题需要保持同一个语义色。 */
private val MENTION_COLOR = Color(0xFF00A1D6)

private fun highlightMessage(message: String) = buildAnnotatedString {
    var last = 0
    for (match in AtOrLinkRegex.findAll(message)) {
        append(message.substring(last, match.range.first))
        withStyle(SpanStyle(color = MENTION_COLOR)) {
            append(match.value)
        }
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
