package dev.bilby.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.BackHandler
import dev.bilby.ui.LocalSystemActions
import dev.bilby.ui.LocalWindowCaption
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow

/** 双击在 1x 与这一档之间来回。3x 够看清截图上的小字,再大就得靠捏合。 */
private const val DoubleTapScale = 3f
private const val MaxScale = 6f

/** 滚轮每一格的缩放倍数。从 1x 到 3x 约五格,到 6x 八格。 */
private const val WheelZoomStep = 1.25f

private const val NoticeDurationMillis = 1800L

private class ImageZoomActions {
    var zoomIn: () -> Unit = {}
    var zoomOut: () -> Unit = {}
    var resetZoom: () -> Unit = {}
}

/**
 * 全屏看图。评论配图点开走这里(PiliPlus 的 `common/widgets/image_grid/image_grid_view.dart`
 * 点击后进 `GalleryViewer`,同样是全屏 + 可缩放 + 左右翻页)。
 *
 * 鼠标与键盘另有一套:滚轮以光标为锚缩放,`+`/`-`/`0` 缩放与归位,`Ctrl+C`/`Ctrl+S` 复制与保存原图,
 * 两侧按钮与左右键翻页,Esc 关闭。
 *
 * 手写而不是引依赖:要的只有捏合缩放、双击、拖动和翻页四件事。连 `detectTransformGestures`
 * 都没用上 —— 它把手势里每个事件都消费掉,1x 时的单指横划也不例外,于是永远翻不到下一张。
 * 为这个引 photo-view 一类的库,换来的是一整套我们用不到的手势策略。
 *
 * **缩放状态按页各存一份并在翻页时归位**:放大着翻到下一张,下一张继承上一张的缩放和位移,
 * 看起来就是"图开在了屏幕外面"。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImageViewer(
    urls: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit,
) {
    if (urls.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = fullScreenDialogProperties(),
    ) {
        LocalWindowCaption.current?.Suppress()
        val pagerState = rememberPagerState(
            initialPage = initialIndex.coerceIn(0, urls.lastIndex),
            pageCount = { urls.size },
        )
        var zoomed by remember { mutableStateOf(false) }
        LaunchedEffect(pagerState.currentPage) { zoomed = false }
        BackHandler(onBack = onDismiss)
        // 关闭按钮那一侧照样躲 displayCutout。
        DialogIntoDisplayCutout()

        val scope = rememberCoroutineScope()
        val systemActions = LocalSystemActions.current
        val supportsImageActions = systemActions.supportsImageActions
        val isTouchLike = LocalPointerSource.current.isTouchLike
        val zoomActions = remember { ImageZoomActions() }

        var notice by remember { mutableStateOf<Pair<String, Int>?>(null) }
        LaunchedEffect(notice?.second) {
            if (notice != null) {
                delay(NoticeDurationMillis)
                notice = null
            }
        }
        val showNotice: (String) -> Unit = { message ->
            notice = message to ((notice?.second ?: 0) + 1)
        }

        val copiedText = stringResource(Res.string.image_copied)
        val copyFailedText = stringResource(Res.string.image_copy_failed)
        val savedText = stringResource(Res.string.image_saved)
        val saveFailedText = stringResource(Res.string.image_save_failed)

        val copyCurrentImage: () -> Unit = {
            if (supportsImageActions) {
                val targetUrl = urls[pagerState.currentPage]
                scope.launch {
                    val ok = systemActions.copyImage(targetUrl)
                    showNotice(if (ok) copiedText else copyFailedText)
                }
            }
        }
        val saveCurrentImage: () -> Unit = {
            if (supportsImageActions) {
                val targetUrl = urls[pagerState.currentPage]
                scope.launch {
                    when (systemActions.saveImage(targetUrl)) {
                        true -> showNotice(savedText)
                        false -> showNotice(saveFailedText)
                        null -> Unit
                    }
                }
            }
        }

        val goTo: (Int) -> Unit = { page ->
            if (page in urls.indices) scope.launch { pagerState.animateScrollToPage(page) }
        }
        // 键盘:左右翻页、缩放、复制/保存、Esc 关闭。焦点要一打开就在这里,否则按键落不到对话框里。
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val ctrl = event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
                    val bare = !event.isCtrlPressed && !event.isAltPressed && !event.isMetaPressed
                    when {
                        ctrl && !event.isShiftPressed && event.key == Key.C && supportsImageActions -> copyCurrentImage()
                        ctrl && !event.isShiftPressed && event.key == Key.S && supportsImageActions -> saveCurrentImage()
                        ctrl && !event.isShiftPressed && (event.key == Key.Zero || event.key == Key.NumPad0) -> zoomActions.resetZoom()
                        bare && (event.key == Key.Equals || event.key == Key.NumPadAdd) -> zoomActions.zoomIn()
                        bare && !event.isShiftPressed && (event.key == Key.Minus || event.key == Key.NumPadSubtract) -> zoomActions.zoomOut()
                        bare && !event.isShiftPressed && (event.key == Key.Zero || event.key == Key.NumPad0) -> zoomActions.resetZoom()
                        bare && !event.isShiftPressed && event.key == Key.DirectionLeft -> goTo(pagerState.currentPage - 1)
                        bare && !event.isShiftPressed && event.key == Key.DirectionRight -> goTo(pagerState.currentPage + 1)
                        bare && !event.isShiftPressed && event.key == Key.Escape -> onDismiss()
                        else -> return@onKeyEvent false
                    }
                    true
                },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                // 放大之后横向拖动是平移图片,不该同时翻页;由每一页自己决定还能不能翻。
                userScrollEnabled = !zoomed,
            ) { page ->
                val pageContent: @Composable () -> Unit = {
                    ZoomableImage(
                        url = urls[page],
                        onDismiss = onDismiss,
                        onZoomedChanged = { pageZoomed ->
                            if (pagerState.currentPage == page) zoomed = pageZoomed
                        },
                        // 翻走的页把缩放归位,回来时是 1x。
                        active = pagerState.currentPage == page,
                        zoomActions = zoomActions,
                    )
                }
                if (!isTouchLike) {
                    val totalItems = if (supportsImageActions) 5 else 3
                    ContextMenuBox(
                        onClick = null,
                        menuLabel = stringResource(Res.string.menu_item_actions),
                        modifier = Modifier.fillMaxSize(),
                        menu = { close ->
                            var cursor = 0
                            if (supportsImageActions) {
                                val copyIndex = cursor++
                                DropdownMenuItem(
                                    onClick = {
                                        close()
                                        copyCurrentImage()
                                    },
                                    text = { Text(stringResource(Res.string.image_copy)) },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                    shape = menuItemShape(copyIndex, totalItems),
                                )
                                val saveIndex = cursor++
                                DropdownMenuItem(
                                    onClick = {
                                        close()
                                        saveCurrentImage()
                                    },
                                    text = { Text(stringResource(Res.string.image_save)) },
                                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                                    shape = menuItemShape(saveIndex, totalItems),
                                )
                            }
                            val zoomInIndex = cursor++
                            DropdownMenuItem(
                                onClick = {
                                    close()
                                    zoomActions.zoomIn()
                                },
                                text = { Text(stringResource(Res.string.image_zoom_in)) },
                                leadingIcon = { Icon(Icons.Outlined.ZoomIn, contentDescription = null) },
                                shape = menuItemShape(zoomInIndex, totalItems),
                            )
                            val zoomOutIndex = cursor++
                            DropdownMenuItem(
                                onClick = {
                                    close()
                                    zoomActions.zoomOut()
                                },
                                text = { Text(stringResource(Res.string.image_zoom_out)) },
                                leadingIcon = { Icon(Icons.Outlined.ZoomOut, contentDescription = null) },
                                shape = menuItemShape(zoomOutIndex, totalItems),
                            )
                            val resetIndex = cursor
                            DropdownMenuItem(
                                onClick = {
                                    close()
                                    zoomActions.resetZoom()
                                },
                                text = { Text(stringResource(Res.string.image_zoom_reset)) },
                                leadingIcon = { Icon(Icons.Outlined.FitScreen, contentDescription = null) },
                                shape = menuItemShape(resetIndex, totalItems),
                            )
                        },
                    ) {
                        pageContent()
                    }
                } else {
                    pageContent()
                }
            }

            BilbyTooltip(
                text = stringResource(Res.string.action_close),
                shortcut = "Esc",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.barsAndCutout)
                    .padding(Spacing.Tight),
            ) {
                IconButton(
                    onClick = onDismiss,
                    // 窗口铺到系统栏底下之后,关闭按钮要自己躲开状态栏,否则压在时钟上。
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.action_close),
                        tint = FixedColors.OnMedia,
                    )
                }
            }

            if (supportsImageActions && !isTouchLike) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.barsAndCutout)
                        .padding(Spacing.Tight),
                ) {
                    BilbyTooltip(text = stringResource(Res.string.image_copy), shortcut = "Ctrl+C") {
                        IconButton(onClick = copyCurrentImage) {
                            Icon(
                                Icons.Outlined.ContentCopy,
                                contentDescription = stringResource(Res.string.image_copy),
                                tint = FixedColors.OnMedia,
                            )
                        }
                    }
                    BilbyTooltip(text = stringResource(Res.string.image_save), shortcut = "Ctrl+S") {
                        IconButton(onClick = saveCurrentImage) {
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = stringResource(Res.string.image_save),
                                tint = FixedColors.OnMedia,
                            )
                        }
                    }
                }
            }

            // 用鼠标时两侧给翻页按钮。鼠标拖动翻页在桌面上找不到(没人会去拖一张图),键盘
            // 左右键又不是人人想得到;手指横划本来就会,触屏时不画,免得盖住图的两边。
            if (urls.size > 1 && !isTouchLike) {
                if (pagerState.currentPage > 0) {
                    PageButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        description = stringResource(Res.string.image_viewer_previous),
                        shortcut = "←",
                        onClick = { goTo(pagerState.currentPage - 1) },
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
                if (pagerState.currentPage < urls.lastIndex) {
                    PageButton(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        description = stringResource(Res.string.image_viewer_next),
                        shortcut = "→",
                        onClick = { goTo(pagerState.currentPage + 1) },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
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

            AnimatedVisibility(
                visible = notice != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.barsAndCutout)
                    .padding(Spacing.Loose),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) {
                    Text(
                        text = notice?.first.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
                    )
                }
            }
        }
    }
}

/** 压在图上的翻页按钮。底色用封面角标那一档遮罩,亮图、暗图上都看得见。 */
@Composable
private fun PageButton(
    icon: ImageVector,
    description: String,
    shortcut: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    BilbyTooltip(
        text = description,
        shortcut = shortcut,
        modifier = modifier.padding(Spacing.Comfortable),
    ) {
        IconButton(
            onClick = onClick,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = FixedColors.ScrimOnMedia,
                contentColor = FixedColors.OnMedia,
            ),
        ) {
            Icon(icon, contentDescription = description)
        }
    }
}

