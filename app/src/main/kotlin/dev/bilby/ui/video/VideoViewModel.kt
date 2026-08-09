package dev.bilby.ui.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.agent.AgentTurnState
import dev.bilby.agent.reduce
import dev.bilby.agent.AgentIntent
import dev.bilby.agent.AgentLoop
import dev.bilby.api.BiliResult
import dev.bilby.danmaku.DanmakuRepository
import dev.bilby.data.DanmakuPrefs
import dev.bilby.data.SettingsStore
import dev.bilby.data.SponsorBlockRepository
import dev.bilby.data.FollowState
import dev.bilby.data.RelationRepository
import dev.bilby.data.SubtitleRepository
import dev.bilby.data.ToViewRepository
import dev.bilby.data.SponsorSegment
import dev.bilby.player.SubtitleCue
import dev.bilby.player.SubtitleTrack
import dev.nihildigit.danmaku.Danmaku
import dev.nihildigit.danmaku.SpecialDanmaku
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import dev.bilby.data.FavFolder
import dev.bilby.data.HeartbeatReporter
import dev.bilby.data.MemberCard
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
    initialBvid: String,
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

    /**
     * 这一页当前在讲哪条视频。
     *
     * **一个播放页只有一个 ViewModel,换一集是 [switchTo],不是换一个实例。** 原先靠
     * `viewModel(key = "video-$bvid")` 选实例:Compose 的 key 决定选哪个实例,它不负责删掉
     * 旧 key 对应的那个。而切集不进 backstack(见 MainActivity 的 VideoRoute),NavEntry 的
     * ViewModelStore 因此在整页出栈之前一直不清 —— 连播走一条就攒一个 VM,每个都还挂着
     * 自己那份 `AudioPlaybackService.state` 的 collect,旧详情、旧字幕、旧弹幕都还在推。
     * 表现是连播时能看到上一条的画面残留几帧,以及连续播放的内存单调增长。
     */
    var bvid: String = initialBvid
        private set

    /**
     * 当前视频的代数,[switchTo] 时自增。
     *
     * **取消挡不住写操作的迟到回调。** 点赞、投币、收藏这些请求一旦发出,取消协程不会让服务端
     * 收不到,只会让我们不知道结果;而它们的失败回滚写的是 `_relation`、`_favFolders` 这些
     * 已经属于新视频的状态。所以写请求照跑到底,只是结果要先对代数才准回到页面上,见 [ifCurrent]。
     */
    private var generation = 0

    /**
     * 当前视频级协程的容器。详情、分 P、字幕、弹幕分段、找相关都挂在它下面,[switchTo] 时
     * 整组取消。用 [SupervisorJob] 是因为这几条互不依赖 —— 字幕拉失败不该把弹幕一起带走。
     *
     * 挂在 `viewModelScope` 的 Job 下面,所以页面销毁时它跟着一起走,不需要另外记一笔。
     */
    private val videoJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private val videoScope = CoroutineScope(viewModelScope.coroutineContext + videoJob)

    /**
     * 持久化的字幕语言读回来了。
     *
     * 字幕轨的观察必须等它:轨道清单可能在偏好读回来之前就到,那一次找轨会拿着空字符串去比,
     * 永远命中"关"。原先靠 `init` 里的语句顺序保证,而 [switchTo] 之后"顺序"不再成立 ——
     * 换一集会重启视频级协程,却不该、也不能再读一次偏好。改成显式的一次性信号。
     */
    private val subtitleLanLoaded = CompletableDeferred<Unit>()

    /** UP 的关注态。视频详情里只有 mid 和名字,关系要另查(PiliPlus 播放页同样单独查)。 */
    private val _followState = MutableStateFlow(FollowState.None)
    val followState: StateFlow<FollowState> = _followState.asStateFlow()

    /**
     * 联合投稿里已经关注了的那些 mid。
     *
     * **null 表示还没查到,不是"一个都没关注"。** 两者必须分开:头像上的关注加号是"未关注
     * 才显示",查到之前当成未关注的话,每次打开联合投稿视频都会先冒出一排加号再消失。
     * PiliPlus 在同一处用一个 `status` 哨兵解决同一个问题。
     */
    private val _staffFollowed = MutableStateFlow<Set<Long>?>(null)
    val staffFollowed: StateFlow<Set<Long>?> = _staffFollowed.asStateFlow()

    /**
     * 关注联合投稿里的某一位。**只关注,没有取关** —— 加号在关注之后就消失了,取关走那个人
     * 的空间页。这一排是快捷入口,不是关系管理界面。
     */
    fun followStaff(mid: Long) {
        if (mid == 0L || _staffFollowed.value?.contains(mid) == true) return
        // 乐观更新:加号立刻消失,和播放页其它写操作同一套规矩。
        _staffFollowed.update { (it ?: emptySet()) + mid }
        val startGeneration = generation
        viewModelScope.launch {
            val result = relationRepository.follow(mid)
            if (result !is BiliResult.Ok) {
                BiliLog.w("关注联合投稿成员失败(mid=$mid): $result")
                ifCurrent(startGeneration) { _staffFollowed.update { it?.minus(mid) } }
            }
        }
    }

    /**
     * UP 的等级(+ 粉丝数,这一轮不显示)。同样是独立请求,独立失败——查不到就是 null,
     * 徽章不画,不影响 UP 名和关注按钮(见 [load] 里怎么处理这次失败)。
     */
    private val _upCard = MutableStateFlow<MemberCard?>(null)
    val upCard: StateFlow<MemberCard?> = _upCard.asStateFlow()

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
        val startGeneration = generation
        viewModelScope.launch {
            val result =
                if (following) relationRepository.unfollow(mid) else relationRepository.follow(mid)
            if (result !is BiliResult.Ok) {
                BiliLog.w("${if (following) "取关" else "关注"}失败: $result")
                ifCurrent(startGeneration) { _followState.value = current }
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
        val target = bvid
        val startGeneration = generation
        viewModelScope.launch {
            when (val result = toViewRepository.add(target)) {
                is BiliResult.Ok -> Unit
                is BiliResult.ApiError -> {
                    ifCurrent(startGeneration) { _addedToView.value = false }
                    BiliLog.w("toview/add 失败(${result.code}): ${result.message}")
                }
                is BiliResult.Failure -> {
                    ifCurrent(startGeneration) { _addedToView.value = false }
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
    private suspend fun loadSponsorSegments(target: String, cid: Long) {
        val prefs = settings.sponsorBlockPrefs.first()
        if (!prefs.enabled) {
            _sponsorSegments.value = emptyList()
            return
        }
        _sponsorSegments.value = sponsorBlockRepository
            .segments(target, cid, prefs.serverUrl)
            .filter { it.category in prefs.categories }
    }

    private val _relation = MutableStateFlow<VideoRelation?>(null)
    val relation: StateFlow<VideoRelation?> = _relation.asStateFlow()

    private val _favFolders = MutableStateFlow<List<FavFolder>>(emptyList())
    val favFolders: StateFlow<List<FavFolder>> = _favFolders.asStateFlow()

    /** 本次播放会话的起点,心跳要用同一个值,不能每次上报重新取。换视频时重置,见 [resetForNewVideo]。 */
    private var sessionStartTs = System.currentTimeMillis() / 1000

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

    /**
     * 弹幕设置整体透出,不拆成一条一条的 StateFlow。
     *
     * 拆开的话每加一个设置项就要在 VideoViewModel、VideoScreen、BilbyPlayer 三层各加一个平行
     * 参数,而这份设置按计划还要长出字号、速度、模式过滤、描边——平行参数多到一定数量之后,
     * "有没有漏传一个"就只能靠人眼比对。整体传一个对象,加设置项只动 [SettingsStore] 一处。
     *
     * **默认关**,持久化在 [SettingsStore];听视频页用不到——它没有画面。
     */
    private val _danmakuPrefs = MutableStateFlow(DanmakuPrefs())
    val danmakuPrefs: StateFlow<DanmakuPrefs> = _danmakuPrefs.asStateFlow()

    /**
     * 已拉到的弹幕池,累计追加、不去重合并(那是以后的事)。时间轴的编译不在这里——
     * 它需要 `measureWidth` 和画布像素宽度,两者都只在 Compose 层才有(见 BilbyPlayer.kt)。
     */
    private val _danmakuPool = MutableStateFlow<List<Danmaku>>(emptyList())
    val danmakuPool: StateFlow<List<Danmaku>> = _danmakuPool.asStateFlow()

    /**
     * mode 7 高级弹幕,与上面那个池分开。
     *
     * **不是"另一种弹幕",是另一套排布规则**:位置由作者写死的绝对坐标决定,不选轨、不判碰撞、
     * 不受显示区域约束,渲染也走独立的一层([dev.nihildigit.danmaku.SpecialDanmakuHost])。合进
     * 同一个池只会让滚动弹幕那条链路上到处是"这条是不是 7"的分支。
     */
    private val _specialDanmakuPool = MutableStateFlow<List<SpecialDanmaku>>(emptyList())
    val specialDanmakuPool: StateFlow<List<SpecialDanmaku>> = _specialDanmakuPool.asStateFlow()

    /**
     * 当前 cid 有分段没拉回来。**"这一段没人发弹幕"和"这一段没拉到"在画面上是同一个样子**
     * (都是空白),给 UI 一个能把两者分开的信号,否则用户只能看着空屏猜是网络还是视频本身。
     * 拉到任何一段就复位:后一次成功说明链路是通的。
     */
    private val _danmakuLoadFailed = MutableStateFlow(false)
    val danmakuLoadFailed: StateFlow<Boolean> = _danmakuLoadFailed.asStateFlow()

    /** 当前弹幕池所属的 cid,换 cid 时用来判断在飞的请求是否已经过期。 */
    private var danmakuCid = 0L

    /** 这一条 cid 已经请求过的分段号(1-based),防止播放进度在同一段内反复轮询时重复拉取。 */
    private val requestedDanmakuSegments = mutableSetOf<Int>()

    /** 这一条 cid 每个分段失败过几次,见 [fetchDanmakuSegment] 里为什么要记这个。 */
    private val danmakuSegmentFailures = mutableMapOf<Int, Int>()

    init {
        // ViewModel 级:偏好跨视频有效,只读一次,[switchTo] 不重读。
        viewModelScope.launch {
            _subtitleLan.value = settings.subtitlePrefs.first().lan
            subtitleLanLoaded.complete(Unit)
        }
        viewModelScope.launch {
            settings.danmakuPrefs
                .distinctUntilChanged()
                .collect { prefs ->
                    val changed = _danmakuPrefs.value.enabled != prefs.enabled
                    _danmakuPrefs.value = prefs
                    if (changed && prefs.enabled) fetchInitialDanmakuSegment(danmakuCid)
                }
        }
        startVideoJobs()
    }

    /**
     * 换一条视频(切集、自动连播、通知栏切歌)。
     *
     * **幂等**:目标就是当前这条时直接返回。调用方因此可以在每次重组时无脑喊一遍,不必自己
     * 记住上一次是哪条 —— 那份"上一次"正是以前用 `viewModel(key = ...)` 时散在 Compose 层的
     * 状态,而它管不了旧实例的死活。
     */
    fun switchTo(target: String) {
        if (target == bvid) return
        generation++
        // **先取消,再换 bvid。** 反过来的话,旧协程在真正被取消之前的那一瞬读到的已经是新
        // bvid,于是上一条视频的结果会被当作这一条的收下 —— 取消是有延迟的,顺序不是风格问题。
        videoJob.cancelChildren()
        bvid = target
        resetForNewVideo()
        startVideoJobs()
    }

    /**
     * 清掉一切属于上一条视频的状态。
     *
     * **只留两样**:[_subtitleLan] 是用户的选择,跨视频延续(新视频没有同语言的轨才关,见
     * [loadSubtitleTracks]);[_danmakuPrefs] 来自持久化偏好,本来就不属于
     * 任何一条视频。其余全部归零 —— 漏掉一个的表现就是切集后短暂显示上一条的标题、评论或字幕,
     * 而它只在慢网络下才看得见。
     */
    private fun resetForNewVideo() {
        _state.value = VideoUiState()
        _related.value = RelatedState()
        _followState.value = FollowState.None
        _staffFollowed.value = null
        _upCard.value = null
        _relation.value = null
        _favFolders.value = emptyList()
        _addedToView.value = false
        _sponsorSegments.value = emptyList()
        _subtitleTracks.value = emptyList()
        _subtitleCues.value = emptyList()
        _danmakuPool.value = emptyList()
        _specialDanmakuPool.value = emptyList()
        _danmakuLoadFailed.value = false
        danmakuCid = 0L
        requestedDanmakuSegments.clear()
        danmakuSegmentFailures.clear()
        lastDanmakuPositionMillis = 0L
        // 心跳的会话起点按视频算。留着上一条的 startTs,这一条的观看时长会从上一条开始的
        // 时刻算起,报上去的是一个越连播越离谱的数。
        sessionStartTs = System.currentTimeMillis() / 1000
    }

    /**
     * 起当前视频级的那几条协程。
     *
     * 每条都先把 [bvid] 捕获成局部 `target` 再用,协程体里不读那个 var:取消有延迟,读 var
     * 会让一条正在退出的旧协程拿到新 bvid 并自认是当前视频。
     */
    private fun startVideoJobs() {
        val target = bvid
        load(target)
        observeCurrentPart(target)
        observeDanmakuCid(target)
        observeSubtitleTracks(target)
    }

    /** 迟到的写结果只有在代数没变时才准落回页面,理由见 [generation]。 */
    private inline fun ifCurrent(generationAtStart: Int, block: () -> Unit) {
        if (generationAtStart == generation) block()
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
        _danmakuPrefs.update { it.copy(enabled = enabled) }
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
    private fun observeDanmakuCid(target: String) = videoScope.launch {
        AudioPlaybackService.state
            .map { if (it.queue?.current?.bvid == target) (it.queue?.currentCid ?: 0L) else 0L }
            .distinctUntilChanged()
            .collect { cid ->
                danmakuCid = cid
                requestedDanmakuSegments.clear()
                danmakuSegmentFailures.clear()
                _danmakuLoadFailed.value = false
                _danmakuPool.value = emptyList()
                _specialDanmakuPool.value = emptyList()
                // 进度也要跟着归零:留着上一条的位置,中途开弹幕会照那个位置去拉段号。
                lastDanmakuPositionMillis = 0L
                if (_danmakuPrefs.value.enabled) fetchInitialDanmakuSegment(cid)
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
        fetchDanmakuAround(cid, lastDanmakuPositionMillis)
    }

    /**
     * 播放进度驱动弹幕分段拉取。**不为此另起轮询**——播放页已经有一份(BilbyPlayer 每 5 秒
     * 回传一次进度用于心跳),这里挂在同一个回调上,详见 VideoScreen/MainActivity 的接线。
     */
    fun onDanmakuPlaybackPosition(positionMillis: Long) {
        // 位置无条件记下来:开关中途被打开时要靠它知道该从哪一段拉起。
        lastDanmakuPositionMillis = positionMillis
        if (!_danmakuPrefs.value.enabled) return
        fetchDanmakuAround(danmakuCid, positionMillis)
    }

    /** 最近一次进度回传。开关关着时也记,见 [onDanmakuPlaybackPosition]。 */
    private var lastDanmakuPositionMillis = 0L

    /**
     * 拉这个进度所在的分段,快到边界时把下一段一起拉回来。
     *
     * **预取的理由是分段边界必然出现的空窗**:分段拉取是懒的,走到 6 分钟整点才发请求,
     * 那一次请求往返期间屏上一条弹幕都没有,而这个破绽每 6 分钟准时来一次。提前量取
     * [DANMAKU_PREFETCH_LEAD_MILLIS],比进度回调的间隔(5 秒)宽出一个数量级,不至于
     * 正好跨过边界那一拍才想起来预取。代价是每段多提前几十秒发一个请求,请求总数不变
     * ——每个段号在一条 cid 上仍然只请求一次。
     */
    private fun fetchDanmakuAround(cid: Long, positionMillis: Long) {
        if (cid == 0L) return
        val segmentIndex = danmakuRepository.segmentIndexFor(positionMillis) + 1
        fetchDanmakuSegment(cid, segmentIndex)
        if (danmakuRepository.millisUntilNextSegment(positionMillis) <= DANMAKU_PREFETCH_LEAD_MILLIS) {
            fetchDanmakuSegment(cid, segmentIndex + 1)
        }
    }

    /**
     * 段号在**请求发起前**就登记进 [requestedDanmakuSegments],否则同一段的进度回调会连着
     * 发好几个重复请求。代价是失败的段号也会留在集合里:曾经因此 seek 回那一段永远没有弹幕,
     * 而且除了一行日志之外没有任何迹象——空段和失败段在 `List<Danmaku>` 上同形,现在由
     * [dev.bilby.api.BiliResult] 分开。
     *
     * 失败后把段号摘出来让它能重来,但**限次**:进度回调每 5 秒一次,一段 6 分钟,不限次就是
     * 对一个正在失败的接口每段重试 70 多回,这是风控最不该踩的形状。[DANMAKU_SEGMENT_RETRIES]
     * 次之后放弃这一段,失败状态交给 [danmakuLoadFailed] 说明。
     */
    private fun fetchDanmakuSegment(cid: Long, segmentIndex: Int) {
        if (!requestedDanmakuSegments.add(segmentIndex)) return
        // videoScope:换视频时这些请求要整组取消。下面的 danmakuCid 判定挡的是**同一条视频内
        // 换分 P**,取消挡不到它——那时 videoScope 不重建。两道判定各管一件事,都不能省。
        videoScope.launch {
            val result = danmakuRepository.getSegment(cid, segmentIndex)
            // 拉取期间可能已经切到别的 cid(切分 P、队列走到下一条)——那份结果不属于当前
            // 弹幕池。此时也不能再动 requestedDanmakuSegments:它已经被换 cid 清空并开始
            // 记录新那一条的段号了,按旧 cid 的结果去删,删掉的是新视频刚登记的段号。
            if (danmakuCid != cid) return@launch
            when (result) {
                is BiliResult.Ok -> {
                    val segment = result.value
                    if (segment.normal.isNotEmpty()) _danmakuPool.update { it + segment.normal }
                    if (segment.special.isNotEmpty()) _specialDanmakuPool.update { it + segment.special }
                    // 一次成功不代表全部拿到了:被彻底放弃的段仍然是缺的,标志要留着。
                    _danmakuLoadFailed.value = danmakuSegmentFailures.values.any { it >= DANMAKU_SEGMENT_RETRIES }
                }
                else -> {
                    val failures = (danmakuSegmentFailures[segmentIndex] ?: 0) + 1
                    danmakuSegmentFailures[segmentIndex] = failures
                    if (failures < DANMAKU_SEGMENT_RETRIES) {
                        requestedDanmakuSegments.remove(segmentIndex)
                    } else {
                        BiliLog.w("弹幕分段放弃重试 cid=$cid segment=$segmentIndex 失败 $failures 次")
                    }
                    _danmakuLoadFailed.value = true
                }
            }
        }
    }

    /** 撞 -412 时 [dev.bilby.data.SubtitleRepository] 会退避重试,见 [loadSubtitleTracks]。 */
    private var subtitleTracksJob: Job? = null

    /**
     * 字幕轨随 cid 变,原因和 [observeCurrentPart] 一样:分 P 各有各的轨,播放器全 app
     * 共用,队列走到别的视频上时不该把那一条的轨拉到这一页来。
     */
    private fun observeSubtitleTracks(target: String) = videoScope.launch {
        // 等持久化的语言读回来再开始跟 cid 走,理由见 [subtitleLanLoaded]。
        subtitleLanLoaded.await()
        AudioPlaybackService.state
            .map { if (it.queue?.current?.bvid == target) (it.queue?.currentCid ?: 0L) else 0L }
            .distinctUntilChanged()
            .collect { cid ->
                // 换 cid 就取消上一条还没跑完的加载——它可能正卡在限流退避的 delay 里。
                // 不取消的话,这个 collect 会等旧的退避结束(最坏 2 分钟)才轮到处理新 cid,
                // 表现为切视频之后字幕迟迟不出来,而退避本来只该拖慢它自己那一条。
                //
                // 这条 Job 挡的是**同一条视频内换分 P**;换视频由 videoScope 整组取消负责。
                subtitleTracksJob?.cancel()
                if (cid != 0L) {
                    subtitleTracksJob = videoScope.launch { loadSubtitleTracks(target, cid) }
                }
            }
    }

    private suspend fun loadSubtitleTracks(target: String, cid: Long) {
        val tracks = subtitleRepository.getTracks(target, cid)
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
        // videoScope:切走之后这一次拉取的结果不该再落到新视频的字幕上。落盘那一句在上面,
        // 走的是 NonCancellable,不受这里的取消影响。
        videoScope.launch { _subtitleCues.value = subtitleRepository.getCues(track.subtitleUrl) }
    }

    /**
     * 只在用户显式点击时才跑(DESIGN 2.3):它占的是官方"相关推荐"的位置,但不能有
     * 相关推荐的行为 —— 自动加载就等于把永不实现清单里的东西装了回来。
     */
    fun findRelated() {
        val detail = _state.value.detail ?: return
        val target = bvid
        _related.value = RelatedState(started = true, turn = AgentTurnState(running = true))
        videoScope.launch {
            agentLoop.run(AgentIntent.Related(target, detail.title, detail.up.name)).collect { event ->
                // 折叠规则与搜索页是同一份(agent/AgentTurn.kt)。这里曾经另写一份,
                // 而那份把 ToolFinished 整个吞了 —— 中间结果卡片在播放页从来没出现过。
                _related.update { it.copy(turn = it.turn.reduce(event)) }
            }
            _related.update { it.copy(turn = it.turn.copy(running = false)) }
        }
    }

    private fun load(target: String) = videoScope.launch {
        when (val detail = repository.getVideoDetail(target)) {
            is BiliResult.Ok -> {
                _state.update { it.copy(detail = detail.value, loading = false) }
                launch {
                    when (val follow = relationRepository.stateOf(detail.value.up.mid)) {
                        is BiliResult.Ok -> _followState.value = follow.value
                        else -> BiliLog.w("查关注状态失败: $follow")
                    }
                }
                // 只有联合投稿才问这一条:单人稿的 staff 是空的,关注态由上面那次 stateOf 负责。
                //
                // **UP 主自己也要问。** 联合投稿那一排里他和其他人一样只有一个加号,如果靠
                // followState 去判断,它初值是"未关注",他头上就会先冒一个加号再消失。
                val staffMids = detail.value.staff
                    .map { it.mid }
                    .let { if (detail.value.staff.isEmpty()) it else it + detail.value.up.mid }
                    .filter { it != 0L }
                    .distinct()
                if (staffMids.isNotEmpty()) {
                    launch {
                        when (val followed = relationRepository.followedAmong(staffMids)) {
                            is BiliResult.Ok -> {
                                // 只记数量,不记 mid:这一条是给冒烟用的,用来分辨"查通了但一个
                                // 都没关注"和"根本没查通"——两者在界面上都是一个加号都不显示。
                                BiliLog.d("联合投稿关注态: ${followed.value.size}/${staffMids.size}")
                                _staffFollowed.value = followed.value
                            }
                            // 查不到就保持 null:宁可一个加号都不显示,也好过把已关注的人显示成未关注。
                            else -> BiliLog.w("查联合投稿关注状态失败: $followed")
                        }
                    }
                }
                launch {
                    when (val card = repository.getMemberCard(detail.value.up.mid)) {
                        is BiliResult.Ok -> _upCard.value = card.value
                        is BiliResult.ApiError -> BiliLog.w("查 UP 主等级失败(${card.code}): ${card.message}")
                        is BiliResult.Failure -> BiliLog.w("查 UP 主等级异常", card.cause)
                    }
                }
                when (val rel = actionRepository.getRelation(target)) {
                    is BiliResult.Ok -> _relation.value = rel.value
                    is BiliResult.ApiError -> BiliLog.w("查互动状态失败(${rel.code}): ${rel.message}")
                    is BiliResult.Failure -> BiliLog.w("查互动状态异常: ${rel.cause}")
                }
            }

            is BiliResult.ApiError -> fail(target, "${detail.message}(${detail.code})")
            is BiliResult.Failure -> fail(target, detail.cause.message ?: "网络错误")
        }
    }

    /**
     * 片段随 cid 变,所以跟着服务那边正在播的分 P 重拉,而不是页面自己记一份 cid。
     *
     * 只认属于本页这条视频的 cid:播放器是全 app 共用的,队列走到别的视频上时不该把
     * 那一条的片段拉到这一页来。
     */
    private fun observeCurrentPart(target: String) = videoScope.launch {
        AudioPlaybackService.state
            .map { if (it.queue?.current?.bvid == target) (it.queue?.currentCid ?: 0L) else 0L }
            .distinctUntilChanged()
            .collect { cid -> if (cid != 0L) loadSponsorSegments(target, cid) }
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
        if (playback.queue?.current?.bvid != bvid) return
        val cid = (playback.queue?.currentCid ?: 0L).takeIf { it != 0L } ?: return
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
        val startGeneration = generation
        viewModelScope.launch {
            when (val result = actionRepository.like(aid, !current.liked)) {
                is BiliResult.Ok -> Unit
                is BiliResult.ApiError -> {
                    BiliLog.w("点赞失败(${result.code}): ${result.message}")
                    ifCurrent(startGeneration) { _relation.value = current }
                }

                is BiliResult.Failure -> {
                    BiliLog.w("点赞异常: ${result.cause}")
                    ifCurrent(startGeneration) { _relation.value = current }
                }
            }
        }
    }

    fun coin(count: Int, alsoLike: Boolean) {
        val current = _relation.value ?: return
        val aid = _state.value.detail?.aid ?: return
        val startGeneration = generation
        viewModelScope.launch {
            when (val result = actionRepository.coin(aid, count, alsoLike)) {
                is BiliResult.Ok -> ifCurrent(startGeneration) {
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
        val startGeneration = generation
        viewModelScope.launch {
            val mid = settings.credentials.first().dedeUserId.toLongOrNull() ?: return@launch
            when (val result = actionRepository.listFavFolders(mid, aid)) {
                is BiliResult.Ok -> ifCurrent(startGeneration) { _favFolders.value = result.value }
                is BiliResult.ApiError -> BiliLog.w("查收藏夹失败(${result.code}): ${result.message}")
                is BiliResult.Failure -> BiliLog.w("查收藏夹异常: ${result.cause}")
            }
        }
    }

    fun confirmFavorite(addIds: List<Long>, delIds: List<Long>) {
        val aid = _state.value.detail?.aid ?: return
        val startGeneration = generation
        viewModelScope.launch {
            when (val result = actionRepository.favorite(aid, addIds, delIds)) {
                is BiliResult.Ok -> ifCurrent(startGeneration) {
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

    private fun fail(target: String, message: String) {
        BiliLog.w("播放页失败($target): $message")
        _state.update { it.copy(loading = false, error = message) }
    }

    private companion object {
        /** 距离分段边界多久开始预取下一段,见 [fetchDanmakuAround]。 */
        const val DANMAKU_PREFETCH_LEAD_MILLIS = 30_000L

        /** 一个分段最多请求几次,见 [fetchDanmakuSegment]。 */
        const val DANMAKU_SEGMENT_RETRIES = 3
    }
}
