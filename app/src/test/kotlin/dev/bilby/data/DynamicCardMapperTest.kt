package dev.bilby.data

import dev.bilby.api.dto.AdditionalDescDto
import dev.bilby.api.dto.AdditionalReserveDto
import dev.bilby.api.dto.AdditionalVoteDto
import dev.bilby.api.dto.ArchiveDto
import dev.bilby.api.dto.ArchiveStatDto
import dev.bilby.api.dto.ArticleDto
import dev.bilby.api.dto.BadgeDto
import dev.bilby.api.dto.CommonCardDto
import dev.bilby.api.dto.DrawDto
import dev.bilby.api.dto.DrawItemDto
import dev.bilby.api.dto.DynamicAdditionalDto
import dev.bilby.api.dto.DynamicBasicDto
import dev.bilby.api.dto.DynamicDescDto
import dev.bilby.api.dto.DynamicEmojiDto
import dev.bilby.api.dto.DynamicItemDto
import dev.bilby.api.dto.DynamicLiveDto
import dev.bilby.api.dto.DynamicModulesDto
import dev.bilby.api.dto.DynamicNoneDto
import dev.bilby.api.dto.DynamicRichNodeDto
import dev.bilby.api.dto.LiveRcmdDto
import dev.bilby.api.dto.MajorDto
import dev.bilby.api.dto.MedialistDto
import dev.bilby.api.dto.ModuleAuthorDto
import dev.bilby.api.dto.DynamicStatDto
import dev.bilby.api.dto.ModuleDynamicDto
import dev.bilby.api.dto.ModuleStatDto
import dev.bilby.api.dto.DynamicMusicDto
import dev.bilby.api.dto.OpusDto
import dev.bilby.api.dto.OpusSummaryDto
import dev.bilby.api.dto.ReserveButtonDto
import dev.bilby.api.dto.ReserveButtonTextDto
import dev.bilby.data.model.ArticleRef
import dev.bilby.data.model.ArticleSpan
import dev.bilby.data.model.DynamicAdditional
import dev.bilby.data.model.DynamicContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态类型分发。测的是 **`item.type` -> `major` 子对象** 这张对照表本身:认错一个,界面上的
 * 表现是"某种动态整类不见了"或者"番剧被画成了投稿视频",两种都指不出原因。
 *
 * 另外三处单独立了用例,都是踩过的:`live_rcmd.content` 的二次 JSON、转发链的深度上限、
 * 以及正文该从 desc 还是 opus 取(取反了就是"午安"被画成一篇专栏)。
 */
class DynamicCardMapperTest {

    private fun item(
        type: String,
        major: MajorDto? = null,
        desc: String? = null,
        nodes: List<DynamicRichNodeDto>? = null,
        additional: DynamicAdditionalDto? = null,
        orig: DynamicItemDto? = null,
    ) = DynamicItemDto(
        idStr = "1000",
        type = type,
        orig = orig,
        modules = DynamicModulesDto(
            moduleAuthor = ModuleAuthorDto(mid = 7L, name = "某人", face = "//f.jpg", pubTs = 1_700_000_000L),
            moduleDynamic = ModuleDynamicDto(
                major = major,
                // desc 与 nodes 都不传时**整个 desc 为空**,这正是"退到 opus"那条分支的前提。
                desc = when {
                    nodes != null -> DynamicDescDto(text = desc.orEmpty(), richTextNodes = nodes)
                    desc != null -> DynamicDescDto(text = desc)
                    else -> null
                },
                additional = additional,
            ),
        ),
    )

    private val archive = ArchiveDto(
        bvid = "BV1xx",
        title = "标题",
        cover = "//c.jpg",
        durationText = "12:34",
        stat = ArchiveStatDto(play = "1.2万", danmaku = "88"),
        badge = BadgeDto("投稿视频"),
    )

    @Test
    fun `投稿视频与合集更新取的是 major 里两个不同的字段`() {
        val av = item("DYNAMIC_TYPE_AV", MajorDto(archive = archive)).toDynamicCard()
        val season = item("DYNAMIC_TYPE_UGC_SEASON", MajorDto(ugcSeason = archive)).toDynamicCard()

        assertEquals("BV1xx", (av?.content as DynamicContent.Video).bvid)
        assertEquals("BV1xx", (season?.content as DynamicContent.Video).bvid)
    }

