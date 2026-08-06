package com.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 个人空间相关接口的响应体。字段路径依据 notes/space-and-search.md 第 1 节
 * (逐条标了 PiliPlus 源码行号)。
 */

/** `x/space/wbi/acc/info`,需要 WBI。不含粉丝数,见 [RelationStatDto]。 */
@Serializable
data class SpaceUserInfoDto(
    val mid: Long = 0L,
    val name: String = "",
    val face: String = "",
    val sign: String = "",
    val level: Int = 0,
)

/** `x/relation/stat`,不需要 WBI。字段名从消费端反推,notes 1.2 节标了 UNSURE。 */
@Serializable
data class RelationStatDto(
    val follower: Long = 0L,
    val following: Long = 0L,
)

/**
 * `x/space/wbi/arc/search` 的响应体,需要 WBI。投稿列表与空间内搜索是同一个接口
 * (notes 1.3 节),`vlist` 里字段是拍平的,不是嵌套 stat/owner 对象。
 */
@Serializable
data class ArchiveSearchResponseDto(
    val page: ArchiveSearchPageDto = ArchiveSearchPageDto(),
    val list: ArchiveSearchListDto = ArchiveSearchListDto(),
)

@Serializable
data class ArchiveSearchPageDto(val count: Int = 0)

@Serializable
data class ArchiveSearchListDto(val vlist: List<VListItemDto> = emptyList())

@Serializable
data class VListItemDto(
    val aid: Long = 0L,
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    /** 秒级时间戳。 */
    val created: Long = 0L,
    /** "mm:ss" 格式的展示字符串,不是秒数(notes 1.3 节)。 */
    val length: String = "",
    val author: String = "",
    val mid: Long = 0L,
    val play: Long = 0L,
    @SerialName("video_review") val videoReview: Long = 0L,
)

/** `x/polymer/web-space/seasons_series_list`,不需要 WBI。合集/系列共用一个列表接口。 */
@Serializable
data class SpaceSeasonSeriesResponseDto(
    @SerialName("items_lists") val itemsLists: SpaceSeasonSeriesItemsDto = SpaceSeasonSeriesItemsDto(),
)

@Serializable
data class SpaceSeasonSeriesItemsDto(
    val page: SpaceSeasonSeriesPageDto = SpaceSeasonSeriesPageDto(),
    @SerialName("seasons_list") val seasonsList: List<SpaceSeasonSeriesEntryDto> = emptyList(),
    @SerialName("series_list") val seriesList: List<SpaceSeasonSeriesEntryDto> = emptyList(),
)

@Serializable
data class SpaceSeasonSeriesPageDto(val total: Int = 0)

@Serializable
data class SpaceSeasonSeriesEntryDto(val meta: SpaceSeasonSeriesMetaDto = SpaceSeasonSeriesMetaDto())

/**
 * 合集(season)与系列(series)复用同一个 meta 结构,靠 seasonId/seriesId 哪个非空区分
 * (notes 1.4.1 节),但这里两者已经分别放在 seasons_list/series_list 数组里,不需要再判断。
 */
@Serializable
data class SpaceSeasonSeriesMetaDto(
    val cover: String = "",
    val name: String = "",
    val ptime: Long = 0L,
    val total: Int = 0,
    @SerialName("season_id") val seasonId: Long? = null,
    @SerialName("series_id") val seriesId: Long? = null,
)

/**
 * 合集/系列详情(目录),`seasons_archives_list`(season)或 `series/archives`(series),
 * 均不需要 WBI(notes 1.4.2 节)。`duration` 这里是数值秒,跟 arc/search 的字符串不同。
 * `owner.name` 接口本身不返回,恒为空,所以这里干脆不建 owner 结构,只取 upMid。
 */
@Serializable
data class SeasonArchivesResponseDto(
    val page: SeasonArchivesPageDto = SeasonArchivesPageDto(),
    val archives: List<SeasonArchiveDto> = emptyList(),
)

@Serializable
data class SeasonArchivesPageDto(val total: Int = 0)

@Serializable
data class SeasonArchiveDto(
    val aid: Long = 0L,
    val bvid: String = "",
    val pic: String = "",
    val title: String = "",
    val pubdate: Long = 0L,
    val duration: Long = 0L,
    val stat: SeasonArchiveStatDto = SeasonArchiveStatDto(),
    @SerialName("upMid") val upMid: Long = 0L,
)

@Serializable
data class SeasonArchiveStatDto(val view: Long = 0L, val danmaku: Long = 0L)
