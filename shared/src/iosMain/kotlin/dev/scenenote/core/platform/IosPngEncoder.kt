package dev.scenenote.core.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** Compose 位图 → PNG 字节（卡片导出）：Skia 编码，无损。 */
class IosPngEncoder : PngEncoder {
    override fun encode(bitmap: ImageBitmap): ByteArray =
        Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
}
