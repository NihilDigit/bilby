package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.CODE_RATE_LIMITED
import dev.bilby.api.DmImgParams
import dev.bilby.api.dto.PlayerV2Dto
import dev.bilby.api.dto.SubtitleBodyDto
import dev.bilby.api.dto.SubtitleTrackDto
import dev.bilby.api.getData
import dev.bilby.api.pathOnly
import dev.bilby.player.SubtitleCue
import dev.bilby.player.SubtitleTrack
import io.ktor.client.call.body
import kotlinx.coroutines.delay

/**
 * AI 字幕。轨道清单和正文是两个不同形状的接口(见两处方法上的注释),这里各自处理,
 * 不假装它们同一套信封。
 *
 * 未登录时 `subtitle_url` 会是空字符串,PiliPlus 在那种情况下退到 danmaku gRPC 去补——
 * Bilby 单账号恒登录(DESIGN 2.6),这条退路不做,拿不到就当没有字幕。
 */
class SubtitleRepository(private val client: BiliClient) {

    /**
     * 一条视频(某一 P)有哪些字幕轨。`x/player/wbi/v2` 要 WBI 签名,响应是标准信封,
     * 走 [getData] 就够。
     *
     * 排序照 PiliPlus `subtitle.dart` 的 `compareTo`:含 `zh` 的排前,同等条件下 AI 的排后。
     */
    suspend fun getTracks(bvid: String, cid: Long): List<SubtitleTrack> =
        fetchTracksWithRetry(bvid, cid).map { it.toDomain() }.sortedWith(TRACK_ORDER)

    /**
     * 命中限流(-412,`BiliResult.CODE_RATE_LIMITED`)按 DESIGN §5 的风控礼仪退避 60s 再续;
     * 其余失败(接口本身报错、网络异常)直接放弃——那些重试也没用,`getData` 已经打过日志。
     *
     * 不无限重试:最多 [MAX_RATE_LIMIT_ATTEMPTS] 次,最坏情况等
     * `(MAX_RATE_LIMIT_ATTEMPTS - 1) * RATE_LIMIT_BACKOFF_MILLIS` 之后放弃,放弃就是"当作
     * 没有字幕"——字幕是锦上添花的功能,不值得为它无休止地撞风控。
     */
    private suspend fun fetchTracksWithRetry(bvid: String, cid: Long): List<SubtitleTrackDto> {
        repeat(MAX_RATE_LIMIT_ATTEMPTS) { attempt ->
            val result = client.getData<PlayerV2Dto>(PLAYER_V2_URL, params = trackParams(bvid, cid), signed = true)
            when (result) {
                is BiliResult.Ok -> return result.value.subtitle?.subtitles.orEmpty()
                is BiliResult.ApiError -> {
                    if (result.code != CODE_RATE_LIMITED) return emptyList()
                    val lastAttempt = attempt == MAX_RATE_LIMIT_ATTEMPTS - 1
                    BiliLog.w(
                        "取字幕轨被限流(-412),第 ${attempt + 1}/$MAX_RATE_LIMIT_ATTEMPTS 次" +
                            if (lastAttempt) ",已到上限,放弃" else ",${RATE_LIMIT_BACKOFF_MILLIS}ms 后重试",
                    )
                    if (!lastAttempt) delay(RATE_LIMIT_BACKOFF_MILLIS)
                }
                // rawGet 抛出的异常已经在 getData 里记过日志。
                is BiliResult.Failure -> return emptyList()
            }
        }
        return emptyList()
    }

    /**
     * `bvid`/`cid` 之外的两个参数是风控埋点,照抄 `VideoRepository.playUrlParams` 那条注释的
     * 判据:PiliPlus 在同族的 `x/player/wbi/playurl` 上全带了这些,是否必需 UNSURE(它没做过
     * 省略测试)。这里补的**是推断,不是实证**——playurl 带这些确认能过,但没有对照实验证明
     * `x/player/wbi/v2` 省略它们也会撞风控;只是按"宁可多带,少带一个字段换来的 -412 很难和
     * 别的失败区分"这条既有判据补齐,不要理解成"必须带才能过"。`fnval`/`fourk`/`try_look`
     * 那几个是取流专用参数,不搬过来。
     */
    private fun trackParams(bvid: String, cid: Long): Map<String, String> = mapOf(
        "bvid" to bvid,
        "cid" to cid.toString(),
        "web_location" to "1315873",
    ) + DmImgParams.next()

    /**
     * 一条字幕轨的正文。**这个响应不是 B 站的标准信封**——没有 `code`/`data` 外层,
     * `getData` 会去剥一层不存在的 `data`,这里改用 `rawGet` 自己反序列化。
     *
     * `subtitleUrl` 是协议相对地址(`//aisubtitle.hdslb.com/...`),PiliPlus
     * `http/video.dart:842` 同样手动补 `https:` 前缀。
     */
    suspend fun getCues(subtitleUrl: String): List<SubtitleCue> {
        if (subtitleUrl.isEmpty()) return emptyList()
        val url = if (subtitleUrl.startsWith("http")) subtitleUrl else "https:$subtitleUrl"
        return runCatching { client.rawGet(url).body<SubtitleBodyDto>() }
            .fold(
                onSuccess = { body ->
                    body.body.map { line ->
                        SubtitleCue(
                            fromMillis = (line.from * 1000).toLong(),
                            toMillis = (line.to * 1000).toLong(),
                            text = line.content.trim(),
                        )
                    }
                },
                onFailure = {
                    BiliLog.w("取字幕正文失败 url=${url.pathOnly()}", it)
                    emptyList()
                },
            )
    }

    private companion object {
        const val PLAYER_V2_URL = "${BiliConstants.WEB_HOST}/x/player/wbi/v2"

        /** thisHasZh != otherHasZh -> 含 zh 的排前;同等条件下 isAi 排后。 */
        val TRACK_ORDER: Comparator<SubtitleTrack> =
            compareByDescending<SubtitleTrack> { it.lan.contains("zh") }.thenBy { it.isAi }

        /** DESIGN §5 的风控礼仪:命中 -412 后退避 60s 再续。 */
        const val RATE_LIMIT_BACKOFF_MILLIS = 60_000L

        /** 初次 + 最多两次重试。不能无限撞:字幕是锦上添花,不值得为它无休止地跟风控耗着。 */
        const val MAX_RATE_LIMIT_ATTEMPTS = 3
    }
}

private fun SubtitleTrackDto.toDomain(): SubtitleTrack {
    val isAi = type == 1
    return SubtitleTrack(
        lan = lan,
        displayName = if (isAi) "$lanDoc（AI）" else lanDoc,
        isAi = isAi,
        subtitleUrl = subtitleUrl,
    )
}
