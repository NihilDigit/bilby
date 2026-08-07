package dev.bilby.ui.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.agent.AgentEvent
import dev.bilby.agent.AgentIntent
import dev.bilby.agent.AgentLoop
import dev.bilby.api.BiliResult
import dev.bilby.danmaku.DanmakuRepository
import dev.bilby.data.SettingsStore
import dev.bilby.data.SponsorBlockRepository
import dev.bilby.data.FollowState
import dev.bilby.data.RelationRepository
import dev.bilby.data.SubtitleRepository
import dev.bilby.data.ToViewRepository
import dev.bilby.data.SponsorSegment
import dev.bilby.player.SubtitleCue
import dev.bilby.player.SubtitleTrack
import dev.danmaku.compose.Danmaku
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dev.bilby.data.FavFolder
import dev.bilby.data.HeartbeatReporter
import dev.bilby.data.VideoActionRepository
import dev.bilby.data.VideoDetail
import dev.bilby.data.VideoRelation
import dev.bilby.data.VideoRepository
import dev.bilby.data.VideoStat
import dev.bilby.player.AudioPlaybackService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 播放页自己的东西:这条视频**是什么**。
 *
 * **播放状态不在这里。** 正在播哪一 P、画质清单、取流失败、队列——全都归
 * [AudioPlaybackService.state]。播放器和队列只有一份,页面是壳(DESIGN 2.4b);
 * 页面再存一份就等于承认有两个真相,而"页面说 A、播放器在放 E"正是那样来的。
 */
