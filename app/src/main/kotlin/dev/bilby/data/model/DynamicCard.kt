package dev.bilby.data.model

/**
 * 一条动态,已经从接口的 `type` + `major` 组合收敛成界面认得的形状。
 *
 * **正文复用 [ArticleSpan]**,不另起一套:动态正文的 `rich_text_nodes` 与专栏正文里的 `rich`
 * 节点是同一族 `RICH_TEXT_NODE_TYPE_*` 取值(@提及、话题、表情、站外链接),两套模型只会让
 * 同一段判断写两遍,而渲染层也已经有一份能画它的组件。
 *
 * @param forwarded 转发时被转发的那一条。它自己也可能有正文和内容,所以是完整的一条动态。
 *   为 null 而 [forwardTips] 非空表示源动态已被删除 —— 这时仍要画出这条,转发者说的话还在。
 */
data class DynamicCard(
    val id: String,
    /**
     * 接口原样的 `DYNAMIC_TYPE_*`。**渲染层不读它**:一条动态是图是字看 [content],是不是转发
     * 看 [forwarded],类型名对读者没有信息量。留着是给日志和映射层用的(`major` 的哪个子对象
     * 由它决定,见 DynamicCardMapper)。
     */
    val type: String,
    val author: DynamicAuthor,
    val publishedAtEpochSeconds: Long,
    val text: List<ArticleSpan> = emptyList(),
    /**
     * [text] 是**摘要**而不是全文,后面还有没读到的内容。
     *
     * 判据是服务端给的 `opus.summary.has_more`(见 [dev.bilby.api.dto.OpusSummaryDto.hasMore]),
     * 不是这段文字有多长,也不是动态类型:一条一千字的文字动态仍然是完整的,而一段五百字的专栏
     * 摘要后面还有正文。绝大多数动态是前者,界面把它们整段铺开,不收也不给出口。
     */
    val textIsSummary: Boolean = false,
    val content: DynamicContent? = null,
    /** 非空表示这条动态背后还有一篇独立正文页,见 [ArticleRef]。 */
    val article: ArticleRef? = null,
    val additional: DynamicAdditional? = null,
    val topic: String? = null,
    val forwarded: DynamicCard? = null,
    val forwardTips: String? = null,
    /** 服务端给的"置顶"之类的标记文案。 */
    val tag: String? = null,
)

data class DynamicAuthor(val mid: Long, val name: String, val faceUrl: String)

/**
 * 进专栏正文页的入口。**它不替代正文** —— 摘要已经在 [DynamicCard.text] 里就地铺开了,
 * 这里只是那篇文章还有自己的一页(完整正文、配图、排版)。
 *
 * 有没有它取决于**跳转地址里认不认得出专栏编号**,不取决于动态类型 —— B 站把长文和图文都发成
 * opus,一篇几千字的文章在动态流里的 type 常常也是"图文"。认不出编号的(纯文字、几张图配一句
 * 话)本来就没有那一页,也就不该有入口:点进去只会看到同样的几行字。
 *
 * @param isRead true = [id] 是 cv 号(旧版专栏),取正文走的接口不同,见 ArticleRepository。
 */
data class ArticleRef(val id: String, val isRead: Boolean)

/** `major` 那一块。哪一种由 `item.type` 决定,见 notes/dynamic-cards.md 第 1 节的对照表。 */
sealed interface DynamicContent {

    /** 投稿视频、合集更新。两者字段结构相同,区别只在合集更新不显示发布人。 */
    data class Video(
        val bvid: String,
        val title: String,
        val coverUrl: String,
        val durationText: String,
        val playCountText: String,
        val danmakuCountText: String,
        val badge: String = "",
    ) : DynamicContent

    /**
     * 番剧、影视、课堂。**站内不播**(DESIGN:非 UGC 内容是 Non-Goal,有版权方),但也不从
     * 时间线里抹掉 —— 关注的人真的更新了一集,时间线上少一条会让人以为漏了。点它跳浏览器,
     * 那是能读到的,而一个点进去发现是空壳的站内入口不是。
     */
    data class Season(
        val title: String,
        val coverUrl: String,
        val durationText: String,
        val webUrl: String,
        val badge: String = "",
    ) : DynamicContent

