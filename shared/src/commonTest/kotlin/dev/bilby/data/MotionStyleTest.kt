package dev.bilby.data

import dev.bilby.ui.theme.platformSupportsPredictiveBack
import dev.bilby.ui.theme.platformUsesStandardMotion
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionStyleTest {

    @Test
    fun platformDefaultsResolveDirectlyToStandardOrExpressive() {
        assertEquals(MotionStyle.Standard, MotionStyle.defaultFor(usesStandardMotion = true))
        assertEquals(MotionStyle.Expressive, MotionStyle.defaultFor(usesStandardMotion = false))
    }

    @Test
    fun fromPrefsParsesSavedValuesAndFallsBackToPlatformDefault() {
        assertEquals(MotionStyle.Reduced, MotionStyle.fromPrefs("Reduced", usesStandardMotion = true))
        assertEquals(MotionStyle.Reduced, MotionStyle.fromPrefs("Reduced", usesStandardMotion = false))
        assertEquals(MotionStyle.Standard, MotionStyle.fromPrefs("Standard", usesStandardMotion = false))
        assertEquals(MotionStyle.Expressive, MotionStyle.fromPrefs("Expressive", usesStandardMotion = true))

        assertEquals(MotionStyle.Standard, MotionStyle.fromPrefs(null, usesStandardMotion = true))
        assertEquals(MotionStyle.Expressive, MotionStyle.fromPrefs(null, usesStandardMotion = false))
        assertEquals(MotionStyle.Standard, MotionStyle.fromPrefs("unknown", usesStandardMotion = true))
        assertEquals(MotionStyle.Expressive, MotionStyle.fromPrefs("unknown", usesStandardMotion = false))
    }

    @Test
    fun appearancePrefsDefaultAndPredictiveBackCapabilityMatchPlatform() {
        assertEquals(MotionStyle.defaultFor(platformUsesStandardMotion), AppearancePrefs().motionStyle)
        assertEquals(!platformUsesStandardMotion, platformSupportsPredictiveBack)
    }
}
