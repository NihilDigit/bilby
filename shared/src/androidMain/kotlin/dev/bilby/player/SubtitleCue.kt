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
 * 二分而不是线性扫:一条长视频几千条 cue,跟着 500ms 的位置轮询线性扫是白烧 CPU。要求
 * `cues` 按 `fromMillis` 升序——接口本来就是顺序给的,取正文时不重排。
 */
fun List<SubtitleCue>.cueAt(positionMillis: Long): SubtitleCue? {
    val index = indexNear(positionMillis)
    if (index < 0) return null
    val cue = this[index]
    return cue.takeIf { positionMillis < it.toMillis }
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
