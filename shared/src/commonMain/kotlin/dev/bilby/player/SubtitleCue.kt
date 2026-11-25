package dev.bilby.player

/** 一条字幕轨的可选项,给 UI 的下拉菜单用。 */
data class SubtitleTrack(
    /** 语言代码,如 `ai-zh`。用它在轨与用户偏好之间对号,不用展示名——那个会跟着 AI 后缀变。 */
    val lan: String,
    /** 已经拼好「（AI）」后缀的展示名,照 PiliPlus `subtitle.dart` 的做法。 */
    val displayName: String,
    val isAi: Boolean,
    val subtitleUrl: String,
)

/** 一条字幕。[fromMillis]/[toMillis] 左闭右开,`text` 已经 trim 过。 */
data class SubtitleCue(val fromMillis: Long, val toMillis: Long, val text: String)

/**
 * 播放位置命中的那条字幕,`cues` 落在两条之间的空档里(常见,句与句之间有停顿)时返回 null。
 *
 * 二分而不是线性扫:一条长视频几千条 cue,跟着位置轮询线性扫是白烧 CPU。要求
 * `cues` 按 `fromMillis` 升序——接口本来就是顺序给的,取正文时不重排。
 * 若存在长短字幕重叠(例如较长外语行覆盖一条短注释),回溯寻找仍在有效期内的字幕,避免前一条提前消失。
 */
fun List<SubtitleCue>.cueAt(positionMillis: Long): SubtitleCue? {
    val index = indexNear(positionMillis)
    if (index < 0) return null
    val cue = this[index]
    if (positionMillis < cue.toMillis) return cue
    return cuesAt(positionMillis).lastOrNull()
}

/** 整条字幕轨中最长单条时长,用于限制 [cuesAt] 向前扫描重叠字幕的窗口。 */
fun List<SubtitleCue>.maxCueDurationMillis(): Long =
    maxOfOrNull { (it.toMillis - it.fromMillis).coerceAtLeast(0L) } ?: 0L

/**
 * 返回播放位置 [positionMillis] 时刻所有生效中的字幕(按原序列升序),
 * 正确处理双语/多说话人同时段或长短字幕重叠,不因后开始的短字幕结束而截断前一条。
 */
fun List<SubtitleCue>.cuesAt(
    positionMillis: Long,
    maxCueDurationMillis: Long = maxCueDurationMillis(),
): List<SubtitleCue> {
    val index = indexNear(positionMillis)
    if (index < 0) return emptyList()
    val earliestFrom = positionMillis - maxCueDurationMillis.coerceAtLeast(0L)
    val active = mutableListOf<SubtitleCue>()
    var i = index
    while (i >= 0) {
        val cue = this[i]
        if (cue.fromMillis < earliestFrom) break
        if (positionMillis >= cue.fromMillis && positionMillis < cue.toMillis) {
            active.add(cue)
        }
        i--
    }
    if (active.size > 1) active.reverse()
    return active
}

/** 将同一时刻生效的多条字幕拼成展示文本,连续重复内容去重;无生效字幕时返回 null。 */
fun List<SubtitleCue>.activeSubtitleText(
    positionMillis: Long,
    maxCueDurationMillis: Long = maxCueDurationMillis(),
): String? {
    val active = cuesAt(positionMillis, maxCueDurationMillis)
    if (active.isEmpty()) return null
    if (active.size == 1) return active[0].text
    val lines = mutableListOf<String>()
    for (cue in active) {
        if (lines.lastOrNull() != cue.text) lines.add(cue.text)
    }
    return lines.joinToString("\n")
}

/**
 * 按用户偏好的语言代码挑选最匹配字幕轨:
 * - [preferredLan] 为空时严格返回 null(保持关闭);
 * - 优先精确匹配 `lan`;
 * - 若无精确匹配(例如上次选了 `ai-zh`,新视频只有 `zh-CN`/`zh-Hans`),按同语言家族回退,
 *   保持人工字幕优先于 AI 字幕(列表已由 `SubtitleRepository` 排序)。
 */
fun List<SubtitleTrack>.findBestMatch(preferredLan: String): SubtitleTrack? {
    if (preferredLan.isBlank() || isEmpty()) return null
    firstOrNull { it.lan == preferredLan }?.let { return it }
    val targetFamily = subtitleLanguageFamily(preferredLan)
    if (targetFamily.isEmpty()) return null
    return firstOrNull { subtitleLanguageFamily(it.lan) == targetFamily }
}

private fun subtitleLanguageFamily(lan: String): String {
    val normalized = lan.trim().lowercase().removePrefix("ai-")
    return normalized.substringBefore('-').substringBefore('_')
}

/**
 * 二分定位:`fromMillis <= positionMillis` 的最后一条的下标。用于文稿跟播滚动——
 * 位置落在两条字幕之间的空档里时,[cueAt] 会返回 null,但滚动条不该停在原地等下一句,
 * 该跟着往前挪到刚讲完的那一句。空列表或位置早于第一条字幕时返回 -1。
 */
fun List<SubtitleCue>.indexNear(positionMillis: Long): Int {
    if (isEmpty()) return -1
    var lo = 0
    var hi = size - 1
    var result = -1
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (this[mid].fromMillis <= positionMillis) {
            result = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return result
}
