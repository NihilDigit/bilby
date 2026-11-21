package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.BvidCodec
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.PlayerV2Dto
import dev.bilby.api.dto.SubtitleBodyDto
import dev.bilby.api.dto.SubtitleTrackDto
import dev.bilby.api.getData
import dev.bilby.api.pathOnly
import dev.bilby.api.toHttpsUrl
import dev.bilby.api.unframeGrpcMessage
import dev.bilby.player.SubtitleCue
import dev.bilby.player.SubtitleTrack
import io.ktor.client.call.body
import io.ktor.http.isSuccess
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * 服务端记着的那一对续播位置:哪一 P、第几毫秒。[cid] 为 0 表示这条视频没有记录。
 *
 * **全站每条视频只有这一对。** 实测确认过(见 [dev.bilby.api.dto.PlayerV2Dto]):给 P1 报过
 * 进度之后再给 P2 报,P1 那份当场消失。所以"续播到第 7 P 的第 3 分钟"是可以表达的,
 * "P1 到第 3 分钟、同时 P7 到第 8 分钟"不行。
 */
data class LastPlayed(val cid: Long, val positionMillis: Long) {
    companion object {
        val NONE = LastPlayed(0, 0)
    }
}

/**
 * 字幕轨与续播位仓库(协议细节与实测见 `notes/subtitle-api.md`)。
 *
 * 轨道清单主走 `x/player/wbi/v2`,与 [lastPlayed] 共享短时归并缓存以减少风控压力;
 * 当 `x/player/wbi/v2` 限流/失败、返回空列表或仅返回空 `subtitle_url` 时,按 PiliPlus
 * (`lib/pages/video/controller.dart:1225-1237`、`lib/grpc/dm.dart:91-122`) 立刻回退到
 * app 端 gRPC `DmView`。正文走无 Cookie 的 `cdnGet` 并统一升级 `https://`。
 */
