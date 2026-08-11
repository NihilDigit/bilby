package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.DmImgParams
import dev.bilby.api.dto.MemberCardResponseDto
import dev.bilby.api.dto.PlayUrlDto
import dev.bilby.api.dto.UgcSeasonDto
import dev.bilby.api.dto.VideoDetailDto
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.toHttpsUrl
import dev.bilby.player.AUDIO_QUALITY_BEST
import dev.bilby.player.DEFAULT_PREFERRED_CODECS
import dev.bilby.player.SelectedStreams
import dev.bilby.player.selectStreams
import dev.bilby.player.videoQualityLabel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

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
    val staff: List<VideoStaff>,
    val stat: VideoStat,
    /**
     * 这条视频最多收几枚币。
     *
     * **转载稿只收 1 枚**,自制稿收 2 枚。判据照 PiliPlus 的
     * `introduction/ugc/widgets/triple_mixin.dart`:`copyright != 2` 为自制,
     * `reachCoinLimit` 对转载稿的上限就是 1。投满之后再投服务端只会拒绝,而拒绝在界面上
     * 只表现为按钮没反应。
     */
    val maxCoins: Int,
    /** 单 P 视频这里也有一个元素;UI 判断要不要显示分 P 列表看 size > 1。 */
    val pages: List<VideoPart>,
    val seasonTitle: String,
    /** 合集 id。用来打开这个合集的目录页,不属于合集时为 0。 */
    val seasonId: Long,
    /**
     * 合集的归属 mid。**不等同于 [up] 的 mid** —— 联合投稿的合集挂在发起者名下,而这条视频的
     * up 字段给的是本条的作者。目录接口按 mid + season_id 取,拿错了会取到空目录。
     */
    val seasonMid: Long,
    /** 合集分集,已跨 section 摊平。不属于合集时为空。 */
    val seasonEpisodes: List<SeasonEpisode>,
)

data class VideoUp(val mid: Long, val name: String, val faceUrl: String)

data class VideoStaff(val mid: Long, val title: String, val name: String, val faceUrl: String)

/**
 * UP 主的等级信息,来自 `x/web-interface/card`——`getVideoDetail` 的 `owner` 只有
 * mid/name/face,不带等级。独立于 [VideoDetail] 是因为它是独立请求,拿不到不该拖累
 * 详情页其余部分的展示(见 `VideoRepository.getMemberCard` 的说明)。
 */
data class MemberCard(val level: Int, val isSeniorMember: Boolean, val follower: Long)

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

/**
 * 进度条判定为"满"的容差。服务端的 last_play_time 是整秒,时长又有 dash.duration 与
 * timeLength 两个来源、彼此能差一秒,卡太紧会让真正看完的视频从倒数第二秒接着播。
 */
private const val FINISHED_TOLERANCE_MILLIS = 2_000L

/**
 * 这次从哪儿接着播。**云端那份是唯一来源。**
 *
 * 本地曾经另存一份 Room 进度并与云端取较大者,那份是"视频播不动"的根因:全 app 只有一个
 * 播放器,翻到新视频时它还装着上一条,新页一 compose 就按自己的 bvid 把上一条的位置写了
 * 下去。一个 780 秒的视频因此拿到 1587 秒的续播点,seek 落到文件末尾之外,CDN 收下连接却
 * 不给响应头,最后以 SocketTimeout 收场。删掉本地那份之后这类串味无处产生。
 *
 * PiliPlus 也是这个分工:网络播放只认 last_play_time,本地缓存只服务离线下载的文件。对我们
 * 更直接的理由是 last_play_time 和流地址来自同一个 playurl 响应——有流必有进度,本地留一份
 * 换不到任何东西。
 *
 * 两个约束:
 * - `last_play_time` 属于 `last_play_cid` 那一 P。cid 对不上就不能用,否则多 P 视频互相串。
 * - 看完的视频要从头播。服务端在看完时给 -1(映射处已归零),但"停在最后两秒"它照样如实
 *   返回,那种位置续播等于一进来就在片尾。
 */
fun PlayInfo.resumeAtMillisFor(cid: Long): Long {
    if (lastPlayCid != 0L && lastPlayCid != cid) return 0
    if (lastPlayTimeMillis <= 0) return 0
    if (durationMillis > 0 && lastPlayTimeMillis >= durationMillis - FINISHED_TOLERANCE_MILLIS) return 0
    return lastPlayTimeMillis
}

class VideoRepository(private val client: BiliClient) {

    private val detailCache = ExpiringLruCache<String, VideoDetail>(DETAIL_CACHE_SIZE, DETAIL_TTL_NANOS)

    /** 正在飞的详情请求,按 bvid 归并。见 [getVideoDetail]。 */
    private val detailInFlight = ConcurrentHashMap<String, CompletableDeferred<VideoDetail?>>()

