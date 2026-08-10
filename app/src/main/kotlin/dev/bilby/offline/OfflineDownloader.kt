package dev.bilby.offline

import dev.bilby.BiliLog
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliResult
import dev.bilby.danmaku.DanmakuRepository
import dev.bilby.data.PlayInfo
import dev.bilby.data.VideoDetail
import dev.bilby.data.VideoRepository
import dev.bilby.data.VideoStat
import dev.bilby.player.QueueItem
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一条要缓存的东西,由界面递进来。cid 允许为 0 —— 空间投稿来源的队列项本来就不带 cid
 * (见 [QueueItem]),由这里用视频详情补上。
 */
data class OfflineRequest(
    val bvid: String,
    val cid: Long,
    val title: String,
    val upName: String,
    val coverUrl: String,
    val durationSeconds: Long,
    /** 用户选的清晰度档。真正下到哪一档由 [dev.bilby.player.selectStreams] 决定,可能降级。 */
    val qualityId: Int,
)

fun QueueItem.toOfflineRequest(qualityId: Int) = OfflineRequest(
    bvid = bvid,
    cid = cid,
    title = title,
    upName = upName,
    coverUrl = coverUrl,
    durationSeconds = durationSeconds,
    qualityId = qualityId,
)

/**
 * 离线下载器。**全 app 一个**,活在 [dev.bilby.AppContainer] 的生命周期上 —— 下载不该因为
 * 用户离开播放页就停,那正是这个功能的用途。
 *
 * ## 为什么不用 Media3 的 `DownloadManager`
 *
 * 它自带队列、断点、进度、进程重启恢复和前台服务,本来是首选。三条前提在 B 站这条链路上
 * 不成立:
 *
 * 1. **直链会过期,而 `DownloadRequest` 的 URI 换不了。** playurl 给的是带签名、有时效的
 *    CDN 地址,下载被打断几小时后恢复必然 403。`DownloadManager` 没有"重新解析这个请求的
 *    地址"的钩子,只会拿着死链一直重试到放弃。而换地址要重走一次 WBI 签名的 playurl,
 *    那是我们自己的仓库。这里的处理见 [DownloadFailure.Expired]。
 * 2. **一个条目是两条流。** DASH 的视频与音频是两个独立 URL,而一个 `Download` 只认一个
 *    URI —— 一条视频要拆成两个互不相干的 Download,"这条缓存好了没有"还得自己拼回来。
 * 3. **它的字节按 URL 索引、存成不透明分片。** 要让播放端认得,得给每条 Download 设
 *    `customCacheKey`,再让所有播放调用点叠一层 `CacheDataSource` 并遵守同一份 key 约定。
 *    落成普通文件之后,离线播放复用的是现成的 `MergingMediaSource`,播放器一行都不用改。
 *
 * 代价是队列、断点、退避要自己写,换来的是过期能自愈、文件明文可查、播放路径不变。
 *
 * ## 并发度可调,默认 1
 *
 * 这里原本写死 1,理由是"同时下三条只会让三条都慢"。那句话对带宽是瓶颈的情形成立,而 owner
 * 的实测是瓶颈常在单条 CDN 连接上 —— 一条只跑到几百 KB/s,三条就是三倍。所以改成设置项,
 * 上限 3:再往上瓶颈换成风控(每条都要先打一次 playurl),那是这条链路上最贵的东西。
 *
 * 用令牌通道限流,而不是按设置数去起对应条数的消费者协程:后者在用户改设置时只能靠取消协程
 * 生效,而被取消的那条正下到一半。令牌的做法让改动只影响**下一条**开始的下载,已经在飞的那些
 * 走完自己的路。
 */
