package dev.bilby.ui.live

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.api.toHttpsUrl
import dev.bilby.ui.errorTextRes
import dev.bilby.api.dto.LiveGuardItemDto
import dev.bilby.danmaku.danmakuModeOrNull
import dev.bilby.data.ArchivedSuperChat
import dev.bilby.data.DanmakusRepository
import dev.bilby.data.FollowState
import dev.bilby.data.RelationRepository
import dev.bilby.data.LiveRepository
import dev.bilby.data.LiveRoomPlayback
import dev.bilby.live.LiveDanmakuClient
import dev.bilby.live.LiveEmote
import dev.bilby.live.LiveFanMedal
import dev.bilby.live.LiveMessage
import dev.bilby.live.findEmotes
import dev.nihildigit.danmaku.Danmaku
import dev.nihildigit.danmaku.DanmakuImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 消息流里的一行。
 *
 * 弹幕、醒目留言、上舰、系统提示排在同一条流里,因为它们在读者眼里就是一条时间线 —— 分成几个
 * 列表会让"这句话是在谁上舰之后说的"读不出来。各类的差异收敛在渲染层的槽位内容上,见
 * `ui/live/LiveFeedRow.kt`。
 *
 * [id] 兼作 `LazyColumn` 的 key,所以必须在一场直播内唯一。
 */
sealed interface LiveFeedItem {
    val id: String

    /** 一条弹幕。和画面上飘过的那条是同一条消息的两种呈现,所以共用 id,不各自编号。 */
    data class Danmaku(
        override val id: String,
        val mid: Long,
        val name: String,
        val face: String,
        val text: String,
        val colorRgb: Int,
        val isSelf: Boolean,
        val medal: LiveFanMedal?,
        val emote: LiveEmote?,
        val inlineEmotes: Map<String, LiveEmote>,
        val replyName: String?,
    ) : LiveFeedItem

    /**
     * 一条醒目留言。
     *
     * [removed] 为真表示被撤回。**流里那条留着并加删除线,不移除** —— 流是这个房间发生过什么的
     * 记录,抹掉一条会让上下文断开(旁边的弹幕常常正在回应它)。汇总屏那份直接移除,那里说的是
     * "此刻还有效的留言"。
     */
    data class SuperChat(
        val message: LiveMessage.SuperChat,
        val removed: Boolean = false,
    ) : LiveFeedItem {
        override val id: String get() = "sc-${message.id}"
    }

    /**
     * 有人上舰。
     *
     * [months] 只有 `USER_TOAST_MSG` 那条带,`GUARD_BUY` 给不了(它的 `num` 是礼物个数)。
     * 两条命令为同一次上舰同时下发,合并见 `LiveRoomViewModel.onGuard`。
     *
     * [id] 不带时间戳:去重正是靠"同一个人同一档只有一行"。
     */
    data class Guard(
        val mid: Long,
        val name: String,
        /** 1 总督 / 2 提督 / 3 舰长。**数越小等级越高。** */
        val guardLevel: Int,
        val months: Int?,
        /** 到达时刻,用于判断下一条同键的消息是不是同一次上舰。 */
        val atMillis: Long,
    ) : LiveFeedItem {
        override val id: String get() = "guard-$mid-$guardLevel-$atMillis"
    }

    /**
     * 系统提示。文案由渲染层按 [kind] 从 `strings.xml` 取,只有超管那两条用服务端的原话
     * ([message]) —— 那句话是"图片内容不适宜,请立即调整"这种具体指示,本地改写只会丢信息。
     */
    data class Notice(
        override val id: String,
        val kind: Kind,
        val message: String? = null,
    ) : LiveFeedItem {
        /**
         * [Disconnected] / [Reconnected] 说的不是房间里发生了什么,而是**这条连接**的事。
         * 仍然走同一个 [Notice]:它们出现的位置就该在流里、按时间排 —— 断线那一刻之前的
         * 消息读到了,之后的没读到,而这个分界只有在流里才说得清。
         */
        enum class Kind { LiveStarted, LiveEnded, Warning, CutOff, SelfBlocked, Disconnected, Reconnected }
    }
}

data class LiveGuardsState(
    val items: List<LiveGuardItemDto> = emptyList(),
    /** 已经拉到第几页。0 表示还没拉过。 */
    val page: Int = 0,
    val loading: Boolean = false,
    val hasMore: Boolean = true,
    @StringRes val error: Int? = null,
)

