package dev.danmaku.compose

/**
 * 位移插值方式。只有这两种是因为数据源(B 站 mode 7)只表达得出这两种,不是这一层的能力上限。
 */
enum class SpecialDanmakuEasing {
    LINEAR,
    EASE_IN_CUBIC,
}

/**
 * 定位/运动弹幕:位置由**作者**写死,不由引擎排布。
 *
 * 它跟 [Danmaku] 是两种东西,不是 [Danmaku] 加几个字段。滚动/顶部/底部弹幕的位置是引擎排出来的
 * 结果(选轨、算速度、判碰撞);这一类的位置是内容的一部分,作者按某个参考画幅摆好了,引擎唯一
 * 该做的是把参考画幅映射到实际画布。两者放进同一个模型,滚动弹幕就要背上关键帧、旋转、alpha
 * 曲线这一堆永远为空的字段,调度器和渲染器还得为它开分支——所以这里独立成层,也独立成模型。
 *
 * **坐标已经归一化到 `[0, 1]`,参考画幅在这一层不存在。** 数据源那边是绝对像素(B 站按
 * 1920×1080 写),换算是 adapter 的事:库不该知道 1920 这个数字,正如它不该知道模式号 7。
 * 归一化值允许超出 `[0, 1]`——作者常让弹幕从画面外飞进来,把它夹回来就是改内容。
 *
 * **不受 [DanmakuViewport] 约束。** 判据跟底部弹幕那条完全一样:位置是引擎排的就该收进显示
 * 区域,是作者指定的就不能收。用户把显示区域调到 50% 的意图是"别让滚动弹幕糊住整个画面",
 * 不是"把作者摆在画面下半部的字挪上去"——收进 viewport 等于按比例揉烂整幅编排,原地毁掉
 * 作者的意图。
 *
 * @param id 列表 key 与去重用。这一层不做任何确定性随机(位置全部由作者给定),所以 id 的
 *   职责比 [Danmaku.id] 轻。
 * @param startTimeMillis 出现时刻的播放进度。
 * @param durationMillis 总寿命。位移可以早于寿命结束(见 [translationDurationMillis]),
 *   alpha 曲线则铺满整个寿命。
 * @param fromX 起点 x,画布宽度的比例。
 * @param fromY 起点 y,画布高度的比例。y 轴向下。
 * @param toX 终点 x。
 * @param toY 终点 y。
 * @param translationDelayMillis 出现后静止多久才开始位移。
 * @param translationDurationMillis 位移本身占用的时长。为 0 表示延迟结束的那一刻直接跳到终点。
 * @param fromAlpha 寿命起点的不透明度。
 * @param toAlpha 寿命终点的不透明度。
 * @param rotateZDegrees 绕 z 轴旋转,平面旋转。
 * @param rotateYDegrees 绕 y 轴旋转。**这是带透视的 3D 旋转**,不是平面斜切:mode 7 用它做
 *   "字幕向画面深处倒下去"这类效果,当成平面变换画出来是另一个图形。渲染层必须走
 *   `graphicsLayer` 并给出 cameraDistance。
 * @param fontSizeFraction 字号占画布**高度**的比例。不跟随用户的全局弹幕字号设置:作者是按
 *   一个确定的字号摆的版,字号一变,行宽跟着变,他排好的对齐关系就散了。按高度而不是宽度归一,
 *   是为了让字在不同宽高比下不被拉扁——位置按各自的轴归一,字号只能挑一个轴。
 * @param hasStroke 是否描边。作者可以关掉:mode 7 常见的用法是把弹幕当画面元素(色块上的标题、
 *   贴边的注释),描边在那些地方是脏东西。
 * @param easing 位移的插值方式,只作用于位移,不作用于 alpha。
 */
data class SpecialDanmaku(
    val id: String,
    val text: String,
    val color: Int,
    val startTimeMillis: Long,
    val durationMillis: Long,
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val translationDelayMillis: Long = 0L,
    val translationDurationMillis: Long = 0L,
    val fromAlpha: Float = 1f,
    val toAlpha: Float = 1f,
    val rotateZDegrees: Float = 0f,
    val rotateYDegrees: Float = 0f,
    val fontSizeFraction: Float = DEFAULT_FONT_SIZE_FRACTION,
    val hasStroke: Boolean = true,
    val easing: SpecialDanmakuEasing = SpecialDanmakuEasing.LINEAR,
) {
    /** 寿命终点(不含)。 */
    val endTimeMillis: Long get() = startTimeMillis + durationMillis

    companion object {
        /** 25/1080:B 站标准字号在 1080 高参考画幅里的占比,adapter 拿不到字号时的兜底。 */
        const val DEFAULT_FONT_SIZE_FRACTION: Float = 25f / 1080f
    }
}

/** 某个播放时刻求值出来的即时状态。位置归一化,含义同 [SpecialDanmaku.fromX] / [SpecialDanmaku.fromY]。 */
data class SpecialDanmakuMotion(
    val x: Float,
    val y: Float,
    val alpha: Float,
)

/**
 * 给定播放进度求这条弹幕的即时状态;不在寿命区间内返回 null。
 *
 * **没有逐条动画状态,也没有预计算缓存。** 整条弹幕是时间的纯函数,求一次就出结果,seek、变速、
 * 暂停因此不需要任何同步逻辑。一个视频里这类弹幕通常是个位数到几十条,跟滚动弹幕的几千条差两三
 * 个数量级——给它加缓存的成本会超过它省下的乘法。
 */
fun SpecialDanmaku.motionAt(playTimeMillis: Long): SpecialDanmakuMotion? {
    if (durationMillis <= 0L) return null
    val elapsed = playTimeMillis - startTimeMillis
    if (elapsed < 0L || elapsed >= durationMillis) return null

    val moved = elapsed - translationDelayMillis
    val rawProgress = when {
        moved < 0L -> 0f
        // 顺序不能跟下一条对调:位移时长为 0 时,延迟结束那一刻(moved == 0)就该在终点,
        // 按 `moved >= duration` 判也得到 1,但除法会先炸。
        translationDurationMillis <= 0L -> 1f
        moved >= translationDurationMillis -> 1f
        else -> moved.toFloat() / translationDurationMillis
    }
    val progress = when (easing) {
        SpecialDanmakuEasing.LINEAR -> rawProgress
        SpecialDanmakuEasing.EASE_IN_CUBIC -> rawProgress * rawProgress * rawProgress
    }

    val alphaProgress = elapsed.toFloat() / durationMillis
    return SpecialDanmakuMotion(
        x = lerp(fromX, toX, progress),
        y = lerp(fromY, toY, progress),
        alpha = lerp(fromAlpha, toAlpha, alphaProgress).coerceIn(0f, 1f),
    )
}

private fun lerp(from: Float, to: Float, progress: Float): Float = from + (to - from) * progress