    @Test
    fun `合集字段挂在 ugc_season 上时,AV 那条路取不到它`() {
        // 认错一个字段的表现正是这样:类型对得上,内容却是空的。
        val wrong = item("DYNAMIC_TYPE_AV", MajorDto(ugcSeason = archive)).toDynamicCard()
        assertNull(wrong)
    }

    @Test
    fun `投稿视频这个默认角标当作没有角标`() {
        val card = item("DYNAMIC_TYPE_AV", MajorDto(archive = archive)).toDynamicCard()
        assertEquals("", (card?.content as DynamicContent.Video).badge)

        val charged = item("DYNAMIC_TYPE_AV", MajorDto(archive = archive.copy(badge = BadgeDto("充电专属"))))
            .toDynamicCard()
        assertEquals("充电专属", (charged?.content as DynamicContent.Video).badge)
    }

    @Test
    fun `番剧与课堂映射成剧集,不冒充投稿视频`() {
        val pgc = item("DYNAMIC_TYPE_PGC", MajorDto(pgc = archive.copy(bvid = null, jumpUrl = "//www.bilibili.com/bangumi/play/ep1")))
        val union = item("DYNAMIC_TYPE_PGC_UNION", MajorDto(pgc = archive.copy(bvid = null)))
        val course = item("DYNAMIC_TYPE_COURSES_SEASON", MajorDto(courses = archive.copy(bvid = null)))

        assertTrue(pgc.toDynamicCard()?.content is DynamicContent.Season)
        assertTrue(union.toDynamicCard()?.content is DynamicContent.Season)
        assertTrue(course.toDynamicCard()?.content is DynamicContent.Season)
    }

    /**
     * 这条是"午安"那个 bug 的回归用例。`itemOpusStyle` 打开之后**纯文字动态也带 opus 和
     * title**(第一行被服务端提成了标题),所以"有 opus.title 就是专栏"这个判据是错的:
     * 它把一句话动态画成了带缩略图的专栏卡片,而真正的正文躺在 desc 里没人读。
     */
    @Test
    fun `有 desc 时正文取 desc,opus 的标题连看都不看`() {
        val card = item(
            "DYNAMIC_TYPE_WORD",
            MajorDto(opus = OpusDto(title = "午安", summary = OpusSummaryDto("现在是纽芬兰的凌晨四点多"))),
            desc = "午安\n现在是纽芬兰的凌晨四点多",
        ).toDynamicCard()

        assertEquals("午安\n现在是纽芬兰的凌晨四点多", (card?.text?.single() as ArticleSpan.Text).text)
        assertNull(card?.article)
        assertNull(card?.content)
        // desc 是这条动态自己写的话,一个字不少。界面据此把它整段铺开,不收也不给"阅读全文"。
        assertEquals(false, card?.textIsSummary)
    }

    /** desc 整个缺失时才退到 opus,那时标题加粗排在摘要前面(PiliPlus rich_node_panel.dart:46-58)。 */
    @Test
    fun `没有 desc 时才用 opus 的标题加摘要`() {
        val card = item(
            "DYNAMIC_TYPE_ARTICLE",
            MajorDto(
                opus = OpusDto(
                    title = "一篇专栏",
                    summary = OpusSummaryDto("摘要"),
                    jumpUrl = "//www.bilibili.com/opus/998",
                ),
            ),
        ).toDynamicCard()

        val spans = card?.text.orEmpty().filterIsInstance<ArticleSpan.Text>()
        assertEquals(listOf("一篇专栏\n", "摘要"), spans.map { it.text })
        assertEquals(true, spans.first().bold)
        // 没有 has_more 就是完整的一条,哪怕它走的是 opus 这条路。请求带着 itemOpusStyle 时
        // 图文和文字动态全部长这样,按"来自 opus"判会把整个首页都判成摘要。
        assertEquals(false, card?.textIsSummary)
    }

