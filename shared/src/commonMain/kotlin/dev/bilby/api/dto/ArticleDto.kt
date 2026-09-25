package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 专栏正文的两条接口。字段路径依据 notes/article.md,来源是 PiliPlus 的
 * `lib/http/dynamics.dart:337-389` 与 `lib/models/dynamics/article_content_model.dart`。
 *
 * 两条接口交回来的正文是**同一种段落数组**:opus 在 `data.item.modules` 里那条
 * `MODULE_TYPE_CONTENT`,read 在 `data.opus.content.paragraphs`。所以段落 DTO 只有一份。
 */
@Serializable
data class OpusDetailDto(
    val item: OpusItemDto? = null,
    /**
     * 这个 opus id 其实是一篇旧版专栏,`fallback.id` 是它的 cv 号,要改走 `x/article/view`。
     * PiliPlus 在 `pages/article/controller.dart:96-101` 也是这么退的。
     */
    val fallback: OpusFallbackDto? = null,
)

@Serializable
data class OpusFallbackDto(val id: String = "")

@Serializable
data class OpusItemDto(
    @SerialName("id_str") val idStr: String = "",
    val basic: OpusBasicDto? = null,
    /**
     * **是数组不是对象**,和动态流的 `modules` 形状不同:每一项自带 `module_type`,
     * 顺序即渲染顺序。照抄 PiliPlus 的 `ItemModulesModel.fromOpusJson`(result.dart:208)。
     */
    val modules: List<OpusModuleDto> = emptyList(),
)

@Serializable
data class OpusBasicDto(
    @SerialName("comment_id_str") val commentIdStr: String = "",
    @SerialName("comment_type") val commentType: Int = 0,
)

@Serializable
data class OpusModuleDto(
    @SerialName("module_type") val moduleType: String = "",
    @SerialName("module_title") val moduleTitle: OpusTitleDto? = null,
    @SerialName("module_author") val moduleAuthor: ModuleAuthorDto? = null,
    @SerialName("module_content") val moduleContent: OpusContentDto? = null,
    /** 正文被风控挡下时才有,里面是一句说明。有它就没有 `module_content`。 */
    @SerialName("module_blocked") val moduleBlocked: OpusBlockedDto? = null,
)

@Serializable
data class OpusTitleDto(val text: String = "")

@Serializable
data class OpusBlockedDto(
    val title: String = "",
    @SerialName("hint_message") val hintMessage: String = "",
)

@Serializable
data class OpusContentDto(val paragraphs: List<ParagraphDto> = emptyList())

/**
 * 旧版专栏 `x/article/view` 的响应。
 *
 * `opus.content.paragraphs` 有值时和新版走同一条渲染路径;只有 `content`(一段 HTML)时
 * 是真正的老数据,PiliPlus 那边另起一套 HTML 渲染器,我们退回浏览器(见 ArticleRepository)。
 */
@Serializable
data class ArticleViewDto(
    val id: Long = 0L,
    val title: String = "",
    val author: ArticleAuthorDto? = null,
    @SerialName("publish_time") val publishTime: Long = 0L,
    @SerialName("origin_image_urls") val originImageUrls: List<String> = emptyList(),
    val content: String = "",
    val opus: ArticleViewOpusDto? = null,
)

@Serializable
data class ArticleViewOpusDto(val content: OpusContentDto? = null)

@Serializable
data class ArticleAuthorDto(
    val mid: Long = 0L,
    val name: String = "",
    val face: String = "",
)

/**
 * 一个段落。`para_type` 决定哪个子对象有值(notes/article.md 第 3 节):
 * 1 正文、2 图片、3 分割线、4 引用、5 列表、6 链接卡片、7 代码、8 标题。
 */
@Serializable
data class ParagraphDto(
    /** 1 = 居中。其余值按左对齐处理。 */
    val align: Int = 0,
    @SerialName("para_type") val paraType: Int = 0,
    val text: RichTextDto? = null,
    val heading: RichTextDto? = null,
    val line: ArticleLineDto? = null,
    val pic: ParagraphPicDto? = null,
    @SerialName("link_card") val linkCard: LinkCardDto? = null,
    val code: ArticleCodeDto? = null,
    val list: ParagraphListDto? = null,
)

@Serializable
data class RichTextDto(val nodes: List<TextNodeDto> = emptyList())

/**
 * 一段文字里的一节。`type` 只有三种走向:`TEXT_NODE_TYPE_RICH` 看 `rich`、
 * `TEXT_NODE_TYPE_FORMULA` 看 `formula`,其余(含 `TEXT_NODE_TYPE_WORD`)看 `word`。
 */
