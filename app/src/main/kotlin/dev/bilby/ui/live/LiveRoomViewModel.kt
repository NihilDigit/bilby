package dev.bilby.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.LiveGuardItemDto
import dev.bilby.danmaku.danmakuModeOrNull
import dev.bilby.data.LiveRepository
import dev.bilby.data.LiveRoomPlayback
import dev.bilby.live.LiveDanmakuClient
import dev.bilby.live.LiveMessage
import dev.nihildigit.danmaku.Danmaku
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 聊天栏里的一行。和弹幕是同一条消息的两种呈现,所以共用 id,不各自编号。 */
data class LiveChatLine(
    val id: String,
    val name: String,
    val text: String,
    val colorRgb: Int,
    val isSelf: Boolean,
)

data class LiveGuardsState(
    val items: List<LiveGuardItemDto> = emptyList(),
    /** 已经拉到第几页。0 表示还没拉过。 */
    val page: Int = 0,
    val loading: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

data class LiveRoomUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val title: String = "",
    val anchorName: String = "",
    val anchorFace: String = "",
    val anchorMid: Long = 0L,
    val coverUrl: String = "",
    val online: Long = 0L,
    /** 未开播时为 false:界面显示封面和一句话,不去连弹幕流。 */
    val isLive: Boolean = false,
    val streamUrl: String? = null,
    val chat: List<LiveChatLine> = emptyList(),
    val superChats: List<LiveMessage.SuperChat> = emptyList(),
    val guards: LiveGuardsState = LiveGuardsState(),
)

/**
 * 直播间。
 *
 * **弹幕不进 [state]。** 它走 [danmaku] 这条独立的流:聊天栏是一份有界列表,每来一条就整份
 * 重发是可以接受的;而弹幕每秒可能几十条,塞进 UI state 会让整页跟着重组。渲染层拿到的是
 * 逐条到达的流,由它用播放器位置打戳后追加进时间轴(见 `DanmakuFeed.Stream`)。
 */
class LiveRoomViewModel(
    private val roomId: Long,
    private val repository: LiveRepository,
    private val danmakuClient: LiveDanmakuClient,
    private val selfMid: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(LiveRoomUiState())
    val state: StateFlow<LiveRoomUiState> = _state.asStateFlow()

    /**
     * 弹幕内容流。`playTimeMillis` 恒为 0,**由渲染层重打** —— 服务端时间戳和播放器时间轴
     * 不是同一根,而渲染层手里就有播放器位置。
     *
     * 满了丢最旧的:高能时刻宁可少显示几条,也不能让弹幕把整条链路堵住。
     */
    private val _danmaku = MutableSharedFlow<Danmaku>(
        extraBufferCapacity = DANMAKU_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val danmaku: SharedFlow<Danmaku> = _danmaku.asSharedFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }

            val info = repository.loadRoomInfo(roomId)
            if (info is BiliResult.Ok) {
                val room = info.value.roomInfo
                val anchor = info.value.anchorInfo?.baseInfo
                _state.update {
                    it.copy(
                        title = room?.title.orEmpty(),
                        coverUrl = room?.cover.orEmpty(),
                        online = room?.online ?: 0L,
                        anchorMid = room?.uid ?: 0L,
                        anchorName = anchor?.uname.orEmpty(),
                        anchorFace = anchor?.face.orEmpty(),
                    )
                }
            }

            when (val playback = repository.loadPlayback(roomId)) {
                is BiliResult.Ok -> {
                    val value = playback.value
                    _state.update {
                        it.copy(
                            loading = false,
                            isLive = value.isLive,
                            streamUrl = value.stream?.url,
                            anchorMid = if (value.uid != 0L) value.uid else it.anchorMid,
                        )
                    }
                    if (value.isLive) connectDanmaku()
                    loadMoreGuards()
                }

                is BiliResult.ApiError -> _state.update {
                    it.copy(loading = false, error = playback.message)
                }

                is BiliResult.Failure -> _state.update {
                    it.copy(loading = false, error = playback.cause.message)
                }
            }
        }
    }

    /**
     * 连上信息流并一直收到页面离开为止。断线重连由客户端自己管,这里只负责分流:
     * 弹幕走独立的流,聊天与醒目留言进 [state]。
     */
    private fun connectDanmaku() {
        viewModelScope.launch {
            danmakuClient.messages(roomId, selfMid).collect { message ->
                when (message) {
                    is LiveMessage.Danmaku -> onDanmaku(message)
                    is LiveMessage.SuperChat -> _state.update {
                        // 新的在前:醒目留言是一条一条读的,最新那条该在手边。
                        it.copy(superChats = (listOf(message) + it.superChats).take(MAX_SUPER_CHATS))
                    }
                }
            }
        }
    }

    private suspend fun onDanmaku(message: LiveMessage.Danmaku) {
        _state.update {
            // 聊天栏有界:一场直播几万条,留着只是等着被 OOM。新的追在末尾,列表自己滚到底。
            val next = it.chat + LiveChatLine(
                id = message.id,
                name = message.senderName,
                text = message.text,
                colorRgb = message.colorRgb,
                isSelf = message.isSelf,
            )
            it.copy(chat = next.takeLast(MAX_CHAT_LINES))
        }

        // 认不出的模式号(代码弹幕之类)只进聊天栏,不上屏。
        val mode = danmakuModeOrNull(message.mode) ?: return
        _danmaku.emit(
            Danmaku(
                id = message.id,
                playTimeMillis = 0L,
                mode = mode,
                color = message.colorRgb,
                text = message.text,
                isSelf = message.isSelf,
            ),
        )
    }

    /** 大航海按页拉。首次进入也走它,所以 [LiveGuardsState.page] 从 0 起。 */
    fun loadMoreGuards() {
        val guards = _state.value.guards
        if (guards.loading || !guards.hasMore) return
        _state.update { it.copy(guards = it.guards.copy(loading = true, error = null)) }

        viewModelScope.launch {
            val anchorMid = _state.value.anchorMid
            if (anchorMid == 0L) {
                // ruid 是主播 mid,拿不到就别发 —— 传 0 不会报错,只会恒定返回空列表,
                // 表现成"这个主播没有大航海",而那是假的。
                _state.update {
                    it.copy(guards = it.guards.copy(loading = false, hasMore = false))
                }
                return@launch
            }
            when (val page = repository.loadGuardPage(anchorMid, guards.page + 1)) {
                is BiliResult.Ok -> _state.update {
                    it.copy(
                        guards = it.guards.copy(
                            items = it.guards.items + page.value.items,
                            page = it.guards.page + 1,
                            loading = false,
                            hasMore = page.value.hasMore,
                        ),
                    )
                }

                is BiliResult.ApiError -> _state.update {
                    it.copy(guards = it.guards.copy(loading = false, error = page.message))
                }

                is BiliResult.Failure -> _state.update {
                    it.copy(guards = it.guards.copy(loading = false, error = page.cause.message))
                }
            }
        }
    }

    private companion object {
        const val MAX_CHAT_LINES = 200
        const val MAX_SUPER_CHATS = 30
        const val DANMAKU_BUFFER = 256
    }
}
