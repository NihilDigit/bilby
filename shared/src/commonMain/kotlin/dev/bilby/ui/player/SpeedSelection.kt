package dev.bilby.ui.player

import kotlin.math.abs
import kotlin.math.roundToInt

internal const val SpeedBlockStepDp = 136f
internal const val SpeedFineStepDp = 6f
internal const val SpeedModeStepDp = 36f
internal const val SpeedSelectionIntentDp = 24f
private const val SpeedSelectionStopDp = 12f

internal val SpeedPresets = listOf(50, 75, 100, 125, 150, 200, 250, 300)

internal fun speedHundredths(speed: Float): Int = (speed * 100).roundToInt().coerceIn(50, 300)

internal fun speedNumber(value: Int): String = when {
    value % 100 == 0 -> (value / 100).toString()
    value % 10 == 0 -> "${value / 100}.${value % 100 / 10}"
    else -> "${value / 100}.${(value % 100).toString().padStart(2, '0')}"
}

internal fun preciseSpeedNumber(value: Int): String =
    "${value / 100}.${(value % 100).toString().padStart(2, '0')}"

internal data class SpeedSelection(
    val value: Int,
    val choices: List<Int>,
    val position: Float,
    val temporary: Boolean,
    val fine: Boolean = false,
    val locked: Boolean = false,
) {
    val speed: Float get() = value / 100f

    fun finer(): SpeedSelection = copy(fine = true)

    fun coarser(): SpeedSelection {
        val next = (choices + value).distinct().sorted()
        return copy(fine = false, choices = next, position = next.indexOf(value).toFloat())
    }

    fun coarseAt(position: Float): SpeedSelection {
        val bounded = position.coerceIn(0f, choices.lastIndex.toFloat())
        return copy(position = bounded, value = choices[bounded.roundToInt()])
    }

    fun fineAt(value: Int): SpeedSelection = copy(value = value.coerceIn(50, 300))

    companion object {
        fun start(original: Float, temporary: Boolean): SpeedSelection {
            val value = speedHundredths(original)
            val choices = (SpeedPresets + value).distinct().sorted()
            return SpeedSelection(value, choices, choices.indexOf(value).toFloat(), temporary)
        }
    }
}

/** 先识别主方向，再跟随局部轨迹判断转向；模式切换期间不调整倍率。 */
internal class SpeedSelectionDrag {
    private enum class Axis { Along, Across }

    private var axis: Axis? = null
    private var anchorAlong = 0f
    private var probeAlong = 0f
    private var probeAcross = 0f
    private var crossStartAlong = 0f
    private var crossStartAcross = 0f
    private var direction = 0
    var repeatRevision = 0
        private set
    var repeatIntervalMillis: Long? = null
        private set

    fun repeat(selection: SpeedSelection): SpeedSelection = when {
        selection.locked || direction == 0 -> selection
        selection.fine -> selection.fineAt(selection.value + direction)
        else -> selection.coarseAt(selection.position + direction)
    }

    fun move(selection: SpeedSelection, along: Float, across: Float): SpeedSelection {
        val localAlong = along - probeAlong
        val localAcross = across - probeAcross
        val alongIntent = abs(localAlong) >= 16f && abs(localAlong) >= abs(localAcross) * 1.25f
        val crossIntent = abs(localAcross) >= 16f && abs(localAcross) >= abs(localAlong) * 1.25f

        if (axis != Axis.Across && crossIntent) {
            axis = Axis.Across
            crossStartAlong = probeAlong
            crossStartAcross = probeAcross
            pauseRepeat()
        } else if (axis == Axis.Across && abs(localAlong) >= SpeedSelectionIntentDp &&
            abs(localAlong) >= abs(localAcross) * 1.8f
        ) {
            reset(along, across)
            return selection
        }

        if (axis == Axis.Across) {
            val cross = across - crossStartAcross
            val clearCross = abs(cross) >= SpeedModeStepDp &&
                abs(cross) >= abs(along - crossStartAlong) * 1.25f
            val next = when {
                clearCross && selection.locked && cross < 0 -> selection.copy(locked = false)
                clearCross && !selection.locked && cross < 0 && !selection.fine -> selection.finer()
                clearCross && !selection.locked && cross > 0 && selection.fine -> selection.coarser()
                clearCross && !selection.locked && cross > 0 -> selection.copy(locked = true)
                else -> null
            }
            if (next != null) {
                reset(along, across)
                return next
            }
            if (abs(cross) < 8f) reset(along, across)
            else if (crossIntent) {
                probeAlong = along
                probeAcross = across
            }
            return selection
        }

        if (alongIntent) {
            axis = Axis.Along
            probeAlong = along
            probeAcross = across
        }
        if (axis != Axis.Along || selection.locked) return selection

        val distance = along - anchorAlong
        val nextDirection = when {
            distance >= SpeedSelectionIntentDp -> 1
            distance <= -SpeedSelectionIntentDp -> -1
            abs(distance) <= SpeedSelectionStopDp -> 0
            else -> direction
        }
        val changed = nextDirection != direction
        setDirection(nextDirection)
        val strength = ((abs(distance) - SpeedSelectionIntentDp) / 96f).coerceIn(0f, 1f)
        repeatIntervalMillis = when {
            direction == 0 -> null
            selection.fine -> ((160 - 144 * strength) / 2).roundToInt().toLong()
            else -> ((380 - 240 * strength) / .6f).roundToInt().toLong()
        }
        return if (changed && direction != 0) repeat(selection) else selection
    }

    private fun reset(along: Float, across: Float) {
        axis = null
        anchorAlong = along
        probeAlong = along
        probeAcross = across
        pauseRepeat()
    }

    private fun pauseRepeat() {
        setDirection(0)
        repeatIntervalMillis = null
    }

    private fun setDirection(next: Int) {
        if (direction != next) repeatRevision++
        direction = next
    }
}
