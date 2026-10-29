package dev.bilby.ui.components

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 抹平 Android material3 1.5.0-alpha25 与 CMP desktop 1.12.0-alpha03(对应 alpha22)两处命名断层:
 * - 无交互静态 `ListItem`:alpha25 改为尾随 `content` lambda 并弃用 `headlineContent`,alpha22 尚无新重载;
 * - `SplitButton`:alpha25 由 `SplitButtonLayout` 改名而来,alpha22 仍叫 `SplitButtonLayout`。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal expect fun StaticListItem(
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = ListItemDefaults.colors(),
    content: @Composable () -> Unit,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal expect fun BilbySplitButton(
    leadingButton: @Composable () -> Unit,
    trailingButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
)
