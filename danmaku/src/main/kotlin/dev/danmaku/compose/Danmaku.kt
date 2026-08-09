package dev.danmaku.compose

/**
 * 弹幕的渲染位置。这一层只认三种,不认服务端的原始模式号(B 站 1/2/3/4/5/6/7/8/9 那一套) ——
 * 号到位置的映射是数据源相关的适配层逻辑,不属于这个中立模型。参照 B 站的映射规则:
 * 1/2/3 -> SCROLL,4 -> BOTTOM,5 -> TOP,6(逆向) 退化成 SCROLL。
 *
 * 7(定位/运动)不在这里,它是 [SpecialDanmaku],走独立的一层:那类弹幕的位置由作者写死,
 * 不选轨、不判碰撞、不受显示区域约束,跟这三种没有一个共同点。8/9(代码/BAS)不支持。
 */
enum class DanmakuMode {
    SCROLL,
    TOP,
    BOTTOM,
}

/**
 * 一条弹幕的中立数据模型,不携带任何数据源或渲染细节。
 *
 * @param id 列表 key、去重,同时是 [DanmakuTimeline] 里所有确定性随机(速度扰动、选轨)的
 *   唯一输入 —— 同一条弹幕(同一个 id)在任意平台、任意两次编译之间必须选出同样的轨道和速度。
 * @param playTimeMillis 弹幕出现的播放进度,单位毫秒。
 * @param color 24 位 RGB。
 * @param fontSize 可空,null 表示跟随调用方的全局字号配置。这一层保留该字段本身就是产品决策:
 *   B 站协议里这个字段一直都在,保留它意味着轨道高度、描边宽度这类布局参数将来能从常量变成
 *   逐条弹幕可变的量,不保留就再也拿不回来了。
 * @param isSelf 是否为本端发出的弹幕,渲染层用于高亮与兜底放置。当前没有任何数据源会产出
 *   `true`(发弹幕功能未实现),但字段本身要留着,不能等到发弹幕功能落地才补。
 */
data class Danmaku(
    val id: String,
    val playTimeMillis: Long,
    val mode: DanmakuMode,
    val color: Int,
    val text: String,
    val fontSize: Int? = null,
    val isSelf: Boolean = false,
)
