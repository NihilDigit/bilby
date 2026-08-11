package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /x/polymer/web-dynamic/v1/feed/all` 与 `.../feed/space` 的响应体(两条接口的响应结构
 * 完全一样)。字段路径依据 notes/dynamic-feed.md 第 1、4、5 节与 notes/dynamic-cards.md,
 * 逐条标了 PiliPlus 的源码行号。
 *
 * **这一层不做取舍,只做映射**:哪些类型进首页、哪些只在空间页出现,是 DynamicRepository 与
 * SpaceRepository 各自的产品判断,不是解析能力的边界。以前这里只映射视频类字段,于是"支持
 * 一种新动态"要先回来改 DTO,而 DTO 改动会同时影响两个仓库。
 */
@Serializable
data class DynamicFeedResponseDto(
    @SerialName("has_more") val hasMore: Boolean = false,
    val offset: String = "",
    @SerialName("update_baseline") val updateBaseline: String = "",
    val items: List<DynamicItemDto> = emptyList(),
)

@Serializable
data class DynamicItemDto(
    @SerialName("id_str") val idStr: String = "",
    /** 字符串枚举,如 DYNAMIC_TYPE_AV / UGC_SEASON / PGC / LIVE_RCMD / FORWARD ...(notes 第 4 节)。 */
    val type: String = "",
    val visible: Boolean = true,
    /** 评论区身份。opus 的 comment_type 是 11 或 12,视频动态指向的是稿件。 */
    val basic: DynamicBasicDto? = null,
    val modules: DynamicModulesDto? = null,
    /**
     * 被转发的原动态,结构和外层完全一样(递归)。**原动态被删时这里仍在,但 type 是
     * `DYNAMIC_TYPE_NONE`**,`module_dynamic.major.none.tips` 里写着"源动态已被作者删除" ——
     * 那种情况要显示成一条失效引用,不能当成解析失败整条丢掉,否则转发者说的话也跟着没了。
     */
    val orig: DynamicItemDto? = null,
)

@Serializable
data class DynamicBasicDto(
    @SerialName("comment_id_str") val commentIdStr: String = "",
    @SerialName("comment_type") val commentType: Int = 0,
    @SerialName("rid_str") val ridStr: String = "",
)

@Serializable
data class DynamicModulesDto(
    @SerialName("module_author") val moduleAuthor: ModuleAuthorDto? = null,
    @SerialName("module_dynamic") val moduleDynamic: ModuleDynamicDto? = null,
    /** 这条**动态**的点赞/评论/转发数,**不是**视频的播放量(notes 第 5 节结尾)。 */
    @SerialName("module_stat") val moduleStat: ModuleStatDto? = null,
    /** 如"置顶"。 */
    @SerialName("module_tag") val moduleTag: ModuleTagDto? = null,
)

/** UP 主与发布时间,对应相对路径 modules.module_author(notes 第 5 节)。 */
@Serializable
data class ModuleAuthorDto(
    val mid: Long = 0L,
    val name: String = "",
    val face: String = "",
    /** 秒级 UNIX 时间戳。notes 里 PiliPlus 只在 >0 时采用,这里原样带出,由 Repository 决定怎么处理 0/缺失。 */
    @SerialName("pub_ts") val pubTs: Long = 0L,
    /** 服务端已经格式化好的展示文案,如"3小时前"。取不到 pub_ts 时才用得上。 */
    @SerialName("pub_time") val pubTime: String = "",
    @SerialName("is_top") val isTop: Boolean = false,
)

@Serializable
data class ModuleStatDto(
    val comment: DynamicStatDto? = null,
    val forward: DynamicStatDto? = null,
    val like: DynamicStatDto? = null,
)

@Serializable
data class DynamicStatDto(
    val count: Long = 0L,
    val status: Boolean = false,
)

@Serializable
data class ModuleTagDto(val text: String = "")

@Serializable
data class ModuleDynamicDto(
    val major: MajorDto? = null,
    val desc: DynamicDescDto? = null,
    /** 挂在正文下面的附加块:投票、预约、商品、赛事(notes/dynamic-cards.md 第 3 节)。 */
    val additional: DynamicAdditionalDto? = null,
    val topic: DynamicTopicDto? = null,
)

/**
 * 动态正文。`text` 是拼平的纯文字,`rich_text_nodes` 才带 @提及、话题、表情与站外链接。
 * 两个都取:富文本节点缺失时(个别类型只给 text)还有得可画。
 */
@Serializable
data class DynamicDescDto(
    val text: String = "",
    @SerialName("rich_text_nodes") val richTextNodes: List<DynamicRichNodeDto> = emptyList(),
)

/** 与专栏正文里的 `rich` 节点是同一套 `RICH_TEXT_NODE_TYPE_*` 取值,但字段少一些。 */
@Serializable
data class DynamicRichNodeDto(
    val type: String = "",
    val text: String = "",
    @SerialName("orig_text") val origText: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
    val rid: String = "",
    val emoji: DynamicEmojiDto? = null,
)

@Serializable
data class DynamicEmojiDto(
    @SerialName("icon_url") val iconUrl: String = "",
    val text: String = "",
    val size: Int = 1,
)

@Serializable
data class DynamicTopicDto(
    val id: Long = 0L,
    val name: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
)

/**
 * `major` 的所有子对象。**哪个非空由 `item.type` 决定**,`major.type`(`MAJOR_TYPE_*`)只在
 * 少数分支上用得到(notes 第 4 节)。
 *
 * 五种视频类动态共用同一套 `archive|ugc_season|pgc|courses` 结构(`DynamicArchiveModel`,
 * notes 第 5、6 节),PGC 与 PGC_UNION 都落在 `pgc` 上。
 */
@Serializable
data class MajorDto(
    val type: String = "",
    val archive: ArchiveDto? = null,
    @SerialName("ugc_season") val ugcSeason: ArchiveDto? = null,
    val pgc: ArchiveDto? = null,
    val courses: ArchiveDto? = null,
    /** 图文动态的配图。 */
    val draw: DrawDto? = null,
    /**
     * 专栏。**新接口把专栏、图文长文都归到 `opus` 上**,老的 `article` 只在旧数据里出现,
     * 两个都收:同一条动态在不同账号/不同时间返回的形状不一定一样。
     */
    val opus: OpusDto? = null,
    val article: ArticleDto? = null,
    /** 开播动态(DYNAMIC_TYPE_LIVE)。 */
    val live: DynamicLiveDto? = null,
    /**
     * 直播推荐卡片(DYNAMIC_TYPE_LIVE_RCMD)。**`content` 是一段 JSON 字符串,要再解一次**
     * (PiliPlus 的 `DynamicLiveModel.fromJson` 对它 `jsonDecode`),真正的房间信息在
     * 里面的 `live_play_info` 上。这是这一族里唯一一处二次编码,漏掉就是一张空卡片。
     */
    @SerialName("live_rcmd") val liveRcmd: LiveRcmdDto? = null,
    /** 新版订阅卡片,里面套的还是一份 live_rcmd。 */
    @SerialName("subscription_new") val subscriptionNew: SubscriptionNewDto? = null,
    /** 活动卡片。**装扮、剧集点评、普通分享都走这个**(PiliPlus save_panel/view.dart:272 的注释)。 */
    val common: CommonCardDto? = null,
    @SerialName("upower_common") val upowerCommon: CommonCardDto? = null,
    val music: DynamicMusicDto? = null,
    /** 收藏夹/歌单分享。 */
    val medialist: MedialistDto? = null,
    /** 源动态被删时占位,`tips` 是"源动态已被作者删除"这类说明。 */
    val none: DynamicNoneDto? = null,
)

@Serializable
data class DrawDto(val items: List<DrawItemDto> = emptyList())

@Serializable
data class DrawItemDto(
    val src: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * 专栏(新)。`summary` 是富文本节点拼出来的纯文本摘要,`pics` 是封面图。
 * `jump_url` 常常是 `//www.bilibili.com/opus/123` 这种协议相对地址,用之前要补 https。
 */
