package dev.bilby.data

import dev.bilby.BiliLog
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
import dev.bilby.api.dto.ArchiveCursorResponseDto
import dev.bilby.api.dto.ArchiveSearchResponseDto
import dev.bilby.api.dto.VListItemDto
import dev.bilby.api.getAppData
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.propagateFailure
import dev.bilby.api.toHttpsUrl
import dev.bilby.data.model.DynamicAdditional
import dev.bilby.data.model.DynamicCard
import dev.bilby.data.model.DynamicContent
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
)

enum class SpaceArchiveOrder(val apiValue: String) {
    Pubdate("pubdate"), // 最新
    Click("click"), // 最多播放
}

data class SpaceArchivePage(val total: Int, val items: List<SpaceVideoItem>)

/** 投稿游标接口的一条。时长是数值秒,UP 名接口给了就带上。 */
data class CursorArchiveItem(
    val aid: Long,
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val durationSeconds: Long,
    val upName: String,
)

/**
 * 以某条投稿为游标取到的一页,按发布时间倒序(新的在前)。
 *
 * @param hasNewer 这一页之前(更新)还有没有。
 * @param hasOlder 这一页之后(更旧)还有没有。
 */
data class CursorArchivePage(
    val items: List<CursorArchiveItem>,
    val hasNewer: Boolean,
    val hasOlder: Boolean,
)

/**
 * 空间动态 tab 的一条。**每条都是一张 [card]**,类型分发在 `DynamicCardMapper` 里做完,这里不再
 * 按形态分一遍 —— 以前分过,于是空间页认得的类型比 PiliPlus 少一大半,直播、音频、番剧更新在
 * 这一页悄悄消失。
 *
 * UP 自己发的视频另外带出 [video],喂给播放队列(`QueueSourceRepository`)。它曾是与卡片二选一的
 * 分支,于是以动态形式发的视频两头落空:当视频就不渲染,当卡片队列又认不出。
 */
