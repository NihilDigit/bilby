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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import dev.bilby.player.PlayerHandle
import dev.bilby.player.PlayerListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@Stable
class PlaybackSpeedController internal constructor(
    private val scope: CoroutineScope,
    private val readSpeed: () -> Float,
    private val writeSpeed: (Float) -> Unit,
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

    private var originalSpeed = speed
    private var drag: SpeedSelectionDrag? = null
    private var pending: Float? = null
    private var writeJob: Job? = null
    private var repeatJob: Job? = null
    private var repeatRevision = -1

    fun sync(speed: Float) {
        if (!active) this.speed = speed
    }

    fun open(temporary: Boolean = false) {
        if (active) finish(cancel = true)
        originalSpeed = readSpeed()
        lockedHint = null
        selection = SpeedSelection.start(originalSpeed, temporary)
        speed = originalSpeed
    }

    fun beginDrag(): Boolean {
        val current = selection ?: return false
        if (dragging || current.locked) return false
        dragging = true
        drag = SpeedSelectionDrag()
        return true
    }

    fun move(alongDp: Float, acrossDp: Float) {
        val current = selection ?: return
        update(drag?.move(current, alongDp, acrossDp) ?: return, allowUnlock = true)
        refreshRepeat()
    }

    fun finer() {
        selection?.let { update(it.finer()) }
    }

    fun coarserOrLock() {
        selection?.let {
            if (it.fine) update(it.coarser())
            else {
                lock()
                finish()
            }
        }
    }

    fun step(direction: Int) {
        selection?.let {
            update(if (it.fine) it.fineAt(it.value + direction) else it.coarseAt(it.position + direction))
        }
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
        selection?.let { update(it.fineAt(speedHundredths(speed))) }
    }

    fun lock() {
        selection?.let { update(it.copy(locked = true)) }
    }

    fun finish(cancel: Boolean = false) {
        val current = selection ?: return
        val lockOnRelease = current.fine && !cancel
        val keep = current.locked || lockOnRelease || (!current.temporary && !cancel)
        val finalSpeed = if (keep) current.speed else originalSpeed
        selection = null
        dragging = false
        repeatJob?.cancel()
        repeatJob = null
        repeatRevision = -1
        drag = null
        speed = finalSpeed
        flush(finalSpeed)
        if (current.locked || lockOnRelease) {
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
        val coarseStep = dragging && !next.fine && !next.locked && previous?.value != next.value
        if (coarseStep) coarseStepRevision++
        if (next.locked || coarseStep) flush(next.speed) else enqueue(next.speed)
    }

    private fun refreshRepeat() {
        val gesture = drag ?: return
        if (gesture.repeatIntervalMillis == null || repeatRevision != gesture.repeatRevision) {
            repeatJob?.cancel()
            repeatJob = null
        }
        repeatRevision = gesture.repeatRevision
        if (gesture.repeatIntervalMillis == null || repeatJob?.isActive == true) return
        repeatJob = scope.launch {
            while (dragging) {
                delay(gesture.repeatIntervalMillis ?: break)
                val current = selection ?: break
                if (current.locked || gesture.repeatIntervalMillis == null) break
                val next = gesture.repeat(current)
                if (next == current) break
                update(next)
            }
        }
    }

    private fun enqueue(value: Float) {
        pending = value
        if (writeJob?.isActive == true) return
        // 连续精调合并成每 40ms 一次;松手、取消和锁定都由 flush 保证最后一档落地。
        writeJob = scope.launch {
            delay(40)
            val latest = pending
            pending = null
            writeJob = null
            if (latest != null && abs(readSpeed() - latest) > 0.0001f) writeSpeed(latest)
        }
    }

    private fun flush(value: Float) {
        writeJob?.cancel()
        writeJob = null
        pending = null
        if (abs(readSpeed() - value) > 0.0001f) writeSpeed(value)
    }
}

@Composable
internal fun rememberPlaybackSpeedController(player: PlayerHandle?, mediaKey: Any): PlaybackSpeedController {
    val scope = rememberCoroutineScope()
    val controller = remember(player, mediaKey) {
        PlaybackSpeedController(scope, { player?.playbackSpeed ?: 1f }, { player?.setPlaybackSpeed(it) })
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
