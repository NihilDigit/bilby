package dev.bilby.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `x/web-interface/history/cursor` 的响应体。字段与分页方式依据
 * notes/comment-toview-history.md §3.1/3.3/3.4,已对着 PiliPlus 的
 * `lib/http/user.dart:85-106`(`historyList`)与 `lib/models_new/history/` 下的几个模型
 * 核对过参数名与响应结构,笔记与代码一致,没有出入。**不带 WBI。**
 *
 * 注:上一行原本写成 `history/星.dart` 那样的通配路径,里面的 `/星` 会开一个**嵌套注释** ——
 * Kotlin 的块注释可以嵌套,不像 C 那样遇到第一个结束符就收尾,于是整个文件从这里到末尾
 * 被吞进一个未闭合的注释里,报的是 `Unclosed comment`。KDoc 里不要写带通配符的路径。
 */
@Serializable
data class HistoryResponseDto(
    val list: List<HistoryItemDto> = emptyList(),
)

@Serializable
data class HistoryItemDto(
    val title: String = "",
    val cover: String = "",
    val history: HistoryRefDto = HistoryRefDto(),
    @SerialName("author_name") val authorName: String = "",
    /** 观看时间戳,也是下一页游标的一半(notes §3.3:游标取上一页最后一条的 oid/view_at)。 */
    @SerialName("view_at") val viewAt: Long = 0L,
    /** 观看进度,秒;-1 表示已看完,与稍后再看同规则(notes §3.4)。 */
    val progress: Long = -1L,
    val duration: Long = 0L,
)

/**
 * `business` 是内容类型的判据:`archive` 是 UGC 稿件,`live`/`article-list`/`pgc`/`cheese`
 * 分别对应直播/专栏/番剧/课程(PiliPlus `pages/history/widgets/item.dart:55-76` 按它分流跳转,
 * 只有 `archive` 落进"当普通视频打开"的分支)。Bilby 目前只做视频稿件(CLAUDE.md),
 * 非 `archive` 的条目在 [dev.bilby.data.HistoryRepository] 里被整体丢弃。
 *
 * `oid` 是下一页游标的另一半。
 */
@Serializable
data class HistoryRefDto(
    val oid: Long = 0L,
    val bvid: String = "",
    val business: String = "",
)
