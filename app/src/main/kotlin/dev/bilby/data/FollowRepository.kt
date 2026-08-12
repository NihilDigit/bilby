package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.FollowTagDto
import dev.bilby.api.dto.FollowingDto
import dev.bilby.api.dto.FollowingsDto
import dev.bilby.api.dto.PortalDto
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.postAction
import dev.bilby.api.toHttpsUrl
import kotlinx.coroutines.flow.first

/** 列表里的一个 UP。头像地址已改写成 https,直接交给 Coil。 */
data class UpBrief(
    val mid: Long,
    val name: String,
    val faceUrl: String,
    val sign: String = "",
    /** 在「特别关注」分组里。三条关注列表接口都带这个字段。 */
    val special: Boolean = false,
)

/** 关注列表的两种排法,都由服务端算,本地不重排。 */
enum class FollowOrder(val param: String) {
    /** 按关注时间倒序。 */
    Recent(""),

    /** B 站按访问频次算好的顺序。 */
    Frequent("attention"),
}

/**
 * 一个关注分组。**这是用户自己划的**,不是谁算出来的排序,所以它比「最常访问」更适合当
 * 这一页的主要入口。
 *
 * @param id `tagid`。-10 是「特别关注」,0 是「默认分组」(没被分进任何自建分组的人)。
 */
data class FollowGroup(val id: Long, val name: String, val count: Int) {
    /** 这两个分组是接口固定给的,改不了名也删不掉。 */
    val isBuiltIn: Boolean get() = id == SPECIAL_GROUP_ID || id == DEFAULT_GROUP_ID
}

/**
 * 「特别关注」。它另有 `tag/special/add`、`/del` 两条独立写接口,但**本项目一条都不走**:
 * 它同时也是一个普通 tagid,跟着 [FollowRepository.replaceGroupsOf] 的覆盖提交一起进出即可。
 * 两条都走会让第二次写撞上 `22004 已经设置该属性了`,见 notes/relation-groups.md 1.7。
 */
const val SPECIAL_GROUP_ID = -10L

/** 「默认分组」:没被分进任何自建分组的人。清空某人的分组要往接口传的正是这个 id。 */
const val DEFAULT_GROUP_ID = 0L

/**
 * 正在直播的一位关注对象。**和 [UpBrief] 分开**:直播是"此刻正在发生、错过就没有"的东西,
 * 而 UpBrief 描述的是一个人。合成一个类之后,那个可空的房间号会出现在每一处用到关注对象的
 * 地方,而其中绝大多数根本填不出它。
 */
data class LiveUpBrief(
    val mid: Long,
    val name: String,
    val faceUrl: String,
    val roomId: Long,
    /** 直播间标题。列表里靠它才看得出"现在在播什么",而不只是一串人名。 */
    val roomTitle: String,
)

/**
 * portal 那一次请求的全部产出。[liveCount] 可能大于 `liveUsers.size` —— 服务端只给这一屏的
 * 那几个人,但会如实告诉你一共有几个在播。首页那一格的数字用它。
 */
data class PortalSnapshot(
    val ups: List<UpBrief> = emptyList(),
    val liveUsers: List<LiveUpBrief> = emptyList(),
    val liveCount: Int = 0,
)

/**
 * 关注列表的读取,以及 `x/relation/tag*` 那一族分组接口的读写。
 *
 * **和 [RelationRepository] 的分工按接口族划,不按读写划**:那边是 `x/relation/modify`
 * (关注、取关、拉黑)与 `x/relation`(单个人的关系),这边是分组。分组的读和写共用同一组
 * 参数约定和同一份 tagid 语义,拆到两个类里只会让下一个人两边都要看。
 *
 * **顺序一律用服务端给的,本地不排、不加权、不记次数。** "最常访问"是 B 站按账号维度算好
 * 的结果,我们只是把它显示出来;在本地再算一遍就成了 DESIGN 1.3 禁止的那种个性化。
 */
