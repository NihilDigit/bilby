package dev.bilby.data

import dev.bilby.formatDurationSeconds
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.DmImgParams
import dev.bilby.api.dto.DynamicFeedResponseDto
import dev.bilby.api.dto.DynamicItemDto
import dev.bilby.api.dto.SeasonArchiveDto
import dev.bilby.api.dto.SeasonArchivesResponseDto
import dev.bilby.api.dto.SpaceSeasonSeriesEntryDto
import dev.bilby.api.dto.SpaceSeasonSeriesResponseDto
import dev.bilby.api.dto.SpaceUserInfoDto
import dev.bilby.api.dto.RelationStatDto
import dev.bilby.api.dto.ArchiveSearchResponseDto
import dev.bilby.api.dto.VListItemDto
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.propagateFailure
import dev.bilby.api.toHttpsUrl
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async

/** 空间页一行视频,投稿/动态/合集目录三个 tab 共用同一个展示形状。 */
data class SpaceVideoItem(
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val durationText: String,
    val publishedAtEpochSeconds: Long,
    val playCountText: String,
    val danmakuCountText: String,
)

data class SpaceProfile(
    val mid: Long,
    val name: String,
    val faceUrl: String,
    val sign: String,
    val level: Int,
    val follower: Long,
    val followState: FollowState,
)

enum class SpaceArchiveOrder(val apiValue: String) {
    Pubdate("pubdate"), // 最新
    Click("click"), // 最多播放
}

data class SpaceArchivePage(val total: Int, val items: List<SpaceVideoItem>)

data class SpaceDynamicPage(val items: List<SpaceVideoItem>, val nextOffset: String?, val hasMore: Boolean)

data class SpaceCollectionItem(
    val id: Long,
    val isSeason: Boolean, // true=合集(season) false=系列(series),notes 1.4.1 节靠数组来源区分
    val name: String,
    val coverUrl: String,
    val total: Int,
    val ptimeEpochSeconds: Long,
)

data class SpaceCollectionPage(val total: Int, val items: List<SpaceCollectionItem>)

data class SpaceCollectionDetailPage(val total: Int, val items: List<SpaceVideoItem>)

/**
 * 个人空间(DESIGN 2.4):投稿/动态/合集三个标签 + 用户信息。
 * 接口细节依据 notes/space-and-search.md 第 1 节,空间投稿与空间动态**必须 WBI 签名**,
 * 裸调返回 -400/-403(notes 1.1、1.3、1.5 节)。
 */
class SpaceRepository(private val client: BiliClient) {

    /** 用户信息(acc/info)与关系统计(relation/stat)是两个接口,合并成一个界面用的 profile。 */
    suspend fun loadProfile(mid: Long): BiliResult<SpaceProfile> = coroutineScope {
        val infoDeferred = async { loadUserInfo(mid) }
        val statDeferred = async { loadRelationStat(mid) }
        val info = infoDeferred.await()
        val stat = statDeferred.await()
        when {
            info is BiliResult.Ok && stat is BiliResult.Ok -> BiliResult.Ok(
                SpaceProfile(
                    mid = info.value.mid,
                    name = info.value.name,
                    faceUrl = info.value.face.toHttpsUrl(),
                    sign = info.value.sign,
                    level = info.value.level,
                    follower = stat.value.follower,
                    // 网页端 acc/info **不填** relation,读它只会得到默认的 0(= 未关注),
                    // 一个缺失被读成确定答案。关注态由 SpaceViewModel 用 x/relation 单独查。
                    followState = FollowState.None,
                )
            )
            info !is BiliResult.Ok -> info.propagateFailure()
            else -> stat.propagateFailure()
        }
    }

    /** 参数集与 header 照抄 PiliPlus member.dart:286-312(含空的 token 位)。 */
    private suspend fun loadUserInfo(mid: Long): BiliResult<SpaceUserInfoDto> =
        client.getData(
            "${BiliConstants.WEB_HOST}/x/space/wbi/acc/info",
            mapOf(
                "mid" to mid.toString(),
                "token" to "",
                "platform" to "web",
                // 与投稿列表的 333.1387 不是同一个值,这个接口用 1550101。
                "web_location" to "1550101",
            ) + DmImgParams.next(),
            signed = true,
            referer = spaceReferer(mid, dynamic = true),
        )

    private suspend fun loadRelationStat(mid: Long): BiliResult<RelationStatDto> =
        client.getData(
            "${BiliConstants.WEB_HOST}/x/relation/stat",
            mapOf("vmid" to mid.toString()),
        )

