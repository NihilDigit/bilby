package dev.bilby.ui.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.data.WhisperMessage
import dev.bilby.data.WhisperContent
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/**
 * 一个私信会话。
 *
 * **只发文本。** 图片、表情、分享卡片这几种收到时能读出来是什么(见 MessageContent),
 * 但发不出去 —— 它们各自要一条上传或选择的链路,而这一版要回答的问题只是"能不能说话"。
 *
 * 输入栏与评论区、直播间是同一个形状(风格指南 §2.7)。
 */
@Composable
fun WhisperScreen(
    state: WhisperUiState,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    /** 进对方的空间。头像和名字都走它。 */
    onOpenSpace: () -> Unit,
    /** 点开消息里的视频。私信里最常见的就是 UP 主推过来的投稿。 */
    onOpenVideo: (String) -> Unit,
    onOpenArticle: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            // **顶栏放头像,而且整块可点。** 会话页原先只有一行名字,于是从私信根本走不到
            // 对方的空间 —— 而"这人是谁"往往正是收到一条陌生私信之后的第一个问题。
            // 系统号没有空间可去(见 WhisperSession.isSystem),那时不给点击。
            WhisperTopBar(
                name = state.name,
                faceUrl = state.faceUrl,
                onOpenSpace = if (state.isSystem) null else onOpenSpace,
                onBack = onBack,
            )
        },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.loading && state.messages.isEmpty() -> FullScreenLoading()
                    state.error != null && state.messages.isEmpty() ->
                        FullScreenError(state.error, onRetry)

                    state.messages.isEmpty() -> EmptyState(stringResource(R.string.whisper_empty))
                    else -> MessageList(state, onOpenVideo, onOpenArticle)
                }
            }
            WhisperInput(sending = state.sending, error = state.sendError, onSend = onSend)
        }
    }
}

@Composable
private fun MessageList(
    state: WhisperUiState,
    onOpenVideo: (String) -> Unit,
    onOpenArticle: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    // 新消息在末尾,进来就停在最后一条:聊天从上往下读,但要看的是最新那句。
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.scrollToItem(state.messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(Spacing.Comfortable),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        items(state.messages, key = { it.seqno }) { message ->
            MessageBubble(
                message = message,
                mine = message.senderUid == state.selfMid,
                onOpenVideo = onOpenVideo,
                onOpenArticle = onOpenArticle,
            )
        }
    }
}

/**
 * 一条消息。**自己发的靠右、对方靠左**,这是聊天界面唯一不需要解释的约定。
 *
 * 气泡宽度封顶:铺满整行时左右两方的边界就消失了,而"谁说的"全靠这条边界。
 *
 * **内容按类型画,不是一律折成一行字。** 私信里数量最多的两类是 UP 主推过来的视频和专栏
 * (实测,见 [dev.bilby.data.WhisperContent]),折成标题就点不开了,而点开正是收到它的意义。
 */
@Composable
private fun MessageBubble(
    message: WhisperMessage,
    mine: Boolean,
    onOpenVideo: (String) -> Unit,
    onOpenArticle: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            val content = message.content
            Surface(
                color = if (mine) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .widthIn(max = BubbleMaxWidth)
                    .then(
                        when (content) {
                            is WhisperContent.Video -> Modifier.clickable { onOpenVideo(content.bvid) }
                            is WhisperContent.Article -> Modifier.clickable { onOpenArticle(content.id) }
                            else -> Modifier
                        },
                    ),
            ) {
                val onColor = if (mine) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                when (content) {
                    is WhisperContent.Text -> BubbleText(content.text, onColor)

                    is WhisperContent.Video -> CardBody(
                        coverUrl = content.coverUrl,
                        title = content.title,
                        subtitle = null,
                        color = onColor,
                    )

                    is WhisperContent.Article -> CardBody(
                        coverUrl = null,
                        title = content.title,
                        subtitle = content.summary.takeIf { it.isNotBlank() },
                        color = onColor,
                    )

                    // 系统通知自带标题,标题和正文分两行 —— 它不是某个人说的话,合成一段读不出
                    // 哪句是重点(“B币券到账通知 / 5.00B币券已到账…”)。
                    is WhisperContent.Notice -> Column(
                        modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    ) {
                        if (content.title.isNotBlank()) {
                            Text(
                                text = content.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = onColor,
                            )
                        }
                        Text(
                            text = content.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 提示条在数据层就被滤掉了(见 MessageRepository.messages),这里画不到。
                    // 留一个分支只是为了让 when 穷尽 —— 真走到说明过滤那步漏了。
                    is WhisperContent.Hint -> BubbleText(content.text, MaterialTheme.colorScheme.outline)

                    is WhisperContent.Unsupported -> BubbleText(
                        stringResource(R.string.whisper_unsupported, content.msgType),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = formatRelativeTime(message.timeSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun BubbleText(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
    )
}

/**
 * 卡片式的一条(视频/专栏):封面在左,标题在右。
 *
 * 封面用 16:9 的小图而不是 [dev.bilby.ui.components.VideoRow] 那一份 —— 那个是给列表用的,
 * 宽度按整行算;这里最宽只有一个气泡,照搬会把标题挤成两个字。专栏没有封面,只有标题和摘要。
 */
@Composable
private fun CardBody(coverUrl: String?, title: String, subtitle: String?, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.padding(Spacing.Tight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!coverUrl.isNullOrBlank()) {
            BiliAsyncImage(
                url = coverUrl,
                contentDescription = null,
                modifier = Modifier
                    .width(CardCoverWidth)
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.small),
            )
        }
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 会话页的顶栏:头像 + 名字,整块可点进空间。
 *
 * 不复用 [BilbyTopBar]:那一个的标题槽是一行文字,而这里要的是"这是谁"——头像在这个位置
 * 承担的正是识别,而名字可能是"哔哩哔哩客服"这种一眼扫过去分不清的。
 */
@Composable
private fun WhisperTopBar(name: String, faceUrl: String, onOpenSpace: (() -> Unit)?, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                modifier = Modifier.then(
                    if (onOpenSpace == null) Modifier else Modifier.clickable(onClick = onOpenSpace),
                ),
            ) {
                Avatar(url = faceUrl, size = Dimens.AvatarRow)
                Text(
                    text = name.ifBlank { stringResource(R.string.whisper_system_account) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
    )
}

@Composable
private fun WhisperInput(sending: Boolean, error: String?, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val send = {
        onSend(text)
        text = ""
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.imePadding()) {
        Column {
            error?.let {
                Text(
                    text = stringResource(R.string.whisper_send_failed, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(
                        start = Spacing.Comfortable,
                        end = Spacing.Comfortable,
                        top = Spacing.Tight,
                    ),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(Spacing.Tight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.whisper_input_hint)) },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) send() }),
                    shape = MaterialTheme.shapes.large,
                )
                FilledIconButton(onClick = send, enabled = !sending && text.isNotBlank()) {
                    if (sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Dimens.IconInline),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.action_send),
                        )
                    }
                }
            }
        }
    }
}

/** 气泡里的封面。比列表行那个小一号:这里最宽只有一个气泡。 */
private val CardCoverWidth = 96.dp

/** 气泡不铺满整行:左右两方的边界全靠这段留白。 */
private val BubbleMaxWidth = 280.dp
