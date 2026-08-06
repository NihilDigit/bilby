package dev.bilby.data

import dev.bilby.BiliLog
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.ActionEnvelopeDto
import dev.bilby.api.dto.ArchiveRelationDto
import dev.bilby.api.dto.FavFolderDto
import dev.bilby.api.dto.FavFolderListDto
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.pathOnly
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
    /**
     * 走 app 端。网页端 `x/web-interface/archive/like` 对第三方客户端返回 -403 账号异常,
     * PiliPlus 也是因此把它注释掉换成这个(notes/auth-model.md)。
     * 注意 like 的取值与网页端相反:**0 是点赞,1 是取消**(PiliPlus video.dart:441)。
     */
    suspend fun like(aid: Long, like: Boolean): BiliResult<Unit> = appPostAction(
        LIKE_URL,
        mapOf("aid" to aid.toString(), "like" to if (like) "0" else "1"),
    )

    /**
     * multiply 是投币枚举而不是任意数量,官方只接受 1 或 2 枚(一次最多投 2 枚给非自己发布的
     * 视频)。select_like 控制投币的同时是否附带点赞。UNSURE: `cross_domain=true` 是公开文档里
     * 记录的固定参数,含义未知(大概率是老版本网页端的 CORS 标记),原样带上。
     */
    suspend fun coin(aid: Long, count: Int, alsoLike: Boolean): BiliResult<Unit> = appPostAction(
        COIN_URL,
        mapOf(
            "aid" to aid.toString(),
            "multiply" to count.toString(),
            "select_like" to if (alsoLike) "1" else "0",
        ),
    )

    /**
     * rid 传 aid(不是 bvid),type=2 固定表示视频稿件。add/del 两个列表至少要有一个非空,
     * 空的那个不传(而不是传逗号拼出来的空字符串)——公开文档没写空字符串是否会被服务端
     * 当成"清空",不确定的情况下宁可不带这个键。
     */
    suspend fun favorite(aid: Long, addMediaIds: List<Long>, delMediaIds: List<Long>): BiliResult<Unit> {
        // batch-deal 收的是 resources("aid:type"),不是 rid+type;而且两个 id 列表
        // **都必须传**,没有的那个传空串。少传或改传 rid/type 都会得到 -400 请求错误。
        // 照抄 PiliPlus lib/http/fav.dart:633-646 与 common_intro_controller.dart:274-277。
        val form = mapOf(
            "resources" to "$aid:$VIDEO_RESOURCE_TYPE",
            "add_media_ids" to addMediaIds.joinToString(","),
            "del_media_ids" to delMediaIds.joinToString(","),
        )
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
        if (envelope.code == 0) {
            BiliResult.Ok(Unit)
        } else {
            BiliLog.w("POST ${url.pathOnly()} 失败(${envelope.code}): ${envelope.message}")
            BiliResult.ApiError(envelope.code, envelope.message)
        }
    }.onFailure { BiliLog.w("POST ${url.pathOnly()} 异常", it) }.getOrElse { BiliResult.Failure(it) }

    /** app 路线:access_key + appkey 签名,不带 Cookie。 */
    private suspend fun appPostAction(url: String, form: Map<String, String>): BiliResult<Unit> = runCatching {
        val envelope = client.appPostForm(url, form).body<ActionEnvelopeDto>()
        if (envelope.code == 0) {
            BiliResult.Ok(Unit)
        } else {
            BiliLog.w("POST ${url.pathOnly()} 失败(${envelope.code}): ${envelope.message}")
            BiliResult.ApiError(envelope.code, envelope.message)
        }
    }.onFailure { BiliLog.w("POST ${url.pathOnly()} 异常", it) }.getOrElse { BiliResult.Failure(it) }

    /** 日志里只留路径,query/表单里可能有签名或账号相关信息,不打印。 */
    private fun String.pathOnly(): String = substringBefore('?')

    private companion object {
        /** 视频稿件在收藏体系里的资源类型。 */
        const val VIDEO_RESOURCE_TYPE = 2

        const val RELATION_URL = "${BiliConstants.WEB_HOST}/x/web-interface/archive/relation"
        // 点赞/投币走 app 端(网页端被风控),收藏留在网页端但用 batch-deal ——
        // 三个选择都照抄 PiliPlus lib/http/api.dart:51/80/110。
        const val LIKE_URL = "${BiliConstants.APP_HOST}/x/v2/view/like"
        const val COIN_URL = "${BiliConstants.APP_HOST}/x/v2/view/coin/add"
        const val FAV_DEAL_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/resource/batch-deal"
        const val FAV_FOLDER_LIST_URL = "${BiliConstants.WEB_HOST}/x/v3/fav/folder/created/list-all"
    }
}