    /**
     * 动态自带的图。图文、纯文字、专栏三种动态的图都落在这里 —— 它们在接口上是同一份
     * `opus.pics`(旧数据在 `draw.items`),在界面上也是同一件事:跟着正文一起铺开的配图。
     */
    data class Images(val images: List<ArticleImage>) : DynamicContent

    /**
     * 直播。开播动态与直播推荐卡片收敛到同一种 —— 它们在界面上是同一件事(某人正在播),
     * 只是接口给了两种形状。
     *
     * @param live false 表示这条是"当时开过播",现在已经下播。仍然画出来,但不给进房入口。
     */
    data class Live(
        val roomId: Long,
        val title: String,
        val coverUrl: String,
        val areaName: String,
        val watchingText: String,
        val live: Boolean,
    ) : DynamicContent

    data class Music(
        val title: String,
        val coverUrl: String,
        val label: String,
        val webUrl: String,
    ) : DynamicContent

    /** 收藏夹/歌单分享。 */
    data class Medialist(
        val title: String,
        val subTitle: String,
        val coverUrl: String,
        val webUrl: String,
    ) : DynamicContent

    /** 活动卡片。**装扮、剧集点评、普通分享都是这一种**(见 notes/dynamic-cards.md)。 */
    data class Common(
        val title: String,
        val description: String,
        val coverUrl: String,
        val webUrl: String,
    ) : DynamicContent

    /** 内容已失效(动态被删、被设为仅自己可见)。 */
    data class Gone(val tips: String) : DynamicContent
}

/** 挂在正文下面的附加块。 */
sealed interface DynamicAdditional {

    /**
     * 投票。**只显示,不投**:投票要 csrf 写接口,而这条动态流是只读的一页;画一个按得下去
     * 却什么都不发生的按钮比不画更糟。想投的人点进原动态。
     */
    data class Vote(val title: String, val joinCount: Long) : DynamicAdditional

    /**
     * 预约(直播预约、新片预约)。**只显示说明,加一个"加入日历"**。
     *
     * 不做报给 B 站的那个预约动作:它要按 `rid` 加上按钮当前状态回传,而服务端对同一批卡片
     * 给出三种按钮(正常、带 `jump_url` 的外跳、`uncheck.disable=1` 的禁用),于是一列预约里
     * 只有小半数按得动,其余长得一样却没反应——这比不画那颗按钮更难理解。日历是记给自己的,
     * 不依赖那套状态,任何一场只要解得出时刻就记得下。
     *
     * **没有人数字段**:`desc1`/`desc2` 里已经写着"3.8万人预约"。PiliPlus 的
     * `additional_panel.dart:102` 同样只画这两行说明,从不读 `reserve_total`。
     *
     * @param startAtEpochSeconds 开播/发布时刻,从 `desc1` 的文案里解出来(接口没有时间戳字段,
     *   见 `data/ReserveStartTime.kt`)。**已经过去的时刻照样带出来**:界面要用它判断这场
     *   是不是已经开始,"加入日历"该不该给由界面按当前时间现算,而不是在解析时就定死 ——
     *   一页动态可能开着几小时,那时算出的答案会过期。
     * @param isLive 直播预约(`stype == 2`);false 是视频预约。
     * @param upMid 发起这场预约的 UP。直播预约在开播前拿不到房间号,只能按它现查。
     * @param jumpUrl 预约的东西已经存在时服务端给的地址(视频发布后、开播后),否则是空串。
     */
    data class Reserve(
        val title: String,
        val description: String,
        val startAtEpochSeconds: Long?,
        val isLive: Boolean,
        val upMid: Long,
        val jumpUrl: String,
    ) : DynamicAdditional

    /** 动态里附带的一条视频(常见于"我发了个视频"这种转发式投稿)。 */
    data class Video(
        val title: String,
        val coverUrl: String,
        val description: String,
        val webUrl: String,
        val durationText: String,
    ) : DynamicAdditional

    data class Common(val title: String, val description: String, val coverUrl: String, val webUrl: String) :
        DynamicAdditional
}
