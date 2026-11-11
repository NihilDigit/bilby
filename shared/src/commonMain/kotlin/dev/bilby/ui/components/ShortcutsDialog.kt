package dev.bilby.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.bilby.resources.Res
import dev.bilby.resources.shortcuts_back
import dev.bilby.resources.shortcuts_danmaku_input
import dev.bilby.resources.shortcuts_danmaku_toggle
import dev.bilby.resources.shortcuts_fullscreen
import dev.bilby.resources.shortcuts_group_global
import dev.bilby.resources.shortcuts_group_image
import dev.bilby.resources.shortcuts_group_player
import dev.bilby.resources.shortcuts_help
import dev.bilby.resources.shortcuts_image_copy_save
import dev.bilby.resources.shortcuts_image_zoom
import dev.bilby.resources.shortcuts_mute
import dev.bilby.resources.shortcuts_nav_tabs
import dev.bilby.resources.shortcuts_open_settings
import dev.bilby.resources.shortcuts_paste_link
import dev.bilby.resources.shortcuts_play_pause
import dev.bilby.resources.shortcuts_prev_next
import dev.bilby.resources.shortcuts_refresh
import dev.bilby.resources.shortcuts_search_focus
import dev.bilby.resources.shortcuts_seek_long
import dev.bilby.resources.shortcuts_seek_percent
import dev.bilby.resources.shortcuts_seek_short
import dev.bilby.resources.shortcuts_title
import dev.bilby.resources.shortcuts_volume
import dev.bilby.stringResource
import dev.bilby.ui.theme.Spacing
import org.jetbrains.compose.resources.StringResource

private data class ShortcutEntry(
    val label: StringResource,
    val keys: List<String>,
)

private val GlobalShortcuts = listOf(
    ShortcutEntry(Res.string.shortcuts_nav_tabs, listOf("Ctrl + 1 / 2 / 3")),
    ShortcutEntry(Res.string.shortcuts_search_focus, listOf("Ctrl + F", "/")),
    ShortcutEntry(Res.string.shortcuts_open_settings, listOf("Ctrl + ,")),
    ShortcutEntry(Res.string.shortcuts_refresh, listOf("F5", "Ctrl + R")),
    ShortcutEntry(Res.string.shortcuts_paste_link, listOf("Ctrl + V")),
    ShortcutEntry(Res.string.shortcuts_back, listOf("Alt + ←", "Esc")),
    ShortcutEntry(Res.string.shortcuts_help, listOf("F1", "Ctrl + /")),
)

private val PlayerShortcuts = listOf(
    ShortcutEntry(Res.string.shortcuts_play_pause, listOf("Space")),
    ShortcutEntry(Res.string.shortcuts_seek_short, listOf("← / →")),
    ShortcutEntry(Res.string.shortcuts_seek_long, listOf("Shift + ← / →")),
    ShortcutEntry(Res.string.shortcuts_seek_percent, listOf("0 – 9")),
    ShortcutEntry(Res.string.shortcuts_prev_next, listOf("[ / ]")),
    ShortcutEntry(Res.string.shortcuts_volume, listOf("↑ / ↓")),
    ShortcutEntry(Res.string.shortcuts_mute, listOf("M")),
    ShortcutEntry(Res.string.shortcuts_fullscreen, listOf("F")),
    ShortcutEntry(Res.string.shortcuts_danmaku_toggle, listOf("D")),
    ShortcutEntry(Res.string.shortcuts_danmaku_input, listOf("Enter")),
)

private val ImageShortcuts = listOf(
    ShortcutEntry(Res.string.shortcuts_image_zoom, listOf("+ / - / 0")),
    ShortcutEntry(Res.string.shortcuts_image_copy_save, listOf("Ctrl + C / S")),
)

/**
 * 快捷键面板(`F1` / `Ctrl+/`),走 [PaneSheet]:宽窗口是右侧划入的模态面板,窄窗口是底部面板。
 */
@Composable
fun ShortcutsSheet(onDismiss: () -> Unit) {
    PaneSheet(onDismissRequest = onDismiss, title = stringResource(Res.string.shortcuts_title)) {
        val showTitle = !inPane
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.Comfortable)
                .padding(bottom = Spacing.Loose),
            verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        ) {
            if (showTitle) {
                Text(
                    text = stringResource(Res.string.shortcuts_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            ShortcutSection(stringResource(Res.string.shortcuts_group_global), GlobalShortcuts)
            ShortcutSection(stringResource(Res.string.shortcuts_group_player), PlayerShortcuts)
            ShortcutSection(stringResource(Res.string.shortcuts_group_image), ImageShortcuts)
        }
    }
}

@Composable
private fun ShortcutSection(title: String, entries: List<ShortcutEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        SectionHeader(title = title)
        entries.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            ) {
                Text(
                    text = stringResource(entry.label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
                    entry.keys.forEach { keycap ->
                        KeycapBadge(keycap)
                    }
                }
            }
        }
    }
}

@Composable
private fun KeycapBadge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = MaterialTheme.shapes.extraSmall,
            )
            .padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
    )
}
