package com.bilby.data

import com.bilby.api.BiliClient
import com.bilby.api.BiliConstants
import com.bilby.api.BiliResult
import com.bilby.api.dto.PlayUrlDto
import com.bilby.api.dto.UgcSeasonDto
import com.bilby.api.dto.VideoDetailDto
import com.bilby.api.getData
import com.bilby.api.map
import com.bilby.api.toHttpsUrl
import com.bilby.player.AUDIO_QUALITY_BEST
import com.bilby.player.DEFAULT_PREFERRED_CODECS
import com.bilby.player.SelectedStreams
import com.bilby.player.selectStreams
import com.bilby.player.videoQualityLabel
import kotlin.random.Random

/** 播放页要显示的一切。分 P 与合集分集都摊平成列表,cid 已经取好,播放器那边不用再挖嵌套。 */
data class VideoDetail(
    val bvid: String,
    val aid: Long,
    /** 默认播放的 cid。取流必需(notes/playurl.md §7)。 */
    val cid: Long,
    val title: String,
    val description: String,
    val coverUrl: String,
    val durationSeconds: Long,
    val publishedAtEpochSeconds: Long,
    val up: VideoUp,
    val stat: VideoStat,
    /** 单 P 视频这里也有一个元素;UI 判断要不要显示分 P 列表看 size > 1。 */
    val pages: List<VideoPart>,
    val seasonTitle: String,
    /** 合集分集,已跨 section 摊平。不属于合集时为空。 */
    val seasonEpisodes: List<SeasonEpisode>,
)

data class VideoUp(val mid: Long, val name: String, val faceUrl: String)

data class VideoStat(
    val view: Long,
    val danmaku: Long,
    val reply: Long,
    val favorite: Long,
    val coin: Long,
    val share: Long,
    val like: Long,
)

data class VideoPart(val cid: Long, val index: Int, val title: String, val durationSeconds: Long)

data class SeasonEpisode(
    val cid: Long,
    val aid: Long,
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val durationSeconds: Long,
)

/** 一次取流的全部结果。 */
data class PlayInfo(
    val streams: SelectedStreams,
    /**
     * 续播位置。**它来自 playurl 响应自身的 last_play_time,不需要另外调
     * `x/player/wbi/v2`**(notes §8.2:那个接口只给 last_play_cid、字幕、看点,没有秒数)。
     * 0 表示没有进度或已看完。
     */
    val lastPlayTimeMillis: Long,
    /** 上次播放到哪一 P;多 P 视频进入播放页时可用它替换默认 cid。0 表示服务端没给。 */
    val lastPlayCid: Long,
    /** 画质菜单的数据源,来自 accept_quality/accept_description,不一定每档都真有流。 */
    val availableQualities: List<QualityOption>,
    val durationMillis: Long,
)

data class QualityOption(val quality: Int, val label: String)

class VideoRepository(private val client: BiliClient) {

    suspend fun getVideoDetail(bvid: String): BiliResult<VideoDetail> =
        client.getData<VideoDetailDto>(VIEW_URL, mapOf("bvid" to bvid)).map { it.toDomain() }

    /**
     * cid 必须由调用方给:同一个 bvid 下多 P/合集各有各的 cid,这里不替调用方决定播哪一 P。
     */
    suspend fun getPlayUrl(
        bvid: String,
        cid: Long,
        preferredQuality: Int = DEFAULT_QUALITY,
        preferredCodecs: List<Int> = DEFAULT_PREFERRED_CODECS,
        preferredAudioQuality: Int = AUDIO_QUALITY_BEST,
    ): BiliResult<PlayInfo> {
        val result =
            client.getData<PlayUrlDto>(PLAY_URL, playUrlParams(bvid, cid, preferredQuality), signed = true)
        return when (result) {
            is BiliResult.Ok -> {
                val dto = result.value
                val streams = dto.dash
                    ?.let { selectStreams(it, preferredQuality, preferredCodecs, preferredAudioQuality) }
                    ?: dto.durlFallback()
                if (streams == null) {
                    BiliResult.Failure(IllegalStateException("playurl 没有可用的流(dash 与 durl 都为空)"))
                } else {
                    BiliResult.Ok(
                        PlayInfo(
                            streams = streams,
                            // 服务端在"没进度"和"已看完"时都可能给 <=0(notes §3),统一归零,
                            // 免得 seek 到负数。
                            lastPlayTimeMillis = dto.lastPlayTime.coerceAtLeast(0) * 1000,
                            lastPlayCid = dto.lastPlayCid,
                            availableQualities = dto.qualityOptions(),
                            durationMillis = dto.dash?.duration?.times(1000L) ?: dto.timeLength,
                        )
                    )
                }
            }
            is BiliResult.ApiError -> result
            is BiliResult.Failure -> result
        }
    }

