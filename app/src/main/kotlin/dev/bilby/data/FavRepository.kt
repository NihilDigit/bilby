package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.FavFolderListDto
import dev.bilby.api.dto.FavResourceListDto
import dev.bilby.api.getData
import dev.bilby.api.map
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
 * 收藏夹的读取侧(往收藏夹里加/删在 [VideoActionRepository],那是写操作,走的风控路径也不同)。
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

    private companion object {
        const val FOLDER_LIST_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/folder/created/list-all"
        const val RESOURCE_LIST_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/resource/list"
        const val PAGE_SIZE = 20
    }
}
