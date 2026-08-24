package dev.bilby.ui.comment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.ui.appendDistinctBy
import dev.bilby.data.CommentCursor
import dev.bilby.data.CommentItem
import dev.bilby.data.CommentRepository
import dev.bilby.data.VIDEO_COMMENT_TYPE
import dev.bilby.data.CommentSort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class CommentUiState(
    val myMid: Long? = null, // 非空且与某条评论 mid 相同时,该评论可删除
    val topComment: CommentItem? = null,
    val items: List<CommentItem> = emptyList(),
    val sort: CommentSort = CommentSort.HOT, // 服务端默认也是热度(notes §1.5)
    val loading: Boolean = false, // 首屏加载
    val appending: Boolean = false, // 追加下一页
    /**
     * 下拉刷新中。**与 [loading] 分开**:首屏是一片空白加一个转圈,刷新是"列表还在、顶上转圈"。
     * 用 loading 顶替的话,每次下拉都会把已经读到的评论整片清掉再重画。
     */
    val refreshing: Boolean = false,
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
    /**
     * 这一楼展开失败的原因。**不写进 [CommentUiState.error]**:那一份画在整个评论区的页脚上,
     * 而展开按钮可能在十几屏之外,报在那里等于没报 —— 点下去看起来什么都没发生。
     */
    val error: String? = null,
)

