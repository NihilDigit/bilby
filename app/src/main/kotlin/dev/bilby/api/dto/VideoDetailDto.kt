package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /x/web-interface/view` 的响应体(data 节点)。cid 的来源依据是 notes/playurl.md
 * 第 7 节:**取流必需的 cid 就在这里**,不需要另外调 `x/player/pagelist`
 * (PiliPlus 里那个常量搜不到调用点)。
 *
 * 这个接口不需要 WBI 签名。
 */
@Serializable
data class VideoDetailDto(
    val bvid: String = "",
    val aid: Long = 0,
    /**
     * 主 cid,即默认播放的那一 P(notes §7)。单 P 视频只有这一个 cid。
     */
    val cid: Long = 0,
    /** 分 P 数量。 */
    val videos: Int = 1,
    /** 1 = 自制,2 = 转载。转载稿一共只收 1 枚币,见 VideoDetail.maxCoins。 */
    val copyright: Int = 0,
    val tid: Int = 0,
    val tname: String = "",
    val title: String = "",
    /** 简介纯文本。带话题跳转的富文本在 desc_v2 里,本期不做。 */
    val desc: String = "",
    /** 封面,可能是 http 或无协议,用前过 toHttpsUrl()。 */
    val pic: String = "",
    /** 秒级时间戳。 */
    val pubdate: Long = 0,
    val ctime: Long = 0,
    /** 全片总时长(秒)。多 P 时是各 P 之和。 */
    val duration: Long = 0,
    val owner: VideoOwnerDto? = null,
    val staff: List<VideoStaffDto> = emptyList(),
    val stat: VideoStatDto? = null,
    /** 分 P 列表。单 P 视频服务端也会给一个元素,不是空数组。 */
    val pages: List<VideoPageDto> = emptyList(),
    @SerialName("ugc_season") val ugcSeason: UgcSeasonDto? = null,
)

@Serializable
data class VideoOwnerDto(
    val mid: Long = 0,
    val name: String = "",
    val face: String = "",
)

@Serializable
data class VideoStaffDto(
    val mid: Long = 0,
    val title: String = "",
    val name: String = "",
    val face: String = "",
)

@Serializable
data class VideoStatDto(
    val view: Long = 0,
    val danmaku: Long = 0,
    val reply: Long = 0,
    val favorite: Long = 0,
    val coin: Long = 0,
    val share: Long = 0,
    val like: Long = 0,
)

/** 分 P。取"下一 P 的 cid"就是按 page 顺序取下一项的 cid(notes §7)。 */
@Serializable
data class VideoPageDto(
    val cid: Long = 0,
    /** 从 1 开始的分 P 序号。 */
    val page: Int = 0,
    /** 分 P 标题。 */
    val part: String = "",
    /** 秒。 */
    val duration: Long = 0,
)

/**
 * 合集。notes §7 明确标了 UNSURE:PiliPlus 那边没有逐行核实分集 cid 的精确路径,
 * 只推测是 `ugc_season.sections[].episodes[].cid`。这里保留这个推测的结构,并且
 * **同时接 episode.cid 和 episode.page.cid 两个位置**——两者哪个是权威路径未经验证,
 * 由 VideoRepository 取非零的那个。真机冒烟时要专门验这一条。
 */
@Serializable
data class UgcSeasonDto(
    val id: Long = 0,
    val title: String = "",
    val cover: String = "",
    val mid: Long = 0,
    val sections: List<UgcSectionDto> = emptyList(),
)

@Serializable
data class UgcSectionDto(
    val id: Long = 0,
    val title: String = "",
    val episodes: List<UgcEpisodeDto> = emptyList(),
)

@Serializable
data class UgcEpisodeDto(
    val id: Long = 0,
    val aid: Long = 0,
    val bvid: String = "",
    /** UNSURE:见 UgcSeasonDto 的说明,这个 cid 与 page.cid 谁权威未验证。 */
    val cid: Long = 0,
    val title: String = "",
    val arc: UgcEpisodeArcDto? = null,
    val page: VideoPageDto? = null,
)

/** 分集的视频元信息,合集目录里要显示封面和时长。 */
@Serializable
data class UgcEpisodeArcDto(
    val pic: String = "",
    val duration: Long = 0,
    val pubdate: Long = 0,
)
