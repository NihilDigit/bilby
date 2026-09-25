package dev.bilby.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.bilby.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.resources.*
import dev.bilby.data.WhisperContent
import dev.bilby.data.model.plainText
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.BiliRichText
import dev.bilby.ui.components.CoverAspectRatio
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FirstScreenState
import dev.bilby.ui.components.ImageViewer
import dev.bilby.ui.components.LoadingSpinner
import dev.bilby.ui.components.SelectableTextDialog
import dev.bilby.ui.components.ListCover
import dev.bilby.ui.components.PillInputField
import dev.bilby.formatDurationSeconds
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

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
    /** 往上翻到顶,取更早的一段。 */
    onLoadOlder: () -> Unit,
    /** 进对方的空间。头像和名字都走它。 */
    onOpenSpace: () -> Unit,
    /** 点开消息里的视频。私信里最常见的就是 UP 主推过来的投稿。 */
    onOpenVideo: (String) -> Unit,
    onOpenArticle: (String) -> Unit,
    /** 其余卡片与系统通知按钮的去处,交给链接解析,认不得的去浏览器。 */
    onOpenLink: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 从「UP 主推送」进来时为 false,见 `Whisper.upPushes`。 */
    showsComposer: Boolean = true,
) {
    val snackbar = remember { SnackbarHostState() }
    var viewingImage by remember { mutableStateOf<String?>(null) }
    var copyingText by remember { mutableStateOf<String?>(null) }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
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
        // **躲让键盘放在这一层,不放在输入栏上。** 挂在输入栏内层的话只有它自己上移,上面那段
        // 消息列表高度不变、被键盘盖住下半截 —— 打字时看不到自己在回哪一句。整列一起退让之后
        // 列表被压短,最后一条仍然贴在输入栏上方。
        //
        // **先把 Scaffold 已经让出的那份 inset 记为已消费**,再 imePadding:键盘的高度里含着
        // 导航栏那一截,而 padding(insets) 已经让过它一次,不记账的话键盘弹起时输入栏上方
        // 会多出一条导航栏高的空白。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .consumeWindowInsets(insets)
                .imePadding(),
        ) {
            FirstScreenState(
                loading = state.loading,
                error = state.error?.let { stringResource(it) },
                isEmpty = state.messages.isEmpty(),
                onRetry = onRetry,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                skeleton = { ChatSkeleton() },
            ) {
                if (state.messages.isEmpty()) {
                    EmptyState(stringResource(Res.string.whisper_empty), Modifier.fillMaxSize())
                } else {
                    MessageList(
                        state = state,
                        onLoadOlder = onLoadOlder,
                        actions = BubbleActions(
                            onOpenVideo = onOpenVideo,
                            onOpenArticle = onOpenArticle,
                            onOpenLink = onOpenLink,
                            onViewImage = { viewingImage = it },
                            onCopyText = { copyingText = it },
                        ),
                    )
                }
            }
            if (showsComposer) {
                WhisperInput(
                    sending = state.sending,
                    error = state.sendError,
                    sentCount = state.sentCount,
                    onSend = onSend,
                )
            }
        }
    }
    viewingImage?.let { url ->
        ImageViewer(urls = listOf(url), initialIndex = 0, onDismiss = { viewingImage = null })
    }
    copyingText?.let { text ->
        SelectableTextDialog(text = text, snackbar = snackbar, onDismiss = { copyingText = null })
    }
}

/** 气泡上的几种去处,打包成一份往下传,免得每一层签名里都排一列回调。 */
private class BubbleActions(
    val onOpenVideo: (String) -> Unit,
    val onOpenArticle: (String) -> Unit,
    val onOpenLink: (String) -> Unit,
    val onViewImage: (String) -> Unit,
    /** 长按文字气泡,把原文摊开来复制,做法同评论区(见 SelectableTextDialog)。 */
    val onCopyText: (String) -> Unit,
)

/**
 * 消息列表。**反向布局**:第 0 项是最新的一条,贴着底边。
 *
 * 正向布局要自己维持"贴底",而往上翻出旧消息时新条目插在列表头上,可见的那几条会被整体往下
 * 推一屏,还得手动把滚动位置补回来。反向布局下旧消息接在列表尾部,插入不影响可见区;新消息
 * 插在第 0 项,人停在底部时跟过去即可。原先的写法是每次重拉都整段替换再判断要不要跟随底部,
 * 往上翻着看旧消息时发一条就会丢掉翻出来的那些(见 WhisperViewModel 的类说明)。
 */
