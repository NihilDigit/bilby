package dev.bilby.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

/**
 * 软键盘动画的终点。键盘升起或收起的那一刻它就已经是最终值,用来提前判断方向。
 * 桌面没有软键盘,恒为零。
 */
@get:Composable
expect val WindowInsets.Companion.imeTarget: WindowInsets

/** 软键盘此刻在不在屏上。桌面没有软键盘,恒为 false。 */
@get:Composable
expect val WindowInsets.Companion.imeVisible: Boolean

/**
 * 屏幕方向,只用作缓存的键(横竖屏下键盘高度不同)。Android 取 Configuration.orientation;
 * 桌面窗口没有方向,恒为同一个值。
 */
@Composable
expect fun screenOrientationKey(): Int
