package dev.danmaku.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.abs

/**
 * 弹幕渲染 host。持有一个 [DanmakuHostState],在 `LaunchedEffect` 里驱动它的帧循环,画布上
 * 把每次回调拿到的当帧计划逐条画出来。这一层只做投影:坐标、裁剪、绘制,不读也不改任何轨道
 * 状态。不引 material3——没有主题、没有默认配色,外观全部来自 [style]。
 *
 * **契约**:排布用的 [DanmakuLayoutConfig.canvasWidthPx] / [DanmakuLayoutConfig.canvasHeightPx]
 * 必须等于这个 Canvas 的像素尺寸。这里故意不重复接收一份尺寸参数去覆盖排布期用的那份配置——
 * `size` 是画布在绘制那一刻的真实尺寸,是唯一权威来源,重复传参只会引入"两处配置各传一份、
 * 可能对不齐"的新故障模式。不一致不会被默默吞掉:尺寸变化时(转屏、分屏、窗口尺寸调整)会
 * 跟 [DanmakuHostState.layout] 比对,超出容差就回调 [onCanvasSizeMismatch] ——库自己没法重编
 * 时间轴(它不持有弹幕池),只负责把"对不齐"这件事暴露出去,重新编排是调用方的事。
 *
 * 位置:
 * - 滚动:`x = viewport.right - (t - emitTime) * speed`,轨道自 viewport 顶边往下铺。
 * - 顶部:锚 viewport 顶边往下堆。
 * - 底部:锚 **画布**底边往上堆,不受 viewport 约束,也不进 viewport 的裁剪区(理由见
 *   [DanmakuViewport]:收进视口它就不是底部弹幕了)。它的纵向上限是
 *   [DanmakuLayoutConfig.bottomTrackFraction]。
 *
 * **绘制帧里没有文字排版,也没有文字绘制命令。** 排版和"描边 + 填充"两遍 `drawText` 都发生在
 * 弹幕进入预热窗口的那一次,结果录进 [DanmakuRenderCache] 持有的 display list;每帧对每条可见
 * 弹幕只做一次平移 + `drawLayer`。上一版是每帧每条查一次 `TextMeasurer` 缓存再提交两遍
 * `drawText`,320 条同屏就是 320 次缓存查询 + 640 次绘制命令,实测主线程每帧约 10ms
 * (120Hz 的预算是 8.3ms),而同期 GPU 只用了 3~5ms —— 瓶颈在提交侧,不在填充率侧。
 *
 * @param renderStats 传进来就能观测缓存行为(命中/未命中、layer 创建/复用/回收);不传就内部
 *   自己建一份,统计照常发生,只是没人读。
 */
