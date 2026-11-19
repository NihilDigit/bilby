package dev.bilby.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPrefsTest {

    @Test
    fun defaultsMatchSpecification() {
        val prefs = NavigationPrefs.Default
        assertEquals(NAVIGATION_PREFS_VERSION, prefs.version)
        assertEquals(StartupDestination.Feed, prefs.startupPage)
        assertEquals(
            listOf(RootTabId.Feed, RootTabId.Search, RootTabId.Library),
            prefs.primaryTabs,
        )
        assertEquals(
            listOf(
                RailUtilityId.Followings,
                RailUtilityId.Dynamics,
                RailUtilityId.Messages,
                RailUtilityId.Settings,
            ),
            prefs.railUtilities,
        )
    }

    @Test
    fun jsonRoundTripPreservesCustomValues() {
        val custom = NavigationPrefs(
            startupPage = StartupDestination.ToView,
            primaryTabs = listOf(RootTabId.Library, RootTabId.Feed),
            railUtilities = listOf(
                RailUtilityId.History,
                RailUtilityId.Offline,
                RailUtilityId.Settings,
                RailUtilityId.ToView,
            ),
        )
        val encoded = encodeNavigationPrefs(custom)
        val decoded = decodeNavigationPrefs(encoded)
        assertEquals(custom.sanitize(), decoded)
    }

    @Test
    fun decodeHandlesUnknownKeysAndInvalidPayloads() {
        val withUnknown = """
            {
                "version": 99,
                "startupPage": "History",
                "primaryTabs": ["Search", "Feed"],
                "railUtilities": ["Messages", "Settings"],
                "futureFlag": true,
                "extraNested": {"a": 1}
            }
        """.trimIndent()
        val decoded = decodeNavigationPrefs(withUnknown)
        assertEquals(NAVIGATION_PREFS_VERSION, decoded.version)
        assertEquals(StartupDestination.History, decoded.startupPage)
        assertEquals(listOf(RootTabId.Search, RootTabId.Feed), decoded.primaryTabs)
        assertEquals(listOf(RailUtilityId.Messages, RailUtilityId.Settings), decoded.railUtilities)

        assertEquals(NavigationPrefs.Default, decodeNavigationPrefs(null))
        assertEquals(NavigationPrefs.Default, decodeNavigationPrefs(""))
        assertEquals(NavigationPrefs.Default, decodeNavigationPrefs("{broken json"))
    }

    @Test
    fun sanitizeDeduplicatesAndEnforcesNonEmptyPrimaryTabs() {
        val duplicated = NavigationPrefs(
            startupPage = StartupDestination.Search,
            primaryTabs = listOf(RootTabId.Search, RootTabId.Feed, RootTabId.Search, RootTabId.Feed),
            railUtilities = listOf(
                RailUtilityId.Followings,
                RailUtilityId.Settings,
                RailUtilityId.Followings,
                RailUtilityId.Settings,
            ),
        ).sanitize()

        assertEquals(listOf(RootTabId.Search, RootTabId.Feed), duplicated.primaryTabs)
        assertEquals(listOf(RailUtilityId.Followings, RailUtilityId.Settings), duplicated.railUtilities)

        val emptyTabs = NavigationPrefs(
            startupPage = StartupDestination.Library,
            primaryTabs = emptyList(),
        ).sanitize()
        assertEquals(listOf(RootTabId.Feed), emptyTabs.primaryTabs)
        assertEquals(StartupDestination.Feed, emptyTabs.startupPage)
    }

    @Test
    fun sanitizePreventsOrphanedRootStartupPageWhileAllowingLibrarySubDestinations() {
        val hiddenRootStartup = NavigationPrefs(
            startupPage = StartupDestination.Search,
            primaryTabs = listOf(RootTabId.Library, RootTabId.Feed),
        ).sanitize()
        assertEquals(StartupDestination.Library, hiddenRootStartup.startupPage)

        val subDestinationStartup = NavigationPrefs(
            startupPage = StartupDestination.Offline,
            primaryTabs = listOf(RootTabId.Feed),
        ).sanitize()
        assertEquals(StartupDestination.Offline, subDestinationStartup.startupPage)
    }

    @Test
    fun sanitizeAlwaysKeepsSettingsInRailUtilities() {
        val missingSettings = NavigationPrefs(
            railUtilities = listOf(RailUtilityId.Followings, RailUtilityId.Messages),
        ).sanitize()
        assertTrue(RailUtilityId.Settings in missingSettings.railUtilities)
        assertEquals(
            listOf(RailUtilityId.Followings, RailUtilityId.Messages, RailUtilityId.Settings),
            missingSettings.railUtilities,
        )

        val emptyUtilities = NavigationPrefs(railUtilities = emptyList()).sanitize()
        assertEquals(listOf(RailUtilityId.Settings), emptyUtilities.railUtilities)
    }

    @Test
    fun mutationHelpersRespectGuardrailsAndOrdering() {
        var prefs = NavigationPrefs.Default

        // Hiding two tabs leaves one; hiding the last one is ignored.
        prefs = prefs.setPrimaryTabVisible(RootTabId.Search, false)
        prefs = prefs.setPrimaryTabVisible(RootTabId.Library, false)
        assertEquals(listOf(RootTabId.Feed), prefs.primaryTabs)
        prefs = prefs.setPrimaryTabVisible(RootTabId.Feed, false)
        assertEquals(listOf(RootTabId.Feed), prefs.primaryTabs)

        // Selecting a hidden root tab as startupPage re-enables it cleanly.
        prefs = prefs.withStartupPage(StartupDestination.Search)
        assertEquals(StartupDestination.Search, prefs.startupPage)
        assertEquals(listOf(RootTabId.Feed, RootTabId.Search), prefs.primaryTabs)

        // Reordering primary tabs works.
        prefs = prefs.movePrimaryTab(RootTabId.Search, -1)
        assertEquals(listOf(RootTabId.Search, RootTabId.Feed), prefs.primaryTabs)

        // Settings cannot be hidden from rail utilities, and reordering works.
        prefs = prefs.setRailUtilityVisible(RailUtilityId.Settings, false)
        assertTrue(RailUtilityId.Settings in prefs.railUtilities)
        prefs = prefs.moveRailUtility(RailUtilityId.Settings, -1)
        assertEquals(
            listOf(
                RailUtilityId.Followings,
                RailUtilityId.Dynamics,
                RailUtilityId.Settings,
                RailUtilityId.Messages,
            ),
            prefs.railUtilities,
        )
    }
}
