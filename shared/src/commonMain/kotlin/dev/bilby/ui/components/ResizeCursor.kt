package dev.bilby.ui.components

import androidx.compose.ui.Modifier

/**
 * 鼠标悬停时显示左右调整大小的光标,给可拖宽的分隔处用。Compose 公共代码里的 PointerIcon 只有
 * 默认、十字、文本、手型四种,调整大小的光标要从 AWT 取,所以分平台。Android 上不换:
 * 触屏没有悬停,接鼠标的平板也有拖动手柄本身的形状可认。
 */
expect fun Modifier.horizontalResizeCursor(): Modifier
