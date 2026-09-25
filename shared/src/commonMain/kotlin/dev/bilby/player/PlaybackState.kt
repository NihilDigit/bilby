package dev.bilby.player

import dev.bilby.data.PlayInfo
import dev.bilby.data.QueueSource

/** 直播项的 mediaId。带前缀是为了和 bvid 分得开:两种条目住在同一份 playlist 里。 */
fun liveMediaId(roomId: Long): String = "live:$roomId"

/**
 * 通知栏、锁屏与播放页共用的展示信息。**哪种源都有**,所以它挂在状态顶层而不是队列上 ——
 * 队列曾经兼任元数据来源,那正是直播这类"不是队列"的源塞不进来的原因。
 */
data class NowPlaying(
    val title: String,
    /** 视频是 UP 名,直播是主播名。 */
    val subtitle: String,
    val coverUrl: String,
)

/**
 * 队列那一部分的状态。**打开过任何东西之后总是非空** —— 直播也是队列里的一条(设计文档
 * 「决定 5」),只是那份队列只有一条、上下一条都按不动。
 */
data class QueueState(
    /** 队列当前这一条。 */
    val current: QueueItem? = null,
    /** 队列内容,自然顺序(随机只改播放顺序,不改列表怎么摆)。 */
    val items: List<QueueItem> = emptyList(),
    val size: Int = 0,
    val shuffled: Boolean = false,
    /**
     * 上/下一条此刻按不按得动,来自播放器,认随机顺序,也认分 P。按钮可用态用这两个,不要拿
     * [sourcePosition] 推:那是列表位置,随机播放下列表第 1 条照样可以有上一条。
     */
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    /**
     * 这份队列属于哪个视频页(帧,docs/queue-redesign.md 决定 3)。视频页只在它等于自己的帧时
     * 才跟着队列走;直播与没有视频页的时候为 null。
     */
    val frameId: String? = null,
    /** 队列的来源,如"直播回放""UP 主投稿"。见 [dev.bilby.data.OpenedQueue.label]。 */
    val sourceLabel: String = "",
    /** 来源的身份,非空时 [sourceLabel] 那一行可以点进目录。见 [dev.bilby.data.OpenedQueue.source]。 */
    val source: QueueSource? = null,
    /**
     * 队列还在补全,现在这份队列只有正在播的这一条。**播放不等它**,所以这不是"正在加载"
     * ([AudioPlaybackUiState.loading] 说的是取流);它给队列面板用,免得那一格看起来像
     * "这个 UP 只有一条投稿"。
     */
    val enriching: Boolean = false,
    /**
     * 队列补全失败了,现在这份队列只有正在播的这一条。**播放本身是好的**,失败的只是"这条
     * 视频属于哪个集合"。摆出来是因为队列里只剩一条这件事本身看不出是"这个 UP 只有一条投稿"
     * 还是"来源没拉到",而后者重试一下往往就好了。重试点是 [PlaybackCommand.RetryQueue]。
     */
    val incomplete: Boolean = false,
    /** 来源在已读部分之前/之后还有。完整队列面板滚到头时据此续取。 */
    val canExtendBefore: Boolean = false,
    val canExtendAfter: Boolean = false,
    /** 那一头的续取正在飞。 */
    val extendingBefore: Boolean = false,
    val extendingAfter: Boolean = false,
    /**
     * 当前条在整份来源里是第几条(1 起)与来源总条数。不知道时为 null,「N / M」不显示 ——
     * 队列只读了来源的一段,[size] 是已读的条数,拿它当 M 会让三千条的 UP 读成"共 40 条"。
     */
    val sourcePosition: Int? = null,
    val sourceTotal: Int? = null,
)

data class AudioPlaybackUiState(
    /** 正在放什么。没打开过任何东西时为 null。 */
    val nowPlaying: NowPlaying? = null,
    /**
     * **播放器此刻真正装着的东西的标识**,与 [QueueState.current] 不是一回事:队列在收到打开
     * 命令的那一刻就指向新视频了,而播放器要等取流回来才切过去,这中间画面上还是上一条的
     * 最后一帧。
     *
     * 播放页据此决定挂画面还是画占位。用队列那一条来判会把上一条视频的残帧当成本页的画面。
     * 值就是条目的 mediaId:视频是 bvid,直播是 [dev.bilby.player.liveMediaId]。
     */
    val loadKey: String? = null,
    /**
     * 正在播的分 P。**它是播放层的状态,不挂在队列上**:队列项的身份只有 bvid,分 P 是这条
     * 视频内部的结构。上报进度、取弹幕、取字幕认的都是这个值 —— 装载层确认过的那一个。
     *
     * **它属于 [loadKey] 那一条,不属于 [QueueState.current]。** 两者在换条的那段窗口里指的
     * 不是同一条内容:队列在切过去的那一刻就报新的一条了,而这个值要等取流回来才跟上。拿队列
     * 那一条的 bvid 配这个 cid,得到的是一对根本不存在的组合 —— 用它去请求弹幕、字幕、
     * SponsorBlock,拉回来的是上一条视频的内容,还白背一次带着错配参数的请求。
     */
    val currentCid: Long = 0,
    val isPlaying: Boolean = false,
    /** 正在取流或正在重试。这一步要走一次网络,不给反馈的话按下"下一条"后会有一两秒静默。 */
    val loading: Boolean = false,
    /**
     * 最近一次播放失败的原因,给用户看的一句话。**失败摆在界面上,不悄悄跳过下一条** ——
     * 跳过让人只看到"忽然换了一条",而真正的原因(直链过期、网络断了、解码器不可用)
     * 一个字都没留下。重试期间也非空,配合 [loading] 表示"正在重试"。
     */
    val error: String? = null,
    /** 画质菜单要用的清单,以及正在播的那一份流。取流归服务,页面只读。 */
    val playInfo: PlayInfo? = null,
    val currentQuality: Int = 0,
    /** 还没打开过任何东西时为 null。见 [QueueState]。 */
    val queue: QueueState? = null,
    /**
     * 别处看到的位置(毫秒)。两条路径写它:放本地副本时的进度核对(见
     * [dev.bilby.player.mergeCachedProgress]),以及页面重开时的云端核对(见
     * [dev.bilby.player.cloudProgressWrittenElsewhere])。
     *
     * **是一条建议,不是一次跳转。** 播放已经从自己那份进度起播了,这个值只让界面摆一条可点的
     * 提示,用户点了才动。自动跳过去会让播放头在没有任何操作的情况下自己动 —— 那比停在一个稍旧
     * 的位置更难理解,而"稍旧"本身是有下限的:它就是本机上次看到的地方。
     */
    val cloudResumeMillis: Long? = null,
    /**
     * [cloudResumeMillis] 那个位置属于哪一 P。为 0 或等于 [currentCid] 时就是当前这一 P,
     * 点了只 seek;不同则是别处换了 P,点了要切过去(同稿件切 P,不是换一条视频)。
     */
    val cloudResumeCid: Long = 0,
    /**
     * 这一条播到末尾停着,之后没有再放、也没有挪过位置。此时"继续"无处可续,播放键应当从头来。
     *
     * 不能拿 `STATE_ENDED` 代替:被 `pauseAtEndOfMediaItems` 拦在末尾的非末条不进 ENDED
     * (见 AudioPlaybackService.applyStopAtEndOfItem),关掉自动连播或还有下一 P 时都是这样。
     */
    val stoppedAtEnd: Boolean = false,
)
