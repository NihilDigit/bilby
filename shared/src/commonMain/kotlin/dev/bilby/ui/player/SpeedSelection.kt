package dev.bilby.ui.player

import kotlin.math.abs
import kotlin.math.roundToInt

internal const val SpeedBlockStepDp = 112f
internal const val SpeedDragStepDp = 56f
internal const val SpeedModeStepDp = 32f
internal const val SpeedSelectionIntentDp = 12f
internal const val SpeedEdgeZoneDp = 36f
private const val SpeedEdgeRepeatIntervalMillis = 450L
private const val SpeedSelectionStopDp = 6f

internal val SpeedPresets = listOf(25, 50, 75, 100, 125, 150, 175, 200, 225, 250, 275, 300)

internal fun speedHundredths(speed: Float): Int = (speed * 100).roundToInt().coerceIn(25, 300)

internal fun speedNumber(value: Int): String = when {
    value % 100 == 0 -> (value / 100).toString()
    value % 10 == 0 -> "${value / 100}.${value % 100 / 10}"
    else -> "${value / 100}.${(value % 100).toString().padStart(2, '0')}"
}

internal data class SpeedSelection(
    val value: Int,
    val choices: List<Int>,
    val position: Float,
    val temporary: Boolean,
    val locked: Boolean = false,
    val dockEnd: Boolean = false,
) {
    val speed: Float get() = value / 100f

    fun coarseAt(position: Float): SpeedSelection {
        val bounded = position.coerceIn(0f, choices.lastIndex.toFloat())
        return copy(position = bounded, value = choices[bounded.roundToInt()])
    }

    fun snapped(): SpeedSelection {
        val index = choices.indexOf(value).takeIf { it >= 0 } ?: position.roundToInt().coerceIn(0, choices.lastIndex)
        return copy(position = index.toFloat(), value = choices[index])
    }

    fun nearestTo(target: Int): SpeedSelection {
        val nearest = choices.minByOrNull { abs(it - target) } ?: value
        return copy(position = choices.indexOf(nearest).toFloat(), value = nearest)
    }

    companion object {
        fun start(original: Float, temporary: Boolean, dockEnd: Boolean = false): SpeedSelection {
            val value = speedHundredths(original)
            val choices = (SpeedPresets + value).distinct().sorted()
            return SpeedSelection(
                value = value,
                choices = choices,
                position = choices.indexOf(value).toFloat(),
                temporary = temporary,
                dockEnd = dockEnd,
            )
        }
    }
}

/** 先识别主方向，再按手指位移连续跟手换档；仅当手指滑到屏幕边缘时才启动自动连切。 */
internal class SpeedSelectionDrag {
    private enum class Axis { Along, Across }

    private var axis: Axis? = null
    private var anchorAlong = 0f
    private var lastAlong = 0f
    private var probeAlong = 0f
    private var probeAcross = 0f
    private var crossStartAlong = 0f
    private var crossStartAcross = 0f
    private var engaged = false
    private var direction = 0
    var repeatRevision = 0
        private set
    var repeatIntervalMillis: Long? = null
        private set

    fun repeat(selection: SpeedSelection): SpeedSelection =
        if (selection.locked || direction == 0) selection
        else selection.coarseAt(selection.position.roundToInt() + direction.toFloat())

    fun move(
        selection: SpeedSelection,
        along: Float,
        across: Float,
        atStartEdge: Boolean = false,
        atEndEdge: Boolean = false,
    ): SpeedSelection {
        val deltaAlong = along - lastAlong
        lastAlong = along
        val localAlong = along - probeAlong
        val localAcross = across - probeAcross
        val alongIntent = abs(localAlong) >= 8f && abs(localAlong) >= abs(localAcross) * 1.15f
        val crossIntent = abs(localAcross) >= 14f && abs(localAcross) >= abs(localAlong) * 1.25f

        if (axis != Axis.Across && crossIntent) {
            axis = Axis.Across
            crossStartAlong = probeAlong
            crossStartAcross = probeAcross
            pauseRepeat()
        } else if (axis == Axis.Across && abs(localAlong) >= SpeedSelectionIntentDp &&
            abs(localAlong) >= abs(localAcross) * 1.8f
        ) {
            reset(along, across)
            return selection.snapped()
        }

        if (axis == Axis.Across) {
            val cross = across - crossStartAcross
            val clearCross = abs(cross) >= SpeedModeStepDp &&
                abs(cross) >= abs(along - crossStartAlong) * 1.25f
            val next = when {
                clearCross && selection.locked && cross < 0 -> selection.snapped().copy(locked = false)
                clearCross && !selection.locked && cross > 0 -> selection.snapped().copy(locked = true)
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
        if (nextDirection == 0) {
            engaged = false
            setDirection(0)
            repeatIntervalMillis = null
            return selection.snapped()
        }

        setDirection(nextDirection)
        val atEdgeInDirection = (nextDirection > 0 && atEndEdge) || (nextDirection < 0 && atStartEdge)
        repeatIntervalMillis = if (atEdgeInDirection) SpeedEdgeRepeatIntervalMillis else null

        if (!engaged) {
            engaged = true
            val effective = distance - SpeedSelectionIntentDp * nextDirection
            return selection.coarseAt(selection.position + effective / SpeedDragStepDp)
        }
        return selection.coarseAt(selection.position + deltaAlong / SpeedDragStepDp)
    }

    private fun reset(along: Float, across: Float) {
        axis = null
        anchorAlong = along
        lastAlong = along
        probeAlong = along
        probeAcross = across
        engaged = false
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
