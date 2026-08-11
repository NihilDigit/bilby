package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.dto.DynamicAdditionalDto
import dev.bilby.api.dto.DynamicItemDto
import dev.bilby.api.dto.DynamicRichNodeDto
import dev.bilby.api.dto.LiveRcmdContentDto
import dev.bilby.api.dto.LiveRcmdDto
import dev.bilby.api.dto.MajorDto
import dev.bilby.api.dto.ModuleDynamicDto
import dev.bilby.api.toHttpsUrl
import dev.bilby.data.model.ArticleImage
import dev.bilby.data.model.ArticleRef
import dev.bilby.data.model.ArticleSpan
import dev.bilby.data.model.DynamicAdditional
import dev.bilby.data.model.DynamicAuthor
import dev.bilby.data.model.DynamicCard
import dev.bilby.data.model.DynamicContent
import kotlinx.serialization.json.Json

/**
 * 动态类型分发。**取值与分支照抄 PiliPlus**(`lib/pages/dynamics/widgets/module_panel.dart`
 * 与 `dynamic_panel.dart`),对照表见 notes/dynamic-cards.md —— 那里逐条标了源码行号。
 *
 * 纯函数,不碰网络,单测直接喂 DTO(见 DynamicCardMapperTest)。
 */
internal fun DynamicItemDto.toDynamicCard(depth: Int = 0): DynamicCard? {
    val author = modules?.moduleAuthor ?: return null
    val moduleDynamic = modules.moduleDynamic
    val major = moduleDynamic?.major

    // 转发链在 B 站最长两层:被转发的那条若本身也是转发,展示的是它的正文而不是再套一层。
    // 不设上限的话,一条构造出来的自引用数据就能把这里递归到栈溢出。
    val forwarded = if (type == "DYNAMIC_TYPE_FORWARD" && depth < MAX_FORWARD_DEPTH) {
        orig?.toDynamicCard(depth + 1)
    } else {
        null
    }
    val forwardTips = orig?.modules?.moduleDynamic?.major?.none?.tips
        ?.takeIf { type == "DYNAMIC_TYPE_FORWARD" && forwarded == null }

    val content = major.toContent(type, idStr)
    val (text, textIsSummary) = moduleDynamic.toSpans()
    val article = major?.takeIf { type in OPUS_TYPES }?.toArticleRef()

    // 什么都没有的一条不画:既没正文、没内容、也不是一条"源没了"的转发。
    if (text.isEmpty() && content == null && forwarded == null && forwardTips == null) {
        if (type.isNotBlank()) BiliLog.d("动态 $idStr 类型 $type 没有可显示的内容,已丢弃")
        return null
    }

    return DynamicCard(
        id = idStr.ifBlank { "$type-${author.mid}-${author.pubTs}" },
        type = type,
        author = DynamicAuthor(mid = author.mid, name = author.name, faceUrl = author.face.toHttpsUrl()),
        publishedAtEpochSeconds = author.pubTs,
        text = text,
        textIsSummary = textIsSummary,
        content = content,
        article = article,
        additional = moduleDynamic?.additional?.toAdditional(),
        topic = moduleDynamic?.topic?.name?.takeIf { it.isNotBlank() },
        forwarded = forwarded,
        forwardTips = forwardTips,
        tag = modules.moduleTag?.text?.takeIf { it.isNotBlank() },
    )
}

/**
 * `item.type` -> `major` 的哪个子对象。这张对照表是这个文件的全部要点:**同一个 `major`
 * 里可能同时挂着好几个子对象**,认错一个就会把番剧画成投稿视频。
 */
