package dev.bilby.danmaku

import dev.bilby.BiliLog
import dev.nihildigit.danmaku.Danmaku
import dev.nihildigit.danmaku.DanmakuMode
import dev.nihildigit.danmaku.SpecialDanmaku

/**
 * 一个分段映射完的结果。两类弹幕分开返回,不合成一个列表:它们走两条互不相干的渲染路径
 * ——普通弹幕要排轨道、判碰撞、受显示区域裁剪,高级弹幕的位置是作者写死的,三样都不适用
 * (见 [SpecialDanmaku])。合成一个列表只会让下游立刻再按类型拆回来。
 */
data class DanmakuSegment(
    val normal: List<Danmaku>,
    val special: List<SpecialDanmaku>,
)

/**
 * 一个分段的原始弹幕 -> 两类中立模型。模式号照 notes/danmaku.md §1.4:1/2/3 滚动,4 底端,
 * 5 顶端,6 逆向(渲染器不支持逆向,退化成滚动 —— 这是 Animeko 那份解析器的既有先例,不是
 * Bilby 自己的权宜),7 走高级弹幕那条独立的路,8(代码)/9(BAS)不支持。
 *
 * **8 和 9 是明确不做,不是还没做**:它们的内容是可执行脚本,在通用渲染库里执行来源不明的
 * 脚本是不可接受的(gap 分析 3.5)。丢弃时按分段汇总记一次数,不逐条刷屏。
 */
internal fun List<RawDanmakuElem>.toMappedSegment(): DanmakuSegment {
    val normal = ArrayList<Danmaku>(size)
    val special = mutableListOf<SpecialDanmaku>()
    var unsupportedScriptCount = 0
    forEachIndexed { index, elem ->
        when (elem.mode) {
            SPECIAL_MODE -> elem.toSpecialDanmakuOrNull(index)?.let(special::add)
            CODE_MODE, BAS_MODE -> unsupportedScriptCount++
            else -> elem.toDanmakuOrNull()?.let(normal::add)
        }
    }
    if (unsupportedScriptCount > 0) {
        BiliLog.d("丢弃脚本类弹幕(mode 8/9,明确不支持) count=$unsupportedScriptCount")
    }
    return DanmakuSegment(normal, special)
}

private const val SPECIAL_MODE = 7
private const val CODE_MODE = 8
private const val BAS_MODE = 9

/**
 * 渲染层要用 id 做列表 key,idStr 是首选(notes §9.2),id(数字)其次;两个都拿不到的
 * 极端情况(理论上不该出现,但解析器不该因为脏数据崩掉)拼一个内容相关的兜底值。
 */
internal fun RawDanmakuElem.renderId(): String =
    idStr.ifEmpty { id.takeIf { it != 0L }?.toString() }
        ?: "$progressMillis:$mode:${content.hashCode()}"

/**
 * B 站的模式号 → 中立三态。**这一步是适配层的活**,库那边只认三种位置。
 *
 * 1/2/3 都是滚动;6 是逆向滚动,退化成普通滚动(不实现反向);4 底部、5 顶部。
 * 7(定位/运动)走 [SpecialDanmakuParser] 那条独立的路,8/9(代码/BAS)不支持,都返回 null。
 *
 * 点播与直播共用它:两边拿到的模式号是同一套编码。
 */
internal fun danmakuModeOrNull(mode: Int): DanmakuMode? = when (mode) {
    1, 2, 3, 6 -> DanmakuMode.SCROLL
    4 -> DanmakuMode.BOTTOM
    5 -> DanmakuMode.TOP
    else -> null
}

/**
 * 单条普通弹幕的映射。`DanmakuMode` 只认三态,不认 B 站的原始编号 —— 库不该知道 B 站长什么样。
 * 认不出来的模式号返回 null。
 */
internal fun RawDanmakuElem.toDanmakuOrNull(): Danmaku? {
    val danmakuMode = danmakuModeOrNull(mode) ?: return null
    return Danmaku(
        id = renderId(),
        playTimeMillis = progressMillis.toLong(),
        mode = danmakuMode,
        color = color,
        text = content,
        // 恒 null,不是漏填。protobuf tag 4 这个 fontSize 是 B 站网页播放器自己的字号档位
        // (18/25/36 = 小/标准/大),单位是以 25 为基准的 CSS px,不是 sp——真实响应量过,
        // 标准弹幕报的就是 25。当成 sp 直接喂给 Compose 的 TextStyle.fontSize,在高密度屏上
        // 是几十上百物理像素的字,一条弹幕能盖过标题。字号基准该由渲染层按画面尺寸定
        // (BilbyPlayer.kt),不是抄一个 B 站自己都没说清单位的数字。字段本身保留:
        // 将来做"大小弹幕分级"时用得上,但要按相对倍率(biliSize / 25f)乘渲染层的基准字号,
        // 不是把这个数字原样当绝对值用。
        fontSize = null,
        isSelf = false, // Bilby 目前没有发弹幕功能(CLAUDE.md),恒 false。
    )
}
