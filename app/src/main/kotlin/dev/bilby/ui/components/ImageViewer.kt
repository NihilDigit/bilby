package dev.bilby.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.bilby.R
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.launch

/** 双击在 1x 与这一档之间来回。3x 够看清截图上的小字,再大就得靠捏合。 */
private const val DoubleTapScale = 3f
private const val MaxScale = 6f

/**
 * 全屏看图。评论配图点开走这里(PiliPlus 的 `common/widgets/image_grid/image_grid_view.dart`
 * 点击后进 `GalleryViewer`,同样是全屏 + 可缩放 + 左右翻页)。
 *
 * 手写而不是引依赖:要的只有捏合缩放、双击、拖动和翻页四件事。连 `detectTransformGestures`
 * 都没用上 —— 它把手势里每个事件都消费掉,1x 时的单指横划也不例外,于是永远翻不到下一张。
 * 为这个引 photo-view 一类的库,换来的是一整套我们用不到的手势策略。
 *
 * **缩放状态按页各存一份并在翻页时归位**:放大着翻到下一张,下一张继承上一张的缩放和位移,
 * 看起来就是"图开在了屏幕外面"。
 */
@Composable
fun ImageViewer(
    urls: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (urls.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 默认的 Dialog 会留出边距并按内容收缩,看图要的是整块屏幕。
            usePlatformDefaultWidth = false,
            // 还要铺到系统栏**底下**去。只给 usePlatformDefaultWidth 的话,窗口仍然避开
            // 状态栏和手势条,黑底铺不满,顶端会露出底层的评论列表 —— 真机上看得很清楚。
            decorFitsSystemWindows = false,
        ),
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialIndex.coerceIn(0, urls.lastIndex),
            pageCount = { urls.size },
        )
        var zoomed by remember { mutableStateOf(false) }
        LaunchedEffect(pagerState.currentPage) { zoomed = false }
        BackHandler(onBack = onDismiss)
        // 只有这个窗口铺进刘海所在的短边,不动 Activity 的窗口 —— 全局开 shortEdges 会让
        // 横屏下每一页的正文都可能压在挖孔底下。关闭按钮那一侧照样躲 displayCutout。
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(dialogWindow) {
            if (dialogWindow != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dialogWindow.attributes = dialogWindow.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            onDispose { }
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // 放大之后横向拖动是平移图片,不该同时翻页;由每一页自己决定还能不能翻。
                userScrollEnabled = !zoomed,
            ) { page ->
                ZoomableImage(
                    url = urls[page],
                    onDismiss = onDismiss,
                    onZoomedChanged = { pageZoomed ->
                        if (pagerState.currentPage == page) zoomed = pageZoomed
                    },
                    // 翻走的页把缩放归位,回来时是 1x。
                    active = pagerState.currentPage == page,
                )
            }

            IconButton(
                onClick = onDismiss,
                // 窗口铺到系统栏底下之后,关闭按钮要自己躲开状态栏,否则压在时钟上。
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.barsAndCutout)
                    .padding(Spacing.Tight),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = FixedColors.OnMedia,
                )
            }

            if (urls.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1} / ${urls.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = FixedColors.OnMedia,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.barsAndCutout)
                        .padding(Spacing.Comfortable),
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(
    url: String,
    onDismiss: () -> Unit,
    onZoomedChanged: (Boolean) -> Unit,
    active: Boolean,
) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(active) {
        if (!active) {
            scale.snapTo(1f)
            offsetX.snapTo(0f)
            offsetY.snapTo(0f)
            onZoomedChanged(false)
        }
    }

    /**
     * 平移不能无界,否则一拖图就飞出屏幕再也找不回来。放大 s 倍后,图片比容器多出
     * `(s-1)*边长`,平移量最多到它的一半(两边各一半)。s <= 1 时没有余量,夹成 0。
     */
    fun clampOffset(value: Float, extent: Int, currentScale: Float): Float {
        val max = (extent * (currentScale - 1f) / 2f).coerceAtLeast(0f)
        return value.coerceIn(-max, max)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    // 单击关闭:全屏看图时这是最顺手的退出方式,和系统相册一致。
                    onTap = { if (scale.value <= 1f) onDismiss() },
                    onDoubleTap = { tap ->
                        scope.launch {
                            if (scale.value > 1f) {
                                launch { scale.animateTo(1f) }
                                launch { offsetX.animateTo(0f) }
                                launch { offsetY.animateTo(0f) }
                                onZoomedChanged(false)
                            } else {
                                // 以双击点为锚放大,而不是以画面中心 —— 否则想看的那个角
                                // 放大后跑到屏幕外面去了。
                                val target = DoubleTapScale
                                val dx = (boxSize.width / 2f - tap.x) * (target - 1f)
                                val dy = (boxSize.height / 2f - tap.y) * (target - 1f)
                                launch { scale.animateTo(target) }
                                launch { offsetX.animateTo(clampOffset(dx, boxSize.width, target)) }
                                launch { offsetY.animateTo(clampOffset(dy, boxSize.height, target)) }
                                onZoomedChanged(true)
                            }
                        }
                    },
                )
            }
            // **手写而不是 `detectTransformGestures`。** 那个组件把手势里的每个事件都消费掉,
            // 包括 1x 时的单指横划 —— 于是横划永远传不到 HorizontalPager,翻不到相邻的图。
            // 这里的规矩是:双指(在缩放)或已经放大了才接管并消费,其余原样放行。
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val multiTouch = event.changes.count { it.pressed } > 1
                        if (!multiTouch && scale.value <= 1f) continue

                        val next = (scale.value * zoom).coerceIn(1f, MaxScale)
                        scope.launch {
                            scale.snapTo(next)
                            onZoomedChanged(next > 1f)
                            if (next > 1f) {
                                offsetX.snapTo(clampOffset(offsetX.value + pan.x, boxSize.width, next))
                                offsetY.snapTo(clampOffset(offsetY.value + pan.y, boxSize.height, next))
                            } else {
                                offsetX.snapTo(0f)
                                offsetY.snapTo(0f)
                            }
                        }
                        event.changes.forEach { if (it.pressed) it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BiliAsyncImage(
            url = url,
            contentDescription = stringResource(R.string.comment_picture),
            // Fit 而不是 Crop:看图要看全,截图被裁掉边缘正是要在这里解决的问题。
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offsetX.value
                    translationY = offsetY.value
                },
        )
    }
}
