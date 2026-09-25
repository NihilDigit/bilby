package dev.bilby.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

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

/** 公共代码经 [NetworkStatus] 问网络,Android 上就是上面这几个函数。 */
class AndroidNetworkStatus(private val context: Context) : NetworkStatus {
    override fun hasInternet(): Boolean = context.hasInternetNetwork()
    override fun internetAvailability(): Flow<Boolean> = context.internetAvailability()
    override fun isMetered(): Boolean = context.isOnMeteredNetwork()
}