@Composable
fun DanmakuHost(
    state: DanmakuHostState,
    style: DanmakuRenderStyle = DanmakuRenderStyle(),
    modifier: Modifier = Modifier,
    renderStats: DanmakuRenderStats? = null,
    onCanvasSizeMismatch: (actualWidthPx: Float, actualHeightPx: Float) -> Unit = { _, _ -> },
) {
    // 这个 measurer 只在准备阶段用,每条文本最多进来一次,自带的 LRU 已经不是热路径 ——
    // 真正的排版缓存是 DanmakuRenderCache 里那张按 (文本, 解析后 TextStyle) 建的表。
    val measurer = rememberTextMeasurer()
    val graphicsContext = LocalGraphicsContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // remember 无条件调用再取,不写成 `renderStats ?: remember { ... }` —— 那样 remember 会被
    // elvis 短路成"有时调、有时不调",槽位表跟着 renderStats 的有无错位。
    val fallbackStats = remember { DanmakuRenderStats() }
    val stats = renderStats ?: fallbackStats

    // 缓存挂在样式和 density 上:字号、字体、颜色兜底、描边、不透明度任一变化,已录的
    // display list 全部作废(alpha 是烤进去的,见 DanmakuRenderCache 的类注释)。
    val cache = remember(measurer, graphicsContext, density, layoutDirection, style, stats) {
        DanmakuRenderCache(measurer, graphicsContext, density, layoutDirection, style, stats)
    }
    // GraphicsLayer 不还回去就是显存泄漏。remember 换实例和离开组合两条路都要走到 release,
    // DisposableEffect(cache) 两者都覆盖:key 变化时先 onDispose 旧的。
    DisposableEffect(cache) {
        onDispose { cache.release() }
    }

    val prewarmer = remember(cache) { DanmakuPrewarmer(cache) }

    // frame 是复用的普通 list,不是 SnapshotStateList——内容变化本身不会触发重组,
    // frameVersion 才是 Canvas 订阅的信号源,列表只是它背后的数据。
    val frame = remember { mutableListOf<DanmakuFlightPlan>() }
    var framePositionMillis by remember { mutableLongStateOf(0L) }
    var frameVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(state, prewarmer) {
        state.run { visible, positionMillis ->
            frame.clear()
            frame.addAll(visible)
            framePositionMillis = positionMillis
            frameVersion++
            // 预热放在帧回调而不是绘制块里:绘制块要保持"零测量",而这里已经在主线程的帧
            // 边界上,花掉的是本帧剩余预算,不是下一帧的。
            prewarmer.onFrame(state, positionMillis)
        }
    }

    val layout = state.layout

    Canvas(
        modifier = modifier
            // preferredFrameRate 是 DrawModifierNode,帧率偏好在每次 draw 时自动下发,不需要
            // 每帧重设;但它必须挂在**真正产生绘制**的节点上才生效,所以只能加在 Canvas 自己
            // 的 modifier 链里,不能由调用方在外层容器上挂。
            // 30/60 档不请求高刷:那两档的绘制频率由 FrameDeadlineScheduler 限制,再向系统要
            // 高刷新率只会让面板空转。
            .then(
                if (state.frameRateCap == DanmakuFrameRateCap.DISPLAY) {
                    Modifier.preferredFrameRate(FrameRateCategory.High)
                } else {
                    Modifier
                },
            )
            .onSizeChanged { size ->
                val actualWidthPx = size.width.toFloat()
                val actualHeightPx = size.height.toFloat()
                val mismatched = abs(actualWidthPx - layout.canvasWidthPx) > CANVAS_SIZE_TOLERANCE_PX ||
                    abs(actualHeightPx - layout.canvasHeightPx) > CANVAS_SIZE_TOLERANCE_PX
                if (actualWidthPx > 0f && actualHeightPx > 0f && mismatched) {
                    onCanvasSizeMismatch(actualWidthPx, actualHeightPx)
                }
            },
    ) {
        // 读一次 frameVersion,让这个绘制块订阅上面的 State 写入。
        @Suppress("UNUSED_EXPRESSION")
        frameVersion

        val viewport = layout.viewportPx
        cache.beginFrame()

        // 分两趟画,因为两趟的裁剪区不同。列表通常只有几十条,多扫一遍比先分组便宜,也不用
        // 为分组分配两个列表。
        //
        // 第一趟:滚动 + 顶部,裁到视口。没有这次裁剪,显示区域就退化成"轨道数少了几条",
        // 弹幕照样画到区域外面去。
        clipRect(left = viewport.left, top = viewport.top, right = viewport.right, bottom = viewport.bottom) {
            for (plan in frame) {
                if (plan.mode == DanmakuMode.BOTTOM) continue
                val y = viewport.top + plan.track * layout.trackHeightPx
                val x = if (plan.mode == DanmakuMode.SCROLL) {
                    viewport.right - (framePositionMillis - plan.emitTimeMillis) * plan.speedPxPerMillis
                } else {
                    centeredX(viewport.left, viewport.width, plan.widthPx)
                }
                cache.draw(this, plan.danmaku, x, y)
            }
        }

        // 第二趟:底部弹幕锚**画布**底边往上堆,不进视口的裁剪区——收进视口它就成了"画面
        // 四分之三处的弹幕",不再是底部弹幕(理由见 DanmakuViewport)。它因此会和字幕、
        // 播放控件抢画面底部那条带,这是底部弹幕固有的,将来靠避让区解决。
        for (plan in frame) {
            if (plan.mode != DanmakuMode.BOTTOM) continue
            val y = size.height - (plan.track + 1) * layout.trackHeightPx
            cache.draw(this, plan.danmaku, centeredX(0f, size.width, plan.widthPx), y)
        }

        cache.endFrame()
    }
}

/**
 * 预热驱动。每隔 [PREWARM_INTERVAL_MILLIS] 播放时间往前看 [PREWARM_LOOKAHEAD_MILLIS],把那时会
 * 在屏的弹幕提前准备好。
 *
 * 有预算上限是因为准备工作(排版 + 录制 display list)是主线程活儿:一次把上百条全准备了,
 * 省下的每帧成本会以一个几十毫秒的尖峰还回去,直方图上就是一个新的丢帧。分摊到多帧做,窗口
 * 有一两秒的余量,来得及。
 *
 * 用播放时间而不是帧数做节流,是因为倍速播放时"多久之后上屏"跟着倍速走,而帧数不跟。
 */
private class DanmakuPrewarmer(private val cache: DanmakuRenderCache) {

    private var lastPositionMillis = Long.MIN_VALUE

    fun onFrame(state: DanmakuHostState, positionMillis: Long) {
        // abs 而不是差值:seek 往回跳同样要立刻重新预热。
        if (abs(positionMillis - lastPositionMillis) < PREWARM_INTERVAL_MILLIS) return
        lastPositionMillis = positionMillis
        var budget = PREWARM_BUDGET_PER_PASS
        state.forEachVisibleAt(positionMillis + PREWARM_LOOKAHEAD_MILLIS) { plan ->
            // 预算用完不提前退出:visitor 没有中断口子,而扫剩下的部分只是每条一次哈希查询。
            if (budget > 0 && cache.prepare(plan.danmaku)) budget--
        }
    }

    private companion object {
        const val PREWARM_LOOKAHEAD_MILLIS = 1_500L
        const val PREWARM_INTERVAL_MILLIS = 100L
        const val PREWARM_BUDGET_PER_PASS = 24
    }
}

/**
 * 固定弹幕的居中 x。宽度用的是**排布期**测出的 [textWidthPx],不是绘制期重新量的宽度:
 * 两者本该相等,不等就说明两条测量路径的字体/字号已经不同源,那时该暴露成位置错位,而不是
 * 让画面自己对齐、把问题藏回排布里。
 */
private fun centeredX(leftPx: Float, availableWidthPx: Float, textWidthPx: Float): Float =
    leftPx + (availableWidthPx - textWidthPx) / 2f

/** 容差:亚像素级的取整误差不算"对不齐",超过一个像素才值得打扰调用方。 */
private const val CANVAS_SIZE_TOLERANCE_PX = 1f