class CommentViewModel(
    private val repository: CommentRepository,
    initialOid: Long,
    /**
     * 评论区所属的内容类型,默认视频稿件。动态那侧由服务端在 `basic.comment_type` 里给,
     * 客户端不推导(notes/dynamic-cards.md)。
     *
     * 与 [oid] 不同,它**跟着这一个 ViewModel 不变**:换视频走 [switchTo],而那始终是同一
     * 种类型;动态详情页是另一个页面、另一个实例。
     */
    private val type: Int = VIDEO_COMMENT_TYPE,
) : ViewModel() {

    /**
     * 当前评论区挂在哪条视频(aid)上。
     *
     * **一个播放页只有一个 CommentViewModel,换视频是 [switchTo]。** 原先靠
     * `viewModel(key = "comment-$aid")` 选实例,而切集不进 backstack,NavEntry 的
     * ViewModelStore 到整页出栈才清 —— 连播走一条就攒一个,和 [dev.bilby.ui.video.VideoViewModel]
     * 是同一个坑的另一个入口。Compose 的 key 决定选哪个实例,不负责删掉旧 key 对应的那个。
     */
    var oid: Long = initialOid
        private set

    /**
     * 换一条视频的评论区。**幂等**,调用方可以每次重组无脑喊一遍。
     *
     * 不需要另做取消和清状态:[loadFirstPage] 本来就要递增 generation、取消主 Job 和全部
     * 展开 Job、清游标和列表 —— 换 oid 要做的事跟切排序完全一样,复用它而不是再写一份。
     */
    fun switchTo(target: Long) {
        if (target == oid) return
        oid = target
        loadFirstPage()
    }

    private val _state = MutableStateFlow(CommentUiState(loading = true))
    val state: StateFlow<CommentUiState> = _state.asStateFlow()

    private var cursor: CommentCursor? = null
    private var loadingPage = false

    // 楼中楼展开是按 root 各自独立翻页,分别记自己的下一页页码。
    private val subReplyNextPage = mutableMapOf<Long, Int>()

    /**
     * 主列表(排序切换、首屏重载、append)共用一代 generation。切排序或重载都要让
     * 一条还在飞的旧响应作废 —— 否则它落地时写的 `cursor` 和拼接出来的 `items` 都是
     * 上一个排序/上一轮的,跟当前显示对不上(性能计划 7.2)。展开楼中楼是各 root 独立
     * 翻页,不共用这份 generation,单独用 [expandJobs] 按 root 取消旧请求。
     */
    private var generation = 0
    private var fetchJob: Job? = null
    private val expandJobs = mutableMapOf<Long, Job>()

    init {
        loadFirstPage()
    }

    fun setMyMid(mid: Long?) {
        _state.update { it.copy(myMid = mid) }
    }

    fun loadFirstPage() {
        generation++
        fetchJob?.cancel()
        expandJobs.values.forEach { it.cancel() }
        expandJobs.clear()
        cursor = null
        subReplyNextPage.clear()
        _state.update {
            CommentUiState(myMid = it.myMid, sort = it.sort, loading = true)
        }
        fetch(append = false)
    }

    /**
     * 下拉刷新。和 [loadFirstPage] 的区别只在**屏上留不留旧列表**:那条是"这一页从头来过"
     * (换排序、换视频),会连着置 loading 清空;这条保留已经读到的评论,只在顶上转圈,
     * 第一页回来了再整片换掉。
     *
     * 楼中楼的展开状态跟着清:它们挂在具体的 rpid 上,而刷新之后那些楼层可能已经不在第一页了。
     */
    fun refresh() {
        generation++
        fetchJob?.cancel()
        expandJobs.values.forEach { it.cancel() }
        expandJobs.clear()
        cursor = null
        subReplyNextPage.clear()
        _state.update { it.copy(refreshing = true, error = null, expandedReplies = emptyMap()) }
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
        val gen = generation
        // oid 捕获成局部量:协程体里读那个 var 的话,[switchTo] 恰好发生在 launch 之后、
        // 协程真正开跑之前时,会拿新 oid 发一次注定被 generation 判废的请求。
        val target = oid
        fetchJob = viewModelScope.launch {
            try {
                when (val result = repository.loadMainPage(target, _state.value.sort, cursor, type)) {
                    is BiliResult.Ok -> {
                        // 迟到的响应不能碰 cursor:排序切换/重载已经把它清成 null,
                        // 这里再写回去,下一次 loadMore 就会拿旧排序的游标去翻页。
                        if (gen != generation) return@launch
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
                                hasMore = page.hasMore,
                                total = if (page.total > 0) page.total else current.total,
                                error = null,
                            )
                        }
                    }

                    is BiliResult.ApiError -> if (gen == generation) setError("${result.message}(${result.code})")
                    is BiliResult.Failure -> if (gen == generation) setError(result.cause.message ?: "网络错误")
                }
            } finally {
                // loadingPage 只按当前 generation 释放:loadFirstPage 取消旧 Job 后会
                // 立刻发起新一轮请求并把它重新置 true,旧 Job 的 finally 迟到执行时
                // 不能把这个刚置位的 true 又清掉。
                if (gen == generation) {
                    loadingPage = false
                    _state.update { it.copy(loading = false, appending = false, refreshing = false) }
                }
            }
        }
    }

    /**
     * 首次展开拉第一页;已展开状态下再次调用视为"加载更多楼中楼"。同一 root 内天然互斥
     * (loadingMore 挡重复点击),这里额外用 [expandJobs] 记 Job:排序切换或首屏重载时
     * (见 [loadFirstPage])要能主动取消掉还在飞的旧展开请求,不是等它自己落地再靠
     * generation 丢弃 —— expandedReplies 那时已经被清空,没必要再让请求空跑。
     */
    fun expandReplies(rootId: Long) {
        val existing = _state.value.expandedReplies[rootId]
        if (existing?.loadingMore == true) return
        val page = subReplyNextPage[rootId] ?: 1
        val gen = generation

        // **先取消旧的,再置 loadingMore。** 反过来的话,旧 Job 的 finally 会在取消时跑,
        // 把刚刚置上的 loadingMore 又抹掉,于是这一次的转圈不显示,而重复点击的守卫也失效。
        expandJobs[rootId]?.cancel()
        _state.update { current ->
            val updated = existing?.copy(loadingMore = true, error = null)
                ?: ExpandedReplies(items = emptyList(), loadingMore = true)
            current.copy(expandedReplies = current.expandedReplies + (rootId to updated))
        }

        expandJobs[rootId] = viewModelScope.launch {
            try {
                when (val result = repository.loadSubReplies(oid, rootId, page, type)) {
                    is BiliResult.Ok -> {
                        if (gen != generation) return@launch
                        val sub = result.value
                        subReplyNextPage[rootId] = sub.nextPage ?: page
                        _state.update { current ->
                            // **展开的第一页要以预览楼层打底。** 主楼自带的 previewReplies 是服务端
                            // 挑的最热几条,而 `x/v2/reply/reply` 只按时间排(sort 参数传 0/1/2/3
                            // 返回完全相同,服务端忽略它),第 1 页多半不含那几条 —— 直接换掉的话,
                            // 用户正在读的那条热评在点下"展开"的瞬间消失,或者被拆到别人中间去,
                            // 表现就是"展开有时候会消失有时候重排"。
                            //
                            // appendDistinctBy 同时兜住重复:预览楼层真的落在这一页里时只留一份,
                            // 服务端不给 nextPage 而同一页被再请求一次时也不会拼出两遍。
                            val base = current.expandedReplies[rootId]?.items
                                ?.takeIf { it.isNotEmpty() }
                                ?: current.rootComment(rootId)?.previewReplies.orEmpty()
                            val merged = ExpandedReplies(
                                items = base.appendDistinctBy(sub.items) { it.rpid },
                                loadingMore = false,
                                hasMore = sub.hasMore,
                            )
                            current.copy(expandedReplies = current.expandedReplies + (rootId to merged))
                        }
                    }

                    is BiliResult.ApiError ->
                        if (gen == generation) setExpandError(rootId, "${result.message}(${result.code})")

                    is BiliResult.Failure ->
                        if (gen == generation) setExpandError(rootId, result.cause.message ?: "网络错误")
                }
            } finally {
                // 失败或取消也要把 loadingMore 收回去,不然这个 root 的展开按钮永远转圈
                // (原逻辑只在成功分支清过,失败路径漏了)。
                if (gen == generation) {
                    _state.update { current ->
                        val entry = current.expandedReplies[rootId] ?: return@update current
                        current.copy(expandedReplies = current.expandedReplies + (rootId to entry.copy(loadingMore = false)))
                    }
                }
            }
        }
    }

    /** `replyTo == null` 发一级评论,否则回复该楼(取其 rpid 作为 parent)。 */
    fun send(text: String, replyTo: Long?) {
        if (text.isBlank() || _state.value.sending) return
        val target = replyTo?.let { rpid -> findComment(rpid) }
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            when (val result = repository.postComment(oid, text, target, type)) {
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
            val result = repository.likeComment(oid, rpid, nextLiked, type)
            if (result is BiliResult.ApiError || result is BiliResult.Failure) {
                // 乐观更新失败要退回去,不然点赞状态和服务端永久不一致。
                applyToComment(rpid) { it.copy(liked = !nextLiked, likeCount = it.likeCount + if (nextLiked) -1 else 1) }
            }
        }
    }

    fun delete(rpid: Long) {
        val comment = findComment(rpid) ?: return
        // 位置可能定位不到(只在楼中楼预览里的那种),那时仍然发请求,只是失败之后没有
        // 原位可放回 —— 它本来也不在乐观移除的三处之内。
        val slot = locate(rpid)
        _state.update { current ->
            current.copy(
                items = current.items.filterNot { it.rpid == rpid }.map { it.withoutPreview(rpid) },
                topComment = current.topComment?.takeUnless { it.rpid == rpid }?.withoutPreview(rpid),
                expandedReplies = current.expandedReplies.mapValues { (_, v) ->
                    v.copy(items = v.items.filterNot { it.rpid == rpid })
                },
            )
        }
        viewModelScope.launch {
            when (val result = repository.deleteComment(oid, rpid, type)) {
                // 删掉的是主楼,它那一组展开结果跟着走:留着的话只是一份没人再读的副本,
                // 而这条主楼要是又被发了一遍(同 rpid 不会,但刷新后同一楼可能重新出现),
                // 展开按钮会直接亮出上一轮的内容。失败路径不清,那边要按原样放回去。
                is BiliResult.Ok -> if (comment.rootRpid == rpid) {
                    subReplyNextPage.remove(rpid)
                    _state.update { it.copy(expandedReplies = it.expandedReplies - rpid) }
                }

                // **失败要把这一条放回原位。** 乐观移除之后不管结果,用户看到的就是"评论没了",
                // 而它还在服务端;下次进来又出现,中间这段时间没有任何办法知道哪个是真的。
                is BiliResult.ApiError -> {
                    slot?.let { restore(comment, it) }
                    setError("删除失败:${result.message}(${result.code})")
                }

                is BiliResult.Failure -> {
                    slot?.let { restore(comment, it) }
                    setError("删除失败:${result.cause.message ?: "网络错误"}")
                }
            }
        }
    }

    /**
     * 一条评论在列表里的位置。删除失败要放回**原位**,不是追加到末尾 —— 一条热评回到列表
     * 尾巴上,和"删掉了又冒出来一条新的"读起来是一回事。
     */
    private sealed interface CommentSlot {
        data object Top : CommentSlot
        data class Main(val index: Int) : CommentSlot
        data class Sub(val rootId: Long, val index: Int) : CommentSlot

        /** 未展开时显示的那两三条预览楼层。**同一条回复可能同时在这里和 [Sub] 里**,见 [locate]。 */
        data class Preview(val rootId: Long, val index: Int) : CommentSlot
    }

    /**
     * 一条评论现在画在哪儿。
     *
     * **展开结果优先于预览层。** 展开之后两处都有这条回复,而 [restore] 只能放回一处 ——
     * 放回预览层的话,屏上仍然是展开结果那一份,恢复看起来没有发生。
     */
    private fun locate(rpid: Long): CommentSlot? {
        val state = _state.value
        if (state.topComment?.rpid == rpid) return CommentSlot.Top
        state.items.indexOfFirst { it.rpid == rpid }.takeIf { it >= 0 }?.let { return CommentSlot.Main(it) }
        state.expandedReplies.forEach { (rootId, expanded) ->
            val index = expanded.items.indexOfFirst { it.rpid == rpid }
            if (index >= 0) return CommentSlot.Sub(rootId, index)
        }
        (listOfNotNull(state.topComment) + state.items).forEach { root ->
            val index = root.previewReplies.indexOfFirst { it.rpid == rpid }
            if (index >= 0) return CommentSlot.Preview(root.rpid, index)
        }
        return null
    }

    /** 从这一楼的预览层里摘掉一条。参数名避开 `rpid`,免得和接收者自己的那个撞上。 */
    private fun CommentItem.withoutPreview(target: Long): CommentItem =
        if (previewReplies.none { it.rpid == target }) this
        else copy(previewReplies = previewReplies.filterNot { it.rpid == target })

    private fun restore(comment: CommentItem, slot: CommentSlot) {
        _state.update { current ->
            when (slot) {
                CommentSlot.Top -> current.copy(topComment = comment)

                // 下标按当时的列表记的,期间翻了一页或刷新过就可能越界,夹回合法区间。
                is CommentSlot.Main -> current.copy(
                    items = current.items.toMutableList().apply {
                        add(slot.index.coerceIn(0, size), comment)
                    },
                )

                is CommentSlot.Sub -> {
                    val entry = current.expandedReplies[slot.rootId] ?: return@update current
                    val items = entry.items.toMutableList().apply {
                        add(slot.index.coerceIn(0, size), comment)
                    }
                    current.copy(
                        expandedReplies = current.expandedReplies + (slot.rootId to entry.copy(items = items)),
                    )
                }

                is CommentSlot.Preview -> {
                    // `this.rpid` 写全:这里比的是主楼自己的 rpid,不是被删那条的。
                    fun CommentItem.restoreInto(): CommentItem =
                        if (this.rpid != slot.rootId) this
                        else copy(
                            previewReplies = previewReplies.toMutableList().apply {
                                add(slot.index.coerceIn(0, size), comment)
                            },
                        )
                    current.copy(
                        topComment = current.topComment?.restoreInto(),
                        items = current.items.map { it.restoreInto() },
                    )
                }
            }
        }
    }

    private fun expandRepliesFresh(rootId: Long) {
        subReplyNextPage.remove(rootId)
        _state.update { it.copy(expandedReplies = it.expandedReplies - rootId) }
        expandReplies(rootId)
    }

    /** 主楼(含置顶)。只在主楼层找,楼中楼没有自己的 previewReplies。 */
    private fun CommentUiState.rootComment(rpid: Long): CommentItem? =
        topComment?.takeIf { it.rpid == rpid } ?: items.find { it.rpid == rpid }

    /**
     * 按 rpid 找一条评论,四处都找:置顶楼、主楼列表、**两者各自的楼中楼预览层**、已展开的
     * 楼中楼。
     *
     * 置顶楼的预览层以前不在这个范围里,而 [send] 靠这个函数把 rpid 换成回复对象 ——
     * 找不到就当成没有对象,于是"回复置顶楼下面那条"会被发成一条一级评论。
     */
    private fun findComment(rpid: Long): CommentItem? {
        val state = _state.value
        val roots = listOfNotNull(state.topComment) + state.items
        roots.find { it.rpid == rpid }?.let { return it }
        roots.forEach { root -> root.previewReplies.find { it.rpid == rpid }?.let { return it } }
        state.expandedReplies.values.forEach { expanded ->
            expanded.items.find { it.rpid == rpid }?.let { return it }
        }
        return null
    }

    private fun applyToComment(rpid: Long, transform: (CommentItem) -> CommentItem) {
        // 一条主楼连同它自带的楼中楼预览走同一个变换。**置顶楼的预览层以前漏在外面**:
        // 它和普通主楼一样带 previewReplies,而这里只认了 topComment 本身。
        //
        // 目标 rpid 走参数传进来,不靠闭包捕获外层那个同名参数:扩展函数里裸写 `rpid`
        // 命中的是接收者自己的成员,`rpid == this.rpid` 会恒真。
        fun CommentItem.applyDeep(target: Long): CommentItem = when (target) {
            rpid -> transform(this)
            else -> copy(previewReplies = previewReplies.map { if (it.rpid == target) transform(it) else it })
        }
        _state.update { current ->
            current.copy(
                topComment = current.topComment?.applyDeep(rpid),
                items = current.items.map { it.applyDeep(rpid) },
                expandedReplies = current.expandedReplies.mapValues { (_, v) ->
                    v.copy(items = v.items.map { if (it.rpid == rpid) transform(it) else it })
                },
            )
        }
    }

    /** 展开失败报在这一楼自己身上,见 [ExpandedReplies.error]。 */
    private fun setExpandError(rootId: Long, message: String) {
        _state.update { current ->
            val entry = current.expandedReplies[rootId] ?: return@update current
            current.copy(expandedReplies = current.expandedReplies + (rootId to entry.copy(error = message)))
        }
    }

    private fun setError(message: String) {
        _state.update { it.copy(loading = false, appending = false, error = message) }
    }
}