    /**
     * 截断与否只看服务端的 `has_more`。2026-08-11 抓的首页里 15 条图文与文字动态的 summary
     * 长 15–72 字、`has_more` 全 false;一个专栏号的 9 条 `DYNAMIC_TYPE_ARTICLE` 长 507–509 字、
     * `has_more` 全 true —— 507 就是服务端的截断位置。
     */
    @Test
    fun `has_more 为真才算摘要,长度和动态类型都不作数`() {
        fun card(hasMore: Boolean) = item(
            "DYNAMIC_TYPE_DRAW",
            MajorDto(
                opus = OpusDto(
                    summary = OpusSummaryDto("正文".repeat(300), hasMore = hasMore),
                    jumpUrl = "//www.bilibili.com/opus/998",
                ),
            ),
        ).toDynamicCard()

        assertEquals(true, card(hasMore = true)?.textIsSummary)
        assertEquals(false, card(hasMore = false)?.textIsSummary)
    }

    /**
     * 旧版专栏(`major.article`)整个没有 has_more 这个字段,而它的 `desc` 按定义就是一段摘要,
     * 所以只要有内容就算截断过 —— 这一条不能跟着"没有 has_more"一起判成完整,否则一篇几千字的
     * 专栏在流里只剩两行且没有"阅读全文"。
     */
    @Test
    fun `旧版专栏没有 has_more,按摘要算`() {
        val card = item(
            "DYNAMIC_TYPE_ARTICLE",
            MajorDto(article = ArticleDto(id = 456L, title = "老专栏", desc = "开头两句")),
        ).toDynamicCard()

        assertEquals(listOf("老专栏\n", "开头两句"), card?.text.orEmpty().filterIsInstance<ArticleSpan.Text>().map { it.text })
        assertEquals(true, card?.textIsSummary)
    }

    /**
     * 图文、专栏、纯文字都**不做成跳转卡片**(PiliPlus module_panel.dart:60-64 对这三种返回
     * SizedBox.shrink),图只是跟着正文铺开。
     *
     * 正文页入口按**认不认得出编号**给,不按 type:一篇长文发出来 type 也可能是"图文"
     * (`DYNAMIC_TYPE_DRAW` + `/opus/<id>`,真机上就是这样),按 type 判会让它只剩一段摘要。
     */
    @Test
    fun `认得出编号才有正文页入口,与动态类型无关`() {
        val draw = item(
            "DYNAMIC_TYPE_DRAW",
            MajorDto(draw = DrawDto(listOf(DrawItemDto(src = "//a.jpg", width = 100, height = 100)))),
            desc = "配图九张",
        ).toDynamicCard()
        val longOpus = item(
            "DYNAMIC_TYPE_DRAW",
            MajorDto(opus = OpusDto(title = "一篇长文", jumpUrl = "//www.bilibili.com/opus/998")),
            desc = "开头几段",
        ).toDynamicCard()

        assertTrue(draw?.content is DynamicContent.Images)
        assertNull(draw?.article)
        assertEquals(ArticleRef("998", isRead = false), longOpus?.article)
    }

    /** 表情只在富文本节点里有地址;`text`/`summary.text` 是拼平的,里面只剩 `[灵魂出窍]` 这个占位符。 */
    @Test
    fun `表情从富文本节点解出地址,不留方括号占位符`() {
        val card = item(
            "DYNAMIC_TYPE_WORD",
            nodes = listOf(
                DynamicRichNodeDto(type = "RICH_TEXT_NODE_TYPE_TEXT", origText = "第一次遇到这种情况"),
                DynamicRichNodeDto(
                    type = "RICH_TEXT_NODE_TYPE_EMOJI",
                    origText = "[灵魂出窍]",
                    emoji = DynamicEmojiDto(iconUrl = "//e.png", size = 1),
                ),
            ),
        ).toDynamicCard()

        assertEquals("第一次遇到这种情况", (card?.text?.get(0) as ArticleSpan.Text).text)
        assertEquals("https://e.png", (card?.text?.get(1) as ArticleSpan.Emoji).url)
    }

    private fun reserveCard(reserve: AdditionalReserveDto) = item(
        "DYNAMIC_TYPE_WORD",
        desc = "正文",
        additional = DynamicAdditionalDto(type = "ADDITIONAL_TYPE_RESERVE", reserve = reserve),
    ).toDynamicCard()?.additional as? DynamicAdditional.Reserve

