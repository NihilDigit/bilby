package dev.bilby.ui.player

import org.junit.Assert.*
import org.junit.Test

class SpeedSelectionTest {
    @Test
    fun `presets use half-step granularity`() {
        assertEquals(listOf(50, 100, 150, 200, 250, 300), SpeedPresets)
    }

    @Test
    fun `longitudinal drift does not accumulate into a lock switch`() {
        var state = SpeedSelection.start(1f, temporary = false)
        val drag = SpeedSelectionDrag()
        for (i in 1..12) {
            state = drag.move(state, i * 20f, i * 8f)
            assertFalse(state.locked)
        }
        assertEquals(300, state.value)
        state = drag.move(state, 240f, 105f)
        assertFalse(state.locked)
    }

    @Test
    fun `curved cross gesture preserves speed through lock and undo`() {
        var actual = 1.5f
        val controller = PlaybackSpeedController({ actual }, { actual = it })
        controller.open()
        controller.beginDrag()
        controller.move(36f, 2f)
        assertEquals(200, controller.selection!!.value)
        controller.move(42f, 20f)
        assertFalse(controller.selection!!.locked)
        assertEquals(200, controller.selection!!.value)
        controller.move(52f, 44f)
        assertTrue(controller.selection!!.locked)
        assertEquals(200, controller.selection!!.value)
        controller.move(59f, 8f)
        assertFalse(controller.selection!!.locked)
        assertEquals(200, controller.selection!!.value)
        controller.finish()
    }

    @Test
    fun `first temporary hold jumps to default hold speed and restores base speed on release`() {
        var actual = 1.25f
        val writes = mutableListOf<Float>()
        val controller = PlaybackSpeedController(
            readSpeed = { actual },
            writeSpeed = { actual = it; writes += it },
            defaultHoldSpeed = 2.5f,
        )
        controller.open(temporary = true)
        assertEquals(250, controller.selection!!.value)
        assertEquals(listOf(50, 100, 150, 200, 250, 300), controller.selection!!.choices)
        assertEquals(2.5f, actual, 0f)
        assertEquals(listOf(2.5f), writes)
        controller.finish()
        assertEquals(1.25f, actual, 0f)
        assertFalse(controller.lockedByHold)
    }

    @Test
    fun `locked hold speed resumes from locked speed on next hold and unlocks back to base on plain release`() {
        var actual = 1f
        val controller = PlaybackSpeedController(
            readSpeed = { actual },
            writeSpeed = { actual = it },
            defaultHoldSpeed = 2.5f,
        )
        controller.open(temporary = true)
        assertEquals(2.5f, actual, 0f)
        controller.beginDrag()
        controller.move(-36f, 0f)
        assertEquals(200, controller.selection!!.value)
        controller.move(-36f, 36f)
        assertTrue(controller.selection!!.locked)
        controller.finish()
        assertEquals(2f, actual, 0f)
        assertTrue(controller.lockedByHold)
        assertEquals(200, controller.lockedHint)

        controller.open(temporary = true)
        assertEquals(200, controller.selection!!.value)
        assertEquals(2f, actual, 0f)
        controller.step(2)
        assertEquals(300, controller.selection!!.value)
        controller.lock()
        controller.finish()
        assertEquals(3f, actual, 0f)
        assertTrue(controller.lockedByHold)

        controller.open(temporary = true)
        assertEquals(300, controller.selection!!.value)
        controller.finish()
        assertEquals(1f, actual, 0f)
        assertFalse(controller.lockedByHold)
    }

    @Test
    fun `panel speed change clears hold lock and updates base speed`() {
        var actual = 1f
        val controller = PlaybackSpeedController({ actual }, { actual = it }, defaultHoldSpeed = 2.5f)
        controller.open(temporary = true)
        controller.lock()
        controller.finish()
        assertTrue(controller.lockedByHold)
        assertEquals(2.5f, actual, 0f)

        controller.applySpeed(1.25f)
        assertFalse(controller.lockedByHold)
        assertEquals(1.25f, actual, 0f)

        controller.open(temporary = true)
        assertEquals(2.5f, actual, 0f)
        controller.finish()
        assertEquals(1.25f, actual, 0f)
    }

    @Test
    fun `sliding to min and reversing right responds immediately without lag`() {
        var state = SpeedSelection.start(2.5f, temporary = true)
        val drag = SpeedSelectionDrag()
        state = drag.move(state, -160f, 0f)
        assertEquals(50, state.value)
        state = drag.move(state, -160f + SpeedDragStepDp * 2f, 0f)
        assertEquals(150, state.value)
    }

