package dev.bilby.offline

import dev.bilby.BiliLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 缓存文件的目录结构与索引。**只碰 java.io.File,不碰 Android**,所以它是这块唯一值得写
 * 测试的地方(见 OfflineStoreTest)。
 *
 * ```
 * <root>/media/<bvid>_<cid>/meta.json | video.m4s | audio.m4s
 * <root>/danmaku/<cid>/<segmentIndex>.pb
 * ```
 *
 * **索引就是 meta.json,没有数据库。** 现有的 [dev.bilby.data.db.BilbyDatabase] 走
 * `fallbackToDestructiveMigration`,理由是那几张表都是可再生的派生数据;缓存条目不是 ——
 * 索引一旦被版本升级丢掉,盘上剩下的就是一堆认不出来的孤儿文件。写在文件旁边则索引与文件
 * 同生共死,不存在"库里有、盘上没有"这种要对账的状态。
 *
 * **弹幕按 cid 平铺,不放在 `<bvid>_<cid>` 里面。** [dev.bilby.danmaku.DanmakuRepository]
 * 手上只有 cid,压在视频目录下面就得为了读一段弹幕扫整个 media 目录。cid 本身就是全局唯一的
 * 分 P 标识,直接拿它当目录名。
 */
class OfflineStore(private val root: File, private val json: Json) {

    private val mediaRoot: File get() = File(root, "media")
    private val danmakuRoot: File get() = File(root, "danmaku")

    fun dirFor(bvid: String, cid: Long): File = File(mediaRoot, offlineId(bvid, cid))

    fun videoFile(bvid: String, cid: Long): File = File(dirFor(bvid, cid), VIDEO_NAME)

    fun audioFile(bvid: String, cid: Long): File = File(dirFor(bvid, cid), AUDIO_NAME)

    fun danmakuFile(cid: Long, segmentIndex: Int): File =
        File(File(danmakuRoot, cid.toString()), "$segmentIndex.pb")

    /**
     * 盘上所有条目,新的在前。
     *
     * **读不出来的目录直接跳过并记一行日志**,不让整个列表失败:一份写坏的 meta.json 不该
     * 让另外二十条也打不开。它下次被重新缓存时会覆盖掉。
     */
    suspend fun list(): List<OfflineItem> = withContext(Dispatchers.IO) {
        mediaRoot.listFiles().orEmpty()
            .mapNotNull { dir -> readManifest(File(dir, META_NAME)) }
            .sortedByDescending { it.createdAtMillis }
    }

    suspend fun read(bvid: String, cid: Long): OfflineItem? = withContext(Dispatchers.IO) {
        readManifest(File(dirFor(bvid, cid), META_NAME))
    }

    /**
     * 这条视频有没有能直接播的本地副本。**离线播放判的就是它,而且必须在任何一次网络之前判**
     * —— 判在补 cid 之后就已经晚了,补 cid 本身要联网(真机上"缓存了却播不动"就是这么来的,
     * 见 `AudioPlaybackService.playCurrent`)。
     *
     * 所以入口按 **bvid** 查,不要求调用方先有 cid;哪一 P 由 [pickCompletedFor] 定,
     * [preferredCid] 为 0 表示"随便哪一 P 都行"(从缓存列表点进来就是这种)。
     *
     * 只扫 `<bvid>_*` 那几个目录,不读整个 media 目录:这一句在每次起播的最前面,而缓存条目
     * 多起来之后全量读盘是实打实的延迟。目录名前缀之外还要核一遍 manifest 里的 bvid ——
     * 前缀相同但更长的 bvid 会同样命中。
     */
    suspend fun completedFor(bvid: String, preferredCid: Long = 0L): OfflineItem? =
        withContext(Dispatchers.IO) {
            val candidates = mediaRoot.listFiles().orEmpty()
                .filter { it.name.startsWith("${bvid}_") }
                .mapNotNull { readManifest(File(it, META_NAME)) }
                .filter { it.bvid == bvid }
            candidates.pickCompletedFor(bvid, preferredCid)
                // 索引说下完了而文件没了(用户去文件管理器删过、或上次写盘被打断):照索引播的话
                // 播放器会在打开文件时才失败,而那个错误离原因很远。
                ?.takeIf { videoFile(it.bvid, it.cid).isFile }
        }

    /** 已经下完的那一条,按精确的 (bvid, cid) 查。 */
    suspend fun completed(bvid: String, cid: Long): OfflineItem? =
        read(bvid, cid)?.takeIf { it.status == OfflineStatus.Completed && videoFile(bvid, cid).isFile }

