package dev.scenenote.core.platform

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.ByteArrayOutputStream

/** Compose 位图 → PNG 字节（卡片导出）。PNG 无损，quality 参数被忽略。 */
class AndroidPngEncoder : PngEncoder {
    override fun encode(bitmap: ImageBitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}
