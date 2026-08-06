package com.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `x/v2/reply/main`(未登录)与 `x/v2/reply`(已登录)两条主楼列表接口共用同一套响应体。
 * 分页字段的语义不同,未登录靠 `cursor.pagination_reply.next_offset` 游标续页,已登录靠
 * 外层 `page.num/size/count` 页码续页(notes/comment-toview-history.md §1.1)——两个字段都
 * 设为可空,由 CommentRepository 按登录态择一读取,不在 DTO 层做判断。
 */
@Serializable
data class ReplyMainResponseDto(
    val cursor: ReplyCursorDto? = null,
    val page: ReplyPageDto? = null,
    val upper: ReplyUpperDto? = null,
    val top: ReplyTopDto? = null,
    val replies: List<ReplyItemDto>? = null,
)

@Serializable
data class ReplyCursorDto(
    @SerialName("is_end") val isEnd: Boolean = false,
    /** 评论总数。tab 标题显示的是它,不是已渲染条数。 */
    @SerialName("all_count") val allCount: Int = 0,
    @SerialName("pagination_reply") val paginationReply: ReplyPaginationDto? = null,
)

@Serializable
data class ReplyPaginationDto(
    @SerialName("next_offset") val nextOffset: String = "",
)

/** 已登录主楼列表与楼中楼列表(`x/v2/reply/reply`)共用同一套页码分页结构(notes §1.3)。 */
@Serializable
data class ReplyPageDto(
    val num: Int = 0,
    val size: Int = 0,
    val count: Int = 0,
)

@Serializable
data class ReplyUpperDto(
    val mid: Long = 0L,
)

/**
 * 置顶评论,独立字段,不在 `replies` 数组里(notes §1.4)。三个子字段(管理员置顶/UP主置顶/
 * 投票置顶)结构与普通评论相同,取第一个非空的展示即可,来源不影响渲染。
 */
@Serializable
data class ReplyTopDto(
    val admin: ReplyItemDto? = null,
    val upper: ReplyItemDto? = null,
    val vote: ReplyItemDto? = null,
) {
    fun pick(): ReplyItemDto? = admin ?: upper ?: vote
}

@Serializable
data class ReplyItemDto(
    val rpid: Long = 0L,
    val mid: Long = 0L,
    val root: Long = 0L,
    val parent: Long = 0L,
    val ctime: Long = 0L,
    val like: Int = 0,
    val count: Int = 0, // 楼中楼总数
    val action: Int = 0, // 0未操作 1已赞 2已踩(notes §1.4)
    val member: ReplyMemberDto = ReplyMemberDto(),
    val content: ReplyContentDto = ReplyContentDto(),
    val replies: List<ReplyItemDto>? = null, // 楼中楼预览,通常只有前几条
)

@Serializable
data class ReplyMemberDto(
    val mid: String = "0", // 注意是字符串,跟外层 ReplyItemDto.mid(int)类型不同(notes §1.4)
    val uname: String = "",
    val avatar: String = "",
)

@Serializable
data class ReplyContentDto(
    val message: String = "",
    val pictures: List<ReplyPictureDto>? = null,
    // key 是消息文本里的表情占位符原文(如 "[doge]"),value 带图片地址,渲染时按 key 在
    // message 里做替换/标注即可,不需要额外解析 members/jump_url(notes §1.4 两者都是未强
    // 类型化的 UNSURE 字段,第一版不依赖它们,只靠正则识别 @ 和链接,详见 CommentSection)。
    val emote: Map<String, ReplyEmoteDto>? = null,
    @SerialName("reply_control") val replyControl: ReplyControlDto? = null,
)

@Serializable
data class ReplyEmoteDto(
    val url: String = "",
)

@Serializable
data class ReplyPictureDto(
    @SerialName("img_src") val imgSrc: String = "",
)

/** 每条评论内嵌的 reply_control(小写),跟评论区级别的 control 是两个同名不同类(notes §1.4)。 */
@Serializable
data class ReplyControlDto(
    val location: String = "", // 形如"IP属地：广东"
)

/** `x/v2/reply/reply` 楼中楼响应(notes §1.3)。`upper` 同样带在这次响应里,楼主判断不需要跨请求缓存。 */
@Serializable
data class ReplyReplyResponseDto(
    val page: ReplyPageDto? = null,
    val upper: ReplyUpperDto? = null,
    val replies: List<ReplyItemDto>? = null,
)

/**
 * `x/v2/reply/add` 成功后的响应体。notes §1.7 指出 PiliPlus 那边这个字段经过 protobuf 编码,
 * 具体到纯 REST web 接口是否如此未经抓包验证(UNSURE),所以这里全部字段可空、不依赖其内容——
 * 发送成功与否只看外层 code==0,拿到的 reply 仅用于尽力展示,拿不到就回退成重新拉一次列表。
 */
@Serializable
data class ReplyAddResponseDto(
    val reply: ReplyItemDto? = null,
)
