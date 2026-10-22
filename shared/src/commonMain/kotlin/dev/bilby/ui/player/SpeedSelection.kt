package dev.bilby.ui.player

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

internal const val SpeedHorizontalStepDp = 76f
internal const val SpeedVerticalStepDp = 40f
internal const val SpeedDragStepDp = 22f
internal const val SpeedModeStepDp = 32f
internal const val SpeedSelectionIntentDp = 8f

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
        fun start(
            original: Float,
            temporary: Boolean,
            dockEnd: Boolean = false,
            initial: Float = original,
        ): SpeedSelection {
            val originalValue = speedHundredths(original)
            val value = speedHundredths(initial)
            val choices = (SpeedPresets + originalValue + value).distinct().sorted()
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

/** 先识别主方向，再按手指增量位移纯跟手换档，往返转向即时响应。 */
internal class SpeedSelectionDrag {
    private enum class Axis { Along, Across }

    private var axis: Axis? = null
    private var lastAlong = 0f
    private var probeAlong = 0f
    private var probeAcross = 0f
    private var crossStartAlong = 0f
    private var crossStartAcross = 0f
    private var engaged = false

    fun move(
        selection: SpeedSelection,
        along: Float,
        across: Float,
    ): SpeedSelection {
        val deltaAlong = along - lastAlong
        lastAlong = along
        val localAlong = along - probeAlong
        val localAcross = across - probeAcross
        val alongIntent = abs(localAlong) >= SpeedSelectionIntentDp && abs(localAlong) >= abs(localAcross) * 1.15f
        val crossIntent = abs(localAcross) >= 14f && abs(localAcross) >= abs(localAlong) * 1.25f

        if (axis != Axis.Across && crossIntent) {
            axis = Axis.Across
            crossStartAlong = probeAlong
            crossStartAcross = probeAcross
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

        if (!engaged) {
            engaged = true
            val effective = localAlong - SpeedSelectionIntentDp * sign(localAlong)
            return selection.coarseAt(selection.position + effective / SpeedDragStepDp)
        }
        return selection.coarseAt(selection.position + deltaAlong / SpeedDragStepDp)
    }

    private fun reset(along: Float, across: Float) {
        axis = null
        lastAlong = along
        probeAlong = along
        probeAcross = across
        engaged = false
    }
}
