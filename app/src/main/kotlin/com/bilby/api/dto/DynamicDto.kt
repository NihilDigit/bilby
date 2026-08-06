package com.bilby.api.dto

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
    /** 字符串枚举,如 DYNAMIC_TYPE_AV / UGC_SEASON / PGC / PGC_UNION / COURSES_SEASON / FORWARD ...(notes 第 4 节)。 */
    val type: String = "",
    val modules: DynamicModulesDto? = null,
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
)

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
