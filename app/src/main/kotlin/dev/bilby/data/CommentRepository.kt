package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.ReplyItemDto
import dev.bilby.api.dto.ReplyMainResponseDto
import dev.bilby.api.dto.ReplyReplyResponseDto
import dev.bilby.api.dto.ReplyAddResponseDto
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.postAction
import dev.bilby.api.postForm
import dev.bilby.api.toHttpsUrl
import kotlinx.coroutines.flow.first

/** 视频评论排序。数值即请求参数(notes §1.5):未登录传 `mode = ordinal + 2`,已登录直传 `sort`。 */
enum class CommentSort(val ordinal0: Int) {
    TIME(0),
    HOT(1),
}

/**
 * 主楼列表的续页游标。未登录用 `pagination_str` 携带的字符串偏移量,已登录用页码——
 * 两者结构不同,由 sealed class 区分,避免拿字符串硬凑出两种含义(notes §1.1)。
 */
sealed interface CommentCursor {
    data class Offset(val nextOffset: String) : CommentCursor
    data class Page(val pn: Int) : CommentCursor
}

data class CommentItem(
    val rpid: Long,
    val rootRpid: Long, // 楼中楼列表用它请求;主楼本身该值等于 rpid
    val mid: Long,
    val uname: String,
    val avatarUrl: String,
    val isUploader: Boolean,
    val ipLocation: String,
    val level: Int,
    val isSeniorMember: Boolean,
    val ctimeEpochSeconds: Long,
    val likeCount: Int,
    val liked: Boolean,
    val message: String,
    val emotes: Map<String, String>, // 占位符文本(如 "[doge]") -> 图片地址
    val pictureUrls: List<String>,
    val subReplyCount: Int,
    val previewReplies: List<CommentItem>, // 展开前先用它垫着,展开后由 loadSubReplies 的结果替换
)

data class CommentPage(
    val topComment: CommentItem?,
    val items: List<CommentItem>,
    val nextCursor: CommentCursor?,
    val hasMore: Boolean,
    /** 服务端给的评论总数。0 表示这条路径没返回(未登录分支的游标结构里没有总数)。 */
    val total: Int = 0,
)

data class SubReplyPage(val items: List<CommentItem>, val nextPage: Int?, val hasMore: Boolean)

/**
 * 视频评论区完整读写(DESIGN 2.3)。范围只覆盖视频稿件评论(`type=1`,notes §1.1),不是通用
 * 评论组件——动态/专栏评论不在 M3 范围内,所以 `type` 直接写死不对外暴露。
 *
 * 无状态:不持有 oid,每次调用显式传入,方便 ViewModel 按视频实例化/复用同一个 Repository。
 */
