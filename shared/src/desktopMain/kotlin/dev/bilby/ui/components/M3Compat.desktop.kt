package dev.bilby.ui.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal actual fun StaticListItem(
    modifier: Modifier,
    leadingContent: (@Composable () -> Unit)?,
    trailingContent: (@Composable () -> Unit)?,
    supportingContent: (@Composable () -> Unit)?,
    colors: ListItemColors,
    content: @Composable () -> Unit,
) {
    ListItem(
        headlineContent = content,
        modifier = modifier,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        supportingContent = supportingContent,
        colors = colors,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal actual fun BilbySplitButton(
    leadingButton: @Composable () -> Unit,
    trailingButton: @Composable () -> Unit,
    modifier: Modifier,
) {
    SplitButtonLayout(
        leadingButton = leadingButton,
        trailingButton = trailingButton,
        modifier = modifier,
    )
}
