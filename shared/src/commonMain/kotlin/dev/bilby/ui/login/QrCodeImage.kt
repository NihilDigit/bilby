package dev.bilby.ui.login

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.IntSize
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.floor

/**
 * 用 zxing 把 [content] 编码成二维码并绘制。
 *
 * 颜色**故意写死为深码块 + 浅底**,不跟随动态取色:扫码器普遍只认这一种极性,暗色主题下
 * 取 onSurface/surface 会得到浅码块画在深底上,B 站客户端直接报"未成功解析到二维码"。
 * 静区(四周的浅色边)同理,是识别的一部分而不是留白。
 */
@Composable
fun QrCodeImage(content: String, modifier: Modifier = Modifier) {
    val bitmap = remember(content) { encodeQrBitmap(content, QR_FOREGROUND, QR_BACKGROUND) }
    Canvas(modifier = modifier) {
        val side = minOf(size.width, size.height)
        // 按整数倍缩放,避免二维码模块边缘落在半像素上产生摩尔纹。
        val scale = floor(side / bitmap.width).coerceAtLeast(1f)
        val drawSide = (bitmap.width * scale).toInt()
        drawImage(
            image = bitmap,
            dstSize = IntSize(drawSide, drawSide),
            dstOffset = androidx.compose.ui.unit.IntOffset(
                x = ((size.width - drawSide) / 2f).toInt(),
                y = ((size.height - drawSide) / 2f).toInt(),
            ),
            // 关闭双线性插值,否则模块边缘被平滑成灰阶,扫描器识别率下降。
            filterQuality = FilterQuality.None,
        )
    }
}

/** 纯黑纯白对比度最高;浅底不用纯白是为了在浅色主题下不至于和背景糊在一起。 */
private val QR_FOREGROUND = Color(0xFF000000)
private val QR_BACKGROUND = Color(0xFFFFFFFF)

private fun encodeQrBitmap(content: String, foreground: Color, background: Color): ImageBitmap {
    val hints = mapOf(
        // 4 个模块宽的静区是 QR 规范要求的下限,少了扫描器定位不到三个角。
        EncodeHintType.MARGIN to 4,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix: BitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    // 一个模块一个像素,放大交给绘制时的整数倍缩放。
    val bitmap = ImageBitmap(matrix.width, matrix.height)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    val paint = Paint()
    paint.color = background
    canvas.drawRect(0f, 0f, matrix.width.toFloat(), matrix.height.toFloat(), paint)
    paint.color = foreground
    for (y in 0 until matrix.height) {
        for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint)
        }
    }
    return bitmap
}