class CommentRepository(
    private val client: BiliClient,
    private val settings: SettingsStore,
) {

    suspend fun loadMainPage(
        oid: Long,
        sort: CommentSort,
        cursor: CommentCursor? = null,
    ): BiliResult<CommentPage> {
        val loggedIn = settings.credentials.first().isLoggedIn
        // cursor 类型与当前登录态不匹配时(比如翻页途中登录态发生变化)直接当作首页处理,
        // 不做游标类型转换——两种分页方式本就不可换算。
        return if (loggedIn) {
            loadLoggedInPage(oid, sort, (cursor as? CommentCursor.Page)?.pn ?: 1)
        } else {
            loadAnonymousPage(oid, sort, (cursor as? CommentCursor.Offset)?.nextOffset ?: "")
        }
    }

    private suspend fun loadAnonymousPage(oid: Long, sort: CommentSort, offset: String): BiliResult<CommentPage> {
        val params = mapOf(
            "oid" to oid.toString(),
            "type" to VIDEO_TYPE.toString(),
            "mode" to (sort.ordinal0 + 2).toString(), // 2=时间 3=热度(notes §1.1/§1.5)
            "pagination_str" to """{"offset":"${offset.escapeForJsonString()}"}""",
        )
        // 未登录分支强制不带 cookie、不签 wbi(notes §1.1),BiliClient 默认行为已满足,不需要特殊处理。
        return client.getData<ReplyMainResponseDto>(MAIN_URL_ANON, params).map { dto ->
            val uploaderMid = dto.upper?.mid ?: 0L
            val nextOffset = dto.cursor?.paginationReply?.nextOffset
            val hasMore = dto.cursor?.isEnd == false && !nextOffset.isNullOrEmpty()
            CommentPage(
                topComment = dto.top?.pick()?.toCommentItem(uploaderMid),
                items = dto.replies.orEmpty().map { it.toCommentItem(uploaderMid) },
                nextCursor = if (hasMore) CommentCursor.Offset(nextOffset!!) else null,
                hasMore = hasMore,
                total = dto.cursor?.allCount ?: 0,
            )
        }
    }

    private suspend fun loadLoggedInPage(oid: Long, sort: CommentSort, pn: Int): BiliResult<CommentPage> {
        val params = mapOf(
            "oid" to oid.toString(),
            "type" to VIDEO_TYPE.toString(),
            "sort" to sort.ordinal0.toString(), // 直传,不加偏移(notes §1.5)
            "pn" to pn.toString(),
            "ps" to "20",
        )
        return client.getData<ReplyMainResponseDto>(MAIN_URL_LOGGED, params).map { dto ->
            val uploaderMid = dto.upper?.mid ?: 0L
            val page = dto.page
            val hasMore = page != null && page.num * page.size < page.count
            CommentPage(
                topComment = dto.top?.pick()?.toCommentItem(uploaderMid),
                items = dto.replies.orEmpty().map { it.toCommentItem(uploaderMid) },
                nextCursor = if (hasMore) CommentCursor.Page(pn + 1) else null,
                hasMore = hasMore,
                total = page?.count ?: 0,
            )
        }
    }

    /** 楼中楼,登录/未登录通用,纯页码分页(notes §1.3)。`sort` 服务端写死为 1,不受用户排序设置影响。 */
    suspend fun loadSubReplies(oid: Long, rootRpid: Long, page: Int = 1): BiliResult<SubReplyPage> {
        val credentials = settings.credentials.first()
        val params = buildMap {
            put("oid", oid.toString())
            put("type", VIDEO_TYPE.toString())
            put("root", rootRpid.toString())
            put("pn", page.toString())
            put("sort", "1")
            if (credentials.isLoggedIn) put("csrf", credentials.biliJct) // query 参数,不是表单字段
        }
        return client.getData<ReplyReplyResponseDto>(SUBREPLY_URL, params).map { dto ->
            val uploaderMid = dto.upper?.mid ?: 0L
            val p = dto.page
            val hasMore = p != null && p.num * p.size < p.count
            SubReplyPage(
                items = dto.replies.orEmpty().map { it.toCommentItem(uploaderMid, rootOverride = rootRpid) },
                nextPage = if (hasMore) page + 1 else null,
                hasMore = hasMore,
            )
        }
    }

    /** 发评论,`replyTo` 为空即发主楼一级评论,非空即回复该楼(notes §1.7)。 */
    suspend fun postComment(oid: Long, message: String, replyTo: CommentItem? = null): BiliResult<Unit> {
        val form = buildMap {
            put("type", VIDEO_TYPE.toString())
            put("oid", oid.toString())
            replyTo?.let {
                put("root", it.rootRpid.toString())
                put("parent", it.rpid.toString())
            }
            put("message", message)
        }
        return client.postForm<ReplyAddResponseDto>(ADD_URL, form).map {}
    }

    /** 只允许删自己的评论,UI 层已经按 mid 过滤入口,这里不重复校验——服务端本身也会拒绝越权删除。 */
    suspend fun deleteComment(oid: Long, rpid: Long): BiliResult<Unit> {
        val form = mapOf("type" to VIDEO_TYPE.toString(), "oid" to oid.toString(), "rpid" to rpid.toString())
        return client.postAction(DEL_URL, form)
    }

    suspend fun likeComment(oid: Long, rpid: Long, like: Boolean): BiliResult<Unit> {
        val form = mapOf(
            "type" to VIDEO_TYPE.toString(),
            "oid" to oid.toString(),
            "rpid" to rpid.toString(),
            "action" to if (like) "1" else "0",
        )
        return client.postAction(LIKE_URL, form)
    }

    /**
     * `root`/`rpid` 语义:主楼的 root=0,此时把自己的 rpid 当 rootRpid 用(请求楼中楼要用这个值)。
     * 楼中楼回复(`x/v2/reply/reply` 返回的条目)root 非 0,直接用服务端给的 root,
     * `rootOverride` 是防御性兜底,服务端字段异常时退回调用方已知的 rootRpid。
     */
    private fun ReplyItemDto.toCommentItem(uploaderMid: Long, rootOverride: Long? = null): CommentItem {
        val myMid = member.mid.toLongOrNull() ?: mid
        return CommentItem(
            rpid = rpid,
            rootRpid = root.takeIf { it != 0L } ?: rootOverride ?: rpid,
            mid = myMid,
            uname = member.uname,
            avatarUrl = member.avatar.toHttpsUrl(),
            isUploader = uploaderMid != 0L && myMid == uploaderMid,
            // reply_control 和 content 是兄弟字段,层级已用真实响应确认(见 CommentDto.kt);
            // 能不能拿到 location 这个 key 取决于登录态,未登录抓包里 11 分钟内的新评论也没有它。
            ipLocation = replyControl?.location.orEmpty(),
            level = member.levelInfo.currentLevel,
            isSeniorMember = member.isSeniorMember != 0,
            ctimeEpochSeconds = ctime,
            likeCount = like,
            liked = action == 1,
            message = content.message,
            emotes = content.emote?.mapValues { it.value.url.toHttpsUrl() }.orEmpty(),
            pictureUrls = content.pictures?.map { it.imgSrc.toHttpsUrl() }.orEmpty(),
            subReplyCount = count,
            previewReplies = replies.orEmpty().map { it.toCommentItem(uploaderMid, rootOverride = root) },
        )
    }

    private fun String.escapeForJsonString(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val VIDEO_TYPE = 1 // 视频稿件评论区(notes §1.1),M3 范围只做这一种
        const val MAIN_URL_ANON = "${BiliConstants.WEB_HOST}/x/v2/reply/main"
        const val MAIN_URL_LOGGED = "${BiliConstants.WEB_HOST}/x/v2/reply"
        const val SUBREPLY_URL = "${BiliConstants.WEB_HOST}/x/v2/reply/reply"
        const val ADD_URL = "${BiliConstants.WEB_HOST}/x/v2/reply/add"
        const val DEL_URL = "${BiliConstants.WEB_HOST}/x/v2/reply/del"
        const val LIKE_URL = "${BiliConstants.WEB_HOST}/x/v2/reply/action"
    }
}
