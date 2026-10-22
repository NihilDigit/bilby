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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import dev.bilby.data.SettingsStore
import dev.bilby.player.PlayerHandle
import dev.bilby.player.PlayerListener
import kotlinx.coroutines.CoroutineScope
import kotlin.math.abs

@Stable
class PlaybackSpeedController internal constructor(
    @Suppress("UNUSED_PARAMETER")
    scope: CoroutineScope,
    private val readSpeed: () -> Float,
    private val writeSpeed: (Float) -> Unit,
    private val readFastForwardSpeed: () -> Float = { SettingsStore.DEFAULT_FAST_FORWARD_SPEED },
) {
    internal var selection by mutableStateOf<SpeedSelection?>(null)
        private set
    var speed by mutableFloatStateOf(readSpeed())
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
    internal val defaultHoldSpeed: Float get() = readFastForwardSpeed()

    private var originalSpeed = speed
    private var drag: SpeedSelectionDrag? = null

    fun sync(speed: Float) {
        if (!active) this.speed = speed
    }

    fun open(temporary: Boolean = false, dockEnd: Boolean = false, initialSpeed: Float? = null) {
        if (active) finish(cancel = true)
        originalSpeed = readSpeed()
        lockedHint = null
        val next = SpeedSelection.start(
            original = originalSpeed,
            temporary = temporary,
            dockEnd = dockEnd,
            initial = initialSpeed ?: originalSpeed,
        )
        selection = next
        if (initialSpeed != null) {
            speed = next.speed
            flush(next.speed)
        } else {
            speed = originalSpeed
        }
    }

    fun beginDrag(): Boolean {
        val current = selection ?: return false
        if (dragging || current.locked) return false
        dragging = true
        drag = SpeedSelectionDrag()
        return true
    }

    fun move(
        alongDp: Float,
        acrossDp: Float,
    ) {
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
        val keep = current.locked || (!current.temporary && !cancel)
        val finalSpeed = if (keep) current.speed else originalSpeed
        selection = null
        dragging = false
        drag = null
        speed = finalSpeed
        flush(finalSpeed)
        if (current.locked) {
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
    fastForwardSpeed: Float = SettingsStore.DEFAULT_FAST_FORWARD_SPEED,
): PlaybackSpeedController {
    val scope = rememberCoroutineScope()
    val currentFastForwardSpeed by rememberUpdatedState(fastForwardSpeed)
    val controller = remember(player, mediaKey) {
        PlaybackSpeedController(
            scope = scope,
            readSpeed = { player?.playbackSpeed ?: 1f },
            writeSpeed = { player?.setPlaybackSpeed(it) },
            readFastForwardSpeed = { currentFastForwardSpeed },
        )
    }
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