@Composable
private fun MessageList(state: WhisperUiState, onLoadOlder: () -> Unit, actions: BubbleActions) {
    val listState = rememberLazyListState()
    val rows = remember(state.messages, state.selfMid) {
        buildChatRows(state.messages, state.selfMid).asReversed()
    }

    // 翻到顶(反向布局里是列表尾部)时取更早的一段。在组合之外观察,理由同 PagedColumn 的触底预取。
    val currentOnLoadOlder by rememberUpdatedState(onLoadOlder)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.let { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount } }
            .distinctUntilChanged()
            .filter { (last, total) -> last != null && last >= total - 1 - OlderPrefetchRows }
            .collect { currentOnLoadOlder() }
    }

    // 最新一条变了(刚发出去的那条补取回来了,或者对方回了):人停在底部附近才跟过去,
    // 往上翻着读旧消息时不把他拽回来。
    val newest = state.messages.lastOrNull()?.seqno
    var seenNewest by remember { mutableStateOf(newest) }
    LaunchedEffect(newest) {
        if (newest != seenNewest && listState.firstVisibleItemIndex <= FollowSlackRows) {
            listState.animateScrollToItem(0)
        }
        seenNewest = newest
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    ) {
        items(rows, key = { it.key }, contentType = { it::class }) { row ->
            when (row) {
                is ChatRow.Time -> TimeSeparator(row.epochSeconds)
                is ChatRow.Caption -> CaptionLine(row)
                is ChatRow.Pushed -> PushedVideoCard(row.push, row.timeSeconds, onClick ={ actions.onOpenVideo(row.push.bvid) })
                is ChatRow.Bubble -> ChatBubble(row, actions)
            }
        }
        if (state.loadingOlder) {
            item(key = "older", contentType = "older") {
                Box(modifier = Modifier.fillMaxWidth().padding(Spacing.Tight), contentAlignment = Alignment.Center) {
                    LoadingSpinner()
                }
            }
        }
    }
}

@Composable
private fun TimeSeparator(epochSeconds: Long) {
    Text(
        text = formatChatTime(epochSeconds),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.Comfortable, bottom = Spacing.Hair),
    )
}

/** 撤回与系统提示:居中一行小字,不属于任何一方。 */
@Composable
private fun CaptionLine(row: ChatRow.Caption) {
    val text = when (val content = row.message.content) {
        WhisperContent.Withdrawn -> stringResource(
            if (row.mine) Res.string.whisper_withdrawn_self else Res.string.whisper_withdrawn_other,
        )

        is WhisperContent.Hint -> content.text
        else -> return
    }
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Loose, vertical = Spacing.Tight),
    )
}

/**
 * UP 主的投稿推送:占满一行的卡片,不是左侧气泡(理由见 [WhisperContent.VideoPush])。
 *
 * **横排,封面在左**,与视频列表的行同一个读法。原先是居中一张竖卡,推送会话里几乎全是这种
 * 卡片,一列居中的大封面读起来既不像聊天也不像列表。
 *
 * 容器与动态卡片同一档(风格指南 §2.7c:surfaceContainer、20dp 圆角、12dp 内边距),封面圆角
 * 按 optical roundness 取 20 − 12 = 8。时长未知(`times` 为 0)时不画角标,不照 PiliPlus
 * 写一个 `--:--`。
 *
 * 推送时间压在右栏底边,与视频行的元信息同一个位置,代替卡片上方那行时间分隔。
 */
