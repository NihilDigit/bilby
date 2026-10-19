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
    fun `fine release locks the exact speed without returning through coarse`() = runTest {
        var actual = 1.37f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(0f, -36f)
        controller.move(24f, -36f)
        assertEquals(138, controller.selection!!.value)
        assertFalse(controller.selection!!.locked)
        controller.finish()
        advanceTimeBy(1000)
        assertEquals(1.38f, actual, 0f)
        assertEquals(138, controller.lockedHint)
    }

    @Test
    fun `cancelling fine adjustment restores original even after preview was applied`() = runTest {
        var actual = 1.37f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(0f, -36f)
        controller.move(24f, -36f)
        advanceTimeBy(50)
        assertEquals(1.38f, actual, 0f)
        controller.cancel()
        advanceTimeBy(1000)
        assertEquals(1.37f, actual, 0f)
        assertNull(controller.lockedHint)
    }

    @Test
    fun `returning from fine to coarse requires an explicit lock to retain the value`() = runTest {
        var actual = 1.37f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(0f, -36f)
        controller.move(24f, -36f)
        controller.move(24f, 0f)
        assertEquals(138, controller.selection!!.value)
        assertTrue(138 in controller.selection!!.choices)
        controller.finish()
        assertEquals(1.37f, actual, 0f)
        assertNull(controller.lockedHint)
    }

    @Test
    fun `repeat timing doubles fine speed and reduces coarse speed to sixty percent`() {
        for ((distance, coarseMillis, fineMillis) in listOf(Triple(24f, 633L, 80L), Triple(120f, 233L, 8L))) {
            val coarse = SpeedSelectionDrag()
            coarse.move(SpeedSelection.start(1f, temporary = false), distance, 0f)
            assertEquals(coarseMillis, coarse.repeatIntervalMillis)
            val fine = SpeedSelectionDrag()
            fine.move(SpeedSelection.start(1f, temporary = false).finer(), distance, 0f)
            assertEquals(fineMillis, fine.repeatIntervalMillis)
        }
    }

    @Test
    fun `longitudinal drift does not accumulate into a mode switch`() {
        var state = SpeedSelection.start(1.5f, temporary = false)
        val drag = SpeedSelectionDrag()
        for (i in 1..12) {
            state = drag.move(state, i * 20f, i * 8f)
            assertFalse(state.fine)
            assertFalse(state.locked)
        }
        assertEquals(200, state.value)
        assertNotNull(drag.repeatIntervalMillis)
        state = drag.move(state, 240f, 105f)
        assertFalse(state.locked)
        assertNotNull(drag.repeatIntervalMillis)
    }

    @Test
    fun `curved cross gesture pauses selection and preserves speed through lock and undo`() = runTest {
        var actual = 1.5f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open()
        controller.beginDrag()
        controller.move(120f, 30f)
        assertEquals(200, controller.selection!!.value)
        controller.move(126f, 48f)
        advanceTimeBy(1000)
        assertFalse(controller.selection!!.locked)
        assertEquals(200, controller.selection!!.value)
        controller.move(138f, 74f)
        assertTrue(controller.selection!!.locked)
        assertEquals(200, controller.selection!!.value)
        controller.move(145f, 37f)
        assertFalse(controller.selection!!.locked)
        assertEquals(200, controller.selection!!.value)
        advanceTimeBy(1000)
        assertEquals(200, controller.selection!!.value)
        controller.finish()
    }

    @Test
    fun `diagonal mode switches freeze fine repeat and insert the exact current value`() = runTest {
        var actual = 1.37f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open()
        controller.beginDrag()
        controller.move(12f, -40f)
        assertTrue(controller.selection!!.fine)
        controller.move(36f, -32f)
        assertEquals(138, controller.selection!!.value)
        advanceTimeBy(500)
        runCurrent()
        val value = controller.selection!!.value
        controller.move(42f, -14f)
        advanceTimeBy(1000)
        assertEquals(value, controller.selection!!.value)
        controller.move(54f, 12f)
        assertFalse(controller.selection!!.fine)
        assertTrue(value in controller.selection!!.choices)
        assertEquals(value, controller.selection!!.value)
        advanceTimeBy(1000)
        assertEquals(value, controller.selection!!.value)
        controller.finish()
    }

    @Test
    fun `fine hold needs intent and can traverse both endpoints without more movement`() = runTest {
        var actual = .5f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open()
        controller.beginDrag()
        controller.move(0f, -36f)
        advanceTimeBy(2000)
        assertEquals(50, controller.selection!!.value)
        controller.move(120f, -20f)
        assertEquals(51, controller.selection!!.value)
        advanceTimeBy(2100)
        runCurrent()
        assertEquals(300, controller.selection!!.value)
        controller.move(0f, -20f)
        advanceTimeBy(1000)
        assertEquals(300, controller.selection!!.value)
        controller.move(-120f, -36f)
        advanceTimeBy(2100)
        runCurrent()
        assertEquals(50, controller.selection!!.value)
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
    fun `coarse drag intent takes one step in either direction`() {
        val state = SpeedSelection.start(1.5f, temporary = false)
        val drag = SpeedSelectionDrag()
        val forward = drag.move(state, SpeedBlockStepDp, 0f)
        assertEquals(200, forward.value)
        assertEquals(150, drag.move(forward, -SpeedBlockStepDp, 0f).value)
    }

    @Test
    fun `returning from lock allows fine adjustment and locking again`() = runTest {
        var actual = 1.5f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(0f, 36f)
        assertTrue(controller.selection!!.locked)
        controller.move(0f, 0f)
        assertFalse(controller.selection!!.locked)
        assertFalse(controller.selection!!.fine)
        controller.move(0f, -36f)
        assertTrue(controller.selection!!.fine)
        controller.move(78f, -36f)
        assertEquals(151, controller.selection!!.value)
        controller.move(78f, 0f)
        controller.move(78f, 36f)
        assertTrue(controller.selection!!.locked)
        controller.finish()
        assertEquals(1.51f, actual, 0f)
        assertEquals(151, controller.lockedHint)
    }

    @Test
    fun `releasing after undoing lock restores the pre-hold speed`() = runTest {
        var actual = 1.37f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(SpeedBlockStepDp, 0f)
        controller.move(SpeedBlockStepDp, 36f)
        assertEquals(1.5f, actual, 0f)
        controller.move(SpeedBlockStepDp, 0f)
        assertFalse(controller.selection!!.locked)
        controller.finish()
        advanceTimeBy(100)
        assertEquals(1.37f, actual, 0f)
        assertNull(controller.lockedHint)
    }

    @Test
    fun `down from fine returns to coarse before another down locks`() {
        var state = SpeedSelection.start(1.5f, temporary = true)
        val drag = SpeedSelectionDrag()
        state = drag.move(state, 0f, -36f)
        assertTrue(state.fine)
        state = drag.move(state, 78f, -36f)
        assertEquals(151, state.value)
        state = drag.move(state, 78f, 0f)
        assertFalse(state.fine)
        assertFalse(state.locked)
        assertEquals(151, state.choices[state.position.toInt()])
        state = drag.move(state, 78f, 36f)
        assertTrue(state.locked)
        assertEquals(151, state.value)
    }

    @Test
    fun `custom values remain selected and are not duplicated on repeated switches`() {
        var state = SpeedSelection.start(1.37f, temporary = false)
        repeat(5) { state = state.finer().fineAt(137).coarser() }
        assertEquals(1, state.choices.count { it == 137 })
        assertEquals(137, state.choices[state.position.toInt()])
        assertEquals(state.choices.sorted(), state.choices)
    }

    @Test
    fun `coarse drag restarts from the inserted custom block`() {
        var state = SpeedSelection.start(1.5f, temporary = true)
        val drag = SpeedSelectionDrag()
        state = drag.move(state, 0f, -36f)
        state = drag.move(state, 78f, -36f)
        state = drag.move(state, 78f, 0f)
        assertEquals(151, state.value)
        state = drag.move(state, 78f + SpeedBlockStepDp, 0f)
        assertEquals(200, state.value)
    }

    @Test
    fun `fine repeat advances exact hundredths and clamps both endpoints`() {
        var state = SpeedSelection.start(1f, temporary = false).finer()
        val drag = SpeedSelectionDrag()
        state = drag.move(state, 24f, 0f)
        assertEquals(101, state.value)
        repeat(199) {
            state = drag.repeat(state)
            assertEquals(102 + it, state.value)
        }
        assertEquals(300, drag.repeat(state).value)
        state = drag.move(state, -24f, 0f)
        repeat(300) { state = drag.repeat(state) }
        assertEquals(50, state.value)
        assertEquals("1.01", speedNumber(101))
        assertEquals("1.37", speedNumber(137))
    }

    @Test
    fun `coarse selection snaps once on intent and ignores subthreshold jitter`() {
        val state = SpeedSelection.start(1.5f, temporary = false)
        val drag = SpeedSelectionDrag()
        assertEquals(state, drag.move(state, 23f, 0f))
        assertNull(drag.repeatIntervalMillis)
        val next = drag.move(state, 24f, 0f)
        assertEquals(200, next.value)
        assertEquals(next.position.toInt().toFloat(), next.position, 0f)
        assertEquals(next, drag.move(next, 28f, 0f))
        assertNotNull(drag.repeatIntervalMillis)
        assertEquals(next, drag.move(next, 12f, 0f))
        assertNull(drag.repeatIntervalMillis)
    }

    @Test
    fun `stationary hold needs movement intent and can traverse the full coarse range`() = runTest {
        var actual = .5f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open()
        controller.beginDrag()
        advanceTimeBy(2000)
        assertEquals(50, controller.selection!!.value)
        controller.move(23f, 0f)
        advanceTimeBy(2000)
        assertEquals(50, controller.selection!!.value)
        controller.move(24f, 0f)
        assertEquals(75, controller.selection!!.value)
        advanceTimeBy(4100)
        runCurrent()
        assertEquals(300, controller.selection!!.value)
        assertEquals(7, controller.coarseStepRevision)
        controller.move(-24f, 0f)
        assertEquals(250, controller.selection!!.value)
        advanceTimeBy(4100)
        runCurrent()
        assertEquals(50, controller.selection!!.value)
        controller.finish()
    }

    @Test
    fun `returning to neutral stops repeat and changing mode reanchors intent`() = runTest {
        var actual = 1f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open()
        controller.beginDrag()
        controller.move(24f, 0f)
        assertEquals(125, controller.selection!!.value)
        controller.move(0f, 0f)
        advanceTimeBy(1000)
        assertEquals(125, controller.selection!!.value)
        controller.move(24f, 0f)
        assertEquals(150, controller.selection!!.value)
        controller.move(24f, -36f)
        advanceTimeBy(1000)
        assertEquals(150, controller.selection!!.value)
        controller.move(24f, 0f)
        advanceTimeBy(1000)
        assertFalse(controller.selection!!.fine)
        assertEquals(150, controller.selection!!.value)
        controller.move(48f, 0f)
        assertEquals(200, controller.selection!!.value)
        controller.move(48f, 36f)
        advanceTimeBy(1000)
        assertEquals(200, controller.selection!!.value)
        controller.finish()
        advanceTimeBy(1000)
        assertEquals(2f, actual, 0f)
    }

    @Test
    fun `cancelling a running coarse repeat leaves no subsequent speed commands`() = runTest {
        var actual = 1f
        val writes = mutableListOf<Float>()
        val controller = PlaybackSpeedController(this, { actual }, { actual = it; writes += it })
        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(120f, 0f)
        advanceTimeBy(300)
        controller.cancel()
        val count = writes.size
        advanceTimeBy(2000)
        assertEquals(count, writes.size)
        assertEquals(1f, actual, 0f)
    }

    @Test
    fun `cancelling temporary preview restores exact original and cancels queued writes`() = runTest {
        var actual = 1.234f
        val writes = mutableListOf<Float>()
        val controller = PlaybackSpeedController(this, { actual }, { actual = it; writes += it })
        controller.open(temporary = true)
        assertEquals(1.234f, actual, 0f)
        assertTrue(writes.isEmpty())
        assertEquals(123, controller.selection!!.value)
        controller.adjustTo(1.37f)
        runCurrent()
        controller.cancel()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1.234f, actual, 0f)
        assertTrue(writes.isEmpty())
        assertFalse(controller.active)
    }

    @Test
    fun `fine updates coalesce and lock flushes the final value immediately`() = runTest {
        var actual = 1f
        val writes = mutableListOf<Float>()
        val controller = PlaybackSpeedController(this, { actual }, { actual = it; writes += it })
        controller.open()
        controller.adjustTo(1.35f)
        controller.adjustTo(1.36f)
        controller.adjustTo(1.37f)
        runCurrent()
        advanceTimeBy(40)
        runCurrent()
        assertEquals(listOf(1.37f), writes)
        controller.adjustTo(1.38f)
        controller.lock()
        assertEquals(1.38f, actual, 0f)
        controller.finish()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(1.37f, 1.38f), writes)
        assertEquals(138, controller.lockedHint)
    }

    @Test
    fun `normal speed entry keeps selected value without a temporary lock`() = runTest {
        var actual = 1.5f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open()
        controller.adjustTo(1.37f)
        controller.finish()
        assertEquals(1.37f, actual, 0f)
        assertNull(controller.lockedHint)
    }

    @Test
    fun `locked selection ignores further adjustment before release`() = runTest {
        var actual = 1f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open(temporary = true)
        controller.adjustTo(1.37f)
        controller.lock()
        controller.step(1)
        controller.adjustTo(2f)
        controller.finish()
        assertEquals(1.37f, actual, 0f)
    }

    @Test
    fun `dragging preserves an explicit lock even when the pointer is cancelled`() = runTest {
        var actual = 1f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(0f, -36f)
        controller.move(78f, -36f)
        controller.move(78f, 0f)
        assertFalse(controller.selection!!.locked)
        controller.move(78f, 36f)
        controller.cancel()
        assertEquals(1.01f, actual, 0f)
        assertFalse(controller.dragging)
    }
}