private fun MajorDto?.toContent(type: String, idStr: String): DynamicContent? {
    if (this == null) return null
    // major.type 为 NONE 时内容已失效,优先于 item.type ——常见于转发的原动态被删。
    none?.tips?.takeIf { it.isNotBlank() }?.let { return DynamicContent.Gone(it) }

    return when (type) {
        "DYNAMIC_TYPE_AV" -> archive.toVideo()
        "DYNAMIC_TYPE_UGC_SEASON" -> ugcSeason.toVideo()

        // 番剧/影视/课堂:解析,但不给站内入口(见 DynamicContent.Season 的说明)。
        "DYNAMIC_TYPE_PGC", "DYNAMIC_TYPE_PGC_UNION" -> pgc.toSeason()
        "DYNAMIC_TYPE_COURSES_SEASON" -> courses.toSeason()

        // 图文、专栏、纯文字三种的正文都由 desc/opus 就地渲染(见 toSpans),这里只剩配图。
        // **这三种都不做成"标题 + 摘要 + 缩略图"的跳转卡片**:PiliPlus 的 module_panel.dart:60-64
        // 对它们返回 SizedBox.shrink(),正文与图由 content_panel.dart 直接铺出来。
        // 以前这里靠"有没有 opus.title"判断是不是专栏,而 itemOpusStyle 打开之后纯文字动态
        // 同样带标题(第一行被服务端提成了 title),于是"午安"这种一句话动态被画成了专栏卡片。
        in OPUS_TYPES -> toImages()

        "DYNAMIC_TYPE_LIVE" -> live?.let {
            DynamicContent.Live(
                roomId = it.id,
                title = it.title,
                coverUrl = it.cover.toHttpsUrl(),
                areaName = it.descFirst,
                watchingText = it.descSecond,
                live = it.liveState == LIVE_STATE_ON,
            )
        }

        "DYNAMIC_TYPE_LIVE_RCMD" -> liveRcmd.toLive(idStr)
        "DYNAMIC_TYPE_SUBSCRIPTION_NEW" -> subscriptionNew?.liveRcmd.toLive(idStr)

        // 活动卡片。装扮、剧集点评、普通分享都是这一种(PiliPlus save_panel/view.dart:272)。
        // upower_common 是充电专属的同形卡片,同一个分支收掉。
        "DYNAMIC_TYPE_COMMON_SQUARE", "DYNAMIC_TYPE_COMMON_VERTICAL" -> (common ?: upowerCommon)?.let {
            DynamicContent.Common(
                title = it.titlePrefix + it.title,
                description = it.desc,
                coverUrl = it.cover.toHttpsUrl(),
                webUrl = it.jumpUrl.toHttpsUrl(),
            )
        }

        "DYNAMIC_TYPE_MUSIC" -> music?.let {
            DynamicContent.Music(
                title = it.title,
                coverUrl = it.cover.toHttpsUrl(),
                label = it.label,
                webUrl = it.jumpUrl.toHttpsUrl(),
            )
        }

        "DYNAMIC_TYPE_MEDIALIST" -> medialist?.let {
            DynamicContent.Medialist(
                title = it.title,
                subTitle = it.subTitle,
                coverUrl = it.cover.toHttpsUrl(),
                webUrl = it.jumpUrl.toHttpsUrl(),
            )
        }

        // 失效的动态。上面那句 none 已经收掉大部分,这里兜住 tips 为空的那种。
        "DYNAMIC_TYPE_NONE" -> DynamicContent.Gone(none?.tips.orEmpty())

        // 转发本身没有 major,内容在 orig 里;纯文字动态也走到这儿(它的正文在 desc 上)。
        "DYNAMIC_TYPE_FORWARD" -> null

        else -> {
            // 认不出来的类型不画一个占位卡片,但要留下类型名 —— 不打这行,下一次 B 站加了
            // 新类型时表现只是"有些动态不见了",指不出是哪一种。
            BiliLog.d("未支持的动态类型 $type($idStr)")
            null
        }
    }
}

/**
 * 动态自带的配图。图片既可能在 `draw.items`(旧形状)也可能在 `opus.pics`
 * (`itemOpusStyle` 这个 features 位打开之后),两处收成同一份。
 */
