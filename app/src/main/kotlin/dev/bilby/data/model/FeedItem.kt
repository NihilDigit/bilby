package dev.bilby.data.model

/**
 * 首页动态流一条视频类条目,DTO -> 领域模型映射见 DynamicRepository。
 * 播放量/弹幕数保留 B 站原样的格式化字符串(如 "1.2万"),是否隐藏这类计数
 * 未定案(DESIGN 7 节),UI 层再决定要不要显示。
 */
data class FeedItem(
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val durationText: String,
    val upName: String,
    val upMid: Long,
    val publishedAtEpochSeconds: Long,
    val playCount: String,
    val danmakuCount: String,
)
