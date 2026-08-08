package dev.bilby.ui.comment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.ui.appendDistinctBy
import dev.bilby.data.CommentCursor
import dev.bilby.data.CommentItem
import dev.bilby.data.CommentRepository
import dev.bilby.data.CommentSort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommentUiState(
    val myMid: Long? = null, // 非空且与某条评论 mid 相同时,该评论可删除
    val topComment: CommentItem? = null,
    val items: List<CommentItem> = emptyList(),
    val sort: CommentSort = CommentSort.HOT, // 服务端默认也是热度(notes §1.5)
    val loading: Boolean = false, // 首屏加载
    val appending: Boolean = false, // 追加下一页
    val hasMore: Boolean = true,
    val error: String? = null,
    val sending: Boolean = false,
    /** 服务端给的评论总数,用于 tab 标题;0 表示还没拿到。 */
    val total: Int = 0,
    // rootRpid -> 展开后的楼中楼全量列表(含继续翻页)。不在这个 map 里的楼层用
    // CommentItem.previewReplies 垫着,展开只在用户点击时才发请求。
    val expandedReplies: Map<Long, ExpandedReplies> = emptyMap(),
)

data class ExpandedReplies(
    val items: List<CommentItem>,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
)

class CommentViewModel(
    private val repository: CommentRepository,
    private val oid: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(CommentUiState(loading = true))
    val state: StateFlow<CommentUiState> = _state.asStateFlow()

    private var cursor: CommentCursor? = null
    private var loadingPage = false

    // 楼中楼展开是按 root 各自独立翻页,分别记自己的下一页页码。
    private val subReplyNextPage = mutableMapOf<Long, Int>()

    init {
        loadFirstPage()
    }

    fun setMyMid(mid: Long?) {
        _state.update { it.copy(myMid = mid) }
    }

    fun loadFirstPage() {
        cursor = null
        subReplyNextPage.clear()
        _state.update {
            CommentUiState(myMid = it.myMid, sort = it.sort, loading = true)
        }
        fetch(append = false)
    }

    fun setSort(sort: CommentSort) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort) }
        loadFirstPage()
    }

    fun loadMore() {
        if (loadingPage || !_state.value.hasMore) return
        _state.update { it.copy(appending = true) }
        fetch(append = true)
    }

    private fun fetch(append: Boolean) {
        loadingPage = true
        viewModelScope.launch {
            when (val result = repository.loadMainPage(oid, _state.value.sort, cursor)) {
                is BiliResult.Ok -> {
                    val page = result.value
                    cursor = page.nextCursor
                    _state.update { current ->
                        current.copy(
                            topComment = if (append) current.topComment else page.topComment,
                            // 登录态走 `x/v2/reply` 的 pn 分页(见 CommentRepository),服务端每页
                            // 按当时的热度分重排整个列表,翻页期间有人点赞或发新评论,同一条就会
                            // 同时出现在上一页尾和下一页首。
                            items = if (append) {
                                current.items.appendDistinctBy(page.items) { it.rpid }
                            } else {
                                page.items.distinctBy { it.rpid }
                            },
                            loading = false,
                            appending = false,
                            hasMore = page.hasMore,
                            total = if (page.total > 0) page.total else current.total,
                            error = null,
                        )
                    }
                }

                is BiliResult.ApiError -> setError("${result.message}(${result.code})")
                is BiliResult.Failure -> setError(result.cause.message ?: "网络错误")
            }
            loadingPage = false
        }
    }

    /** 首次展开拉第一页;已展开状态下再次调用视为"加载更多楼中楼"。 */
    fun expandReplies(rootId: Long) {
        val existing = _state.value.expandedReplies[rootId]
        if (existing?.loadingMore == true) return
        val page = subReplyNextPage[rootId] ?: 1

        _state.update { current ->
            val updated = existing?.copy(loadingMore = true) ?: ExpandedReplies(items = emptyList(), loadingMore = true)
            current.copy(expandedReplies = current.expandedReplies + (rootId to updated))
        }

        viewModelScope.launch {
            when (val result = repository.loadSubReplies(oid, rootId, page)) {
                is BiliResult.Ok -> {
                    val sub = result.value
                    subReplyNextPage[rootId] = sub.nextPage ?: page
                    _state.update { current ->
                        val prevItems = current.expandedReplies[rootId]?.items.orEmpty()
                        val merged = ExpandedReplies(
                            items = prevItems + sub.items,
                            loadingMore = false,
                            hasMore = sub.hasMore,
                        )
                        current.copy(expandedReplies = current.expandedReplies + (rootId to merged))
                    }
                }

                is BiliResult.ApiError -> setError("${result.message}(${result.code})")
                is BiliResult.Failure -> setError(result.cause.message ?: "网络错误")
            }
        }
    }

    /** `replyTo == null` 发一级评论,否则回复该楼(取其 rpid 作为 parent)。 */
    fun send(text: String, replyTo: Long?) {
        if (text.isBlank() || _state.value.sending) return
        val target = replyTo?.let { rpid -> findComment(rpid) }
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            when (val result = repository.postComment(oid, text, target)) {
                is BiliResult.Ok -> {
                    _state.update { it.copy(sending = false) }
                    // notes §1.7:发送成功后拿不到可靠的新评论结构,不做本地拼接,直接重拉受影响的列表。
                    if (target == null) loadFirstPage() else expandRepliesFresh(target.rootRpid)
                }

                is BiliResult.ApiError -> {
                    _state.update { it.copy(sending = false, error = "${result.message}(${result.code})") }
                }

                is BiliResult.Failure -> {
                    _state.update { it.copy(sending = false, error = result.cause.message ?: "网络错误") }
                }
            }
        }
    }

    fun like(rpid: Long) {
        val comment = findComment(rpid) ?: return
        val nextLiked = !comment.liked
        applyToComment(rpid) { it.copy(liked = nextLiked, likeCount = it.likeCount + if (nextLiked) 1 else -1) }
        viewModelScope.launch {
            val result = repository.likeComment(oid, rpid, nextLiked)
            if (result is BiliResult.ApiError || result is BiliResult.Failure) {
                // 乐观更新失败要退回去,不然点赞状态和服务端永久不一致。
                applyToComment(rpid) { it.copy(liked = !nextLiked, likeCount = it.likeCount + if (nextLiked) -1 else 1) }
            }
        }
    }

    fun delete(rpid: Long) {
        val comment = findComment(rpid) ?: return
        _state.update { current ->
            current.copy(
                items = current.items.filterNot { it.rpid == rpid },
                topComment = current.topComment?.takeUnless { it.rpid == rpid },
                expandedReplies = current.expandedReplies.mapValues { (_, v) ->
                    v.copy(items = v.items.filterNot { it.rpid == rpid })
                },
            )
        }
        viewModelScope.launch {
            val result = repository.deleteComment(oid, rpid)
            if (result is BiliResult.ApiError) setError("${result.message}(${result.code})")
            if (result is BiliResult.Failure) setError(result.cause.message ?: "网络错误")
            if (comment.rootRpid == rpid) subReplyNextPage.remove(rpid)
        }
    }

    private fun expandRepliesFresh(rootId: Long) {
        subReplyNextPage.remove(rootId)
        _state.update { it.copy(expandedReplies = it.expandedReplies - rootId) }
        expandReplies(rootId)
    }

    private fun findComment(rpid: Long): CommentItem? {
        val state = _state.value
        state.topComment?.let { if (it.rpid == rpid) return it }
        state.items.find { it.rpid == rpid }?.let { return it }
        state.items.forEach { top -> top.previewReplies.find { it.rpid == rpid }?.let { return it } }
        state.expandedReplies.values.forEach { expanded ->
            expanded.items.find { it.rpid == rpid }?.let { return it }
        }
        return null
    }

    private inline fun applyToComment(rpid: Long, transform: (CommentItem) -> CommentItem) {
        _state.update { current ->
            current.copy(
                topComment = current.topComment?.let { if (it.rpid == rpid) transform(it) else it },
                items = current.items.map { top ->
                    when {
                        top.rpid == rpid -> transform(top)
                        else -> top.copy(previewReplies = top.previewReplies.map {
                            if (it.rpid == rpid) transform(it) else it
                        })
                    }
                },
                expandedReplies = current.expandedReplies.mapValues { (_, v) ->
                    v.copy(items = v.items.map { if (it.rpid == rpid) transform(it) else it })
                },
            )
        }
    }

    private fun setError(message: String) {
        _state.update { it.copy(loading = false, appending = false, error = message) }
    }
}
