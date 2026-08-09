package dev.bilby.danmaku

import dev.bilby.BiliLog
import dev.danmaku.compose.SpecialDanmaku
import dev.danmaku.compose.SpecialDanmakuEasing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * B 站 mode 7(定位/运动弹幕)的 `content` 解析。
 *
 * `content` 不是普通文本,是一个 14 元 JSON 数组。这份下标映射来自 PiliPlus 依赖的
 * `canvas_danmaku`(`SpecialDanmakuContentItem.fromList`,MIT,原作者 Predidit),不是从公开
 * 文档推的——按 CLAUDE.md 的规矩,接口行为以 PiliPlus 实际发/收的为准:
 *
 * | 下标 | 含义 |
 * |---:|---|
 * | 0 / 1 | 起点 x / y,绝对像素 |
 * | 2 | alpha,`"起始-结束"` 形如 `"1-0"` |
 * | 3 | 存活时长,**秒** |
 * | 4 | 文本 |
 * | 5 / 6 | 绕 z / y 轴旋转,度 |
 * | 7 / 8 | 终点 x / y |
 * | 9 / 10 | 位移时长 / 位移前延迟,毫秒 |
 * | 11 | 是否描边,1/0 |
 * | 12 | 字体名,解析但不使用 |
 * | 13 | 缓动,1 = easeInCubic,其余线性 |
 *
 * **坐标写在 1920×1080 参考画幅里**(PiliPlus 那边的 `videoX`/`videoY` 默认就是这两个数),
 * 所以这里归一化之后再交给库——`:danmaku` 不该知道 1920 这个数字,正如它不该知道模式号 7。
 *
 * **解析失败一律留一行日志,不静默丢。** 这是 [BiliLog] 那条纪律在这里的具体形态:mode 7 的
 * 数组格式是站方定的、会变,格式一变而我们整块 `catch` 吞掉的话,现象只有"高级弹幕不显示了",
 * 指不出任何原因。PiliPlus 上游正是 `try { } catch (_) {}`,这里不照抄。日志带下标和字段数,
 * 不带完整 `content`——单条 mode 7 的数组可以很长。
 */
internal fun RawDanmakuElem.toSpecialDanmakuOrNull(index: Int): SpecialDanmaku? {
    val array = runCatching { specialJson.parseToJsonElement(content) as? JsonArray }.getOrNull()
    if (array == null) {
        BiliLog.w("高级弹幕解析失败 index=$index reason=content 不是 JSON 数组 length=${content.length}")
        return null
    }
    // 12 而不是 14:0~11 是位置、时长、文本这些缺一不可的字段,12(字体)本来就不用,
    // 13(缓动)缺省当线性——为了两个可有可无的尾字段丢掉整条弹幕不划算。
    if (array.size < REQUIRED_FIELD_COUNT) {
        BiliLog.w("高级弹幕解析失败 index=$index reason=字段数不足 size=${array.size}")
        return null
    }

    val text = array.stringAt(TEXT)?.trim().orEmpty()
    if (text.isEmpty()) {
        BiliLog.w("高级弹幕解析失败 index=$index reason=文本为空 size=${array.size}")
        return null
    }

    val durationSeconds = array.floatAt(DURATION_SECONDS)
    val fromX = array.relativeAt(START_X, REFERENCE_WIDTH)
    val fromY = array.relativeAt(START_Y, REFERENCE_HEIGHT)
    if (durationSeconds == null || fromX == null || fromY == null) {
        BiliLog.w("高级弹幕解析失败 index=$index reason=起点或时长不是有效数字 size=${array.size}")
        return null
    }
    // 终点坏了不丢整条:退化成"停在起点不动"。能放到画面上的弹幕就该放上去,
    // 判据跟下面 alpha 那条一样——只有连位置都定不下来时才真的画不出来。
    val toX = array.relativeAt(END_X, REFERENCE_WIDTH) ?: fromX
    val toY = array.relativeAt(END_Y, REFERENCE_HEIGHT) ?: fromY
    val durationMillis = (durationSeconds * 1000f).toLong()
    if (durationMillis <= 0L) {
        BiliLog.w("高级弹幕解析失败 index=$index reason=时长非正 durationMillis=$durationMillis")
        return null
    }

    // alpha 坏了不丢整条:文本和位置都还在,这条弹幕仍然该出现在画面上,只是不做淡入淡出。
    // 上面那几个字段坏了才是真的画不出来。
    val alpha = array.stringAt(ALPHA)?.let(::parseAlphaPair)
    if (alpha == null) {
        BiliLog.w("高级弹幕 alpha 字段异常,按不透明处理 index=$index size=${array.size}")
    }

    return SpecialDanmaku(
        id = renderId(),
        text = text,
        color = color,
        startTimeMillis = progressMillis.toLong(),
        durationMillis = durationMillis,
        fromX = fromX,
        fromY = fromY,
        toX = toX,
        toY = toY,
        // 位移时长和延迟本来就是毫秒,跟下标 3 的秒不同单位——这不是笔误,是站方的格式如此。
        translationDelayMillis = array.floatAt(TRANSLATION_DELAY_MILLIS)?.toLong()?.coerceAtLeast(0L) ?: 0L,
        translationDurationMillis = array.floatAt(TRANSLATION_DURATION_MILLIS)?.toLong()?.coerceAtLeast(0L) ?: 0L,
        fromAlpha = alpha?.first ?: 1f,
        toAlpha = alpha?.second ?: 1f,
        rotateZDegrees = array.floatAt(ROTATE_Z) ?: 0f,
        rotateYDegrees = array.floatAt(ROTATE_Y) ?: 0f,
        // 协议字号跟普通弹幕是同一个 tag,基准 25、单位是 1920×1080 参考画幅里的 CSS px
        // (见 DanmakuMapper 里那段说明)。普通弹幕丢掉它是因为绝对位置由引擎排,字号该由渲染层
        // 定;这里恰恰相反——作者按某个确定字号排的版,不用他的字号,他排好的对齐就散了。
        fontSizeFraction = fontSize.takeIf { it > 0 }?.let { it / REFERENCE_HEIGHT }
            ?: SpecialDanmaku.DEFAULT_FONT_SIZE_FRACTION,
        hasStroke = (array.floatAt(HAS_STROKE) ?: 1f) != 0f,
        easing = if (array.floatAt(EASING)?.toInt() == EASING_IN_CUBIC) {
            SpecialDanmakuEasing.EASE_IN_CUBIC
        } else {
            SpecialDanmakuEasing.LINEAR
        },
    )
}

