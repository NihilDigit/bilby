package dev.bilby.live

/** 正文里的一处表情代号:`text.substring(start, end)` 这一段画成 [emote]。 */
class LiveEmoteMatch(val start: Int, val end: Int, val emote: LiveEmote)

/**
 * 找出正文里所有的表情代号,按出现顺序,互不重叠。
 *
 * 键是服务端给的任意字符串(不是 `[xxx]` 那种固定形状),所以正则由这一批键现拼,拼之前要转义 ——
 * 键里出现 `[` 或 `+` 是会发生的,不转义就是一个语义完全不同的正则。**长的键排在前面**:
 * 短键是长键前缀时,先匹配短的会把长键切成两半。
 */
fun findEmotes(text: String, emotes: Map<String, LiveEmote>): List<LiveEmoteMatch> {
    if (emotes.isEmpty()) return emptyList()
    val pattern = emotes.keys
        .sortedByDescending { it.length }
        .joinToString("|") { Regex.escape(it) }
    val regex = runCatching { Regex(pattern) }.getOrNull() ?: return emptyList()
    return regex.findAll(text).mapNotNull { match ->
        emotes[match.value]?.let { LiveEmoteMatch(match.range.first, match.range.last + 1, it) }
    }.toList()
}
