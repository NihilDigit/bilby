package dev.bilby.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Immutable
data class BatteryStatus(
    val percent: Int,
    val isCharging: Boolean,
)

enum class NetworkKind {
    Wifi,
    Cellular5G,
    CellularLte,
    CellularOther,
    Ethernet,
    Offline,
    Unknown,
}

@Immutable
data class PlayerSystemStatus(
    val timeText: String,
    val battery: BatteryStatus?,
    val network: NetworkKind,
)

/**
 * 全屏播放顶栏右端的系统状态读数(当前时间、电量与充电态、网络类别)。
 *
 * [enabled] 为 `false`(内嵌或画中画)时不注册任何系统监听、不开启后台轮询。
 */
@Composable
expect fun rememberPlayerSystemStatus(enabled: Boolean): PlayerSystemStatus

private val ClockMinuteFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun currentClockTimeText(): String = LocalTime.now().format(ClockMinuteFormatter)

/**
 * 当前时间(`HH:mm`),仅在整分边界唤醒一次;[enabled] 为 `false` 时完全休眠。
 */
@Composable
internal fun rememberCurrentTimeText(enabled: Boolean): String {
    val timeText by produceState(
        initialValue = if (enabled) currentClockTimeText() else "",
        key1 = enabled,
    ) {
        if (!enabled) {
            value = ""
            return@produceState
        }
        while (true) {
            value = currentClockTimeText()
            val nowMillis = System.currentTimeMillis()
            val delayMillis = (60_000L - (nowMillis % 60_000L) + 20L).coerceIn(50L, 60_050L)
            delay(delayMillis)
        }
    }
    return timeText
}