@Composable
private fun PushedVideoCard(push: WhisperContent.VideoPush, timeSeconds: Long, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.largeIncreased,
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Tight),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.Cozy).height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        ) {
            ListCover(
                url = push.coverUrl,
                durationText = if (push.durationSeconds > 0) formatDurationSeconds(push.durationSeconds) else "",
                cornerRadius = PushedCoverCorner,
            )
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                Text(
                    text = push.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatChatTime(timeSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 一条消息。**自己发的靠右、对方靠左**,这是聊天界面唯一不需要解释的约定。
 *
 * - 气泡宽度封顶:铺满整行时左右两方的边界就消失了,而"谁说的"全靠这条边界。
 * - 自己的用 primaryContainer,对方的用 surfaceContainerHigh。对方那一侧原先是
 *   surfaceContainer,浅色主题下和页面底色只差一点,气泡的边在真机上几乎看不出来
 *   (同风格指南 §2.3c 对 surfaceContainerLow 的那条)。
 * - 同一个人接连的几句归成一组(见 [ChatRow.Bubble]):组内的间距收窄,发送方那一侧相邻的
 *   角收小。
 *
 * **内容按类型画,不是一律折成一行字。** 私信里数量最多的两类是 UP 主推过来的视频和专栏
 * (实测,见 [dev.bilby.data.WhisperContent]),折成标题就点不开了,而点开正是收到它的意义。
 */
@Composable
private fun ChatBubble(row: ChatRow.Bubble, actions: BubbleActions) {
    val haptics = LocalHapticFeedback.current
    val mine = row.mine
    val content = row.message.content
    val shape = bubbleShape(mine, row.joinsPrevious, row.joinsNext)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (row.joinsPrevious) GroupedGap else Spacing.Tight),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        // 图片不套气泡:图自己就是那块形状,外面再垫一层底色只是多一圈边。
        if (content is WhisperContent.Image) {
            ChatImage(content, shape, onClick = { actions.onViewImage(content.url) })
            return@Row
        }
        val secondary = if (mine) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        val tap: Modifier = when (content) {
            is WhisperContent.Video -> Modifier.clickable { actions.onOpenVideo(content.bvid) }
            is WhisperContent.Article -> Modifier.clickable { actions.onOpenArticle(content.id) }
            is WhisperContent.Link -> Modifier.clickable { actions.onOpenLink(content.url) }
            // 文字气泡只认长按。不用 combinedClickable:它要求一个单击动作,单击时的涟漪之后
            // 什么都不发生,读起来像按坏了。
            is WhisperContent.Text -> {
                val plain = content.spans.plainText()
                Modifier
                    .pointerInput(plain) {
                        detectTapGestures(
                            onLongPress = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                actions.onCopyText(plain)
                            },
                        )
                    }
                    .semantics {
                        onLongClick {
                            actions.onCopyText(plain)
                            true
                        }
                    }
            }

            else -> Modifier
        }
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            shape = shape,
            // 先裁再点:涟漪与长按反馈落在气泡的形状里。
            modifier = Modifier.widthIn(max = BubbleMaxWidth).clip(shape).then(tap),
        ) {
            when (content) {
                is WhisperContent.Text -> BiliRichText(
                    spans = content.spans,
                    style = MaterialTheme.typography.bodyLarge,
                    onLinkClick = actions.onOpenLink,
                    onMentionClick = {},
                    modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
                )

                is WhisperContent.Video -> CardBody(content.coverUrl, content.title, subtitle = null, secondary)

                is WhisperContent.Article -> CardBody(
                    coverUrl = content.coverUrl,
                    title = content.title,
                    subtitle = content.summary.takeIf { it.isNotBlank() },
                    secondary = secondary,
                )

                is WhisperContent.Link -> CardBody(
                    coverUrl = content.coverUrl,
                    title = content.title,
                    subtitle = content.subtitle.takeIf { it.isNotBlank() },
                    secondary = secondary,
                )

                is WhisperContent.Notice -> NoticeBody(content, secondary, actions.onOpenLink)

                is WhisperContent.Unsupported -> Text(
                    text = stringResource(Res.string.whisper_unsupported, content.msgType),
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondary,
                    modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
                )

                // 图片在上面单独画;撤回、提示与投稿推送是居中的行(见 buildChatRows),到不了这里。
                is WhisperContent.Image,
                is WhisperContent.Hint,
                is WhisperContent.VideoPush,
                WhisperContent.Withdrawn,
                -> Unit
            }
        }
    }
}

/**
 * 气泡的形状。四角都是 [BubbleCorner],只有发送方那一侧与同组相邻气泡相接的角收成
 * [BubbleJoint] —— 收的是"接着说"的那一边,读起来是同一段话从上往下接下去。
 * start/end 跟着布局方向走,RTL 下左右自动对调。
 */