private fun MajorDto.toImages(): DynamicContent? {
    val images = draw?.items.orEmpty().map { ArticleImage(it.src.toHttpsUrl(), it.width, it.height) }
        .ifEmpty { opus?.pics.orEmpty().map { ArticleImage(it.url.toHttpsUrl(), it.width, it.height) } }
        .ifEmpty { article?.covers.orEmpty().map { ArticleImage(it.toHttpsUrl(), 0, 0) } }
        .filter { it.url.isNotEmpty() }
    return images.takeIf { it.isNotEmpty() }?.let { DynamicContent.Images(it) }
}

/**
 * 专栏正文页的入口。**判据是"认不认得出编号",不是 item.type**:B 站把长文和图文都发成
 * opus,同一篇几千字的文章在这里的 type 常常是 `DYNAMIC_TYPE_DRAW` 而不是 `_ARTICLE`
 * (真机上验过),按 type 给入口会让相当一部分长文只剩一段截断的摘要且无处可去。
 *
 * 跳转地址里有 `/opus/<id>` 或 `/read/cv<id>` 就说明这篇有自己的一页;认不出编号的
 * (纯文字动态、只有几张图的动态)本来也没有那一页。
 */
private fun MajorDto.toArticleRef(): ArticleRef? {
    val jumpUrl = (opus?.jumpUrl?.takeIf { it.isNotBlank() } ?: article?.jumpUrl.orEmpty()).toHttpsUrl()
    val identity = articleIdentityOf(jumpUrl)
        ?: article?.id?.takeIf { it > 0 }?.let { it.toString() to true }
        ?: return null
    return ArticleRef(id = identity.first, isRead = identity.second)
}

/**
 * 动态正文。**`desc` 优先于 `opus`,不是反过来** —— 这条顺序照 PiliPlus 的
 * `rich_node_panel.dart:34-60`:有 `desc` 就只读 `desc`,`opus.title` 连看都不看;
 * 只有 `desc` 整个缺失时才退到 opus,那时标题加粗排在摘要前面。
 *
 * 顺序反过来的代价是实测过的:纯文字动态的第一行会被服务端提成 `opus.title`,先读 opus
 * 就等于把"午安"当成了一篇专栏的标题,而真正的正文(带表情节点的那一份)被丢在 `desc` 里
 * 没人读 —— 表情画不出来正是这么来的,`opus.summary.text` 是拼平的纯文本。
 */
private fun ModuleDynamicDto?.toSpans(): DynamicText {
    val desc = this?.desc
    if (desc != null) {
        val spans = desc.richTextNodes.toSpans()
            .ifEmpty { desc.text.takeIf { it.isNotBlank() }?.let { listOf(ArticleSpan.Text(it)) }.orEmpty() }
        // 走到这里的正文是完整的,见 DynamicCard.textIsSummary。
        return DynamicText(spans, isSummary = false)
    }
    val major = this?.major ?: return DynamicText(emptyList(), isSummary = false)
    val opus = major.opus
    // 旧版专栏(major.article)没有富文本节点,只有标题和一段摘要,按同一个形状排。
    val title = (opus?.title ?: major.article?.title)?.takeIf { it.isNotBlank() }
    val body = opus?.summary?.richTextNodes.orEmpty().toSpans()
        .ifEmpty {
            (opus?.summary?.text ?: major.article?.desc).orEmpty()
                .takeIf { it.isNotBlank() }?.let { listOf(ArticleSpan.Text(it)) }.orEmpty()
        }
    val spans = when {
        title == null -> body
        body.isEmpty() -> listOf(ArticleSpan.Text(title, bold = true))
        else -> listOf(ArticleSpan.Text("$title\n", bold = true)) + body
    }
    // 截断与否由服务端说(见 OpusSummaryDto.hasMore)。旧版专栏那条没有这个字段,而它的
    // `article.desc` 按定义就是摘要,所以只要有内容就算截断过。
    val hasMore = opus?.summary?.hasMore ?: (major.article != null && spans.isNotEmpty())
    return DynamicText(spans, isSummary = hasMore)
}

/** [toSpans] 的返回值。两样东西一起算出来,分成两个函数会让同一条分支写两遍。 */
private data class DynamicText(val spans: List<ArticleSpan>, val isSummary: Boolean)

