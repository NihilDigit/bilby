package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.FavFolderDto
import dev.bilby.api.dto.FavFolderPageDto
import dev.bilby.api.dto.FavMediaDto
import dev.bilby.api.dto.FavResourceListDto
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.postAction
import dev.bilby.api.toHttpsUrl
import kotlinx.coroutines.flow.first

/** 收藏夹里的一条。多数是视频稿件,也可能是音频或剧集,见 [isVideo]。 */
data class FavVideo(
    /** 内容 id,含义随 [type] 变(视频是 aid)。取消收藏要连同 [type] 一起带回去。 */
    val aid: Long,
    val type: Int,
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val durationSeconds: Long,
    val upName: String,
    val playCount: Long,
    val danmakuCount: Long,
    val favTimeEpochSeconds: Long,
    /** 稿件已失效(删稿/转私密)。这种条目照常列出来但不可点 —— 悄悄隐藏会让人以为自己记错了。 */
    val invalid: Boolean,
    /** 剧集的类别名(「番剧」「电影」)。只有 type 24 带。 */
    val ogvTypeName: String = "",
) {
    /**
     * 视频稿件。音频与剧集不在本应用的范围里(UGC-only),照常列出、不可打开、不进队列 ——
     * 拿它们的 id 当 aid 去开播放页,打开的是另一个不相干的稿件。
     */
    val isVideo: Boolean get() = type == TYPE_VIDEO

    val playable: Boolean get() = isVideo && !invalid && bvid.isNotEmpty()

    companion object {
        const val TYPE_VIDEO = 2
        const val TYPE_AUDIO = 12
        const val TYPE_OGV = 24
    }
}

/**
 * @param info 这个收藏夹本身。resource/list 每一页都带,内容页的页头与顶栏标题用它 ——
 *   路由带过来的标题可能已经在别处改过。
 */
data class FavPage(val items: List<FavVideo>, val hasMore: Boolean, val info: FavFolderDetail?)

data class FavFolderPage(val items: List<FavFolderDetail>, val hasMore: Boolean)

/** 收藏夹内容的排序。取值照 PiliPlus 的 `FavOrderType`,枚举名就是接口的 `order`。 */
enum class FavOrder(val apiValue: String) {
    Mtime("mtime"), // 最近收藏
    View("view"), // 最多播放
    Pubtime("pubtime"), // 最近投稿
}

/**
 * 一个收藏夹的完整信息。[FavFolder] 只够收藏面板用(标题、条数、这个视频在不在里面),
 * 管理页还要知道能不能删、谁能看到,而这两件事都压在 `attr` 这个位域里。
 */
data class FavFolderDetail(
    val id: Long,
    val title: String,
    val intro: String,
    val count: Int,
    val attr: Int,
    /** 空串表示没有封面。list-all 不给这个字段,所以列表走 created/list,见 notes/fav.md §1。 */
    val coverUrl: String = "",
) {
    /** 默认收藏夹。它删不掉,所以列表里直接不给删除入口,而不是点了报错。 */
    val isDefault: Boolean get() = attr and ATTR_NOT_DEFAULT == 0

    val isPublic: Boolean get() = attr and ATTR_PRIVATE == 0

    private companion object {
        const val ATTR_NOT_DEFAULT = 2
        const val ATTR_PRIVATE = 1
    }
}

/**
 * 收藏夹本身:列表、内容、增删改,以及在某个收藏夹里取消收藏。
 *
 * 播放页那个「收藏到」面板走的是 [VideoActionRepository] —— 那边是以**一个视频**为主语
 * (它在哪些夹子里),这边是以**一个收藏夹**为主语。两处调的都是 batch-deal,参数形状见
 * notes/fav.md。
 *
 * 收藏夹与稍后再看在产品上是同一类东西:用户**自己挑好的有限存货**。DESIGN 1.2 否决点心盒时
 * 给的理由就是这个 —— 降低好内容的启动成本,而不是再造一个供给管道。所以它们并排放在第三屏。
 */
