package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET x/v3/fav/resource/list` 的 data 节点 —— 一个收藏夹里的内容。
 * 参数与字段依据 PiliPlus 的 `lib/http/api.dart`(`favResourceList`)与
 * bilibili-API-collect 的 fav/list.md。
 */
@Serializable
data class FavResourceListDto(
    val medias: List<FavMediaDto>? = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class FavMediaDto(
    /** 稿件 aid。收藏夹接口不保证给 bvid,所以列表用 aid 做 key,跳转前再换算。 */
    val id: Long = 0L,
    val bvid: String = "",
    val title: String = "",
    val cover: String = "",
    /** 秒。 */
    val duration: Long = 0L,
    val upper: FavUpperDto = FavUpperDto(),
    @SerialName("cnt_info") val cntInfo: FavCntDto = FavCntDto(),
    /** 稿件失效时为 0(UP 删稿或转私密),此时封面与标题都是空的。 */
    val attr: Int = 0,
)

@Serializable
data class FavUpperDto(val mid: Long = 0L, val name: String = "")

@Serializable
data class FavCntDto(val play: Long = 0L)
