package com.bilby.ui.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilby.BiliLog
import com.bilby.agent.AgentEvent
import com.bilby.agent.AgentIntent
import com.bilby.agent.AgentLoop
import com.bilby.api.BiliResult
import com.bilby.data.SettingsStore
import com.bilby.data.SponsorBlockRepository
import com.bilby.data.SponsorSegment
import kotlinx.coroutines.flow.first
import com.bilby.data.PlayInfo
import com.bilby.data.FavFolder
import com.bilby.data.HeartbeatReporter
import com.bilby.data.VideoActionRepository
import com.bilby.data.VideoDetail
import com.bilby.data.VideoRelation
import com.bilby.data.VideoRepository
import com.bilby.data.VideoStat
import com.bilby.data.db.PlaybackProgressDao
import com.bilby.data.db.PlaybackProgressEntity
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
) : ViewModel() {

    /** 赞助/片头片尾片段,默认开启自动跳过。拉取失败就是空列表,不影响播放。 */
    private val _sponsorSegments = MutableStateFlow<List<SponsorSegment>>(emptyList())
    val sponsorSegments: StateFlow<List<SponsorSegment>> = _sponsorSegments.asStateFlow()

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
                        is AgentEvent.Answer -> current.copy(answer = event.items, running = false)
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
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            when (val play = repository.getPlayUrl(bvid, cid, preferredQuality = quality)) {
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
        launch { _sponsorSegments.value = sponsorBlockRepository.segments(bvid, cid) }
        when (val play = repository.getPlayUrl(bvid, cid)) {
            is BiliResult.Ok -> {
                val local = progressDao.get(bvid)?.takeIf { it.cid == cid }?.positionMillis ?: 0
                _state.update {
                    it.copy(
                        playInfo = play.value,
                        // 本地进度与服务端 last_play_time 取较大者:我们不上报心跳时服务端那份
                        // 会停在别的客户端留下的位置,取大的一方总是更接近"我看到哪了"。
                        resumeAtMillis = maxOf(local, play.value.lastPlayTimeMillis),
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
