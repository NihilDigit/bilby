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
    fun `repeat activates only when finger reaches the edge in the drag direction`() {
        val start = SpeedSelection.start(1f, temporary = false)
        val midDrag = SpeedSelectionDrag()
        midDrag.move(start, 40f, 0f, atStartEdge = false, atEndEdge = false)
        assertNull(midDrag.repeatIntervalMillis)
        val wrongEdge = SpeedSelectionDrag()
        wrongEdge.move(start, 40f, 0f, atStartEdge = true, atEndEdge = false)
        assertNull(wrongEdge.repeatIntervalMillis)
        val rightEdge = SpeedSelectionDrag()
        rightEdge.move(start, 40f, 0f, atStartEdge = false, atEndEdge = true)
        assertEquals(450L, rightEdge.repeatIntervalMillis)
    }

    @Test
    fun `longitudinal drift does not accumulate into a lock switch`() {
        var state = SpeedSelection.start(1.5f, temporary = false)
        val drag = SpeedSelectionDrag()
        for (i in 1..12) {
            state = drag.move(state, i * 20f, i * 8f, atEndEdge = true)
            assertFalse(state.locked)
        }
        assertEquals(250, state.value)
        assertNotNull(drag.repeatIntervalMillis)
        state = drag.move(state, 240f, 105f, atEndEdge = true)
        assertFalse(state.locked)
        assertNotNull(drag.repeatIntervalMillis)
    }

    @Test
    fun `curved cross gesture pauses selection and preserves speed through lock and undo`() = runTest {
        var actual = 1.5f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open()
        controller.beginDrag()
        controller.move(48f, 2f)
        assertEquals(175, controller.selection!!.value)
        controller.move(54f, 20f)
        advanceTimeBy(1000)
        assertFalse(controller.selection!!.locked)
        assertEquals(175, controller.selection!!.value)
        controller.move(64f, 44f)
        assertTrue(controller.selection!!.locked)
        assertEquals(175, controller.selection!!.value)
        controller.move(71f, 8f)
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
    fun `coarse drag intent takes one step and continuous drag follows finger`() {
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
    fun `returning from lock allows stepping and locking again`() = runTest {
        var actual = 1.5f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(0f, 36f)
        assertTrue(controller.selection!!.locked)
        controller.move(0f, 0f)
        assertFalse(controller.selection!!.locked)
        controller.move(48f, 0f)
        assertEquals(175, controller.selection!!.value)
        controller.move(48f, 36f)
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
        controller.move(48f, 0f)
        controller.move(48f, 36f)
        assertEquals(1.5f, actual, 0f)
        controller.move(48f, 0f)
        assertFalse(controller.selection!!.locked)
        controller.finish()
        advanceTimeBy(100)
        assertEquals(1.37f, actual, 0f)
        assertNull(controller.lockedHint)
    }

    @Test
    fun `coarse selection snaps once on intent and ignores subthreshold jitter`() {
        val state = SpeedSelection.start(1.5f, temporary = false)
        val drag = SpeedSelectionDrag()
        assertEquals(state, drag.move(state, 11f, 0f))
        assertNull(drag.repeatIntervalMillis)
        val next = drag.move(state, 48f, 0f, atEndEdge = true)
        assertEquals(175, next.value)
        assertNotNull(drag.repeatIntervalMillis)
        assertEquals(next.snapped(), drag.move(next, 6f, 0f, atEndEdge = true))
        assertNull(drag.repeatIntervalMillis)
    }

    @Test
    fun `edge hold traverses the full coarse range while mid-screen hold stays put`() = runTest {
        var actual = .25f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open()
        controller.beginDrag()
        advanceTimeBy(2000)
        assertEquals(25, controller.selection!!.value)
        controller.move(48f, 0f, atEndEdge = false)
        assertEquals(50, controller.selection!!.value)
        advanceTimeBy(2000)
        assertEquals("mid-screen hold must not auto-repeat", 50, controller.selection!!.value)
        controller.move(48f, 0f, atEndEdge = true)
        advanceTimeBy(5200)
        runCurrent()
        assertEquals(300, controller.selection!!.value)
        assertEquals(11, controller.coarseStepRevision)
        controller.move(-48f, 0f, atStartEdge = true)
        advanceTimeBy(5500)
        runCurrent()
        assertEquals(25, controller.selection!!.value)
        controller.finish()
    }

    @Test
    fun `returning to neutral stops repeat and locking reanchors intent`() = runTest {
        var actual = 1f
        val controller = PlaybackSpeedController(this, { actual }, { actual = it })
        controller.open()
        controller.beginDrag()
        controller.move(48f, 0f, atEndEdge = true)
        assertEquals(125, controller.selection!!.value)
        controller.move(0f, 0f)
        advanceTimeBy(1000)
        assertEquals(125, controller.selection!!.value)
        controller.move(48f, 0f)
        assertEquals(150, controller.selection!!.value)
        controller.move(48f, 36f)
        assertTrue(controller.selection!!.locked)
        advanceTimeBy(1000)
        assertEquals(150, controller.selection!!.value)
        controller.move(48f, 0f)
        assertFalse(controller.selection!!.locked)
        advanceTimeBy(1000)
        assertEquals(150, controller.selection!!.value)
        controller.move(96f, 0f)
        assertEquals(175, controller.selection!!.value)
        controller.move(96f, 36f)
        advanceTimeBy(1000)
        assertEquals(175, controller.selection!!.value)
        controller.finish()
        advanceTimeBy(1000)
        assertEquals(1.75f, actual, 0f)
    }

    @Test
    fun `cancelling a running coarse repeat leaves no subsequent speed commands`() = runTest {
        var actual = 1f
        val writes = mutableListOf<Float>()
        val controller = PlaybackSpeedController(this, { actual }, { actual = it; writes += it })
        controller.open(temporary = true)
        controller.beginDrag()
        controller.move(120f, 0f, atEndEdge = true)
        advanceTimeBy(500)
        controller.cancel()
        val count = writes.size
        advanceTimeBy(2000)
        assertEquals(count, writes.size)
        assertEquals(1f, actual, 0f)
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
        controller.move(48f, 0f)
        assertEquals(125, controller.selection!!.value)
        controller.move(48f, 36f)
        assertTrue(controller.selection!!.locked)
        controller.cancel()
        assertEquals(1.25f, actual, 0f)
        assertFalse(controller.dragging)
    }
}
