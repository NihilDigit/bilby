package dev.bilby.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.rememberReducedMotion

/**
 * 共享的多尺寸焦点轮播封装：基于 M3 1.5 `HorizontalMultiBrowseCarousel` + `maskClip` 与桌面竖向滚轮横向驱动
 * ([verticalWheelScrollsRow])；并在系统开启「减弱动效」([rememberReducedMotion])时退化为等宽
 * `HorizontalUncontainedCarousel`，避免连续滚动形变带来视觉不适。
 *
 * 静止时 100% 稳定无自转/呼吸动画，所有形变均由用户滑动或滚轮输入因果驱动。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BilbyMultiBrowseCarousel(
    itemCount: Int,
    preferredItemWidth: Dp,
    modifier: Modifier = Modifier,
    itemSpacing: Dp = Spacing.Tight,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.Comfortable),
    itemShape: Shape = MaterialTheme.shapes.large,
    itemContent: @Composable BoxScope.(index: Int) -> Unit,
) {
    if (itemCount <= 0) return
    val state = rememberCarouselState { itemCount }
    val reducedMotion = rememberReducedMotion()
    val wheelModifier = modifier.verticalWheelScrollsRow(state)
    if (reducedMotion) {
        HorizontalUncontainedCarousel(
            state = state,
            itemWidth = preferredItemWidth,
            modifier = wheelModifier,
            itemSpacing = itemSpacing,
            contentPadding = contentPadding,
        ) { index ->
            Box(modifier = Modifier.clip(itemShape)) {
                itemContent(index)
            }
        }
    } else {
        HorizontalMultiBrowseCarousel(
            state = state,
            preferredItemWidth = preferredItemWidth,
            modifier = wheelModifier,
            itemSpacing = itemSpacing,
            flingBehavior = CarouselDefaults.multiBrowseFlingBehavior(
                state = state,
                snapAnimationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
            ),
            contentPadding = contentPadding,
        ) { index ->
            Box(modifier = Modifier.maskClip(itemShape)) {
                itemContent(index)
            }
        }
    }
}

/**
 * 列表便捷重载，将每个位置映射到对应数据项。
 */
@Composable
fun <T> BilbyMultiBrowseCarousel(
    items: List<T>,
    preferredItemWidth: Dp,
    modifier: Modifier = Modifier,
    itemSpacing: Dp = Spacing.Tight,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.Comfortable),
    itemShape: Shape = MaterialTheme.shapes.large,
    itemContent: @Composable BoxScope.(item: T) -> Unit,
) {
    BilbyMultiBrowseCarousel(
        itemCount = items.size,
        preferredItemWidth = preferredItemWidth,
        modifier = modifier,
        itemSpacing = itemSpacing,
        contentPadding = contentPadding,
        itemShape = itemShape,
    ) { index ->
        itemContent(items[index])
    }
}
