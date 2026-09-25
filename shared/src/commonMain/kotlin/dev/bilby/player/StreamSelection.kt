package dev.bilby.player

import dev.bilby.api.dto.DashDto
import dev.bilby.api.dto.DashStreamDto
import dev.bilby.getStringBlocking
import dev.bilby.resources.Res
import dev.bilby.resources.video_quality_dolby_vision
import dev.bilby.resources.video_quality_unknown

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
    /**
     * 选中的编码本机是否有硬解器。false 说明这一档画质本机一个编码都硬解不了,只能软解,
     * 值得在画质菜单上提示一句——用户看到"卡"和"烫"时应该能知道原因。
     */
    val hardwareDecoded: Boolean = true,
    /** 实际选中的音质 id,0 表示没有分离音轨(durl 兜底)。播放面板的音质一行勾这个。 */
    val audioId: Int = 0,
    /**
     * 这次下发了哪几种音轨,按音质从高到低:无损、杜比、再按码率。播放面板的音质一行列这些 ——
     * 只列真有地址的,不像画质菜单那样列 accept_quality(那里面有没下发流的档)。
     */
    val audioOptions: List<Int> = emptyList(),
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

/**
 * 在 [available] 这些档里挑出对应 [preferred] 的那一档。**纯算术,不看流本身**,所以它是
 * 这条规则唯一值得单测的形态(见 ResolveQualityTest)。
 *
 * 画质 id 是严格可比的数字(notes/playurl.md §4.2 那张表),所以规则只有一条,**播放和缓存
 * 共用同一条**:
 *
 * 1. 取**不高于** [preferred] 的最高一档。精确命中自然落在这里,降级也是这一句。
 * 2. 一档都不满足时**往上取最低可行的那一档**。这只发生在"用户选了 360P,而这条视频最低
 *    就是 720P"上 —— 此时唯一的选择是升,而升到最低的那一档才是离他要的最近的。
 *
 * 这里曾经按调用方分成两种回退(播放取最高、缓存取最低),那是把一个不存在的分歧做成了参数:
 * 第 2 条里"最低可行档"本来就同时是离 [preferred] 最近的和最省的,两条路径要的是同一个答案。
 *
 * **第 2 条与 PiliPlus 不一致,是有意的。** 它那边(notes/playurl.md §4.1 第 2 步)在这种情形下
 * 直接取最高档。PiliPlus 是接口行为的权威,而这一条不是接口行为,是"用户说了想省流量之后
 * 该给他什么"——给最高档等于把他的偏好反过来用。能撞上这条分支的只有那种只下发高码率档的
 * 片源(大多数视频有 360/480/720 打底)。
 *
 * [available] 为空时返回 null —— 那说明这条视频压根没下发可用的流,调用方该按"取流失败"
 * 处理,而不是拿一个编出来的档位继续往下走。
 */
fun resolveQuality(available: List<Int>, preferred: Int): Int? =
    available.filter { it <= preferred }.maxOrNull() ?: available.minOrNull()

fun selectStreams(
    dash: DashDto,
    preferredQuality: Int,
    preferredCodecs: List<Int> = DEFAULT_PREFERRED_CODECS,
    preferredAudioQuality: Int = AUDIO_QUALITY_BEST,
    /**
     * 本机有硬解器的编码。默认查真机([hardwareDecodableCodecIds]),测试里传固定集合。
     * 作用见下面的选流注释:偏好列表决定"想要哪个",这个集合决定"哪些不许选"。
     */
    hardwareCodecs: Set<Int> = hardwareDecodableCodecIds,
): SelectedStreams? {
    val videos = dash.video.filter { it.baseUrl.isNotEmpty() }
    if (videos.isEmpty()) return null

    // 候选集只取 dash.video 里真的有地址的那些,不用响应里的 accept_quality:后者是
    // "这个账号理论上能选的档",可能包含本次没下发流的档(比如 4K 对非大会员),
    // 照它选会选出一条没有 baseUrl 的流。accept_quality 只适合喂给画质菜单。
    //
    // **这一步才是"能不能取到这一档"的真答案。** 请求里带的 qn 只是个愿望:账号权限、
    // 片源本身都可能让服务端下发另一套档位,所以不按预期档位去构造参数硬试,而是照它
    // 实际给了什么来挑(需求补充的最后一条)。
    val availableQualities = videos.map { it.id }.distinct()
    // 上面的 videos 非空保证了这里拿得到值;[resolveQuality] 只在候选为空时给 null。
    val targetQuality = resolveQuality(availableQualities, preferredQuality) ?: return null

    val candidates = videos.filter { it.id == targetQuality }

    // 先在"本机能硬解"的子集里按偏好挑。Media3 只会照 MediaCodecList 的顺序取第一个可用的
    // 解码器,某个编码没有硬解时它会静默退到 c2.android.* 软解——不报错、不掉级、只是费电
    // 且高分辨率下掉帧。所以"别选到软解"这件事只能在这里做,播放器那边没有对应开关。
    //
    // 硬解候选为空(整档画质本机一个都硬解不了,比如只发了 AV1 的 8K)时按原逻辑兜底:
    // 软解播出来也比播不出来强,不能因为省电把视频变成不可播。
    val hardwareCandidates = candidates.filter { stream ->
        hardwareCodecs.any { stream.matchesCodec(it) }
    }
    val pool = hardwareCandidates.ifEmpty { candidates }
    val video = preferredCodecs.firstNotNullOfOrNull { codecId ->
        pool.firstOrNull { it.matchesCodec(codecId) }
    } ?: pool.first()

    val audio = selectAudio(dash, preferredAudioQuality)
    return SelectedStreams(
        videoUrl = preferredStreamUrl(video.baseUrl, video.backupUrls),
        audioUrl = audio?.let { preferredStreamUrl(it.baseUrl, it.backupUrls) },
        qualityLabel = videoQualityLabel(video.id),
        codec = codecLabel(video),
        qualityId = video.id,
        hardwareDecoded = hardwareCandidates.isNotEmpty(),
        audioId = audio?.id ?: 0,
        audioOptions = audioOptions(dash),
    )
}

