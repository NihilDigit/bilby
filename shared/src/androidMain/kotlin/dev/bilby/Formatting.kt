package dev.bilby

/**
 * 时长格式化的唯一实现。
 *
 * 放在根包而不是 `ui/`:数据层也要用(空间投稿列表把 `durationText` 当成展示字段直接带出来),
 * 而 `data` 依赖 `ui` 是反的。这里和 [BiliLog] 同级,两边都能引。
 *
 * **满一小时才出小时位**,不补前导零:`14:43`、`1:02:03`。恒定 `hh:mm:ss` 的话,开头那个
 * 永远是 `00:` 的小时位在每一帧里都占着位置,而绝大多数视频不到一小时 —— 它不携带信息,
 * 只是噪声。分秒位仍然补零,`1:5` 不是时间的读法。
 *
 * 这之前仓库里有六份各自为政的实现(播放器、听视频、播放队列、收藏夹、空间仓库、agent 工具),
 * 其中三份没处理超过一小时的情况,会打出 `85:30` 这种读不出来的东西。
 */
fun formatDurationSeconds(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest)
    else "%d:%02d".format(minutes, rest)
}

/** 播放位置来自播放器,单位是毫秒。截断而不是四舍五入:进度条读数不该比实际位置超前。 */
fun formatDurationMillis(millis: Long): String = formatDurationSeconds(millis / 1000)
