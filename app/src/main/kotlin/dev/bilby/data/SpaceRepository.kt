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
import dev.bilby.api.dto.ArchiveSearchResponseDto
import dev.bilby.api.dto.VListItemDto
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

/**
 * 空间动态 tab 的一条。
 *
 * **只有两个分支,不是每种动态一个。** 类型分发本身在 `DynamicCardMapper` 里,产出的
 * [dev.bilby.data.model.DynamicCard] 已经把十几种形态收敛好了;这里再按形态分一遍,等于把
 * 同一套判断写两份 —— 以前正是这样,于是空间页认得的类型比 PiliPlus 少一大半,而且直播、
 * 音频、番剧更新在这一页悄悄消失。
 *
 * [Video] 仍然单独留着,因为它要喂给播放队列(`QueueSourceRepository`),而队列装的是
 * [SpaceVideoItem] —— 三个 tab 共用的那个视频行形状。
 */
sealed interface SpaceDynamicItem {
    val key: String

    data class Video(val item: SpaceVideoItem) : SpaceDynamicItem {
        override val key: String get() = item.bvid
    }

    /** 投稿视频之外的全部类型:图文、文字、转发、直播、专栏、番剧更新、音频、收藏夹、活动。 */
    data class Card(val card: DynamicCard) : SpaceDynamicItem {
        override val key: String get() = card.id
    }
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
                "page_size" to "30",
                "page_num" to page.toString(),
                "web_location" to "333.1387",
            )
        } else {
            "${BiliConstants.WEB_HOST}/x/series/archives" to mapOf(
                "mid" to mid.toString(),
                "series_id" to id.toString(),
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
     * **所有类型都保留**,分发照 PiliPlus(见 notes/dynamic-cards.md);只有投稿视频进播放
     * 队列,其余以卡片形式显示。
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
            val items = dto.items.mapNotNull { it.toSpaceDynamicItem() }
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
 * (对照表见 notes/dynamic-cards.md);这里只做一件事:把投稿视频挑出来换成
 * [SpaceVideoItem],好让它能进播放队列。
 *
 * 分成两步而不是让 mapper 直接产出 [SpaceVideoItem]:队列要的那个形状带发布时间,而发布时间
 * 在动态的作者模块上、不在 `major.archive` 里 —— 那是"这条动态"的属性,不是"这个稿件"的,
 * 让 mapper 认识空间页的模型只会把两边焊死。
 */
internal fun DynamicItemDto.toSpaceDynamicItem(): SpaceDynamicItem? {
    val card = toDynamicCard() ?: return null
    val video = card.content as? DynamicContent.Video ?: return SpaceDynamicItem.Card(card)
    // 转发来的视频不算这位 UP 的投稿。队列装的是他自己发的东西,混进转发的之后
    // 「听这位 UP 的投稿」会放出别人的稿件。这一条照样显示,只是以卡片形式。
    if (card.forwarded != null) return SpaceDynamicItem.Card(card)
    return SpaceDynamicItem.Video(
        SpaceVideoItem(
            bvid = video.bvid,
            title = video.title,
            coverUrl = video.coverUrl,
            durationText = video.durationText,
            publishedAtEpochSeconds = card.publishedAtEpochSeconds,
            playCountText = video.playCountText,
            danmakuCountText = video.danmakuCountText,
        ),
    )
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