/**
 * 从跳转地址里认出专栏的编号。`/opus/<id>` 是新版,`/read/cv<id>` 是旧版 —— 两套编号取哪条
 * 接口不同(见 notes/article.md 第 0 节),所以要连"是哪一套"一起带出来。
 *
 * @return id 与 isRead;认不出来返回 null。
 */
internal fun articleIdentityOf(url: String): Pair<String, Boolean>? {
    OPUS_PATH.find(url)?.let { return it.groupValues[1] to false }
    READ_PATH.find(url)?.let { return it.groupValues[1] to true }
    return null
}

private fun dev.bilby.api.dto.ArchiveDto?.toVideo(): DynamicContent? {
    val archive = this ?: return null
    val bvid = archive.bvid?.takeIf { it.isNotBlank() } ?: return null
    return DynamicContent.Video(
        bvid = bvid,
        title = archive.title,
        coverUrl = archive.cover.toHttpsUrl(),
        durationText = archive.durationText,
        playCountText = archive.stat?.play.orEmpty(),
        danmakuCountText = archive.stat?.danmaku.orEmpty(),
        badge = archive.badge?.text?.takeIf { it != DEFAULT_BADGE }.orEmpty(),
    )
}

private fun dev.bilby.api.dto.ArchiveDto?.toSeason(): DynamicContent? {
    val archive = this ?: return null
    if (archive.title.isBlank()) return null
    return DynamicContent.Season(
        title = archive.title,
        coverUrl = archive.cover.toHttpsUrl(),
        durationText = archive.durationText,
        webUrl = archive.jumpUrl.toHttpsUrl(),
        badge = archive.badge?.text?.takeIf { it != DEFAULT_BADGE }.orEmpty(),
    )
}

/**
 * 直播推荐卡片。**`content` 是一段 JSON 文本,要再解一次** —— 这是整份动态响应里唯一一处
 * 二次编码,漏掉的表现是一张什么都没有的空卡片,而不是解析报错。
 */
private fun LiveRcmdDto?.toLive(idStr: String): DynamicContent? {
    val raw = this?.content?.takeIf { it.isNotBlank() } ?: return null
    val info = runCatching { LiveRcmdJson.decodeFromString<LiveRcmdContentDto>(raw).livePlayInfo }
        .onFailure { BiliLog.w("动态 $idStr 的 live_rcmd.content 解不开", it) }
        .getOrNull() ?: return null
    if (info.roomId == 0L) return null
    return DynamicContent.Live(
        roomId = info.roomId,
        title = info.title,
        coverUrl = info.cover.toHttpsUrl(),
        areaName = info.areaName,
        watchingText = info.watchedShow?.textSmall.orEmpty(),
        live = info.liveStatus == LIVE_STATE_ON,
    )
}

private fun DynamicAdditionalDto.toAdditional(): DynamicAdditional? = when (type) {
    "ADDITIONAL_TYPE_VOTE" -> vote?.let {
        DynamicAdditional.Vote(
            title = it.title.ifBlank { it.desc },
            joinCount = it.joinNum,
        )
    }

    // state == -1 是这场预约已经取消,PiliPlus 的 additional_panel.dart:77 同样跳过它。
    "ADDITIONAL_TYPE_RESERVE" -> reserve?.takeIf { it.state != RESERVE_CANCELLED }?.let {
        DynamicAdditional.Reserve(
            title = it.title,
            description = listOfNotNull(it.desc1?.text, it.desc2?.text).filter(String::isNotBlank).joinToString("  "),
            // 时刻只在 desc1 那句话里,desc2 是人数(见 ReserveStartTime 的说明)。
            startAtEpochSeconds = it.desc1?.text?.let(::parseReserveStartTime),
            isLive = it.stype == RESERVE_STYPE_LIVE,
            upMid = it.upMid,
            jumpUrl = it.jumpUrl.toHttpsUrl(),
        )
    }

    "ADDITIONAL_TYPE_UGC" -> ugc?.let {
        DynamicAdditional.Video(
            title = it.title,
            coverUrl = it.cover.toHttpsUrl(),
            description = it.descSecond,
            webUrl = it.jumpUrl.toHttpsUrl(),
            durationText = it.duration,
        )
    }

    "ADDITIONAL_TYPE_COMMON" -> common?.let {
        DynamicAdditional.Common(
            title = it.titlePrefix + it.title,
            description = it.desc,
            coverUrl = it.cover.toHttpsUrl(),
            webUrl = it.jumpUrl.toHttpsUrl(),
        )
    }

    // 商品(ADDITIONAL_TYPE_GOODS)与赛事(_MATCH)不画:前者是带货,在这个应用里没有落点;
    // 后者要一整套比分版式,而它只出现在赛事官号的动态里。
    else -> null
}