    /**
     * 参数原样抄自 PiliPlus(notes §1.1,lib/http/video.dart:216-238)。
     *
     * `fnval = 4048` 是**照抄的整数**:PiliPlus 只写了"获取所有格式的视频",仓库里没有位掩码
     * 常量表,**每一位的含义(DASH/HDR/4K/杜比/8K/AV1 各占哪位)UNSURE**,笔记里明确拒绝
     * 凭记忆填。改这个值之前先查 bilibili-API-collect 的 videostream_url.md。
     *
     * gaia_source / isGaiaAvoided / web_location / try_look / dm_* 都是风控相关的埋点参数,
     * PiliPlus 全带上了,**是否必需 UNSURE**(它没做过省略测试)。宁可多带:少带一个字段
     * 换来的 -403 很难和其他失败区分。
     */
    private fun playUrlParams(bvid: String, cid: Long, qn: Int): Map<String, String> = mapOf(
        "bvid" to bvid,
        "cid" to cid.toString(),
        "qn" to qn.toString(),
        "fnval" to "4048",
        "fnver" to "0",
        "fourk" to "1",
        "voice_balance" to "0",
        "gaia_source" to "pre-load",
        "isGaiaAvoided" to "true",
        "web_location" to "1315873",
        // 免登录也能拿 1080P 的开关。
        "try_look" to "1",
        "dm_img_list" to "[]",
        "dm_img_str" to randomBase64Like(16, 64),
        "dm_cover_img_str" to randomBase64Like(32, 128),
        "dm_img_inter" to """{"ds":[],"wh":[0,0,0],"of":[0,0,0]}""",
    )

    private fun VideoDetailDto.toDomain(): VideoDetail = VideoDetail(
        bvid = bvid,
        aid = aid,
        cid = cid,
        title = title,
        description = desc,
        coverUrl = pic.toHttpsUrl(),
        durationSeconds = duration,
        publishedAtEpochSeconds = pubdate,
        up = VideoUp(
            mid = owner?.mid ?: 0,
            name = owner?.name.orEmpty(),
            faceUrl = owner?.face.orEmpty().toHttpsUrl(),
        ),
        stat = VideoStat(
            view = stat?.view ?: 0,
            danmaku = stat?.danmaku ?: 0,
            reply = stat?.reply ?: 0,
            favorite = stat?.favorite ?: 0,
            coin = stat?.coin ?: 0,
            share = stat?.share ?: 0,
            like = stat?.like ?: 0,
        ),
        pages = pages.mapIndexed { i, p ->
            VideoPart(
                cid = p.cid,
                // page 字段理论上从 1 开始,但兜一下:序号错了会让"下一 P"跳错。
                index = if (p.page > 0) p.page else i + 1,
                title = p.part,
                durationSeconds = p.duration,
            )
        },
        seasonTitle = ugcSeason?.title.orEmpty(),
        seasonEpisodes = ugcSeason?.flattenEpisodes().orEmpty(),
    )

    /**
     * UNSURE:分集 cid 的权威路径没被核实过(notes §7 明确标了这条)。PiliPlus 推测是
     * `ugc_season.sections[].episodes[].cid`,但 episode 下还有一个 page 对象也带 cid。
     * 这里取非零的那个,并丢掉两处都是 0 的分集——cid 为 0 传给 playurl 只会拿到一个
     * 难以归因的错误码。真机上验过之后可以把这段收敛成单一路径。
     */
    private fun UgcSeasonDto.flattenEpisodes(): List<SeasonEpisode> =
        sections.flatMap { it.episodes }.mapNotNull { ep ->
            val cid = if (ep.cid != 0L) ep.cid else ep.page?.cid ?: 0L
            if (cid == 0L) return@mapNotNull null
            SeasonEpisode(
                cid = cid,
                aid = ep.aid,
                bvid = ep.bvid,
                title = ep.title,
                coverUrl = ep.arc?.pic.orEmpty().toHttpsUrl(),
                durationSeconds = ep.arc?.duration ?: ep.page?.duration ?: 0L,
            )
        }

    /**
     * accept_quality 与 accept_description 是两个平行数组,靠下标对应。长度不一致时以
     * accept_quality 为准,缺的用本地清晰度表补,不让菜单错位。
     */
    private fun PlayUrlDto.qualityOptions(): List<QualityOption> =
        acceptQuality.mapIndexed { i, q ->
            QualityOption(q, acceptDescription.getOrNull(i) ?: videoQualityLabel(q))
        }

    /**
     * dash 缺席时的老格式兜底(notes §3.2:FLV/MP4,PiliPlus 说"已被淘汰")。这类流音视频
     * 合一,所以 audioUrl 为 null,播放器那边不要走 MergingMediaSource。
     *
     * 多段(durl.size > 1)直接放弃:PiliPlus 靠 mpv 的 edl:// 把多段拼成一条虚拟流,
     * Media3 没有对应能力(要拼得自己做 ConcatenatingMediaSource + 分段时长),
     * 而这条路本身已经罕见,不值得在没有样本的情况下先写。
     */
    private fun PlayUrlDto.durlFallback(): SelectedStreams? {
        val single = durl?.singleOrNull()?.takeIf { it.url.isNotEmpty() } ?: return null
        return SelectedStreams(
            videoUrl = single.url,
            audioUrl = null,
            qualityLabel = videoQualityLabel(quality),
            // 兜底流基本都是老的 flv/mp4,PiliPlus 也是写死 avc1(notes §3.2)。
            codec = "AVC",
            qualityId = quality,
        )
    }

    private fun randomBase64Like(minLength: Int, maxLength: Int): String {
        val length = Random.nextInt(minLength, maxLength + 1)
        return (1..length).map { BASE64_ALPHABET.random() }.joinToString("")
    }

    private companion object {
        const val VIEW_URL = "${BiliConstants.WEB_HOST}/x/web-interface/view"
        const val PLAY_URL = "${BiliConstants.WEB_HOST}/x/player/wbi/playurl"

        /** PiliPlus 的默认 qn(notes §1.1),1080P 高清。 */
        const val DEFAULT_QUALITY = 80

        const val BASE64_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    }
}
