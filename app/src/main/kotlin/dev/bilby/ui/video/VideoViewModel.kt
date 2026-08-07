package dev.bilby.ui.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.agent.AgentEvent
import dev.bilby.agent.AgentIntent
import dev.bilby.agent.AgentLoop
import dev.bilby.api.BiliResult
import dev.bilby.data.SettingsStore
import dev.bilby.data.QueueSourceRepository
import dev.bilby.data.SponsorBlockRepository
import dev.bilby.data.ToViewRepository
import dev.bilby.data.SponsorSegment
import kotlinx.coroutines.flow.first
import dev.bilby.data.PlayInfo
import dev.bilby.data.FavFolder
import dev.bilby.data.HeartbeatReporter
import dev.bilby.data.VideoActionRepository
import dev.bilby.data.VideoDetail
import dev.bilby.data.VideoRelation
import dev.bilby.data.VideoRepository
import dev.bilby.data.VideoStat
import dev.bilby.data.db.PlaybackProgressDao
import dev.bilby.data.db.PlaybackProgressEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VideoUiState(
    val detail: VideoDetail? = null,
    val playInfo: PlayInfo? = null,
    val resumeAtMillis: Long = 0,
    val currentCid: Long = 0,
    val currentQuality: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
)

class VideoViewModel(
    private val bvid: String,
    private val repository: VideoRepository,
    private val progressDao: PlaybackProgressDao,
    private val agentLoop: AgentLoop,
    private val heartbeatReporter: HeartbeatReporter,
    private val actionRepository: VideoActionRepository,
    private val settings: SettingsStore,
    private val sponsorBlockRepository: SponsorBlockRepository,
    private val queueSourceRepository: QueueSourceRepository,
    private val toViewRepository: ToViewRepository,
) : ViewModel() {

    /**
     * 是否已加入稍后再看。**只进不出**:没有便宜的办法知道当前视频在不在列表里
     * (要判断就得把整个列表拉下来),而移除本来就该在稍后再看页面做 —— 那里是个列表,
     * 划掉一条是自然动作。所以这个状态只从 false 走到 true,不是一个 toggle。
     */
    private val _addedToView = MutableStateFlow(false)
    val addedToView: StateFlow<Boolean> = _addedToView.asStateFlow()

    /**
     * 加入稍后再看。乐观更新:点了立刻切成已加入态,不等接口回来,也不回头拉列表确认 ——
     * 重拉会让计数闪两次,和点赞/收藏的处理一致。失败则回滚并留日志
     * (DESIGN 8:任何被吞掉的失败都必须留下一行能定位的日志)。
     */
    fun addToView() {
        if (_addedToView.value) return
        _addedToView.value = true
        viewModelScope.launch {
            when (val result = toViewRepository.add(bvid)) {
                is BiliResult.Ok -> Unit
                is BiliResult.ApiError -> {
                    _addedToView.value = false
                    BiliLog.w("toview/add 失败(${result.code}): ${result.message}")
                }
                is BiliResult.Failure -> {
                    _addedToView.value = false
                    BiliLog.w("toview/add 异常", result.cause)
                }
            }
        }
    }

    /**
     * 播放队列。它占的是官方相关推荐的位置,但装的是确定性的有限集合:当前合集,
     * 没有合集就退到该 UP 的投稿(DESIGN 2.4b)。听视频播的就是这一份,不另建。
     */
    private val _queue = MutableStateFlow(QueueUiState(loading = true))
    val queue: StateFlow<QueueUiState> = _queue.asStateFlow()

    fun toggleShuffle() {
        val next = !_queue.value.shuffled
        _queue.update { it.copy(shuffled = next) }
        // NonCancellable:切了随机就退出页面是常见操作,而 DataStore 的 edit 是挂起函数,
        // 页面一走 viewModelScope 就取消,写还没落盘。理由同 SettingsViewModel.persist。
        viewModelScope.launch(NonCancellable) {
            val prefs = settings.playbackPrefs.first()
            settings.savePlaybackPrefs(prefs.copy(shuffled = next))
        }
    }

    private fun loadQueue(detail: VideoDetail) = viewModelScope.launch {
        val shuffled = settings.playbackPrefs.first().shuffled
        val built = queueSourceRepository.fromSeason(detail.bvid)
            ?: queueSourceRepository.fromUpSpace(detail.up.mid, detail.bvid)
        _queue.value = QueueUiState(
            items = built?.items.orEmpty(),
            currentBvid = detail.bvid,
            sourceLabel = built?.sourceLabel.orEmpty(),
            shuffled = shuffled,
            loading = false,
        )
    }

    /** 赞助/片头片尾片段,默认开启自动跳过。拉取失败就是空列表,不影响播放。 */
    private val _sponsorSegments = MutableStateFlow<List<SponsorSegment>>(emptyList())
    val sponsorSegments: StateFlow<List<SponsorSegment>> = _sponsorSegments.asStateFlow()

    /**
     * 关掉时直接不发请求,而不是拉回来再过滤:这是发给第三方服务端的查询,
     * 用户关掉这个功能的意思里包含"别去问它"。
     *
     * 类别过滤发生在仓库合并重叠区间之后,所以极少数跨类别重叠的片段会按合并后那一段的
     * 类别一起去留。重叠本来就是罕见的边界情况(仓库那边也是这么取舍的),
     * 为它把过滤下沉到仓库、再让仓库认识用户偏好,不值当。
     */
    private suspend fun loadSponsorSegments(cid: Long) {
        val prefs = settings.sponsorBlockPrefs.first()
        if (!prefs.enabled) {
            _sponsorSegments.value = emptyList()
            return
        }
        _sponsorSegments.value = sponsorBlockRepository
            .segments(bvid, cid, prefs.serverUrl)
            .filter { it.category in prefs.categories }
    }

    private val _relation = MutableStateFlow<VideoRelation?>(null)
    val relation: StateFlow<VideoRelation?> = _relation.asStateFlow()

    private val _favFolders = MutableStateFlow<List<FavFolder>>(emptyList())
    val favFolders: StateFlow<List<FavFolder>> = _favFolders.asStateFlow()

    /** 本次播放会话的起点,心跳要用同一个值,不能每次上报重新取。 */
    private val sessionStartTs = System.currentTimeMillis() / 1000

    private val _state = MutableStateFlow(VideoUiState())
    val state: StateFlow<VideoUiState> = _state.asStateFlow()

    private val _related = MutableStateFlow(RelatedState())
    val related: StateFlow<RelatedState> = _related.asStateFlow()

    init {
        load()
    }

    /**
     * 只在用户显式点击时才跑(DESIGN 2.3):它占的是官方"相关推荐"的位置,但不能有
     * 相关推荐的行为 —— 自动加载就等于把永不实现清单里的东西装了回来。
     */
    fun findRelated() {
        val detail = _state.value.detail ?: return
        _related.value = RelatedState(started = true, running = true)
        viewModelScope.launch {
            agentLoop.run(AgentIntent.Related(bvid, detail.title, detail.up.name)).collect { event ->
                _related.update { current ->
                    when (event) {
                        is AgentEvent.Thinking -> current
                        is AgentEvent.ToolStarted -> current.copy(steps = current.steps + event.label)
                        is AgentEvent.ToolFinished -> current
                        is AgentEvent.Answer -> current.copy(answer = event.items, summary = event.summary, running = false)
                        is AgentEvent.Failed -> current.copy(error = event.message, running = false)
                    }
                }
            }
            _related.update { it.copy(running = false) }
        }
    }

    /**
     * 切清晰度要重新取流。必须带着当前播放位置回来 —— 从头开始是最容易犯也最招人烦的
     * 错误,而且用户往往是看到一半才觉得画质不够。
     */
    fun setQuality(quality: Int, currentPositionMillis: Long) {
        val cid = _state.value.currentCid
        if (cid == 0L) return
        // 在播放页改画质就是改全局默认(DESIGN 2 节):设置页不重复放一个画质选项,
        // 也就没有"两处能改同一件事"的问题。
        viewModelScope.launch(NonCancellable) { settings.saveDefaultQuality(quality) }
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            when (
                val play = repository.getPlayUrl(
                    bvid,
                    cid,
                    preferredQuality = quality,
                    preferredCodecs = settings.playerPrefs.first().codec.codecIds,
                )
            ) {
                is BiliResult.Ok -> _state.update {
                    it.copy(
                        playInfo = play.value,
                        resumeAtMillis = currentPositionMillis,
                        currentQuality = quality,
                        loading = false,
                    )
                }

                is BiliResult.ApiError -> fail("切换清晰度失败:${play.message}(${play.code})")
                is BiliResult.Failure -> fail(play.cause.message ?: "网络错误")
            }
        }
    }

    private fun load() = viewModelScope.launch {
        when (val detail = repository.getVideoDetail(bvid)) {
            is BiliResult.Ok -> {
                _state.update { it.copy(detail = detail.value) }
                loadQueue(detail.value)
                when (val rel = actionRepository.getRelation(bvid)) {
                    is BiliResult.Ok -> _relation.value = rel.value
                    is BiliResult.ApiError -> BiliLog.w("查互动状态失败(${rel.code}): ${rel.message}")
                    is BiliResult.Failure -> BiliLog.w("查互动状态异常: ${rel.cause}")
                }
                playPart(detail.value.cid)
            }

            is BiliResult.ApiError -> fail("${detail.message}(${detail.code})")
            is BiliResult.Failure -> fail(detail.cause.message ?: "网络错误")
        }
    }

    /** 合集/多 P 的确定性导航(DESIGN 2.3:这是导航不是推荐)。 */
    fun playPart(cid: Long) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null, currentCid = cid) }
        // 片段随 cid 变,换 P 要重拉;放在取流之前不阻塞播放(它自己有超时与静默失败)。
        launch { loadSponsorSegments(cid) }
        val player = settings.playerPrefs.first()
        when (
            val play = repository.getPlayUrl(
                bvid,
                cid,
                preferredQuality = player.defaultQuality,
                preferredCodecs = player.codec.codecIds,
            )
        ) {
            is BiliResult.Ok -> {
                val local = progressDao.get(bvid)?.takeIf { it.cid == cid }?.positionMillis ?: 0
                _state.update {
                    it.copy(
                        playInfo = play.value,
                        // 本地进度与服务端 last_play_time 取较大者:我们不上报心跳时服务端那份
                        // 会停在别的客户端留下的位置,取大的一方总是更接近"我看到哪了"。
                        resumeAtMillis = maxOf(local, play.value.lastPlayTimeMillis),
                        // 画质菜单要显示"当前是哪一档",没有这一行它在换 P 后会退回 0(无选中)。
                        currentQuality = player.defaultQuality,
                        loading = false,
                    )
                }
            }

            is BiliResult.ApiError -> fail("取流失败:${play.message}(${play.code})")
            is BiliResult.Failure -> fail(play.cause.message ?: "网络错误")
        }
    }

    /**
     * 心跳上报。DESIGN 7 节已定案回传:它是跨端续播的必要条件,不是观看画像。
     *
     * 与本地 Room 那份进度是两套并存 —— 本地那份负责冷启动瞬间续播(不能等网络),
     * 心跳负责让官方端和其他客户端看到同一个位置。
     */
    fun reportHeartbeat(positionMillis: Long, durationMillis: Long, finished: Boolean) {
        val detail = _state.value.detail ?: return
        val cid = _state.value.currentCid.takeIf { it != 0L } ?: return
        viewModelScope.launch {
            heartbeatReporter.report(
                aid = detail.aid,
                cid = cid,
                progressSeconds = positionMillis / 1000,
                playedTimeSeconds = positionMillis / 1000,
                realtimeSeconds = positionMillis / 1000,
                startTs = sessionStartTs,
                videoDurationSeconds = durationMillis / 1000,
                isFinished = finished,
            )
        }
    }

    fun toggleLike() {
        val current = _relation.value ?: return
        val aid = _state.value.detail?.aid ?: return
        // 乐观更新:点赞是高频动作,等一个来回再变色会让人以为没点上。失败再翻回去。
        _relation.value = current.copy(liked = !current.liked)
        adjustStat { it.copy(like = it.like + if (current.liked) -1 else 1) }
        viewModelScope.launch {
            when (val result = actionRepository.like(aid, !current.liked)) {
                is BiliResult.Ok -> Unit
                is BiliResult.ApiError -> {
                    BiliLog.w("点赞失败(${result.code}): ${result.message}")
                    _relation.value = current
                }

                is BiliResult.Failure -> {
                    BiliLog.w("点赞异常: ${result.cause}")
                    _relation.value = current
                }
            }
        }
    }

    fun coin(count: Int, alsoLike: Boolean) {
        val current = _relation.value ?: return
        val aid = _state.value.detail?.aid ?: return
        viewModelScope.launch {
            when (val result = actionRepository.coin(aid, count, alsoLike)) {
                is BiliResult.Ok -> {
                    _relation.value = current.copy(
                        coined = current.coined + count,
                        liked = current.liked || alsoLike,
                    )
                    adjustStat {
                        it.copy(
                            coin = it.coin + count,
                            like = if (alsoLike && !current.liked) it.like + 1 else it.like,
                        )
                    }
                }

                is BiliResult.ApiError -> BiliLog.w("投币失败(${result.code}): ${result.message}")
                is BiliResult.Failure -> BiliLog.w("投币异常: ${result.cause}")
            }
        }
    }

    fun openFavPicker() {
        val aid = _state.value.detail?.aid ?: return
        viewModelScope.launch {
            val mid = settings.credentials.first().dedeUserId.toLongOrNull() ?: return@launch
            when (val result = actionRepository.listFavFolders(mid, aid)) {
                is BiliResult.Ok -> _favFolders.value = result.value
                is BiliResult.ApiError -> BiliLog.w("查收藏夹失败(${result.code}): ${result.message}")
                is BiliResult.Failure -> BiliLog.w("查收藏夹异常: ${result.cause}")
            }
        }
    }

    fun confirmFavorite(addIds: List<Long>, delIds: List<Long>) {
        val aid = _state.value.detail?.aid ?: return
        viewModelScope.launch {
            when (val result = actionRepository.favorite(aid, addIds, delIds)) {
                is BiliResult.Ok -> {
                    _relation.update { it?.copy(favored = addIds.isNotEmpty()) }
                    adjustStat { it.copy(favorite = it.favorite + addIds.size - delIds.size) }
                    // 本地改勾选状态,不重拉。重拉会把服务端的实时计数盖回来,而热门视频的
                    // 计数每秒都在变,表现就是刚 +1 的数字又跳一下 —— 乐观更新与重拉只能选一个。
                    _favFolders.update { folders ->
                        folders.map { folder ->
                            when (folder.id) {
                                in addIds -> folder.copy(containsThis = true, count = folder.count + 1)
                                in delIds -> folder.copy(containsThis = false, count = folder.count - 1)
                                else -> folder
                            }
                        }
                    }
                }

                is BiliResult.ApiError -> BiliLog.w("收藏失败(${result.code}): ${result.message}")
                is BiliResult.Failure -> BiliLog.w("收藏异常: ${result.cause}")
            }
        }
    }

    /**
     * 计数来自视频详情的静态字段,不会因为你点赞而变。不跟着动的话,点了赞数字纹丝不动,
     * 看起来就像没生效 —— 所以这里按动作乐观增减。
     */
    private fun adjustStat(transform: (VideoStat) -> VideoStat) {
        _state.update { current ->
            val detail = current.detail ?: return@update current
            current.copy(detail = detail.copy(stat = transform(detail.stat)))
        }
    }

    fun saveProgress(positionMillis: Long, durationMillis: Long) {
        val cid = _state.value.currentCid
        if (cid == 0L || positionMillis <= 0) return
        viewModelScope.launch {
            progressDao.upsert(
                PlaybackProgressEntity(
                    bvid = bvid,
                    cid = cid,
                    positionMillis = positionMillis,
                    durationMillis = durationMillis,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(loading = false, error = message) }
    }
}
