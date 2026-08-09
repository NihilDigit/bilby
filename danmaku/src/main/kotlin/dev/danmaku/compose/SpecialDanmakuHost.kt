package dev.danmaku.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize

/**
 * [SpecialDanmakuHost] 的状态:一份弹幕列表加一个时钟。
 *
 * 它跟 [DanmakuHostState] 是两个独立的东西,不共享调度器也不共享帧循环。这一层没有轨道、没有
 * 碰撞、没有溢出策略——位置全由内容给定,能被调度的东西一个都没有,所以没有"状态机"可言,
 * [danmaku] 换一份就是全部。
 *
 * [danmaku] 是普通的 Compose state:调用方直接赋值(比如新分段拉回来时追加),不需要通知函数。
 */
@Stable
class SpecialDanmakuHostState(internal val clock: DanmakuClock) {

    // 委托属性不能带自定义 setter(Kotlin 的限制),所以拆成后备属性 + 手写访问器。
    private var poolState by mutableStateOf<List<SpecialDanmaku>>(emptyList())

    /** 整池。赋值时按 [SpecialDanmaku.startTimeMillis] 排好序,[activeDanmaku] 靠这个顺序二分。 */
    var danmaku: List<SpecialDanmaku>
        get() = poolState
        set(value) {
            // **同一个实例反复赋值必须是空操作。** 调用方通常写成
            // `state.danmaku = pool`(见 BilbyPlayer),那一行每次重组都会跑,而重组是很频繁的。
            if (value === poolState) return
            poolState = if (value.isSortedByStart()) value else value.sortedBy { it.startTimeMillis }
            // **不重置 activeBucket。** 这里曾经把它设成哨兵值想"强制重算",但哨兵会让
            // refreshActive() 把区间中心当成 0,活跃集合瞬间坍缩成视频最开头那几秒 —— 当前该
            // 显示的全部消失,下一帧帧循环才恢复。表现就是高级弹幕每次重组闪一下。
            // refreshActive() 本来就按当前桶重算,不需要任何强制。
            refreshActive()
        }

    /**
     * 帧循环写入的当前播放进度。渲染层在 `graphicsLayer` 的 lambda 里读它,那里的 state 读取
     * 只会让图层失效,不触发重组——所以每帧更新它的代价是"图层重新算一次变换矩阵",而不是
     * "整棵子树重组一遍"。
     */
    internal var positionMillis by mutableLongStateOf(0L)

    /**
     * 当前该进组合的那些。**不是整池。**
     *
     * 这一层每条弹幕都是一个已组合节点、各带一个 [androidx.compose.ui.graphics.GraphicsLayer],
     * 而上面那个 `positionMillis` 每帧变一次会让**所有**图层重算变换矩阵——不在寿命内的也一样,
     * 它们只是 alpha=0。几十条时无所谓,真机上撞到了几百条 mode 7 的视频:帧耗时 50th 从 14ms
     * 涨到 21ms、jank 从 10% 涨到 44%,而 GPU 一直是 4ms,全压在主线程算矩阵上。
     *
     * 所以按时间切一刀。**不能每帧切**——那样每帧都要重组一遍子树,比省下来的更贵;按
     * [ACTIVE_BUCKET_MILLIS] 分桶,进度跨桶才重算,重组降到每秒一次量级。
     */
    internal var activeDanmaku: List<SpecialDanmaku> by mutableStateOf(emptyList())
        private set

    private var activeBucket = Long.MIN_VALUE

    /** 进度推进时调用。返回是否真的换了区间(没换就不必让调用方做别的事)。 */
    internal fun onPositionChanged(positionMillis: Long): Boolean {
        val bucket = Math.floorDiv(positionMillis, ACTIVE_BUCKET_MILLIS)
        if (bucket == activeBucket) return false
        activeBucket = bucket
        refreshActive()
        return true
    }