data class SpaceDynamicItem(
    val card: DynamicCard,
    val video: SpaceVideoItem? = null,
    /**
     * 这条视频也在「投稿」栏里。动态栏据此去重。以动态形式发的视频不进投稿列表
     * (notes/space-and-search.md 1.5),为 false,只有动态栏看得到它。
     */
    val listedInArchive: Boolean = false,
    /**
     * 取到这一条的那一页用的游标,第一页为 null。从动态栏点开 [video] 时,队列从这一页找起
     * (见 [QueueContext.UpDynamics])。
     */
    val pageOffset: String? = null,
) {
    val key: String get() = card.id
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

    companion object {
        /** 投稿列表一页的条数。列表页据此算出点中的那条落在第几页(见 QueueContext.UpArchive)。 */
        const val ARCHIVE_PAGE_SIZE = 30

        /** 合集/系列目录一页的条数,同上。 */
        const val COLLECTION_PAGE_SIZE = 30
    }

    /**
     * 这个人的直播间号,没开通过直播间返回 null。
     *
     * **不看在不在播**,与 [loadProfile] 里那个 `liveRoom` 正相反:那边是"现在能不能进去看",
     * 这边回答的是"这场预约的直播在哪个房间",而房间号在开播前后是同一个。预约卡片只给
     * up_mid,房间号得这么现查(见 DynamicAdditional.Reserve)。
     */
    suspend fun liveRoomId(mid: Long): Long? = when (val info = loadUserInfo(mid)) {
        is BiliResult.Ok -> info.value.liveRoom?.roomid?.takeIf { it != 0L }
        else -> {
            BiliLog.w("预约:查直播间号失败,mid=$mid")
            null
        }
    }

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
            put("ps", ARCHIVE_PAGE_SIZE.toString())
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

    /**
     * 以 [aid] 为游标取这位 UP 的投稿,**不受页号深度影响**:web 投稿接口的深 `pn` 会被服务端
     * 夹住(见 [loadArchives] 的调用方历史),这个接口按 aid 定位,几十万条的 UP 也一次命中
     * (notes/space-and-search.md 1.4.3)。
     *
     * @param newer true 取游标之前(更新)的一页,false 取之后(更旧)的一页。
     * @param includeCursor 取更旧的那页时把游标自己放在第一条。取更新的那页不认这个参数。
     *
     * 更新的那一页接口仍按倒序给(紧邻游标的在末尾),这里原样返回,调用方直接接在前面。
     * 游标不在这位 UP 的投稿里(动态视频、直播回放)时返回 -1200,不是空页。
     */
    suspend fun loadArchiveCursor(
        mid: Long,
        aid: Long,
        newer: Boolean,
        includeCursor: Boolean = false,
    ): BiliResult<CursorArchivePage> {
        val params = buildMap {
            put("vmid", mid.toString())
            put("aid", aid.toString())
            put("ps", "20")
            put("order", "pubdate")
            put("build", "2001100")
            put("mobi_app", "android_hd")
            put("platform", "android")
            put("qn", "80")
            if (newer) put("sort", "asc") else if (includeCursor) put("include_cursor", "true")
        }
        return client.getAppData<ArchiveCursorResponseDto>(
            "${BiliConstants.APP_HOST}/x/v2/space/archive/cursor",
            params,
        ).map { dto ->
            CursorArchivePage(
                items = dto.item.mapNotNull { item ->
                    val itemAid = item.param.toLongOrNull() ?: return@mapNotNull null
                    CursorArchiveItem(
                        aid = itemAid,
                        bvid = item.bvid,
                        title = item.title,
                        coverUrl = item.cover.toHttpsUrl(),
                        durationSeconds = item.duration,
                        upName = item.author,
                    )
                },
                hasNewer = dto.hasPrev,
                hasOlder = dto.hasNext,
            )
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

    /**
     * 合集/系列详情(目录),两套接口二选一(notes 1.4.2 节),均不需要 WBI。
     *
     * 收 id 与 isSeason 而不是整个 [SpaceCollectionItem]:合集目录现在也从播放页进得去
     * (队列来源就是这个合集),那条路上手里只有 id 和名字,没有列表接口给的封面与条数。
     */
    suspend fun loadCollectionDetail(
        mid: Long,
        id: Long,
        isSeason: Boolean,
        page: Int,
    ): BiliResult<SpaceCollectionDetailPage> {
        val (url, params) = if (isSeason) {
            "${BiliConstants.WEB_HOST}/x/polymer/web-space/seasons_archives_list" to mapOf(
                "mid" to mid.toString(),
                "season_id" to id.toString(),
                "sort_reverse" to "false",
                "page_size" to COLLECTION_PAGE_SIZE.toString(),
                "page_num" to page.toString(),
                "web_location" to "333.1387",
            )
        } else {
            "${BiliConstants.WEB_HOST}/x/series/archives" to mapOf(
                "mid" to mid.toString(),
                "series_id" to id.toString(),
                "sort" to "desc",
                "ps" to COLLECTION_PAGE_SIZE.toString(),
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
     * **所有类型都保留**,分发照 PiliPlus(见 notes/dynamic-cards.md);UP 自己发的视频额外
     * 带出能进播放队列的视频行。
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
            val items = dto.items.mapNotNull { it.toSpaceDynamicItem()?.copy(pageOffset = offset?.ifEmpty { null }) }
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

}

/**
 * 一条动态映射成空间页认得的东西。**类型分发不在这里**,在 `DynamicCardMapper`
 * (对照表见 notes/dynamic-cards.md);这里只做一件事:把 UP 自己发的视频挑出来换成
 * [SpaceVideoItem],好让它能进播放队列。
 *
 * 分成两步而不是让 mapper 直接产出 [SpaceVideoItem]:队列要的那个形状带发布时间,而发布时间
 * 在动态的作者模块上、不在 `major.archive` 里 —— 那是"这条动态"的属性,不是"这个稿件"的,
 * 让 mapper 认识空间页的模型只会把两边焊死。
 */
internal fun DynamicItemDto.toSpaceDynamicItem(): SpaceDynamicItem? {
    val card = toDynamicCard() ?: return null
    val video = card.content as? DynamicContent.Video
    // 转发来的视频不算这位 UP 的投稿。队列装的是他自己发的东西,混进转发的之后
    // 「听这位 UP 的投稿」会放出别人的稿件。
    if (video == null || card.forwarded != null) return SpaceDynamicItem(card)
    return SpaceDynamicItem(
        card = card,
        video = SpaceVideoItem(
            bvid = video.bvid,
            title = video.title,
            coverUrl = video.coverUrl,
            durationText = video.durationText,
            publishedAtEpochSeconds = card.publishedAtEpochSeconds,
            playCountText = video.playCountText,
            danmakuCountText = video.danmakuCountText,
        ),
        listedInArchive = type == "DYNAMIC_TYPE_UGC_SEASON" ||
            modules?.moduleAuthor?.pubAction in ARCHIVE_PUB_ACTIONS,
    )
}

/**
 * 视频动态里"也在投稿列表"的那几种作者动作。**列的是已知在投稿栏里的,不是已知不在的**:
 * 将来冒出一种新文案时,按这个方向判错只是在两栏各出现一次,反过来判错则是动态视频又一次
 * 两栏都看不到。合集更新不看文案,按类型算进来 —— 合集里的稿件一定在投稿列表里。
 */
private val ARCHIVE_PUB_ACTIONS = setOf("投稿了视频", "与他人联合创作")


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
