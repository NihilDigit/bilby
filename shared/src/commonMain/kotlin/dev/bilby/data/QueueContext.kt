package dev.bilby.data

import dev.bilby.BiliLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 点开一条视频时用户眼前的那份列表。**队列就是这份列表**,含排序与筛选(docs/queue-redesign.md
 * 决定 1)。它随视频页的 NavKey 一起保存,进程被杀之后按它重建队列。
 *
 * 分页来源带着 [page]:那条视频在点开时落在第几页。服务按它取那一页再找这条视频,不让列表页
 * 把条目递过来 —— 列表页那份可能已经过时,重取一页只是一次请求。页号只是起点,列表在这期间
 * 挪过(新投稿把旧的往后推)时服务会看相邻两页。
 */
@Serializable
sealed interface QueueContext {

    /**
     * 列表本身是异质的:动态流、搜索、历史、站内跳转、分享链接。这些列表拿来当队列等于把无关的
     * 内容灌进来,所以退到这条视频自己的归属:合集 → 系列 → UP 投稿里它前后的邻居。
     */
    @Serializable
    data object Affiliation : QueueContext

    /** 合集/系列的目录页。 */
    @Serializable
    data class Collection(
        val mid: Long,
        val id: Long,
        val isSeason: Boolean,
        val name: String,
        val page: Int = 1,
    ) : QueueContext

    /** 空间页的投稿栏,带着当时的排序与空间内搜索词。 */
    @Serializable
    data class UpArchive(
        val mid: Long,
        val order: SpaceArchiveOrder,
        val keyword: String,
        val page: Int,
    ) : QueueContext

    /**
     * 空间页的动态栏。**只有从那里点开 UP 自己发的视频才用它**;动态视频不在投稿列表里
     * (notes/space-and-search.md 1.4.3),按投稿建队列找不到它。
     *
     * @param pageOffset 这条动态所在那一页的游标,第一页为 null。动态的游标是服务端给的不透明串,
     *   没有页号可记,记下取那一页时用的游标,服务从这一页找起。
     */
    @Serializable
    data class UpDynamics(val mid: Long, val pageOffset: String?) : QueueContext

    /** 收藏夹内容,带着当时的排序与夹内搜索词,理由同 [UpArchive]。 */
    @Serializable
    data class FavFolder(
        val mediaId: Long,
        val title: String,
        val page: Int,
        val order: FavOrder = FavOrder.Mtime,
        val keyword: String = "",
    ) : QueueContext

    /** @param asc 列表页当时的排序方向。队列按同一个方向排,下一条才是列表里的下一行。 */
    @Serializable
    data class ToView(val asc: Boolean = false) : QueueContext

    /** 缓存列表。本地有完整副本的视频在离线时也退到这里,见 `AudioPlaybackService.openQueue`。 */
    @Serializable
    data object Offline : QueueContext
}

/**
 * 过 session 命令的 Bundle 时用 JSON 串:Bundle 装不下密封类,而 NavKey 本来就按 kotlinx
 * serialization 存,同一份描述不必再写一套 Bundle 映射。
 */
private val contextJson = Json { ignoreUnknownKeys = true }

fun encodeQueueContext(context: QueueContext): String = contextJson.encodeToString(QueueContext.serializer(), context)

/** 认不出时给 null,调用方退到 [QueueContext.Affiliation]。 */
fun decodeQueueContext(text: String): QueueContext? =
    runCatching { contextJson.decodeFromString(QueueContext.serializer(), text) }
        .onFailure { BiliLog.w("队列上下文解析失败: ${it.message}") }
        .getOrNull()
