package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET x/v3/fav/resource/list` 的 data 节点 —— 一个收藏夹里的内容。
 * 参数与字段依据 PiliPlus 的 `lib/http/fav.dart`(`userFavFolderDetail`)与
 * bilibili-API-collect 的 fav/list.md,见 notes/fav.md §6。
 */
@Serializable
data class FavResourceListDto(
    /** 这个收藏夹本身:标题、简介、公开性、条数。每一页都带,内容页的页头取第一页的。 */
    val info: FavFolderDto? = null,
    val medias: List<FavMediaDto>? = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class FavMediaDto(
    /** 内容 id。视频是 aid,音频是 auid,剧集是 epid —— 含义随 [type] 变。 */
    val id: Long = 0L,
    /** 2 视频稿件,12 音频,24 剧集(PiliPlus `fav_video_card.dart`)。取消收藏时要原样带回去。 */
    val type: Int = 0,
    val bvid: String = "",
    val title: String = "",
    val cover: String = "",
    /** 秒。 */
    val duration: Long = 0L,
    val upper: FavUpperDto = FavUpperDto(),
    @SerialName("cnt_info") val cntInfo: FavCntDto = FavCntDto(),
    /** 收藏时间,epoch 秒。 */
    @SerialName("fav_time") val favTime: Long = 0L,
    /**
     * 失效标记。0 与 16 是正常,其余(9 UP 删稿、1 其他原因)是失效,此时封面与标题都是空的。
     * 判据照 PiliPlus `fav_video_card.dart` 的 `[0, 16].contains(attr)`,见 notes/fav.md §6。
     */
    val attr: Int = 0,
    /** 只有剧集(type 24)带,`type_name` 是「番剧」「电影」这一类。 */
    val ogv: FavOgvDto? = null,
)

@Serializable
data class FavUpperDto(val mid: Long = 0L, val name: String = "")

@Serializable
data class FavCntDto(val play: Long = 0L, val danmaku: Long = 0L)

@Serializable
data class FavOgvDto(@SerialName("type_name") val typeName: String = "")
