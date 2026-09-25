package dev.bilby.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.Image
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap

actual fun ImageRequest.Builder.forSoftwareCanvas(): ImageRequest.Builder = allowHardware(false)

actual fun Image.toImageBitmap(): ImageBitmap = toBitmap().asImageBitmap()