/**
 * `"1-0"` 这种一对起止值。两端都必须是有效数字,否则返回 null 交给调用方记日志。
 *
 * 从 `'-'` **最后一次**出现的位置切,不是第一次:两端可以是负数(`"-0.2-1"`),按第一次切会
 * 把负号当分隔符。
 */
private fun parseAlphaPair(raw: String): Pair<Float, Float>? {
    val separator = raw.lastIndexOf('-')
    // 单个数字是合法写法,表示整条弹幕恒定不透明度,不是格式错误。原先一律要求有 '-',
    // 真机上因此整片刷"alpha 字段异常"——那不是数据坏,是解析器过严。
    if (separator <= 0) {
        val single = raw.trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        return single.coerceIn(0f, 1f) to single.coerceIn(0f, 1f)
    }
    val from = raw.substring(0, separator).trim().toFloatOrNull() ?: return null
    val to = raw.substring(separator + 1).trim().toFloatOrNull() ?: return null
    if (!from.isFinite() || !to.isFinite()) return null
    return from.coerceIn(0f, 1f) to to.coerceIn(0f, 1f)
}

/**
 * 数组元素既可能是裸数字也可能是带引号的字符串(同一个下标在不同弹幕里两种都见得到),
 * [JsonPrimitive.content] 两种都拿得到原始字面量,统一按它取。
 *
 * NaN / Infinity 一律当解析失败:它们会顺着归一化一路传到 `graphicsLayer` 的变换矩阵里,
 * 表现是整条弹幕连同它的图层一起消失,而不是"这个字段没生效"。
 */
private fun JsonArray.floatAt(index: Int): Float? =
    (getOrNull(index) as? JsonPrimitive)?.content?.toFloatOrNull()?.takeIf { it.isFinite() }

/**
 * 坐标归一化。**同一个下标有两种写法**,靠数值本身区分(canvas_danmaku 的 `_toRelativePosition`
 * 是同一套判据):
 *
 * - 大于 1 的、或者写成不带小数点的整数字面量 —— 是参考画幅([REFERENCE_WIDTH] ×
 *   [REFERENCE_HEIGHT])里的绝对像素,除以参考边长得到比例;
 * - 其余(带小数点且不超过 1)—— 本来就是 `[0, 1]` 的比例,原样用。
 *
 * "不带小数点的整数字面量" 这条不能省:`1` 和 `1.0` 数值相等但含义相反,前者是第 1 像素
 * (几乎贴左边),后者是画面最右侧。只看数值会把所有写成 `0`/`1` 的绝对坐标当成比例。
 *
 * 结果不夹回 `[0, 1]`:作者常让弹幕从画外飞进来,夹回去等于改内容(裁剪由渲染层负责)。
 */
private fun JsonArray.relativeAt(index: Int, reference: Float): Float? {
    val raw = (getOrNull(index) as? JsonPrimitive)?.content ?: return null
    val value = raw.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
    val absolute = value > 1f || !raw.contains('.')
    return if (absolute) value / reference else value
}

/** 下标 4 的文本里,`/n` 是站方约定的换行转义,不是普通字符。 */
private fun JsonArray.stringAt(index: Int): String? =
    (getOrNull(index) as? JsonPrimitive)?.content?.replace("/n", "\n")

// isLenient:数组里混着裸数字、带引号的数字和不加引号的怪字面量,严格模式会整条抛掉。
private val specialJson = Json { isLenient = true }

private const val REFERENCE_WIDTH = 1920f
private const val REFERENCE_HEIGHT = 1080f
private const val REQUIRED_FIELD_COUNT = 12
private const val EASING_IN_CUBIC = 1

private const val START_X = 0
private const val START_Y = 1
private const val ALPHA = 2
private const val DURATION_SECONDS = 3
private const val TEXT = 4
private const val ROTATE_Z = 5
private const val ROTATE_Y = 6
private const val END_X = 7
private const val END_Y = 8
private const val TRANSLATION_DURATION_MILLIS = 9
private const val TRANSLATION_DELAY_MILLIS = 10
private const val HAS_STROKE = 11
private const val EASING = 13
