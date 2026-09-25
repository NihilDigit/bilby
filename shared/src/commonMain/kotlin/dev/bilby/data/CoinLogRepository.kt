package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.getData
import dev.bilby.api.map
import kotlinx.serialization.Serializable

/**
 * 硬币记录的一条。
 *
 * @param timeText 服务端给的是拼好的字符串(`2026-09-25 10:00:00`),不是时间戳,见 notes。
 * @param delta 这一次的增减。按小数收:PiliPlus 读作 `num`(`models_new/coin_log/list.dart`),
 *   而硬币余额本身是小数(nav 的 `money`,notes/space-and-search.md §1.9),按整数收的话一条带
 *   小数的记录会让整页解析失败。
 */
data class CoinLogEntry(
    /** 在这一次响应里的位置。接口不给 id,列表按它作键。 */
    val position: Int,
    val timeText: String,
    val delta: Double,
    val reason: String,
)

/**
 * 自己账号的硬币收支(`x/member/web/coin/log`,notes/space-and-search.md §1.10)。
 *
 * **不分页**:接口一次给出最近一段时间的全部记录,没有页码或游标参数,PiliPlus 同样只请求一次
 * (`http/user.dart` 的 coinLog)。
 */
class CoinLogRepository(private val client: BiliClient) {

    suspend fun load(): BiliResult<List<CoinLogEntry>> =
        client.getData<CoinLogDto>(
            COIN_LOG_URL,
            mapOf("jsonp" to "jsonp", "web_location" to "333.33"),
        ).map { dto ->
            dto.list.mapIndexed { index, item ->
                CoinLogEntry(position = index, timeText = item.time, delta = item.delta, reason = item.reason)
            }
        }

    private companion object {
        const val COIN_LOG_URL = "${BiliConstants.WEB_HOST}/x/member/web/coin/log"
    }
}

@Serializable
private data class CoinLogDto(val list: List<CoinLogItemDto> = emptyList())

@Serializable
private data class CoinLogItemDto(
    val time: String = "",
    val delta: Double = 0.0,
    val reason: String = "",
)
