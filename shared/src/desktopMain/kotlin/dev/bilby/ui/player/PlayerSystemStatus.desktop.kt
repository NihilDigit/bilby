package dev.bilby.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dev.bilby.BiliLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.net.NetworkInterface
import java.util.Collections

private const val DESKTOP_POLL_INTERVAL_MILLIS = 10_000L

private const val SYSTEM_POWER_STATUS_SIZE = 12L
private const val BATTERY_FLAG_CHARGING = 0x08
private const val BATTERY_FLAG_NO_BATTERY = 0x80
private const val BATTERY_FLAG_UNKNOWN = 0xFF
private const val AC_LINE_ONLINE = 1

private const val AF_INET: Short = 2
private const val SOCKADDR_IN_SIZE = 16L
private const val MIB_IFROW_SIZE = 860L
private const val MIB_IFROW_DW_INDEX_OFFSET = 512L
private const val MIB_IFROW_DW_TYPE_OFFSET = 516L
private const val MIB_IFROW_DW_DESCR_LEN_OFFSET = 600L
private const val MIB_IFROW_B_DESCR_OFFSET = 604L
private const val MAXLEN_IFDESCR = 256

private const val IF_TYPE_OTHER = 1
private const val IF_TYPE_ETHERNET_CSMACD = 6
private const val IF_TYPE_PPP = 23
private const val IF_TYPE_PROP_VIRTUAL = 53
private const val IF_TYPE_IEEE80211 = 71
private const val IF_TYPE_GIGABITETHERNET = 117
private const val IF_TYPE_TUNNEL = 131
private const val IF_TYPE_WWANPP = 243
private const val IF_TYPE_WWANPP2 = 244

private val IsWindowsHost: Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

private data class DesktopHardwareSample(
    val battery: BatteryStatus?,
    val network: NetworkKind,
)

private val IdleHardwareSample = DesktopHardwareSample(
    battery = null,
    network = NetworkKind.Unknown,
)

@Composable
actual fun rememberPlayerSystemStatus(enabled: Boolean): PlayerSystemStatus {
    val timeText = rememberCurrentTimeText(enabled)
    val sample by produceState(initialValue = IdleHardwareSample, key1 = enabled) {
        if (!enabled) {
            value = IdleHardwareSample
            return@produceState
        }
        while (true) {
            value = withContext(Dispatchers.IO) { sampleDesktopStatus() }
            delay(DESKTOP_POLL_INTERVAL_MILLIS)
        }
    }
    if (!enabled) {
        return PlayerSystemStatus(
            timeText = "",
            battery = null,
            network = NetworkKind.Unknown,
        )
    }
    return PlayerSystemStatus(
        timeText = timeText,
        battery = sample.battery,
        network = sample.network,
    )
}

private fun sampleDesktopStatus(): DesktopHardwareSample {
    if (!IsWindowsHost) {
        return DesktopHardwareSample(
            battery = null,
            network = inspectJavaPhysicalNetwork(allowOfflineFallback = true),
        )
    }
    val native = SystemStatusNative ?: return DesktopHardwareSample(
        battery = null,
        network = inspectJavaPhysicalNetwork(allowOfflineFallback = true),
    )
    val battery = runCatching { queryWindowsBattery(native) }
        .onFailure { BiliLog.w("读取 Windows 电源状态失败", it) }
        .getOrNull()
    val network = runCatching { queryWindowsNetwork(native) }
        .onFailure { BiliLog.w("读取 Windows 网络状态失败", it) }
        .getOrDefault(NetworkKind.Unknown)
    return DesktopHardwareSample(battery = battery, network = network)
}

private fun queryWindowsBattery(native: SystemStatusBindings): BatteryStatus? {
    Arena.ofConfined().use { arena ->
        val seg = arena.allocate(SYSTEM_POWER_STATUS_SIZE, 4)
        val ok = native.getSystemPowerStatus.invokeExact(seg) as Int
        if (ok == 0) return null
        val acLineStatus = seg.get(JAVA_BYTE, 0).toInt() and 0xFF
        val batteryFlag = seg.get(JAVA_BYTE, 1).toInt() and 0xFF
        val percent = seg.get(JAVA_BYTE, 2).toInt() and 0xFF
        // 台式机无电池时 BatteryFlag 置 0x80(No system battery),此时完全隐藏电量指示。
        if ((batteryFlag and BATTERY_FLAG_NO_BATTERY) != 0 || batteryFlag == BATTERY_FLAG_UNKNOWN || percent > 100) {
            return null
        }
        val isCharging = (batteryFlag and BATTERY_FLAG_CHARGING) != 0 || acLineStatus == AC_LINE_ONLINE
        return BatteryStatus(percent = percent, isCharging = isCharging)
    }
}

