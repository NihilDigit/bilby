package dev.bilby.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueEdgeResistanceTest {

    @Test
    fun `bumper stop ignores overscroll in the same frame that reached the boundary`() {
        val logic = QueueEdgeResistanceLogic(touchThresholdPx = 120f, pointerThresholdPx = 160f)

        val outcome = logic.onPostScroll(
            consumedY = -30f,
            availableY = -80f,
            isTouchLike = true,
            canLoadBefore = true,
            canLoadAfter = true,
        )

        assertFalse(outcome.consumed)
        assertNull(outcome.triggeredBefore)
        assertEquals(0f, logic.bottomRawPx, 0.001f)
    }

    @Test
    fun `touch pull builds resistance, unwinds on reverse drag, and triggers only on release past threshold`() {
        val logic = QueueEdgeResistanceLogic(touchThresholdPx = 120f, pointerThresholdPx = 160f)

        // Pull up at bottom edge by 70px (< 120px threshold)
        val step1 = logic.onPostScroll(0f, -70f, isTouchLike = true, canLoadBefore = false, canLoadAfter = true)
        assertTrue(step1.consumed)
        assertNull(step1.triggeredBefore)
        assertFalse(step1.crossedTouchThreshold)
        assertFalse(logic.isBottomArmed(isTouchLike = true))

        // Drag back down by 30px -> unwinds bottom pull to 40px
        val unwound = logic.onPreScroll(availableY = 30f, isTouchLike = true)
        assertEquals(30f, unwound, 0.001f)
        assertEquals(40f, logic.bottomRawPx, 0.001f)

        // Release below threshold -> does not trigger
        assertNull(logic.onTouchRelease(canLoadBefore = false, canLoadAfter = true))
        assertEquals(0f, logic.bottomRawPx, 0.001f)

        // Pull again past threshold (80 + 50 = 130 >= 120)
        val pullA = logic.onPostScroll(0f, -80f, isTouchLike = true, canLoadBefore = false, canLoadAfter = true)
        assertFalse(pullA.crossedTouchThreshold)
        val pullB = logic.onPostScroll(0f, -50f, isTouchLike = true, canLoadBefore = false, canLoadAfter = true)
        assertTrue(pullB.crossedTouchThreshold)
        assertTrue(logic.isBottomArmed(isTouchLike = true))
        // Further pull does not re-fire haptic threshold crossing
        val pullC = logic.onPostScroll(0f, -10f, isTouchLike = true, canLoadBefore = false, canLoadAfter = true)
        assertFalse(pullC.crossedTouchThreshold)

        // Release at/above threshold triggers loadAfter (before = false) and resets
        assertEquals(false, logic.onTouchRelease(canLoadBefore = false, canLoadAfter = true))
        assertEquals(0f, logic.bottomRawPx, 0.001f)
    }

    @Test
    fun `pointer wheel requires multiple notches past boundary and clears on opposite scroll`() {
        val logic = QueueEdgeResistanceLogic(touchThresholdPx = 120f, pointerThresholdPx = 160f)

        // 1st wheel notch down at bottom (64px / 160px = 40%) -> no trigger yet
        val tick1 = logic.onWheelBoundaryScroll(
            scrollDeltaY = 1f,
            wheelStepPx = 64f,
            atTop = false,
            atBottom = true,
            canLoadBefore = true,
            canLoadAfter = true,
        )
        assertTrue(tick1.consumed)
        assertNull(tick1.triggeredBefore)
        assertEquals(0.4f, logic.bottomProgress(isTouchLike = false), 0.001f)

        // Scrolling opposite direction clears accumulated resistance immediately
        val opposite = logic.onWheelBoundaryScroll(
            scrollDeltaY = -1f,
            wheelStepPx = 64f,
            atTop = false,
            atBottom = true,
            canLoadBefore = true,
            canLoadAfter = true,
        )
        assertFalse(opposite.consumed)
        assertEquals(0f, logic.bottomRawPx, 0.001f)

        // 3 deliberate notches past bottom (64 + 64 + 64 = 192 >= 160) -> triggers on 3rd notch
        assertNull(logic.onWheelBoundaryScroll(1f, 64f, atTop = false, atBottom = true, canLoadBefore = false, canLoadAfter = true).triggeredBefore)
        assertNull(logic.onWheelBoundaryScroll(1f, 64f, atTop = false, atBottom = true, canLoadBefore = false, canLoadAfter = true).triggeredBefore)
        val tick3 = logic.onWheelBoundaryScroll(1f, 64f, atTop = false, atBottom = true, canLoadBefore = false, canLoadAfter = true)
        assertEquals(false, tick3.triggeredBefore)
        assertEquals(0f, logic.bottomRawPx, 0.001f)
    }

    @Test
    fun `disabled edge does not consume scroll so parent sheet gestures remain unobstructed`() {
        val logic = QueueEdgeResistanceLogic(touchThresholdPx = 120f, pointerThresholdPx = 160f)

        val topDisabled = logic.onPostScroll(0f, 90f, isTouchLike = true, canLoadBefore = false, canLoadAfter = true)
        assertFalse(topDisabled.consumed)
        assertEquals(0f, logic.topRawPx, 0.001f)

        val wheelTopDisabled = logic.onWheelBoundaryScroll(
            scrollDeltaY = -1f,
            wheelStepPx = 64f,
            atTop = true,
            atBottom = false,
            canLoadBefore = false,
            canLoadAfter = true,
        )
        assertFalse(wheelTopDisabled.consumed)
        assertEquals(0f, logic.topRawPx, 0.001f)
    }

    @Test
    fun `top edge triggers loadBefore on touch release and wheel threshold`() {
        val logic = QueueEdgeResistanceLogic(touchThresholdPx = 100f, pointerThresholdPx = 128f)

        // Touch pull down past top edge
        logic.onPostScroll(0f, 110f, isTouchLike = true, canLoadBefore = true, canLoadAfter = false)
        assertEquals(true, logic.onTouchRelease(canLoadBefore = true, canLoadAfter = false))

        // Wheel scroll up 2 notches past top edge (64 * 2 = 128 >= 128)
        assertNull(logic.onWheelBoundaryScroll(-1f, 64f, atTop = true, atBottom = false, canLoadBefore = true, canLoadAfter = false).triggeredBefore)
        val tick2 = logic.onWheelBoundaryScroll(-1f, 64f, atTop = true, atBottom = false, canLoadBefore = true, canLoadAfter = false)
        assertEquals(true, tick2.triggeredBefore)
    }
}
