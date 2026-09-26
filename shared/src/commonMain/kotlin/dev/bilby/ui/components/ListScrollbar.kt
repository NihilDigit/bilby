package dev.bilby.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 列表右侧的滚动条。桌面上是一条可拖动的滚动条:鼠标没有甩动,几百条的列表只靠滚轮
 * 走不到底,也看不出自己在哪儿。Android 上什么也不画,触屏靠甩动,系统也没有这个惯例。
 */
@Composable
expect fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier)

@Composable
expect fun ListScrollbar(state: LazyGridState, modifier: Modifier = Modifier)

@Composable
expect fun ListScrollbar(state: LazyStaggeredGridState, modifier: Modifier = Modifier)