    private fun refreshActive() {
        val pool = poolState
        if (pool.isEmpty()) {
            activeDanmaku = emptyList()
            return
        }
        val center = if (activeBucket == Long.MIN_VALUE) 0L else activeBucket * ACTIVE_BUCKET_MILLIS
        // 区间两端各放宽一个桶:桶内进度会一直走到下一个桶边界,那期间新进场的必须已经在组合里。
        val from = center - ACTIVE_BUCKET_MILLIS
        val until = center + 2 * ACTIVE_BUCKET_MILLIS
        // 起点在 until 之后的一定没进场;起点在 from 之前但还没结束的仍然在屏上,所以从
        // 第一条"结束时间 >= from"的开始扫,不能直接用起点二分的下界。
        val end = pool.lowerBoundByStart(until)
        activeDanmaku = pool.subList(0, end).filter { it.endTimeMillis >= from }
    }
}

/** 第一个 `startTimeMillis >= target` 的下标。 */
private fun List<SpecialDanmaku>.lowerBoundByStart(target: Long): Int {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startTimeMillis < target) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun List<SpecialDanmaku>.isSortedByStart(): Boolean {
    for (i in 1 until size) if (this[i - 1].startTimeMillis > this[i].startTimeMillis) return false
    return true
}

/**
 * 活跃区间的分桶粒度。取 2 秒:mode 7 的 `duration` 常见几秒量级,桶太小会让重组频繁,
 * 太大又会让组合里堆着一批还没进场的。
 */
private const val ACTIVE_BUCKET_MILLIS = 2_000L

/**
 * 定位/运动弹幕(mode 7 一类)的渲染层,独立于滚动/顶部/底部那一层。
 *
 * **它不受 [DanmakuViewport] 约束,也不参与 [DanmakuHost] 的裁剪。** 理由跟底部弹幕那条一样,
 * 见 [SpecialDanmaku] 的文档:位置是作者指定的,不是引擎排的,收进显示区域等于揉烂作者的编排。
 * 调用方应当把它叠在 [DanmakuHost] 之上、铺满整个画面。
 *
 * 每条弹幕是一个自带 `graphicsLayer` 的独立节点,不是画在同一张 Canvas 上:`rotationY` 要的是
 * **带透视的 3D 旋转**,`DrawScope` 的仿射变换给不出来,只有图层能给。代价是一条弹幕一个图层,
 * 这在 mode 7 上是划算的——一个视频里通常个位数到几十条,跟滚动弹幕差两三个数量级。
 *
 * 文字排版走 [TextMeasurer] 自带的 LRU,这里不另建缓存(理由同 [DanmakuHost])。
 *
 * @param style 复用普通弹幕的外观参数,但**只取 `baseTextStyle` 的字体族/字重、描边和
 *   `opacity`**:字号由 [SpecialDanmaku.fontSizeFraction] 决定,不跟随用户的全局字号设置;
 *   是否描边由 [SpecialDanmaku.hasStroke] 决定,`strokeWidthPx <= 0` 时仍然整体关闭。
 */
@Composable
fun SpecialDanmakuHost(
    state: SpecialDanmakuHostState,
    style: DanmakuRenderStyle = DanmakuRenderStyle(),
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 64)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val hasContent = state.danmaku.isNotEmpty()

    // 列表为空时不跑帧循环。绝大多数视频一条 mode 7 都没有,那种情况下这一层应该完全不产生
    // 每帧工作量;这里不像 DanmakuHost 那样在"无可见弹幕"时挂起,是因为判定可见需要先遍历
    // 一遍列表,而列表本身就只有几十条——省下的还不如判定花的多。
    LaunchedEffect(state, hasContent) {
        if (!hasContent) return@LaunchedEffect
        while (true) {
            withFrameMillis { }
            val now = state.clock.positionMillis
            state.positionMillis = now
            // 跨桶才重算活跃区间,见 SpecialDanmakuHostState.activeDanmaku。
            state.onPositionChanged(now)
        }
    }

    // **必须裁到自己的边界。** 下面每条弹幕在这个 Box 里都占 0×0(见 SpecialDanmakuItem 里
    // 那个 layout 块),位置纯靠 graphicsLayer 的 translation 平移出去,而 Compose 默认不裁剪
    // ——节点平移到哪就画到哪。作者又常把坐标写在画外让弹幕飞进来,所以溢出是常态不是异常。
    // 全屏时这个 Box 铺满屏幕,溢出看不出来;退出全屏后它只有视频那一块,不裁的话弹幕会画到
    // 视频外面、盖在页面其它内容上。DanmakuHost 那边是靠自己 clipRect 到视口达到同一效果。
    Box(modifier = modifier.clipToBounds().onSizeChanged { canvasSize = it }) {
        if (canvasSize.width == 0 || canvasSize.height == 0) return@Box
        for (item in state.activeDanmaku) {
            key(item.id) {
                SpecialDanmakuItem(item, state, canvasSize, style, measurer)
            }
        }
    }
}

