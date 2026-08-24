package dev.bilby.data.model

/**
 * 首页时间序流里的一条投稿。
 *
 * 视频与专栏是同一条流里的两种投稿,合成一个密封类型是因为列表键、已读位置、排除名单三样
 * 都不该关心它是哪一种。首页此前只有视频,列表键就是 bvid、已读位置也存 bvid —— 那个键
 * 指不出一条专栏,是这次把类型抽出来的全部理由。
 *
 * 分流规则在 DynamicFeedStore,不在这里。
 */
sealed interface FeedEntry {
    /** 列表键与已读位置都用它,跨类型唯一。 */
    val id: String
    val upMid: Long
    val upName: String
    val publishedAtEpochSeconds: Long

    /**
     * 投稿视频与合集更新。播放量/弹幕数保留 B 站原样的格式化字符串(如 "1.2万")。
     */
    data class Video(
        val bvid: String,
        val title: String,
        val coverUrl: String,
        val durationText: String,
        override val upName: String,
        override val upMid: Long,
        override val publishedAtEpochSeconds: Long,
        val playCount: String,
        val danmakuCount: String,
    ) : FeedEntry {
        override val id: String get() = bvid
    }

    /**
     * 专栏投稿。
     *
     * **判据是服务端说这段正文后面还有(`opus.summary.has_more`),不是动态类型** ——
     * 见 OpusSummaryDto.hasMore 记下的实测:带着 itemOpusStyle 请求时,一篇几千字的长文
     * 常常是 DYNAMIC_TYPE_DRAW,而一条纯文字动态照样有标题。界面上这个字段就是"阅读全文"
     * 那个入口在不在,图文和短动态没有它。
     */
    data class Article(
        val ref: ArticleRef,
        val title: String,
        /** 服务端截断过的摘要,纯文本。表情在这里只剩方括号占位符,一行卡片不画它们。 */
        val summary: String,
        val coverUrl: String,
        override val upName: String,
        override val upMid: Long,
        override val publishedAtEpochSeconds: Long,
    ) : FeedEntry {
        // 两套编号取的接口不同(notes/article.md 第 0 节),所以键里要带上是哪一套,
        // 否则一篇 cv123 和一篇 opus123 会撞成同一条。
        override val id: String get() = if (ref.isRead) "cv${ref.id}" else "opus${ref.id}"
    }
}