class SubtitleRepository(
    private val client: BiliClient,
    private val dmViewRetryDelayMillis: Long = DM_VIEW_RETRY_DELAY_MILLIS,
    private val cueRetryDelayMillis: Long = CUE_RETRY_DELAY_MILLIS,
) {

    /**
     * `x/player/wbi/v2` 的短时缓存与并发归并。打开视频时播放服务([lastPlayed])与播放页
     * ([getTracks])几乎同时问同一对 `(bvid, cid)`,归并成一次请求避免放大 `-412`。
     */
    private val playerV2Cache = ExpiringLruCache<Pair<String, Long>, PlayerV2Dto>(
        maxSize = PLAYER_V2_CACHE_SIZE,
        ttlNanos = PLAYER_V2_TTL_NANOS,
    )
    private val playerV2InFlight = ConcurrentHashMap<Pair<String, Long>, CompletableDeferred<BiliResult<PlayerV2Dto>?>>()

    /**
     * 一条视频(某一 P)有哪些字幕轨。
     *
     * 排序照 PiliPlus `subtitle.dart` 的 `compareTo`:含 `zh` 的排前,同等条件下 AI 的排后。
     * 仅保留带有效 `subtitle_url` 的轨道,并将 `//` 与 `http://` 统一规范为 `https://`。
     */
    suspend fun getTracks(bvid: String, cid: Long): List<SubtitleTrack> {
        if (bvid.isBlank() || cid <= 0L) return emptyList()

        // 主路线: x/player/wbi/v2(实测与降级条件见 notes/subtitle-api.md §1)。
        when (val result = fetchPlayerV2(bvid, cid)) {
            is BiliResult.Ok -> {
                val raw = result.value.subtitle?.subtitles.orEmpty()
                val usable = raw.toDomainTracks()
                if (usable.isNotEmpty()) return usable
                if (raw.isNotEmpty()) {
                    BiliLog.w(
                        "x/player/wbi/v2 返回 ${raw.size} 条空链接字幕轨,回退 DmView bvid=$bvid cid=$cid",
                    )
                }
            }
            is BiliResult.ApiError -> {
                BiliLog.w(
                    "x/player/wbi/v2 取字幕轨失败(${result.code}): ${result.message},回退 DmView bvid=$bvid cid=$cid",
                )
            }
            is BiliResult.Failure -> {
                BiliLog.w(
                    "x/player/wbi/v2 取字幕轨异常(${result.cause.message}),回退 DmView bvid=$bvid cid=$cid",
                )
            }
        }

        // 回退路线: app 端 gRPC DmView(见 notes/subtitle-api.md §2)。
        return fetchTracksViaDmView(bvid, cid).toDomainTracks()
    }

    /**
     * 这条视频当前记在哪一 P 的第几毫秒。**是整条视频的那一对,与传进去的 [cid] 无关** ——
     * 问 P1 也会回 P7 的记录(实测,见 [PlayerV2Dto])。cid 为 0 表示没有记录,或者这次没问到。
     *
     * 单独一次请求,所以调用方要先确认值得问 —— 每打开一条视频多一次请求在风控上是有代价的
     * (见 `VideoRepository` 里那段说明)。
     *
     * **问不到返回 null,而不是 [LastPlayed.NONE]。** 这两件事必须分开:前者是"这次没问到"
     * (离线、限流、接口出错),后者是"服务端确认没有记录"。它们曾经都返回 NONE,于是离线播
     * 缓存时,核对进度那一步把「服务端进度基线」写成了 0 —— 而离线正是那个功能存在的场景。
     * 后果有两层:基线被抹掉之后,"云端没动过、本地说了算"这一支再也成立不了,本地看的进度
     * 下次会被更旧的云端值顶掉;而下一次联网时 0 又必然不等于云端的真值,于是弹出一条"别处
     * 已看到"——用户根本没在别处看。
     */
    suspend fun lastPlayed(bvid: String, cid: Long): LastPlayed? {
        if (bvid.isBlank() || cid <= 0L) return null
        val result = fetchPlayerV2(bvid, cid)
        val value = (result as? BiliResult.Ok)?.value ?: return null
        // 夹取不只是防脏数据:看完的稿件服务端记的是哨兵 -1,这里回的是 -1000(实测,
        // notes/playurl.md §8.1.2)。折成 0 之后它和"服务端没有记录"同形,而两者对调用方要的
        // 行为一样——没有可以续播的位置。**哨兵到此为止**,不要再往上层传,那会让每个调用方
        // 各判一遍负数,判据也就有了好几份(见 [dev.bilby.player.cloudProgressWrittenElsewhere])。
        return LastPlayed(value.lastPlayCid, value.lastPlayTimeMillis.coerceAtLeast(0))
    }

    /**
     * 上次播到这条视频的哪一 P。0 表示没有记录**或这次没问到** —— 这条路上两者等价:
     * 问不到就照页面给的那一 P 播,续播接不上只是回到第一 P,不该让起播失败。
     */
    suspend fun lastPlayedCid(bvid: String, cid: Long): Long = lastPlayed(bvid, cid)?.cid ?: 0L

    private suspend fun fetchPlayerV2(bvid: String, cid: Long): BiliResult<PlayerV2Dto> {
        val key = bvid to cid
        while (true) {
            playerV2Cache.get(key)?.let { return BiliResult.Ok(it) }

            val own = CompletableDeferred<BiliResult<PlayerV2Dto>?>()
            val running = playerV2InFlight.putIfAbsent(key, own) ?: own
            if (running !== own) {
                // 等待方直接复用并发同键结果;若发起方被取消(收到 null),回到循环开头由自己发起。
                running.await()?.let { return it }
                continue
            }

            var outcome: BiliResult<PlayerV2Dto>? = null
            try {
                val result = client.getData<PlayerV2Dto>(
                    PLAYER_V2_URL,
                    params = trackParams(bvid, cid),
                    signed = true,
                    // 全 app 唯一一处换 UA。这个接口不接受浏览器 UA,判据与实验见
                    // BiliConstants.NON_BROWSER_USER_AGENT —— 别顺手推广到别的接口,
                    // 那样做过一次,`x/web-interface/card` 当场 -352、搜索直接没结果。
                    userAgent = BiliConstants.NON_BROWSER_USER_AGENT,
                )
                outcome = result
                if (result is BiliResult.Ok) {
                    playerV2Cache.put(key, result.value)
                }
                return result
            } finally {
                // 先摘牌再放人,顺序反过来的话被唤醒的等待方会重新等到这块已经作废的牌上。
                playerV2InFlight.remove(key, own)
                own.complete(outcome)
            }
        }
    }

    /**
     * 通过 app 端 gRPC `DmView` 获取字幕轨(见 `notes/subtitle-api.md` §2)。
     *
     * 不走 WBI、不带 Web Cookie,在 `x/player/wbi/v2` 撞风控或返回空字幕时稳定可用。
     * 对瞬时网络错误或服务端 5xx/可重试 gRPC 状态最多重试一次。
     */
    private suspend fun fetchTracksViaDmView(bvid: String, cid: Long): List<SubtitleTrackDto> {
        val aid = BvidCodec.toAid(bvid)
        if (aid <= 0L) {
            BiliLog.w("DmView 回退跳过: 无法从 bvid 换算 aid bvid=$bvid")
            return emptyList()
        }
        val requestBody = encodeDmViewRequest(aid = aid, cid = cid)
        repeat(MAX_DM_VIEW_ATTEMPTS) { attempt ->
            val lastAttempt = attempt == MAX_DM_VIEW_ATTEMPTS - 1
            val parsed = runCatching {
                val response = client.appGrpcPost(DM_VIEW_GRPC_PATH, requestBody)
                if (!response.status.isSuccess()) {
                    BiliLog.w(
                        "取字幕轨(DmView)被拒 path=$DM_VIEW_GRPC_PATH " +
                            "code=${response.status.value} message=${response.status.description}",
                    )
                    return@runCatching DmViewFetchOutcome.Failed(
                        retryable = response.status.value.isTransientHttpStatus(),
                    )
                }
                val grpcStatus = response.headers["Grpc-Status"]
                if (grpcStatus != GRPC_STATUS_OK) {
                    val grpcMessage = response.headers["Grpc-Message"].orEmpty()
                    BiliLog.w(
                        "取字幕轨(DmView)失败 path=$DM_VIEW_GRPC_PATH " +
                            "code=${grpcStatus ?: "missing"} message=$grpcMessage",
                    )
                    return@runCatching DmViewFetchOutcome.Failed(
                        retryable = grpcStatus in RETRYABLE_GRPC_STATUSES,
                    )
                }
                val protoBytes = unframeGrpcMessage(response.body<ByteArray>())
                DmViewFetchOutcome.Ok(parseDmViewSubtitles(protoBytes))
            }.getOrElse { cause ->
                if (cause is CancellationException) throw cause
                BiliLog.w(
                    "取字幕轨(DmView)异常 path=$DM_VIEW_GRPC_PATH message=${cause.message}",
                    cause,
                )
                DmViewFetchOutcome.Failed(retryable = true)
            }

            when (parsed) {
                is DmViewFetchOutcome.Ok -> return parsed.tracks
                is DmViewFetchOutcome.Failed -> {
                    if (!parsed.retryable || lastAttempt) return emptyList()
                    if (dmViewRetryDelayMillis > 0L) delay(dmViewRetryDelayMillis)
                }
            }
        }
        return emptyList()
    }

    /**
     * **只有 bvid + cid,别再往里加东西。** PiliPlus 调这个接口就发这些
     * (`lib/http/video.dart:910-919`,只有 aid/bvid/cid/season_id/ep_id 加 WBI 签名)。
     *
     * 这里曾按"宁可多带"的判据补过 `web_location` 与四个 `dm_img_*`,理由是同族的
     * `x/player/wbi/playurl` 带了它们能过。那是推断:PiliPlus 的 playurl **确实**带
     * (`video.dart:213-236`),但它在这个接口上一个都不带,而 `dm_img_*` 是网页端塞
     * WebGL/canvas 指纹的位置——我们填的是随机字节,格式对而来源假。结果是全 app 唯一
     * 长期回 -412 的接口,恰好就是唯一多带了这些参数的接口。
     */
    private fun trackParams(bvid: String, cid: Long): Map<String, String> = mapOf(
        "bvid" to bvid,
        "cid" to cid.toString(),
    )

    /**
     * 一条字幕轨的正文(见 `notes/subtitle-api.md` §3)。
     *
     * - 响应不是 B 站标准信封(没有 `code`/`data` 外层),位于 `aisubtitle.hdslb.com` CDN 域;
     * - 走 [BiliClient.cdnGet] 不附带 Cookie/账号 header(对照 PiliPlus `account_mgr.dart:219`);
     * - 统一经 [toHttpsUrl] 将 `//` 和 `http://` 升为 `https://`,避免 Android 禁止明文 HTTP 失败;
     * - 校验 HTTP 状态码并对瞬时 5xx/429/网络异常重试一次,绝不吞掉 [CancellationException]。
     */
    suspend fun getCues(subtitleUrl: String): List<SubtitleCue> {
        val url = normalizeSubtitleUrl(subtitleUrl)
        if (url.isEmpty()) return emptyList()

        repeat(MAX_CUE_ATTEMPTS) { attempt ->
            val lastAttempt = attempt == MAX_CUE_ATTEMPTS - 1
            val outcome = runCatching {
                val response = client.cdnGet(url)
                if (!response.status.isSuccess()) {
                    BiliLog.w(
                        "取字幕正文被拒 url=${url.pathOnly()} " +
                            "code=${response.status.value} message=${response.status.description}",
                    )
                    return@runCatching CueFetchOutcome.Failed(
                        retryable = response.status.value.isTransientHttpStatus(),
                    )
                }
                CueFetchOutcome.Ok(parseSubtitleCues(response.body<SubtitleBodyDto>()))
            }.getOrElse { cause ->
                if (cause is CancellationException) throw cause
                BiliLog.w(
                    "取字幕正文失败 url=${url.pathOnly()} message=${cause.message}",
                    cause,
                )
                CueFetchOutcome.Failed(retryable = true)
            }

            when (outcome) {
                is CueFetchOutcome.Ok -> return outcome.cues
                is CueFetchOutcome.Failed -> {
                    if (!outcome.retryable || lastAttempt) return emptyList()
                    if (cueRetryDelayMillis > 0L) delay(cueRetryDelayMillis)
                }
            }
        }
        return emptyList()
    }

    private sealed interface DmViewFetchOutcome {
        data class Ok(val tracks: List<SubtitleTrackDto>) : DmViewFetchOutcome
        data class Failed(val retryable: Boolean) : DmViewFetchOutcome
    }

    private sealed interface CueFetchOutcome {
        data class Ok(val cues: List<SubtitleCue>) : CueFetchOutcome
        data class Failed(val retryable: Boolean) : CueFetchOutcome
    }

    private companion object {
        const val PLAYER_V2_URL = "${BiliConstants.WEB_HOST}/x/player/wbi/v2"
        const val DM_VIEW_GRPC_PATH = "/bilibili.community.service.dm.v1.DM/DmView"

        const val PLAYER_V2_CACHE_SIZE = 16
        /** 仅覆盖视频打开瞬间 `lastPlayed` 与 `getTracks` 的并发窗口,不影响切回前台复核云端进度。 */
        const val PLAYER_V2_TTL_NANOS = 3_000_000_000L

        const val MAX_DM_VIEW_ATTEMPTS = 2
        const val DM_VIEW_RETRY_DELAY_MILLIS = 500L
        const val GRPC_STATUS_OK = "0"
        val RETRYABLE_GRPC_STATUSES = setOf("4", "8", "13", "14")

        const val MAX_CUE_ATTEMPTS = 2
        const val CUE_RETRY_DELAY_MILLIS = 400L
    }
}