/**
 * 下发了的音轨,从高到低。顺序是显式的(无损、杜比、普通音轨按 id 降序),理由同 [selectAudio]:
 * 音质 id 的大小不代表音质高低。
 */
private fun audioOptions(dash: DashDto): List<Int> {
    val flac = dash.flac?.audio?.takeIf { it.baseUrl.isNotEmpty() }?.id
    val dolby = dash.dolby?.audio.orEmpty().firstOrNull { it.baseUrl.isNotEmpty() }?.id
    val normal = dash.audio.orEmpty().filter { it.baseUrl.isNotEmpty() }.map { it.id }.distinct().sortedDescending()
    return listOfNotNull(flac, dolby) + normal
}

/** 音质的名字,表见 notes/playurl.md 音质那一节。 */
fun audioQualityLabel(id: Int): String = when (id) {
    AUDIO_QUALITY_BEST -> "最高"
    30251 -> "Hi-Res 无损"
    30250, 30255 -> "杜比全景声"
    30280 -> "192K"
    30232 -> "132K"
    30216 -> "64K"
    else -> "音质 $id"
}

/** 设置页默认音质的几档。无损和杜比只有"最高"能选到:不是每条视频都有,指名要它们没有意义。 */
val DEFAULT_AUDIO_QUALITY_OPTIONS = listOf(AUDIO_QUALITY_BEST, 30280, 30232, 30216)

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
    stream.codecs.startsWith("dvh1") -> getStringBlocking(Res.string.video_quality_dolby_vision)
    else -> stream.codecs
}

/**
 * 清晰度 id → 简称,表抄自 PiliPlus(notes §4.2)。
 *
 * 画质菜单也用这张表,不用接口给的 accept_description:那是中文的「高清 1080P」,英文界面里
 * 原样露出来。表里多数档名中英通用,只有杜比视界和认不出的档走文案表。取文案不挂在组合上,
 * 数据层建画质清单时也要调它;读的是打包进应用的资源,很快。
 */
fun videoQualityLabel(id: Int): String = when (id) {
    129 -> "HDR Vivid"
    127 -> "8K"
    126 -> getStringBlocking(Res.string.video_quality_dolby_vision)
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
    else -> getStringBlocking(Res.string.video_quality_unknown, id)
}

/**
 * 从 `[base_url] + backup_url` 里挑一个真正能连的地址。
 *
 * B 站给的第一个地址常常是 PCDN 节点:裸 IP、`*.mcdn.bilivideo.*`、或带 `os=mcdn` 参数的
 * upos 地址。这类节点对第三方客户端极不稳定——连得上却不给响应头,一直挂到读超时,表现是
 * "转圈很久然后失败",而不是一个干净的错误码。
 *
 * 规则照 PiliPlus 的 `VideoUtils.getCdnUrl`(lib/utils/video_utils.dart):
 * 优先真正的 upos 镜像,PCDN 一律往后排,一个都没有时才退回原地址(有总比没有强)。
 * 这里**不做 host 改写**——PiliPlus 那边改写是为了让用户选 CDN 厂商,我们没有这个设置,
 * 改写只会把一个能连的地址换成一个没验证过的。
 */
fun preferredStreamUrl(baseUrl: String, backupUrls: List<String>): String {
    val all = (listOf(baseUrl) + backupUrls).filter { it.isNotEmpty() }
    if (all.isEmpty()) return baseUrl
    return all.firstOrNull { !it.isPcdn() } ?: all.first()
}

/** 裸 IP 主机、mcdn 域名、以及 `os=mcdn` 的 upos 地址,都是 PCDN。 */
private fun String.isPcdn(): Boolean {
    val host = substringAfter("://", "").substringBefore('/').substringBefore(':')
    if (host.isEmpty()) return false
    if (host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) return true
    if (host.contains(".mcdn.bilivideo.")) return true
    if (contains("szbdyd.com")) return true
    return contains("os=mcdn")
}
