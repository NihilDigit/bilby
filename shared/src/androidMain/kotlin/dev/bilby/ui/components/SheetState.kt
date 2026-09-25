package dev.bilby.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable

/**
 * 只有"收起"和"展开"两档的底部面板状态,不停在半开。
 *
 * 本项目的几张面板(完整队列、分 P、一楼回复、写评论)都要跳过半开:半开时视口的下半截在屏幕外,
 * 要居中的那一条、要打字的那块输入区正好落在看不见的地方。
 *
 * material3 1.5.0 起 `rememberModalBottomSheetState(skipPartiallyExpanded = true)` 弃用,
 * 换成按"允许停在哪几档"给一个集合。收在这里是为了四处调用点不各写一遍那个集合。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberExpandedSheetState(): SheetState =
    rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded))
