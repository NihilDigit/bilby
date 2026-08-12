package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.postAction
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

/**
 * 关注状态。空间页从 `acc/info` 的 `relation` 直接拿,播放页要另查 [RelationRepository.stateOf]。
 *
 * **互关是一个独立状态,不要合并进"已关注"** —— B 站用户在意这个区别,把它显示成"已关注"
 * 等于丢掉信息。
 */
enum class FollowState {
    None,
    Following,
    Mutual,
    Blocked,
    /** 这是自己的空间,没有关注这个动作。 */
    Self,
    ;

    val isFollowing: Boolean get() = this == Following || this == Mutual

    companion object {
        /**
         * `attribute` → 状态。取值照 PiliPlus 的 `member/widget/user_info_card.dart:498-505`,
         * 那是这份表唯一的权威来源。
         *
         * **互关有 4 和 6 两个值,实测下发的是 6**(`x/relation?fid=364259623` 回
         * `attribute: 6`,2026-08-12)。这里原先是一张 `attribute == it.attribute` 的精确匹配表、
         * 且只登记了 4,于是 6 落进兜底的 [None] —— 表现是互关的人在播放页和空间页一律显示成
         * 未关注,点关注还会成功(服务端幂等)。**兜底到"未关注"是这个 bug 能一直不被发现的原因**:
         * 一个没登记的值和"确实没关注"在界面上完全同形。
         *
         * 1 是悄悄关注,这个应用不区分,并进 [Following] —— 从"我关没关他"这个问题上看它就是
         * 关注了。
         */
        fun of(attribute: Int): FollowState = when (attribute) {
            1, 2 -> Following
            4, 6 -> Mutual
            128 -> Blocked
            -1 -> Self
            else -> None
        }
    }
}

/**
 * 关注与取关。
 *
 * **这条是网页端接口,走 cookie + csrf,不是 app 端的 access_key。** 点赞/投币/收藏因为风控
 * 必须走 app 端(见 notes/auth-model.md),很容易顺手把关注也归过去——但 PiliPlus 用的是
 * 网页端这条(`lib/http/video.dart` 的 `relationMod`),照它做。
 *
 * 参数是**分开放**的:`statistics` 与 `x-bili-device-req-json` 在 query,业务字段在 body。
 * Referer/Origin 指到该 UP 的空间页,不是站点首页。这三点照抄 PiliPlus,放错会被拒。
 */
class RelationRepository(private val client: BiliClient) {

    /**
     * 单个用户的关注态。空间页不需要它(`acc/info` 自带 relation),播放页需要:
     * 视频详情里只有 UP 的 mid 和名字,没有关系。PiliPlus 在播放页同样单独查这条。
     */
    suspend fun stateOf(mid: Long): BiliResult<FollowState> =
        client.getData<RelationDto>(STATE, mapOf("fid" to mid.toString()))
            .map { FollowState.of(it.attribute) }

    /**
     * 一次问一批人的关注态,给联合投稿那一排头像用。
     *
     * **服务端只把有关系的那些放进返回的 map 里**,没关系的 mid 根本不出现,所以这里返回的是
     * "已关注的集合"而不是逐个的状态。PiliPlus 也是这么用的(`queryUserStat` 拿 `relations`
     * 之后直接判断 key 在不在)。
     *
     * 这里比它多走一步,按 attribute 过一遍 [FollowState]:拉黑(128)同样会出现在 map 里,
     * 只看 key 存不存在会把拉黑过的人显示成已关注。
     */
    suspend fun followedAmong(mids: List<Long>): BiliResult<Set<Long>> {
        if (mids.isEmpty()) return BiliResult.Ok(emptySet())
        return client.getData<JsonElement>(RELATIONS, mapOf("fids" to mids.joinToString(",")))
            .map { it.followedMids() }
    }

    /**
     * **`data` 有两种形状,不能直接当 Map 收。** 有关系时是 `{"<mid>": {"attribute": n, ...}}`,
     * 一个关系都没有时服务端给的是 `[]` 而不是 `{}` —— 按 `Map<String, _>` 反序列化会在后一种
     * 情况抛异常。而调用方拿不到结果就整排不画加号,于是"一个人都没关注"反倒成了看不见关注
     * 入口的那一种人。
     */
    private fun JsonElement.followedMids(): Set<Long> {
        val entries = (this as? JsonObject)?.entries ?: return emptySet()
        return entries.mapNotNullTo(mutableSetOf()) { (mid, relation) ->
            val attribute = (relation as? JsonObject)
                ?.get("attribute")
                ?.jsonPrimitive
                ?.intOrNull
                ?: return@mapNotNullTo null
            // 按 attribute 过一遍而不是只看 key 在不在:拉黑(128)同样会出现在这份 map 里。
            mid.toLongOrNull()?.takeIf { FollowState.of(attribute).isFollowing }
        }
    }

    suspend fun follow(mid: Long): BiliResult<Unit> = modify(mid, ACT_FOLLOW)

    suspend fun unfollow(mid: Long): BiliResult<Unit> = modify(mid, ACT_UNFOLLOW)

    private suspend fun modify(mid: Long, act: Int): BiliResult<Unit> = client.postAction(
        url = MODIFY,
        params = mapOf(
            "statistics" to STATISTICS,
            "x-bili-device-req-json" to DEVICE_REQ_JSON,
        ),
        form = mapOf(
            "fid" to mid.toString(),
            "act" to act.toString(),
            "re_src" to RE_SRC,
            "gaia_source" to "web_main",
            "spmid" to SPMID,
            "extend_content" to """{"entity":"user","entity_id":$mid,"fp":"${BiliConstants.USER_AGENT}"}""",
        ),
        referer = "${BiliConstants.SPACE_HOST}/$mid/dynamic",
    )

    private companion object {
        const val STATE = "${BiliConstants.WEB_HOST}/x/relation"
        const val RELATIONS = "${BiliConstants.WEB_HOST}/x/relation/relations"
        const val MODIFY = "${BiliConstants.WEB_HOST}/x/relation/modify"

        const val ACT_FOLLOW = 1
        const val ACT_UNFOLLOW = 2

        /** 5 拉黑、6 移除拉黑、7 移除粉丝也走同一个接口,本项目不做。 */
        const val RE_SRC = "11"
        const val SPMID = "333.1387"
        const val STATISTICS = """{"appId":100,"platform":5}"""
        const val DEVICE_REQ_JSON = """{"platform":"web","device":"pc","spmid":"333.1387"}"""
    }
}

/** `x/relation` 只用得上 attribute 一个字段,取值含义见 [FollowState]。 */
@Serializable
internal data class RelationDto(val attribute: Int = 0)
