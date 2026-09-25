package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.dto.DanmakusChannelDataDto
import dev.bilby.api.dto.DanmakusEnvelopeDto
import dev.bilby.api.dto.DanmakusItemDto
import dev.bilby.api.dto.DanmakusLivePageDto
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

/**
 * 本场早前的一条醒目留言。**没有 id**,身份就是这三样(见 [DanmakusItemDto])。
 *
 * @param sendDateMillis 服务端记录的发送时刻。与 B 站那侧去重时按秒对齐。
 */
data class ArchivedSuperChat(
    val senderMid: Long,
    val senderName: String,
    val message: String,
    val priceYuan: Int,
    val sendDateMillis: Long,
)

/**
 * danmakus.com(ukamnads)的本场醒目留言。事实全部记在 `notes/danmakus-com.md`。
 *
 * **不走 [dev.bilby.api.BiliClient],也不带任何 B 站凭据。** 这是发给站外服务器的请求,
 * BiliClient 会自动带上 Cookie/Referer/WBI 签名,那一套第三方既不认也不需要 —— 带过去
 * 只是把登录态交给一个不受 B 站控制的第三方。同一条判断见 [SponsorBlockRepository]。
 *
 * 手动 `bodyAsText` + `decodeFromString`,不靠共享 HttpClient 上的 ContentNegotiation:
 * 第三方的 content-type 标不标准不由我们决定,认不出时 ContentNegotiation 直接抛。
 *
 * 任何一步失败(网络、非 2xx、解析、这个主播没被收录、这一场没在录)一律返回空列表并留一行
 * 日志:这一节是锦上添花,它整个拿不到时界面上就是没有"本场早前"这一节,和这场本来就没有
 * 早前的留言看起来一样。
 */
class DanmakusRepository(
    private val httpClient: HttpClient,
    private val json: Json,
) {

    /**
     * 这位主播**正在进行**的那一场里的全部醒目留言。
     *
     * 两步:先按 mid 查频道拿本场的 `liveId`,再按 `liveId` 拉 type=3 的消息。查询键是主播的
     * mid,不是房间号(notes 第 1 节)。
     */
    suspend fun currentSessionSuperChats(anchorMid: Long): List<ArchivedSuperChat> = runCatching {
        if (anchorMid <= 0L) return emptyList()
        val liveId = currentLiveId(anchorMid) ?: return emptyList()
        val page = get<DanmakusLivePageDto>(LIVE_PATH) {
            parameter("liveId", liveId)
            parameter("includeDanmakus", true)
            parameter("type", TYPE_SUPER_CHAT)
        } ?: return emptyList()

        page.data?.danmakus.orEmpty()
            .filter { it.type == TYPE_SUPER_CHAT && it.message.isNotBlank() }
            // **一份录制里就有重复**:实测同一条 SC 在同一个版本里出现两次(2026-08-24,
            // 100 条里 50 条各两份)。按"谁、哪一秒、说了什么"收掉,和跨来源去重同一个键。
            .distinctBy { Triple(it.uid, it.sendDate / 1000, it.message) }
            .map {
                ArchivedSuperChat(
                    senderMid = it.uid,
                    senderName = it.uname,
                    message = it.message,
                    // 价格是元,取整:界面上的档位和金额都按整元说话(SuperChatPrice)。
                    priceYuan = it.price.toInt(),
                    sendDateMillis = it.sendDate,
                )
            }
    }.onFailure { BiliLog.w("danmakus 取本场醒目留言失败 mid=$anchorMid", it) }.getOrDefault(emptyList())

    /**
     * 正在进行的那一场里**收得最多的那一份录制**的 liveId。
     *
     * **一场有多个录制版本,取哪一份是这段代码的全部要点。** 顶层那个 liveId 是本站官方录的,
     * 进行中的场次里它常常一条都没有;真正有数据的是贡献者的版本,每个版本一个自己的 liveId
     * (只差几位十六进制)。按 `rangeDanmakusCount` 取最多的那份 —— 不同版本的录制端接入时间
     * 不同,收得最多的通常就是接得最早的那个。
     *
     * 没有正在进行的场次时返回 null;往期场次不做,是产品范围。
     */
    private suspend fun currentLiveId(anchorMid: Long): String? {
        val channel = get<DanmakusChannelDataDto>(CHANNEL_PATH) {
            parameter("uId", anchorMid)
            parameter("includeLive", true)
        } ?: return null
        val live = channel.lives
            .filter { !it.isFinish && it.liveId.isNotBlank() }
            .maxByOrNull { it.startDate }
        if (live == null) {
            // 收录了这位主播,但这一场没在录(录制端没连上,或这一场刚开始)。和"没收录这位
            // 主播"是两回事,后者在上面那层已经打过一行业务失败了。
            BiliLog.d("danmakus 没有进行中的场次 mid=$anchorMid,共 ${channel.lives.size} 场")
            return null
        }
        val best = live.versions
            .filter { !it.live?.liveId.isNullOrBlank() }
            .maxByOrNull { it.rangeDanmakusCount }
        BiliLog.d("danmakus 本场 ${live.versions.size} 份录制,最多的一份 ${best?.rangeDanmakusCount ?: 0} 条")
        return best?.live?.liveId ?: live.liveId
    }

    private suspend inline fun <reified T> get(
        path: String,
        crossinline params: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): T? {
        val response = httpClient.get("$HOST$path") { params() }
        if (!response.status.isSuccess()) {
            BiliLog.w("danmakus $path 返回 ${response.status.value}")
            return null
        }
        val envelope: DanmakusEnvelopeDto<T> = json.decodeFromString(response.bodyAsText())
        if (envelope.code != CODE_OK) {
            BiliLog.w("danmakus $path 业务失败(${envelope.code}): ${envelope.message}")
            return null
        }
        return envelope.data
    }

    private companion object {
        /**
         * **API 域是 ukamnads.icu,不是 danmakus.com** —— 主域对非浏览器 UA 回 403
         * (notes 第 0 节)。
         */
        const val HOST = "https://ukamnads.icu"
        const val CHANNEL_PATH = "/api/v2/channel"
        const val LIVE_PATH = "/api/v2/live"

        /** 消息类型:3 是醒目留言。 */
        const val TYPE_SUPER_CHAT = 3

        const val CODE_OK = 200
    }
}
