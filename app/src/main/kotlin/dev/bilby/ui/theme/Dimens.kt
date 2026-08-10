package dev.bilby.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 间距刻度。M3 的间距系统是 8dp 起步的线性刻度,4dp 及以下算"嵌套单位"(只在组件内部用)。
 * 这里按用途命名而不是按倍数命名(space150 这种)—— 倍数名在设计工具里有用,在代码里读起来
 * 只是把 12 换了个写法,不解释任何东西。
 *
 * 全项目不再出现裸 dp 字面量,唯一的例外是描边宽度和图标尺寸这类由组件规格定死的数。
 */
object Spacing {
    /** 4dp。图标和紧跟其后的文字、角标内部。 */
    val Hair = 4.dp

    /** 8dp。同一条信息内部的行距(标题与 UP 主名之间)。 */
    val Tight = 8.dp

    /** 12dp。并列元素之间(封面与文字块之间)。 */
    val Cozy = 12.dp

    /** 16dp。屏幕左右边距,以及区块之间。M3 compact 断点的推荐页边距就是 16dp。 */
    val Comfortable = 16.dp

    /** 24dp。区块之间需要明显断开时,以及空态/错误态四周。 */
    val Loose = 24.dp

    /** 32dp。整屏居中内容(登录页、空态)与屏幕边缘。 */
    val Spacious = 32.dp
}

/**
 * 窗口断点与几档最大宽度。值直接取 M3 的 window size class,没有自拟的数。
 *
 * 断点判的是**窗口**能铺多宽,不是设备是不是平板:同一台平板分屏之后就该按 compact 排。
 */
object Breakpoints {
    /** 600dp 起是 medium:底部导航换成 navigation rail 的那一档。 */
    val Medium = 600.dp

    /** 840dp 起是 expanded:设置页、个人页在这一档才拆双栏。 */
    val Expanded = 840.dp

    val Large = 1200.dp

    val ExtraLarge = 1600.dp

    /**
     * 正文类页面的最大行长。再宽下去一行要横扫半米,眼睛回行时会跳错行 ——
     * 这是排版上的老结论,不是为了留白好看。
     */
    val ReadableWidth = 1040.dp

    /**
     * 播放画面(和听视频页那张封面)的最大宽度。比 [ReadableWidth] 窄一档:
     * 画面再大也不会更清楚,而人和屏幕的距离没变,视线要来回扫的角度却变大了。
     */
    val MediaWidth = 720.dp

    /**
     * 播放控制条分行的阈值。低于这个宽度,倍速/画质/字幕/弹幕会和时间读数、全屏按钮
     * 挤在一行里互相压扁,所以另起一行摆。
     */
    val StackedControlBar = 480.dp
}

/**
 * 反复出现的布局尺寸。这些不是"间距",是拿定过的版式决定,写在一处才能保证四个列表页
 * (动态、搜索、空间、稍后再看)看起来是同一个列表。
 */
object Dimens {
    /**
     * 列表行里封面的宽度。16:10 下高 80dp,加上下 8dp 内边距,一行 96dp。
     *
     * 128 这个数是跟着标题字号一起重算的。以前标题是 16sp、封面 140dp:
     * 360dp 宽的屏上留给文字的是 360 − 16×2 − 140 − 12 = 176dp,一行 11 个汉字。
     * 标题改成 14sp(与 PiliPlus 的卡片一致)之后,封面收到 128dp 反而更宽松:
     * 360 − 32 − 128 − 12 = 188dp ÷ 14sp ≈ 13 个字,两行 26 字。
     *
     * 换句话说,风格指南里"128dp 换不来一行字"那条结论只在 16sp 下成立。
     * 再改这个数之前把这道算术连着字号一起重做。
     */
    val ListCoverWidth = 128.dp

    /** 队列、助理过程这类次级列表里的小封面。 */
    val CompactCoverWidth = 72.dp

    /** 助理检索过程那一排横向缩略图。 */
    val TraceCardWidth = 120.dp

    /** 列表行里的头像(评论区)。 */
    val AvatarSmall = 36.dp

    /** 播放页 UP 主头像。 */
    val AvatarMedium = 40.dp

    /** 空间页顶部的大头像。 */
    val AvatarLarge = 56.dp

    /**
     * 可点区域的最小边长。M3 与 Android 无障碍规范都取 48dp;
     * material3 的 `minimumInteractiveComponentSize()` 也是按这个数扩的。
     */
    val MinTouchTarget = 48.dp

    /** 行内小图标(跟在文字旁边的)。M3 规定按钮的前后置图标统一 20dp,这里沿用。 */
    val IconInline = 20.dp

    /** 独立图标按钮里的图标。 */
    val IconAction = 24.dp

    /** 登录二维码的边长。 */
    val QrCode = 220.dp

    /**
     * LV 徽章的渲染高度,评论区和空间页头部共用同一档。
     *
     * 比旁边的名字矮一档是有意的:徽章是名字的**附注**,和名字等高会让它读成并列的第二个标识。
     * PiliPlus 的比例也是这样(`level_icon.dart` 默认 height=11,正文 13–14)。
     */
    val LevelBadgeHeight = 11.dp

    /**
     * 播放页里嵌着的队列列表高度。它嵌在可滚动的简介页里,必须有界高度;
     * 定高同时让队列有个固定占位,不会因为条数不同把下面的内容顶来顶去。
     */
    val EmbeddedQueueHeight = 320.dp

    /**
     * 队列的高度下限。横屏或宽屏两栏时可用高度会小很多,队列按窗口高度的三分之一取,
     * 但不能矮到只剩一条 —— 看不见"下一条是什么"的话,这个列表就没有存在的意义了。
     */
    val EmbeddedQueueMinHeight = 160.dp
}
