package dev.bilby.ui.components

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 颜色跟主题走:默认样式是固定的灰,深色主题下几乎看不见。 */
@Composable
actual fun ListScrollbar(state: LazyListState, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier.fillMaxHeight(),
        style = LocalScrollbarStyle.current.copy(
            unhoverColor = colors.onSurface.copy(alpha = 0.24f),
            hoverColor = colors.onSurface.copy(alpha = 0.48f),
        ),
    )
}
