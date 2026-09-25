package dev.bilby.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 画面上的光标藏不藏。只有桌面有光标;Android 上鼠标光标由系统管,原样返回。 */
internal expect fun Modifier.playerCursor(hidden: Boolean): Modifier

/** 挂着画面且在播放时不让屏幕息。桌面上由系统电源策略管,不做处理。 */
@Composable
internal expect fun KeepScreenOn(enabled: Boolean)

/**
 * 全屏的窗口一侧:Android 上是转到画面朝向并隐藏系统栏,离开组合时还原。
 * 页面一侧的布局(画面铺满)在 [PlayerShell] 里,两端相同。
 */
@Composable
internal expect fun FullscreenEffect(
    isFullscreen: Boolean,
    isPortraitVideo: Boolean,
    /** 见 [PlayerShell] 的同名参数:画面铺到边缘但没全屏。 */
    fullBleed: Boolean,
    /** 见 [PlayerShell] 的同名参数:两栏下把状态栏整条收起来。 */
    hideStatusBar: Boolean,
)