@Serializable
data class OpusDto(
    val title: String? = null,
    val summary: OpusSummaryDto? = null,
    val pics: List<OpusPicDto> = emptyList(),
    @SerialName("jump_url") val jumpUrl: String = "",
)

/**
 * `text` 是拼平的纯文本,表情在里面只剩 `[灵魂出窍]` 这样的方括号占位符,拿它当正文就永远
 * 画不出表情。真正能画的是 `rich_text_nodes`,PiliPlus 的 `rich_node_panel.dart:41` 取的
 * 也是这一条,`text` 只在节点缺失时兜底。
 */
@Serializable
data class OpusSummaryDto(
    val text: String = "",
    @SerialName("rich_text_nodes") val richTextNodes: List<DynamicRichNodeDto> = emptyList(),
    /**
     * 这一份是截断过的,后面还有正文。
     *
     * **判断"能不能就地读完"只看这个字段,不看长度也不看动态类型**:2026-08-11 抓的首页里
     * 15 条图文与文字动态的 summary 长 15–72 字、`has_more` 全 false,而一个专栏号的 9 条
     * `DYNAMIC_TYPE_ARTICLE` 长 507–509 字、`has_more` 全 true —— 507 就是服务端的截断位置。
     *
     * 早先想用「`module_dynamic.desc` 在不在」来分,那条在本项目上恒不成立:请求带着
     * `itemOpusStyle`(见 BiliConstants.DYN_FEATURES),服务端会把图文和文字动态一律返回成
     * `MAJOR_TYPE_OPUS`,`desc` 因此恒为 null。PiliPlus 不读这个字段,它给每条动态都留了
     * 详情页,收到 6 行了事。
     */
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class OpusPicDto(
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

/** 专栏(旧)。`covers` 最多三张,`desc` 是摘要。 */
@Serializable
data class ArticleDto(
    val id: Long = 0L,
    val title: String = "",
    val desc: String = "",
    val covers: List<String> = emptyList(),
    @SerialName("jump_url") val jumpUrl: String = "",
)

@Serializable
data class DynamicNoneDto(val tips: String = "")

/** 开播动态。`live_state` 为 1 才是正在播。 */
@Serializable
data class DynamicLiveDto(
    val id: Long = 0L,
    val title: String = "",
    val cover: String = "",
    @SerialName("desc_first") val descFirst: String = "",
    @SerialName("desc_second") val descSecond: String = "",
    @SerialName("live_state") val liveState: Int = 0,
    val badge: BadgeDto? = null,
    @SerialName("jump_url") val jumpUrl: String = "",
)

/** 见 [MajorDto.liveRcmd]:`content` 里装的是一整段 JSON 文本,不是对象。 */
@Serializable
data class LiveRcmdDto(val content: String = "")

@Serializable
data class SubscriptionNewDto(@SerialName("live_rcmd") val liveRcmd: LiveRcmdDto? = null)

/** [LiveRcmdDto.content] 二次解出来的形状。 */
@Serializable
data class LiveRcmdContentDto(
    @SerialName("live_play_info") val livePlayInfo: LivePlayInfoDto? = null,
)

@Serializable
data class LivePlayInfoDto(
    @SerialName("room_id") val roomId: Long = 0L,
    @SerialName("live_status") val liveStatus: Int = 0,
    val title: String = "",
    val cover: String = "",
    @SerialName("area_name") val areaName: String = "",
    @SerialName("watched_show") val watchedShow: WatchedShowDto? = null,
)

@Serializable
data class WatchedShowDto(@SerialName("text_small") val textSmall: String = "")

@Serializable
data class CommonCardDto(
    val title: String = "",
    @SerialName("title_prefix") val titlePrefix: String = "",
    val desc: String = "",
    val cover: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
    val badge: BadgeDto? = null,
)

@Serializable
data class DynamicMusicDto(
    val id: Long = 0L,
    val title: String = "",
    val cover: String = "",
    /** 音频的副标题,通常是"音频"或分区名。 */
    val label: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
)

@Serializable
data class MedialistDto(
    val id: Long = 0L,
    val title: String = "",
    @SerialName("sub_title") val subTitle: String = "",
    val cover: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
    val badge: BadgeDto? = null,
)

/**
 * 附加块。`type` 取 `ADDITIONAL_TYPE_VOTE` / `_RESERVE` / `_UGC` / `_GOODS` / `_COMMON` /
 * `_MATCH` / `_UPOWER_LOTTERY`(PiliPlus `pages/dynamics/widgets/additional_panel.dart`)。
 * 商品(`_GOODS`)不解析,理由同专栏里的商品卡。
 */
@Serializable
data class DynamicAdditionalDto(
    val type: String = "",
    val vote: AdditionalVoteDto? = null,
    val reserve: AdditionalReserveDto? = null,
    val ugc: AdditionalUgcDto? = null,
    val common: CommonCardDto? = null,
)

@Serializable
data class AdditionalVoteDto(
    @SerialName("vote_id") val voteId: Long = 0L,
    val title: String = "",
    val desc: String = "",
    @SerialName("join_num") val joinNum: Long = 0L,
)

/**
 * 预约(直播预约、新片预约)。`desc1`/`desc2` 是两行说明,展示只用这两行。
 *
 * **`reserve_total` 解出来但不显示**:desc 里已经写着"3.8万人预约",再折算一遍就是同一个数
 * 印两遍(真机上出现过)。留着字段是因为它标着这块数据在哪,PiliPlus 同样不读它。
 */
@Serializable
data class AdditionalReserveDto(
    val title: String = "",
    val rid: Long = 0L,
    val state: Int = 0,
    /** 1 = 视频预约,2 = 直播预约。2026-08-11 抓的 8 条里两种都有,取值稳定。 */
    val stype: Int = 0,
    /**
     * 预约的东西**已经存在之后**才有值:视频发布后是 `//www.bilibili.com/video/BV…`,
     * 开播后是 `https://live.bilibili.com/<房间号>`。还没到时间的一律是空串 ——
     * 所以预约中的直播拿不到房间号,要按 [upMid] 现查(见 SpaceRepository.liveRoomId)。
     */
    @SerialName("jump_url") val jumpUrl: String = "",
    @SerialName("up_mid") val upMid: Long = 0L,
    @SerialName("reserve_total") val reserveTotal: Long = 0L,
    val desc1: AdditionalDescDto? = null,
    val desc2: AdditionalDescDto? = null,
    val button: ReserveButtonDto? = null,
)

/**
 * 预约按钮。**整块不读**:界面上预约只做"加入日历",不报给 B 站,理由见
 * [dev.bilby.data.model.DynamicAdditional.Reserve]。
 *
 * 字段留着是因为它们说明了那个决定的由来。2026-08-11 抓的 8 条真实预约里三种形态都在:
 * 正常按钮;`jump_url` 非空的外跳(「预计今天 18:30发布」这类,官方端点它是跳去视频页);
 * `uncheck.disable == 1` 的禁用(时刻已经过去的两场)。照它们画的话一列预约里只有小半数
 * 按得动,而它们长得一模一样。"约没约"则要看 `status == type`,`type` 是这张卡片的"已预约"
 * 取值,不是固定的 1(PiliPlus `result.dart:762` 的 `isReserved`)。
 */
@Serializable
data class ReserveButtonDto(
    val status: Int = 0,
    val type: Int = 0,
    val check: ReserveButtonTextDto? = null,
    val uncheck: ReserveButtonTextDto? = null,
    @SerialName("jump_url") val jumpUrl: String = "",
)

@Serializable
data class ReserveButtonTextDto(
    val text: String = "",
    val disable: Int = 0,
)

@Serializable
data class AdditionalDescDto(
    val text: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
)

@Serializable
data class AdditionalUgcDto(
    val title: String = "",
    val cover: String = "",
    @SerialName("desc_second") val descSecond: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
    val duration: String = "",
)

@Serializable
data class ArchiveDto(
    /** 番剧类可能没有 bvid;是否 fallback 到 epid 由 Repository 决定(notes 第 5 节)。 */
    val bvid: String? = null,
    val aid: Long = 0L,
    val title: String = "",
    val cover: String = "",
    /** 展示用字符串,如 "12:34",不是秒数(notes 第 5 节)。 */
    @SerialName("duration_text") val durationText: String = "",
    @SerialName("jump_url") val jumpUrl: String = "",
    val epid: Long = 0L,
    @SerialName("season_id") val seasonId: Long = 0L,
    /** 角标,如"充电专属"。PiliPlus 把"投稿视频"这个文案当作没有角标。 */
    val badge: BadgeDto? = null,
    val stat: ArchiveStatDto? = null,
)

@Serializable
data class BadgeDto(val text: String = "")

/** 视频播放量/弹幕数,注意 JSON key 是 danmaku 不是 danmu(notes 第 5 节)。两者都是格式化过的字符串,如 "1.2万"。 */
@Serializable
data class ArchiveStatDto(
    val play: String = "",
    val danmaku: String = "",
)
