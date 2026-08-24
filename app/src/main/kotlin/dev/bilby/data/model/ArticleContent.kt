package dev.bilby.data.model

/**
 * 一篇专栏。正文已经解析成块序列,渲染层只按块画,不再认识接口的 `para_type` 数字。
 *
 * @param blockedMessage 正文被风控挡下时服务端给的说明。非空时 [blocks] 必然为空 ——
 *   这两种情况在界面上要分开:一个是"读不到,原因在这儿",另一个是"这篇是空的"。
 */
data class Article(
    val id: String,
    val title: String,
    val authorName: String,
    val authorFaceUrl: String,
    val authorMid: Long,
    val publishedAtEpochSeconds: Long,
    val blocks: List<ArticleBlock>,
    /** 站内读不了时跳浏览器用的地址。 */
    val webUrl: String,
    val blockedMessage: String? = null,
)

/**
 * 正文里的一块。对应接口的 `para_type`,但**不透传那个数字** —— 数字只在解析那一步有意义,
 * 让渲染层跟着 `when (paraType)` 走会把接口形状焊进视图层。
 */
sealed interface ArticleBlock {

    /** 正文段落。[quote] 为真时是引用块(`para_type == 4`),两者的文字结构完全一样。 */
    data class Paragraph(
        val spans: List<RichSpan>,
        val centered: Boolean = false,
        val quote: Boolean = false,
    ) : ArticleBlock

    data class Heading(val spans: List<RichSpan>) : ArticleBlock

    /** 一段里的图。一张和多张在版式上是两回事,但都是这一块,由渲染层按张数排。 */
    data class Images(val images: List<ArticleImage>) : ArticleBlock

    /**
     * 分割线。[imageUrl] 非空时作者用的是一张装饰图而不是一条线,原样铺出来 ——
     * 换成普通分割线会把作者排的版拆掉一节。
     */
    data class Divider(val imageUrl: String? = null) : ArticleBlock

    /** [ordered] 为真时渲染成 1. 2. 3.,否则是圆点。序号用 [ListEntry.order],不用下标。 */
    data class BulletList(val items: List<ListEntry>, val ordered: Boolean) : ArticleBlock

    data class Code(val content: String, val language: String) : ArticleBlock

    /**
     * 引用卡片:文中插的视频、直播、专栏、音频或活动。
     *
     * @param destinationUrl 点进去要打开的地址。为空表示这张卡片指向的内容已经失效
     *   (`LINK_CARD_TYPE_ITEM_NULL`),这时只画不可点。
     */
    data class LinkCard(
        val title: String,
        val description: String,
        val coverUrl: String,
        val destinationUrl: String,
        /** 卡片左上角的类别文案,如"视频""直播"。 */
        val kind: LinkCardKind,
    ) : ArticleBlock
}

enum class LinkCardKind { Video, Live, Article, Music, Common, Gone }

data class ListEntry(val order: Int, val spans: List<RichSpan>)

data class ArticleImage(val url: String, val width: Int, val height: Int) {
    /**
     * 长图。竖得超过这个比例时不按原比例整张铺开 —— 一张 1:8 的长图会把正文顶出去一屏多,
     * 读者得空滚半天才看到下一段。判据取 PiliPlus 的 `Style.imgMaxRatio`。
     */
    val isLongImage: Boolean get() = width > 0 && height.toFloat() / width > LONG_IMAGE_RATIO

    private companion object {
        const val LONG_IMAGE_RATIO = 2.5f
    }
}
