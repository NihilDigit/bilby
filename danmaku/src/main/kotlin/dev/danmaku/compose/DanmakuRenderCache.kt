package dev.danmaku.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

/**
 * 排版缓存的 key。**颜色不在里面**:颜色不影响排版,同一句话的红蓝两份该共用一次测量。
 * 反过来,所有影响排版的属性都必须在里面,而它们全部住在 [style] 这一个对象里 —— 字体族、
 * 字重、字号、locale、字间距、textDecoration 一个不落 —— 所以这里直接把整份解析后的
 * [TextStyle] 当 key,而不是手抄一份"我认为重要的属性"清单。抄清单这条路走不通:漏一个属性
 * 表现为"换了字体但排版没变",而且以后每加一个可配样式都要记得回来补,没人会记得。
 */
internal data class DanmakuLayoutKey(val text: String, val style: TextStyle)

/**
 * display list 的 key。比 [DanmakuLayoutKey] 多一个颜色(录制时颜色被烤进 display list),
 * 少一整个 [TextStyle] —— 因为整个 [DanmakuRenderCache] 本身就是按 style 建的(style 一变
 * 整个缓存重建并释放),缓存内部只可能有一份 baseTextStyle,再把它塞进逐帧查询的 key 里就是
 * 白算一次 [TextStyle.hashCode]。这个哈希不便宜(要走遍 SpanStyle + ParagraphStyle 的几十个
 * 字段),而这个 key 是每帧每条弹幕都要算一次的东西,320 条同屏时那点开销正是我们要省的。
 */
internal data class DanmakuLayerKey(val text: String, val fontSizeSp: Float?, val colorRgb: Int)

/**
 * 分级绘制后端的第一、二级(gap 分析 3.4「文字准备与绘制后端」):
 *
 * 1. **prepared layout** —— 弹幕进入预热窗口时测一次,`TextLayoutResult` 存下来。绘制帧
 *    **不调用** [TextMeasurer.measure];真的漏了(刚 seek 完那一两帧)会当场补测并记一次
 *    [DanmakuRenderStats.latePrepareCount],不是把这条弹幕吞掉。
 * 2. **`GraphicsLayer`** —— 把描边和填充两遍 `drawText` 录进一个 display list,之后每帧只做
 *    一次 Canvas 平移 + `drawLayer()`。原先每帧每条要提交两遍文字绘制命令(320 条同屏就是
 *    640 次),现在是一次 RenderNode 引用。
 *
 * 第三级 sprite atlas 不做:实测 GPU 只用了 3~5ms,瓶颈不在提交绘制命令那一侧。
 *
 * ### 透明度为什么不设成 layer alpha
 *
 * `GraphicsLayer` 缓存的是 display list,本身不等于离屏位图 —— 但默认
 * `CompositingStrategy.Auto` 下,**layer alpha < 1 必然把它提升为离屏缓冲**。而弹幕不透明度
 * (`DanmakuRenderStyle.opacity`)是用户设置项,默认就 < 1,天真地写 `layer.alpha = opacity`
 * 会给每条弹幕开一张离屏 buffer,比不用 layer 还慢。`CompositingStrategy.ModulateAlpha` 能
 * 绕开离屏,但它改变重叠内容的 alpha 合成结果,而弹幕恰恰重叠(不限密度档下 320 条互相压着)。
 *
 * 所以这里**把 alpha 烤进录制时的绘制命令**(`drawText` 的 `alpha` 参数),layer alpha 保持 1,
 * 样式变化时整个缓存重建重录。这样画出来和"直接往 Canvas 上画"逐像素一致 —— 重叠的弹幕照样
 * 互相透出,和主流播放器行为相同。**不要"顺手简化"成 `layer.alpha = style.opacity`**,那一行
 * 改动看着等价,代价是每条弹幕一张离屏缓冲。
 *
 * ### 生命周期
 *
 * 每个 [GraphicsLayer] 都占显存里的一份 display list,必须还给 [GraphicsContext]。淘汰走
 * [endFrame],整体走 [release];调用方要用 `DisposableEffect` 保证 [release] 一定被调到。
 */
