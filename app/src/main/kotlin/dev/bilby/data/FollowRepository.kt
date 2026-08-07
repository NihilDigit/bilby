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
    suspend fun frequentUps(): BiliResult<List<UpBrief>> =
        client.getData<PortalDto>(
            PORTAL_URL,
            // up_list_more=1 是必需的:不带它服务端照样返回 code 0,但 up_list 整个缺席,
            // 表现是这一排静默消失而没有任何错误可查。web_location 照 PiliPlus 传。
            mapOf("up_list_more" to "1", "web_location" to "333.1365"),
        ).map { dto -> dto.up_list?.items.orEmpty().map { it.toBrief() } }

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