data class LiveRoomUiState(
    val loading: Boolean = true,
    /** 失败说哪一句,存的是资源 id。映射与理由见 [dev.bilby.ui.errorTextRes]。 */
    @StringRes val error: Int? = null,
    val title: String = "",
    val anchorName: String = "",
    val anchorFace: String = "",
    val anchorMid: Long = 0L,
    val coverUrl: String = "",
    /**
     * 「N 人看过」,服务端拼好的整句。**不是人气值** —— 那个数(room_info.online)是按互动
     * 算的分数,送礼和弹幕都会推高它,写成"人"是错的。空串表示还没拿到,那一行就不画。
     */
    val watched: String = "",
    /** 未开播时为 false:界面显示封面和一句话,不去连弹幕流。 */
    val isLive: Boolean = false,
    /** 这一场的开播时刻,秒。没在播或拿不到时为 null,那一行不画。 */
    val liveStartedAt: Long? = null,
    /**
     * **只用来判断这个房间此刻有没有流可放**,不交给播放器 —— 取流归服务(见
     * `AudioPlaybackService.resolveLiveStream`):直播地址带时效,页面这一份等到重试、切档、
     * 切纯音频时早已经是一条对不上的旧地址。
     */
    val streamUrl: String? = null,
    /** 这个房间能选的清晰度档位;空表示不出入口。 */
    val qualities: List<Int> = emptyList(),
    /** 服务端实际给的档位。请求 A 拿到 B 是正常的,界面要显示拿到的那个。 */
    val currentQn: Int = 0,
    /**
     * 高能榜人数。**和 `watched`(N 人看过)是两个数**:那个是来过多少人,这个是此刻榜上有
     * 多少人。0 表示还没收到过这条命令,那时不画。
     */
    val onlineRank: Int = 0,
    val feed: List<LiveFeedItem> = emptyList(),
    /**
     * **本场全部的醒目留言,一份日志。** 进房时拉一次历史打底(danmakus 的本场记录 +
     * 官方 `getMessageList` 里还生效的那些),之后长连接收到的一条条追加进来。
     *
     * 界面上的两节都从这一份派生:`endTimeSeconds > now` 的是「生效中」,其余是「本场早前」。
     * **从前它是两份**——一份"此刻还生效"、一份进房那一刻拉的历史——于是进房后收到、又在你
     * 眼皮底下过期的那些 SC 从两边同时掉出去,待得越久丢得越多。
     *
     * 和 [feed] 里的醒目留言仍是同一批消息的两种用途:流是"发生过什么"的记录(撤回的那条留在
     * 那里加删除线),这里是清单(撤回的直接移除)。
     */
    val sessionSuperChats: List<LiveMessage.SuperChat> = emptyList(),
    /** 「醒目留言」那一屏正在补进房前的历史。 */
    val superChatsLoading: Boolean = false,
    /**
     * 这一份里有没有 danmakus 补来的内容。**只决定要不要标那行来源** —— 开关关着或那边没数据
     * 时,「本场早前」里剩下的是你在场期间过期的那些,标"来自 danmakus.com"就是句假话。
     */
    val hasArchive: Boolean = false,
    val guards: LiveGuardsState = LiveGuardsState(),
    /** 这一条正在发。发送中输入栏不可再发,避免连点发出两条。 */
    val sendingDanmaku: Boolean = false,
    /**
     * 上一次发送失败的原因。**留在这儿直到下一次发送**,不自动消失:直播间没有别的地方
     * 说这句话,而失败的常见原因(等级不够、房间禁言、被风控)都需要人读一眼才知道下一步。
     */
    val sendError: String? = null,
    /** 主播的关注态。null = 还没查到(或主播 mid 还不知道),那时不画关注按钮。 */
    val followState: FollowState? = null,
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
    private val settings: dev.bilby.data.SettingsStore,
    private val danmakusRepository: DanmakusRepository,
    /** 一批 mid 的头像。danmakus 补来的醒目留言不带头像,见 [loadArchivedSuperChats]。 */
    private val userFaces: suspend (Collection<Long>) -> Map<Long, String>,
    private val relationRepository: RelationRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LiveRoomUiState())
    val state: StateFlow<LiveRoomUiState> = _state.asStateFlow()

    /** 系统提示的本地编号,见 [pushNotice]。 */
    private var noticeSeq = 0L

    /** 信息流那条连接,见 [connectDanmaku]。 */
    private var danmakuJob: Job? = null

    /** 最近一条消息到达的时刻。null 表示一条都还没来过,见 [watchConnection]。 */
    private var lastMessageAtMillis: Long? = null

    /**
     * 此刻认为连着。**开场就是 true** —— 进房那几秒还没有消息,但那不叫"断开";真的断了由
     * [watchConnection] 判。
     */
    private var connected = true

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
                        watched = info.value.watchedShow?.textLarge.orEmpty(),
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
                            liveStartedAt = value.liveStartEpochSeconds,
                            streamUrl = value.stream?.url,
                            qualities = value.stream?.acceptQn.orEmpty(),
                            currentQn = value.stream?.qn ?: 0,
                            anchorMid = if (value.uid != 0L) value.uid else it.anchorMid,
                        )
                    }
                    // **信息流不看开没开播。** 中途开播这件事本身是从这条连接上来的
                    // (LIVE,见 notes/live.md §10.3),未开播就不连的话,未开播时进的房
                    // 永远等不到开播,只能退出去再进来。
                    connectDanmaku()
                    loadFollowState()
                    // 两份历史仍然只在开播时拉:未开播时「本场」指的是上一场。
                    if (value.isLive) {
                        loadSuperChatHistory()
                        loadArchivedSuperChats()
                    }
                    loadMoreGuards()
                }

                // 接口原话和异常 message 都不上屏,收成三句里的一句;原文与错误码由
                // errorTextRes 打进 BiliLog(见 dev.bilby.ui.errorTextRes)。这一句会画在
                // 封面正中央的那块遮罩上,而在那儿最该回答的是"是没开播,还是我这边没读到"。
                else -> _state.update {
                    it.copy(loading = false, error = playback.errorTextRes("直播间取流"))
                }
            }
        }
    }

    /**
     * 补上进房之前发出、此刻仍在有效期内的醒目留言。
     *
     * **只进汇总屏那份清单,不进流。** 流是"进来之后发生了什么"的记录,把十条几分钟前的留言
     * 一次性倒进它的开头,读起来像是刚刚同时发生的。
     *
     * 拉一次就够,不重试:长连接接着会把新的推过来,而这一份的作用只是填上开头那个空档。失败时
     * 汇总屏是空的,和这个房间本来就没有醒目留言看起来一样 —— 这一处不值得为区分两者加一行错误。
     */
    /**
     * 本场早前的醒目留言,来自 danmakus.com。
     *
     * **偏好关着就一个请求都不发**(见 SettingsStore.danmakusArchiveEnabled)。主播 mid 要等
     * `loadRoomInfo` 回来才有,所以这一步排在取流之后。
     *
     * 拉一次,不重试也不轮询:这一份是进房之前那段的补全,进房之后发生的由长连接负责。
     */
    private fun loadArchivedSuperChats() {
        viewModelScope.launch {
            if (!settings.danmakusArchiveEnabled.first()) {
                BiliLog.d("danmakus 本场早前:开关关着,不请求")
                return@launch
            }
            val mid = _state.value.anchorMid
            val archived = danmakusRepository.currentSessionSuperChats(mid)
            // 这一节拿不到时界面上什么都不会有,不打这行就查不出是哪一步没成 ——
            // mid 为 0(房间信息没给出主播)和"这位主播没被收录"在界面上长得一模一样。
            BiliLog.d("danmakus 本场早前:mid=$mid 取到 ${archived.size} 条")
            if (archived.isEmpty()) return@launch
            _state.update { state ->
                state.copy(
                    sessionSuperChats = state.sessionSuperChats.mergeSuperChats(archived.map { it.toMessage() }),
                    hasArchive = true,
                )
            }
            // 先上屏再补头像:列表不必等这一轮,头像到了再换进去。查不到的仍是空串,照旧不画。
            val faces = userFaces(archived.map { it.senderMid })
            BiliLog.d("danmakus 本场早前:补到 ${faces.size} 个头像")
            if (faces.isEmpty()) return@launch
            _state.update { state ->
                state.copy(
                    sessionSuperChats = state.sessionSuperChats.map { sc ->
                        if (sc.senderFace.isEmpty()) {
                            faces[sc.senderMid]?.let { sc.copy(senderFace = it) } ?: sc
                        } else {
                            sc
                        }
                    },
                )
            }
        }
    }

    /** 主播的关注态。房间信息里只有 mid,关系要另查,同播放页(VideoViewModel)。 */
    private fun loadFollowState() {
        val mid = _state.value.anchorMid
        if (mid == 0L || _state.value.followState != null) return
        viewModelScope.launch {
            when (val result = relationRepository.stateOf(mid)) {
                is BiliResult.Ok -> _state.update { it.copy(followState = result.value) }
                else -> BiliLog.w("查主播关注状态失败: $result")
            }
        }
    }

    /**
     * 关注或取关主播。**乐观更新、失败回滚、不重拉**,同播放页的 toggleFollow;互关取关后
     * 退回"未关注",理由也同那边。
     */
    fun toggleFollow() {
        val mid = _state.value.anchorMid
        val current = _state.value.followState ?: return
        if (mid == 0L || current == FollowState.Self || current == FollowState.Blocked) return
        val following = current.isFollowing
        _state.update { it.copy(followState = if (following) FollowState.None else FollowState.Following) }
        viewModelScope.launch {
            val result = if (following) relationRepository.unfollow(mid) else relationRepository.follow(mid)
            if (result !is BiliResult.Ok) {
                BiliLog.w("${if (following) "取关主播" else "关注主播"}失败: $result")
                _state.update { it.copy(followState = current) }
            }
        }
    }

    private fun loadSuperChatHistory() {
        viewModelScope.launch {
            _state.update { it.copy(superChatsLoading = true) }
            val result = repository.loadSuperChats(roomId)
            _state.update { state ->
                val loaded = state.copy(superChatsLoading = false)
                if (result is BiliResult.Ok) {
                    loaded.copy(sessionSuperChats = loaded.sessionSuperChats.mergeSuperChats(result.value))
                } else {
                    loaded
                }
            }
        }
    }

    /**
     * 连上信息流并一直收到页面离开为止。断线重连由客户端自己管,这里只负责分流:
     * 弹幕走独立的流,聊天与醒目留言进 [state]。
     */
    private fun connectDanmaku() {
        // 失败面板上的重试会再走一遍 [load],而断线重连归客户端自己管(见
        // LiveDanmakuClient.messages),这里再连一次只会多出一个收集器,每条弹幕上屏两遍。
        if (danmakuJob?.isActive == true) return
        danmakuJob = viewModelScope.launch {
            // **登录态在这里现取**,不在构造时捕获:凭据是个 flow,VM 建出来那一刻它可能
            // 还没发出第一个值,拿到的是 0。用登录态签出来的 token 配一个 uid=0 的认证包,
            // 服务端有理由拒绝,而被拒之后它只是把连接关掉。
            val selfMid = settings.credentials.first().dedeUserId.toLongOrNull() ?: 0L
            launch { watchConnection() }
            danmakuClient.messages(roomId, selfMid).collect { message ->
                lastMessageAtMillis = System.currentTimeMillis()
                onReconnected()
                when (message) {
                    is LiveMessage.Danmaku -> onDanmaku(message)
                    // 进房时那一份只在那一刻准,之后靠这条命令跟。人气值(Popularity)不再显示,
                    // 但仍然收着 —— 它是心跳回包本身,丢掉会让心跳那条分支无处落地。
                    is LiveMessage.Watched -> _state.update { it.copy(watched = message.text) }
                    is LiveMessage.OnlineRankCount -> _state.update { it.copy(onlineRank = message.count) }
                    is LiveMessage.Popularity -> Unit

                    is LiveMessage.SuperChat -> onSuperChat(message)
                    is LiveMessage.SuperChatRemoved -> onSuperChatRemoved(message.ids)
                    is LiveMessage.GuardBuy -> onGuard(message)

                    // 主播中途改标题:静默换掉,不在流里留一行。这是页面头部的一个属性变了,
                    // 而流里那些是"发生了什么"。
                    is LiveMessage.RoomTitleChanged ->
                        _state.update { it.copy(title = message.title) }

                    is LiveMessage.LiveStateChanged -> onLiveStateChanged(message.live)

                    is LiveMessage.Warning -> pushNotice(
                        kind = if (message.cutOff) {
                            LiveFeedItem.Notice.Kind.CutOff
                        } else {
                            LiveFeedItem.Notice.Kind.Warning
                        },
                        message = message.message,
                    )

                    // **别人被禁言与这个用户无关。** 自己被禁言之后发弹幕会一直失败,不说一声
                    // 就只剩一个没有原因的错误。
                    is LiveMessage.Blocked ->
                        if (message.uid == selfMid) {
                            pushNotice(LiveFeedItem.Notice.Kind.SelfBlocked)
                        }
                }
            }
        }
    }

    private suspend fun onDanmaku(message: LiveMessage.Danmaku) {
        append(
            LiveFeedItem.Danmaku(
                id = message.id,
                mid = message.senderMid,
                name = message.senderName,
                face = message.senderFace,
                text = message.text,
                colorRgb = message.colorRgb,
                isSelf = message.isSelf,
                medal = message.medal,
                emote = message.emote,
                inlineEmotes = message.inlineEmotes,
                replyName = message.replyName,
            ),
        )

        // 认不出的模式号(代码弹幕之类)只进流,不上屏。
        val mode = danmakuModeOrNull(message.mode) ?: return
        _danmaku.emit(
            Danmaku(
                id = message.id,
                playTimeMillis = 0L,
                mode = mode,
                color = message.colorRgb,
                text = message.text,
                isSelf = message.isSelf,
                images = danmakuImages(message),
            ),
        )
    }

    /**
     * 屏上弹幕里画成图的那几段,键是图的地址(见 `LiveEmoteImageSource`)。整条就是一张图时盖住
     * 全文,那串文字是表情的代号,读者要看的是图,和聊天栏同一个处理。
     */
    private fun danmakuImages(message: LiveMessage.Danmaku): List<DanmakuImage> {
        message.emote?.let { emote ->
            if (message.text.isEmpty()) return emptyList()
            return listOf(emote.toDanmakuImage(0, message.text.length, STICKER_HEIGHT_EM))
        }
        return findEmotes(message.text, message.inlineEmotes).map {
            it.emote.toDanmakuImage(it.start, it.end, INLINE_EMOTE_HEIGHT_EM)
        }
    }

    private fun LiveEmote.toDanmakuImage(start: Int, end: Int, heightEm: Float): DanmakuImage {
        val aspect = if (widthPx > 0 && heightPx > 0) widthPx.toFloat() / heightPx else 1f
        return DanmakuImage(start, end, key = url.toHttpsUrl(), widthEm = heightEm * aspect, heightEm = heightEm)
    }

    /**
     * 追一行到流的末尾。
     *
     * **流有界:一场直播几万条,留着只是等着被 OOM。** 新的追在末尾,列表自己滚到底
     * (见 `LiveRoomScreen` 里那段跟到底的说明)。
     */
    private fun append(item: LiveFeedItem) {
        _state.update { it.copy(feed = (it.feed + item).takeLast(MAX_FEED_ITEMS)) }
    }

    /**
     * 连接活着没有。**判据是心跳,不是连接对象本身。**
     *
     * `LiveDanmakuClient.messages` 把断线和重连整个包在自己里面(见那个函数):失败被
     * `runCatching` 吃掉、退避之后重连,流本身既不结束也不抛,所以这一层看不到任何事件。
     * 能看到的是**没东西来了** —— 那条连接每 30 秒发一次心跳,回包带着人气值上来
     * (`LiveMessage.Popularity`),所以"超过两轮心跳没有任何消息"就是断了。房间再冷也不影响
     * 这个判据,心跳与有没有人说话无关。
     *
     * 改这个超时之前先看 `LiveDanmakuClient.HEARTBEAT_INTERVAL_MILLIS`:两者是一对。
     */
    private suspend fun watchConnection() {
        while (true) {
            delay(CONNECTION_CHECK_INTERVAL_MILLIS)
            val last = lastMessageAtMillis ?: continue
            if (System.currentTimeMillis() - last > CONNECTION_TIMEOUT_MILLIS) onDisconnected()
        }
    }

    /**
     * 断了。**只在第一次断的时候留一行**,超时之后每轮检查都再留一行的话,网一直不好就是
     * 满屏「连接已断开」。
     */
    private fun onDisconnected() {
        if (!connected) return
        connected = false
        pushNotice(LiveFeedItem.Notice.Kind.Disconnected)
    }

    /** 又有消息了。**开场第一条不算重连** —— 那时还没断过,`connected` 一直是 true。 */
    private fun onReconnected() {
        if (connected) return
        connected = true
        pushNotice(LiveFeedItem.Notice.Kind.Reconnected)
    }

    private fun pushNotice(kind: LiveFeedItem.Notice.Kind, message: String? = null) {
        // 系统提示没有服务端 id,而同一种提示一场里可能出现多次(警告能连着来好几条),
        // 拿 kind 当 key 会让第二条顶掉第一条。自己编号。
        val seq = noticeSeq++
        append(LiveFeedItem.Notice(id = "notice-$seq", kind = kind, message = message))
    }

    /**
     * 一条醒目留言同时进流和进汇总屏。
     *
     * 汇总屏按**到期时间**排,不按到达顺序:那一屏读的是"还剩多久",排在最前的该是最快消失的
     * 那条。流那边照旧按到达顺序追在末尾。
     */
    private fun onSuperChat(message: LiveMessage.SuperChat) {
        append(LiveFeedItem.SuperChat(message))
        _state.update { it.copy(sessionSuperChats = it.sessionSuperChats.mergeSuperChats(listOf(message))) }
    }

    /**
     * 并进本场那份日志。
     *
     * **排序按发出时间倒序,封顶也按它** —— 这一份是"本场发生过什么"的记录,时间序是它唯一
     * 说得通的顺序;丢也从最旧的丢。生效中那一节要的"按到期时间排"是派生时才做的事,不在这里
     * (见 SuperChatPane)。
     *
     * 去重键是[dedupKey],不是 id:danmakus 那侧根本没有消息 id。同一条两边都有时留带真 id 的
     * 那一份 —— 它才撤得回、有头像。
     */
    private fun List<LiveMessage.SuperChat>.mergeSuperChats(
        incoming: List<LiveMessage.SuperChat>,
    ): List<LiveMessage.SuperChat> = (this + incoming)
        .groupBy { it.dedupKey() }
        .map { (_, same) -> same.firstOrNull { it.id > 0 } ?: same.first() }
        .sortedByDescending { it.startTimeSeconds }
        .take(MAX_SESSION_SUPER_CHATS)

    /**
     * 去重键:谁、哪一秒、说了什么。
     *
     * **两侧的时间不是同一个字段**:B 站给的是 `start_time`(秒),danmakus 记的是自己收到那条的
     * `sendDate`(毫秒)。同一条留言两边可能差个把秒,所以只对到秒仍有漏网的可能 —— 漏了的后果
     * 是"本场早前"里多出一条同样内容的,不是少了什么,可以接受。
     */
    private fun LiveMessage.SuperChat.dedupKey() = Triple(senderMid, startTimeSeconds, message)

    /**
     * danmakus 的一条转成同一个模型,好和 B 站那侧并成一份日志。
     *
     * `endTimeSeconds` 给成发出时刻:那些是本场早前的,按定义早就过期了,派生"生效中"时自然
     * 落在外面。**id 是合成的负数**,只为占住那个字段 —— 真 id 是正数,负号保证撞不上;去重和
     * 列表键都不认它(见 [dedupKey] 与 SuperChatPane)。
     */
    private fun ArchivedSuperChat.toMessage(): LiveMessage.SuperChat {
        val sentAt = sendDateMillis / 1000
        return LiveMessage.SuperChat(
            id = -(senderMid * ARCHIVED_ID_SHIFT + sentAt % ARCHIVED_ID_SHIFT),
            message = message,
            priceYuan = priceYuan,
            senderMid = senderMid,
            senderName = senderName,
            // danmakus 不给头像 URL(notes 的字段表),先留空串,由 [loadArchivedSuperChats]
            // 另查一轮补上;补不到时渲染层判成"这一条不画头像"。
            senderFace = "",
            startTimeSeconds = sentAt,
            endTimeSeconds = sentAt,
        )
    }

    /**
     * 撤回:从本场日志里整条移除,流里那条改成删除线(理由见 [LiveFeedItem.SuperChat.removed])。
     *
     * **移除是整条,不是只从"生效中"那一节挪到"本场早前"**(owner 定):撤回是平台把它拿下来了,
     * 让它在下一节里复活等于绕过这个动作。
     */
    private fun onSuperChatRemoved(ids: List<Long>) {
        val removed = ids.toSet()
        _state.update { state ->
            state.copy(
                sessionSuperChats = state.sessionSuperChats.filterNot { it.id in removed },
                feed = state.feed.map { item ->
                    if (item is LiveFeedItem.SuperChat && item.message.id in removed) {
                        item.copy(removed = true)
                    } else {
                        item
                    }
                },
            )
        }
    }

    /**
     * 上舰。
     *
     * **`GUARD_BUY` 和 `USER_TOAST_MSG` 为同一次上舰同时下发**,两条都会走到这里。哪一条先到
     * 没有保证,而带月数的只有后者,所以做法是:同一个人同一档在窗口内再来一条时,就地把已有那行
     * 补全,不新增一行。在解析层丢掉其中一条不行 —— 丢 toast 会永远拿不到月数,丢 GUARD_BUY 则
     * 在只有前者下发时整条消息消失。
     *
     * 就地更新还有一个作用:行的位置不动。把它提到末尾会让整条列表在合并的那一刻重排一次。
     */
    private fun onGuard(message: LiveMessage.GuardBuy) {
        val now = System.currentTimeMillis()
        _state.update { state ->
            val index = state.feed.indexOfLast {
                it is LiveFeedItem.Guard &&
                    it.mid == message.senderMid &&
                    it.guardLevel == message.guardLevel &&
                    now - it.atMillis <= GUARD_MERGE_WINDOW_MILLIS
            }
            if (index < 0) {
                val item = LiveFeedItem.Guard(
                    mid = message.senderMid,
                    name = message.senderName,
                    guardLevel = message.guardLevel,
                    months = message.months,
                    atMillis = now,
                )
                return@update state.copy(feed = (state.feed + item).takeLast(MAX_FEED_ITEMS))
            }
            val existing = state.feed[index] as LiveFeedItem.Guard
            val merged = existing.copy(
                // 两条各有各缺的那一半,谁先到都要能补上另一半。
                name = existing.name.ifEmpty { message.senderName },
                months = existing.months ?: message.months,
            )
            state.copy(feed = state.feed.toMutableList().also { it[index] = merged })
        }
    }

    /**
     * 中途开播与下播。
     *
     * 下播时把 `isLive` 置回 false,页面随即换成封面那一屏(见 `LiveRoomScreen`)。
     * 开播时要重新取一次流:此刻手里那个地址要么是空的,要么早就过期了。
     *
     * **不走 [load]。** 那条路会再连一次信息流,而这条消息正是从已经连着的那一条上来的,
     * 重连的结果是两个收集器同时往流里塞消息,每条弹幕出现两遍。
     */
    private fun onLiveStateChanged(live: Boolean) {
        pushNotice(
            if (live) LiveFeedItem.Notice.Kind.LiveStarted else LiveFeedItem.Notice.Kind.LiveEnded,
        )
        if (!live) {
            _state.update { it.copy(isLive = false, streamUrl = null) }
            return
        }
        // **开播是新的一场,场次级的状态跟着翻篇。** 醒目留言那份日志按场次算,上一场的留在
        // 屏上会让「本场早前」显示的是别的场次的留言。
        //
        // 不重新拉两份历史:这一场从此刻起,之前没有可补的,而 danmakus 那侧的「本场」此刻
        // 指的还是上一场。大航海不清 —— 那一份挂在主播身上,不随场次变。
        //
        // 流也不清:它是这个页面上发生过什么的记录,两场之间隔着一行下播和一行开播,读得出来。
        _state.update { it.copy(sessionSuperChats = emptyList(), hasArchive = false) }
        viewModelScope.launch {
            val playback = repository.loadPlayback(roomId)
            if (playback is BiliResult.Ok) {
                val value = playback.value
                _state.update {
                    it.copy(
                        isLive = value.isLive,
                        // 重新取流时一并刷新:下播又开播的话,这是新的一场。
                        liveStartedAt = value.liveStartEpochSeconds,
                        streamUrl = value.stream?.url,
                        qualities = value.stream?.acceptQn ?: it.qualities,
                        currentQn = value.stream?.qn ?: it.currentQn,
                    )
                }
            }
        }
    }

    /**
     * 换清晰度。这一趟只为了知道服务端实际给了哪一档(要不到会自己降),**换流由服务做** ——
     * 界面把新档位交给它,那条命令报的是房间号加档位,装载参数变了才重新取一次流。
     *
     * **不动弹幕连接**:信息流跟清晰度没关系,断开重连只会白丢几条。
     */
    fun setQuality(qn: Int) {
        viewModelScope.launch {
            val playback = repository.loadPlayback(roomId, qn)
            if (playback is BiliResult.Ok) {
                _state.update {
                    it.copy(
                        streamUrl = playback.value.stream?.url,
                        currentQn = playback.value.stream?.qn ?: it.currentQn,
                        qualities = playback.value.stream?.acceptQn ?: it.qualities,
                    )
                }
            }
        }
    }

    /**
     * 发一条弹幕。
     *
     * **发出去之后什么都不加**:自己那条会由弹幕长连接原样推回来(`LiveDanmakuClient` 按 mid
     * 标 `isSelf`),同时进聊天栏和画面。本地再补一条就是两遍 —— 这和点播那边正相反,
     * 那边服务端不会把弹幕推给自己,所以必须本地回显。
     *
     * 未开播时不发:房间没开播时服务端会拒,而界面此刻显示的是封面,弹幕发出去也无处可去。
     */
    fun sendDanmaku(text: String) {
        val message = text.trim()
        if (message.isEmpty() || _state.value.sendingDanmaku || !_state.value.isLive) return
        _state.update { it.copy(sendingDanmaku = true, sendError = null) }
        viewModelScope.launch {
            val error = when (val result = repository.sendDanmaku(roomId, message)) {
                is BiliResult.Ok -> null
                is BiliResult.ApiError -> "${result.message}(${result.code})"
                is BiliResult.Failure -> result.cause.message ?: "网络错误"
            }
            _state.update { it.copy(sendingDanmaku = false, sendError = error) }
        }
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

                // 同上面那条:原话与错误码只进 BiliLog。这一处和取流那一处是同一个毛病,
                // 一起改掉,免得同一页两种说法。
                else -> _state.update {
                    it.copy(
                        guards = it.guards.copy(
                            loading = false,
                            error = page.errorTextRes("直播间取大航海"),
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        const val MAX_FEED_ITEMS = 200

        /**
         * 多久看一次连接还在不在,以及多久没消息算断。
         *
         * 超时取两轮心跳多一点:一轮(`LiveDanmakuClient.HEARTBEAT_INTERVAL_MILLIS` = 30s)
         * 会被网络抖动误判,三轮以上又要一分半才说得出"断了",而那时人已经自己看出来了。
         */
        const val CONNECTION_CHECK_INTERVAL_MILLIS = 10_000L
        const val CONNECTION_TIMEOUT_MILLIS = 70_000L
        /**
         * 本场日志封顶,超了从最旧的丢(owner 定)。
         *
         * 500 条按一条几十字节算是几十 KB,而大房间一场几百条 —— 再往上就是让内存跟着场次
         * 长度线性涨,换来的是没人会翻到的那一段。
         */
        const val MAX_SESSION_SUPER_CHATS = 500

        /** 合成 id 时把 mid 抬高的位数,见 [toMessage]。 */
        const val ARCHIVED_ID_SHIFT = 100_000L
        const val DANMAKU_BUFFER = 256

        /**
         * `GUARD_BUY` 与 `USER_TOAST_MSG` 之间的合并窗口。实测两条几乎同时到达,取一分钟是为了
         * 容忍一次网络抖动;同一个人在一分钟内连开两次同档大航海不是会发生的事。
         */
        const val GUARD_MERGE_WINDOW_MILLIS = 60_000L

        /**
         * 屏上表情的高度,以弹幕字号为单位。弹幕轨道高是字号的 1.6 倍,整条大表情取 1.5 刚好填满
         * 一条轨道而不压到邻轨;夹在正文里的小表情跟聊天栏一样略高于字。
         */
        const val STICKER_HEIGHT_EM = 1.5f
        const val INLINE_EMOTE_HEIGHT_EM = 1.2f
    }
}
