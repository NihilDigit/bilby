package dev.bilby.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.bilby.BiliLog
import kotlin.math.roundToInt

private enum class AndroidTransport {
    Ethernet,
    Wifi,
    Cellular,
    Offline,
    Unknown,
}

private const val NETWORK_TYPE_LTE_CA = 19

@Composable
actual fun rememberPlayerSystemStatus(enabled: Boolean): PlayerSystemStatus {
    val context = LocalContext.current
    val timeText = rememberCurrentTimeText(enabled)

    var battery by remember { mutableStateOf<BatteryStatus?>(null) }
    var transport by remember { mutableStateOf(AndroidTransport.Unknown) }
    var cellularKind by remember { mutableStateOf(NetworkKind.CellularOther) }

    DisposableEffect(context, enabled) {
        if (!enabled) {
            battery = null
            return@DisposableEffect onDispose { }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                battery = parseBatteryIntent(intent)
            }
        }
        var registered = false
        val sticky = runCatching {
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                .also { registered = true }
        }.onFailure { BiliLog.w("注册电量广播监听失败", it) }.getOrNull()
        battery = parseBatteryIntent(sticky)

        onDispose {
            if (registered) {
                runCatching { context.unregisterReceiver(receiver) }
                    .onFailure { BiliLog.w("注销电量广播监听失败", it) }
            }
        }
    }

    DisposableEffect(context, enabled) {
        if (!enabled) {
            transport = AndroidTransport.Unknown
            return@DisposableEffect onDispose { }
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            transport = AndroidTransport.Unknown
            return@DisposableEffect onDispose { }
        }
        transport = resolveAndroidTransport(cm, capsOverride = null)

        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                transport = resolveAndroidTransport(cm, capsOverride = null)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                transport = resolveAndroidTransport(cm, capsOverride = networkCapabilities)
            }

            override fun onLost(network: Network) {
                transport = resolveAndroidTransport(cm, capsOverride = null)
            }

            override fun onUnavailable() {
                transport = AndroidTransport.Offline
            }
        }
        var registered = false
        runCatching {
            cm.registerDefaultNetworkCallback(callback, mainHandler)
            registered = true
        }.onFailure { BiliLog.w("注册默认网络状态回调失败", it) }

        onDispose {
            if (registered) {
                runCatching { cm.unregisterNetworkCallback(callback) }
                    .onFailure { BiliLog.w("注销默认网络状态回调失败", it) }
            }
        }
    }

    val observeCellular = enabled && transport == AndroidTransport.Cellular
    DisposableEffect(context, observeCellular) {
        if (!observeCellular) {
            return@DisposableEffect onDispose { }
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cellularKind = resolveFallbackCellularKind(cm)
        val unregister = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Api31CellularObserver.register(context, cm) { kind -> cellularKind = kind }
        } else {
            null
        }
        onDispose {
            unregister?.invoke()
        }
    }

    if (!enabled) {
        return PlayerSystemStatus(
            timeText = "",
            battery = null,
            network = NetworkKind.Unknown,
        )
    }

    val networkKind = when (transport) {
        AndroidTransport.Ethernet -> NetworkKind.Ethernet
        AndroidTransport.Wifi -> NetworkKind.Wifi
        AndroidTransport.Cellular -> cellularKind
        AndroidTransport.Offline -> NetworkKind.Offline
        AndroidTransport.Unknown -> NetworkKind.Unknown
    }

    return PlayerSystemStatus(
        timeText = timeText,
        battery = battery,
        network = networkKind,
    )
}

private fun parseBatteryIntent(intent: Intent?): BatteryStatus? {
    if (intent == null) return null
    val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (!present || level < 0 || scale <= 0) return null
    val percent = ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || plugged != 0
    return BatteryStatus(percent = percent, isCharging = isCharging)
}

private fun resolveAndroidTransport(
    cm: ConnectivityManager,
    capsOverride: NetworkCapabilities?,
): AndroidTransport {
    val caps = capsOverride ?: runCatching {
        cm.activeNetwork?.let(cm::getNetworkCapabilities)
    }.onFailure { BiliLog.w("读取活动网络能力失败", it) }.getOrNull()

    if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
        return AndroidTransport.Offline
    }
    // 先判物理传输层,再看 VPN:Android 在挂 VPN 时保留底层物理网卡的 TRANSPORT_* 位,
    // 先判 TRANSPORT_VPN 会把所有开着代理的设备误判成未知传输层。
    return when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> AndroidTransport.Ethernet
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> AndroidTransport.Wifi
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> AndroidTransport.Cellular
        else -> AndroidTransport.Unknown
    }
}

@Suppress("DEPRECATION")
private fun resolveFallbackCellularKind(cm: ConnectivityManager?): NetworkKind {
    if (cm == null) return NetworkKind.CellularOther
    val subtype = runCatching { cm.activeNetworkInfo?.subtype }
        .onFailure { BiliLog.w("读取蜂窝网络子类型失败", it) }
        .getOrNull() ?: return NetworkKind.CellularOther
    return when (subtype) {
        TelephonyManager.NETWORK_TYPE_NR -> NetworkKind.Cellular5G
        TelephonyManager.NETWORK_TYPE_LTE, NETWORK_TYPE_LTE_CA -> NetworkKind.CellularLte
        else -> NetworkKind.CellularOther
    }
}

/**
 * 单独封装在 `@RequiresApi(31)` 对象内,避免 API 29–30 类加载校验期触碰 [TelephonyCallback]。
 * `DisplayInfoListener` 在 Android 12+ 无需 `READ_PHONE_STATE`;部分定制系统若仍抛
 * `SecurityException`,由 `runCatching` 接住并回退到 [resolveFallbackCellularKind]。
 */
@RequiresApi(Build.VERSION_CODES.S)
private object Api31CellularObserver {
    fun register(
        context: Context,
        cm: ConnectivityManager?,
        onUpdate: (NetworkKind) -> Unit,
    ): (() -> Unit)? {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        val callback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                onUpdate(mapDisplayInfo(telephonyDisplayInfo, cm))
            }
        }
        return runCatching {
            tm.registerTelephonyCallback(context.mainExecutor, callback)
            val unregister: () -> Unit = {
                runCatching { tm.unregisterTelephonyCallback(callback) }
                    .onFailure { BiliLog.w("注销蜂窝网络显示信息回调失败", it) }
            }
            unregister
        }.onFailure {
            BiliLog.w("注册蜂窝网络显示信息回调失败,回退至 NetworkInfo", it)
        }.getOrNull()
    }

    private fun mapDisplayInfo(
        info: TelephonyDisplayInfo,
        cm: ConnectivityManager?,
    ): NetworkKind {
        val overrideType = info.overrideNetworkType
        val netType = info.networkType
        return when {
            overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE ||
                overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED ||
                netType == TelephonyManager.NETWORK_TYPE_NR -> NetworkKind.Cellular5G

            overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA ||
                overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO ||
                netType == TelephonyManager.NETWORK_TYPE_LTE -> NetworkKind.CellularLte

            netType == TelephonyManager.NETWORK_TYPE_UNKNOWN -> resolveFallbackCellularKind(cm)
            else -> NetworkKind.CellularOther
        }
    }
}