internal fun normalizeSubtitleUrl(raw: String): String {
    val normalized = raw.trim().toHttpsUrl()
    return if (normalized.startsWith("https://")) normalized else ""
}

internal fun List<SubtitleTrackDto>.toDomainTracks(): List<SubtitleTrack> {
    val seenLan = HashSet<String>()
    return mapNotNull { dto ->
        val lan = dto.lan.trim()
        val url = normalizeSubtitleUrl(dto.subtitleUrl)
        if (lan.isEmpty() || url.isEmpty() || !seenLan.add(lan)) return@mapNotNull null
        val isAi = dto.type == 1 || lan.startsWith("ai-")
        val baseLabel = dto.lanDoc.trim().ifEmpty { lan }
        val displayName = if (
            isAi &&
            !baseLabel.contains("AI", ignoreCase = true) &&
            !baseLabel.contains("自动")
        ) {
            "$baseLabel（AI）"
        } else {
            baseLabel
        }
        SubtitleTrack(
            lan = lan,
            displayName = displayName,
            isAi = isAi,
            subtitleUrl = url,
        )
    }.sortedWith(SUBTITLE_TRACK_ORDER)
}

internal fun parseSubtitleCues(body: SubtitleBodyDto): List<SubtitleCue> =
    body.body
        .mapNotNull { line ->
            val text = line.content.trim()
            if (text.isEmpty()) return@mapNotNull null
            val fromMillis = (line.from * 1000).toLong().coerceAtLeast(0L)
            val toMillis = (line.to * 1000).toLong().coerceAtLeast(fromMillis)
            SubtitleCue(fromMillis = fromMillis, toMillis = toMillis, text = text)
        }
        .sortedBy { it.fromMillis }

private fun Int.isTransientHttpStatus(): Boolean = this == 429 || this in 500..599

private val SUBTITLE_TRACK_ORDER: Comparator<SubtitleTrack> =
    compareByDescending<SubtitleTrack> { it.lan.contains("zh") }.thenBy { it.isAi }