class FollowRepository(
    private val client: BiliClient,
    private val settings: SettingsStore,
) {

    /**
     * 动态页顶部的"最常访问"。
     *
     * 这是导航,不是推荐:里面每一个人都是用户自己关注的,点进去是空间页(DESIGN 1.1 把
     * "进空间"列为带意图的入口)。它不产生任何内容条目,也不参与动态流的排序。
     */
    suspend fun frequentUps(): BiliResult<PortalSnapshot> =
        client.getData<PortalDto>(
            PORTAL_URL,
            // up_list_more=1 是必需的:不带它服务端照样返回 code 0,但 up_list 整个缺席,
            // 表现是这一排静默消失而没有任何错误可查。web_location 照 PiliPlus 传。
            mapOf("up_list_more" to "1", "web_location" to "333.1365"),
        ).map { dto ->
            // 正在直播的人**单独成一份**,不再往那排人身上贴角标。两者的成员并不重合:
            // live_users 里的人不一定在"最常访问"那一排,而贴角标的做法只标得出重合的那部分,
            // 剩下的正在直播的人一个都露不出来。
            val live = dto.live_users
            PortalSnapshot(
                ups = dto.up_list?.items.orEmpty().map { it.toBrief() },
                liveUsers = live?.items.orEmpty()
                    .filter { it.room_id > 0 }
                    .map {
                        LiveUpBrief(
                            mid = it.mid,
                            name = it.uname,
                            faceUrl = it.face.toHttpsUrl(),
                            roomId = it.room_id,
                            roomTitle = it.title,
                        )
                    },
                // count 用服务端那个数,不用 items.size —— items 只是这一屏给的那几个。
                liveCount = live?.count ?: 0,
            )
        }

    /**
     * 关注列表,按 [order] 排。
     *
     * 两种排法都是服务端给的:`attention` 是 B 站按访问频次算好的"最常访问",空串是按关注
     * 时间倒序的"最近关注"。本地不重排也不加权(见类注释)。
     */
    suspend fun followings(page: Int, order: FollowOrder): BiliResult<List<UpBrief>> {
        val mid = settings.credentials.first().dedeUserId
        return client.getData<FollowingsDto>(
            FOLLOWINGS_URL,
            mapOf(
                "vmid" to mid,
                "pn" to page.toString(),
                "ps" to PAGE_SIZE.toString(),
                "order" to "desc",
                "order_type" to order.param,
            ),
        ).map { dto -> dto.list.map { it.toBrief() } }
    }

    /**
     * 用户自己建的关注分组。「特别关注」也在这份名单里(tagid = -10),不单独一条路 ——
     * 它在接口眼里就是一个分组,分出来只会让取成员时多一份分支。
     */
    suspend fun groups(): BiliResult<List<FollowGroup>> =
        client.getData<List<FollowTagDto>>(TAGS_URL).map { tags ->
            tags.map { FollowGroup(id = it.tagid, name = it.name, count = it.count) }
        }

    /**
     * 某个分组里的人。**这条接口的 data 是一个裸数组**,没有 total,所以翻页只能按
     * "这一页没满就是最后一页"判。
     */
    suspend fun groupMembers(groupId: Long, page: Int): BiliResult<List<UpBrief>> {
        val mid = settings.credentials.first().dedeUserId
        return client.getData<List<FollowingDto>>(
            GROUP_URL,
            mapOf(
                "mid" to mid,
                "tagid" to groupId.toString(),
                "pn" to page.toString(),
                "ps" to PAGE_SIZE.toString(),
            ),
        ).map { list -> list.map { it.toBrief() } }
    }

    /**
     * 新建一个分组。**不读返回里的 tagid**,建完由调用方重拉 [groups] ——
     * 理由见 notes/relation-groups.md 1.2。
     */
    suspend fun createGroup(name: String): BiliResult<Unit> =
        client.postAction(TAG_CREATE_URL, form = mapOf("tag" to name), params = TAG_PARAMS)

    /** 改名。字段叫 `name`,建的时候那个叫 `tag`,接口本身不对称。 */
    suspend fun renameGroup(id: Long, name: String): BiliResult<Unit> = client.postAction(
        TAG_UPDATE_URL,
        form = mapOf("tagid" to id.toString(), "name" to name),
        params = TAG_PARAMS,
    )

    /** 删分组。组里的人退回默认分组,关注关系不受影响。 */
    suspend fun deleteGroup(id: Long): BiliResult<Unit> =
        client.postAction(TAG_DEL_URL, form = mapOf("tagid" to id.toString()), params = TAG_PARAMS)

    /**
     * 把一个人的分组**整个换成** [groupIds]。
     *
     * 接口叫 `tags/addUsers`,但它不是追加:传进去的就是这个人此后所属的全部分组,不在里面的
     * 一律被摘掉(notes/relation-groups.md 1.5)。所以调用方必须先用
     * [RelationRepository.groupsOf] 读齐当前的一份再写回,漏掉的那些会被静默清掉。
     *
     * 空集合要传 `"0"`(默认分组)而不是空串 —— 空串这条接口不认。
     */
    suspend fun replaceGroupsOf(mid: Long, groupIds: Set<Long>): BiliResult<Unit> = client.postAction(
        TAGS_ADD_USERS_URL,
        form = mapOf(
            "fids" to mid.toString(),
            // 括号不能省:`to` 是中缀函数,绑定比 `?:` 紧,没有括号时整个 Pair 会成为
            // `?:` 的左操作数,类型退化成 Serializable。
            "tagids" to (
                groupIds.takeIf { it.isNotEmpty() }?.joinToString(",")
                    ?: DEFAULT_GROUP_ID.toString()
                ),
        ),
        params = TAG_PARAMS,
    )

    // 特别关注**没有单独的方法**:它就是 tagid 为 -10 的那个分组,跟着 [replaceGroupsOf]
    // 一起覆盖提交。`tag/special/add` 与 `/del` 那两条接口确实存在(形状也和上面四条不同,
    // body 只有 fid 和 csrf),但在这条路上是多余的 —— 覆盖提交里带了 -10 之后再调它,
    // 服务端回 `22004 已经设置该属性了`。接口事实留在 notes/relation-groups.md 1.7。

    /**
     * 在自己的关注里按名字搜。**要 WBI 签名**,照 PiliPlus `member.dart:688-717` —— 浏览器
     * 里裸调也回 code 0,但风控是逐接口的(CLAUDE.md),按它实际发的来。
     *
     * `order_type` 固定 `attention`,同样照 PiliPlus:搜索结果的排序不跟着列表当前那个模式走,
     * 那样同一个词在两个模式下会给出两种顺序,而用户搜的是"这个人在哪",不是一份排序。
     */
    suspend fun searchFollowings(name: String, page: Int): BiliResult<List<UpBrief>> {
        val mid = settings.credentials.first().dedeUserId
        return client.getData<FollowingsDto>(
            SEARCH_URL,
            mapOf(
                "vmid" to mid,
                "pn" to page.toString(),
                "ps" to PAGE_SIZE.toString(),
                "order" to "desc",
                "order_type" to "attention",
                "name" to name,
                "gaia_source" to "main_web",
                "web_location" to "333.999",
            ),
            signed = true,
        ).map { dto -> dto.list.map { it.toBrief() } }
    }

    private fun FollowingDto.toBrief() =
        UpBrief(mid = mid, name = uname, faceUrl = face.toHttpsUrl(), sign = sign, special = special == 1)

    private fun dev.bilby.api.dto.PortalUpDto.toBrief() =
        UpBrief(mid = mid, name = uname, faceUrl = face.toHttpsUrl())

    private companion object {
        const val PORTAL_URL = "${BiliConstants.WEB_HOST}/x/polymer/web-dynamic/v1/portal"
        const val FOLLOWINGS_URL = "${BiliConstants.WEB_HOST}/x/relation/followings"
        const val TAGS_URL = "${BiliConstants.WEB_HOST}/x/relation/tags"
        const val GROUP_URL = "${BiliConstants.WEB_HOST}/x/relation/tag"
        const val SEARCH_URL = "${BiliConstants.WEB_HOST}/x/relation/followings/search"
        const val TAG_CREATE_URL = "${BiliConstants.WEB_HOST}/x/relation/tag/create"
        const val TAG_UPDATE_URL = "${BiliConstants.WEB_HOST}/x/relation/tag/update"
        const val TAG_DEL_URL = "${BiliConstants.WEB_HOST}/x/relation/tag/del"
        const val TAGS_ADD_USERS_URL = "${BiliConstants.WEB_HOST}/x/relation/tags/addUsers"
        const val PAGE_SIZE = 50

        /**
         * 四条分组写接口共同的 query。**只有这一个参数**:关注/取关那条还带 `statistics`
         * 并覆盖 Referer,这几条都不带(notes/relation-groups.md 1.0)。
         */
        val TAG_PARAMS = mapOf("x-bili-device-req-json" to DEVICE_REQ_JSON)

        const val DEVICE_REQ_JSON = """{"platform":"web","device":"pc","spmid":"333.1387"}"""
    }
}