data class VideoUiState(
    val detail: VideoDetail? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class VideoViewModel(
    private val bvid: String,
    private val repository: VideoRepository,
    private val agentLoop: AgentLoop,
    private val heartbeatReporter: HeartbeatReporter,
    private val actionRepository: VideoActionRepository,
    private val settings: SettingsStore,
    private val sponsorBlockRepository: SponsorBlockRepository,
    private val toViewRepository: ToViewRepository,
    private val relationRepository: RelationRepository,
    private val subtitleRepository: SubtitleRepository,
    private val danmakuRepository: DanmakuRepository,
) : ViewModel() {

    /** UP 的关注态。视频详情里只有 mid 和名字,关系要另查(PiliPlus 播放页同样单独查)。 */
    private val _followState = MutableStateFlow(FollowState.None)
    val followState: StateFlow<FollowState> = _followState.asStateFlow()

    /**
     * 关注/取关。与点赞投币同样是乐观更新、不重拉:等一个来回再变字会让人以为没点上。
     *
     * 互关状态下取关要退回"未关注"而不是"已关注" —— 对方关注你这件事不受你取关影响,
     * 但从你这边看关系确实断了。
     */
    fun toggleFollow() {
        val mid = _state.value.detail?.up?.mid ?: return
        val current = _followState.value
        if (current == FollowState.Self || current == FollowState.Blocked) return

        val following = current.isFollowing
        _followState.value = if (following) FollowState.None else FollowState.Following
        viewModelScope.launch {
            val result =
                if (following) relationRepository.unfollow(mid) else relationRepository.follow(mid)
            if (result !is BiliResult.Ok) {
                BiliLog.w("${if (following) "取关" else "关注"}失败: $result")
                _followState.value = current
            }
        }
    }

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

    /** 这条视频(当前 cid)有哪些字幕轨,换 P/换视频就重新拉。 */
    private val _subtitleTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val subtitleTracks: StateFlow<List<SubtitleTrack>> = _subtitleTracks.asStateFlow()

    /** 选中轨的语言代码,空字符串是关(默认)。看视频的控制条和听视频的文稿共用这一份状态。 */
    private val _subtitleLan = MutableStateFlow("")
    val subtitleLan: StateFlow<String> = _subtitleLan.asStateFlow()

    private val _subtitleCues = MutableStateFlow<List<SubtitleCue>>(emptyList())
    val subtitleCues: StateFlow<List<SubtitleCue>> = _subtitleCues.asStateFlow()

    /** 弹幕总开关。**默认关**,持久化到 [SettingsStore],看视频与听视频页共用不到——听视频没有画面。 */
    private val _danmakuEnabled = MutableStateFlow(false)
    val danmakuEnabled: StateFlow<Boolean> = _danmakuEnabled.asStateFlow()

    /**
     * 已拉到的弹幕池,累计追加、不去重合并(那是以后的事)。时间轴的编译不在这里——
     * 它需要 `measureWidth` 和画布像素宽度,两者都只在 Compose 层才有(见 BilbyPlayer.kt)。
     */
    private val _danmakuPool = MutableStateFlow<List<Danmaku>>(emptyList())
    val danmakuPool: StateFlow<List<Danmaku>> = _danmakuPool.asStateFlow()

    /** 当前弹幕池所属的 cid,换 cid 时用来判断在飞的请求是否已经过期。 */
    private var danmakuCid = 0L

    /** 这一条 cid 已经请求过的分段号(1-based),防止播放进度在同一段内反复轮询时重复拉取。 */
    private val requestedDanmakuSegments = mutableSetOf<Int>()

    init {
        load()
        observeCurrentPart()
        observeDanmakuCid()
        // 先读一次持久化的偏好,再开始跟播放器的 cid 走——顺序反过来的话,轨道清单可能在
        // 偏好读回来之前就到,那一次找轨会拿着空字符串去比,永远命中"关"。
        viewModelScope.launch {
            _subtitleLan.value = settings.subtitlePrefs.first().lan
            observeSubtitleTracks()
        }
        viewModelScope.launch { _danmakuEnabled.value = settings.danmakuPrefs.first().enabled }
    }

    /**
     * 弹幕开关。持久化用 NonCancellable,理由与 [selectSubtitle] 相同。
     *
     * 打开时补一次段 1 预取:换 cid 那一刻开关还是关的,[observeDanmakuCid] 跳过了预取,
     * 不补的话要等下一次进度回调(播放中最长 5 秒)才有机会拉到东西——播放中途打开开关会
     * 有一段空窗。**只在开着的时候才拉**是风控要求:弹幕默认关,不该让每一个不用这个功能
     * 的用户在每次打开视频时都多背一次请求。
     */
    fun setDanmakuEnabled(enabled: Boolean) {
        _danmakuEnabled.value = enabled
        viewModelScope.launch(NonCancellable) { settings.saveDanmakuEnabled(enabled) }
        if (enabled) fetchInitialDanmakuSegment(danmakuCid)
    }

    /**
     * 弹幕池随 cid 变,原因和字幕轨、SponsorBlock 片段一样:播放器全 app 共用,队列走到
     * 别的视频上时不该把那一条的弹幕留在这一页。换 cid 清空已请求分段集合与弹幕池——
     * 那是另一条视频的弹幕,不是"还没拉完"。
     *
     * 预取段 1 只在开关已经打开时才做,理由见 [setDanmakuEnabled]。
     */
    private fun observeDanmakuCid() = viewModelScope.launch {
        AudioPlaybackService.state
            .map { if (it.current?.bvid == bvid) it.currentCid else 0L }
            .distinctUntilChanged()
            .collect { cid ->
                danmakuCid = cid
                requestedDanmakuSegments.clear()
                _danmakuPool.value = emptyList()
                // 进度也要跟着归零:留着上一条的位置,中途开弹幕会照那个位置去拉段号。
                lastDanmakuPositionMillis = 0L
                if (_danmakuEnabled.value) fetchInitialDanmakuSegment(cid)
            }
    }

    /**
     * 拉当前进度所在的那一段,不必等下一次进度回调。
     *
     * **不能写死段 1**:换 cid 时进度确实是 0,但用户在第 20 分钟按下开关时,该拉的是第 4 段
     * 而不是第 1 段 —— 拉错段的表现是"开了弹幕但一条都不来",而它和"这个视频没人发弹幕"
     * 在画面上完全一样,查不出来。
     */
    private fun fetchInitialDanmakuSegment(cid: Long) {
        if (cid == 0L) return
        fetchDanmakuSegment(cid, danmakuRepository.segmentIndexFor(lastDanmakuPositionMillis) + 1)
    }

    /**
     * 播放进度驱动弹幕分段拉取。**不为此另起轮询**——播放页已经有一份(BilbyPlayer 每 5 秒
     * 回传一次进度用于心跳),这里挂在同一个回调上,详见 VideoScreen/MainActivity 的接线。
     */
    fun onDanmakuPlaybackPosition(positionMillis: Long) {
        // 位置无条件记下来:开关中途被打开时要靠它知道该从哪一段拉起。
        lastDanmakuPositionMillis = positionMillis
        if (!_danmakuEnabled.value || danmakuCid == 0L) return
        fetchDanmakuSegment(danmakuCid, danmakuRepository.segmentIndexFor(positionMillis) + 1)
    }

    /** 最近一次进度回传。开关关着时也记,见 [onDanmakuPlaybackPosition]。 */
    private var lastDanmakuPositionMillis = 0L

    private fun fetchDanmakuSegment(cid: Long, segmentIndex: Int) {
        if (!requestedDanmakuSegments.add(segmentIndex)) return
        viewModelScope.launch {
            val segment = danmakuRepository.getSegment(cid, segmentIndex)
            // 拉取期间可能已经切到别的 cid(切分 P、队列走到下一条)——那份结果不属于
            // 当前弹幕池,丢弃,不追加。
            if (danmakuCid != cid || segment.isEmpty()) return@launch
            _danmakuPool.update { it + segment }
        }
    }

    /** 撞 -412 时 [dev.bilby.data.SubtitleRepository] 会退避重试,见 [loadSubtitleTracks]。 */
    private var subtitleTracksJob: Job? = null

    /**
     * 字幕轨随 cid 变,原因和 [observeCurrentPart] 一样:分 P 各有各的轨,播放器全 app
     * 共用,队列走到别的视频上时不该把那一条的轨拉到这一页来。
     */
    private suspend fun observeSubtitleTracks() {
        AudioPlaybackService.state
            .map { if (it.current?.bvid == bvid) it.currentCid else 0L }
            .distinctUntilChanged()
            .collect { cid ->
                // 换 cid 就取消上一条还没跑完的加载——它可能正卡在限流退避的 delay 里。
                // 不取消的话,这个 collect 会等旧的退避结束(最坏 2 分钟)才轮到处理新 cid,
                // 表现为切视频之后字幕迟迟不出来,而退避本来只该拖慢它自己那一条。
                subtitleTracksJob?.cancel()
                if (cid != 0L) {
                    subtitleTracksJob = viewModelScope.launch { loadSubtitleTracks(cid) }
                }
            }
    }

    private suspend fun loadSubtitleTracks(cid: Long) {
        val tracks = subtitleRepository.getTracks(bvid, cid)
        _subtitleTracks.value = tracks
        // 换 P 或换视频之后继续用上次选的那条(按语言代码找);找不到就关掉,不自动挑一条——
        // "默认关"是产品要求,不是"还没设置过"才关。
        val track = tracks.firstOrNull { it.lan == _subtitleLan.value }
        _subtitleCues.value = track?.let { subtitleRepository.getCues(it.subtitleUrl) }.orEmpty()
    }

    /** 用户在控制条的字幕菜单里选了一条轨(或选了"关")。[lan] 为空字符串表示关。 */
    fun selectSubtitle(lan: String) {
        _subtitleLan.value = lan
        // NonCancellable:选完字幕紧接着退出页面是常见操作,而退出会取消 viewModelScope,
        // DataStore 的 edit 又是挂起函数——不挡住取消的话这次选择会在写盘前被砍掉。
        // 和风格指南里"设置的落盘一律 NonCancellable"是同一条规矩。
        viewModelScope.launch(NonCancellable) { settings.saveSubtitleLan(lan) }
        val track = _subtitleTracks.value.firstOrNull { it.lan == lan }
        if (track == null) {
            _subtitleCues.value = emptyList()
            return
        }
        viewModelScope.launch { _subtitleCues.value = subtitleRepository.getCues(track.subtitleUrl) }
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
                        is AgentEvent.Answer -> current.copy(blocks = event.blocks, running = false)
                        is AgentEvent.Failed -> current.copy(error = event.message, running = false)
                    }
                }
            }
            _related.update { it.copy(running = false) }
        }
    }

    private fun load() = viewModelScope.launch {
        when (val detail = repository.getVideoDetail(bvid)) {
            is BiliResult.Ok -> {
                _state.update { it.copy(detail = detail.value, loading = false) }
                launch {
                    when (val follow = relationRepository.stateOf(detail.value.up.mid)) {
                        is BiliResult.Ok -> _followState.value = follow.value
                        else -> BiliLog.w("查关注状态失败: $follow")
                    }
                }
                when (val rel = actionRepository.getRelation(bvid)) {
                    is BiliResult.Ok -> _relation.value = rel.value
                    is BiliResult.ApiError -> BiliLog.w("查互动状态失败(${rel.code}): ${rel.message}")
                    is BiliResult.Failure -> BiliLog.w("查互动状态异常: ${rel.cause}")
                }
            }

            is BiliResult.ApiError -> fail("${detail.message}(${detail.code})")
            is BiliResult.Failure -> fail(detail.cause.message ?: "网络错误")
        }
    }

    /**
     * 片段随 cid 变,所以跟着服务那边正在播的分 P 重拉,而不是页面自己记一份 cid。
     *
     * 只认属于本页这条视频的 cid:播放器是全 app 共用的,队列走到别的视频上时不该把
     * 那一条的片段拉到这一页来。
     */
    private fun observeCurrentPart() = viewModelScope.launch {
        AudioPlaybackService.state
            .map { if (it.current?.bvid == bvid) it.currentCid else 0L }
            .distinctUntilChanged()
            .collect { cid -> if (cid != 0L) loadSponsorSegments(cid) }
    }

    /**
     * 心跳上报。DESIGN 7 节已定案回传:它是跨端续播的必要条件,不是观看画像。
     *
     * 本地不再另存一份进度,续播只认服务端这一份(服务那边按 playurl 带回的位置续播),所以这里既是
     * 回传也是我们自己下次进来的依据。
     */
    fun reportHeartbeat(positionMillis: Long, durationMillis: Long, finished: Boolean) {
        val detail = _state.value.detail ?: return
        val playback = AudioPlaybackService.state.value
        // 播放器装的必须是本页这一条。位置和时长是从播放器读的,而全 app 只有一个播放器
        // (DESIGN 2.4b):队列翻到下一条时本页的轮询可能还没停,不对身份就会把下一条的进度
        // 按本页的 aid 报上去。云端那份是续播的唯一来源,报错一次,下次进来就 seek 到不存在的位置。
        if (playback.current?.bvid != bvid) return
        val cid = playback.currentCid.takeIf { it != 0L } ?: return
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

    private fun fail(message: String) {
        BiliLog.w("播放页失败($bvid): $message")
        _state.update { it.copy(loading = false, error = message) }
    }
}
