package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 评论与私信共用的表情面板响应(`notes/comment-toview-history.md` §1.9)。 */
@Serializable
data class ReplyEmotePanelDto(
    val packages: List<ReplyEmotePackageDto> = emptyList(),
)

@Serializable
data class ReplyEmotePackageDto(
    val id: Long = 0,
    val text: String = "",
    val url: String = "",
    /** `type == 4` 为纯文本颜文字,其余为图片表情包。 */
    val type: Int = 0,
    val emote: List<ReplyEmoteItemDto> = emptyList(),
)

@Serializable
data class ReplyEmoteItemDto(
    val id: Long = 0,
    val text: String = "",
    val url: String = "",
    val meta: ReplyEmoteMetaDto? = null,
)

@Serializable
data class ReplyEmoteMetaDto(
    /** `1` 普通小表情,`2` 装扮大表情。 */
    val size: Int = 1,
    val alias: String = "",
)

/** 直播间表情面板响应(`notes/live.md` §4.1)。 */
@Serializable
data class LiveEmotePanelDto(
    @SerialName("data") val packages: List<LiveEmotePackageDto> = emptyList(),
)

@Serializable
data class LiveEmotePackageDto(
    @SerialName("pkg_id") val pkgId: Long = 0,
    @SerialName("pkg_name") val pkgName: String = "",
    /** `pkg_type == 3` 为官方小表情(插入输入框),其余为房间/粉丝勋章/大航海大贴纸(直接单发)。 */
    @SerialName("pkg_type") val pkgType: Int = 0,
    @SerialName("current_cover") val currentCover: String = "",
    val emoticons: List<LiveEmoteItemDto> = emptyList(),
)

@Serializable
data class LiveEmoteItemDto(
    val emoji: String = "",
    val url: String = "",
    @SerialName("emoticon_unique") val emoticonUnique: String = "",
    /** `1` 可用,`0` 未解锁。 */
    val perm: Int = 1,
    @SerialName("unlock_need_level") val unlockNeedLevel: Int = 0,
    @SerialName("unlock_show_text") val unlockShowText: String = "",
)
