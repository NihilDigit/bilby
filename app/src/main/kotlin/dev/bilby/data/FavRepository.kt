package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.FavFolderDto
import dev.bilby.api.dto.FavFolderListDto
import dev.bilby.api.dto.FavResourceListDto
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.postAction
import dev.bilby.api.toHttpsUrl
import kotlinx.coroutines.flow.first

/** 收藏夹里的一条视频。 */
data class FavVideo(
    val aid: Long,
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val durationSeconds: Long,
    val upName: String,
    val playCount: Long,
    /** 稿件已失效(删稿/转私密)。这种条目照常列出来但不可点 —— 悄悄隐藏会让人以为自己记错了。 */
    val invalid: Boolean,
)

data class FavPage(val items: List<FavVideo>, val hasMore: Boolean)

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

    /** 用户自建的收藏夹。不带 rid,所以每项的 fav_state 无意义,这里也不读它。 */
    suspend fun folders(): BiliResult<List<FavFolder>> {
        val mid = settings.credentials.first().dedeUserId
        return client.getData<FavFolderListDto>(
            FOLDER_LIST_URL,
            mapOf("up_mid" to mid, "type" to "2"),
        ).map { dto ->
            dto.list.map { FavFolder(id = it.id, title = it.title, containsThis = false, count = it.mediaCount) }
        }
    }

    /** 收藏夹内容。`order=mtime` 是收藏时间倒序,与 B 站默认一致。 */
    suspend fun folderContents(mediaId: Long, page: Int): BiliResult<FavPage> =
        client.getData<FavResourceListDto>(
            RESOURCE_LIST_URL,
            mapOf(
                "media_id" to mediaId.toString(),
                "pn" to page.toString(),
                "ps" to PAGE_SIZE.toString(),
                "order" to "mtime",
                "type" to "0",
                "tid" to "0",
            ),
        ).map { dto ->
            FavPage(
                items = dto.medias.orEmpty().map {
                    FavVideo(
                        aid = it.id,
                        bvid = it.bvid,
                        title = it.title,
                        coverUrl = it.cover.toHttpsUrl(),
                        durationSeconds = it.duration,
                        upName = it.upper.name,
                        playCount = it.cntInfo.play,
                        invalid = it.attr != 0,
                    )
                },
                hasMore = dto.hasMore,
            )
        }

    /** 管理页要的那份收藏夹列表:比 [folders] 多带简介与 attr。接口是同一个,见 notes/fav.md。 */
    suspend fun folderDetails(): BiliResult<List<FavFolderDetail>> {
        val mid = settings.credentials.first().dedeUserId
        return client.getData<FavFolderListDto>(
            FOLDER_LIST_URL,
            mapOf("up_mid" to mid, "type" to "2"),
        ).map { dto -> dto.list.map { it.toDetail() } }
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

    suspend fun removeFromFolder(mediaId: Long, aid: Long): BiliResult<Unit> =
        dealResource(aid, addMediaIds = emptyList(), delMediaIds = listOf(mediaId))

    /** 撤销一次 [removeFromFolder]:同一条 resources 换到 add_media_ids 上,别无差别。 */
    suspend fun restoreToFolder(mediaId: Long, aid: Long): BiliResult<Unit> =
        dealResource(aid, addMediaIds = listOf(mediaId), delMediaIds = emptyList())

    /** 两个列表都必须传,没有的那个传空串;resources 的格式是 `aid:type`。见 notes/fav.md。 */
    private suspend fun dealResource(
        aid: Long,
        addMediaIds: List<Long>,
        delMediaIds: List<Long>,
    ): BiliResult<Unit> = client.postAction(
        RESOURCE_DEAL_URL,
        mapOf(
            "resources" to "$aid:$VIDEO_RESOURCE_TYPE",
            "add_media_ids" to addMediaIds.joinToString(","),
            "del_media_ids" to delMediaIds.joinToString(","),
        ),
    )

    private fun FavFolderDto.toDetail() = FavFolderDetail(
        id = id,
        title = title,
        intro = intro,
        count = mediaCount,
        attr = attr,
    )

    private companion object {
        const val FOLDER_LIST_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/folder/created/list-all"
        const val FOLDER_INFO_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/folder/info"
        const val FOLDER_ADD_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/folder/add"
        const val FOLDER_EDIT_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/folder/edit"
        const val FOLDER_DEL_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/folder/del"
        const val RESOURCE_LIST_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/resource/list"
        const val RESOURCE_DEAL_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/resource/batch-deal"
        const val PAGE_SIZE = 20

        /** 视频稿件在收藏体系里的资源类型。 */
        const val VIDEO_RESOURCE_TYPE = 2
    }
}