    /**
     * 预约人数只取服务端那两行说明。以前另外把 `reserve_total` 折算一遍拼在后面,真机上是
     * "3.8万人预约   3.8万 人预约" —— 同一个数印了两遍。
     */
    @Test
    fun `预约只留服务端的说明,不再自己折算一遍人数`() {
        val reserve = reserveCard(
            AdditionalReserveDto(
                rid = 77L,
                title = "直播预约:2026冰火歌会",
                reserveTotal = 38000L,
                desc1 = AdditionalDescDto("08-23 19:00 直播"),
                desc2 = AdditionalDescDto("3.8万人预约"),
            ),
        )

        assertEquals("08-23 19:00 直播  3.8万人预约", reserve?.description)
    }

    /**
     * 预约块只剩说明和时刻,按钮状态一概不读。
     *
     * 2026-08-11 抓的 8 条真实预约里,服务端给出三种按钮:正常、带 `jump_url` 的外跳(「预计
     * 今天 18:30发布」这类)、以及 `uncheck.disable=1` 的禁用(时间已经过去的两场)。照它们
     * 画按钮的话一列预约里只有小半数按得动,而它们长得一模一样。
     */
    @Test
    fun `按钮的三种形态都不影响解析结果`() {
        val jump = reserveCard(
            AdditionalReserveDto(rid = 1L, desc1 = AdditionalDescDto("08-23 19:00 直播"), button = ReserveButtonDto(type = 1, jumpUrl = "//live.bilibili.com/1")),
        )
        val disabled = reserveCard(
            AdditionalReserveDto(
                rid = 1L,
                desc1 = AdditionalDescDto("08-23 19:00 直播"),
                button = ReserveButtonDto(type = 1, uncheck = ReserveButtonTextDto(text = "预约", disable = 1)),
            ),
        )

        assertEquals(jump?.startAtEpochSeconds, disabled?.startAtEpochSeconds)
        assertEquals("08-23 19:00 直播", disabled?.description)
    }

    /**
     * `stype` 分开两种预约:2 是直播、1 是视频。界面据此决定去处 —— 直播随时能进(房间号
     * 按 up_mid 现查),视频要等发布。`jump_url` 只在东西已经存在时才有值。
     */
    @Test
    fun `stype 分出直播预约与视频预约,地址原样带出`() {
        val live = reserveCard(AdditionalReserveDto(rid = 1L, stype = 2, upMid = 66L))
        val video = reserveCard(
            AdditionalReserveDto(rid = 1L, stype = 1, upMid = 77L, jumpUrl = "//www.bilibili.com/video/BV1uiuq62E5q"),
        )

        assertEquals(true, live?.isLive)
        assertEquals(66L, live?.upMid)
        // 还没开播的直播预约没有地址,所以房间号只能靠 up_mid 现查。
        assertEquals("", live?.jumpUrl)
        assertEquals(false, video?.isLive)
        assertEquals("https://www.bilibili.com/video/BV1uiuq62E5q", video?.jumpUrl)
    }

    @Test
    fun `已取消的预约不画`() {
        val card = item(
            "DYNAMIC_TYPE_WORD",
            desc = "正文",
            additional = DynamicAdditionalDto(
                type = "ADDITIONAL_TYPE_RESERVE",
                reserve = AdditionalReserveDto(title = "取消了的预约", state = -1),
            ),
        ).toDynamicCard()

        assertNull(card?.additional)
    }

    @Test
    fun `专栏编号从跳转地址里认,opus 与 read 是两套`() {
        assertEquals("998" to false, articleIdentityOf("https://www.bilibili.com/opus/998"))
        assertEquals("123" to true, articleIdentityOf("https://www.bilibili.com/read/cv123"))
        assertEquals("123" to true, articleIdentityOf("https://www.bilibili.com/read/123"))
        assertNull(articleIdentityOf("https://www.bilibili.com/video/BV1xx"))
    }

    @Test
    fun `旧版专栏没有跳转地址时退回 article id,并按 cv 号处理`() {
        val card = item(
            "DYNAMIC_TYPE_ARTICLE",
            MajorDto(article = ArticleDto(id = 456L, title = "老专栏", desc = "摘要")),
        ).toDynamicCard()

        assertEquals(ArticleRef("456", isRead = true), card?.article)
    }