    /**
     * 投稿列表与空间内搜索是同一个接口(notes 1.3 节):`keyword` 非空即是搜索。
     * `order` 只暴露 pubdate/click 两档,UI 上对应"最新/最多播放"两个 FilterChip。
     */
    suspend fun loadArchives(
        mid: Long,
        page: Int,
        order: SpaceArchiveOrder,
        keyword: String? = null,
    ): BiliResult<SpaceArchivePage> {
        val params = buildMap {
            put("mid", mid.toString())
            put("ps", "30")
            // 分区筛选,0 即不限。PiliPlus 恒定带上(member.dart:363),我们没有分区筛选
            // 这个功能,但少一个参数就是少一个字段,签名内容也跟着不同。
            put("tid", "0")
            put("pn", page.toString())
            put("order", order.apiValue)
            put("platform", "web")
            put("web_location", "333.1387")
            put("order_avoided", "true")
            if (!keyword.isNullOrBlank()) put("keyword", keyword)
            putAll(DmImgParams.next())
        }
        val result = client.getData<ArchiveSearchResponseDto>(
            "${BiliConstants.WEB_HOST}/x/space/wbi/arc/search",
            params,
            signed = true,
            referer = spaceReferer(mid),
        )
        return result.map { dto ->
            SpaceArchivePage(dto.page.count, dto.list.vlist.map { it.toVideoItem() })
        }
    }

    /** 合集与系列共用一个列表接口(notes 1.4.1 节),固定每页 10 条(接口本身写死)。 */
    suspend fun loadCollections(mid: Long, page: Int): BiliResult<SpaceCollectionPage> {
        val result = client.getData<SpaceSeasonSeriesResponseDto>(
            "${BiliConstants.WEB_HOST}/x/polymer/web-space/seasons_series_list",
            mapOf("mid" to mid.toString(), "page_num" to page.toString(), "page_size" to "10"),
        )
        return result.map { dto ->
            val seasons = dto.itemsLists.seasonsList.mapNotNull { it.toCollectionItem(isSeason = true) }
            val series = dto.itemsLists.seriesList.mapNotNull { it.toCollectionItem(isSeason = false) }
            SpaceCollectionPage(dto.itemsLists.page.total, seasons + series)
        }
    }

    /** 合集/系列详情(目录),两套接口二选一(notes 1.4.2 节),均不需要 WBI。 */
    suspend fun loadCollectionDetail(
        mid: Long,
        collection: SpaceCollectionItem,
        page: Int,
    ): BiliResult<SpaceCollectionDetailPage> {
        val (url, params) = if (collection.isSeason) {
            "${BiliConstants.WEB_HOST}/x/polymer/web-space/seasons_archives_list" to mapOf(
                "mid" to mid.toString(),
                "season_id" to collection.id.toString(),
                "sort_reverse" to "false",
                "page_size" to "30",
                "page_num" to page.toString(),
                "web_location" to "333.1387",
            )
        } else {
            "${BiliConstants.WEB_HOST}/x/series/archives" to mapOf(
                "mid" to mid.toString(),
                "series_id" to collection.id.toString(),
                "sort" to "desc",
                "ps" to "30",
                "pn" to page.toString(),
                "web_location" to "333.1387",
            )
        }
        val result = client.getData<SeasonArchivesResponseDto>(url, params)
        return result.map { dto -> SpaceCollectionDetailPage(dto.page.total, dto.archives.map { it.toVideoItem() }) }
    }

    /**
     * 空间动态(notes 1.5 节),需要 WBI。分页游标由服务端驱动:`loadNext == true` 时
     * 用返回的新 offset 再拉一页并拼接(notes 1.5 节,与 DynamicRepository 的 feed/all 不同)。
     * 只保留视频类动态,行样式才能跟投稿列表一致——这是产品要求的展示形态,不是接口限制。
     */
    suspend fun loadDynamics(mid: Long, offset: String?): BiliResult<SpaceDynamicPage> {
        val params = buildMap {
            put("host_mid", mid.toString())
            put("offset", offset ?: "")
            put("timezone_offset", "-480")
            // 动态接口都要 features,首页那条(DynamicRepository)一直带着,空间这条漏了。
            put("features", BiliConstants.DYN_FEATURES)
            put("platform", "web")
            put("web_location", "333.1387")
            put("x-bili-device-req-json", """{"platform":"web","device":"pc","spmid":"333.1387"}""")
            putAll(DmImgParams.next())
        }
        val result = client.getData<DynamicFeedResponseDto>(
            "${BiliConstants.WEB_HOST}/x/polymer/web-dynamic/v1/feed/space",
            params,
            signed = true,
            referer = spaceReferer(mid, dynamic = true),
        )
        return result.map { dto ->
            val items = dto.items.mapNotNull { it.toVideoItem() }
            SpaceDynamicPage(items, dto.offset.ifEmpty { null }, dto.hasMore)
        }
    }

