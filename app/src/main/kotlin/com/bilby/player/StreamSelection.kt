package com.bilby.player

import com.bilby.api.dto.DashDto
import com.bilby.api.dto.DashStreamDto

/**
 * 从 playurl 的 dash 节点里挑出一条视频流和一条音频流。纯函数、不碰 Android、不碰网络,
 * 这样它是取流这块唯一值得写测试的地方(见 StreamSelectionTest)。
 *
 * 选流规则抄自 PiliPlus,依据 notes/playurl.md 第 4 节,不是本地发挥。
 *
 * **喂给播放器时必须带请求头**:B 站的 CDN 对 baseUrl 做防盗链,少了这两个头会 403。
 * 取值与 BiliConstants 一致:
 *   Referer: https://www.bilibili.com
 *   User-Agent: 桌面浏览器 UA(BiliConstants.USER_AGENT)
 * Media3 侧在 DefaultHttpDataSource.Factory / OkHttpDataSource.Factory 上
 * setDefaultRequestProperties 即可(notes §6)。
 */
data class SelectedStreams(
    val videoUrl: String,
    /** 分离音轨的地址。durl 兜底流是音视频合一的,那种情况下为 null。 */
    val audioUrl: String?,
    val qualityLabel: String,
    /** 人类可读的编码名,如 AVC / AV1;认不出来时是原始 codecs 字符串。 */
    val codec: String,
    /** 实际选中的清晰度 id,可能低于用户偏好(降级),UI 要显示的是这个而不是偏好值。 */
    val qualityId: Int = 0,
)

/** 服务端 codecid 取值,notes §3.1 / §4.1。 */
object VideoCodecId {
    const val AVC = 7
    const val HEVC = 12
    const val AV1 = 13
}

/**
 * 默认编码偏好。**照抄 PiliPlus 的默认值 `[AVC, AV1]`(lib/utils/storage_pref.dart:273),
 * 注意它不含 HEVC** —— 这是抄来的,不是我们的判断:B 站的 HEVC 流在部分设备上的硬解兼容性
 * 不如 AVC/AV1,PiliPlus 宁可不用。要改先在真机上验。
 */
val DEFAULT_PREFERRED_CODECS = listOf(VideoCodecId.AVC, VideoCodecId.AV1)

/** 音质偏好的哨兵值:不指定,按 flac → dolby → 最高普通音轨挑。 */
const val AUDIO_QUALITY_BEST = Int.MAX_VALUE

fun selectStreams(
    dash: DashDto,
    preferredQuality: Int,
    preferredCodecs: List<Int> = DEFAULT_PREFERRED_CODECS,
    preferredAudioQuality: Int = AUDIO_QUALITY_BEST,
): SelectedStreams? {
    val videos = dash.video.filter { it.baseUrl.isNotEmpty() }
    if (videos.isEmpty()) return null

    // 候选集只取 dash.video 里真的有地址的那些,不用响应里的 accept_quality:后者是
    // "这个账号理论上能选的档",可能包含本次没下发流的档(比如 4K 对非大会员),
    // 照它选会选出一条没有 baseUrl 的流。accept_quality 只适合喂给画质菜单。
    val availableQualities = videos.map { it.id }.distinct()
    val targetQuality = closestAtMost(availableQualities, preferredQuality)
        ?: availableQualities.max()

    val candidates = videos.filter { it.id == targetQuality }
    val video = preferredCodecs.firstNotNullOfOrNull { codecId ->
        candidates.firstOrNull { it.matchesCodec(codecId) }
    } ?: candidates.first()

    return SelectedStreams(
        videoUrl = video.baseUrl,
        audioUrl = selectAudio(dash, preferredAudioQuality)?.baseUrl,
        qualityLabel = videoQualityLabel(video.id),
        codec = codecLabel(video),
        qualityId = video.id,
    )
}

/**
 * 音轨优先级 flac → dolby → 普通 audio,顺序抄自 PiliPlus(notes §3.1 的三路合并)。
 *
 * 这里不对三路统一做"按 id 就近匹配":音质 id 的大小**不代表音质高低**
 * (30280=192K 数值上大于 30251=Hi-Res 无损、30250=杜比全景声,见 notes §4.2 的枚举表),
 * 拿数值排序会把无损排到 192K 后面。所以就近匹配只在普通 audio 数组内部做,
 * flac/dolby 靠显式优先级。
 */
private fun selectAudio(dash: DashDto, preferredAudioQuality: Int): DashStreamDto? {
    val flac = dash.flac?.audio?.takeIf { it.baseUrl.isNotEmpty() }
    val dolby = dash.dolby?.audio.orEmpty().firstOrNull { it.baseUrl.isNotEmpty() }
    val normal = dash.audio.orEmpty().filter { it.baseUrl.isNotEmpty() }

    if (preferredAudioQuality == AUDIO_QUALITY_BEST) {
        return flac ?: dolby ?: normal.maxByOrNull { it.id }
    }
    // 指定了具体音质:先在全部候选里找精确匹配(用户可能就是指名要杜比或无损),
    // 找不到再退到普通音轨里就近往下取,还不行就取最低的一条。
    val all = listOfNotNull(flac, dolby) + normal
    all.firstOrNull { it.id == preferredAudioQuality }?.let { return it }
    val target = closestAtMost(normal.map { it.id }, preferredAudioQuality)
    return normal.firstOrNull { it.id == target } ?: normal.minByOrNull { it.id }
}

/** `<= target` 里最大的一个;一个都没有(偏好比所有可选档都低)时返回 null,由调用方决定兜底。 */
private fun closestAtMost(values: List<Int>, target: Int): Int? =
    values.filter { it <= target }.maxOrNull()

/**
 * codecid 是首选判据;有些响应里 codecid 为 0(或本地构造的兜底流没有它),
 * 这时退回按 codecs 字符串前缀判断,前缀表原样抄自 PiliPlus(notes §4.1)。
 */
private fun DashStreamDto.matchesCodec(codecId: Int): Boolean =
    if (codecid != 0) codecid == codecId
    else codecPrefixes(codecId).any { codecs.startsWith(it) }

private fun codecPrefixes(codecId: Int): List<String> = when (codecId) {
    VideoCodecId.AVC -> listOf("avc1")
    VideoCodecId.HEVC -> listOf("hev1", "hvc1")
    VideoCodecId.AV1 -> listOf("av01")
    else -> emptyList()
}

private fun codecLabel(stream: DashStreamDto): String = when {
    stream.codecid == VideoCodecId.AVC || stream.codecs.startsWith("avc1") -> "AVC"
    stream.codecid == VideoCodecId.HEVC ||
        stream.codecs.startsWith("hev1") || stream.codecs.startsWith("hvc1") -> "HEVC"
    stream.codecid == VideoCodecId.AV1 || stream.codecs.startsWith("av01") -> "AV1"
    stream.codecs.startsWith("dvh1") -> "杜比视界"
    else -> stream.codecs
}

/** 清晰度 id → 简称,表原样抄自 PiliPlus(notes §4.2)。 */
fun videoQualityLabel(id: Int): String = when (id) {
    129 -> "HDR Vivid"
    127 -> "8K"
    126 -> "杜比视界"
    125 -> "HDR"
    120 -> "4K"
    116 -> "1080P60"
    112 -> "1080P+"
    80 -> "1080P"
    74 -> "720P60"
    64 -> "720P"
    32 -> "480P"
    16 -> "360P"
    6 -> "240P"
    // 表是抄的,服务端将来加新档这里会认不出来,如实显示 id,不按数字猜一个分辨率名字。
    else -> "画质 $id"
}