private fun bubbleShape(mine: Boolean, joinsPrevious: Boolean, joinsNext: Boolean): Shape {
    val top = if (joinsPrevious) BubbleJoint else BubbleCorner
    val bottom = if (joinsNext) BubbleJoint else BubbleCorner
    return if (mine) {
        RoundedCornerShape(topStart = BubbleCorner, topEnd = top, bottomEnd = bottom, bottomStart = BubbleCorner)
    } else {
        RoundedCornerShape(topStart = top, topEnd = BubbleCorner, bottomEnd = BubbleCorner, bottomStart = bottom)
    }
}

/**
 * 图片与自定义表情。比例取发送方填的宽高,夹在 [MinImageRatio] 与 [MaxImageRatio] 之间,
 * 缺尺寸时按方图:读进来之前就占好位置,换上图时下面的消息不跳。长宽都有上限,一张竖长图
 * 不会占满一整屏。
 */
@Composable
private fun ChatImage(image: WhisperContent.Image, shape: Shape, onClick: () -> Unit) {
    val ratio = if (image.width > 0 && image.height > 0) {
        (image.width.toFloat() / image.height).coerceIn(MinImageRatio, MaxImageRatio)
    } else {
        1f
    }
    val maxSide = if (image.sticker) StickerMaxSide else ImageMaxSide
    BiliAsyncImage(
        url = image.url,
        contentDescription = null,
        // 表情是透明底的图,裁切会切掉它的边;照片裁切是为了让格子和比例严丝合缝。
        contentScale = if (image.sticker) ContentScale.Fit else ContentScale.Crop,
        modifier = Modifier
            .sizeIn(maxWidth = maxSide, maxHeight = maxSide)
            .aspectRatio(ratio)
            .clip(shape)
            .then(if (image.sticker) Modifier else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh))
            .clickable(onClick = onClick),
    )
}

/**
 * 卡片式的一条(视频、专栏、其他分享):封面在上,占满气泡宽,标题与副标题在下。
 *
 * 原先封面在左、标题在右,气泡最宽 280dp,封面只剩 96dp 宽,标题挤在剩下的一百多 dp 里
 * 通常只露出半句。封面比例同列表的 16:10(风格指南 §1.3b),原先写的 16:9 会把上下各裁掉
 * 一条。宽度固定为 [CardWidth]:卡片跟着标题长短变宽窄的话,一串推送读起来参差不齐。
 */
@Composable
private fun CardBody(coverUrl: String?, title: String, subtitle: String?, secondary: Color) {
    Column(modifier = Modifier.width(CardWidth)) {
        if (!coverUrl.isNullOrBlank()) {
            BiliAsyncImage(
                url = coverUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().aspectRatio(CoverAspectRatio),
            )
        }
        Column(
            modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair / 2),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 系统通知(10)。标题和正文分两行 —— 它不是某个人说的话,合成一段读不出哪句是重点
 * (“B币券到账通知 / 5.00B币券已到账…”)。带去处的通知底下给一个文字按钮,字用通知自带的,
 * 没有时用官方前端的说法「查看详情」。
 */
@Composable
private fun NoticeBody(content: WhisperContent.Notice, secondary: Color, onOpenLink: (String) -> Unit) {
    Column(
        modifier = Modifier.padding(start = Spacing.Cozy, end = Spacing.Cozy, top = Spacing.Tight),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        if (content.title.isNotBlank()) {
            Text(text = content.title, style = MaterialTheme.typography.bodyLarge)
        }
        Text(text = content.text, style = MaterialTheme.typography.bodyMedium, color = secondary)
        if (content.jumpUrl.isNotBlank()) {
            TextButton(onClick = { onOpenLink(content.jumpUrl) }) {
                Text(content.jumpText.ifBlank { stringResource(Res.string.whisper_notice_open) })
            }
        } else {
            Spacer(Modifier.height(Spacing.Tight))
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
                    if (onOpenSpace == null) {
                        Modifier
                    } else {
                        Modifier
                            .clip(MaterialTheme.shapes.small)
                            .clickable(onClick = onOpenSpace)
                            .padding(end = Spacing.Tight)
                    },
                ),
            ) {
                Avatar(url = faceUrl, size = Dimens.AvatarRow)
                Text(
                    text = name.ifBlank { stringResource(Res.string.whisper_system_account) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.action_back),
                )
            }
        },
    )
}

