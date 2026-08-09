package dev.danmaku.compose

/**
 * **滚动与顶部**弹幕可以落在画布的哪一块,归一化到 `[0, 1]`。
 *
 * 这是一个**布局区域**,不是"轨道数倍率"。上一版只拿 0.5 去除行高算轨道数,画布仍然整块参与
 * 渲染,既不裁剪也不偏移;调成 25% 或 75% 时"只占上面这么多"这句话根本不成立。改成区域之后
 * 顶部弹幕锚区域顶边往下堆,滚动弹幕铺在区域内,两者一起按这个矩形裁剪。
 *
 * **底部弹幕不受它约束,仍然锚画布底边往上堆。** 这不是漏掉的:把底部弹幕收进 `[0, 75%]`,
 * 它就成了"画面四分之三处的弹幕",不再是底部弹幕。用户调这个比例的意图是"别让滚动弹幕糊住
 * 整个画面",不是"把底部弹幕挪上来"。底部弹幕的纵向上限是它自己的
 * [DanmakuLayoutConfig.bottomTrackFraction],跟这里无关。
 *
 * 由此**底部弹幕会和字幕、播放控件抢画面底部那条带**。这是底部弹幕固有的,正解是给它们配
 * 避让区([insets] 就是那个扩展位:把字幕条和控件的占位折算成 inset 灌进来,不必动
 * [DanmakuLayoutConfig] 和调度器),不是把底部弹幕往上推。
 *
 * 档位(25/50/75/100%)是 app 侧的事,这里不做成枚举:枚举一旦写死,加一档就得改库。
 */
data class DanmakuViewport(
    /** 纵向区间上沿,0 是画布顶边。 */
    val topFraction: Float = 0f,
    /** 纵向区间下沿,1 是画布底边。 */
    val bottomFraction: Float = 0.75f,
    val insets: DanmakuInsets = DanmakuInsets.None,
) {
    companion object {
        /**
         * 档位设置的直接表达:从**顶边**起占 [heightFraction] 那么高,余量全部留在下面。
         *
         * 不做成上下各留一半的居中区间:留白的用途是给画面底部让出一条不被滚动弹幕糊住的带,
         * 顶部没有这个需求,居中只会白白牺牲上面的可用高度。
         */
        fun topAnchored(heightFraction: Float, insets: DanmakuInsets = DanmakuInsets.None): DanmakuViewport =
            DanmakuViewport(0f, heightFraction.coerceIn(0f, 1f), insets)
    }
}

/** 四周安全边距,同样按画布尺寸归一化。 */
data class DanmakuInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    companion object {
        val None = DanmakuInsets()
    }
}

/**
 * [DanmakuViewport] 落到具体画布尺寸上的像素矩形。调度器算的每一个横向量(穿屏距离、间距、
 * slack)都以 [width] 为基准,渲染也在这个矩形里裁剪——两边引用同一个对象,不会各算一份。
 */
data class DanmakuViewportPx(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

fun DanmakuViewport.resolve(canvasWidthPx: Float, canvasHeightPx: Float): DanmakuViewportPx {
    val left = canvasWidthPx * insets.left
    val right = canvasWidthPx * (1f - insets.right)
    val top = canvasHeightPx * (topFraction + insets.top)
    val bottom = canvasHeightPx * (bottomFraction - insets.bottom)
    // 边距把区域挤成负数时收敛成零面积,不返回 right < left 的矩形——负宽会让速度公式算出
    // 负速度,弹幕反着飞,而画面上看不出"是边距配错了"。
    return DanmakuViewportPx(
        left = left,
        top = top,
        right = maxOf(left, right),
        bottom = maxOf(top, bottom),
    )
}