    @Test
    fun `coarse drag intent takes one step and continuous drag follows finger`() {
        val state = SpeedSelection.start(1.5f, temporary = false)
        val drag = SpeedSelectionDrag()
        val firstStep = SpeedSelectionIntentDp + SpeedDragStepDp
        val forward = drag.move(state, firstStep, 0f)
        assertEquals(200, forward.value)
        val further = drag.move(forward, firstStep + SpeedDragStepDp, 0f)
        assertEquals(250, further.value)
        assertEquals(200, drag.move(further, firstStep, 0f).value)
    }

    @Test
    fun `returning from lock allows stepping and locking again`() {
        var actual = 1f
        val controller = PlaybackSpeedController({ actual }, { actual = it }, defaultHoldSpeed = 2f)
        controller.open(temporary = true)
        assertEquals(200, controller.selection!!.value)
        controller.beginDrag()
        controller.move(0f, 36f)
        assertTrue(controller.selection!!.locked)
        controller.move(0f, 0f)
        assertFalse(controller.selection!!.locked)
        controller.move(36f, 0f)
        assertEquals(250, controller.selection!!.value)
        controller.move(36f, 36f)
        assertTrue(controller.selection!!.locked)
        controller.finish()
        assertEquals(2.5f, actual, 0f)
        assertEquals(250, controller.lockedHint)
    }

    @Test
    fun `releasing after undoing lock restores the pre-hold speed`() {
        var actual = 1.37f
        val controller = PlaybackSpeedController({ actual }, { actual = it }, defaultHoldSpeed = 2f)
        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(36f, 0f)
        controller.move(36f, 36f)
        assertEquals(2.5f, actual, 0f)
        controller.move(36f, 0f)
        assertFalse(controller.selection!!.locked)
        controller.finish()
        assertEquals(1.37f, actual, 0f)
        assertNull(controller.lockedHint)
    }

    @Test
    fun `coarse selection ignores subthreshold jitter`() {
        val state = SpeedSelection.start(1.5f, temporary = false)
        val drag = SpeedSelectionDrag()
        assertEquals(state, drag.move(state, 9f, 0f))
        val next = drag.move(state, 36f, 0f)
        assertEquals(200, next.value)
    }

    @Test
    fun `adjustTo snaps to the nearest choice and lock commits it`() {
        var actual = 1f
        val writes = mutableListOf<Float>()
        val controller = PlaybackSpeedController({ actual }, { actual = it; writes += it })
        controller.open()
        controller.adjustTo(1.35f)
        assertEquals(150, controller.selection!!.value)
        controller.adjustTo(1.9f)
        assertEquals(200, controller.selection!!.value)
        controller.lock()
        assertEquals(2f, actual, 0f)
        controller.finish()
        assertEquals(listOf(1.5f, 2f), writes)
        assertEquals(200, controller.lockedHint)
    }

    @Test
    fun `normal speed entry keeps selected value without a temporary lock`() {
        var actual = 1.5f
        val controller = PlaybackSpeedController({ actual }, { actual = it })
        controller.open()
        controller.adjustTo(2f)
        controller.finish()
        assertEquals(2f, actual, 0f)
        assertNull(controller.lockedHint)
    }

    @Test
    fun `locked selection ignores further adjustment before release`() {
        var actual = 1f
        val controller = PlaybackSpeedController({ actual }, { actual = it })
        controller.open(temporary = true)
        controller.adjustTo(1.5f)
        controller.lock()
        controller.step(1)
        controller.adjustTo(2f)
        controller.finish()
        assertEquals(1.5f, actual, 0f)
    }

    @Test
    fun `cancelling after sliding into lock zone rolls back instead of committing`() {
        var actual = 1f
        val controller = PlaybackSpeedController({ actual }, { actual = it }, defaultHoldSpeed = 2f)
        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(36f, 0f)
        assertEquals(250, controller.selection!!.value)
        controller.move(36f, 36f)
        assertTrue(controller.selection!!.locked)
        controller.cancel()
        assertEquals(1f, actual, 0f)
        assertFalse(controller.lockedByHold)
        assertNull(controller.lockedHint)
        assertFalse(controller.dragging)

        controller.open(temporary = true)
        controller.lock()
        controller.finish()
        assertEquals(2f, actual, 0f)
        assertTrue(controller.lockedByHold)

        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(76f, 0f)
        assertEquals(300, controller.selection!!.value)
        controller.move(76f, 36f)
        assertTrue(controller.selection!!.locked)
        controller.cancel()
        assertEquals(2f, actual, 0f)
        assertTrue(controller.lockedByHold)
    }
}
