package dev.bilby.data

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.api.dto.NavInfoDto
import dev.bilby.api.getData
import dev.bilby.api.propagateFailure
import dev.bilby.api.toHttpsUrl

data class AccountInfo(
    val mid: Long,
    val name: String,
    val faceUrl: String,
    val level: Int,
    val isSeniorMember: Boolean,
    /** 个性签名。可能为空,拿不到时也是空——个人页对空签名的处理是整行不显示。 */
    val sign: String,
)

/**
 * 个人页头部要的账号身份(头像/名字/等级/签名),主体来自 `x/web-interface/nav`。
 * 这不是 [SpaceRepository] 的 `acc/info`——那个接口要 `mid` 查*别人*的空间,这里查的是
 * 当前登录账号自己,`nav` 不带 mid 参数、靠 Cookie 认身份,两者不能互相替代。
 *
 * **`nav` 不带个性签名。** 核实过 PiliPlus 的 `UserInfoData`(`models/user/info.dart`)——
 * 它就是拿 nav 的响应体解出来的模型,字段列到 `pendant`/`vipLabel`/`isSeniorMember` 这种
 * 边角信息,唯独没有 `sign`;PiliPlus 自己的"我的"页(`pages/mine/controller.dart`,同样只调
 * `UserHttp.userInfo()` 也就是 nav)也确实不显示签名。所以拿签名要再打一次
 * [SpaceRepository.loadUserInfo](即 `x/space/wbi/acc/info`,带 `sign` 字段)——
 * 用自己的 mid 查自己的空间信息,和空间页读别人是同一个接口,不是新起的路。
 */
class AccountRepository(
    private val client: BiliClient,
    private val spaceRepository: SpaceRepository,
) {

    suspend fun loadInfo(): BiliResult<AccountInfo> {
        val nav = client.getData<NavInfoDto>("${BiliConstants.WEB_HOST}/x/web-interface/nav")
        if (nav !is BiliResult.Ok) return nav.propagateFailure()

        // 签名拿不到就留空,不阻断头像/名字/等级的展示——签名是头部的锦上添花,
        // 不该让第二次请求的成败决定整个头部能不能显示。
        val sign = (spaceRepository.loadUserInfo(nav.value.mid) as? BiliResult.Ok)?.value?.sign.orEmpty()
        return BiliResult.Ok(nav.value.toDomain(sign))
    }

    private fun NavInfoDto.toDomain(sign: String) = AccountInfo(
        mid = mid,
        name = uname,
        faceUrl = face.toHttpsUrl(),
        level = levelInfo.currentLevel,
        isSeniorMember = isSeniorMember == 1,
        sign = sign,
    )
}
