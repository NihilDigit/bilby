package dev.bilby.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewSidebar
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Start
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import dev.bilby.data.FeedUpStripPosition
import dev.bilby.data.NavigationPrefs
import dev.bilby.data.RailUtilityId
import dev.bilby.data.RootTabId
import dev.bilby.data.StartupDestination
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.theme.Spacing

@Composable
fun NavigationCustomizationPage(
    state: SettingsUiState,
    onUpdatePrefs: ((NavigationPrefs) -> NavigationPrefs) -> Unit,
    onResetPrefs: () -> Unit,
    onBack: (() -> Unit)?,
) {
    val prefs = state.navigationPrefs.sanitize()
    val visibleTabs = prefs.primaryTabs
    val orderedTabs = visibleTabs + RootTabId.entries.filterNot { it in visibleTabs }
    val visibleUtilities = prefs.railUtilities
    val orderedUtilities = visibleUtilities + RailUtilityId.entries.filterNot { it in visibleUtilities }

    val moveUpLabel = stringResource(Res.string.nav_custom_move_up)
    val moveDownLabel = stringResource(Res.string.nav_custom_move_down)

    SettingsSubPage(
        title = stringResource(Res.string.settings_section_navigation_customization),
        onBack = onBack,
        ready = state.loaded,
        actions = {
            TextButton(onClick = onResetPrefs) {
                Text(stringResource(Res.string.player_custom_reset_default))
            }
        },
    ) {
        SettingsGroup(title = stringResource(Res.string.settings_start_tab)) {
            row { position ->
                ChoiceRow(
                    position = position,
                    icon = Icons.Outlined.Start,
                    title = stringResource(Res.string.settings_start_tab),
                    subtitle = stringResource(Res.string.nav_custom_startup_subtitle),
                    options = StartupDestination.entries,
                    selected = prefs.startupPage,
                    label = { startupDestinationLabel(it) },
                    onChange = { destination ->
                        onUpdatePrefs { it.withStartupPage(destination) }
                    },
                )
            }
        }

        SettingsGroup(title = stringResource(Res.string.nav_custom_group_primary_tabs)) {
            orderedTabs.forEach { tab ->
                row { position ->
                    val visibleIndex = visibleTabs.indexOf(tab)
                    val isVisible = visibleIndex >= 0
                    val isLastVisible = isVisible && visibleTabs.size <= 1
                    ReorderableVisibilityRow(
                        position = position,
                        icon = tab.icon(),
                        title = rootTabLabel(tab),
                        subtitle = if (isLastVisible) {
                            stringResource(Res.string.nav_custom_last_visible_tab_hint)
                        } else {
                            null
                        },
                        visible = isVisible,
                        toggleEnabled = !isLastVisible,
                        canMoveUp = isVisible && visibleIndex > 0,
                        canMoveDown = isVisible && visibleIndex < visibleTabs.lastIndex,
                        moveUpLabel = moveUpLabel,
                        moveDownLabel = moveDownLabel,
                        onToggle = { checked ->
                            onUpdatePrefs { it.setPrimaryTabVisible(tab, checked) }
                        },
                        onMoveUp = {
                            onUpdatePrefs { it.movePrimaryTab(tab, -1) }
                        },
                        onMoveDown = {
                            onUpdatePrefs { it.movePrimaryTab(tab, 1) }
                        },
                    )
                }
            }
        }

        SettingsGroup(title = stringResource(Res.string.nav_custom_feed_up_strip_position)) {
            row { position ->
                ChoiceRow(
                    position = position,
                    icon = Icons.AutoMirrored.Outlined.ViewSidebar,
                    title = stringResource(Res.string.nav_custom_feed_up_strip_position),
                    subtitle = stringResource(Res.string.nav_custom_feed_up_strip_position_subtitle),
                    options = FeedUpStripPosition.entries,
                    selected = prefs.feedUpStripPosition,
                    label = { feedUpStripPositionLabel(it) },
                    onChange = { nextPosition ->
                        onUpdatePrefs { it.withFeedUpStripPosition(nextPosition) }
                    },
                )
            }
        }

        SettingsGroup(title = stringResource(Res.string.nav_custom_group_rail_utilities)) {
            orderedUtilities.forEach { utility ->
                row { position ->
                    val visibleIndex = visibleUtilities.indexOf(utility)
                    val isVisible = visibleIndex >= 0
                    val isMandatory = utility == RailUtilityId.Settings
                    ReorderableVisibilityRow(
                        position = position,
                        icon = utility.icon(),
                        title = railUtilityLabel(utility),
                        subtitle = if (isMandatory) {
                            stringResource(Res.string.nav_custom_settings_required_hint)
                        } else {
                            null
                        },
                        visible = isVisible,
                        toggleEnabled = !isMandatory,
                        canMoveUp = isVisible && visibleIndex > 0,
                        canMoveDown = isVisible && visibleIndex < visibleUtilities.lastIndex,
                        moveUpLabel = moveUpLabel,
                        moveDownLabel = moveDownLabel,
                        onToggle = { checked ->
                            onUpdatePrefs { it.setRailUtilityVisible(utility, checked) }
                        },
                        onMoveUp = {
                            onUpdatePrefs { it.moveRailUtility(utility, -1) }
                        },
                        onMoveDown = {
                            onUpdatePrefs { it.moveRailUtility(utility, 1) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReorderableVisibilityRow(
    position: RowPosition,
    icon: ImageVector,
    title: String,
    subtitle: String?,
    visible: Boolean,
    toggleEnabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    moveUpLabel: String,
    moveDownLabel: String,
    onToggle: (Boolean) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    StaticSettingRow(
        position = position,
        icon = icon,
        title = title,
        subtitle = subtitle,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                if (visible) {
                    BilbyTooltip(text = moveUpLabel) {
                        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowUp,
                                contentDescription = moveUpLabel,
                            )
                        }
                    }
                    BilbyTooltip(text = moveDownLabel) {
                        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = moveDownLabel,
                            )
                        }
                    }
                }
                Switch(
                    checked = visible,
                    onCheckedChange = if (toggleEnabled) onToggle else null,
                    enabled = toggleEnabled,
                )
            }
        },
    )
}

@Composable
internal fun startupDestinationLabel(destination: StartupDestination): String = when (destination) {
    StartupDestination.Feed -> stringResource(Res.string.tab_feed)
    StartupDestination.Search -> stringResource(Res.string.tab_search)
    StartupDestination.Library -> stringResource(Res.string.tab_library)
    StartupDestination.ToView -> stringResource(Res.string.tab_toview)
    StartupDestination.FavFolders -> stringResource(Res.string.fav_folders_title)
    StartupDestination.History -> stringResource(Res.string.history_title)
    StartupDestination.Offline -> stringResource(Res.string.offline_title)
}

@Composable
private fun rootTabLabel(tab: RootTabId): String = when (tab) {
    RootTabId.Feed -> stringResource(Res.string.tab_feed)
    RootTabId.Search -> stringResource(Res.string.tab_search)
    RootTabId.Library -> stringResource(Res.string.tab_library)
}

private fun RootTabId.icon(): ImageVector = when (this) {
    RootTabId.Feed -> Icons.Outlined.Subscriptions
    RootTabId.Search -> Icons.Outlined.Search
    RootTabId.Library -> Icons.Outlined.VideoLibrary
}

@Composable
private fun feedUpStripPositionLabel(position: FeedUpStripPosition): String = when (position) {
    FeedUpStripPosition.Left -> stringResource(Res.string.nav_custom_feed_up_strip_left)
    FeedUpStripPosition.Top -> stringResource(Res.string.nav_custom_feed_up_strip_top)
    FeedUpStripPosition.Right -> stringResource(Res.string.nav_custom_feed_up_strip_right)
}

@Composable
private fun railUtilityLabel(utility: RailUtilityId): String = when (utility) {
    RailUtilityId.Followings -> stringResource(Res.string.profile_following)
    RailUtilityId.Pushes -> stringResource(Res.string.feed_up_pushes_entry)
    RailUtilityId.Dynamics -> stringResource(Res.string.nav_custom_utility_dynamics)
    RailUtilityId.Messages -> stringResource(Res.string.message_title)
    RailUtilityId.FavFolders -> stringResource(Res.string.fav_folders_title)
    RailUtilityId.Offline -> stringResource(Res.string.offline_title)
    RailUtilityId.History -> stringResource(Res.string.history_title)
    RailUtilityId.ToView -> stringResource(Res.string.tab_toview)
    RailUtilityId.Settings -> stringResource(Res.string.settings_title)
}

private fun RailUtilityId.icon(): ImageVector = when (this) {
    RailUtilityId.Followings -> Icons.Outlined.PeopleAlt
    RailUtilityId.Pushes -> Icons.Outlined.Mail
    RailUtilityId.Dynamics -> Icons.Outlined.Forum
    RailUtilityId.Messages -> Icons.Outlined.Mail
    RailUtilityId.FavFolders -> Icons.Outlined.CollectionsBookmark
    RailUtilityId.Offline -> Icons.Outlined.DownloadForOffline
    RailUtilityId.History -> Icons.Outlined.History
    RailUtilityId.ToView -> Icons.Outlined.WatchLater
    RailUtilityId.Settings -> Icons.Outlined.Settings
}