    /**
     * 视频详情。**同一条视频的并发请求合并成一次,并在很短的时间内复用结果。**
     *
     * 打开一条视频时这份详情会被问两遍:播放页要标题、统计和分 P,播放服务建队列要
     * `ugc_season` 和 UP 的 mid。两次请求几乎同时发出,谁都不知道对方存在,而它们要的是
     * 同一份响应 —— 多出来的那次纯粹是在风控额度上花钱。
     *
     * TTL 只有 [DETAIL_TTL_NANOS],覆盖的就是这个"几乎同时"的窗口。不敢再长:点赞、投币、
     * 收藏数走的是本地乐观更新、按约定不重拉(CLAUDE.md),缓存留太久会让退出再进来时看到
     * 一个比刚才更旧的数字。
     *
     * 失败不进缓存也不共享:等待方拿到 null 时自己重来一次。把别人的失败当成自己的失败,
     * 会让"页面报错但播放正常"这类组合无从解释。
     */
    suspend fun getVideoDetail(bvid: String): BiliResult<VideoDetail> {
        while (true) {
            detailCache.get(bvid)?.let { return BiliResult.Ok(it) }

            val own = CompletableDeferred<VideoDetail?>()
            val running = detailInFlight.putIfAbsent(bvid, own) ?: own
            if (running !== own) {
                running.await()?.let { return BiliResult.Ok(it) }
                // 那一次被取消或失败了(播放页退出、切集都会取消调用方)。自己重来一遍:
                // 循环回到开头时 in-flight 那一格已经空出来,这次由自己当发起方。
                continue
            }

            var fetched: VideoDetail? = null
            try {
                val result = client.getData<VideoDetailDto>(VIEW_URL, mapOf("bvid" to bvid))
                    .map { it.toDomain() }
                if (result is BiliResult.Ok) {
                    fetched = result.value
                    detailCache.put(bvid, result.value)
                }
                return result
            } finally {
                // 先摘牌再放人,顺序反过来的话被放出来的等待方会重新等到这块已经作废的牌上。
                detailInFlight.remove(bvid, own)
                own.complete(fetched)
            }
        }
    }

    /**
     * UP 主等级(+顺带粉丝数,这一轮不显示,留着给以后用)。公开接口,不需要登录态、
     * 不需要 WBI 签名——实测抓包确认过(见 [dev.bilby.api.dto.MemberCardResponseDto] 的注释)。
     *
     * 每打开一条视频多一次请求,风控上是有代价的(`x/player/wbi/v2` 今天已经因为额外请求
     * 撞过一次 -412),但这是产品决定要的信息,不是技术上顺手加的。调用方(VideoViewModel)
     * 要让这次失败独立于视频详情——查不到等级不该连累 UP 名和关注按钮。
     */
    suspend fun getMemberCard(mid: Long): BiliResult<MemberCard> =
        client.getData<MemberCardResponseDto>(CARD_URL, mapOf("mid" to mid.toString(), "photo" to "false"))
            .map { it.toDomain() }

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
                            // **last_play_time 本来就是毫秒,不要再乘 1000。** PiliPlus 是
                            // `Duration(milliseconds: data.lastPlayTime)`(lib/pages/video/
                            // controller.dart:843)。乘过一次之后每个位置都远超片长,于是被
                            // resumeAtMillisFor 的"看完即归零"那道闸吞掉,表现是心跳照常回传、
                            // 每次进来却都从头开始 —— 一个单位错误伪装成了一个逻辑问题。
                            //
                            // 服务端在"没进度"和"已看完"时都可能给 <=0(notes §3),统一归零,
                            // 免得 seek 到负数。
                            lastPlayTimeMillis = dto.lastPlayTime.coerceAtLeast(0),
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
    ) + DmImgParams.next()

    private fun MemberCardResponseDto.toDomain() = MemberCard(
        level = card.levelInfo.currentLevel,
        isSeniorMember = isSeniorMember == 1,
        follower = follower,
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
        staff = staff.map {
            VideoStaff(mid = it.mid, title = it.title, name = it.name, faceUrl = it.face.toHttpsUrl())
        }.filter { it.mid != 0L && it.name.isNotBlank() },
        stat = VideoStat(
            view = stat?.view ?: 0,
            danmaku = stat?.danmaku ?: 0,
            reply = stat?.reply ?: 0,
            favorite = stat?.favorite ?: 0,
            coin = stat?.coin ?: 0,
            share = stat?.share ?: 0,
            like = stat?.like ?: 0,
        ),
        maxCoins = if (copyright == COPYRIGHT_REPRINT) 1 else VideoActionRepository.MAX_COIN_PER_VIDEO,
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
        seasonId = ugcSeason?.id ?: 0L,
        seasonMid = ugcSeason?.mid ?: 0L,
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

    private companion object {
        /** `copyright` 的转载取值。1 是自制,接口不给时按自制处理(多收的那一枚由服务端拒)。 */
        const val COPYRIGHT_REPRINT = 2

        const val VIEW_URL = "${BiliConstants.WEB_HOST}/x/web-interface/view"
        const val CARD_URL = "${BiliConstants.WEB_HOST}/x/web-interface/card"
        const val PLAY_URL = "${BiliConstants.WEB_HOST}/x/player/wbi/playurl"

        /** PiliPlus 的默认 qn(notes §1.1),1080P 高清。 */
        const val DEFAULT_QUALITY = 80

        /** 详情缓存:够装下一个合集里连着看的十几条,再多就是在留没人会回头的东西。 */
        const val DETAIL_CACHE_SIZE = 16

        /** 30 秒。理由见 [getVideoDetail]:它要盖住的是"同时问两遍"那个窗口,不是省流量。 */
        val DETAIL_TTL_NANOS = 30L * 1_000_000_000L
    }
}

/**
 * 有上限、会过期的小缓存,按访问顺序淘汰。
 *
 * **两条都要有**:只设过期时间的话,连着翻几十条视频就把整批留在内存里等它们自己老去;
 * 只设容量的话,一份过期数据会因为一直被读到而永远留在表里,并且一直被当成新的。
 *
 * 时钟用 [System.nanoTime]:它是单调的,不会被校时拽着走,而这里只做差。
 */
internal class ExpiringLruCache<K : Any, V : Any>(
    private val maxSize: Int,
    private val ttlNanos: Long,
) {
    private class Entry<V>(val value: V, val storedAtNanos: Long)

    private val entries = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>): Boolean =
            size > maxSize
    }

    @Synchronized
    fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (System.nanoTime() - entry.storedAtNanos > ttlNanos) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = Entry(value, System.nanoTime())
    }
}
