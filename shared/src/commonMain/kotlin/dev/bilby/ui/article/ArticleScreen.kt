package dev.bilby.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilby.data.model.Article
import dev.bilby.data.model.ArticleBlock
import dev.bilby.data.model.ArticleImage
import dev.bilby.data.model.LinkCardKind
import dev.bilby.data.model.plainText
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.LocalSystemActions
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BiliRichText
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.ContextMenuBox
import dev.bilby.ui.components.CoverCornerRadius
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.ImageViewer
import dev.bilby.ui.components.ListCover
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.SelectableTextDialog
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.components.rememberCopyAction
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular

/**
 * 专栏阅读页。
 *
 * **这一页的标题是内容,不是路牌**,所以标题排在正文里而不是顶栏上 —— 顶栏那一格按风格
 * 指南 §2.5 固定写"专栏",专栏标题动辄二十几个字,放上去只会被截成半句。
 *
 * 正文用 `LazyColumn` 而不是 `Column + verticalScroll`:一篇长专栏有上百段、几十张图,
 * 全部一次性组合会在进页面那一下卡住,而图片全都活着还会把内存吃满。
 */
@Composable
fun ArticleScreen(
    state: ArticleUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onLinkClick: (String) -> Unit,
    onMentionClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val system = LocalSystemActions.current
    // pinned 而不是 enterAlways:一篇专栏是一口气读下去的,顶栏往上滑走再滑回来,视线要在
    // 正文和一条时隐时现的横条之间分心。滚起来只换一档容器色,正文和顶栏之间才有边界。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(
                title = stringResource(Res.string.article_title),
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    // 站内读得到时它仍然有用:图表类专栏在网页上是可横向拖的,我们这里只有一张图。
                    state.article?.webUrl?.let { url ->
                        IconButton(onClick = { system.openInBrowser(url) }) {
                            Icon(
                                Icons.Outlined.OpenInBrowser,
                                contentDescription = stringResource(Res.string.article_open_in_browser),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val article = state.article
        when {
            state.loading && article == null -> FullScreenLoading(Modifier.padding(padding))
            article == null -> FullScreenError(
                state.error.orEmpty(),
                onRetry,
                Modifier.padding(padding),
            )

            else -> AdaptiveContent(modifier = Modifier.padScaffoldExceptBottom(padding)) {
                ArticleBody(article, onLinkClick, onMentionClick)
            }
        }
    }
}

@Composable
private fun ArticleBody(
    article: Article,
    onLinkClick: (String) -> Unit,
    onMentionClick: (Long) -> Unit,
) {
    // 全篇的图收成一张名单,点开谁都能左右翻到相邻的图 —— 一段一个 viewer 的话,翻到段尾
    // 就停住了,而读者的预期是"这篇文章里的图"。
    val images = remember(article) {
        article.blocks.filterIsInstance<ArticleBlock.Images>().flatMap { it.images }.map { it.url }
    }
    val fullText = remember(article) { article.plainFullText() }
    val isTouchLike = LocalPointerSource.current.isTouchLike
    var viewerIndex by remember(article) { mutableStateOf<Int?>(null) }
    var selectionTarget by remember(article) { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.Spacious + navigationBarsBottom()),
        // 段间距比列表那档松一档:段落之间没有别的分隔物,靠的就是这道空白。12dp 在一页
        // 密排汉字里读不出"这里换了一段"。
        verticalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
    ) {
        item(key = "header") {
            ArticleHeader(
                article = article,
                fullText = fullText,
                isTouchLike = isTouchLike,
                onMentionClick = onMentionClick,
            )
        }
        article.blockedMessage?.let { message ->
            item(key = "blocked") {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Comfortable),
                )
            }
        }
        // 不给 items 指定 key:段落没有自己的 id,而同一篇里内容完全相同的段落(用来分隔的
        // 空行、重复出现的图)很常见,拿内容当 key 会让 LazyColumn 抛 key 重复。
        items(article.blocks) { block ->
            ArticleBlockView(
                block = block,
                isTouchLike = isTouchLike,
                onLinkClick = onLinkClick,
                onMentionClick = onMentionClick,
                onImageClick = { url -> viewerIndex = images.indexOf(url).takeIf { it >= 0 } },
                onSelectText = { selectionTarget = it },
            )
        }
        // 收尾。一篇短专栏读完之后下面是一整屏空白,没有它就分不清"读完了"和"还没加载完"。
        item(key = "end") {
            Text(
                text = stringResource(Res.string.article_end),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.Loose),
            )
        }
    }

    viewerIndex?.let { index ->
        ImageViewer(urls = images, initialIndex = index, onDismiss = { viewerIndex = null })
    }
    selectionTarget?.let { target ->
        SelectableTextDialog(
            text = target,
            onDismiss = { selectionTarget = null },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArticleHeader(
    article: Article,
    fullText: String,
    isTouchLike: Boolean,
    onMentionClick: (Long) -> Unit,
) {
    val copy = rememberCopyAction()
    val copyTitleLabel = stringResource(Res.string.action_copy_title)
    val copyUpNameLabel = stringResource(Res.string.action_copy_up_name)
    val copyUidLabel = stringResource(Res.string.action_copy_uid)
    val copyArticleIdLabel = stringResource(Res.string.action_copy_article_id)
    val copyFullTextLabel = stringResource(Res.string.action_copy_full_text)
    val menuLabel = stringResource(Res.string.menu_item_actions)
    val articleIdValue = remember(article.id) {
        article.id.takeIf { it.isNotBlank() }?.let { raw ->
            if (raw.startsWith("cv", ignoreCase = true)) raw else "cv$raw"
        }.orEmpty()
    }

    val menuActions = buildList {
        if (article.title.isNotBlank()) {
            add(ArticleMenuAction(copyTitleLabel, Icons.Outlined.ContentCopy) { copy(article.title) })
        }
        if (article.authorName.isNotBlank()) {
            add(ArticleMenuAction(copyUpNameLabel, Icons.Outlined.ContentCopy) { copy(article.authorName) })
        }
        if (article.authorMid != 0L) {
            add(ArticleMenuAction(copyUidLabel, Icons.Outlined.ContentCopy) { copy(article.authorMid.toString()) })
        }
        if (articleIdValue.isNotBlank()) {
            add(ArticleMenuAction(copyArticleIdLabel, Icons.Outlined.ContentCopy) { copy(articleIdValue) })
        }
        if (fullText.isNotBlank()) {
            add(ArticleMenuAction(copyFullTextLabel, Icons.Outlined.ContentCopy) { copy(fullText) })
        }
    }

    ContextMenuBox(
        onClick = null,
        menuLabel = menuLabel,
        modifier = Modifier.fillMaxWidth(),
        menu = { close ->
            menuActions.forEachIndexed { index, action ->
                DropdownMenuItem(
                    onClick = {
                        close()
                        action.onClick()
                    },
                    text = { Text(action.label) },
                    leadingIcon = { Icon(action.icon, contentDescription = null) },
                    shape = menuItemShape(index, menuActions.size),
                )
            }
        },
    ) { openMenu ->
        val headerContent: @Composable () -> Unit = {
            Column(
                // 标题栏与正文之间不画横线:正文里的 `ArticleBlock.Divider` 是作者自己排的分隔线,
                // 页面再添一条同样的线,读者分不出哪条是排版、哪条是内容。断开靠这里的下边距。
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isTouchLike && menuActions.isNotEmpty()) {
                            Modifier
                                .pointerInput(openMenu) {
                                    detectTapGestures(onLongPress = { openMenu() })
                                }
                                .semantics {
                                    onLongClick(label = menuLabel) {
                                        openMenu()
                                        true
                                    }
                                }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = Spacing.Comfortable)
                    .padding(bottom = Spacing.Tight),
                verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            ) {
                if (article.title.isNotBlank()) {
                    Text(article.title, style = MaterialTheme.typography.headlineMediumEmphasized)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    // 头像 36dp,这一行本来就只有那么高,够不到 48dp 的触摸下限。撑的是点击区,
                    // 头像尺寸和两行字都没动(风格指南 §3 第一条)。
                    modifier = Modifier
                        .heightIn(min = Dimens.MinTouchTarget)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(
                            role = Role.Button,
                            enabled = article.authorMid != 0L,
                        ) { onMentionClick(article.authorMid) }
                        .padding(end = Spacing.Tight),
                ) {
                    Avatar(url = article.authorFaceUrl, size = Dimens.AvatarRow)
                    Column {
                        Text(article.authorName, style = MaterialTheme.typography.bodyMediumEmphasized)
                        val published = formatRelativeTime(article.publishedAtEpochSeconds)
                        if (published.isNotEmpty()) {
                            Text(
                                text = published,
                                style = MaterialTheme.typography.bodySmall.tabular,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (!isTouchLike) {
            SelectionContainer { headerContent() }
        } else {
            headerContent()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArticleBlockView(
    block: ArticleBlock,
    isTouchLike: Boolean,
    onLinkClick: (String) -> Unit,
    onMentionClick: (Long) -> Unit,
    onImageClick: (String) -> Unit,
    onSelectText: (String) -> Unit,
) {
    val horizontal = Modifier.fillMaxWidth().padding(horizontal = Spacing.Comfortable)
    val body = MaterialTheme.typography.bodyLarge.copy(lineHeight = BodyLineHeight)
    when (block) {
        is ArticleBlock.Paragraph -> {
            val paragraphText = remember(block.spans) { block.spans.plainText().trim() }
            CopyableArticleTextBlock(
                plainText = paragraphText,
                isTouchLike = isTouchLike,
                onSelectText = onSelectText,
                modifier = horizontal,
            ) {
                if (block.quote) {
                    // 引用块靠容器认,不靠缩进,也不靠左侧竖条:中文段落本来就没有首行缩进的排法,只缩进
                    // 一档几乎看不出来;竖条是一根线,风格指南 §2.3c 不再用线分区。
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        BiliRichText(
                            spans = block.spans,
                            style = body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            onLinkClick = onLinkClick,
                            onMentionClick = onMentionClick,
                            modifier = Modifier.padding(Spacing.Cozy),
                        )
                    }
                } else {
                    BiliRichText(
                        spans = block.spans,
                        style = body,
                        centered = block.centered,
                        onLinkClick = onLinkClick,
                        onMentionClick = onMentionClick,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        is ArticleBlock.Heading -> {
            val headingText = remember(block.spans) { block.spans.plainText().trim() }
            CopyableArticleTextBlock(
                plainText = headingText,
                isTouchLike = isTouchLike,
                onSelectText = onSelectText,
                modifier = horizontal.padding(top = Spacing.Tight),
            ) {
                BiliRichText(
                    spans = block.spans,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    onLinkClick = onLinkClick,
                    onMentionClick = onMentionClick,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is ArticleBlock.Images -> ArticleImages(block.images, onImageClick)

        is ArticleBlock.Divider -> if (block.imageUrl != null) {
            BiliAsyncImage(
                url = block.imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            // 作者排的「这里断开」照样断开,只是不画成线:一段比段间距大得多的空白。
            Spacer(Modifier.height(ArticleBreakHeight))
        }

        is ArticleBlock.BulletList -> {
            val listText = remember(block) { block.plainBlockText() }
            val bulletTopOffset = with(LocalDensity.current) {
                ((BodyLineHeight.toDp() - BulletDotSize) / 2).coerceAtLeast(0.dp)
            }
            CopyableArticleTextBlock(
                plainText = listText,
                isTouchLike = isTouchLike,
                onSelectText = onSelectText,
                modifier = horizontal,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                ) {
                    block.items.forEachIndexed { index, entry ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                            if (block.ordered) {
                                Text(
                                    text = "${entry.order.takeIf { it > 0 } ?: (index + 1)}.",
                                    style = body.tabular,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .padding(top = bulletTopOffset)
                                        .size(BulletDotSize)
                                        .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                                )
                            }
                            BiliRichText(
                                spans = entry.spans,
                                style = body,
                                onLinkClick = onLinkClick,
                                onMentionClick = onMentionClick,
                            )
                        }
                    }
                }
            }
        }

        // 代码块横滚而不是折行:折了之后缩进对不上,一段 shell 命令会被断在参数中间。
        is ArticleBlock.Code -> ArticleCodeBlock(
            block = block,
            isTouchLike = isTouchLike,
            onSelectText = onSelectText,
            modifier = horizontal,
        )

        is ArticleBlock.LinkCard -> ArticleLinkCard(block, onLinkClick)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CopyableArticleTextBlock(
    plainText: String,
    isTouchLike: Boolean,
    onSelectText: (String) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (plainText.isBlank()) {
        Box(modifier = modifier) { content() }
        return
    }
    val copy = rememberCopyAction()
    val copyParagraphLabel = stringResource(Res.string.action_copy_paragraph)
    val selectTextLabel = stringResource(Res.string.action_select_text)
    val menuLabel = stringResource(Res.string.menu_item_actions)

    ContextMenuBox(
        onClick = null,
        menuLabel = menuLabel,
        modifier = modifier,
        menu = { close ->
            DropdownMenuItem(
                onClick = {
                    close()
                    copy(plainText)
                },
                text = { Text(copyParagraphLabel) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                shape = menuItemShape(0, 2),
            )
            DropdownMenuItem(
                onClick = {
                    close()
                    onSelectText(plainText)
                },
                text = { Text(selectTextLabel) },
                leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                shape = menuItemShape(1, 2),
            )
        },
    ) { openMenu ->
        if (!isTouchLike) {
            SelectionContainer {
                content()
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(openMenu) {
                        detectTapGestures(onLongPress = { openMenu() })
                    }
                    .semantics {
                        onLongClick(label = menuLabel) {
                            openMenu()
                            true
                        }
                    },
            ) {
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArticleCodeBlock(
    block: ArticleBlock.Code,
    isTouchLike: Boolean,
    onSelectText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = rememberCopyAction()
    val copyCodeLabel = stringResource(Res.string.action_copy_code)
    val selectTextLabel = stringResource(Res.string.action_select_text)
    val menuLabel = stringResource(Res.string.menu_item_actions)

    ContextMenuBox(
        onClick = null,
        menuLabel = menuLabel,
        modifier = modifier,
        menu = { close ->
            if (block.content.isNotBlank()) {
                DropdownMenuItem(
                    onClick = {
                        close()
                        copy(block.content)
                    },
                    text = { Text(copyCodeLabel) },
                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                    shape = menuItemShape(0, 2),
                )
                DropdownMenuItem(
                    onClick = {
                        close()
                        onSelectText(block.content)
                    },
                    text = { Text(selectTextLabel) },
                    leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                    shape = menuItemShape(1, 2),
                )
            }
        },
    ) { openMenu ->
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isTouchLike && block.content.isNotBlank()) {
                        Modifier
                            .pointerInput(openMenu) {
                                detectTapGestures(onLongPress = { openMenu() })
                            }
                            .semantics {
                                onLongClick(label = menuLabel) {
                                    openMenu()
                                    true
                                }
                            }
                    } else {
                        Modifier
                    },
                ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(start = Spacing.Cozy, end = Spacing.Hair),
                ) {
                    Text(
                        text = block.language,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    BilbyTooltip(text = copyCodeLabel) {
                        IconButton(
                            onClick = { copy(block.content) },
                            enabled = block.content.isNotBlank(),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = copyCodeLabel,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(Dimens.IconInline),
                            )
                        }
                    }
                }
                SelectionContainer {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(Spacing.Cozy),
                    ) {
                        Text(
                            text = block.content,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }
    }
}

private data class ArticleMenuAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

private val BulletDotSize = 5.dp

private fun ArticleBlock.BulletList.plainBlockText(): String =
    items.mapIndexed { index, entry ->
        val prefix = if (ordered) "${entry.order.takeIf { it > 0 } ?: (index + 1)}. " else "- "
        prefix + entry.spans.plainText().trim()
    }.filter { it.isNotBlank() }.joinToString("\n")

private fun Article.plainFullText(): String = buildList {
    title.trim().takeIf { it.isNotEmpty() }?.let(::add)
    blocks.forEach { block ->
        val piece = when (block) {
            is ArticleBlock.Heading -> block.spans.plainText().trim()
            is ArticleBlock.Paragraph -> block.spans.plainText().trim()
            is ArticleBlock.BulletList -> block.plainBlockText()
            is ArticleBlock.Code -> block.content.trimEnd()
            is ArticleBlock.Images,
            is ArticleBlock.Divider,
            is ArticleBlock.LinkCard,
            -> ""
        }
        if (piece.isNotBlank()) add(piece)
    }
}.joinToString("\n\n")

/**
 * 正文里的图。**一张一行,整宽,按原图比例**,几张都一样。
 *
 * 这里和动态里的九宫格是两回事,不要统一:动态的配图是一组快照,一眼扫过去就行;专栏正文的
 * 图是被读的内容(截图、图表、分镜),而且顺序是作者排的。三张插图压成一行方格之后每张都
 * 看不清,读者还得逐张点开 —— 原文本来就是顺序整宽大图。等分方格还会把非正方形的图裁掉
 * 两边,截图裁掉的正好是行首行尾。
 */
@Composable
private fun ArticleImages(images: List<ArticleImage>, onImageClick: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Comfortable),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        images.forEach { image ->
            // 长图按 16:10 裁一个可点的引子,点开在看图器里完整看。整幅铺开的话,一张 1:8 的
            // 长图要滚半天才见到下一段。
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
                    .clickable(role = Role.Button) { onImageClick(image.url) },
            )
        }
    }
}

@Composable
private fun ArticleLinkCard(card: ArticleBlock.LinkCard, onLinkClick: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable)
            .clickable(
                role = Role.Button,
                enabled = card.destinationUrl.isNotEmpty(),
            ) { onLinkClick(card.destinationUrl) },
    ) {
        Row(
            modifier = Modifier.padding(Spacing.Tight),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (card.coverUrl.isNotEmpty()) {
                ListCover(url = card.coverUrl, width = Dimens.CompactCoverWidth)
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
                Text(
                    text = stringResource(card.kind.label()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = card.title.ifBlank { stringResource(Res.string.article_card_gone) },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (card.description.isNotBlank()) {
                    Text(
                        text = card.description,
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

private fun LinkCardKind.label() = when (this) {
    LinkCardKind.Video -> Res.string.article_card_video
    LinkCardKind.Live -> Res.string.article_card_live
    LinkCardKind.Article -> Res.string.article_card_article
    LinkCardKind.Music -> Res.string.article_card_music
    LinkCardKind.Common -> Res.string.article_card_common
    LinkCardKind.Gone -> Res.string.article_card_gone
}

/** 作者排的分隔符。块间距之外再空这么高,约两行正文。 */
private val ArticleBreakHeight = 32.dp

/**
 * 正文行高。16sp 的 1.75 倍,与评论区那条同一个来源(PiliPlus 的 `height: 1.75`,
 * 见风格指南 §2.7b):汉字墨迹几乎占满 em 框,一篇几十段的长文按基线行高排会读得很吃力。
 * 同样不去改 `Typography.bodyLarge` —— 行高按这段文字有多长定,不按字号定。
 */
private val BodyLineHeight = 28.sp
