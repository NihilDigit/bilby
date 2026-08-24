package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.data.db.FeedCacheRepository
import dev.bilby.data.model.DynamicCard
import dev.bilby.data.model.FeedEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** 关注动态流的两半。哪一条归哪半见 DynamicRepository 的分流。 */
enum class DynamicFeedHalf { Home, Other }

data class DynamicFeedStatus(
    /** 首屏还什么都没有。 */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val appending: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

/**
 * 关注动态流。**全 app 只有这一份**,首页和"其他动态"是它的两个视图,不是两条流。
 *
 * 不变式只有一条:**列表就是从服务端连续取到的那几页,游标指向再下一页**。刷新即整段重取
 * (旧的一律丢掉,不做头部合并),翻页即续接。本地不保留服务端这次没给的东西,所以取关的人、
 * 删掉的动态都会在下一次刷新时消失 —— 从前那份能独立往下长的本地列表没有任何路径能表达
 * "这一条不在了",取关的 UP 一直留在首页正是这么来的。
 *
 * 排除名单在读出去的那一步过滤,不写进列表:排除可撤销,烤进列表的话取消排除之后那些条目
 * 永远回不来。过滤放在这里而不是某个 ViewModel 的 publish 里,两个视图才会一致 —— 从前
 * 首页排除掉的人在"其他动态"里照样出现。
 *
 * 缓存只有首页那一半有,理由见 [FeedCacheRepository]。
 */
class DynamicFeedStore(
    private val repository: DynamicRepository,
    private val cache: FeedCacheRepository,
    settings: SettingsStore,
) {

    private val _home = MutableStateFlow<List<FeedEntry>>(emptyList())
    private val _other = MutableStateFlow<List<DynamicCard>>(emptyList())
    private val _status = MutableStateFlow(DynamicFeedStatus())

    val home: Flow<List<FeedEntry>> = combine(_home, settings.excludedFeedMids) { items, excluded ->
        items.filterNot { it.upMid in excluded }
    }

    val other: Flow<List<DynamicCard>> = combine(_other, settings.excludedFeedMids) { cards, excluded ->
        cards.filterNot { it.author.mid in excluded }
    }

    val status: StateFlow<DynamicFeedStatus> = _status.asStateFlow()

    private var nextOffset: String? = null

    /**
     * 一次只发一条请求。**抢不到就直接返回,不排队** —— 排队的那次醒来时看到的是已经变过的
     * 列表和游标,它拿着旧参数继续做完,结果不是重复一页就是跳过一页。
     */
    private val fetching = Mutex()

    private var started = false

    /**
     * 起流。两个视图谁先出现谁负责,之后进来的读现成的。
     *
     * @param half 调用方要看的那一半,决定"这一页够不够"(见 [runFetch])。
     */
    suspend fun ensureStarted(half: DynamicFeedHalf) {
        if (started) return
        started = true
        val cached = runCatching { cache.restore() }
            .onFailure { BiliLog.w("读动态流缓存失败", it) }
            .getOrNull()
            .orEmpty()
        if (cached.isNotEmpty()) {
            // 缓存非空就先铺出来,首屏不空等网络;随后的头部请求会把它整段换掉。**这次请求
            // 不打转圈**:用户没要求刷新,顶上转一圈只会让人以为自己碰到了什么。
            _home.value = cached
            _status.update { it.copy(loading = false) }
        }
        fetch(half, append = false, spinner = false)
    }

    /** 下拉刷新,以及首屏出错之后的重试。 */
    suspend fun refresh(half: DynamicFeedHalf) = fetch(half, append = false, spinner = true)

    suspend fun loadMore(half: DynamicFeedHalf) {
        if (!_status.value.hasMore) return
        fetch(half, append = true, spinner = true)
    }

    private suspend fun fetch(half: DynamicFeedHalf, append: Boolean, spinner: Boolean) {
        if (!fetching.tryLock()) return
        try {
            if (spinner) {
                // 转哪一种圈由"这一半现在有没有东西"决定:空的是首屏加载(整页转圈),非空的是
                // 刷新(顶上转圈)。首屏出错后的重试走的正是前者。
                val empty = when (half) {
                    DynamicFeedHalf.Home -> _home.value.isEmpty()
                    DynamicFeedHalf.Other -> _other.value.isEmpty()
                }
                _status.update {
                    when {
                        append -> it.copy(appending = true, error = null)
                        empty -> it.copy(loading = true, error = null)
                        else -> it.copy(refreshing = true, error = null)
                    }
                }
            }
            runFetch(half, append)
        } finally {
            fetching.unlock()
        }
    }

    /**
     * 点赞的乐观更新。列表在这里,所以改也在这里;发请求和回滚仍归调用方
     * (见 OtherDynamicsViewModel.like)。
     */
    fun setLiked(id: String, liked: Boolean) {
        _other.update { cards ->
            cards.map { card ->
                val interaction = card.interaction
                if (card.id != id || interaction == null || interaction.liked == liked) {
                    card
                } else {
                    card.copy(
                        interaction = interaction.copy(
                            liked = liked,
                            likeCount = (interaction.likeCount + if (liked) 1 else -1).coerceAtLeast(0),
                        ),
                    )
                }
            }
        }
    }

    /**
     * 取到这一半有东西为止,至多 [MAX_AUTO_PAGES] 页。
     *
     * **判据是"要用它的这一半拿到了几条",不是"这一页解析出了几条"** ——一页里两半各分到多少
     * 差别很大,整页都是投稿视频是常态。另一半的收获照样进列表,不像从前那样被丢掉:同一次
     * 请求同时喂两个视图,是这条流合并之后最实际的好处。
     *
     * 上限是防止在坏数据上无限翻页,不是内容策略。
     */
    private suspend fun runFetch(half: DynamicFeedHalf, append: Boolean) {
        val freshHome = mutableListOf<FeedEntry>()
        val freshOther = mutableListOf<DynamicCard>()
        var offset = if (append) nextOffset else null
        var hasMore = true
        for (page in 0 until MAX_AUTO_PAGES) {
            when (val result = repository.loadFeed(offset)) {
                is BiliResult.Ok -> {
                    freshHome += result.value.home
                    freshOther += result.value.other
                    offset = result.value.nextOffset
                    hasMore = result.value.hasMore && result.value.nextOffset != null
                    val gained = when (half) {
                        DynamicFeedHalf.Home -> freshHome.isNotEmpty()
                        DynamicFeedHalf.Other -> freshOther.isNotEmpty()
                    }
                    if (gained || !hasMore) break
                }

                is BiliResult.ApiError -> return setError("${result.message}(${result.code})")
                is BiliResult.Failure -> return setError(result.cause.message ?: "网络错误")
            }
        }

        if (append) {
            // 分页边界上服务端偶尔会把同一条再给一遍,按 id 去重;不重排也不更新已有那条。
            _home.update { current ->
                val seen = current.mapTo(HashSet(current.size)) { it.id }
                current + freshHome.filter { seen.add(it.id) }
            }
            _other.update { current ->
                val seen = current.mapTo(HashSet(current.size)) { it.id }
                current + freshOther.filter { seen.add(it.id) }
            }
        } else {
            _home.value = freshHome
            _other.value = freshOther
        }
        nextOffset = offset
        runCatching { cache.saveHead(_home.value) }
            .onFailure { BiliLog.w("写动态流缓存失败", it) }
        _status.value = DynamicFeedStatus(loading = false, hasMore = hasMore)
    }

    private fun setError(message: String) = _status.update {
        it.copy(loading = false, refreshing = false, appending = false, error = message)
    }

    private companion object {
        const val MAX_AUTO_PAGES = 3
    }
}
