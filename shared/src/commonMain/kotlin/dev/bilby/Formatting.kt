package dev.bilby

/**
 * 时长格式化的唯一实现。
 *
 * 放在根包而不是 `ui/`:数据层也要用(空间投稿列表把 `durationText` 当成展示字段直接带出来),
 * 而 `data` 依赖 `ui` 是反的。这里和 [BiliLog] 同级,两边都能引。
 *
 * **统一两位补零,满一小时才出小时位**:`04:05`、`02:50:08`。恒定 `hh:mm:ss` 的话,开头那个
 * 永远是 `00:` 的小时位在每一帧里都占着位置,而绝大多数视频不到一小时 —— 它不携带信息,
 * 只是噪声。服务端部分接口返回的 `"275:48"` 或不补零串在数据入口由 [normalizeDurationText] 规整。
 *
 * 这之前仓库里有六份各自为政的实现(播放器、听视频、播放队列、收藏夹、空间仓库、agent 工具),
 * 其中三份没处理超过一小时的情况,会打出 `85:30` 这种读不出来的东西。
 */
fun formatDurationSeconds(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, rest)
    else "%02d:%02d".format(minutes, rest)
}

/** 播放位置来自播放器,单位是毫秒。截断而不是四舍五入:进度条读数不该比实际位置超前。 */
fun formatDurationMillis(millis: Long): String = formatDurationSeconds(millis / 1000)

/** `"12:34"` / `"275:48"` / `"1:02:03"` 转为秒;无法解析时返回 0。 */
fun parseDurationSeconds(text: String): Long = parseDurationOrNull(text.trim()) ?: 0L

/**
 * 规整服务端返回的时长字符串(如 `"275:48"`、`"4:5"`)为统一补零且按需出小时位的 `(hh:)mm:ss`。
 * 非纯数字冒号串(如空白或文字角标)原样返回。
 */
fun normalizeDurationText(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    return parseDurationOrNull(trimmed)?.let(::formatDurationSeconds) ?: trimmed
}

private fun parseDurationOrNull(trimmed: String): Long? {
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(':')
    if (parts.size !in 2..3) return null
    var total = 0L
    for (part in parts) {
        if (part.isEmpty() || !part.all { it.isDigit() }) return null
        val value = part.toLongOrNull() ?: return null
        total = total * 60 + value
    }
    return total
}
