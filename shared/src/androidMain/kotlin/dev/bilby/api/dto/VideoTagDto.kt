package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /x/web-interface/view/detail/tag` 的响应体。data 节点直接是数组,不套对象。
 * 端点行为与 tag_type 的已知取值见 notes/video-tags.md。
 */
@Serializable
data class VideoTagDto(
    @SerialName("tag_id") val tagId: Long = 0,
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("tag_type") val tagType: String = "",
)
