package dev.bilby.player

import android.media.MediaCodecList
import dev.bilby.BiliLog

/**
 * 本机到底能硬解哪几种编码。
 *
 * **为什么需要这个查询**:Media3 自己不做"硬解优先"的排序。`MediaCodecSelector.DEFAULT` 直接
 * 返回 `MediaCodecList` 的原始顺序,而框架保证这个顺序里好的解码器在前(Android 10 起
 * `c2.qti.*` 一类厂商硬解排在 `c2.android.*` 软解之前);renderer 拿到列表后只按
 * "格式是否被支持"再稳定排一次,不改变硬/软的相对次序。也就是说:**只要某个编码有硬解,
 * Media3 就会用它;一旦没有硬解,Media3 会毫无怨言地退到软解**,既不报错也不提示。
 *
 * 所以真正的杠杆不在播放器配置,在**选流**:B 站同一个视频常常同时发 AVC / HEVC / AV1 三条流,
 * 挑到一条本机没有硬解的,就等于自己给自己选了软解——CPU 占用和耗电是数量级差别,1080P60 的
 * AV1 在没有硬解块的中低端机上还会直接掉帧。[hardwareDecodableCodecIds] 就是给
 * [selectStreams] 用的过滤器。
 */
object DeviceCodecs {

    /** B 站 codecid → MediaCodec 的 MIME。 */
    private val MIME_BY_CODEC_ID = mapOf(
        VideoCodecId.AVC to "video/avc",
        VideoCodecId.HEVC to "video/hevc",
        VideoCodecId.AV1 to "video/av01",
    )

    /** 查询失败的原因,由 [logOnce] 补打日志——见 [query] 里为什么不在那儿打。 */
    @Volatile
    private var queryFailure: Throwable? = null

    /**
     * 本机存在硬件解码器的 codecid 集合。查询要遍历系统全部 codec 信息(几十毫秒),
     * 结果在一次进程生命周期内不会变,所以只算一次。
     */
    val hardwareDecodableCodecIds: Set<Int> by lazy { query() }

    /** 给日志和"解码信息"用的一行说明。 */
    val summary: String
        get() = MIME_BY_CODEC_ID.keys.joinToString(" ") { id ->
            val name = codecIdName(id)
            if (id in hardwareDecodableCodecIds) "$name=硬解" else "$name=软解"
        }

    private fun query(): Set<Int> {
        // REGULAR_CODECS 而不是 ALL_CODECS:后者会把只在特定条件下可用的项也列出来。
        //
        // 失败在这里只记不打:这个属性是 selectStreams 的默认参数,JVM 单元测试里
        // MediaCodecList 根本不存在,在这里调 BiliLog 会连带把 android.util.Log 拖进来,
        // 让一个纯函数的测试挂在日志上。日志推迟到 logOnce(),那只在真机建播放器时调。
        // 空列表和抛异常是同一件事:都表示"这台机器没告诉我们它能解什么"。`codecInfos` 是
        // 平台类型,拿到 null 时不会在这一行炸,而是在后面遍历时炸——所以判断要留在 try 里。
        val infos = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            queryFailure = t
            null
        } ?: run {
            // 查不到就当全都支持:宁可保持现状,也不要因为一次查询失败把用户锁死在 AVC。
            return MIME_BY_CODEC_ID.keys
        }

        val result = mutableSetOf<Int>()
        for ((codecId, mime) in MIME_BY_CODEC_ID) {
            val hit = infos.any { info ->
                !info.isEncoder &&
                    // minSdk 29,isHardwareAccelerated 可以直接用,不需要靠名字前缀猜。
                    info.isHardwareAccelerated &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            if (hit) result += codecId
        }
        return result
    }

    /** debug 构建建播放器时打一次,真机上定位"为什么这台机器选了 AVC"用。 */
    fun logOnce() {
        queryFailure?.let { BiliLog.w("查询本机解码器失败,按全部编码可硬解处理", it) }
        PlayerLog.d("本机硬解能力: $summary")
        if (!PlayerLog.isDebug) return
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
            .getOrNull()
            ?.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it in MIME_BY_CODEC_ID.values }
            }
            ?.forEach { info ->
                val types = info.supportedTypes.filter { it in MIME_BY_CODEC_ID.values }
                PlayerLog.d("  ${info.name} hw=${info.isHardwareAccelerated} $types")
            }
    }

    private fun codecIdName(codecId: Int): String = when (codecId) {
        VideoCodecId.AVC -> "AVC"
        VideoCodecId.HEVC -> "HEVC"
        VideoCodecId.AV1 -> "AV1"
        else -> "codecid$codecId"
    }
}