@Composable
private fun SpecialDanmakuItem(
    danmaku: SpecialDanmaku,
    state: SpecialDanmakuHostState,
    canvasSize: IntSize,
    style: DanmakuRenderStyle,
    measurer: TextMeasurer,
) {
    val density = LocalDensity.current
    val textStyle = remember(style.baseTextStyle, danmaku.fontSizeFraction, canvasSize.height, density) {
        val fontSizePx = danmaku.fontSizeFraction * canvasSize.height
        style.baseTextStyle.copy(fontSize = with(density) { fontSizePx.toSp() })
    }
    val layoutResult = remember(danmaku.text, textStyle, measurer) {
        measurer.measure(text = danmaku.text, style = textStyle)
    }
    val color = remember(danmaku.color) { Color(danmaku.color or ALPHA_OPAQUE_MASK) }
    val strokeStyle = remember(style.strokeWidthPx, danmaku.hasStroke) {
        if (danmaku.hasStroke && style.strokeWidthPx > 0f) {
            Stroke(width = style.strokeWidthPx, miter = 3f, join = StrokeJoin.Round)
        } else {
            null
        }
    }
    val sizeDp = with(density) { layoutResult.size.width.toDp() to layoutResult.size.height.toDp() }

    Canvas(
        modifier = Modifier
            // 自身在父布局里占 0×0,并以无约束尺寸测量下游。缺了这一步,一条比画面还宽的
            // 弹幕(作者常这么干,让它从画外飞进来)会被 Box 的约束压回画面宽度,文字被迫折行
            // ——那是排版被改了,不是位置被改了,画面上看不出是约束干的。
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints())
                layout(0, 0) { placeable.place(0, 0) }
            }
            .size(sizeDp.first, sizeDp.second)
            // 位置、透明度、旋转全在图层里算:这个 lambda 读 state.positionMillis,读取只让
            // 图层失效,不触发重组,所以每帧只重算变换矩阵,文字的 display list 录一次就不动了。
            .graphicsLayer {
                val motion = danmaku.motionAt(state.positionMillis)
                if (motion == null) {
                    alpha = 0f
                    return@graphicsLayer
                }
                alpha = motion.alpha * style.opacity
                // 归一化坐标指的是文字**左上角**在画布上的落点,不是中心。
                translationX = motion.x * canvasSize.width
                translationY = motion.y * canvasSize.height
                rotationZ = danmaku.rotateZDegrees
                rotationY = danmaku.rotateYDegrees
                // cameraDistance 的默认值是 8 像素,对一个几百像素宽的图层来说相当于把相机贴在
                // 字面上:稍一转 rotationY 就会有极端的透视畸变,甚至部分转到相机背后出现绘制
                // 瑕疵。官方建议取"至少跟图层尺寸同量级",这里按画面高度取,画得出透视又不失真。
                cameraDistance = canvasSize.height * CAMERA_DISTANCE_FACTOR
            },
    ) {
        if (strokeStyle != null) {
            drawText(
                textLayoutResult = layoutResult,
                color = style.strokeColor,
                topLeft = Offset.Zero,
                drawStyle = strokeStyle,
            )
        }
        // 必须显式传 Fill:两遍共享同一份 TextLayoutResult,省略参数会沿用上一遍设进底层
        // paragraph 的 Stroke,画出空心字。理由详见 DanmakuHost.drawDanmaku。
        drawText(textLayoutResult = layoutResult, color = color, topLeft = Offset.Zero, drawStyle = Fill)
    }
}

private const val ALPHA_OPAQUE_MASK = 0xFF000000.toInt()

/** 相机距离取画面高度的这个倍数;2 倍在 1080p 上约 2160px,跟常见 mode 7 实现的透视强度接近。 */
private const val CAMERA_DISTANCE_FACTOR = 2f
