package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /x/polymer/web-dynamic/v1/feed/all` 的响应体。字段路径依据见
 * notes/dynamic-feed.md 第 1、4、5 节(逐条标了 PiliPlus 源码行号)。
 *
 * 只映射视频类动态用得到的字段:图文/文字/直播/音频等 major 变体,以及转发的
 * `orig` 递归结构,这一层完全不涉及(转发在 DynamicRepository 里按 DESIGN 2.1
 * 直接丢弃,不需要解析被转发的原动态)。
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
    /** 字符串枚举,如 DYNAMIC_TYPE_AV / UGC_SEASON / PGC / PGC_UNION / COURSES_SEASON / FORWARD ...(notes 第 4 节)。 */
    val type: String = "",
    val modules: DynamicModulesDto? = null,
    /**
     * 被转发的原动态,结构和外层完全一样(递归)。**原动态被删时这里仍在,但 type 是
     * `DYNAMIC_TYPE_NONE`**,`module_dynamic.major.none.tips` 里写着"源动态已被作者删除" ——
     * 那种情况要显示成一条失效引用,不能当成解析失败整条丢掉,否则转发者说的话也跟着没了。
     */
    val orig: DynamicItemDto? = null,
)

@Serializable
data class DynamicModulesDto(
    @SerialName("module_author") val moduleAuthor: ModuleAuthorDto? = null,
    @SerialName("module_dynamic") val moduleDynamic: ModuleDynamicDto? = null,
)

/** UP 主与发布时间,对应相对路径 modules.module_author(notes 第 5 节)。 */
@Serializable
data class ModuleAuthorDto(
    val mid: Long = 0L,
    val name: String = "",
    val face: String = "",
    /** 秒级 UNIX 时间戳。notes 里 PiliPlus 只在 >0 时采用,这里原样带出,由 Repository 决定怎么处理 0/缺失。 */
    @SerialName("pub_ts") val pubTs: Long = 0L,
)

@Serializable
data class ModuleDynamicDto(
    val major: MajorDto? = null,
    val desc: DynamicDescDto? = null,
)

@Serializable
data class DynamicDescDto(
    val text: String = "",
)

/**
 * 五种视频类动态共用同一套 `major.{archive|ugc_season|pgc|courses}` 结构
 * (`DynamicArchiveModel`,notes 第 5、6 节),PGC 与 PGC_UNION 都落在 `pgc` 上。
 */
@Serializable
data class MajorDto(
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

@Serializable
data class OpusSummaryDto(val text: String = "")

@Serializable
data class OpusPicDto(val url: String = "")

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

@Serializable
data class ArchiveDto(
    /** 番剧类可能没有 bvid;是否 fallback 到 epid 由 Repository 决定(notes 第 5 节)。 */
    val bvid: String? = null,
    val title: String = "",
    val cover: String = "",
    /** 展示用字符串,如 "12:34",不是秒数(notes 第 5 节)。 */
    @SerialName("duration_text") val durationText: String = "",
    val stat: ArchiveStatDto? = null,
)

/** 视频播放量/弹幕数,注意 JSON key 是 danmaku 不是 danmu(notes 第 5 节)。两者都是格式化过的字符串,如 "1.2万"。 */
@Serializable
data class ArchiveStatDto(
    val play: String = "",
    val danmaku: String = "",
)
