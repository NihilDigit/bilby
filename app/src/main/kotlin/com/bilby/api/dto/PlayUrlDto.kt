package com.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /x/player/wbi/playurl` 的响应体(data 节点)。字段依据 notes/playurl.md 第 3 节,
 * 那里逐条标了 PiliPlus 的源码行号。
 *
 * 命名的坑:网页端这个接口对 dash 流里的每个字段**同时下发驼峰和下划线两种拼写**
 * (既有 `baseUrl` 又有 `base_url`)。kotlinx.serialization 的 @JsonNames 在这种
 * "两个名字同时出现"的情况下会抛 JsonDecodingException(别名和主名冲突),所以取流地址
 * 这类不能丢的字段用两个可空属性各接一种拼写,再由 getter 合并;其余字段只接一种,
 * 多出来的那份靠 ignoreUnknownKeys 吃掉。
 */
@Serializable
data class PlayUrlDto(
    /** 服务端实际给出的清晰度 id(不一定等于请求里的 qn)。 */
    val quality: Int = 0,
    val format: String = "",
    /** 毫秒。 */
    @SerialName("timelength") val timeLength: Long = 0,
    @SerialName("accept_quality") val acceptQuality: List<Int> = emptyList(),
    @SerialName("accept_description") val acceptDescription: List<String> = emptyList(),
    @SerialName("accept_format") val acceptFormat: String = "",
    @SerialName("video_codecid") val videoCodecid: Int = 0,
    @SerialName("support_formats") val supportFormats: List<SupportFormatDto> = emptyList(),
    val dash: DashDto? = null,
    /** dash 缺席时才有值,见 §3.2:FLV/MP4 老格式兜底。 */
    val durl: List<DurlDto>? = null,
    /**
     * 续播位置(秒)。notes §8.2 的结论:秒级进度是跟 playurl 一起返回的,不需要另外调
     * `x/player/wbi/v2`(那个接口只给 last_play_cid、字幕、看点)。UGC 在顶层,PGC/PUGV
     * 在 play_view_business_info 下(本类只覆盖 UGC)。
     */
    @SerialName("last_play_time") val lastPlayTime: Long = 0,
    /** 上次播放到哪一 P。多 P 视频进入播放页时用它决定默认 cid。 */
    @SerialName("last_play_cid") val lastPlayCid: Long = 0,
)

/** 按清晰度反查该档支持哪些编码,是画质菜单和"这档有没有 AV1"的依据(notes §3.3)。 */
@Serializable
data class SupportFormatDto(
    val quality: Int = 0,
    val format: String = "",
    @SerialName("new_description") val newDescription: String = "",
    @SerialName("display_desc") val displayDesc: String = "",
    val codecs: List<String> = emptyList(),
)

@Serializable
data class DashDto(
    /** 秒。 */
    val duration: Int = 0,
    val minBufferTime: Double = 0.0,
    val video: List<DashStreamDto> = emptyList(),
    /** 普通音轨。杜比/无损各在自己的节点里,不混在这个数组内(notes §3.1)。 */
    val audio: List<DashStreamDto>? = null,
    val dolby: DolbyDto? = null,
    val flac: FlacDto? = null,
)

/** 杜比全景声。注意 audio 是数组,和 flac 的单对象不对称,这是服务端的形状不是笔误。 */
@Serializable
data class DolbyDto(
    val type: Int = 0,
    val audio: List<DashStreamDto>? = null,
)

/** Hi-Res 无损。display=false 表示当前账号不可选(非大会员)。 */
@Serializable
data class FlacDto(
    val display: Boolean = false,
    val audio: DashStreamDto? = null,
)

/** video[] 与 audio[] 共用同一套字段,差别只在 width/height/frameRate 对音轨无意义。 */
@Serializable
data class DashStreamDto(
    /** video 是清晰度 id(80=1080P...),audio 是音质 id(30280=192K...)。见 notes §4.2。 */
    val id: Int = 0,
    @SerialName("baseUrl") val baseUrlCamel: String? = null,
    @SerialName("base_url") val baseUrlSnake: String? = null,
    @SerialName("backupUrl") val backupUrlCamel: List<String>? = null,
    @SerialName("backup_url") val backupUrlSnake: List<String>? = null,
    val bandwidth: Long = 0,
    /** 7=AVC, 12=HEVC, 13=AV1(取值来自服务端,不是本地枚举)。 */
    val codecid: Int = 0,
    /** 形如 `avc1.640032` / `av01.0.08M.08` / `hev1.1.6.L120.90`,按前缀判编码。 */
    val codecs: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("frameRate") val frameRate: String = "",
    @SerialName("mimeType") val mimeType: String = "",
    val sar: String = "",
    val startWithSap: Int = 0,
    /**
     * UNSURE:PiliPlus 把整个 segment_base 原样存成 Map,没有验证过子字段名
     * (notes §3.1)。这里按 bilibili-API-collect 文档的写法声明 initialization /
     * index_range,但它们是否真的指向可用的 init segment / sidx 未经验证——Media3 侧
     * 目前按 ProgressiveMediaSource 直接播完整 baseUrl,不依赖这两个值。
     */
    @SerialName("segment_base") val segmentBase: SegmentBaseDto? = null,
) {
    val baseUrl: String get() = baseUrlCamel ?: baseUrlSnake ?: ""
    val backupUrls: List<String> get() = backupUrlCamel ?: backupUrlSnake ?: emptyList()
}

@Serializable
data class SegmentBaseDto(
    val initialization: String = "",
    @SerialName("index_range") val indexRange: String = "",
)

/** 非 dash 兜底流。多段时 order 是段序号;PiliPlus 注释说这类流"已被淘汰"(notes §3.2)。 */
@Serializable
data class DurlDto(
    val order: Int = 0,
    /** 毫秒。 */
    val length: Long = 0,
    val size: Long = 0,
    val url: String = "",
    @SerialName("backup_url") val backupUrl: List<String>? = null,
)