    @Test
    fun `开播动态的 live_state 决定还在不在播`() {
        val on = item("DYNAMIC_TYPE_LIVE", MajorDto(live = DynamicLiveDto(id = 42L, title = "标题", liveState = 1)))
        val off = item("DYNAMIC_TYPE_LIVE", MajorDto(live = DynamicLiveDto(id = 42L, title = "标题", liveState = 0)))

        assertEquals(true, (on.toDynamicCard()?.content as DynamicContent.Live).live)
        assertEquals(false, (off.toDynamicCard()?.content as DynamicContent.Live).live)
    }

    /**
     * `live_rcmd.content` 是一段 **JSON 字符串**,不是对象。整份动态响应里只有这一处二次编码,
     * 漏掉它不会解析报错,只会画出一张什么都没有的空卡片 —— 所以必须有个用例钉住。
     */
    @Test
    fun `直播推荐卡片的 content 是内嵌 JSON 文本,要再解一次`() {
        val content = """{"live_play_info":{"room_id":12345,"live_status":1,"title":"在播","cover":"//lc.jpg","area_name":"电台","watched_show":{"text_small":"1.1万"}}}"""
        val card = item("DYNAMIC_TYPE_LIVE_RCMD", MajorDto(liveRcmd = LiveRcmdDto(content))).toDynamicCard()

        val live = card?.content as DynamicContent.Live
        assertEquals(12345L, live.roomId)
        assertEquals("在播", live.title)
        assertEquals("电台", live.areaName)
        assertEquals("1.1万", live.watchingText)
    }

    @Test
    fun `内嵌 JSON 解不开时这条动态整体丢掉,不产出一张空卡片`() {
        val card = item("DYNAMIC_TYPE_LIVE_RCMD", MajorDto(liveRcmd = LiveRcmdDto("不是 JSON"))).toDynamicCard()
        assertNull(card)
    }

    @Test
    fun `装扮与活动都走 COMMON_SQUARE`() {
        val card = item(
            "DYNAMIC_TYPE_COMMON_SQUARE",
            MajorDto(common = CommonCardDto(titlePrefix = "装扮 ", title = "某某", desc = "限时", jumpUrl = "//x")),
        ).toDynamicCard()

        assertEquals("装扮 某某", (card?.content as DynamicContent.Common).title)
    }

    @Test
    fun `音频与收藏夹分享各有各的 major 字段`() {
        val music = item("DYNAMIC_TYPE_MUSIC", MajorDto(music = DynamicMusicDto(id = 1, title = "曲名", label = "音频")))
        val medialist = item("DYNAMIC_TYPE_MEDIALIST", MajorDto(medialist = MedialistDto(id = 2, title = "歌单")))

        assertTrue(music.toDynamicCard()?.content is DynamicContent.Music)
        assertTrue(medialist.toDynamicCard()?.content is DynamicContent.Medialist)
    }

    @Test
    fun `转发把原动态解出来,转发者说的话留在外层`() {
        val card = item(
            "DYNAMIC_TYPE_FORWARD",
            desc = "转发者说的话",
            orig = item("DYNAMIC_TYPE_AV", MajorDto(archive = archive)),
        ).toDynamicCard()

        assertTrue(card?.forwarded?.content is DynamicContent.Video)
        assertNull(card?.content)
        assertEquals(1, card?.text?.size)
    }

    @Test
    fun `原动态被删时仍然画这一条,带上服务端的说明`() {
        val card = item(
            "DYNAMIC_TYPE_FORWARD",
            desc = "转发者说的话",
            orig = DynamicItemDto(
                type = "DYNAMIC_TYPE_NONE",
                modules = DynamicModulesDto(
                    moduleDynamic = ModuleDynamicDto(major = MajorDto(none = DynamicNoneDto("源动态已被作者删除"))),
                ),
            ),
        ).toDynamicCard()

        assertNull(card?.forwarded)
        assertEquals("源动态已被作者删除", card?.forwardTips)
    }

