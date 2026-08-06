package com.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /x/web-interface/wbi/search/type` 的响应体,`search_type=video` 分支。
 * 字段路径依据 notes/space-and-search.md 2.2、2.7 节,JSON 是拍平结构,不像
 * PiliPlus 的 model 那样嵌套 owner/stat——这里按接口原样声明,嵌套在 Repository 里做。
 */
@Serializable
data class SearchVideoResultDto(
    val numResults: Int = 0,
    val result: List<SearchVideoItemDto> = emptyList(),
)

@Serializable
data class SearchVideoItemDto(
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    val pubdate: Long = 0,
    /** "12:34" 格式的展示字符串,不是秒数(notes 2.7)。 */
    val duration: String = "",
    val mid: Long = 0,
    val author: String = "",
    val play: Long = 0,
    val danmaku: Long = 0,
)

/** `search_type=bili_user` 分支,字段名与 json key 完全一致(notes 2.8)。 */
@Serializable
data class SearchUserResultDto(
    val numResults: Int = 0,
    val result: List<SearchUserItemDto> = emptyList(),
)

@Serializable
data class SearchUserItemDto(
    val mid: Long = 0,
    val uname: String = "",
    val usign: String = "",
    val fans: Long = 0,
    val upic: String = "",
)
