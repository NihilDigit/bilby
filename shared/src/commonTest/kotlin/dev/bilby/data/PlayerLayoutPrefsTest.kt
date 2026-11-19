package dev.bilby.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLayoutPrefsTest {

    @Test
    fun defaultsEnforceSingleSettingsAndTouchCenterCluster() {
        val prefs = PlayerLayoutPrefs.Default

        // §4: Touch defaults have Play + Previous/Next in center cluster, not duplicated in bottom bar.
        for (formFactor in listOf(PlayerFormFactor.TouchEmbedded, PlayerFormFactor.TouchFullscreen)) {
            val layout = prefs.layoutFor(formFactor)
            assertTrue(layout.center.showCenterPlay)
            assertEquals(CenterSlotAction.Previous, layout.center.innerLeft.action)
            assertEquals(CenterSlotAction.Next, layout.center.innerRight.action)
            assertEquals(CenterSlotAction.None, layout.center.outerLeft.action)
            assertEquals(CenterSlotAction.None, layout.center.outerRight.action)
            assertEquals(CenterSlotScale.Medium, layout.center.innerLeft.scale)
            assertEquals(CenterSlotScale.Small, layout.center.outerLeft.scale)
            assertFalse(layout.hasInBottomBar(PlayerControlId.PlayPause))
            assertFalse(layout.hasInBottomBar(PlayerControlId.Previous))
            assertFalse(layout.hasInBottomBar(PlayerControlId.Next))
        }

        // §4: Pointer defaults hide center play and place Play/Prev/Next in bottom bar.
        for (formFactor in listOf(PlayerFormFactor.PointerEmbedded, PlayerFormFactor.PointerFullscreen)) {
            val layout = prefs.layoutFor(formFactor)
            assertFalse(layout.center.showCenterPlay)
            assertTrue(layout.hasInBottomBar(PlayerControlId.PlayPause))
            assertTrue(layout.hasInBottomBar(PlayerControlId.Previous))
            assertTrue(layout.hasInBottomBar(PlayerControlId.Next))
        }

        // §1c: Fullscreen has single Settings in top bar, NOT duplicated in bottom bar.
        for (formFactor in listOf(PlayerFormFactor.TouchFullscreen, PlayerFormFactor.PointerFullscreen)) {
            val layout = prefs.layoutFor(formFactor)
            assertTrue(layout.hasInTopBar(PlayerControlId.Settings))
            assertFalse(layout.hasInBottomBar(PlayerControlId.Settings))
        }

        // §1c: Embedded has Settings in bottom bar, NOT in top bar.
        for (formFactor in listOf(PlayerFormFactor.TouchEmbedded, PlayerFormFactor.PointerEmbedded)) {
            val layout = prefs.layoutFor(formFactor)
            assertFalse(layout.hasInTopBar(PlayerControlId.Settings))
            assertTrue(layout.hasInBottomBar(PlayerControlId.Settings))
        }
    }

    @Test
    fun serializationRoundTripPreservesPresetsAndCustomValues() {
        for (preset in PlayerLayoutPreset.entries) {
            val original = PlayerLayoutPrefs.preset(preset)
            val json = encodePlayerLayoutPrefs(original)
            val decoded = decodePlayerLayoutPrefs(json)
            assertEquals(original, decoded)
        }

        val custom = PlayerLayoutPrefs.Default.copy(
            seekButtonSeconds = 25,
            doubleTapSeekSeconds = 12,
            syncDoubleTapWithSeekButton = false,
        ).updateLayout(PlayerFormFactor.TouchFullscreen) { layout ->
            layout.withCenterSlot(
                position = CenterSlotPosition.OuterLeft,
                config = CenterSlotConfig(CenterSlotAction.SeekBackward(45), CenterSlotScale.ExtraSmall),
                isFullscreen = true,
            )
        }
        assertEquals(custom, decodePlayerLayoutPrefs(encodePlayerLayoutPrefs(custom)))
        assertEquals(PlayerLayoutPrefs.Default, decodePlayerLayoutPrefs("{not valid json"))
        assertEquals(PlayerLayoutPrefs.Default, decodePlayerLayoutPrefs(null))
    }

    @Test
    fun sanitizeDeduplicatesControlsAcrossCenterTopAndBottom() {
        val raw = PlayerFormFactorLayout(
            center = CenterClusterConfig(
                showCenterPlay = true,
                innerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
                innerRight = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
                outerLeft = CenterSlotConfig(CenterSlotAction.SeekBackward(10), CenterSlotScale.Small),
                outerRight = CenterSlotConfig(CenterSlotAction.SeekBackward(20), CenterSlotScale.Small),
            ),
            topBar = listOf(
                PlayerPanelItem.Control(PlayerControlId.Settings),
                PlayerPanelItem.Control(PlayerControlId.Settings),
                PlayerPanelItem.Control(PlayerControlId.PlayPause),
                PlayerPanelItem.Spacer,
            ),
            bottomBar = listOf(
                PlayerPanelItem.Control(PlayerControlId.PlayPause),
                PlayerPanelItem.Control(PlayerControlId.Previous),
                PlayerPanelItem.Control(PlayerControlId.SeekBackward),
                PlayerPanelItem.Control(PlayerControlId.Settings),
                PlayerPanelItem.Control(PlayerControlId.Fullscreen),
                PlayerPanelItem.Control(PlayerControlId.Fullscreen),
            ),
        )

        val clean = raw.sanitize(isFullscreen = true)
        assertEquals(CenterSlotAction.Previous, clean.center.innerLeft.action)
        assertEquals(CenterSlotAction.None, clean.center.innerRight.action)
        assertEquals(CenterSlotAction.SeekBackward(10), clean.center.outerLeft.action)
        assertEquals(CenterSlotAction.None, clean.center.outerRight.action)

        // Back is enforced at index 0 in fullscreen; Settings kept once in topBar, removed from bottomBar.
        assertEquals(PlayerPanelItem.Control(PlayerControlId.Back), clean.topBar.first())
        assertEquals(1, clean.topBar.count { it == PlayerPanelItem.Control(PlayerControlId.Settings) })
        assertFalse(clean.hasInBottomBar(PlayerControlId.Settings))
        assertFalse(clean.hasInBottomBar(PlayerControlId.PlayPause))
        assertFalse(clean.hasInBottomBar(PlayerControlId.Previous))
        assertFalse(clean.hasInBottomBar(PlayerControlId.SeekBackward))
        assertEquals(1, clean.bottomBar.count { it == PlayerPanelItem.Control(PlayerControlId.Fullscreen) })
    }

    @Test
    fun sanitizeRestoresPlayPauseAndFullscreenGuardrails() {
        val empty = PlayerFormFactorLayout(
            center = CenterClusterConfig(
                showCenterPlay = false,
                outerLeft = CenterSlotConfig(CenterSlotAction.None),
                innerLeft = CenterSlotConfig(CenterSlotAction.None),
                innerRight = CenterSlotConfig(CenterSlotAction.None),
                outerRight = CenterSlotConfig(CenterSlotAction.None),
            ),
            topBar = emptyList(),
            bottomBar = emptyList(),
        )

        val cleanEmbedded = empty.sanitize(isFullscreen = false)
        assertTrue(cleanEmbedded.hasControl(PlayerControlId.PlayPause))
        assertTrue(cleanEmbedded.hasControl(PlayerControlId.Fullscreen))
        assertFalse(cleanEmbedded.hasControl(PlayerControlId.Back))

        val cleanFullscreen = empty.sanitize(isFullscreen = true)
        assertTrue(cleanFullscreen.hasControl(PlayerControlId.Back))
        assertTrue(cleanFullscreen.hasControl(PlayerControlId.PlayPause))
        assertTrue(cleanFullscreen.hasControl(PlayerControlId.Fullscreen))
    }

    @Test
    fun sanitizeClampsSeekSecondsAndComputesEffectiveDoubleTap() {
        val prefs = PlayerLayoutPrefs.Default.copy(
            seekButtonSeconds = 999,
            doubleTapSeekSeconds = 0,
            syncDoubleTapWithSeekButton = true,
            touchEmbedded = PlayerLayoutPrefs.DefaultTouchEmbedded.copy(
                center = CenterClusterConfig(
                    showCenterPlay = true,
                    innerLeft = CenterSlotConfig(CenterSlotAction.SeekBackward(-10)),
                    innerRight = CenterSlotConfig(CenterSlotAction.SeekForward(500)),
                ),
            ),
        ).sanitize()

        assertEquals(MAX_SEEK_SECONDS, prefs.seekButtonSeconds)
        assertEquals(MIN_SEEK_SECONDS, prefs.doubleTapSeekSeconds)
        assertEquals(MAX_SEEK_SECONDS, prefs.effectiveDoubleTapSeekSeconds)
        assertEquals(CenterSlotAction.SeekBackward(MIN_SEEK_SECONDS), prefs.touchEmbedded.center.innerLeft.action)
        assertEquals(CenterSlotAction.SeekForward(MAX_SEEK_SECONDS), prefs.touchEmbedded.center.innerRight.action)

        val unsynced = prefs.copy(doubleTapSeekSeconds = 20, syncDoubleTapWithSeekButton = false)
        assertEquals(20, unsynced.effectiveDoubleTapSeekSeconds)
    }

    @Test
    fun editorMutationsMaintainSingleCanonicalEntryAndGuardrails() {
        val base = PlayerLayoutPrefs.DefaultTouchFullscreen
        val withSeek = base.withCenterSlot(
            position = CenterSlotPosition.InnerLeft,
            config = CenterSlotConfig(CenterSlotAction.SeekBackward(15), CenterSlotScale.Medium),
            isFullscreen = true,
        ).withSymmetricCenterSlot(CenterSlotPosition.InnerLeft, isFullscreen = true)

        assertEquals(CenterSlotAction.SeekBackward(15), withSeek.center.innerLeft.action)
        assertEquals(CenterSlotAction.SeekForward(15), withSeek.center.innerRight.action)
        assertEquals(CenterSlotScale.Medium, withSeek.center.innerRight.scale)

        // Moving Settings from topBar to bottomBar removes it from topBar.
        val movedSettings = withSeek.addToBar(
            bar = PlayerBarSlot.Bottom,
            item = PlayerPanelItem.Control(PlayerControlId.Settings),
            isFullscreen = true,
        )
        assertFalse(movedSettings.hasInTopBar(PlayerControlId.Settings))
        assertTrue(movedSettings.hasInBottomBar(PlayerControlId.Settings))

        // Fullscreen and Back cannot be removed.
        val fullscreenIdx = movedSettings.bottomBar.indexOf(PlayerPanelItem.Control(PlayerControlId.Fullscreen))
        assertFalse(movedSettings.canRemoveBarItem(PlayerBarSlot.Bottom, fullscreenIdx, isFullscreen = true))
        assertFalse(movedSettings.canRemoveBarItem(PlayerBarSlot.Top, 0, isFullscreen = true))
    }
}
