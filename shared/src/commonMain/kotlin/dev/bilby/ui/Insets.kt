package dev.bilby.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp

/**
 * 全面屏适配集中在这个文件的几个定义上。
 *
 * 起因是同一个 `displayCutout.union(systemBars)` 在六处各写了一遍,两处还把 union 的两边写反
 * 了 —— 结果一样,但读的人得先确认这是不是同一件事。更麻烦的是漏写:哪一页忘了躲挖孔,要
 * 横屏跑到那一页才看得见。
 *
 * **挖孔要单独躲,是因为 targetSdk 35 之后 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT` 按 ALWAYS
 * 生效**,窗口不再被系统让开挖孔那一条;而 `Scaffold` 默认消费的
 * `systemBarsForVisualComponents` 不含 `displayCutout`,谁都不会替正文躲开。
 */

/**
 * 压在画面上的控件(返回、全屏顶栏、锁、控制条)要躲开的范围。
 *
 * 用在**媒体之上**,不是用在页面上:画面本身该铺满挖孔和状态栏,躲的是浮在它上面的按钮。
 * 外层若已经 `windowInsetsPadding` 过,这份 inset 已被消费,这里量到 0,不会叠加。
 */
val WindowInsets.Companion.barsAndCutout: WindowInsets
    @Composable get() = systemBars.union(displayCutout)

/**
 * 只取左右两侧的挖孔,给普通页面用。
 *
 * 不带上下:竖屏的挖孔落在状态栏高度之内,连同 `Scaffold` 自己那份 systemBars 一起算就会把
 * 顶部推下去两次。横屏的挖孔在左右,和手势导航条(下边缘)不在同一条边上,两者相加没有重叠。
 */
val WindowInsets.Companion.horizontalCutout: WindowInsets
    @Composable get() = displayCutout.only(WindowInsetsSides.Horizontal)

/**
 * 让掉 `Scaffold` 给的 inset 里顶部和左右那几份,**底部留给里面的滚动内容自己让**。
 *
 * 整份 `padding(insets)` 挂在列表外面,列表就在手势条上方被切断:滑到底碰不到屏幕下沿,
 * 滚动时内容也不从手势条底下经过。底部那份要由列表当内边距加在最后一项下面,见
 * [navigationBarsBottom]。
 *
 * 让掉的部分同时记为已消费,里面再量 inset 时不会把顶部算第二遍。
 */
@Composable
fun Modifier.padScaffoldExceptBottom(insets: PaddingValues): Modifier {
    val direction = LocalLayoutDirection.current
    val handled = PaddingValues(
        start = insets.calculateStartPadding(direction),
        top = insets.calculateTopPadding(),
        end = insets.calculateEndPadding(direction),
    )
    return padding(handled).consumeWindowInsets(handled)
}

/**
 * 列表底部要让出的手势条(导航栏)高度,加在列表的 `contentPadding` 里。外层已经让过并消费掉时
 * 量到 0,不会叠加。
 */
@Composable
fun navigationBarsBottom(): Dp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
