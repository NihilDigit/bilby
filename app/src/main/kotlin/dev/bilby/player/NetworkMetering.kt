package dev.bilby.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService

/**
 * 当前这条网络算不算流量。默认画质按它分两档,见 [dev.bilby.data.PlayerPrefs.defaultQualityOn]。
 *
 * **判据是 `NET_CAPABILITY_NOT_METERED` 而不是 `TRANSPORT_WIFI`。** 要省的是流量,而手机热点
 * 和按量计费的 WiFi 在 transport 眼里都是 WiFi —— 拿 transport 判,这两种情况会直接上高画质,
 * 正是这个功能要避免的那件事。系统这一位来自用户在流量设置里的标记,比我们猜得准。
 *
 * **读不到时当作计费。** 拿不到网络能力有两种情况:真的没网,或者系统没告诉我们。前者取流本来
 * 就会失败,后者宁可省着来 —— 猜错方向的代价不对称,多花的流量收不回来,而画质低一档看得见、
 * 用户随手就能在播放页调上去。
 */
fun Context.isOnMeteredNetwork(): Boolean {
    val manager = getSystemService<ConnectivityManager>() ?: return true
    val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return true
    return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
