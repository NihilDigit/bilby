package dev.bilby.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
actual fun ListScrollbar(state: LazyListState, modifier: Modifier) = Unit

@Composable
actual fun ListScrollbar(state: LazyGridState, modifier: Modifier) = Unit

@Composable
actual fun ListScrollbar(state: LazyStaggeredGridState, modifier: Modifier) = Unit
