package dev.bilby.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import dev.bilby.BiliLog
import dev.bilby.ui.theme.LocalIsDarkTheme
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle

/**
 * 带自绘标题栏的窗口内容:标题栏在上,[content] 占满其余部分。须放在 BilbyTheme 里,标题栏取
 * 主题的 surface 色,与顶栏、导航栏连成一片。
 *
 * 系统行为经 [WindowsCaption] 保留。接管失败时(拿不到 HWND 或 FFM 调用出错)系统标题栏还在,
 * 不再画第二条。
 *
 * [showTitleBar] 为 false 时(无边框全屏、小窗)不画标题栏,整个窗口交给内容。标题栏放在
 * [content] 之前的条件分支里,出入时 [content] 在组合里的位置不变,播放页不会因此重建。
 */
@Composable
internal fun FrameWindowScope.WindowFrame(
    title: String,
    icon: Painter?,
    showTitleBar: Boolean,
    content: @Composable () -> Unit,
) {
    // 在组合时就接管:此时窗口已有 HWND 而尚未显示,首帧就是去掉系统标题栏后的布局。
    val caption = remember(window) { WindowsCaption(window).takeIf { it.install() } }
    val dark = LocalIsDarkTheme.current
    // 用 DisposableEffect 而不是 LaunchedEffect:前者在组合提交时同步执行,赶在窗口首次显示之前;
    // 后者要等下一帧,窗口会先闪一下浅色边框。
    DisposableEffect(window, dark) {
        DwmWindowFrame.setDark(window, dark)
        onDispose {}
    }
    Column(Modifier.fillMaxSize()) {
        if (showTitleBar && caption != null) WindowsTitleBar(caption, title, icon)
        Box(Modifier.fillMaxWidth().weight(1f)) { content() }
    }
}

/** 与 Windows 11 标题栏按钮(46×32 epx)同高。 */
private val TitleBarHeight = 32.dp

@Composable
private fun WindowsTitleBar(caption: WindowsCaption, title: String, icon: Painter?) {
    // 各部分在根坐标系里的位置,每次变化都汇总成一份交给窗口过程做命中测试。只在布局回调里读写,
    // 不参与重组,所以不用 State。
    val bounds = remember { CaptionBounds() }
    fun publish() = caption.updateLayout(bounds.bar, bounds.buttons.toMap())
    // 标题栏撤下(进全屏、进小窗)时清掉布局,窗口过程不再把这块答成标题栏。
    DisposableEffect(caption) {
        onDispose { caption.clearLayout() }
    }

    val colors = MaterialTheme.colorScheme
    // Windows 11 的非活动窗口标题与按钮图标变灰。
    val contentColor = if (caption.isActive) colors.onSurface else colors.onSurface.copy(alpha = 0.45f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(colors.surface)
            .onGloballyPositioned {
                bounds.bar = it.boundsInWindow()
                publish()
            },
    ) {
        Spacer(Modifier.width(16.dp))
        if (icon != null) {
            Image(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = title,
            color = contentColor,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        for (button in WindowsCaption.Button.entries) {
            CaptionButton(
                button = button,
                isMaximized = caption.isMaximized,
                hovered = caption.hovered == button,
                pressed = caption.pressed == button && caption.hovered == button,
                contentColor = contentColor,
                modifier = Modifier.onGloballyPositioned {
                    bounds.buttons[button] = it.boundsInWindow()
                    publish()
                },
            )
        }
    }
}

private class CaptionBounds {
    var bar: Rect = Rect.Zero
    val buttons = mutableMapOf<WindowsCaption.Button, Rect>()
}

/**
 * 标题栏按钮。悬停与按下状态来自窗口过程而不是 Compose 的指针事件:这三块在系统看来是非客户区,
 * 鼠标消息不发给画布,见 [WindowsCaption]。
 *
 * 配色照 Windows 11 系统按钮,不取主题的 container 色:这三个按钮模仿的是系统外框,与相邻窗口的
 * 按钮一致比与应用内控件一致要紧。普通按钮悬停与按下各叠一层 6% 与 4% 的前景色,关闭按钮悬停为
 * 红底白字。
 */
@Composable
private fun CaptionButton(
    button: WindowsCaption.Button,
    isMaximized: Boolean,
    hovered: Boolean,
    pressed: Boolean,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val isClose = button == WindowsCaption.Button.CLOSE
    val background = when {
        isClose && pressed -> CloseRed.copy(alpha = 0.9f)
        isClose && hovered -> CloseRed
        pressed -> contentColor.copy(alpha = 0.04f)
        hovered -> contentColor.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    val glyphColor = when {
        isClose && pressed -> Color.White.copy(alpha = 0.7f)
        isClose && hovered -> Color.White
        pressed -> contentColor.copy(alpha = contentColor.alpha * 0.6f)
        else -> contentColor
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(46.dp, TitleBarHeight).background(background),
    ) {
        val glyph = when (button) {
            WindowsCaption.Button.MINIMIZE -> CaptionGlyph.Minimize
            WindowsCaption.Button.MAXIMIZE -> if (isMaximized) CaptionGlyph.Restore else CaptionGlyph.Maximize
            WindowsCaption.Button.CLOSE -> CaptionGlyph.Close
        }
        val iconFont = CaptionIconFont
        if (iconFont != null) {
            Text(glyph.char.toString(), color = glyphColor, fontFamily = iconFont, fontSize = 10.sp)
        } else {
            Icon(glyph.fallback, contentDescription = null, tint = glyphColor, modifier = Modifier.size(14.dp))
        }
    }
}

private val CloseRed = Color(0xFFC42B1C)

/** 码位在 Segoe Fluent Icons(Windows 11)与 Segoe MDL2 Assets(Windows 10)里相同。 */
private enum class CaptionGlyph(val char: Char, val fallback: ImageVector) {
    Minimize(Char(0xE921), Icons.Filled.Remove),
    Maximize(Char(0xE922), Icons.Filled.CropSquare),
    Restore(Char(0xE923), Icons.Filled.FilterNone),
    Close(Char(0xE8BB), Icons.Filled.Close),
}

/**
 * 系统标题栏按钮用的图标字体,与系统按钮的字形一致。两个都没有时用 Material 图标代替。
 * 按名字直接查 FontMgr:Compose 的 FontFamily(名字) 找不到字体时静默换成默认字体,私用区码位会画成方框。
 */
private val CaptionIconFont: FontFamily? by lazy {
    listOf("Segoe Fluent Icons", "Segoe MDL2 Assets").firstNotNullOfOrNull { name ->
        runCatching { FontMgr.default.matchFamilyStyle(name, FontStyle.NORMAL) }
            .onFailure { BiliLog.w("标题栏图标字体 $name 查找失败", it) }
            .getOrNull()
            ?.let { FontFamily(Typeface(it)) }
    }
}
