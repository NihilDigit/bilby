package com.bilby.ui.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bilby.api.BiliResult
import com.bilby.data.PlayInfo
import com.bilby.data.VideoDetail
import com.bilby.data.VideoRepository
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
    val loading: Boolean = true,
    val error: String? = null,
)

class VideoViewModel(
    private val bvid: String,
    private val repository: VideoRepository,
    private val progressDao: PlaybackProgressDao,
) : ViewModel() {

    private val _state = MutableStateFlow(VideoUiState())
    val state: StateFlow<VideoUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() = viewModelScope.launch {
        when (val detail = repository.getVideoDetail(bvid)) {
            is BiliResult.Ok -> {
                _state.update { it.copy(detail = detail.value) }
                playPart(detail.value.cid)
            }

            is BiliResult.ApiError -> fail("${detail.message}(${detail.code})")
            is BiliResult.Failure -> fail(detail.cause.message ?: "网络错误")
        }
    }

    /** 合集/多 P 的确定性导航(DESIGN 2.3:这是导航不是推荐)。 */
    fun playPart(cid: Long) = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null, currentCid = cid) }
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
