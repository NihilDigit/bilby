package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /x/web-interface/wbi/search/type` 的响应体,`search_type=video` 分支。
 * 字段路径依据 notes/space-and-search.md 2.2、2.7 节,JSON 是拍平结构,不像
 * PiliPlus 的 model 那样嵌套 owner/stat——这里按接口原样声明,嵌套在 Repository 里做。
 */
/**
 * 分类搜索三个分支共有的一格。触发风控时接口照样回 code 0,`data` 里没有结果,只有这张
 * 验证凭据(notes/space-and-search.md 2.2)。不认它的话,风控读起来就是「没有结果」。
 */
interface SearchChallengeCarrier {
    val vVoucher: String?
}

@Serializable
data class SearchVideoResultDto(
    val numResults: Int = 0,
    val result: List<SearchVideoItemDto> = emptyList(),
    @SerialName("v_voucher") override val vVoucher: String? = null,
) : SearchChallengeCarrier

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
    @SerialName("v_voucher") override val vVoucher: String? = null,
) : SearchChallengeCarrier

@Serializable
data class SearchUserItemDto(
    val mid: Long = 0,
    val uname: String = "",
    val usign: String = "",
    val fans: Long = 0,
    val upic: String = "",
    /** 投稿数。 */
    val videos: Long = 0,
)

/**
 * `search_type=article` 分支。字段依据 PiliPlus `lib/models/search/result.dart` 的
 * `SearchArticleItemModel`(notes/space-and-search.md 2.12)。**没有作者名**,只有 mid。
 */
@Serializable
data class SearchArticleResultDto(
    val numResults: Int = 0,
    val result: List<SearchArticleItemDto> = emptyList(),
    @SerialName("v_voucher") override val vVoucher: String? = null,
) : SearchChallengeCarrier

/**
 * `GET /x/web-interface/suggest` 的 data(notes/space-and-search.md 2.9)。`result` 没有补全词时
 * 形状不定,按 JsonElement 收,在 Repository 里取 `tag`。
 */
@Serializable
data class SearchSuggestDto(
    val result: kotlinx.serialization.json.JsonElement? = null,
)

@Serializable
data class SearchArticleItemDto(
    /** cv 号。 */
    val id: Long = 0,
    /** 带 `<em>` 高亮与 HTML 实体,同视频标题。 */
    val title: String = "",
    val desc: String = "",
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    val view: Long = 0,
    val reply: Long = 0,
    @SerialName("pub_time") val pubTime: Long = 0,
    @SerialName("category_name") val categoryName: String = "",
)
