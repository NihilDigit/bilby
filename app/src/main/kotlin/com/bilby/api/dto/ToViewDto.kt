package com.bilby.api.dto

import kotlinx.serialization.Serializable

/**
 * 稍后再看 `x/v2/history/toview/web` 的响应体,依据 notes/comment-toview-history.md
 * 第 2.1、2.3 节。
 */
@Serializable
data class ToViewResponseDto(
    val count: Int = 0,
    val list: List<ToViewItemDto> = emptyList(),
)

@Serializable
data class ToViewItemDto(
    val aid: Long = 0L,
    val bvid: String = "",
    val pic: String = "",
    val title: String = "",
    /** 数值秒,不是 "mm:ss" 字符串。 */
    val duration: Long = 0L,
    val pubdate: Long = 0L,
    /** 观看进度,单位秒;-1 表示已看完(与历史记录同规则,notes 2.3 节标了 UNSURE,按同语义处理)。 */
    val progress: Long = -1L,
    val owner: ToViewOwnerDto = ToViewOwnerDto(),
)

@Serializable
data class ToViewOwnerDto(val mid: Long = 0L, val name: String = "")

/**
 * 写操作(add/del/clear)成功时 `data` 字段通常是 null,不能走 BiliClient.getData/postForm
 * 那套"data 非空才算 Ok"的判定(会把成功也判成 ApiError),所以只取外层信封的 code/message。
 */
@Serializable
data class ToViewActionEnvelopeDto(
    val code: Int = 0,
    val message: String = "",
)
