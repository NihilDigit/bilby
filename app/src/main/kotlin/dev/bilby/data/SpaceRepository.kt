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
    /** 正在直播的房间。**只有真的在播才非空** —— 见 [SpaceLiveRoom]。 */
    val liveRoom: SpaceLiveRoom? = null,
)

/**
 * 这个 UP 正在直播。
 *
 * 空间信息接口本来就带这一段,所以判断"在不在播"不需要额外一次请求。只在
 * `liveStatus == 1` 时产出:0 是没开播,2 是轮播(在放录像),把轮播当直播会让人点进去
 * 看到一段循环播放的旧内容。
 */
data class SpaceLiveRoom(
    val roomId: Long,
    val title: String,
    val coverUrl: String,
    val online: Long,
)

enum class SpaceArchiveOrder(val apiValue: String) {
    Pubdate("pubdate"), // 最新
    Click("click"), // 最多播放
}

data class SpaceArchivePage(val total: Int, val items: List<SpaceVideoItem>)

sealed interface SpaceDynamicItem {
    val key: String

    data class Video(val item: SpaceVideoItem) : SpaceDynamicItem {
        override val key: String get() = item.bvid
    }

    /** 类型名是界面文字,按 [type] 在 UI 层取本地化文案,不在数据层写死。 */
    data class Text(
        override val key: String,
        val type: String,
        val text: String,
        val publishedAtEpochSeconds: Long,
    ) : SpaceDynamicItem

    /** 图文:一段话加若干张图。图可以点开看大图,所以带的是完整的 URL 列表而不是缩略图。 */
    data class Draw(
        override val key: String,
        val text: String,
        val images: List<String>,
        val publishedAtEpochSeconds: Long,
    ) : SpaceDynamicItem

    /**
     * 专栏。**只带卡片要显示的东西**:正文不在动态接口里,要另外一次请求
     * (见 `ui/space/SpaceScreen.kt` 里的说明)。
     */
    data class Article(
        override val key: String,
        val title: String,
        val summary: String,
        val coverUrl: String,
        val url: String,
        val publishedAtEpochSeconds: Long,
    ) : SpaceDynamicItem

    /**
     * 转发。[origin] 为 null 表示源动态已经被删 —— 这时仍然要把这一条画出来,
     * 因为转发者自己说的那段话([text])还在。
     */
    data class Forward(
        override val key: String,
        val text: String,
        val publishedAtEpochSeconds: Long,
        val origin: SpaceDynamicItem?,
        /** 源动态没了时服务端给的说明,如"源动态已被作者删除"。 */
        val originTips: String,
    ) : SpaceDynamicItem
}

