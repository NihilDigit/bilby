package dev.bilby.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * 当前窗口是否处于前台可见状态。
 *
 * 桌面端在窗口失焦或最小化（且不处于画中画模式）时设为 `false`，供唱片旋转、波浪进度条、
 * 播放指示器、骨架屏脉动及轮询协程自动暂停，消除后台 GPU/CPU 空转；Android 端保持默认 `true`。
 */
val LocalWindowVisible = compositionLocalOf { true }
