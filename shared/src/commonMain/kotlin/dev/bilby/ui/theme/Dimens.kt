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
     * 宽屏网格里一格视频行的最大宽度,照 PiliPlus `Grid.videoCardHDelegate` 的
     * `smallCardWidth * 2`。行是定宽封面加文字,再宽只是标题行变长;超过它就多分一列。
     */
    val VideoRowMaxWidth = 480.dp

    /**
     * 播放控制条给得起档名(倍速倍数、画质档名、字幕轨名)的最小宽度。低于这一档只留图标。
     *
     * **分不分行不看这个数**,那由控制条把自己这一行量一遍决定(见
     * `ui/video/BilbyPlayer.kt` 的 `PlayerControlBar`):档名是变长的,窗口宽度回答不了
     * "这一行装得下吗"。这个数只回答"要不要试着给档名"。
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
     * 128 这个数是跟着标题字号一起算的。标题是 16sp(bodyLarge):360dp 宽的屏上留给文字的是
     * 360 − 16×2 − 128 − 12 = 188dp ÷ 16sp ≈ 11 个汉字,两行 22 字。
     *
     * **封面从 140dp 收到 128dp 换来的不是更多字,是同样 11 个字排得松一点** ——
     * 140dp 下文字只有 176dp,恰好也是 11 个字。想让两行多放两三个字只有把标题降到 14sp
     * 那一条路(188dp ÷ 14sp ≈ 13 个字),而那个字号让标题和它下面的元信息认不出主次。
     *
     * 再改这个数之前把这道算术连着字号一起重做。
     */
    val ListCoverWidth = 128.dp

    /** 队列、助理过程这类次级列表里的小封面。 */
    val CompactCoverWidth = 72.dp

    /** 助理检索过程那一排横向缩略图。 */
    val TraceCardWidth = 120.dp

    /**
     * 头像四档,**按用途分,不按大小分**:同一个用途在全 app 只有一个尺寸,而"中"和"小"
     * 这种名字挡不住第四档被加进来。所有头像都走 [dev.bilby.ui.components.Avatar],
     * 别在别处手搓 `clip(CircleShape)` —— 那样漏掉的是 `ContentScale.Crop`,
     * 表现是非正方形的头像被拉长,而且只在漏掉的那几处拉长。
     *
     * 列表行里的一个人:评论、动态、专栏、关注列表、播放页的 UP、直播的舰长。
     * 这一档以前是 36 和 40 两个数,分别叫 Small 和 Medium —— 差 4dp,谁也看不出来,
     * 而两个名字让人以为该有区别。
     */
    val AvatarRow = 36.dp

    /**
     * 楼中楼里的一个人。它排在主楼正文之下、一个底色容器之内,和主楼同一档的话三条回复就是
     * 三个与主楼等大的头像,层级读不出来,正文也只剩半屏宽。预览与展开两种形态都画它,
     * 展开时左缘因此不动(见 `ui/comment/CommentSection.kt` 的 SubReplyRow)。
     */
    val AvatarNested = 24.dp

    /** 成排站着的头像:首页那一排关注、正在直播那一叠的前脸。 */
    val AvatarStack = 48.dp

    /** 页头的大头像:空间页、我的。 */
    val AvatarHeader = 56.dp

    /**
     * 可点区域的最小边长。M3 与 Android 无障碍规范都取 48dp;
     * material3 的 `minimumInteractiveComponentSize()` 也是按这个数扩的。
     */
    val MinTouchTarget = 48.dp

    /** 行内小图标(跟在文字旁边的)。M3 规定按钮的前后置图标统一 20dp,这里沿用。 */
    val IconInline = 20.dp

    /**
     * 「在放」的符号([dev.bilby.ui.components.PlayingIndicator])跟在文字旁边时的尺寸。
     *
     * **它必须由调用方给,`Canvas` 没有固有尺寸** —— 不给就是 0×0,编译和预览都不报错,
     * 真机上那个符号直接不存在。动态卡片的直播格踩过:代码里写着那个调用,
     * 而那一格从来没画出来过。
     */
    val PlayingIndicatorInline = 14.dp

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
}
