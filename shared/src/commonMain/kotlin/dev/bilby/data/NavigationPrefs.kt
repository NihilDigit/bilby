package dev.bilby.data

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val NAVIGATION_PREFS_VERSION = 1

@Serializable
enum class StartupDestination {
    Feed,
    Search,
    Library,
    ToView,
    FavFolders,
    History,
    Offline,
    ;

    val rootTabId: RootTabId?
        get() = when (this) {
            Feed -> RootTabId.Feed
            Search -> RootTabId.Search
            Library -> RootTabId.Library
            ToView, FavFolders, History, Offline -> null
        }

    val isLibrarySubDestination: Boolean
        get() = rootTabId == null
}

@Serializable
enum class RootTabId {
    Feed,
    Search,
    Library,
    ;

    fun toStartupDestination(): StartupDestination = when (this) {
        Feed -> StartupDestination.Feed
        Search -> StartupDestination.Search
        Library -> StartupDestination.Library
    }
}

@Serializable
enum class RailUtilityId {
    Followings,
    Dynamics,
    Messages,
    Offline,
    History,
    ToView,
    Settings,
}

@Immutable
@Serializable
data class NavigationPrefs(
    val version: Int = NAVIGATION_PREFS_VERSION,
    val startupPage: StartupDestination = StartupDestination.Feed,
    val primaryTabs: List<RootTabId> = DefaultPrimaryTabs,
    val railUtilities: List<RailUtilityId> = DefaultRailUtilities,
) {
    /**
     * 强校验与修复护栏：
     * 1. 主导航去重且至少保留 1 项；
     * 2. 若默认启动页为某主导航标签且该标签已被隐藏，则回退至当前可见主导航首项；
     * 3. 桌面端侧栏快捷入口去重且始终保留 [RailUtilityId.Settings]，防止无法进入设置。
     */
    fun sanitize(): NavigationPrefs {
        val cleanTabs = primaryTabs.distinct().ifEmpty { listOf(RootTabId.Feed) }
        val startupTab = startupPage.rootTabId
        val cleanStartup = if (startupTab != null && startupTab !in cleanTabs) {
            cleanTabs.first().toStartupDestination()
        } else {
            startupPage
        }
        val dedupedUtilities = railUtilities.distinct()
        val cleanUtilities = if (RailUtilityId.Settings in dedupedUtilities) {
            dedupedUtilities
        } else {
            dedupedUtilities + RailUtilityId.Settings
        }
        return copy(
            version = NAVIGATION_PREFS_VERSION,
            startupPage = cleanStartup,
            primaryTabs = cleanTabs,
            railUtilities = cleanUtilities,
        )
    }

    fun withStartupPage(destination: StartupDestination): NavigationPrefs {
        val requiredTab = destination.rootTabId
        val nextTabs = if (requiredTab != null && requiredTab !in primaryTabs) {
            primaryTabs + requiredTab
        } else {
            primaryTabs
        }
        return copy(startupPage = destination, primaryTabs = nextTabs).sanitize()
    }

    fun setPrimaryTabVisible(tab: RootTabId, visible: Boolean): NavigationPrefs {
        val current = primaryTabs.distinct()
        val next = if (visible) {
            if (tab in current) current else current + tab
        } else {
            if (current.size <= 1) current else current.filterNot { it == tab }
        }
        return copy(primaryTabs = next).sanitize()
    }

    fun movePrimaryTab(tab: RootTabId, delta: Int): NavigationPrefs {
        val current = primaryTabs.distinct().toMutableList()
        val index = current.indexOf(tab)
        if (index < 0) return sanitize()
        val target = (index + delta).coerceIn(0, current.lastIndex)
        if (target == index) return sanitize()
        current.removeAt(index)
        current.add(target, tab)
        return copy(primaryTabs = current).sanitize()
    }

    fun setRailUtilityVisible(utility: RailUtilityId, visible: Boolean): NavigationPrefs {
        if (utility == RailUtilityId.Settings && !visible) return sanitize()
        val current = railUtilities.distinct()
        val next = if (visible) {
            if (utility in current) current else current + utility
        } else {
            current.filterNot { it == utility }
        }
        return copy(railUtilities = next).sanitize()
    }

    fun moveRailUtility(utility: RailUtilityId, delta: Int): NavigationPrefs {
        val current = railUtilities.distinct().toMutableList()
        val index = current.indexOf(utility)
        if (index < 0) return sanitize()
        val target = (index + delta).coerceIn(0, current.lastIndex)
        if (target == index) return sanitize()
        current.removeAt(index)
        current.add(target, utility)
        return copy(railUtilities = current).sanitize()
    }

    companion object {
        val DefaultPrimaryTabs = listOf(
            RootTabId.Feed,
            RootTabId.Search,
            RootTabId.Library,
        )

        val DefaultRailUtilities = listOf(
            RailUtilityId.Followings,
            RailUtilityId.Dynamics,
            RailUtilityId.Messages,
            RailUtilityId.Settings,
        )

        val Default = NavigationPrefs(
            version = NAVIGATION_PREFS_VERSION,
            startupPage = StartupDestination.Feed,
            primaryTabs = DefaultPrimaryTabs,
            railUtilities = DefaultRailUtilities,
        ).sanitize()
    }
}

private val NavigationPrefsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun encodeNavigationPrefs(prefs: NavigationPrefs): String =
    NavigationPrefsJson.encodeToString(NavigationPrefs.serializer(), prefs.sanitize())

fun decodeNavigationPrefs(raw: String?): NavigationPrefs {
    if (raw.isNullOrBlank()) return NavigationPrefs.Default
    return runCatching {
        NavigationPrefsJson.decodeFromString(NavigationPrefs.serializer(), raw).sanitize()
    }.getOrElse { NavigationPrefs.Default }
}