class FavRepository(
    private val client: BiliClient,
    private val settings: SettingsStore,
) {

    /**
     * 用户自建的收藏夹,一页。走 created/list 而不是 list-all:后者不带封面,而列表和「我的」
     * 都要画封面(notes/fav.md §1)。PiliPlus 的收藏夹页与「我的」页走的也是这一个。
     */
    suspend fun folderPage(page: Int): BiliResult<FavFolderPage> {
        val mid = settings.credentials.first().dedeUserId
        return client.getData<FavFolderPageDto>(
            FOLDER_PAGE_URL,
            mapOf(
                "up_mid" to mid,
                "pn" to page.toString(),
                "ps" to Paging.FOLDER_PAGE_SIZE.toString(),
            ),
        ).map { dto -> FavFolderPage(dto.list.orEmpty().map { it.toDetail() }, dto.hasMore) }
    }

    /**
     * 收藏夹内容。
     *
     * @param keyword 夹内搜索词,空串即不筛。
     */
    suspend fun folderContents(
        mediaId: Long,
        page: Int,
        order: FavOrder = FavOrder.Mtime,
        keyword: String = "",
    ): BiliResult<FavPage> =
        client.getData<FavResourceListDto>(
            RESOURCE_LIST_URL,
            mapOf(
                "media_id" to mediaId.toString(),
                "pn" to page.toString(),
                "ps" to Paging.PAGE_SIZE.toString(),
                "keyword" to keyword,
                "order" to order.apiValue,
                "type" to "0",
                "tid" to "0",
                "platform" to "web",
            ),
        ).map { dto ->
            FavPage(
                items = dto.medias.orEmpty().map { it.toFavVideo() },
                hasMore = dto.hasMore,
                info = dto.info?.toDetail(),
            )
        }

    /**
     * 单个收藏夹的信息。**编辑前必须拉这一次**:list-all 不保证带 intro,拿列表里那份去填
     * 编辑框,保存时就会把用户原来的简介抹成空串 —— add 与 edit 是同一个形状,intro 每次必传。
     * PiliPlus 的 `pages/fav_create/view.dart` 进编辑页也是先取一次 folder/info。
     */
    suspend fun folderInfo(mediaId: Long): BiliResult<FavFolderDetail> =
        client.getData<FavFolderDto>(FOLDER_INFO_URL, mapOf("media_id" to mediaId.toString()))
            .map { it.toDetail() }

    suspend fun createFolder(title: String, intro: String, isPublic: Boolean): BiliResult<Unit> =
        saveFolder(mediaId = null, title = title, intro = intro, isPublic = isPublic)

    suspend fun editFolder(
        mediaId: Long,
        title: String,
        intro: String,
        isPublic: Boolean,
    ): BiliResult<Unit> = saveFolder(mediaId, title, intro, isPublic)

    /**
     * 新建与编辑是同一个形状,差别只有 endpoint 和多一个 media_id(PiliPlus 的
     * `FavHttp.addOrEditFolder` 用 isAdd 开关切,这里用 mediaId 是否为空)。
     *
     * cover 必传但本应用没有封面入口,固定传空串。将来加封面时非空的值要先编码,见 notes/fav.md。
     */
    private suspend fun saveFolder(
        mediaId: Long?,
        title: String,
        intro: String,
        isPublic: Boolean,
    ): BiliResult<Unit> = client.postAction(
        if (mediaId == null) FOLDER_ADD_URL else FOLDER_EDIT_URL,
        buildMap {
            put("title", title)
            put("intro", intro)
            put("privacy", if (isPublic) "0" else "1")
            put("cover", "")
            if (mediaId != null) put("media_id", mediaId.toString())
        },
    )

    /** 接口收的是逗号连接的一串 media_id,本应用一次只删一个,批量能力留着不用。 */
    suspend fun deleteFolders(mediaIds: List<Long>): BiliResult<Unit> = client.postAction(
        FOLDER_DEL_URL,
        mapOf("media_ids" to mediaIds.joinToString(","), "platform" to "web"),
    )

    /** 清掉这个收藏夹里的失效内容。服务端判哪些算失效,本地不必先知道,见 notes/fav.md §7。 */
    suspend fun cleanInvalid(mediaId: Long): BiliResult<Unit> = client.postAction(
        RESOURCE_CLEAN_URL,
        mapOf("media_id" to mediaId.toString(), "platform" to "web"),
    )


    private fun FavMediaDto.toFavVideo() = FavVideo(
        aid = id,
        type = type,
        bvid = bvid,
        title = title,
        coverUrl = cover.toHttpsUrl(),
        durationSeconds = duration,
        upName = upper.name,
        playCount = cntInfo.play,
        danmakuCount = cntInfo.danmaku,
        favTimeEpochSeconds = favTime,
        invalid = attr !in VALID_MEDIA_ATTRS,
        ogvTypeName = ogv?.typeName.orEmpty(),
    )

    private fun FavFolderDto.toDetail() = FavFolderDetail(
        id = id,
        title = title,
        intro = intro,
        count = mediaCount,
        attr = attr,
        coverUrl = cover.takeIf { it.isNotBlank() }?.toHttpsUrl().orEmpty(),
    )

    object Paging {
        /** 收藏夹内容一页的条数。列表页据此算出点中的那条落在第几页(见 QueueContext.FavFolder)。 */
        const val PAGE_SIZE = 20

        /** created/list 一页几个收藏夹。照 PiliPlus 的 20。 */
        const val FOLDER_PAGE_SIZE = 20
    }

    private companion object {
        const val FOLDER_PAGE_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/folder/created/list"
        const val FOLDER_INFO_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/folder/info"
        const val FOLDER_ADD_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/folder/add"
        const val FOLDER_EDIT_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/folder/edit"
        const val FOLDER_DEL_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/folder/del"
        const val RESOURCE_LIST_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/resource/list"
        const val RESOURCE_CLEAN_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/resource/clean"

        /** 内容的 attr 取这两个值时条目正常,见 [FavMediaDto.attr]。 */
        val VALID_MEDIA_ATTRS = setOf(0, 16)
    }
}
