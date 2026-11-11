package dev.bilby.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import dev.bilby.player.PlayerHandle
import dev.bilby.player.PlayerListener
import kotlin.math.abs

@Stable
class PlaybackSpeedController internal constructor(
    private val readSpeed: () -> Float,
    private val writeSpeed: (Float) -> Unit,
    defaultHoldSpeed: Float = DefaultHoldSpeed,
) {
    internal var selection by mutableStateOf<SpeedSelection?>(null)
        private set
    var speed by mutableFloatStateOf(readSpeed())
        private set
    var defaultHoldSpeed by mutableFloatStateOf(defaultHoldSpeed)
    var lockedByHold by mutableStateOf(false)
        private set
    var lockedHint by mutableStateOf<Int?>(null)
        private set
    var hintRevision by mutableIntStateOf(0)
        private set
    var dragging by mutableStateOf(false)
        private set
    internal var coarseStepRevision by mutableIntStateOf(0)
        private set

    val active: Boolean get() = selection != null

    /** 未被长按锁定的常驻基准倍速(如 1.0x 或面板设定的倍速)。 */
    private var baseSpeed = speed
    private var lockedHoldSpeed = speed
    private var preOpenSpeed = speed
    private var preOpenLocked = false
    private var drag: SpeedSelectionDrag? = null

    fun sync(speed: Float) {
        if (!active) {
            this.speed = speed
            if (lockedByHold && abs(speed - lockedHoldSpeed) > 0.005f) {
                lockedByHold = false
                baseSpeed = speed
            } else if (!lockedByHold) {
                baseSpeed = speed
            }
        }
    }

    /** 设置面板显式修改常驻倍速:清除长按锁定态并以新倍速作为基准原速。 */
    fun applySpeed(target: Float) {
        if (active) cancel()
        lockedByHold = false
        lockedHint = null
        baseSpeed = target
        speed = target
        flush(target)
    }

    fun open(temporary: Boolean = false, dockEnd: Boolean = false, dockTop: Boolean = false) {
        if (active) finish(cancel = true)
        val currentActual = readSpeed()
        preOpenSpeed = currentActual
        preOpenLocked = lockedByHold
        if (!lockedByHold) baseSpeed = currentActual
        val initial = when {
            !temporary -> currentActual
            lockedByHold -> currentActual
            else -> defaultHoldSpeed
        }
        lockedHint = null
        val started = SpeedSelection.start(initial, temporary, dockEnd = dockEnd, dockTop = dockTop)
        selection = started
        speed = started.speed
        flush(started.speed)
    }

    fun beginDrag(vertical: Boolean = true): Boolean {
        val current = selection ?: return false
        if (dragging || current.locked) return false
        dragging = true
        drag = SpeedSelectionDrag(
            stepDp = if (vertical) SpeedVerticalDragStepDp else SpeedHorizontalDragStepDp,
        )
        return true
    }

    fun move(alongDp: Float, acrossDp: Float) {
        val current = selection ?: return
        update(
            drag?.move(current, alongDp, acrossDp) ?: return,
            allowUnlock = true,
        )
    }

    fun step(direction: Int) {
        selection?.let { update(it.coarseAt(it.position + direction)) }
    }

    fun choose(value: Int) {
        if (dragging) return
        val current = selection ?: return
        val index = current.choices.indexOf(value)
        if (index < 0) return
        update(current.coarseAt(index.toFloat()).copy(locked = true))
        finish()
    }

    fun adjustTo(speed: Float) {
        selection?.let { update(it.nearestTo(speedHundredths(speed))) }
    }

    fun lock() {
        selection?.let { update(it.copy(locked = true)) }
    }

    fun finish(cancel: Boolean = false) {
        val current = selection ?: return
        val finalSpeed = when {
            cancel -> {
                lockedByHold = preOpenLocked
                preOpenSpeed
            }
            current.locked -> {
                val chosen = current.speed
                if (current.temporary) {
                    lockedByHold = true
                    lockedHoldSpeed = chosen
                } else {
                    lockedByHold = false
                    baseSpeed = chosen
                }
                chosen
            }
            !current.temporary -> {
                val chosen = current.speed
                lockedByHold = false
                baseSpeed = chosen
                chosen
            }
            else -> {
                // 画面长按未锁定直接松手(含此前已处于长按锁定态时再次长按未重锁):解除锁定回到基准原速。
                lockedByHold = false
                baseSpeed
            }
        }
        selection = null
        dragging = false
        drag = null
        speed = finalSpeed
        flush(finalSpeed)
        if (!cancel && current.locked) {
            lockedHint = current.value
            hintRevision++
        }
    }

    fun cancel() = finish(cancel = true)

    fun clearHint() {
        lockedHint = null
    }

    private fun update(next: SpeedSelection, allowUnlock: Boolean = false) {
        val previous = selection
        if (previous?.locked == true && !allowUnlock) return
        if (previous == next) return
        selection = next
        speed = next.speed
        if (dragging && !next.locked && previous?.value != next.value) {
            coarseStepRevision++
        }
        flush(next.speed)
    }

    private fun flush(value: Float) {
        if (abs(readSpeed() - value) > 0.0001f) writeSpeed(value)
    }
}

@Composable
internal fun rememberPlaybackSpeedController(
    player: PlayerHandle?,
    mediaKey: Any,
    defaultHoldSpeed: Float = DefaultHoldSpeed,
): PlaybackSpeedController {
    val controller = remember(player, mediaKey) {
        PlaybackSpeedController(
            readSpeed = { player?.playbackSpeed ?: 1f },
            writeSpeed = { player?.setPlaybackSpeed(it) },
            defaultHoldSpeed = defaultHoldSpeed,
        )
    }
    controller.defaultHoldSpeed = defaultHoldSpeed
    DisposableEffect(player, controller) {
        val listener = object : PlayerListener {
            override fun onPlaybackSpeedChanged(speed: Float) = controller.sync(speed)
        }
        player?.addListener(listener)
        player?.let { controller.sync(it.playbackSpeed) }
        onDispose {
            controller.cancel()
            player?.removeListener(listener)
        }
    }
    val window = LocalWindowInfo.current.containerSize
    val vertical = window.width > window.height
    LaunchedEffect(controller, vertical) { controller.cancel() }
    return controller
}