class OfflineDownloader(
    private val scope: CoroutineScope,
    private val store: OfflineStore,
    private val client: BiliClient,
    private val videoRepository: VideoRepository,
    private val danmakuRepository: DanmakuRepository,
    /** 用户设的并发度,见 [dev.bilby.data.SettingsStore.offlineConcurrency]。 */
    private val concurrency: Flow<Int>,
    /** 有活儿要干/干完了。宿主用它拉起或收掉前台服务,见 [OfflineDownloadService]。 */
    private val onBusyChanged: (Boolean) -> Unit = {},
) {

    private val _items = MutableStateFlow<List<OfflineItem>>(emptyList())

    /** 盘上的全部条目 + 正在下的那条,按创建时间倒序。界面只读这一份。 */
    val items: StateFlow<List<OfflineItem>> = _items.asStateFlow()

    /**
     * 待办。用 `Channel` 而不是自己维护一个 list + 锁:排队本来就是"一个消费者按顺序取",
     * 而无界通道的 `send` 不挂起,界面那一侧勾十条也不会被阻塞。
     */
    private val pending = Channel<OfflineRequest>(Channel.UNLIMITED)

    /**
     * 还没干完的件数。**忙不忙用计数判,不用"消费者协程还活着没有"判** —— 后者在消费者正要
     * 退出、而生产者刚放进一件的那一瞬会读到"还活着",于是新的一件永远没人取。
     */
    private val outstanding = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 开工令牌。**在飞的下载条数 = 已发出去的令牌数**,由 [concurrency] 增减(见 init)。
     *
     * 容量取上限而不是当前设置值:通道容量建了就改不了,而设置随时会变大。
     */
    private val permits = Channel<Unit>(MAX_CONCURRENCY)

    /**
     * 正在下的那些,以及各自的协程。**只为了让 [delete] 能把它掐掉** —— 不掐的话,删除会把
     * 目录删掉,而还在写的那条流立刻把目录重建出来,盘上留下一个列表里查无此人的文件。
     *
     * 键是**请求**的 (bvid, cid),其中 cid 可能还是 0([runOne] 里才补出来),所以 [delete]
     * 不能直接按键查,要按 bvid 匹配并把 cid=0 那条也算上。
     */
    private val running = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * 已经排进 [pending]、但用户又把它删掉了的那些。
     *
     * **删一条排队中的不能只从列表里抹掉**:请求还在通道里,轮到它时会照样下完,然后作为一条
     * 新的已缓存条目冒出来 —— 表现是"删了之后过一会儿自己回来了"。并发度大于 1 之后这事更常
     * 撞上:排队的条数一多,用户本来就更会去取消其中几条。
     */
    private val cancelled = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    init {
        scope.launch {
            // 先扫无主文件再读列表。**顺序不能反** —— 扫的判据正是"列表里认不认得它",
            // 而这一刻还没有任何一条在下,不存在扫到一半正好在写的情形(见 sweepOrphans)。
            store.sweepOrphans()
            _items.value = store.list().map { it.recoveredFromInterruption() }
            // 上一次进程没走完就被杀了的那些要落盘改状态,否则下次启动读回来的还是 Running。
            _items.value.filter { it.error == OFFLINE_INTERRUPTED }.forEach { store.write(it) }
            backfillLegacyMetadata()
        }

        // 令牌的发放与回收。收令牌会挂起到某条下载结束才拿得到,于是"调小"这件事天然只作用于
        // 下一条,不打断在飞的。
        scope.launch {
            var granted = 0
            concurrency.map { it.coerceIn(1, MAX_CONCURRENCY) }.distinctUntilChanged().collect { target ->
                while (granted < target) {
                    permits.send(Unit)
                    granted++
                }
                while (granted > target) {
                    permits.receive()
                    granted--
                }
            }
        }

        // 消费者常驻,条数按上限起。真正的并发度由令牌卡住,多出来的那几条只是空等。
        repeat(MAX_CONCURRENCY) {
            scope.launch {
                for (request in pending) {
                    permits.receive()
                    val key = offlineId(request.bvid, request.cid)
                    try {
                        if (!cancelled.remove(key)) {
                            // **每一条起一个子协程再 join,不直接调 runOne。** 这样 [delete] 能只
                            // 取消这一条,而不会把整个消费者一起带走 —— 子协程被取消不会向上传播
                            // 成失败,循环照常取下一件。
                            val job = scope.launch { runOne(request) }
                            running[key] = job
                            job.join()
                            running.remove(key)
                        }
                    } finally {
                        permits.send(Unit)
                        if (outstanding.decrementAndGet() == 0) onBusyChanged(false)
                    }
                }
            }
        }
    }

    /**
     * 加入队列。**已经下完的直接跳过**,不重下 —— 用户在选择面板里勾中一条已缓存的
     * (面板会把它标成已缓存并禁选,但队列内容可能在面板打开期间变过),重下一遍的代价是
     * 几百 MB 和一次白跑的风控额度。
     *
     * 失败过的那条要能重来,所以只跳过 [OfflineStatus.Completed]。
     */
    fun enqueue(requests: List<OfflineRequest>) {
        if (requests.isEmpty()) return
        scope.launch {
            requests.forEach { request ->
                val existing = _items.value.firstOrNull { it.bvid == request.bvid && it.cid == request.cid }
                if (existing?.status == OfflineStatus.Completed) return@forEach
                // 上一次被取消的那条又被排进来了(重试,或者用户改了主意),撤掉那道墓碑。
                cancelled.remove(offlineId(request.bvid, request.cid))
                upsert(request.toQueuedItem())
                if (outstanding.getAndIncrement() == 0) onBusyChanged(true)
                pending.send(request)
            }
        }
    }

    /**
     * 删一条。**正在下的那条要先把它掐掉再删文件。**
     *
     * 顺序反过来就是个必然出错的组合:目录删掉了,而还在写的那条流下一个缓冲区就把它重建
     * 出来 —— 盘上留下几百 MB,列表里却查无此人,只有去文件管理器才看得见。
     *
     * 用 `cancelAndJoin` 而不是 `cancel`:后者只是发个信号就返回,`RandomAccessFile` 那边
     * 可能还在写。等它真的停下来再删。
     */
    fun delete(item: OfflineItem) {
        scope.launch {
            // 还在通道里排队的那条也要作废,否则轮到它时会照样下完再冒出来(见 [cancelled])。
            cancelled.add(offlineId(item.bvid, item.cid))
            // 正在下的那条,它的键是**请求**的 cid,可能还是 0(真 cid 是 [runOne] 里补的),
            // 所以要连 `<bvid>_0` 一起找。墓碑不这么放:cid=0 的请求补出来是 P1,而用户删的
            // 可能是 P3,把两者混为一谈会误杀一条还没开始的下载。
            running.keys
                .filter { it == offlineId(item.bvid, item.cid) || it == offlineId(item.bvid, 0) }
                .mapNotNull { running.remove(it) }
                .forEach { it.cancelAndJoin() }
            store.delete(item.bvid, item.cid)
            _items.update { list -> list.filterNot { it.bvid == item.bvid && it.cid == item.cid } }
        }
    }

    /** 失败的那条重来。参数从索引里读回来,不用界面再攒一份。 */
    fun retry(item: OfflineItem) {
        enqueue(
            listOf(
                OfflineRequest(
                    bvid = item.bvid,
                    cid = item.cid,
                    title = item.title,
                    upName = item.upName,
                    coverUrl = item.coverUrl,
                    durationSeconds = item.durationSeconds,
                    qualityId = item.qualityId,
                ),
            ),
        )
    }

    private suspend fun runOne(request: OfflineRequest) {
        // **详情无条件拉一次**,不只在 cid 缺失时拉。队列项只带列表要显示的那几样,而离线播放页
        // 要的是一整份(aid 撑评论区、upMid 撑 UP 那一行、发布时间撑简介那行日期,见 OfflineItem)。
        // 拉不到不算失败:cid 已知时照样能下,只是这一条的元信息停在队列项给的那几样。
        val detail = (videoRepository.getVideoDetail(request.bvid) as? BiliResult.Ok)?.value

        // cid 为 0 的队列项要先补:拿着 0 去 playurl 会被服务端当成无效 cid,表现是这一条
        // 静默失败,而它和"网络不好"在界面上完全一样。
        val cid = request.cid.takeIf { it != 0L } ?: detail?.cid?.takeIf { it != 0L } ?: run {
            // 连是哪一 P 都不知道,这条就没有身份,只能在内存里留个失败态 —— 落盘会变成
            // 一个 `<bvid>_0` 幽灵目录(见 OfflineStore.write)。
            fail(request.toQueuedItem(), "取不到分 P 信息")
            return
        }
        val resolved = request.copy(cid = cid)

        // **补出 cid 之后才谈得上"是不是已经缓存过了"。** [enqueue] 那道去重在 cid 还是 0
        // 时判不了:它比的是 (bvid, cid),而 0 跟任何真 cid 都不相等。漏了这一句的后果是
        // 一条已经下好的视频从空间页再点一次缓存会整个重下一遍。
        val alreadyDone = _items.value.any {
            it.bvid == resolved.bvid && it.cid == cid && it.status == OfflineStatus.Completed
        }
        if (alreadyDone) {
            // 占位格要撤掉,否则列表里留着一条永远 Queued 的空行。
            publish(_items.value.first { it.bvid == resolved.bvid && it.cid == cid })
            return
        }

        // 第一次带着真 cid 发布,[mergedWith] 会顺手把 cid=0 那格占位撤掉。
        var item = resolved.toQueuedItem().withMetadataFrom(detail, cid).copy(status = OfflineStatus.Running)
        upsert(item)

        var playInfo = fetchPlayUrl(resolved) ?: run { fail(item, "取流失败"); return }
        item = item.copy(
            qualityId = playInfo.streams.qualityId,
            qualityLabel = playInfo.streams.qualityLabel,
            codec = playInfo.streams.codec,
        )
        upsert(item)

        val videoFile = store.videoFile(resolved.bvid, cid)
        val audioFile = store.audioFile(resolved.bvid, cid)
        videoFile.parentFile?.mkdirs()

        // 音频先下:它只有视频的几十分之一,先落盘意味着"下到一半"的那份也已经能听。
        if (playInfo.streams.audioUrl != null) {
            val audioOk = downloadWithRefresh(
                request = resolved,
                target = audioFile,
                urlOf = { it.streams.audioUrl },
                initial = playInfo,
                onRefreshed = { playInfo = it },
            )
            if (!audioOk) {
                fail(item, "音频流下载失败")
                return
            }
        }

        val videoOk = downloadWithRefresh(
            request = resolved,
            target = videoFile,
            urlOf = { it.streams.videoUrl },
            initial = playInfo,
            onRefreshed = { playInfo = it },
            // 进度只按视频那条算:音频占比小到画在同一根进度条上看不出来,而把两条加起来
            // 就得先知道音频总长,那要多等一次响应头。
            onProgress = { downloaded, total, speed ->
                item = item.copy(downloadedBytes = downloaded, totalBytes = total, speedBytesPerSecond = speed)
                // **进度只进内存,不落盘。** 每 1MB 写一次 meta.json 是纯粹的磨损,而进程被杀
                // 之后这个数字本来也不可信 —— 恢复时以文件的实际长度为准。
                publish(item)
            },
        )
        if (!videoOk) {
            fail(item, "视频流下载失败")
            return
        }

        // 弹幕无条件存(owner 定):一条视频的全部弹幕只有几十 KB,相对视频本身可以忽略,而
        // 离线时没有弹幕的 B 站视频是另一个东西。所以它不再是缓存面板上的一个勾。
        val danmakuSaved = saveDanmaku(cid, item.durationSeconds)

        upsert(
            item.copy(
                status = OfflineStatus.Completed,
                hasDanmaku = danmakuSaved,
                downloadedBytes = videoFile.length() + audioFile.length(),
                totalBytes = videoFile.length() + audioFile.length(),
                speedBytesPerSecond = 0,
                error = null,
            ),
        )
    }

    /**
     * 把详情里那份完整元信息盖到条目上。**详情为 null 时原样返回** —— 队列项给的标题、封面、
     * UP 名虽然少,但都是对的,拿一份空详情去覆盖只会把它们抹成空白。
     *
     * [OfflineItem.partTitle] 只在多 P 时填:单 P 视频的 `pages[0].title` 常常是 "1" 或者
     * 和标题一字不差,写进去只会让列表每行下面多一句废话。
     */
    private fun OfflineItem.withMetadataFrom(detail: VideoDetail?, cid: Long): OfflineItem {
        if (detail == null) return this
        val part = detail.pages.firstOrNull { it.cid == cid }
        return copy(
            title = detail.title.ifEmpty { title },
            partTitle = if (detail.pages.size > 1) part?.title.orEmpty() else "",
            upName = detail.up.name.ifEmpty { upName },
            coverUrl = detail.coverUrl.ifEmpty { coverUrl },
            durationSeconds = part?.durationSeconds?.takeIf { it > 0 }
                ?: detail.durationSeconds.takeIf { it > 0 }
                ?: durationSeconds,
            aid = detail.aid,
            upMid = detail.up.mid,
            upFaceUrl = detail.up.faceUrl,
            description = detail.description,
            publishedAtEpochSeconds = detail.publishedAtEpochSeconds,
            stat = detail.stat.toOfflineStat(),
        )
    }

    /**
     * 取流。**用户选的档在这条视频上不一定存在** —— 批量勾十条时,有的片源最高只有 720,
     * 有的最低就是 1080。就近回退的规则在 [dev.bilby.player.resolveQuality] 里,和播放共用
     * 同一条,这里不做任何特殊处理。
     *
     * 真正下到哪一档以响应为准,写回 [OfflineItem.qualityLabel],列表上如实显示 ——
     * 回退是要让人看得见的,但不值得为它弹一个打断操作的提示。
     */
    private suspend fun fetchPlayUrl(request: OfflineRequest): PlayInfo? =
        when (
            val result = videoRepository.getPlayUrl(
                bvid = request.bvid,
                cid = request.cid,
                preferredQuality = request.qualityId,
            )
        ) {
            is BiliResult.Ok -> result.value
            is BiliResult.ApiError -> {
                BiliLog.w("缓存取流被拒 bvid=${request.bvid} cid=${request.cid} code=${result.code} ${result.message}")
                null
            }
            is BiliResult.Failure -> {
                BiliLog.w("缓存取流异常 bvid=${request.bvid} cid=${request.cid}", result.cause)
                null
            }
        }

    /**
     * 下一条流,**地址过期就换一个接着下**。
     *
     * 这是整个下载器存在的理由(见类注释第 1 条):签名直链的有效期比一部长视频的下载时间短,
     * 而 `Range` 续传让"换地址"几乎零成本 —— 新地址指向的是同一份文件,从已有字节数接着要
     * 就行。[urlOf] 从新的 [PlayInfo] 里挑同一条流(视频或音频)。
     */
    private suspend fun downloadWithRefresh(
        request: OfflineRequest,
        target: File,
        urlOf: (PlayInfo) -> String?,
        initial: PlayInfo,
        onRefreshed: (PlayInfo) -> Unit,
        onProgress: ProgressSink = { _, _, _ -> },
    ): Boolean {
        var info = initial
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            val url = urlOf(info) ?: return false
            when (downloadTo(url, target, onProgress)) {
                DownloadFailure.Expired -> {
                    BiliLog.w("缓存直链过期,重取 playurl bvid=${request.bvid} cid=${request.cid}")
                    info = fetchPlayUrl(request) ?: return false
                    onRefreshed(info)
                }
                DownloadFailure.Transient -> delay(RETRY_BASE_DELAY_MILLIS shl attempt)
                DownloadFailure.Fatal -> return false
                null -> return true
            }
            attempt++
        }
        return false
    }

    /**
     * 把一条流写到 [target],已经有的字节接着写。成功返回 null,失败返回该怎么理解这次失败。
     *
     * **服务端可能不接受 Range**(回 200 而不是 206),那时必须从头重写,不能追加 —— 追加的
     * 结果是一个前面重复了一段的文件,播放器会在那个接缝上解码失败,而失败点离原因很远。
     */
    private suspend fun downloadTo(
        url: String,
        target: File,
        onProgress: ProgressSink,
    ): DownloadFailure? = runCatching {
        val existing = if (target.isFile) target.length() else 0L
        client.streamGet(url, rangeStart = existing) { response ->
            when {
                // 416 = 要的区间超出文件长度,也就是本地这份已经是完整的了。
                response.status == HttpStatusCode.RequestedRangeNotSatisfiable -> null
                !response.status.isSuccess() -> classifyHttpFailure(response.status.value)
                else -> {
                    val append = response.status == HttpStatusCode.PartialContent && existing > 0
                    val startAt = if (append) existing else 0L
                    val total = startAt + (response.contentLength() ?: 0L)
                    writeChannel(response.bodyAsChannel(), target, startAt, total, onProgress)
                    null
                }
            }
        }
    }.getOrElse { cause ->
        BiliLog.w("缓存流写盘失败 file=${target.name}", cause)
        DownloadFailure.Transient
    }

    /**
     * 边收边写。用 [RandomAccessFile] 定位到 [startAt] 而不是 `FileOutputStream(append)`:
     * 服务端不接受 Range 时要从 0 覆盖写,两种情形在这里是同一句 `seek`。
     */
    private suspend fun writeChannel(
        channel: ByteReadChannel,
        target: File,
        startAt: Long,
        total: Long,
        onProgress: ProgressSink,
    ) = withContext(Dispatchers.IO) {
        RandomAccessFile(target, "rw").use { file ->
            file.seek(startAt)
            if (startAt == 0L) file.setLength(0)
            val buffer = ByteArray(BUFFER_SIZE)
            var written = startAt
            var lastReported = startAt
            var lastReportedAt = System.currentTimeMillis()
            var speed = 0L
            while (true) {
                val read = channel.readAvailable(buffer, 0, buffer.size)
                if (read <= 0) break
                file.write(buffer, 0, read)
                written += read
                // 每写够一档才上报一次:每个缓冲区都发一次状态会让界面每秒重组几百次,
                // 而进度条上的差别肉眼看不出来。**再加一条按时间的**:网速只有几百 KB/s 时
                // 攒够 1MB 要好几秒,那期间速度那一栏是不动的,看上去像卡住了。
                val now = System.currentTimeMillis()
                if (written - lastReported >= PROGRESS_STEP_BYTES ||
                    now - lastReportedAt >= PROGRESS_STEP_MILLIS
                ) {
                    // **速度按两次上报之间实测**,不用"总字节 / 总耗时":后者在续传时会把
                    // 上次那几百 MB 也算进这一秒里,显示成几十 MB/s。
                    val elapsed = now - lastReportedAt
                    if (elapsed > 0) speed = (written - lastReported) * 1000 / elapsed
                    lastReported = written
                    lastReportedAt = now
                    onProgress(written, total, speed)
                }
            }
            onProgress(written, total, speed)
        }
    }

    /**
     * 把整条视频的弹幕逐段存盘。**失败不算整条缓存失败** —— 视频已经在盘上了,因为弹幕没拉到
     * 就把它判成失败,用户只会看到一条明明能播的视频被标成红字。拉到几段算几段,
     * [OfflineItem.hasDanmaku] 如实反映有没有拿到东西。
     */
    private suspend fun saveDanmaku(cid: Long, durationSeconds: Long): Boolean {
        var saved = 0
        repeat(danmakuSegmentCount(durationSeconds)) { index ->
            val segment = index + 1
            when (val result = danmakuRepository.getSegmentBytes(cid, segment)) {
                is BiliResult.Ok -> {
                    store.writeDanmaku(cid, segment, result.value)
                    saved++
                }
                // 仓库那侧已经按 path/code/message 记过一行,这里不重复打。
                else -> Unit
            }
        }
        return saved > 0
    }

    private suspend fun fail(item: OfflineItem, reason: String) {
        BiliLog.w("缓存失败 bvid=${item.bvid} cid=${item.cid}: $reason")
        upsert(item.copy(status = OfflineStatus.Failed, speedBytesPerSecond = 0, error = reason))
    }

    /**
     * 给加这套元信息之前存下来的条目补课。
     *
     * 判据是 `aid == 0`:那是"这条索引是旧的"唯一可靠的信号(见 [OfflineItem.aid]),而旧索引
     * 的后果不是显示得少一点,是**显示错的东西** —— 评论区拿 `aid = 0` 去打接口,简介那行日期
     * 是 1970-01-01。按 bvid 重新拉一次详情就能全补上(owner 定的做法)。
     *
     * 顺手把弹幕也补了:补课的条目多半是"默认缓存弹幕"之前存的,离线打开时一条弹幕都没有。
     *
     * 一条一条来,不并发:这是启动时的后台活儿,没人在等它,而它打的是同一个详情接口 ——
     * 开机就并发轰十几次正是风控最容易注意到的形状。
     */
    private suspend fun backfillLegacyMetadata() {
        val legacy = _items.value.filter { it.status == OfflineStatus.Completed && it.aid == 0L && it.hasIdentity }
        if (legacy.isEmpty()) return
        BiliLog.d("缓存索引补课 ${legacy.size} 条")
        legacy.forEach { item ->
            val detail = (videoRepository.getVideoDetail(item.bvid) as? BiliResult.Ok)?.value ?: return@forEach
            val hasDanmaku = item.hasDanmaku || saveDanmaku(item.cid, item.durationSeconds)
            upsert(item.withMetadataFrom(detail, item.cid).copy(hasDanmaku = hasDanmaku))
            delay(BACKFILL_INTERVAL_MILLIS)
        }
    }

    /** 落盘 + 发布。没有 cid 的那份 [OfflineStore.write] 自己会拒,这里不重复判。 */
    private suspend fun upsert(item: OfflineItem) {
        store.write(item)
        publish(item)
    }

    /** 合并规则(含"真 cid 到手就撤掉占位格")在 [mergedWith] 里,那条不变式值得单独测。 */
    private fun publish(item: OfflineItem) {
        _items.update { it.mergedWith(item) }
    }

    private fun OfflineRequest.toQueuedItem() = OfflineItem(
        bvid = bvid,
        cid = cid,
        title = title,
        upName = upName,
        coverUrl = coverUrl,
        durationSeconds = durationSeconds,
        qualityId = qualityId,
        status = OfflineStatus.Queued,
        createdAtMillis = System.currentTimeMillis(),
    )

    private companion object {
        /** 同一条流最多试几次(含换地址那次)。和播放重试同一个数,理由也一样。 */
        const val MAX_ATTEMPTS = 3
        const val RETRY_BASE_DELAY_MILLIS = 1_000L
        const val BUFFER_SIZE = 64 * 1024

        /** 进度上报的粒度。1MB 在几百 MB 的视频上是几百次更新,足够顺滑又不会刷爆重组。 */
        const val PROGRESS_STEP_BYTES = 1024L * 1024

        /** 慢速时按这个间隔兜底上报,不然速度那一栏几秒才动一次。 */
        const val PROGRESS_STEP_MILLIS = 1_000L

        /** 并发度上限,和 [dev.bilby.data.SettingsStore.MAX_OFFLINE_CONCURRENCY] 同值。 */
        const val MAX_CONCURRENCY = 3

        /** 补课两条之间歇一下,别在开机那几秒里连着打同一个接口。 */
        const val BACKFILL_INTERVAL_MILLIS = 500L
    }
}

/** 下载进度上报:已下字节、总字节(未知为 0)、当前速度(字节/秒)。 */
private typealias ProgressSink = (downloaded: Long, total: Long, speedBytesPerSecond: Long) -> Unit

private fun VideoStat.toOfflineStat() = OfflineStat(
    view = view,
    danmaku = danmaku,
    reply = reply,
    favorite = favorite,
    coin = coin,
    share = share,
    like = like,
)