    /**
     * 写索引。**先写临时文件再 rename**:直接往 meta.json 上写,进度更新时被杀掉就会留下
     * 一个截断的 JSON,那一条从此在列表里消失,而它的几百 MB 还在盘上。
     */
    suspend fun write(item: OfflineItem): Unit = withContext(Dispatchers.IO) {
        // **没有身份的条目不落盘。** cid 为 0 的那份是"还不知道要缓存哪一 P"的内存占位
        // (见 [hasIdentity]);写下去就会在盘上留一个 `<bvid>_0` 目录,里面只有 meta.json、
        // 没有任何流,而真正的下载随后跑在 `<bvid>_<真 cid>` 下面 —— 一条视频两行,用户点到的
        // 多半是那行 0 字节、永远 Queued、播不动的。真机上出过,这道闸是根治处。
        if (!item.hasIdentity) {
            BiliLog.w("缓存条目还没有 cid,不落盘 bvid=${item.bvid}")
            return@withContext
        }
        val dir = dirFor(item.bvid, item.cid).apply { mkdirs() }
        val target = File(dir, META_NAME)
        val temp = File(dir, "$META_NAME.tmp")
        runCatching {
            temp.writeText(json.encodeToString(item))
            if (!temp.renameTo(target)) {
                // rename 失败(极少见,同目录下不跨卷)时退回直接写,总比丢掉这次更新强。
                target.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { BiliLog.w("写缓存索引失败 id=${item.id}", it) }
    }

    /**
     * 记下本地副本播到哪儿了。
     *
     * **不动 `serverProgressBaseMillis`** —— 那个字段回答的是"服务端那边现在是多少",而这一句
     * 发生时手上没有服务端的值(放本地副本本来就不问网络)。推进它的是心跳上报成功那一刻,见
     * [recordServerBase]。两件事分开写,是因为把它们并成一个函数就得为"这次没有服务端值"传一个
     * 哨兵进去,而那个哨兵和"服务端真的是 0"长得一样。
     */
    suspend fun recordProgress(bvid: String, cid: Long, positionMillis: Long): Unit =
        updateEntry(bvid, cid) { it.copy(watchedPositionMillis = positionMillis) }

    /**
     * 心跳报上去之后,服务端存的就是我们报的这个数。
     *
     * 推进 base 等于宣告"到此为止两边是一致的",于是本地那份不再有话语权,直到下一次断网观看
     * 把它们重新拉开。判据见 [dev.bilby.player.mergeCachedProgress]。
     */
    suspend fun recordServerBase(bvid: String, cid: Long, baseMillis: Long): Unit =
        updateEntry(bvid, cid) { it.copy(serverProgressBaseMillis = baseMillis) }

    /**
     * 读一条、改一条、写回去。
     *
     * **条目不在盘上就什么都不做。** [write] 会 `mkdirs`,照它写下去等于在用户刚删掉的位置重建
     * 一个只有 meta.json、没有任何流的目录 —— 缓存列表里会多出一条播不动的视频,而删除操作看起来
     * 失败了。播放停在删除之后是正常时序(队列里那一条还在放),所以这不是异常路径。
     *
     * 从盘上重读而不是拿调用方手里那份改:这个文件下载器也在写,拿内存里的旧副本整体覆盖回去
     * 会把下载状态、已下字节、失败原因一起退回到读盘那一刻。
     */
    private suspend fun updateEntry(
        bvid: String,
        cid: Long,
        transform: (OfflineItem) -> OfflineItem,
    ): Unit = withContext(Dispatchers.IO) {
        val existing = readManifest(File(dirFor(bvid, cid), META_NAME)) ?: return@withContext
        write(transform(existing))
    }

    suspend fun writeDanmaku(cid: Long, segmentIndex: Int, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            val file = danmakuFile(cid, segmentIndex)
            file.parentFile?.mkdirs()
            runCatching { file.writeBytes(bytes) }
                .onFailure { BiliLog.w("写缓存弹幕失败 cid=$cid segment=$segmentIndex", it) }
        }

    /**
     * 本地这一段弹幕的原始字节,没有就返回 null。
     *
     * 存的是**服务端下发的原始 protobuf**,不是解析结果:播放时复用同一份解析器
     * ([dev.bilby.danmaku.parseDmSegMobileReply]),不会出现"在线一套、离线一套"两条映射。
     */
    suspend fun readDanmaku(cid: Long, segmentIndex: Int): ByteArray? = withContext(Dispatchers.IO) {
        val file = danmakuFile(cid, segmentIndex)
        if (!file.isFile) null else runCatching { file.readBytes() }.getOrNull()
    }

    /**
     * 删一条。**索引先删,文件后删** —— 反过来的话,删文件删到一半被打断会留下一个指向空目录
     * 的条目,列表里点进去是一条播不了的视频。先删索引则最坏情况只是留下一堆没人认领的字节,
     * 下次缓存同一条时整个目录会被重建。
     */
    suspend fun delete(bvid: String, cid: Long): Unit = withContext(Dispatchers.IO) {
        val dir = dirFor(bvid, cid)
        File(dir, META_NAME).delete()
        dir.deleteRecursively()
        File(danmakuRoot, cid.toString()).deleteRecursively()
    }

    /**
     * 清掉列表里认不出来的那些字节,返回回收了多少。
     *
     * ## 三种残留,只有这一种需要清
     *
     * - **删除正在下的那条**:下载器会先把任务掐掉再删文件([OfflineDownloader.delete]),
     *   不会留东西。
     * - **进程被杀时下到一半的分片**:那不是垃圾,是断点 —— 索引还在,条目在列表里显示成
     *   "上次没下完,点一下继续",续传按已有字节数发 Range。用户不想要就在列表里删掉它。
     *   **这种绝对不能扫**,扫掉等于把用户已经下好的几百 MB 白白丢了。
     * - **没有(或读不出)索引的目录**:这一种才是真的没人认领。来源是 [delete] 那个
     *   "先删索引后删文件"的顺序在中间被打断,或者一次写坏的 meta.json。它在列表里查无
     *   此人、点不到也删不掉,却实实在在占着盘,而 [usedBytes] 又会把它算进去 ——
     *   于是"已用 3.2 GB"和列表里那三条对不上,没有任何办法解释。
     *
     * 读不出的索引也按无主处理:JSON 解析失败不是瞬时故障,那份索引不会自己好起来,留着
     * 只是把一块永远回收不了的空间留在盘上。缓存本来就是可以重下的。
     *
     * **只在启动时、下载开始之前调**([OfflineDownloader] 的 init)。这一刻没有任何一条在写,
     * 所以不需要和下载器同步;放在别处就要回答"扫到一半正好在下这条"怎么办。
     */
    suspend fun sweepOrphans(): Long = withContext(Dispatchers.IO) {
        val known = list()
        val knownDirs = known.map { dirFor(it.bvid, it.cid).name }.toSet()
        val knownCids = known.map { it.cid.toString() }.toSet()

        val orphanDirs = mediaRoot.listFiles().orEmpty().filter { it.name !in knownDirs } +
            danmakuRoot.listFiles().orEmpty().filter { it.name !in knownCids }

        var reclaimed = 0L
        orphanDirs.forEach { dir ->
            val size = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            if (dir.deleteRecursively()) reclaimed += size
        }
        if (reclaimed > 0) {
            BiliLog.w("清掉无主缓存文件 dirs=${orphanDirs.size} bytes=$reclaimed")
        }
        reclaimed
    }

    /** 已用空间,给列表页页脚显示一行。 */
    suspend fun usedBytes(): Long = withContext(Dispatchers.IO) {
        mediaRoot.walkBottomUp().filter { it.isFile }.sumOf { it.length() } +
            danmakuRoot.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 读一份索引。**cid 为 0 的当作读不出来**:那是旧版本留在盘上的幽灵条目(见 [write]),
     * 它没有身份、没有流、点开播不动。判成无效之后它就不出现在 [list] 里,于是
     * [sweepOrphans] 会把它当无主目录清掉 —— 不用单写一段一次性的清理代码。
     */
    private fun readManifest(file: File): OfflineItem? {
        if (!file.isFile) return null
        val item = runCatching { json.decodeFromString<OfflineItem>(file.readText()) }
            .onFailure { BiliLog.w("缓存索引读不出来,跳过 path=${file.parentFile?.name}", it) }
            .getOrNull()
            ?: return null
        if (!item.hasIdentity) {
            BiliLog.w("盘上有没有 cid 的幽灵条目,按无主处理 bvid=${item.bvid}")
            return null
        }
        return item
    }

    private companion object {
        const val META_NAME = "meta.json"

        /** 扩展名照 DASH 分片的惯例。播放器认的是内容不是后缀,这两个名字只为人眼服务。 */
        const val VIDEO_NAME = "video.m4s"
        const val AUDIO_NAME = "audio.m4s"
    }
}
