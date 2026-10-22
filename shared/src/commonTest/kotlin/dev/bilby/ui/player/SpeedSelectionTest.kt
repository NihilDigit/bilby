package dev.bilby.ui.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedSelectionTest {
    @Test
    fun `opening with initialSpeed immediately applies the boost and restores original on release`() = runTest {
        var actual = 1.25f
        val writes = mutableListOf<Float>()
        val controller = PlaybackSpeedController(this, { actual }, { actual = it; writes += it })
        controller.open(temporary = true, initialSpeed = 2.5f)
        assertEquals(250, controller.selection!!.value)
        assertEquals(2.5f, actual, 0f)
        assertEquals(listOf(2.5f), writes)
        controller.finish()
        assertEquals(1.25f, actual, 0f)
        assertEquals(listOf(2.5f, 1.25f), writes)
    }

    @Test
    fun `longitudinal drift does not accumulate into a lock switch`() {
        var state = SpeedSelection.start(1.5f, temporary = false)
        val drag = SpeedSelectionDrag()
        for (i in 1..12) {
            state = drag.move(state, i * 10f, i * 4f)
            assertFalse(state.locked)
        }
        assertEquals(275, state.value)
        state = drag.move(state, 120f, 52f)
        assertFalse(state.locked)
    }

    @Test
    fun `curved cross gesture preserves speed through lock and undo`() = runTest {
        var actual = 1.5f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open()
        controller.beginDrag()
        controller.move(30f, 2f)
        assertEquals(175, controller.selection!!.value)
        controller.move(34f, 20f)
        advanceTimeBy(1000)
        assertFalse(controller.selection!!.locked)
        assertEquals(175, controller.selection!!.value)
        controller.move(40f, 44f)
        assertTrue(controller.selection!!.locked)
        assertEquals(175, controller.selection!!.value)
        controller.move(45f, 8f)
        assertFalse(controller.selection!!.locked)
        assertEquals(175, controller.selection!!.value)
        advanceTimeBy(1000)
        assertEquals(175, controller.selection!!.value)
        controller.finish()
    }

    @Test
    fun `opening selects the latest playback speed without changing it`() = runTest {
        var actual = 1f
        val writes = mutableListOf<Float>()
        val controller = PlaybackSpeedController(this, { actual }, { actual = it; writes += it })
        actual = 1.37f
        controller.open(temporary = true)
        assertEquals(137, controller.selection!!.value)
        assertEquals(137, controller.selection!!.choices[controller.selection!!.position.toInt()])
        assertEquals(1.37f, actual, 0f)
        assertTrue(writes.isEmpty())
        controller.finish()
    }

    @Test
    fun `coarse drag intent takes one step and continuous drag follows finger in both directions immediately`() {
        val state = SpeedSelection.start(1.5f, temporary = false)
        val drag = SpeedSelectionDrag()
        val firstStep = SpeedSelectionIntentDp + SpeedDragStepDp
        val forward = drag.move(state, firstStep, 0f)
        assertEquals(175, forward.value)
        val further = drag.move(forward, firstStep + SpeedDragStepDp, 0f)
        assertEquals(200, further.value)
        assertEquals(175, drag.move(further, firstStep, 0f).value)
    }

    @Test
    fun `sliding to min and reversing right responds immediately without lag`() {
        var state = SpeedSelection.start(1f, temporary = true, initial = 2.5f)
        val drag = SpeedSelectionDrag()
        // 往左滑到底 0.25x
        state = drag.move(state, -220f, 0f)
        assertEquals(25, state.value)
        // 从 -220dp 往右回拨两步(+44dp),无需回到原点 0f 即当场回到 0.75x
        state = drag.move(state, -220f + SpeedDragStepDp * 2f, 0f)
        assertEquals(75, state.value)
    }

    @Test
    fun `holding still at any offset keeps the current speed without auto-scrolling`() = runTest {
        var actual = 1f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open(temporary = true, initialSpeed = 2.5f)
        controller.beginDrag()
        controller.move(-(SpeedSelectionIntentDp + SpeedDragStepDp * 2f), 0f)
        assertEquals(200, controller.selection!!.value)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(200, controller.selection!!.value)
        controller.finish()
    }

    @Test
    fun `returning from lock allows stepping and locking again`() = runTest {
        var actual = 1.5f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(0f, 36f)
        assertTrue(controller.selection!!.locked)
        controller.move(0f, 0f)
        assertFalse(controller.selection!!.locked)
        controller.move(30f, 0f)
        assertEquals(175, controller.selection!!.value)
        controller.move(30f, 36f)
        assertTrue(controller.selection!!.locked)
        controller.finish()
        assertEquals(1.75f, actual, 0f)
        assertEquals(175, controller.lockedHint)
    }

    @Test
    fun `releasing after undoing lock restores the pre-hold speed`() = runTest {
        var actual = 1.37f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(30f, 0f)
        controller.move(30f, 36f)
        assertEquals(1.5f, actual, 0f)
        controller.move(30f, 0f)
        assertFalse(controller.selection!!.locked)
        controller.finish()
        advanceTimeBy(100)
        assertEquals(1.37f, actual, 0f)
        assertNull(controller.lockedHint)
    }

    @Test
    fun `coarse selection ignores subthreshold jitter`() {
        val state = SpeedSelection.start(1.5f, temporary = false)
        val drag = SpeedSelectionDrag()
        assertEquals(state, drag.move(state, 7f, 0f))
        val next = drag.move(state, 30f, 0f)
        assertEquals(175, next.value)
    }

    @Test
    fun `cancelling temporary preview restores exact original`() = runTest {
        var actual = 1.234f
        val writes = mutableListOf<Float>()
        val controller = PlaybackSpeedController(this, { actual }, { actual = it; writes += it })
        controller.open(temporary = true)
        assertEquals(1.234f, actual, 0f)
        assertTrue(writes.isEmpty())
        assertEquals(123, controller.selection!!.value)
        controller.adjustTo(1.5f)
        runCurrent()
        assertEquals(listOf(1.5f), writes)
        controller.cancel()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1.234f, actual, 0f)
        assertFalse(controller.active)
    }

    @Test
    fun `adjustTo snaps to the nearest choice and lock commits it`() = runTest {
        var actual = 1f
        val writes = mutableListOf<Float>()
        val controller = PlaybackSpeedController(this, { actual }, { actual = it; writes += it })
        controller.open()
        controller.adjustTo(1.35f)
        assertEquals(125, controller.selection!!.value)
        controller.adjustTo(1.9f)
        assertEquals(200, controller.selection!!.value)
        controller.lock()
        assertEquals(2f, actual, 0f)
        controller.finish()
        assertEquals(listOf(1.25f, 2f), writes)
        assertEquals(200, controller.lockedHint)
    }

    @Test
    fun `normal speed entry keeps selected value without a temporary lock`() = runTest {
        var actual = 1.5f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open()
        controller.adjustTo(2f)
        controller.finish()
        assertEquals(2f, actual, 0f)
        assertNull(controller.lockedHint)
    }

    @Test
    fun `locked selection ignores further adjustment before release`() = runTest {
        var actual = 1f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open(temporary = true)
        controller.adjustTo(1.5f)
        controller.lock()
        controller.step(1)
        controller.adjustTo(2f)
        controller.finish()
        assertEquals(1.5f, actual, 0f)
    }

    @Test
    fun `dragging preserves an explicit lock even when the pointer is cancelled`() = runTest {
        var actual = 1f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(30f, 0f)
        assertEquals(125, controller.selection!!.value)
        controller.move(30f, 36f)
        assertTrue(controller.selection!!.locked)
        controller.cancel()
        assertEquals(1.25f, actual, 0f)
        assertFalse(controller.dragging)
    }
}
