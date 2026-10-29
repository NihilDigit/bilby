package dev.bilby.ui.player

import dev.bilby.player.VideoDimensions

/**
 * 显示比例包含像素宽高比。Media3 的尺寸已应用旋转,mpv 的 dwidth/dheight 已包含显示比例,
 * 两端不再在这里旋转。未知尺寸返回 null,不能沿用另一条视频的比例。
 */
internal fun VideoDimensions.displayAspect(): Float? {
    if (width <= 0 || height <= 0) return null
    val par = pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
    return (width * par / height).takeIf { it.isFinite() && it > 0f }
}
