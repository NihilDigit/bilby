package dev.bilby.player

import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** 网络状态。由平台实现:Android 读 ConnectivityManager,桌面一律当作有网、不计费。 */
interface NetworkStatus {
    /** 此刻系统认不认为有网。不打探服务端,只问系统。 */
    fun hasInternet(): Boolean

    /** 有网与否的变化,首个值是当前状态。 */
    fun internetAvailability(): Flow<Boolean>

    /** 当前网络计不计费。拿不准时按计费算:默认画质宁低勿高。 */
    fun isMetered(): Boolean
}

/** 挂起到有网为止。已经有网时立即返回。 */
suspend fun NetworkStatus.awaitInternet() {
    internetAvailability().first { it }
}

/**
 * 问一次 API,[ApiProbeTimeoutMillis] 内回来了就算网络跟得上。装载前拿它和本地副本赛跑,
 * 见 LoadResolver 的 networkResponsive。
 *
 * 问的是 `x/web-interface/nav`(PiliPlus 的 userInfo):一个不带流地址的读接口,应用本来就
 * 频繁调它。只看回没回来,不看回的是什么 —— 未登录的 -101 也说明网络是通的。
 * 抛异常(断网、DNS 失败)同样算跟不上。
 */
suspend fun probeApi(client: BiliClient): Boolean =
    withTimeoutOrNull(ApiProbeTimeoutMillis) {
        runCatching { client.rawGet("${BiliConstants.WEB_HOST}/x/web-interface/nav") }.isSuccess
    } ?: false

/**
 * 赛跑的时限。正常网络下 nav 一两百毫秒回来;超过这个数,后面补 cid、取 playurl、起播每一步
 * 都要等同样久,不如直接放盘上那份。
 */
private const val ApiProbeTimeoutMillis = 400L
