package dev.bilby.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.Image
import coil3.request.ImageRequest
import coil3.toBitmap

actual fun ImageRequest.Builder.forSoftwareCanvas(): ImageRequest.Builder = this

actual fun Image.toImageBitmap(): ImageBitmap = toBitmap().asComposeImageBitmap()
