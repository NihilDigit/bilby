package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/skipSegments` 的响应体(BSponsorBlock,B 站生态用的与 YouTube SponsorBlock
 * 同构的第三方协议,服务端默认 https://www.bsbsb.top)。
 *
 * 字段形状照抄 PiliPlus 的现成实现(读证,不是靠公开文档猜的):
 * - 请求参数 videoID/cid 见 PiliPlus/lib/http/sponsor_block.dart:56-67。
 * - 响应字段(category/actionType/segment/UUID/videoDuration/votes)见
 *   PiliPlus/lib/models_new/sponsor_block/segment_item.dart:3-35。
 * - segment 是"起止秒数"的二元数组,服务端可能给小数(同文件第 28 行 `(e as num)`)。
 * - actionType 可能缺省,PiliPlus 没有显式补默认值,协议约定缺省即 "skip"
 *   (仓库里筛"可跳过片段"时依赖这条,见 SponsorBlockRepository)。
 */
@Serializable
data class SponsorBlockSegmentDto(
    val category: String,
    val actionType: String? = null,
    val segment: List<Double> = emptyList(),
    @SerialName("UUID") val uuid: String = "",
    val videoDuration: Double? = null,
    val votes: Int? = null,
)
