package dev.bilby.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import dev.bilby.api.BiliClient
import dev.bilby.api.BiliConstants
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * 此刻有没有一条能上网的网络。装载时决定放不放本地副本、下载时决定等网还是退避,都问它。
 *
 * **判据是 `NET_CAPABILITY_INTERNET`,不要求 `VALIDATED`。** 后者来自系统对一个固定地址的连通性
 * 探测,而那个地址在国内可能不通(不改探测地址的 ROM 上),于是一条完全能访问 B 站的网络被判成
 * 不可用,离线副本和"等网"都会在有网时误触发。INTERNET 只说"这条网络声称能上网",判错的那一面
 * 由请求本身的失败兜住。
 *
 * **系统服务拿不到时当作有网**,和 [isOnMeteredNetwork] 反过来:这里判错的代价是多试一次请求,
 * 反过来判则是有网也不去取。没有活动网络才是确定的"没网"。
 */
fun Context.hasInternetNetwork(): Boolean {
    val manager = getSystemService<ConnectivityManager>() ?: return true
    val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** 默认网络能不能上网的变化,起始值是当前状态。判据同 [hasInternetNetwork]。 */
fun Context.internetAvailability(): Flow<Boolean> = callbackFlow {
    val manager = getSystemService<ConnectivityManager>()
    if (manager == null) {
        trySend(true)
        awaitClose()
        return@callbackFlow
    }
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        }

        override fun onLost(network: Network) {
            trySend(false)
        }
    }
    trySend(hasInternetNetwork())
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()

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

/** 挂起到有网为止。已经有网时立即返回。 */
suspend fun Context.awaitInternet() {
    internetAvailability().first { it }
}
