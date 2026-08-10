package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.FollowingsDto
import dev.bilby.api.dto.PortalDto
import dev.bilby.api.getData
import dev.bilby.api.map
import dev.bilby.api.toHttpsUrl
import kotlinx.coroutines.flow.first

/** 列表里的一个 UP。头像地址已改写成 https,直接交给 Coil。 */
data class UpBrief(
    val mid: Long,
    val name: String,
    val faceUrl: String,
    val sign: String = "",
)

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
 * 关注关系的读取侧(改关注在 [RelationRepository])。
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
     * 关注列表。`order_type=attention` 与顶部那排同序 —— 从那排点进完整列表时,
     * 换一种排法会让人以为进错了地方。
     */
    suspend fun followings(page: Int): BiliResult<List<UpBrief>> {
        val mid = settings.credentials.first().dedeUserId
        return client.getData<FollowingsDto>(
            FOLLOWINGS_URL,
            mapOf(
                "vmid" to mid,
                "pn" to page.toString(),
                "ps" to PAGE_SIZE.toString(),
                "order" to "desc",
                "order_type" to "attention",
            ),
        ).map { dto -> dto.list.map { UpBrief(it.mid, it.uname, it.face.toHttpsUrl(), it.sign) } }
    }

    private fun dev.bilby.api.dto.PortalUpDto.toBrief() =
        UpBrief(mid = mid, name = uname, faceUrl = face.toHttpsUrl())

    private companion object {
        const val PORTAL_URL = "${BiliConstants.WEB_HOST}/x/polymer/web-dynamic/v1/portal"
        const val FOLLOWINGS_URL = "${BiliConstants.WEB_HOST}/x/relation/followings"
        const val PAGE_SIZE = 50
    }
}