@Composable
private fun ZoomableImage(
    url: String,
    onDismiss: () -> Unit,
    onZoomedChanged: (Boolean) -> Unit,
    active: Boolean,
    zoomActions: ImageZoomActions,
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

    /**
     * 缩放到 [next],并让 [anchor] 处的那一点留在原地。anchor 相对容器中心计:图上一点在屏幕上的
     * 位置是 `中心 + 位移 + 倍数 × 该点`,倍数从 s 变到 s' 时,要让锚点不动,位移就得换成
     * `anchor - (anchor - 位移) × s'/s`。
     */
    fun zoomAround(next: Float, anchor: Offset) {
        val current = scale.value
        val ratio = next / current
        val x = clampOffset(anchor.x - (anchor.x - offsetX.value) * ratio, boxSize.width, next)
        val y = clampOffset(anchor.y - (anchor.y - offsetY.value) * ratio, boxSize.height, next)
        scope.launch {
            scale.snapTo(next)
            offsetX.snapTo(x)
            offsetY.snapTo(y)
            onZoomedChanged(next > 1f)
        }
    }

    if (active) {
        SideEffect {
            zoomActions.zoomIn = {
                val next = (scale.value * WheelZoomStep).coerceIn(1f, MaxScale)
                zoomAround(next, Offset.Zero)
            }
            zoomActions.zoomOut = {
                val next = (scale.value / WheelZoomStep).coerceIn(1f, MaxScale)
                zoomAround(next, Offset.Zero)
            }
            zoomActions.resetZoom = {
                scope.launch {
                    launch { scale.animateTo(1f) }
                    launch { offsetX.animateTo(0f) }
                    launch { offsetY.animateTo(0f) }
                    onZoomedChanged(false)
                }
            }
        }
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
            }
            // 滚轮缩放,以光标所在处为锚:看图器里滚轮没有别的事可做,系统看图工具也都是这样。
            // 吃掉滚动事件,否则横向分页器会把竖直的滚轮量当成翻页。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        val notches = change.scrollDelta.y
                        if (notches == 0f) continue
                        // 往上滚放大。按比例而不是按定量加减:每一格放大的观感一样。
                        val next = (scale.value * WheelZoomStep.pow(-notches)).coerceIn(1f, MaxScale)
                        val anchor = change.position - Offset(boxSize.width / 2f, boxSize.height / 2f)
                        zoomAround(next, anchor)
                        change.consume()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        BiliAsyncImage(
            url = url,
            contentDescription = stringResource(Res.string.comment_picture),
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
