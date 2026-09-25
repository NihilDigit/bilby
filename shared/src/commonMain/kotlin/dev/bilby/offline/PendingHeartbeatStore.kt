package dev.bilby.offline

import dev.bilby.BiliLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一条没发出去的心跳,等联网后补发(见 [dev.bilby.data.HeartbeatReporter.flushPending])。
 *
 * 字段就是心跳请求体要的那几样(notes/playurl.md §8.1.1),外加 [bvid]:补发成功之后要按
 * (bvid, cid) 找到那条缓存推进它的进度,而缓存的身份是 bvid 不是 aid。
 */
@Serializable
data class PendingHeartbeat(
    val aid: Long,
    val cid: Long,
    val bvid: String,
    val playedTimeSeconds: Long,
    val finished: Boolean,
    /** 记下它的时刻。补发按它排序,同一稿件多个分 P 时最后写的那条落在服务端。 */
    val queuedAtMillis: Long,
)

/**
 * 待补发的心跳表,按 (aid, cid) 只留最后一条。
 *
 * **不放在缓存目录里。** 进度是看的时候产生的,和那份缓存文件是两件东西:断网看完一条、随手把
 * 缓存删掉,这次观看照样该报上去。放进 `<bvid>_<cid>` 目录的话,删缓存就把它一起删了。
 *
 * 也不进 Room:那个库走 `fallbackToDestructiveMigration`(见 [OfflineStore] 的说明),而这张表
 * 正是不可再生的东西。条目只有几条,一个 JSON 文件整读整写足够。
 *
 * 读写都过同一把锁:上报失败的写入和补发的删除分别跑在心跳 scope 与 WorkManager 的线程上,
 * 两边各自读改写,不锁就会互相覆盖。
 */
class PendingHeartbeatStore(private val file: File, private val json: Json) {

    private val lock = Mutex()

    suspend fun put(entry: PendingHeartbeat): Unit = mutate { entries ->
        entries.filterNot { it.aid == entry.aid && it.cid == entry.cid } + entry
    }

    suspend fun snapshot(): List<PendingHeartbeat> = lock.withLock { read() }

    /**
     * 补发成功或被服务端拒绝之后撤掉这一条。**只撤一模一样的那条**:补发在飞的时候同一
     * (aid, cid) 可能又记下一条更新的,按键删会把它一起删掉。
     */
    suspend fun remove(entry: PendingHeartbeat): Unit = mutate { entries -> entries - entry }

    /**
     * 同一稿件的一次实时心跳报成功了,撤掉在它之前记下的那些。
     *
     * 按 aid 而不是 (aid, cid):服务端整个稿件只存一对 (cid, 秒数)(notes/playurl.md §8.2.1),
     * 留着另一 P 的旧记录,补发时会把服务端从刚报的这一 P 拽回那一 P。[sentAtMillis] 之后记下的
     * 不撤:那是比这次成功更新的失败,补发它才对。
     */
    suspend fun removeOlder(aid: Long, sentAtMillis: Long): Unit = mutate { entries ->
        entries.filterNot { it.aid == aid && it.queuedAtMillis <= sentAtMillis }
    }

    private suspend fun mutate(transform: (List<PendingHeartbeat>) -> List<PendingHeartbeat>) {
        lock.withLock {
            val before = read()
            val after = transform(before)
            if (after != before) write(after)
        }
    }

    /** 读不出来按空表处理:一份写坏的文件不会自己好起来,留着它只会让每次补发都失败。 */
    private suspend fun read(): List<PendingHeartbeat> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        runCatching { json.decodeFromString<List<PendingHeartbeat>>(file.readText()) }
            .onFailure { BiliLog.w("待补发心跳表读不出来,按空表处理 path=${file.name}", it) }
            .getOrDefault(emptyList())
    }

    /** 先写临时文件再 rename,理由同 [OfflineStore.write]。 */
    private suspend fun write(entries: List<PendingHeartbeat>): Unit = withContext(Dispatchers.IO) {
        runCatching {
            file.parentFile?.mkdirs()
            if (entries.isEmpty()) {
                file.delete()
                return@runCatching
            }
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(entries))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { BiliLog.w("写待补发心跳表失败 path=${file.name}", it) }
    }
}
