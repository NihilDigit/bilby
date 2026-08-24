package dev.bilby.data.model

/**
 * 一段富文本里的一节。**专栏、动态、评论共用这一份**,由 `ui/components/BiliRichText.kt`
 * 渲染。
 *
 * 三处的来源不一样:专栏和动态的接口直接给结构化节点,映射一遍就是这个列表;评论只给一个
 * 扁平的 `message` 字符串加几张边表,要先扫一遍(见 `data/CommentRichText.kt`)。**差别到
 * 解析为止** —— 再往后,"一段能点的文字长什么样、点了去哪"三处只有一份实现。原先评论区自己
 * 拼 `AnnotatedString`,同一件事在这个仓库里有两套写法,链接颜色、表情尺寸、读屏语义各改各的。
 */
sealed interface RichSpan {

    /** 这一节在纯文本里是什么样。自由复制和无障碍读的都是它拼起来的结果。 */
    val plainText: String

    /**
     * @param colorArgb 作者指定的字色,null 表示没指定。**是否采用由渲染层决定** ——
     *   这些颜色全是照网页白底挑的,深色主题下有相当一部分会掉到看不清,得先过一遍对比度
     *   (见 `ui/components/BiliRichText.kt`)。数据层不做这个判断:它不知道当前主题的底色。
     * @param fontSizeSp 作者指定的字号,null 表示用正文默认字号。
     */
    data class Text(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strikethrough: Boolean = false,
        val colorArgb: Int? = null,
        val fontSizeSp: Float? = null,
    ) : RichSpan {
        override val plainText: String get() = text
    }

    /** 一段能点开的链接。[icon] 见 [RichLinkIcon]。 */
    data class Link(
        val text: String,
        val url: String,
        val icon: RichLinkIcon = RichLinkIcon.None,
    ) : RichSpan {
        override val plainText: String get() = text
    }

    data class Mention(val text: String, val mid: Long) : RichSpan {
        override val plainText: String get() = text
    }

    data class Emoji(val url: String, val alt: String, val scale: Float) : RichSpan {
        /** 复制出去的是占位符原文(`[doge]`),不是图。alt 为空时退回一个通用的词。 */
        override val plainText: String get() = alt.ifBlank { "[表情]" }
    }

    /**
     * 公式。**保留 LaTeX 源码,不渲染成图**:B 站那条 `x/web-frontend/mathjax/tex` 返回的是
     * SVG,要额外引 coil-svg 才画得出来,而专栏里带公式的比例极低。原样显示至少读得懂,
     * 显示不出来的空白读不懂。
     */
    data class Formula(val latex: String) : RichSpan {
        override val plainText: String get() = latex
    }

    /**
     * 评论里的一个时间点,点了跳过去。只有评论有 —— 专栏和动态不属于任何一条视频,
     * 那里的 `12:34` 就是一串数字。
     *
     * [millis] 是解析出来的位置,[text] 是原文(全角冒号原样留着,改成半角就不是他写的那句了)。
     */
    data class Timestamp(val text: String, val millis: Long) : RichSpan {
        override val plainText: String get() = text
    }
}

/**
 * 链接前面画什么记号。
 *
 * 记号存在的理由是**链接和正文同色时读不出它能点**;而画什么取决于点过去是什么东西,
 * 所以这是一个枚举而不是一个布尔。
 */
enum class RichLinkIcon {
    /** 不画。链接自己带标题、读得出是个链接时用这一档。 */
    None,

    /** 站外链接,一个链条字符。与 PiliPlus 在 `RICH_TEXT_NODE_TYPE_WEB` 前面加 U+1F517 一致。 */
    Web,

    /**
     * 站内视频。
     *
     * 用本地矢量而不是接口给的 `prefix_icon`:那张图全站是同一张,拉它等于每条评论多一次
     * 图床请求;更实际的问题是行内占位符要先定死宽高,而那张图的比例接口不给 —— 猜错就是
     * 一张被压扁的图标。
     */
    Video,
}

/** 这一串节拼成的纯文本。自由复制、剪贴板和读屏用的都是它。 */
fun List<RichSpan>.plainText(): String = joinToString("") { it.plainText }
