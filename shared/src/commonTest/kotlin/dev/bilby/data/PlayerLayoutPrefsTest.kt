package dev.bilby.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLayoutPrefsTest {

    @Test
    fun defaultsEnforceTwoSurfaceLayoutsAndTouchCenterDeduplication() {
        val prefs = PlayerLayoutPrefs.Default
        assertTrue(prefs.showCenterControlsOnTouch)
        assertTrue(prefs.isCenterControlsActive(isTouch = true))
        assertFalse(prefs.isCenterControlsActive(isTouch = false))

        // Embedded and Fullscreen both have center cluster (Play + Previous/Next) and full bottom bar for pointer mode.
        for (mode in PlayerLayoutMode.entries) {
            val layout = prefs.layoutFor(mode)
            assertTrue(layout.center.showCenterPlay)
            assertEquals(CenterSlotAction.Previous, layout.center.innerLeft.action)
            assertEquals(CenterSlotAction.Next, layout.center.innerRight.action)
            assertEquals(CenterSlotAction.None, layout.center.outerLeft.action)
            assertEquals(CenterSlotAction.None, layout.center.outerRight.action)
            assertEquals(CenterSlotScale.Medium, layout.center.innerLeft.scale)
            assertEquals(CenterSlotScale.Small, layout.center.outerLeft.scale)
            assertTrue(layout.center.innerLeft.showBackground)
            assertTrue(layout.center.outerLeft.showBackground)

            // Bottom bar keeps PlayPause/Previous/Next for pointer/center-off mode.
            assertTrue(layout.hasInBottomBar(PlayerControlId.PlayPause))
            assertTrue(layout.hasInBottomBar(PlayerControlId.Previous))
            assertTrue(layout.hasInBottomBar(PlayerControlId.Next))

            // On touch with center controls active, PlayPause/Previous/Next are deduplicated from bars.
            val touchCenterActive = prefs.isCenterControlsActive(isTouch = true)
            assertTrue(layout.isHiddenByCenter(PlayerControlId.PlayPause, touchCenterActive))
            assertTrue(layout.isHiddenByCenter(PlayerControlId.Previous, touchCenterActive))
            assertTrue(layout.isHiddenByCenter(PlayerControlId.Next, touchCenterActive))
            assertFalse(layout.isHiddenByCenter(PlayerControlId.Settings, touchCenterActive))

            // On pointer (or when showCenterControlsOnTouch = false), bars show all controls.
            val pointerCenterActive = prefs.isCenterControlsActive(isTouch = false)
            assertFalse(layout.isHiddenByCenter(PlayerControlId.PlayPause, pointerCenterActive))
            assertFalse(layout.isHiddenByCenter(PlayerControlId.Previous, pointerCenterActive))
            assertFalse(layout.isHiddenByCenter(PlayerControlId.Next, pointerCenterActive))
        }

        // Fullscreen has single Settings in top bar, NOT duplicated in bottom bar; Embedded has Settings in bottom bar.
        assertTrue(prefs.fullscreen.hasInTopBar(PlayerControlId.Settings))
        assertFalse(prefs.fullscreen.hasInBottomBar(PlayerControlId.Settings))
        assertFalse(prefs.embedded.hasInTopBar(PlayerControlId.Settings))
        assertTrue(prefs.embedded.hasInBottomBar(PlayerControlId.Settings))

        // Large embedded (tablet/desktop two-pane) maps to Fullscreen spacious layout.
        assertEquals(prefs.fullscreen, prefs.layoutFor(isLargeOrFullscreen = true))
        assertEquals(prefs.embedded, prefs.layoutFor(isLargeOrFullscreen = false))
    }

    @Test
    fun serializationRoundTripPreservesPresetsShowBackgroundAndCustomValues() {
        for (preset in PlayerLayoutPreset.entries) {
            val original = PlayerLayoutPrefs.preset(preset)
            val json = encodePlayerLayoutPrefs(original)
            val decoded = decodePlayerLayoutPrefs(json)
            assertEquals(original, decoded)
        }

        val statusIdx = PlayerLayoutPrefs.Default.fullscreen.topBar.indexOfFirst {
            it is PlayerPanelItem.Control && it.id == PlayerControlId.SystemStatus
        }
        assertTrue(statusIdx >= 0)

        val custom = PlayerLayoutPrefs.Default.copy(
            showCenterControlsOnTouch = false,
            seekButtonSeconds = 25,
            doubleTapSeekSeconds = 12,
            syncDoubleTapWithSeekButton = false,
        ).updateLayout(PlayerLayoutMode.Fullscreen) { layout ->
            layout.withCenterSlot(
                position = CenterSlotPosition.OuterLeft,
                config = CenterSlotConfig(
                    action = CenterSlotAction.SeekBackward(45),
                    scale = CenterSlotScale.ExtraSmall,
                    showBackground = false,
                ),
                isFullscreen = true,
            ).withBarItemBackground(
                bar = PlayerBarSlot.Top,
                index = statusIdx,
                showBackground = false,
                isFullscreen = true,
            )
        }.updateListen { listen ->
            listen.withSlot(
                CenterSlotPosition.OuterLeft,
                CenterSlotConfig(CenterSlotAction.SeekBackward(20), CenterSlotScale.Small, showBackground = false),
            ).withSymmetricSlot(CenterSlotPosition.OuterLeft)
        }

        val decoded = decodePlayerLayoutPrefs(encodePlayerLayoutPrefs(custom))
        assertEquals(custom, decoded)
        assertFalse(decoded.showCenterControlsOnTouch)
        assertFalse(decoded.fullscreen.center.outerLeft.showBackground)
        val decodedStatus = decoded.fullscreen.topBar[statusIdx] as PlayerPanelItem.Control
        assertFalse(decodedStatus.showBackground)
        assertFalse(decoded.listen.outerLeft.showBackground)
        assertFalse(decoded.listen.outerRight.showBackground)
        assertEquals(CenterSlotAction.SeekForward(20), decoded.listen.outerRight.action)

        assertEquals(PlayerLayoutPrefs.Default, decodePlayerLayoutPrefs("{not valid json"))
        assertEquals(PlayerLayoutPrefs.Default, decodePlayerLayoutPrefs(null))
    }

    @Test
    fun sanitizeDeduplicatesCenterSlotsAndBarsIndependently() {
        val raw = PlayerFormFactorLayout(
            center = CenterClusterConfig(
                showCenterPlay = true,
                innerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
                innerRight = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
                outerLeft = CenterSlotConfig(CenterSlotAction.SeekBackward(10), CenterSlotScale.Small),
                outerRight = CenterSlotConfig(CenterSlotAction.SeekBackward(20), CenterSlotScale.Small),
            ),
            topBar = listOf(
                PlayerPanelItem.Control(PlayerControlId.Back, showBackground = false),
                PlayerPanelItem.Control(PlayerControlId.Settings, showBackground = false),
                PlayerPanelItem.Control(PlayerControlId.Settings, showBackground = true),
                PlayerPanelItem.Spacer,
            ),
            bottomBar = listOf(
                PlayerPanelItem.Control(PlayerControlId.PlayPause),
                PlayerPanelItem.Control(PlayerControlId.Previous),
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

        // Back is enforced at index 0 in fullscreen and preserves showBackground; Settings kept once in topBar.
        val firstTop = clean.topBar.first() as PlayerPanelItem.Control
        assertEquals(PlayerControlId.Back, firstTop.id)
        assertFalse(firstTop.showBackground)
        assertEquals(1, clean.topBar.count { it is PlayerPanelItem.Control && it.id == PlayerControlId.Settings })
        assertFalse(clean.hasInBottomBar(PlayerControlId.Settings))

        // Bottom bar keeps PlayPause and Previous (runtime deduplicates when touch center cluster is active).
        assertTrue(clean.hasInBottomBar(PlayerControlId.PlayPause))
        assertTrue(clean.hasInBottomBar(PlayerControlId.Previous))
        assertEquals(1, clean.bottomBar.count { it is PlayerPanelItem.Control && it.id == PlayerControlId.Fullscreen })
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
        assertTrue(cleanEmbedded.hasInBottomBar(PlayerControlId.PlayPause))
        assertTrue(cleanEmbedded.hasInBottomBar(PlayerControlId.Fullscreen))
        assertFalse(cleanEmbedded.hasControl(PlayerControlId.Back))

        val cleanFullscreen = empty.sanitize(isFullscreen = true)
        assertTrue(cleanFullscreen.hasInTopBar(PlayerControlId.Back))
        assertTrue(cleanFullscreen.hasInBottomBar(PlayerControlId.PlayPause))
        assertTrue(cleanFullscreen.hasInBottomBar(PlayerControlId.Fullscreen))
    }

    @Test
    fun sanitizeClampsSeekSecondsAndComputesEffectiveDoubleTap() {
        val prefs = PlayerLayoutPrefs.Default.copy(
            seekButtonSeconds = 999,
            doubleTapSeekSeconds = 0,
            syncDoubleTapWithSeekButton = true,
            embedded = PlayerLayoutPrefs.DefaultEmbedded.copy(
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
        assertEquals(CenterSlotAction.SeekBackward(MIN_SEEK_SECONDS), prefs.embedded.center.innerLeft.action)
        assertEquals(CenterSlotAction.SeekForward(MAX_SEEK_SECONDS), prefs.embedded.center.innerRight.action)

        val unsynced = prefs.copy(doubleTapSeekSeconds = 20, syncDoubleTapWithSeekButton = false)
        assertEquals(20, unsynced.effectiveDoubleTapSeekSeconds)
    }

    @Test
    fun editorMutationsMaintainGuardrailsAndSymmetricBackground() {
        val base = PlayerLayoutPrefs.DefaultFullscreen
        val withSeek = base.withCenterSlot(
            position = CenterSlotPosition.InnerLeft,
            config = CenterSlotConfig(CenterSlotAction.SeekBackward(15), CenterSlotScale.Medium, showBackground = false),
            isFullscreen = true,
        ).withSymmetricCenterSlot(CenterSlotPosition.InnerLeft, isFullscreen = true)

        assertEquals(CenterSlotAction.SeekBackward(15), withSeek.center.innerLeft.action)
        assertEquals(CenterSlotAction.SeekForward(15), withSeek.center.innerRight.action)
        assertEquals(CenterSlotScale.Medium, withSeek.center.innerRight.scale)
        assertFalse(withSeek.center.innerRight.showBackground)

        // Moving Settings from topBar to bottomBar removes it from topBar.
        val movedSettings = withSeek.addToBar(
            bar = PlayerBarSlot.Bottom,
            item = PlayerPanelItem.Control(PlayerControlId.Settings),
            isFullscreen = true,
        )
        assertFalse(movedSettings.hasInTopBar(PlayerControlId.Settings))
        assertTrue(movedSettings.hasInBottomBar(PlayerControlId.Settings))

        // Fullscreen, PlayPause, and Back (in fullscreen) cannot be removed.
        val fullscreenIdx = movedSettings.bottomBar.indexOfFirst {
            it is PlayerPanelItem.Control && it.id == PlayerControlId.Fullscreen
        }
        val playIdx = movedSettings.bottomBar.indexOfFirst {
            it is PlayerPanelItem.Control && it.id == PlayerControlId.PlayPause
        }
        assertFalse(movedSettings.canRemoveBarItem(PlayerBarSlot.Bottom, fullscreenIdx, isFullscreen = true))
        assertFalse(movedSettings.canRemoveBarItem(PlayerBarSlot.Bottom, playIdx, isFullscreen = true))
        assertFalse(movedSettings.canRemoveBarItem(PlayerBarSlot.Top, 0, isFullscreen = true))
    }

    @Test
    fun listenFourSlotDefaultsCoexistSeekAndEpisodeAndDeduplicate() {
        val defaults = PlayerLayoutPrefs.Default.listen
        assertEquals(ListenCoverStyle.VinylDisc, defaults.coverStyle)
        assertEquals(CenterSlotAction.None, defaults.outerLeft.action)
        assertEquals(CenterSlotAction.Previous, defaults.innerLeft.action)
        assertEquals(CenterSlotAction.Next, defaults.innerRight.action)
        assertEquals(CenterSlotAction.None, defaults.outerRight.action)
        assertEquals(CenterSlotScale.Small, defaults.outerLeft.scale)
        assertEquals(CenterSlotScale.Medium, defaults.innerLeft.scale)
        assertTrue(defaults.innerLeft.showBackground)
        assertEquals(ListenQuickChip.entries, defaults.visibleChips)

        // Podcast preset has both episode (outer, Small) and seek ±15s (inner, Medium) simultaneously.
        val podcastListen = PlayerLayoutPrefs.Podcast.listen
        assertEquals(CenterSlotAction.Previous, podcastListen.outerLeft.action)
        assertEquals(CenterSlotAction.SeekBackward(15), podcastListen.innerLeft.action)
        assertEquals(CenterSlotAction.SeekForward(15), podcastListen.innerRight.action)
        assertEquals(CenterSlotAction.Next, podcastListen.outerRight.action)
        assertEquals(CenterSlotScale.Small, podcastListen.outerLeft.scale)
        assertEquals(CenterSlotScale.Medium, podcastListen.innerLeft.scale)

        // Duplicate actions across Listen slots are deduplicated (inner wins over outer).
        val dirty = ListenLayoutConfig(
            coverStyle = ListenCoverStyle.StaticCover,
            outerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Small),
            innerLeft = CenterSlotConfig(CenterSlotAction.Previous, CenterSlotScale.Medium),
            innerRight = CenterSlotConfig(CenterSlotAction.SeekForward(10), CenterSlotScale.Medium),
            outerRight = CenterSlotConfig(CenterSlotAction.SeekForward(30), CenterSlotScale.Small),
            visibleChips = listOf(
                ListenQuickChip.Speed,
                ListenQuickChip.Quality,
                ListenQuickChip.Speed,
                ListenQuickChip.Subtitle,
                ListenQuickChip.Quality,
            ),
        )
        val clean = PlayerLayoutPrefs.Default.copy(listen = dirty).sanitize().listen
        assertEquals(ListenCoverStyle.StaticCover, clean.coverStyle)
        assertEquals(CenterSlotAction.Previous, clean.innerLeft.action)
        assertEquals(CenterSlotAction.None, clean.outerLeft.action)
        assertEquals(CenterSlotAction.SeekForward(10), clean.innerRight.action)
        assertEquals(CenterSlotAction.None, clean.outerRight.action)
        assertEquals(
            listOf(ListenQuickChip.Speed, ListenQuickChip.Quality, ListenQuickChip.Subtitle),
            clean.visibleChips,
        )

        val toggledOff = clean.toggleChip(ListenQuickChip.Quality, visible = false)
        assertFalse(toggledOff.hasChip(ListenQuickChip.Quality))
        val toggledOn = toggledOff.toggleChip(ListenQuickChip.SleepTimer, visible = true)
        assertTrue(toggledOn.hasChip(ListenQuickChip.SleepTimer))
    }

    @Test
    fun decodeLegacyJsonMigratesListenSideActionAndDefaultsShowBackground() {
        // 1. Legacy JSON without listen field and with old 4-form-factor keys.
        val legacyNoListen = """
            {
              "version": 1,
              "touchEmbedded": {},
              "seekButtonSeconds": 20,
              "doubleTapSeekSeconds": 10,
              "syncDoubleTapWithSeekButton": false
            }
        """.trimIndent()
        val decodedNoListen = decodePlayerLayoutPrefs(legacyNoListen)
        assertEquals(20, decodedNoListen.seekButtonSeconds)
        assertEquals(10, decodedNoListen.doubleTapSeekSeconds)
        assertFalse(decodedNoListen.syncDoubleTapWithSeekButton)
        assertTrue(decodedNoListen.showCenterControlsOnTouch)
        assertEquals(ListenLayoutConfig.Default, decodedNoListen.listen)

        // 2. Legacy JSON with listen.sideAction = "SeekDelta" migrates to innerLeft/innerRight seek slots.
        val legacyListenSeek = """
            {
              "version": 1,
              "seekButtonSeconds": 25,
              "listen": {
                "coverStyle": "StaticCover",
                "sideAction": "SeekDelta",
                "visibleChips": ["Speed", "SleepTimer"]
              }
            }
        """.trimIndent()
        val decodedSeek = decodePlayerLayoutPrefs(legacyListenSeek)
        assertEquals(ListenCoverStyle.StaticCover, decodedSeek.listen.coverStyle)
        assertNull(decodedSeek.listen.sideAction)
        assertEquals(CenterSlotAction.None, decodedSeek.listen.outerLeft.action)
        assertEquals(CenterSlotAction.SeekBackward(25), decodedSeek.listen.innerLeft.action)
        assertEquals(CenterSlotAction.SeekForward(25), decodedSeek.listen.innerRight.action)
        assertEquals(CenterSlotAction.None, decodedSeek.listen.outerRight.action)
        assertEquals(listOf(ListenQuickChip.Speed, ListenQuickChip.SleepTimer), decodedSeek.listen.visibleChips)

        // 3. Legacy JSON with listen.sideAction = "None" clears all 4 slots.
        val legacyListenNone = """
            {
              "version": 1,
              "listen": {
                "sideAction": "None"
              }
            }
        """.trimIndent()
        val decodedNone = decodePlayerLayoutPrefs(legacyListenNone)
        assertEquals(CenterSlotAction.None, decodedNone.listen.outerLeft.action)
        assertEquals(CenterSlotAction.None, decodedNone.listen.innerLeft.action)
        assertEquals(CenterSlotAction.None, decodedNone.listen.innerRight.action)
        assertEquals(CenterSlotAction.None, decodedNone.listen.outerRight.action)

        // 4. Legacy JSON without showBackground fields on CenterSlotConfig & PlayerPanelItem.Control defaults to true.
        val legacyNoBg = """
            {
              "version": 1,
              "fullscreen": {
                "center": {
                  "showCenterPlay": true,
                  "innerLeft": { "action": { "type": "previous" }, "scale": "Medium" },
                  "innerRight": { "action": { "type": "next" }, "scale": "Medium" }
                },
                "topBar": [
                  { "type": "control", "id": "Back" },
                  { "type": "control", "id": "SystemStatus" }
                ],
                "bottomBar": [
                  { "type": "control", "id": "PlayPause" },
                  { "type": "control", "id": "Fullscreen" }
                ]
              }
            }
        """.trimIndent()
        val decodedNoBg = decodePlayerLayoutPrefs(legacyNoBg)
        assertTrue(decodedNoBg.fullscreen.center.innerLeft.showBackground)
        val statusItem = decodedNoBg.fullscreen.topBar.filterIsInstance<PlayerPanelItem.Control>()
            .first { it.id == PlayerControlId.SystemStatus }
        assertTrue(statusItem.showBackground)
        assertEquals(PlayerGestureConfig.Default, decodedNoBg.gestures)
    }

    @Test
    fun gestureConfigDefaultsClampAndRoundTrip() {
        val defaults = PlayerLayoutPrefs.Default.gestures
        assertEquals(VerticalSwipeAction.Brightness, defaults.leftVerticalSwipe)
        assertEquals(VerticalSwipeAction.Volume, defaults.rightVerticalSwipe)
        assertEquals(DEFAULT_VERTICAL_ZONE_FRACTION, defaults.verticalZoneFraction, 1e-5f)
        assertEquals(SwipeSeekSensitivity.Standard, defaults.swipeSeekSensitivity)
        assertTrue(defaults.swipeSeekCancelZone)
        assertEquals(DEFAULT_DOUBLE_TAP_ZONE_FRACTION, defaults.doubleTapZoneFraction, 1e-5f)

        val outOfRange = PlayerLayoutPrefs.Default.updateGestures {
            it.copy(
                leftVerticalSwipe = VerticalSwipeAction.Episode,
                rightVerticalSwipe = VerticalSwipeAction.None,
                verticalZoneFraction = 0.99f,
                swipeSeekSensitivity = SwipeSeekSensitivity.High,
                swipeSeekCancelZone = false,
                doubleTapZoneFraction = 0.01f,
            )
        }
        assertEquals(VerticalSwipeAction.Episode, outOfRange.gestures.leftVerticalSwipe)
        assertEquals(VerticalSwipeAction.None, outOfRange.gestures.rightVerticalSwipe)
        assertEquals(MAX_VERTICAL_ZONE_FRACTION, outOfRange.gestures.verticalZoneFraction, 1e-5f)
        assertEquals(SwipeSeekSensitivity.High, outOfRange.gestures.swipeSeekSensitivity)
        assertFalse(outOfRange.gestures.swipeSeekCancelZone)
        assertEquals(MIN_DOUBLE_TAP_ZONE_FRACTION, outOfRange.gestures.doubleTapZoneFraction, 1e-5f)

        val lowAndNonFinite = PlayerGestureConfig(
            verticalZoneFraction = 0.05f,
            doubleTapZoneFraction = Float.NaN,
        ).sanitize()
        assertEquals(MIN_VERTICAL_ZONE_FRACTION, lowAndNonFinite.verticalZoneFraction, 1e-5f)
        assertEquals(DEFAULT_DOUBLE_TAP_ZONE_FRACTION, lowAndNonFinite.doubleTapZoneFraction, 1e-5f)

        val decoded = decodePlayerLayoutPrefs(encodePlayerLayoutPrefs(outOfRange))
        assertEquals(outOfRange.gestures, decoded.gestures)
    }

    @Test
    fun dragAndDropInsertAndMoveMaintainGuardrails() {
        val base = PlayerLayoutPrefs.DefaultFullscreen
        // Inserting at index 0 in fullscreen topBar keeps Back pinned at index 0.
        val inserted = base.insertIntoBar(
            bar = PlayerBarSlot.Top,
            index = 0,
            item = PlayerPanelItem.Control(PlayerControlId.Share),
            isFullscreen = true,
        )
        assertEquals(PlayerControlId.Back, (inserted.topBar[0] as PlayerPanelItem.Control).id)
        assertEquals(PlayerControlId.Share, (inserted.topBar[1] as PlayerPanelItem.Control).id)

        // Moving Share from topBar to bottomBar at index 1 removes it from topBar and places it at bottomBar[1].
        val movedCross = inserted.moveBarItemToBarAt(
            fromBar = PlayerBarSlot.Top,
            fromIndex = 1,
            toBar = PlayerBarSlot.Bottom,
            toIndex = 1,
            isFullscreen = true,
        )
        assertFalse(movedCross.hasInTopBar(PlayerControlId.Share))
        assertEquals(PlayerControlId.Share, (movedCross.bottomBar[1] as PlayerPanelItem.Control).id)

        // Same-bar reorder moves Share to index 0 in bottomBar.
        val reordered = movedCross.moveBarItemToBarAt(
            fromBar = PlayerBarSlot.Bottom,
            fromIndex = 1,
            toBar = PlayerBarSlot.Bottom,
            toIndex = 0,
            isFullscreen = true,
        )
        assertEquals(PlayerControlId.Share, (reordered.bottomBar[0] as PlayerPanelItem.Control).id)
    }
}
