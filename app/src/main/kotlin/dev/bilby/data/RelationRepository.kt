package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.postAction
import kotlinx.serialization.Serializable

/**
 * 关注状态。空间页从 `acc/info` 的 `relation` 直接拿,播放页要另查 [RelationRepository.stateOf]。
 *
 * **互关是一个独立状态,不要合并进"已关注"** —— B 站用户在意这个区别,把它显示成"已关注"
 * 等于丢掉信息。
 */
enum class FollowState(val attribute: Int) {
    None(0),
    Following(2),
    Mutual(4),
    Blocked(128),
    /** 这是自己的空间,没有关注这个动作。 */
    Self(-1),
    ;

    val isFollowing: Boolean get() = this == Following || this == Mutual

    companion object {
        fun of(attribute: Int): FollowState =
            entries.firstOrNull { it.attribute == attribute } ?: None
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