private fun queryWindowsNetwork(native: SystemStatusBindings): NetworkKind {
    Arena.ofConfined().use { arena ->
        // GetBestInterfaceEx 仅做内核路由表查表,不发任何网络包。
        val sockaddr = arena.allocate(SOCKADDR_IN_SIZE, 4)
        sockaddr.set(JAVA_SHORT, 0, AF_INET)
        sockaddr.set(JAVA_BYTE, 4, 223.toByte())
        sockaddr.set(JAVA_BYTE, 5, 5.toByte())
        sockaddr.set(JAVA_BYTE, 6, 5.toByte())
        sockaddr.set(JAVA_BYTE, 7, 5.toByte())

        val outIndex = arena.allocate(JAVA_INT)
        val bestErr = native.getBestInterfaceEx.invokeExact(sockaddr, outIndex) as Int
        if (bestErr != 0) return NetworkKind.Offline

        val bestIfIndex = outIndex.get(JAVA_INT, 0)
        if (bestIfIndex == 0) return NetworkKind.Offline

        val ifRow = arena.allocate(MIB_IFROW_SIZE, 4)
        ifRow.set(JAVA_INT, MIB_IFROW_DW_INDEX_OFFSET, bestIfIndex)
        val entryErr = native.getIfEntry.invokeExact(ifRow) as Int
        if (entryErr != 0) return inspectJavaPhysicalNetwork(allowOfflineFallback = false)

        return when (ifRow.get(JAVA_INT, MIB_IFROW_DW_TYPE_OFFSET)) {
            IF_TYPE_IEEE80211 -> NetworkKind.Wifi
            IF_TYPE_WWANPP, IF_TYPE_WWANPP2 -> NetworkKind.CellularLte
            IF_TYPE_ETHERNET_CSMACD, IF_TYPE_GIGABITETHERNET -> {
                val descrLen = ifRow.get(JAVA_INT, MIB_IFROW_DW_DESCR_LEN_OFFSET).coerceIn(0, MAXLEN_IFDESCR)
                val descr = if (descrLen > 0) {
                    val bytes = ifRow.asSlice(MIB_IFROW_B_DESCR_OFFSET, descrLen.toLong()).toArray(JAVA_BYTE)
                    String(bytes, Charsets.ISO_8859_1).trimEnd('\u0000').lowercase()
                } else {
                    ""
                }
                if (descr.isNotEmpty() && VirtualAdapterKeywords.any { descr.contains(it) }) {
                    inspectJavaPhysicalNetwork(allowOfflineFallback = false)
                } else {
                    NetworkKind.Ethernet
                }
            }
            // TUN / Clash / WireGuard 等虚拟网卡接管默认路由时,回退扫描活动的物理网卡以区分 Wi-Fi 与有线。
            IF_TYPE_PROP_VIRTUAL, IF_TYPE_TUNNEL, IF_TYPE_PPP, IF_TYPE_OTHER ->
                inspectJavaPhysicalNetwork(allowOfflineFallback = false)
            else -> inspectJavaPhysicalNetwork(allowOfflineFallback = false)
        }
    }
}

private val VirtualAdapterKeywords = listOf(
    "vethernet",
    "virtual",
    "vmware",
    "vbox",
    "virtualbox",
    "hyper-v",
    "wsl",
    "tun",
    "tap",
    "wintun",
    "wireguard",
    "clash",
    "sing-box",
    "singbox",
    "tailscale",
    "zerotier",
    "vpn",
    "wan miniport",
    "bluetooth",
    "npcap",
    "loopback",
    "teredo",
    "isatap",
)

private val WifiAdapterKeywords = listOf(
    "wi-fi",
    "wifi",
    "wlan",
    "802.11",
    "wireless",
)

private val CellularAdapterKeywords = listOf(
    "wwan",
    "cellular",
    "mobile broadband",
)

private fun inspectJavaPhysicalNetwork(allowOfflineFallback: Boolean): NetworkKind {
    return runCatching {
        val enumeration = NetworkInterface.getNetworkInterfaces()
            ?: return@runCatching if (allowOfflineFallback) NetworkKind.Offline else NetworkKind.Unknown
        val physicalUp = Collections.list(enumeration).filter { nif ->
            if (!nif.isUp || nif.isLoopback || nif.isVirtual || nif.isPointToPoint) return@filter false
            val descriptor = "${nif.name.orEmpty()} ${nif.displayName.orEmpty()}".lowercase()
            if (VirtualAdapterKeywords.any { descriptor.contains(it) }) return@filter false
            nif.inetAddresses.asSequence().any { addr -> !addr.isLoopbackAddress && !addr.isLinkLocalAddress }
        }
        if (physicalUp.isEmpty()) {
            return@runCatching if (allowOfflineFallback) NetworkKind.Offline else NetworkKind.Unknown
        }
        val descriptors = physicalUp.map { "${it.name.orEmpty()} ${it.displayName.orEmpty()}".lowercase() }
        when {
            descriptors.any { d -> WifiAdapterKeywords.any { d.contains(it) } } -> NetworkKind.Wifi
            descriptors.any { d -> CellularAdapterKeywords.any { d.contains(it) } } -> NetworkKind.CellularLte
            else -> NetworkKind.Ethernet
        }
    }.onFailure { BiliLog.w("枚举物理网络接口失败", it) }
        .getOrDefault(NetworkKind.Unknown)
}

private class SystemStatusBindings(
    val getSystemPowerStatus: MethodHandle,
    val getBestInterfaceEx: MethodHandle,
    val getIfEntry: MethodHandle,
)

private val SystemStatusNative: SystemStatusBindings? by lazy {
    if (!IsWindowsHost) return@lazy null
    runCatching {
        val linker = Linker.nativeLinker()
        val kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global())
        val iphlpapi = SymbolLookup.libraryLookup("iphlpapi.dll", Arena.global())
        SystemStatusBindings(
            getSystemPowerStatus = linker.downcallHandle(
                kernel32.findOrThrow("GetSystemPowerStatus"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS),
            ),
            getBestInterfaceEx = linker.downcallHandle(
                iphlpapi.findOrThrow("GetBestInterfaceEx"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS),
            ),
            getIfEntry = linker.downcallHandle(
                iphlpapi.findOrThrow("GetIfEntry"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS),
            ),
        )
    }.onFailure { BiliLog.w("加载 kernel32/iphlpapi 系统状态符号失败", it) }.getOrNull()
}