    /**
     * 转发一条图文动态时,被转的那条**全部内容都在 forwarded 上**:外层的 major 是空的,
     * 只有转发者说的话。这里若把原动态解成一张只有作者名的卡片,界面上就是"转发了一条空动态"。
     */
    @Test
    fun `被转的原动态带着自己的正文与配图`() {
        val card = item(
            "DYNAMIC_TYPE_FORWARD",
            desc = "说得好",
            orig = item(
                "DYNAMIC_TYPE_DRAW",
                MajorDto(draw = DrawDto(listOf(DrawItemDto(src = "//a.jpg", width = 100, height = 100)))),
                desc = "原动态的正文",
            ),
        ).toDynamicCard()

        assertEquals("原动态的正文", (card?.forwarded?.text?.single() as ArticleSpan.Text).text)
        assertTrue(card?.forwarded?.content is DynamicContent.Images)
        assertNull(card?.forwardTips)
    }

    /** 转发链最长两层。没有上限的话,一条自引用的数据就能把这里递归到栈溢出。 */
    @Test
    fun `转发链不超过两层`() {
        val inner = item("DYNAMIC_TYPE_FORWARD", desc = "第二层", orig = item("DYNAMIC_TYPE_AV", MajorDto(archive = archive)))
        val card = item("DYNAMIC_TYPE_FORWARD", desc = "第一层", orig = inner).toDynamicCard()

        assertEquals("第二层", (card?.forwarded?.text?.first() as ArticleSpan.Text).text)
        assertNull(card?.forwarded?.forwarded)
    }

    @Test
    fun `投票只解出标题与参与人数`() {
        val card = item(
            "DYNAMIC_TYPE_WORD",
            desc = "来投一票",
            additional = DynamicAdditionalDto(
                type = "ADDITIONAL_TYPE_VOTE",
                vote = AdditionalVoteDto(voteId = 9, title = "选哪个", joinNum = 1200),
            ),
        ).toDynamicCard()

        assertEquals(DynamicAdditional.Vote("选哪个", 1200L), card?.additional)
    }

    @Test
    fun `商品附加块不画`() {
        val card = item(
            "DYNAMIC_TYPE_WORD",
            desc = "正文",
            additional = DynamicAdditionalDto(type = "ADDITIONAL_TYPE_GOODS"),
        ).toDynamicCard()

        assertNull(card?.additional)
    }

    @Test
    fun `没有作者模块的条目丢掉,不产出一条无名动态`() {
        assertNull(DynamicItemDto(idStr = "1", type = "DYNAMIC_TYPE_WORD").toDynamicCard())
    }

    @Test
    fun `认不出的新类型丢掉,不画占位卡片`() {
        assertNull(item("DYNAMIC_TYPE_SOMETHING_NEW", MajorDto()).toDynamicCard())
    }

    /**
     * 评论区身份原样取自 `basic`。**这条测的是"没有按类型推导"**:纯文字动态的 `comment_type`
     * 在真实数据里是 17,而这里喂的是 1(视频稿件那一档),映射照样把 1 带出来。要是哪天有人
     * 补一张 `DYNAMIC_TYPE_*` -> `comment_type` 的对照表,这条会红 —— 那张表在 PiliPlus 里
     * 不存在,写出来就是发明,而错了的表现是评论区里全是别人的评论。
     */
    @Test
    fun `评论区的 oid 与 type 照抄 basic,不按动态类型推导`() {
        val word = item("DYNAMIC_TYPE_WORD", desc = "正文")
        val card = word.copy(
            basic = DynamicBasicDto(commentIdStr = "998", commentType = 1),
            modules = word.modules?.copy(
                moduleStat = ModuleStatDto(
                    like = DynamicStatDto(count = 12, status = true),
                    comment = DynamicStatDto(count = 3),
                ),
            ),
        ).toDynamicCard()

        val interaction = card?.interaction
        assertEquals("998", interaction?.commentId)
        assertEquals(1, interaction?.commentType)
        assertEquals(12L, interaction?.likeCount)
        assertTrue(interaction?.liked == true)
        assertEquals(3L, interaction?.commentCount)
    }

    @Test
    fun `拿不到 id_str 的动态不给互动栏`() {
        // id 那时是本地拼出来的占位串,拿它去点赞只会被服务端拒,而按钮看起来是好的。
        val card = item("DYNAMIC_TYPE_WORD", desc = "正文").copy(idStr = "").toDynamicCard()
        assertNull(card?.interaction)
    }
}