internal class DanmakuRenderCache(
    private val measurer: TextMeasurer,
    private val graphicsContext: GraphicsContext,
    private val density: Density,
    private val layoutDirection: LayoutDirection,
    private val style: DanmakuRenderStyle,
    val stats: DanmakuRenderStats,
    private val maxLayers: Int = MAX_LAYERS,
    private val maxLayouts: Int = MAX_LAYOUTS,
) {

    private val strokeStyle: Stroke? = if (style.strokeWidthPx > 0f) {
        Stroke(width = style.strokeWidthPx, miter = 3f, join = StrokeJoin.Round)
    } else {
        null
    }

    /**
     * 录制画布四周的余量。[Stroke] 沿字形轮廓**居中**描,向外溢出半个线宽,而 display list 的
     * 尺寸是按文字 bounds 报的 —— 不留余量的话,底层 RenderNode 一旦按自己的边界裁剪,描边的
     * 外半边就没了。留一圈之后录制时把文字画在 `(pad, pad)`,绘制时反向平移回去,位置不变。
     */
    private val padPx: Int = if (strokeStyle == null) 0 else ceil(style.strokeWidthPx / 2f).toInt() + 1

    /** 按字号缓存解析后的 [TextStyle]。字号实际只有几档,这张表最多几个条目。 */
    private val styleByFontSize = HashMap<Float, TextStyle>()

    // accessOrder = true:查询即"最近使用",淘汰从表头开始。
    private val layouts = LinkedHashMap<DanmakuLayoutKey, TextLayoutResult>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private val layers = LinkedHashMap<DanmakuLayerKey, LayerEntry>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    private var frameId = 0L
    private var released = false

    private class LayerEntry(val layer: GraphicsLayer, var lastUsedFrame: Long)

    /** 绘制一帧的开始。帧号是[endFrame] 判断"这条本帧还在用、不能回收"的依据。 */
    fun beginFrame() {
        frameId++
    }

    /**
     * 准备一条弹幕:排版 + 录制 display list。已经准备过就只更新 LRU 位置。
     * 返回是否发生了真实的准备工作,预热循环用它扣预算。
     */
    fun prepare(danmaku: Danmaku): Boolean {
        if (released) return false
        val key = layerKeyOf(danmaku, style)
        val existing = layers[key]
        if (existing != null) {
            existing.lastUsedFrame = frameId
            return false
        }
        create(key, danmaku)
        return true
    }

    /**
     * 画一条已准备好的弹幕。没准备过就当场补一份 —— 掉一条弹幕比多花一帧的钱糟糕得多,
     * 但这条路径会记进 [DanmakuRenderStats.latePrepareCount],让"预热没跟上"是可观测的。
     */
    fun draw(scope: DrawScope, danmaku: Danmaku, x: Float, y: Float) {
        if (released) return
        val key = layerKeyOf(danmaku, style)
        val entry = layers[key] ?: run {
            stats.onLatePrepare()
            create(key, danmaku)
        }
        entry.lastUsedFrame = frameId
        stats.onLayerReused()
        val pad = padPx.toFloat()
        scope.translate(x - pad, y - pad) {
            drawLayer(entry.layer)
        }
    }

    /** 绘制一帧的结束:把超出上限的条目淘汰掉。本帧用过的绝不回收。 */
    fun endFrame() {
        trimLayers()
        trimLayouts()
    }

    /** 释放全部 [GraphicsLayer]。调用后这个实例不再可用。 */
    fun release() {
        if (released) return
        released = true
        for (entry in layers.values) {
            graphicsContext.releaseGraphicsLayer(entry.layer)
            stats.onLayerReleased(0)
        }
        layers.clear()
        layouts.clear()
        styleByFontSize.clear()
    }

    private fun create(key: DanmakuLayerKey, danmaku: Danmaku): LayerEntry {
        val layout = layoutOf(danmaku)
        val layer = graphicsContext.createGraphicsLayer()
        // danmaku.color 是不带 alpha 的 24 位 RGB,直接塞进 Color(Int) 会被当成 0x00RRGGBB
        // (alpha=0,全透明),必须先把 alpha 字节填满。
        record(layer, layout, Color(danmaku.color or ALPHA_OPAQUE_MASK))
        val entry = LayerEntry(layer, frameId)
        layers[key] = entry
        stats.onLayerCreated(layers.size)
        return entry
    }

    /**
     * 录制描边 + 填充两遍。
     *
     * **第二遍必须显式传 `drawStyle = Fill`,不能省略。** `drawText` 的 `drawStyle` 默认值是
     * `null`,语义是"不覆盖底层 paragraph 已经设过的绘制方式",不是"用 Fill" —— 两遍共享同一份
     * [TextLayoutResult],第一遍把 `Stroke` 设进了底层 paragraph,第二遍不传就会继续描边,
     * 肉眼看是空心字。凡是共享 [TextLayoutResult] 做多遍绘制的地方都有这个坑。
     */
    private fun record(layer: GraphicsLayer, layout: TextLayoutResult, color: Color) {
        val size = IntSize(layout.size.width + padPx * 2, layout.size.height + padPx * 2)
        val topLeft = Offset(padPx.toFloat(), padPx.toFloat())
        val stroke = strokeStyle
        layer.record(density, layoutDirection, size) {
            if (stroke != null) {
                drawText(
                    textLayoutResult = layout,
                    color = style.strokeColor,
                    topLeft = topLeft,
                    alpha = style.opacity,
                    drawStyle = stroke,
                )
            }
            drawText(
                textLayoutResult = layout,
                color = color,
                topLeft = topLeft,
                alpha = style.opacity,
                drawStyle = Fill,
            )
        }
    }

    private fun layoutOf(danmaku: Danmaku): TextLayoutResult {
        val key = layoutKeyOf(danmaku, style, styleByFontSize)
        layouts[key]?.let {
            stats.onLayoutHit()
            return it
        }
        stats.onLayoutMiss()
        val layout = measurer.measure(text = danmaku.text, style = key.style)
        layouts[key] = layout
        return layout
    }

    private fun trimLayers() {
        if (layers.size <= maxLayers) return
        val iterator = layers.entries.iterator()
        while (iterator.hasNext() && layers.size > maxLayers) {
            val entry = iterator.next().value
            // 本帧画过的不能回收:上限低于同屏条数时才会撞到这条,那时宁可暂时超限。
            if (entry.lastUsedFrame >= frameId) continue
            graphicsContext.releaseGraphicsLayer(entry.layer)
            iterator.remove()
            stats.onLayerReleased(layers.size)
        }
    }

    /**
     * 排版表按纯 LRU 淘汰,不看帧号:被淘汰的 [TextLayoutResult] 如果还有 display list 在用,
     * 那份 display list 早就录完了,不再需要排版对象 —— 淘汰只意味着下次同文本要重测一次。
     */
    private fun trimLayouts() {
        if (layouts.size <= maxLayouts) return
        val iterator = layouts.keys.iterator()
        while (iterator.hasNext() && layouts.size > maxLayouts) {
            iterator.next()
            iterator.remove()
        }
    }

    companion object {
        /**
         * display list 上限。要明显大于峰值同屏条数(实测 1264×2780 面板、不限密度档的峰值是
         * 320),否则每帧都在淘汰刚画过的东西 —— 那比不缓存还糟。预热窗口里还有一批未上屏的,
         * 所以按峰值的一倍多给。display list 存的是绘制命令不是位图,单条很小。
         */
        const val MAX_LAYERS = 768

        /** 排版表上限。同屏 + 预热之外还留一段最近使用窗口,让来回 seek 不必反复重测。 */
        const val MAX_LAYOUTS = 1024

        private const val INITIAL_CAPACITY = 128
        private const val LOAD_FACTOR = 0.75f
        private const val ALPHA_OPAQUE_MASK = 0xFF000000.toInt()
    }
}