@Serializable
data class TextNodeDto(
    val type: String = "",
    val word: ArticleWordDto? = null,
    val rich: ArticleRichDto? = null,
    val formula: ArticleFormulaDto? = null,
)

@Serializable
data class ArticleWordDto(
    val words: String = "",
    /** 0 表示没给,由 `font_level` 兜底。 */
    @SerialName("font_size") val fontSize: Double = 0.0,
    val style: ArticleTextStyleDto? = null,
    /** `#RRGGBB`。作者自己挑的颜色,是否采用由渲染层按对比度决定(见 ArticleContent)。 */
    val color: String = "",
    @SerialName("font_level") val fontLevel: String = "",
)

@Serializable
data class ArticleTextStyleDto(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strikethrough: Boolean = false,
)

@Serializable
data class ArticleRichDto(
    val type: String = "",
    val text: String = "",
    @SerialName("orig_text") val origText: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
    /** @某人时是对方的 mid,话题时是话题 id。 */
    val rid: String = "",
    val emoji: ArticleEmojiDto? = null,
    val style: ArticleTextStyleDto? = null,
)

/** 三个 url 字段按 webp → gif → icon 取第一个非空,和 PiliPlus 的 `Emoji.fromJson` 一致。 */
@Serializable
data class ArticleEmojiDto(
    @SerialName("webp_url") val webpUrl: String = "",
    @SerialName("gif_url") val gifUrl: String = "",
    @SerialName("icon_url") val iconUrl: String = "",
    /** 相对正文字号的倍数,通常是 1 或 2。 */
    val size: Double = 1.0,
)

@Serializable
data class ArticleFormulaDto(@SerialName("latex_content") val latexContent: String = "")

/** `para_type == 2`。一段可以是一张图,也可以是一组图。 */
@Serializable
data class ParagraphPicDto(val pics: List<ArticlePicDto> = emptyList())

@Serializable
data class ArticlePicDto(
    val url: String = "",
    val width: Double = 0.0,
    val height: Double = 0.0,
)

/** `para_type == 3`。带 `pic` 时是一张装饰性的分割图,不带就是一条普通分割线。 */
@Serializable
data class ArticleLineDto(val pic: ArticlePicDto? = null)

@Serializable
data class ArticleCodeDto(
    val content: String = "",
    /** 形如 `language-kotlin`,也出现过 `language-clike`。 */
    val lang: String = "",
)

@Serializable
data class ParagraphListDto(
    val items: List<ParagraphListItemDto> = emptyList(),
    /** 2 = 有序列表,其余按无序处理。 */
    val style: Int = 0,
)

@Serializable
data class ParagraphListItemDto(
    val level: Int = 0,
    val order: Int = 0,
    val nodes: List<TextNodeDto> = emptyList(),
)

@Serializable
data class LinkCardDto(val card: LinkCardBodyDto? = null)

/**
 * `type` 决定读哪个子对象:`LINK_CARD_TYPE_UGC` / `_COMMON` / `_LIVE` / `_OPUS` / `_MUSIC`
 * 各有各的形状,`_ITEM_NULL` 是内容已失效。商品卡(`_GOODS`)不解析 —— 带货卡片在这个
 * 应用里没有落点,画出来只是一个点不动的广告。
 */
@Serializable
data class LinkCardBodyDto(
    val oid: String = "",
    val type: String = "",
    val ugc: CardUgcDto? = null,
    val common: CardCommonDto? = null,
    val live: CardLiveDto? = null,
    val opus: CardOpusDto? = null,
    val music: CardMusicDto? = null,
    @SerialName("item_null") val itemNull: CardItemNullDto? = null,
)

@Serializable
data class CardUgcDto(
    val title: String = "",
    val cover: String = "",
    @SerialName("desc_second") val descSecond: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
    val duration: String = "",
)

@Serializable
data class CardCommonDto(
    val title: String = "",
    val cover: String = "",
    val desc1: String = "",
    val desc2: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
)

@Serializable
data class CardLiveDto(
    val title: String = "",
    val cover: String = "",
    @SerialName("desc_first") val descFirst: String = "",
    @SerialName("desc_second") val descSecond: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
)

@Serializable
data class CardOpusDto(
    val title: String = "",
    val cover: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
)

@Serializable
data class CardMusicDto(
    val title: String = "",
    val cover: String = "",
    val label: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
)

@Serializable
data class CardItemNullDto(val text: String = "")
