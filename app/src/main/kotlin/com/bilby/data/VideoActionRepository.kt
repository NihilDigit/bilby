package com.bilby.data

import com.bilby.api.BiliClient
import com.bilby.api.BiliConstants
import com.bilby.api.BiliResult
import com.bilby.api.dto.ActionEnvelopeDto
import com.bilby.api.dto.ArchiveRelationDto
import com.bilby.api.dto.FavFolderDto
import com.bilby.api.dto.FavFolderListDto
import com.bilby.api.getData
import com.bilby.api.map
import io.ktor.client.call.body

/** 视频当前是否已赞/已投币/已收藏,来自 archive/relation。 */
data class VideoRelation(val liked: Boolean, val coined: Int, val favored: Boolean)

data class FavFolder(val id: Long, val title: String, val containsThis: Boolean, val count: Int)

/**
 * 点赞 / 投币 / 收藏这套写操作,以及查询当前互动状态、收藏夹列表。
 *
 * 这几个接口都不在 notes/playurl.md、notes/comment-toview-history.md 的覆盖范围内,
 * 参数依据公开的 bilibili-API-collect 文档(非 PiliPlus 源码读证),逐个方法上标了
 * UNSURE 的地方是文档本身留白、需要真机验证的取值。
 */
class VideoActionRepository(private val client: BiliClient) {

    /** 当前登录账号对这个视频的赞/币/收藏状态。 */
    suspend fun getRelation(bvid: String): BiliResult<VideoRelation> =
        client.getData<ArchiveRelationDto>(RELATION_URL, mapOf("bvid" to bvid))
            .map { VideoRelation(liked = it.like, coined = it.coin, favored = it.favorite) }

    /**
     * like=1 点赞,like=2 取消(跟 archive/relation 里 coin 这种"数量"语义不同,
     * 这个接口的 like 字段本身就是"目标动作"而不是布尔值,UNSURE: 未见到官方对
     * "重复点赞是否报错"的说明,这里不做本地幂等判断,交给调用方按 getRelation 结果决定
     * 要不要调用)。
     */
    suspend fun like(bvid: String, like: Boolean): BiliResult<Unit> = postAction(
        LIKE_URL,
        mapOf("bvid" to bvid, "like" to if (like) "1" else "2"),
    )

    /**
     * multiply 是投币枚举而不是任意数量,官方只接受 1 或 2 枚(一次最多投 2 枚给非自己发布的
     * 视频)。select_like 控制投币的同时是否附带点赞。UNSURE: `cross_domain=true` 是公开文档里
     * 记录的固定参数,含义未知(大概率是老版本网页端的 CORS 标记),原样带上。
     */
    suspend fun coin(bvid: String, count: Int, alsoLike: Boolean): BiliResult<Unit> = postAction(
        COIN_URL,
        mapOf(
            "bvid" to bvid,
            "multiply" to count.toString(),
            "select_like" to if (alsoLike) "1" else "0",
            "cross_domain" to "true",
        ),
    )

    /**
     * rid 传 aid(不是 bvid),type=2 固定表示视频稿件。add/del 两个列表至少要有一个非空,
     * 空的那个不传(而不是传逗号拼出来的空字符串)——公开文档没写空字符串是否会被服务端
     * 当成"清空",不确定的情况下宁可不带这个键。
     */
    suspend fun favorite(aid: Long, addMediaIds: List<Long>, delMediaIds: List<Long>): BiliResult<Unit> {
        val form = buildMap {
            put("rid", aid.toString())
            put("type", "2")
            if (addMediaIds.isNotEmpty()) put("add_media_ids", addMediaIds.joinToString(","))
            if (delMediaIds.isNotEmpty()) put("del_media_ids", delMediaIds.joinToString(","))
        }
        return postAction(FAV_DEAL_URL, form)
    }

    /**
     * 不带 rid 调用同一个 list-all 接口取全部收藏夹,取第一个作为默认收藏夹。
     * UNSURE: 公开文档没有单独的"查默认收藏夹"接口,这里假设服务端按创建顺序返回列表、
     * 且默认收藏夹总是最先创建的那个(即 id 最小/排第一),未经真机验证;如果验证发现顺序
     * 不可靠,需要改成认 attr 位或者别的字段。
     */
    suspend fun getDefaultFavFolderId(mid: Long): BiliResult<Long> =
        client.getData<FavFolderListDto>(FAV_FOLDER_LIST_URL, mapOf("up_mid" to mid.toString(), "type" to "2"))
            .map { it.list.firstOrNull()?.id ?: 0L }

    /** 带 rid=aid 调用,让服务端在每一项里标出这个视频是否已经在该收藏夹里(fav_state)。 */
    suspend fun listFavFolders(mid: Long, aid: Long): BiliResult<List<FavFolder>> =
        client.getData<FavFolderListDto>(
            FAV_FOLDER_LIST_URL,
            mapOf("up_mid" to mid.toString(), "rid" to aid.toString(), "type" to "2"),
        ).map { dto ->
            dto.list.map {
                FavFolder(id = it.id, title = it.title, containsThis = it.favState == 1, count = it.mediaCount)
            }
        }

    /**
     * 点赞/投币/收藏成功时 data 通常是 null 或只有零散字段,不满足 postForm 的判定
     * (参考 ToViewRepository 的做法),所以自己解析只含 code/message 的信封。
     */
    private suspend fun postAction(url: String, form: Map<String, String>): BiliResult<Unit> = runCatching {
        val envelope = client.rawPostForm(url, form).body<ActionEnvelopeDto>()
        if (envelope.code == 0) BiliResult.Ok(Unit) else BiliResult.ApiError(envelope.code, envelope.message)
    }.getOrElse { BiliResult.Failure(it) }

    private companion object {
        const val RELATION_URL = "${BiliConstants.WEB_HOST}/x/web-interface/archive/relation"
        const val LIKE_URL = "${BiliConstants.WEB_HOST}/x/web-interface/archive/like"
        const val COIN_URL = "${BiliConstants.WEB_HOST}/x/web-interface/coin/add"
        const val FAV_DEAL_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/resource/deal"
        const val FAV_FOLDER_LIST_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/folder/created/list-all"
    }
}
