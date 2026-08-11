package dev.bilby.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilby.R
import dev.bilby.data.model.Article
import dev.bilby.data.model.ArticleBlock
import dev.bilby.data.model.ArticleImage
import dev.bilby.data.model.LinkCardKind
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.ShareLink
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.CoverCornerRadius
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.ImageViewer
import dev.bilby.ui.components.ListCover
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

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
    val context = LocalContext.current
    Scaffold(
        modifier = modifier,
        topBar = {
            BilbyTopBar(
                title = stringResource(R.string.article_title),
                onBack = onBack,
                actions = {
                    // 站内读得到时它仍然有用:图表类专栏在网页上是可横向拖的,我们这里只有一张图。
                    state.article?.webUrl?.let { url ->
                        IconButton(onClick = { ShareLink.openInBrowser(context, url) }) {
                            Icon(
                                Icons.Outlined.OpenInBrowser,
                                contentDescription = stringResource(R.string.article_open_in_browser),
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

            else -> AdaptiveContent(modifier = Modifier.padding(padding)) {
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
    var viewerIndex by remember(article) { mutableStateOf<Int?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.Spacious),
        // 段间距比列表那档松一档:段落之间没有别的分隔物,靠的就是这道空白。12dp 在一页
        // 密排汉字里读不出"这里换了一段"。
        verticalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
    ) {
        item(key = "header") { ArticleHeader(article, onMentionClick) }
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
                onLinkClick = onLinkClick,
                onMentionClick = onMentionClick,
                onImageClick = { url -> viewerIndex = images.indexOf(url).takeIf { it >= 0 } },
            )
        }
        // 收尾。一篇短专栏读完之后下面是一整屏空白,没有它就分不清"读完了"和"还没加载完"。
        item(key = "end") {
            Text(
                text = stringResource(R.string.article_end),
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
}

@Composable
private fun ArticleHeader(article: Article, onMentionClick: (Long) -> Unit) {
    Column(
        // 标题栏与正文之间不画横线:正文里的 `ArticleBlock.Divider` 是作者自己排的分隔线,
        // 页面再添一条同样的线,读者分不出哪条是排版、哪条是内容。断开靠这里的下边距。
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable)
            .padding(bottom = Spacing.Tight),
        verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
    ) {
        if (article.title.isNotBlank()) {
            Text(article.title, style = MaterialTheme.typography.headlineSmall)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier.clickable(
                role = Role.Button,
                enabled = article.authorMid != 0L,
            ) { onMentionClick(article.authorMid) },
        ) {
            Avatar(url = article.authorFaceUrl, size = Dimens.AvatarRow)
            Column {
                Text(article.authorName, style = MaterialTheme.typography.bodyMedium)
                val published = formatRelativeTime(article.publishedAtEpochSeconds)
                if (published.isNotEmpty()) {
                    Text(
                        text = published,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ArticleBlockView(
    block: ArticleBlock,
    onLinkClick: (String) -> Unit,
    onMentionClick: (Long) -> Unit,
    onImageClick: (String) -> Unit,
) {
    val horizontal = Modifier.fillMaxWidth().padding(horizontal = Spacing.Comfortable)
    val body = MaterialTheme.typography.bodyLarge.copy(lineHeight = BodyLineHeight)
    when (block) {
        is ArticleBlock.Paragraph -> if (block.quote) {
            // 引用块靠左侧那道竖条认,不靠缩进:中文段落本来就没有首行缩进的排法,只缩进
            // 一档在一屏全是文字的页面里几乎看不出来。
            // 竖条要和右边那段文字一样高。Row 里拿不到兄弟的高度,所以整行按内容的最小固有
            // 高度定,竖条再 fillMaxHeight 撑上去。
            Row(modifier = horizontal.height(IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(QuoteBarWidth)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                ArticleParagraph(
                    spans = block.spans,
                    style = body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    onLinkClick = onLinkClick,
                    onMentionClick = onMentionClick,
                    modifier = Modifier.padding(start = Spacing.Cozy),
                )
            }
        } else {
            ArticleParagraph(
                spans = block.spans,
                style = body,
                centered = block.centered,
                onLinkClick = onLinkClick,
                onMentionClick = onMentionClick,
                modifier = horizontal,
            )
        }

        is ArticleBlock.Heading -> ArticleParagraph(
            spans = block.spans,
            style = MaterialTheme.typography.titleMedium,
            onLinkClick = onLinkClick,
            onMentionClick = onMentionClick,
            modifier = horizontal.padding(top = Spacing.Tight),
        )

        is ArticleBlock.Images -> ArticleImages(block.images, onImageClick)

        is ArticleBlock.Divider -> if (block.imageUrl != null) {
            BiliAsyncImage(
                url = block.imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            HorizontalDivider(modifier = horizontal)
        }

        is ArticleBlock.BulletList -> Column(
            modifier = horizontal,
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            block.items.forEachIndexed { index, entry ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                    Text(
                        text = if (block.ordered) "${entry.order.takeIf { it > 0 } ?: (index + 1)}." else "•",
                        style = body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ArticleParagraph(
                        spans = entry.spans,
                        style = body,
                        onLinkClick = onLinkClick,
                        onMentionClick = onMentionClick,
                    )
                }
            }
        }

        // 代码块横滚而不是折行:折了之后缩进对不上,一段 shell 命令会被断在参数中间。
        is ArticleBlock.Code -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.small,
            modifier = horizontal,
        ) {
            Column(modifier = Modifier.padding(Spacing.Cozy)) {
                if (block.language.isNotBlank()) {
                    Text(
                        text = block.language,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = block.content,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }

        is ArticleBlock.LinkCard -> ArticleLinkCard(block, onLinkClick)
    }
}

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
                    text = card.title.ifBlank { stringResource(R.string.article_card_gone) },
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
    LinkCardKind.Video -> R.string.article_card_video
    LinkCardKind.Live -> R.string.article_card_live
    LinkCardKind.Article -> R.string.article_card_article
    LinkCardKind.Music -> R.string.article_card_music
    LinkCardKind.Common -> R.string.article_card_common
    LinkCardKind.Gone -> R.string.article_card_gone
}

private val QuoteBarWidth = 4.dp

/**
 * 正文行高。16sp 的 1.75 倍,与评论区那条同一个来源(PiliPlus 的 `height: 1.75`,
 * 见风格指南 §2.7b):汉字墨迹几乎占满 em 框,一篇几十段的长文按基线行高排会读得很吃力。
 * 同样不去改 `Typography.bodyLarge` —— 行高按这段文字有多长定,不按字号定。
 */
private val BodyLineHeight = 28.sp
