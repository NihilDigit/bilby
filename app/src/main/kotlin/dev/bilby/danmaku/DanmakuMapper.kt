package dev.bilby.danmaku

import dev.danmaku.compose.Danmaku
import dev.danmaku.compose.DanmakuMode

/**
 * B 站原始弹幕 -> `:danmaku` 库的中立模型。映射照 notes/danmaku.md §1.4 的模式号定义:
 * 1/2/3 滚动,4 底端,5 顶端,6 逆向(渲染器不支持逆向,退化成滚动 —— 这是 Animeko 那份解析器
 * 的既有先例,不是 Bilby 自己的权宜),7/8/9(高级/代码/BAS)整条跳过,返回 null。
 *
 * 这层映射只在这里出现一次:`DanmakuMode` 只认三态,不认 B 站的原始编号 —— 库不该知道
 * B 站长什么样。
 */
internal fun RawDanmakuElem.toDanmakuOrNull(): Danmaku? {
    val danmakuMode = when (mode) {
        1, 2, 3, 6 -> DanmakuMode.SCROLL
        4 -> DanmakuMode.BOTTOM
        5 -> DanmakuMode.TOP
        else -> return null
    }
    // 渲染层要用 id 做列表 key,idStr 是首选(notes §9.2),id(数字)其次;两个都拿不到的
    // 极端情况(理论上不该出现,但解析器不该因为脏数据崩掉)拼一个内容相关的兜底值。
    val id = idStr.ifEmpty { id.takeIf { it != 0L }?.toString() }
        ?: "$progressMillis:$mode:${content.hashCode()}"
    return Danmaku(
        id = id,
        playTimeMillis = progressMillis.toLong(),
        mode = danmakuMode,
        color = color,
        text = content,
        fontSize = fontSize.takeIf { it > 0 },
        isSelf = false, // Bilby 目前没有发弹幕功能(CLAUDE.md),恒 false。
    )
}