/** 弹幕自身字号优先,其次全局字号,都没有就回落到 `baseTextStyle` 自带的字号(返回 null)。 */
internal fun fontSizeSpOf(danmaku: Danmaku, style: DanmakuRenderStyle): Float? =
    danmaku.fontSize?.toFloat() ?: style.globalFontSizeSp

/**
 * 解析出这条弹幕真正用来排版的 [TextStyle]。[styleByFontSize] 是可选的复用表:`copy` 本身不贵,
 * 但字号实际只有几档,存下来就把准备路径上的这点分配清零了;不传(测试里)只是每次新建一份,
 * 结果完全相同 —— 这一点是 key 正确性的前提,[TextStyle] 的相等性不看身份。
 */
internal fun resolvedTextStyleOf(
    danmaku: Danmaku,
    style: DanmakuRenderStyle,
    styleByFontSize: MutableMap<Float, TextStyle>? = null,
): TextStyle {
    val fontSizeSp = fontSizeSpOf(danmaku, style) ?: return style.baseTextStyle
    val build = { style.baseTextStyle.copy(fontSize = fontSizeSp.sp) }
    return styleByFontSize?.getOrPut(fontSizeSp, build) ?: build()
}

internal fun layoutKeyOf(
    danmaku: Danmaku,
    style: DanmakuRenderStyle,
    styleByFontSize: MutableMap<Float, TextStyle>? = null,
): DanmakuLayoutKey = DanmakuLayoutKey(danmaku.text, resolvedTextStyleOf(danmaku, style, styleByFontSize))

internal fun layerKeyOf(danmaku: Danmaku, style: DanmakuRenderStyle): DanmakuLayerKey =
    DanmakuLayerKey(danmaku.text, fontSizeSpOf(danmaku, style), danmaku.color)
