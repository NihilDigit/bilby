package dev.bilby.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import coil3.Image
import coil3.request.ImageRequest

/**
 * 解码成能画进软件画布的位图。弹幕库在 CPU 上把图画进自己的位图图集,Android 的硬件位图
 * 画不进去;桌面没有硬件位图,原样返回。
 */
expect fun ImageRequest.Builder.forSoftwareCanvas(): ImageRequest.Builder

/** Coil 解出的图转成 Compose 的位图。两端的位图类型不同(Android Bitmap 与 Skia Bitmap)。 */
expect fun Image.toImageBitmap(): ImageBitmap