/**
 * 会话页底部那条输入栏。
 *
 * **草稿只在发出去之后才清。** 以前是按下发送就清,于是一次失败同时拿走两样东西:刚打的那句话,
 * 以及"它到底发出去了没有"的答案 —— 屏上既没有新气泡也没有输入内容。现在失败原因就在输入框
 * 上方一行,带一个重试,草稿还在框里。
 *
 * 成功与失败在 ViewModel 的协程里分道,界面读不到那个分支,只能读它报的成功计数
 * ([WhisperUiState.sentCount])。**判据是"这个数变大了",不是"和记着的那个不一样"**:进程重建
 * 之后 ViewModel 是新的,计数从 0 起,而 `rememberSaveable` 恢复出来的是重建之前那个数,
 * 按"不一样"判会在回到这一页的第一帧把刚恢复的草稿清掉。
 *
 * 胶囊本身是共用的 [PillInputField],这里只管草稿、失败提示和字数刻度。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WhisperInput(sending: Boolean, error: SendError?, sentCount: Int, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var seenSentCount by rememberSaveable { mutableIntStateOf(sentCount) }
    LaunchedEffect(sentCount) {
        if (sentCount > seenSentCount) text = ""
        seenSentCount = sentCount
    }
    val send = { onSend(text) }

    // 过了刻度才出现,而且不拦输入 —— 私信长度上限同样只有服务端说得准,理由与评论那一侧同
    // (见 `ui/comment/CommentInputParts.kt` 的 commentDraftCounter)。返回 null 而不是一个空的槽位:
    // 那个槽位一存在就占掉一行高度。
    val counter: (@Composable () -> Unit)? = if (text.length < CounterFrom) {
        null
    } else {
        val label = stringResource(Res.string.input_length_counter, text.length, SoftLimit)
        val slot: @Composable () -> Unit = {
            Text(text = label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
        }
        slot
    }

    val canSend = !sending && text.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        // 失败原因摆在输入框上面,不做 toast:人正看着这条输入栏,而失败之后要做的两件事
        // (改一句再发、直接重试)都在这一带。正在发的时候不画上一次的失败。
        if (error != null && !sending) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.Hair),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        Res.string.whisper_send_failed,
                        error.serverMessage ?: stringResource(error.fallback),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(onClick = send) { Text(stringResource(Res.string.action_retry)) }
            }
        }
        // 输入框和发送键装在同一个胶囊里:发送是这个框的动作,不是旁边另一个控件。
        PillInputField(
            value = text,
            onValueChange = { text = it },
            placeholder = stringResource(Res.string.whisper_input_hint),
            canSend = canSend,
            sending = sending,
            onSend = send,
        )
        // 字数刻度过了才出现,在框外右下,不占框里的行。
        counter?.let { slot ->
            ProvideTextStyle(
                MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Box(modifier = Modifier.padding(end = Spacing.Comfortable)) { slot() }
            }
        }
    }
}

/** 私信正文的字数刻度。**不是本地上限**,判决在服务端,理由见 `commentDraftCounter` 的说明。 */
private const val SoftLimit = 500

/** 到这个长度才把计数器画出来。 */
private const val CounterFrom = 400

/** 卡片气泡的固定宽度,见 [CardBody]。封面 16:10 下高 150dp,一屏放得下两张。 */
private val CardWidth = 240.dp


/** 卡片 20dp 圆角减 12dp 内边距。 */
private val PushedCoverCorner = 8.dp

/** 气泡不铺满整行:左右两方的边界全靠这段留白。 */
private val BubbleMaxWidth = 280.dp

/**
 * 气泡四角的圆角。取 largeIncreased 那一档的 20dp,与动态卡片同(风格指南 §2.7c):
 * 一行字的气泡高 40dp,20 正好是半高,单行时两端是整圆。
 */
private val BubbleCorner = 20.dp

/** 同组相邻气泡相接那一角,取刻度上最小的 extraSmall。直角读起来像没画完。 */
private val BubbleJoint = 4.dp

/** 同组气泡之间的间距。组与组之间是 Spacing.Tight,差出来的那一截就是分组。 */
private val GroupedGap = 2.dp

private val ImageMaxSide = 200.dp
private val StickerMaxSide = 120.dp
private const val MinImageRatio = 0.5f
private const val MaxImageRatio = 2f

/** 离顶还剩几行就去取更早的一段。 */
private const val OlderPrefetchRows = 5

/** 新消息到来时,离底部几行以内算"停在底部",跟过去。时间分隔也占行,所以不是 0。 */
private const val FollowSlackRows = 3