/**
 * 动态正文的富文本节点 -> 与专栏共用的 [ArticleSpan]。节点类型是同一族
 * `RICH_TEXT_NODE_TYPE_*`,处理规则也一样:有跳转地址才做成链接,没有的退回纯文字。
 */
private fun List<DynamicRichNodeDto>.toSpans(): List<ArticleSpan> = mapNotNull { node ->
    when (node.type) {
        "RICH_TEXT_NODE_TYPE_EMOJI" -> node.emoji?.iconUrl?.takeIf { it.isNotBlank() }?.let {
            ArticleSpan.Emoji(
                url = it.toHttpsUrl(),
                alt = node.origText.ifBlank { node.text },
                scale = node.emoji.size.toFloat().takeIf { size -> size > 0f } ?: 1f,
            )
        }

        "RICH_TEXT_NODE_TYPE_AT" -> node.rid.toLongOrNull()
            ?.let { ArticleSpan.Mention(node.text, it) }
            ?: node.text.takeIf { it.isNotBlank() }?.let { ArticleSpan.Text(it) }

        // 纯文字节点取 origText:`text` 在部分节点上是被服务端折过的展示文案(如把一条长链接
        // 折成"网页链接"),origText 才是作者写下的原话。PiliPlus 的 rich_node_panel.dart:71 同此。
        "RICH_TEXT_NODE_TYPE_TEXT" -> node.origText.ifBlank { node.text }
            .takeIf { it.isNotBlank() }?.let { ArticleSpan.Text(it) }

        else -> when {
            node.jumpUrl.isNotBlank() -> ArticleSpan.Link(
                text = node.text,
                url = node.jumpUrl.toHttpsUrl(),
                showIcon = node.type == "RICH_TEXT_NODE_TYPE_WEB",
            )

            node.text.isNotBlank() -> ArticleSpan.Text(node.text)
            else -> null
        }
    }
}

/** 只用来解 `live_rcmd.content` 那段内嵌 JSON,和全局那份配置无关,所以在这里自带一个。 */
private val LiveRcmdJson = Json { ignoreUnknownKeys = true }

private val OPUS_PATH = Regex("""/opus/(\d+)""")
private val READ_PATH = Regex("""/read/(?:cv)?(\d+)""")

/** PiliPlus 把"投稿视频"这个角标文案当作没有角标(result.dart:1101)。 */
private const val DEFAULT_BADGE = "投稿视频"

private const val LIVE_STATE_ON = 1

/** 正文由 desc/opus 就地渲染的三种(PiliPlus module_panel.dart:60-64 对它们不画卡片)。 */
private val OPUS_TYPES = setOf("DYNAMIC_TYPE_DRAW", "DYNAMIC_TYPE_ARTICLE", "DYNAMIC_TYPE_WORD")

/** `reserve.state`:-1 表示这场预约已被取消。 */
private const val RESERVE_CANCELLED = -1

/** `reserve.stype`:2 是直播预约,1 是视频预约。 */
private const val RESERVE_STYPE_LIVE = 2

/** 转发链最长两层。 */
private const val MAX_FORWARD_DEPTH = 1
