package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.appendDistinctBy
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

    /*
     * 下面四个是 [_home] 与 [_other] 仅有的写入口(点赞那处是逐条 map,改不了条数和 id),
     * 存在的理由只有一个:**列表里不能有两条相同的 id**。
     *
     * 这条约束从前是调用方的义务,而 `appendDistinctBy` 那时就已经在仓库里了,还带着一条
     * "线上崩过一次,key 是评论的 rpid" 的注释 —— 即便如此,这个文件的三处拼接仍然漏了两处
     * (整段替换那两条路裸赋值),第三处把去重手抄了一遍。义务型的约束就是这样失效的:
     * 订阅列表因此崩过,key 是 bvid,杀掉重启照样崩,因为冷启动会把同一份重复再拼一次。
     *
     * 所以把去重挪进写入口。现在这个文件里写不出一条没去过重的赋值。
     *
     * **为什么 bvid 重复是正常输入而不是脏数据**:同一个视频可以同时以 DYNAMIC_TYPE_AV 和
     * DYNAMIC_TYPE_UGC_SEASON 出现两条动态(两者字段结构一致,见 notes/dynamic-feed.md
     * 第 199 行),跨页边界上服务端也会把同一条再给一遍。合成一条正是该有的显示结果。
     */

    private fun replaceHome(items: List<FeedEntry>) {
        _home.value = items.distinctBy { it.id }
    }

    private fun appendHome(items: List<FeedEntry>) {
        _home.update { current -> current.appendDistinctBy(items) { it.id } }
    }

    private fun replaceOther(cards: List<DynamicCard>) {
        _other.value = cards.distinctBy { it.id }
    }

    private fun appendOther(cards: List<DynamicCard>) {
        _other.update { current -> current.appendDistinctBy(cards) { it.id } }
    }

    private var nextOffset: String? = null

    /**
     * 现在这份列表是由上游几页拼出来的。**刷新据此决定要重建多深**,见 [runFetch]。
     *
     * 用页数而不是条数:上游的游标以页为单位,而一页里两半各分到多少每次都不同,按条数算
     * 会在最后一页上多要或少要一整页。
     */
    private var loadedPages = 0

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
            replaceHome(cached)
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
     * 首屏与追加取到这一半有东西为止,至多 [MAX_AUTO_PAGES] 页;**刷新把原先翻到的深度整个
     * 重建一遍**,取 [loadedPages] 页。
     *
     * 判据是"要用它的这一半拿到了几条",不是"这一页解析出了几条" ——一页里两半各分到多少
     * 差别很大,整页都是投稿视频是常态。另一半的收获照样进列表:同一次请求同时喂两个视图,
     * 是这条流合并之后最实际的好处。
     *
     * **刷新不能沿用那条"够了就停"的规则。** 沿用的话它取一页就停,而下面是整份替换,于是
     * 翻了十页再下拉,剩下的就是第一页——列表在用户眼皮底下缩回一屏,读到哪儿也一并丢了。
     * 重建一遍比往前拼接贵,换来的是一份和"重新打开这一页"完全一致的列表:上游这段时间里
     * 删掉的、改过的、换了顺序的都跟着变,拼接只会把旧的那份原样留在下面。
     *
     * 两处上限都是防止在坏数据上无限翻页,不是内容策略。
     */
    private suspend fun runFetch(half: DynamicFeedHalf, append: Boolean) {
        val freshHome = mutableListOf<FeedEntry>()
        val freshOther = mutableListOf<DynamicCard>()
        var offset = if (append) nextOffset else null
        var hasMore = true
        var pages = 0
        // 手上已经有列表、又不是追加,就是一次刷新 —— 首屏(列表还空着)走的是 append 那套。
        val rebuilding = !append && loadedPages > 0
        val budget = if (rebuilding) loadedPages.coerceAtMost(MAX_REBUILD_PAGES) else MAX_AUTO_PAGES
        if (rebuilding && loadedPages > MAX_REBUILD_PAGES) {
            BiliLog.w("刷新只重建 $MAX_REBUILD_PAGES 页,原有 $loadedPages 页")
        }
        while (pages < budget) {
            when (val result = repository.loadFeed(offset)) {
                is BiliResult.Ok -> {
                    pages++
                    freshHome += result.value.home
                    freshOther += result.value.other
                    offset = result.value.nextOffset
                    hasMore = result.value.hasMore && result.value.nextOffset != null
                    if (!hasMore) break
                    val gained = when (half) {
                        DynamicFeedHalf.Home -> freshHome.isNotEmpty()
                        DynamicFeedHalf.Other -> freshOther.isNotEmpty()
                    }
                    if (!rebuilding && gained) break
                }

                is BiliResult.ApiError -> return setError("${result.message}(${result.code})")
                is BiliResult.Failure -> return setError(result.cause.message ?: "网络错误")
            }
        }

        if (append) {
            appendHome(freshHome)
            appendOther(freshOther)
            loadedPages += pages
        } else {
            replaceHome(freshHome)
            replaceOther(freshOther)
            loadedPages = pages
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

        /**
         * 刷新最多重建这么多页。翻得比这更深的人再刷新会看到列表缩短一截——代价是一次刷新
         * 至多 12 趟串行请求,再往上等待时间比丢掉的那几屏更难接受。真截断了会记一行日志。
         */
        const val MAX_REBUILD_PAGES = 12
    }
}
