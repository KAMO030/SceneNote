package dev.scenenote.core.platform

/** 系统分享面板（AirDrop / Quick Share / 微信文件 都走它，07 篇：多设备只靠系统能力）。平台实现注入 Koin。 */
interface Sharer {
    /** 分享一段文字（Markdown 当纯文本）。 */
    fun shareText(text: String, title: String)
    /** 分享一个本机文件（.md / .voxnote / .png / .srt）。 */
    fun shareFile(path: String, mime: String, title: String)
}

/** 把 Compose 画出的位图编码成 PNG 字节（卡片 png 导出）。 */
interface PngEncoder {
    fun encode(bitmap: androidx.compose.ui.graphics.ImageBitmap): ByteArray
}

class NoopSharer : Sharer {
    override fun shareText(text: String, title: String) {}
    override fun shareFile(path: String, mime: String, title: String) {}
}