data class SpaceDynamicPage(val items: List<SpaceDynamicItem>, val nextOffset: String?, val hasMore: Boolean)

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
                    liveRoom = info.value.liveRoom
                        ?.takeIf { it.liveStatus == LIVE_STATUS_LIVE && it.roomid != 0L }
                        ?.let {
                            SpaceLiveRoom(
                                roomId = it.roomid,
                                title = it.title,
                                coverUrl = it.cover.toHttpsUrl(),
                                online = it.online,
                            )
                        },
                )
            )
            info !is BiliResult.Ok -> info.propagateFailure()
            else -> stat.propagateFailure()
        }
    }

    /**
     * 参数集与 header 照抄 PiliPlus member.dart:286-312(含空的 token 位)。
     *
     * 不再是 private:`AccountRepository` 也要用它取个性签名 —— `x/web-interface/nav`
     * 不带 `sign`(PiliPlus 自己的"我的"页 `pages/mine/controller.dart` 同样只用 nav 取
     * 头像/昵称/等级,`UserInfoData` 模型整个没有 sign 字段,它自己的"我的"页也确实不显示
     * 签名),要拿签名就得走这条接口,和空间页读同一个人信息是同一件事,没必要另起一份请求。
     */
    suspend fun loadUserInfo(mid: Long): BiliResult<SpaceUserInfoDto> =
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
     * 视频和非视频动态都保留；非视频动态使用轻量文字行，不进入播放队列。
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
            val items = dto.items.mapNotNull { it.toDynamicItem() }
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
     * 一条动态映射成界面认得的东西。
     *
     * **番剧、影视、课堂(PGC / PGC_UNION / COURSES_SEASON)一律丢掉**,这不是"还没做":
     * 非 UGC 内容是 Non-Goal(版权),画出来只会给一个点了打不开的入口。
     */
    private fun DynamicItemDto.toDynamicItem(): SpaceDynamicItem? {
        val author = modules?.moduleAuthor ?: return null
        val major = modules.moduleDynamic?.major
        val text = modules.moduleDynamic?.desc?.text.orEmpty().trim()
        val key = idStr.ifBlank { "$type-${author.mid}-${author.pubTs}" }

        val archive = when (type) {
            "DYNAMIC_TYPE_AV" -> major?.archive
            "DYNAMIC_TYPE_UGC_SEASON" -> major?.ugcSeason
            else -> null
        }
        if (archive != null) {
            val bvid = archive.bvid?.takeIf { it.isNotBlank() } ?: return null
            return SpaceDynamicItem.Video(
                SpaceVideoItem(
                    bvid = bvid,
                    title = archive.title,
                    coverUrl = archive.cover.toHttpsUrl(),
                    durationText = archive.durationText,
                    publishedAtEpochSeconds = author.pubTs,
                    playCountText = archive.stat?.play ?: "",
                    danmakuCountText = archive.stat?.danmaku ?: "",
                ),
            )
        }

        // 专栏先认 opus(新接口把长文都归到它上面),没有再退回旧的 article。
        val opus = major?.opus
        val article = major?.article
        if (opus != null || article != null) {
            val title = opus?.title?.takeIf { it.isNotBlank() } ?: article?.title.orEmpty()
            val summary = opus?.summary?.text?.takeIf { it.isNotBlank() } ?: article?.desc.orEmpty()
            if (title.isBlank() && summary.isBlank()) return null
            return SpaceDynamicItem.Article(
                key = key,
                title = title,
                summary = summary.trim(),
                coverUrl = (opus?.pics?.firstOrNull()?.url ?: article?.covers?.firstOrNull()).orEmpty().toHttpsUrl(),
                url = (opus?.jumpUrl ?: article?.jumpUrl).orEmpty().toHttpsUrl(),
                publishedAtEpochSeconds = author.pubTs,
            )
        }

        val images = major?.draw?.items.orEmpty().map { it.src.toHttpsUrl() }.filter { it.isNotEmpty() }
        if (images.isNotEmpty()) {
            return SpaceDynamicItem.Draw(
                key = key,
                text = text,
                images = images,
                publishedAtEpochSeconds = author.pubTs,
            )
        }

        if (type == "DYNAMIC_TYPE_FORWARD") {
            // 源动态自己再走一遍这个映射。它被删时 `orig.type` 是 DYNAMIC_TYPE_NONE,
            // 映射结果为 null,这一条照样要画 —— 转发者说的话还在。
            val origin = orig?.toDynamicItem()
            val tips = orig?.modules?.moduleDynamic?.major?.none?.tips.orEmpty()
            if (text.isEmpty() && origin == null && tips.isEmpty()) return null
            return SpaceDynamicItem.Forward(
                key = key,
                text = text,
                publishedAtEpochSeconds = author.pubTs,
                origin = origin,
                originTips = tips,
            )
        }

        if (text.isEmpty()) return null
        return SpaceDynamicItem.Text(
            key = key,
            type = type,
            text = text,
            publishedAtEpochSeconds = author.pubTs,
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

/** `live_room.liveStatus`:1 才是正在直播,2 是轮播录像。 */
private const val LIVE_STATUS_LIVE = 1