    /**
     * 空间接口的 Referer 指向这个人的空间页,而不是站点首页 —— 真实浏览器发这些请求时
     * 用户就停在这一页上。动态 tab 多一层 `/dynamic`,与 PiliPlus 手写的那几组一致。
     *
     * UA 不跟着换:PiliPlus 在这里额外把 UA 覆盖成 BrowserUa.pc,是因为它的全局 UA 是
     * `Dart/3.6 (dart:io)`,那个必须换掉;我们的全局 UA 本来就是桌面 Chrome,已经满足
     * "看起来像浏览器"这个真实目的,再换一个 Safari UA 只是徒增不一致。
     */
    private fun spaceReferer(mid: Long, dynamic: Boolean = false): String =
        "${BiliConstants.SPACE_HOST}/$mid" + if (dynamic) "/dynamic" else ""

    private fun VListItemDto.toVideoItem() = SpaceVideoItem(
        bvid = bvid,
        title = title,
        coverUrl = pic.toHttpsUrl(),
        durationText = length,
        publishedAtEpochSeconds = created,
        playCountText = play.formatCount(),
        danmakuCountText = videoReview.formatCount(),
    )

    private fun SeasonArchiveDto.toVideoItem() = SpaceVideoItem(
        bvid = bvid,
        title = title,
        coverUrl = pic.toHttpsUrl(),
        durationText = formatDurationSeconds(duration),
        publishedAtEpochSeconds = pubdate,
        playCountText = stat.view.formatCount(),
        danmakuCountText = stat.danmaku.formatCount(),
    )

    private fun SpaceSeasonSeriesEntryDto.toCollectionItem(isSeason: Boolean): SpaceCollectionItem? {
        val id = if (isSeason) meta.seasonId else meta.seriesId
        if (id == null) return null
        return SpaceCollectionItem(
            id = id,
            isSeason = isSeason,
            name = meta.name,
            coverUrl = meta.cover.toHttpsUrl(),
            total = meta.total,
            ptimeEpochSeconds = meta.ptime,
        )
    }

    /**
     * 只认视频类动态,复用 DynamicRepository 同一套 major 收敛逻辑(notes 里同一份证据),
     * 图文/转发等类型丢弃——空间动态 tab 的行样式要跟投稿一致(团队分工要求),混入非视频行会破坏这一点。
     */
    private fun DynamicItemDto.toVideoItem(): SpaceVideoItem? {
        val archive = when (type) {
            "DYNAMIC_TYPE_AV" -> modules?.moduleDynamic?.major?.archive
            "DYNAMIC_TYPE_UGC_SEASON" -> modules?.moduleDynamic?.major?.ugcSeason
            "DYNAMIC_TYPE_PGC", "DYNAMIC_TYPE_PGC_UNION" -> modules?.moduleDynamic?.major?.pgc
            "DYNAMIC_TYPE_COURSES_SEASON" -> modules?.moduleDynamic?.major?.courses
            else -> null
        } ?: return null
        val author = modules?.moduleAuthor ?: return null
        val bvid = archive.bvid?.takeIf { it.isNotBlank() } ?: return null
        return SpaceVideoItem(
            bvid = bvid,
            title = archive.title,
            coverUrl = archive.cover.toHttpsUrl(),
            durationText = archive.durationText,
            publishedAtEpochSeconds = author.pubTs,
            playCountText = archive.stat?.play ?: "",
            danmakuCountText = archive.stat?.danmaku ?: "",
        )
    }
}


/**
 * 空间动态接口本身就返回 "1.2万" 这类格式化字符串;arc/search 与合集详情返回的是原始数值,
 * 这里统一格式化,好让三个 tab 的行样式看起来一致(都是"XX万"这种展示形式)。
 */
private fun Long.formatCount(): String = when {
    this >= 100_000_000 -> "%.1f亿".format(this / 100_000_000.0)
    this >= 10_000 -> "%.1f万".format(this / 10_000.0)
    else -> toString()
}
